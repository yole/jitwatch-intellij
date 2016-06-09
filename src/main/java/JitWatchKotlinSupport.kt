package ru.yole.jitwatch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.adoptopenjdk.jitwatch.model.MemberSignatureParts
import org.jetbrains.kotlin.codegen.ClassBuilderMode
import org.jetbrains.kotlin.codegen.state.IncompatibleClassTracker
import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.fileClasses.NoResolveFileClassesProvider
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

class JitWatchKotlinSupport : JitWatchLanguageSupport<KtClassOrObject, KtCallableDeclaration> {
    override fun getAllClasses(file: PsiFile): List<KtClassOrObject> =
            ApplicationManager.getApplication().runReadAction(Computable {
                PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java).toList()
            })

    override fun getAllMethods(cls: KtClassOrObject): List<KtCallableDeclaration> =
        cls.declarations.filterIsInstance<KtCallableDeclaration>()

    override fun isMethod(element: PsiElement) = element is KtFunction && element !is KtFunctionLiteral

    override fun findMethodAtOffset(file: PsiFile, offset: Int): KtFunction? =
        PsiTreeUtil.getParentOfType(file.findElementAt(offset), KtFunction::class.java)

    override fun getNameRange(element: PsiElement): TextRange = when(element) {
        is PsiNameIdentifierOwner -> element.nameIdentifier?.textRange ?: element.textRange
        else -> element.textRange
    }

    override fun getClassVMName(cls: KtClassOrObject): String? {
        val descriptor = cls.resolveToDescriptor() as? ClassDescriptor ?: return null
        return getClassDescriptorVMName(descriptor)
    }

    private fun getClassDescriptorVMName(descriptor: ClassDescriptor): String {
        val typeMapper = KotlinTypeMapper(BindingContext.EMPTY, ClassBuilderMode.LIGHT_CLASSES, NoResolveFileClassesProvider, null,
                IncompatibleClassTracker.DoNothing, JvmAbi.DEFAULT_MODULE_NAME)
        return typeMapper.mapClass(descriptor).internalName.replace('/', '.')
    }

    override fun getContainingClass(method: KtCallableDeclaration): KtClassOrObject? = method.containingClassOrObject

    override fun matchesSignature(method: KtCallableDeclaration,
                                  memberName: String,
                                  paramTypeNames: List<String>,
                                  returnTypeName: String): Boolean {
        val descriptor = method.resolveToDescriptor() as? FunctionDescriptor ?: return false
        return matchesSignature(descriptor, memberName, paramTypeNames, returnTypeName)
    }

    private fun matchesSignature(descriptor: FunctionDescriptor, memberName: String, paramTypeNames: List<String>, returnTypeName: String): Boolean {
        val typeMapper = KotlinTypeMapper(BindingContext.EMPTY, ClassBuilderMode.LIGHT_CLASSES, NoResolveFileClassesProvider, null,
                IncompatibleClassTracker.DoNothing, JvmAbi.DEFAULT_MODULE_NAME)
        val signature = typeMapper.mapAsmMethod(descriptor)

        val expectedName = if (descriptor is ConstructorDescriptor)
            DescriptorUtils.getFqNameFromTopLevelClass(descriptor.containingDeclaration).asString()
        else
            signature.name

        if (expectedName != memberName) return false
        if (signature.argumentTypes.size != paramTypeNames.size) return false
        val paramTypes = paramTypeNames zip signature.argumentTypes.map { it.className }
        if (paramTypes.any { it.first != it.second })
            return false
        if (returnTypeName != signature.returnType.className) {
            return false
        }
        return true
    }

    override fun findCallToMember(file: PsiFile, offset: Int, calleeMember: MemberSignatureParts, sameLineCallIndex: Int): PsiElement? {
        var result: PsiElement? = null
        var curIndex = 0
        processCalls(file, offset) { expression, resultingDescriptor ->
            val target = resultingDescriptor as? FunctionDescriptor ?: return@processCalls
            if (matchesSignature(target, calleeMember.memberName, calleeMember.paramTypes, calleeMember.returnType)) {
                if (curIndex == sameLineCallIndex) {
                    result = expression.calleeExpression
                }
                curIndex++
            }

        }
        return result
    }

    override fun findAllocation(file: PsiFile, offset: Int, jvmName: String): PsiElement? {
        var result: PsiElement? = null
        processCalls(file, offset) { expression, resultingDescriptor ->
            val target = resultingDescriptor as? ConstructorDescriptor ?: return@processCalls
            val createdClass = getClassDescriptorVMName(target.containingDeclaration)
            if (createdClass == jvmName) {
                result = expression.calleeExpression
            }
        }
        return result
    }

    private fun processCalls(file: PsiFile, offset: Int, callback: (KtCallExpression, CallableDescriptor) -> Unit) {
        val expr = file.findLargestExpressionAt(offset) ?: return
        expr.accept(object: KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                val bindingContext = expression.analyze(BodyResolveMode.PARTIAL)
                val resolvedCall = expression.getResolvedCall(bindingContext) ?: return

                callback(expression, resolvedCall.resultingDescriptor)
            }
        })
    }
}

private fun PsiFile.findLargestExpressionAt(offset: Int): KtExpression? {
    var element = PsiTreeUtil.getParentOfType(findElementAt(offset), KtExpression::class.java) ?: return null
    while (true) {
        val parent = PsiTreeUtil.getParentOfType(element, KtExpression::class.java)
        if (parent == null || parent.textRange.startOffset < offset) break
        element = parent
    }
    return element
}
