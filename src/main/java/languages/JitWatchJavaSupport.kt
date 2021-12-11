package ru.yole.jitwatch.languages

import com.intellij.debugger.engine.JVMNameUtil
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.ClassUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import org.adoptopenjdk.jitwatch.model.MemberSignatureParts
import org.adoptopenjdk.jitwatch.model.MetaClass

class JitWatchJavaSupport : JitWatchLanguageSupport<PsiClass, PsiMethod> {
    override fun getAllClasses(file: PsiFile): List<PsiClass> =
        ApplicationManager.getApplication().runReadAction(Computable {
            PsiTreeUtil.collectElementsOfType(file, PsiClass::class.java).toList()
        })

    override fun findClass(project: Project, metaClass: MetaClass): PsiClass? {
        val psiClass = JavaPsiFacade.getInstance(project).findClass(metaClass.fullyQualifiedName, ProjectScope.getAllScope(project))
        return if (psiClass?.language == JavaLanguage.INSTANCE) psiClass else null
    }

    override fun getAllMethods(cls: PsiClass): List<PsiMethod> = (cls.methods + cls.constructors).toList()

    override fun isMethod(element: PsiElement): Boolean = element is PsiMethod

    override fun findMethodAtOffset(file: PsiFile, offset: Int): PsiMethod? =
        PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiMethod::class.java)

    override fun getNameRange(element: PsiElement): TextRange = when(element) {
        is PsiNameIdentifierOwner -> element.nameIdentifier?.textRange ?: element.textRange
        else -> element.textRange
    }

    override fun getClassVMName(cls: PsiClass): String?  = JVMNameUtil.getClassVMName(cls)

    override fun getContainingClass(method: PsiMethod): PsiClass? = method.containingClass

    override fun matchesSignature(method: PsiMethod, memberName: String, paramTypeNames: List<String>, returnTypeName: String): Boolean {
        val psiMethodName = if (method.isConstructor)
            JVMNameUtil.getClassVMName(method.containingClass)?.substringAfterLast('.') ?: method.name
        else
            method.name
        if (memberName != psiMethodName) return false

        if (paramTypeNames.size != method.parameterList.parametersCount) return false
        val paramTypes = paramTypeNames zip method.parameterList.parameters.map { it.type.jvmText() }
        if (paramTypes.any { it.first != it.second})
            return false

        val psiMethodReturnTypeName = if (method.isConstructor) "void" else method.returnType?.jvmText() ?: ""
        if (returnTypeName != psiMethodReturnTypeName)
            return false

        return true
    }

    private fun PsiType.jvmText(): String {
        val erasedType = TypeConversionUtil.erasure(this)
        if (erasedType is PsiClassType) {
            val psiClass = erasedType.resolve()
            if (psiClass != null) {
                val vmName = JVMNameUtil.getClassVMName(psiClass)
                if (vmName != null) {
                    return vmName
                }
            }
        }
        return erasedType.canonicalText
    }

    override fun findCallToMember(file: PsiFile, offset: Int, calleeMember: MemberSignatureParts, sameLineCallIndex: Int): PsiElement? {
        val statement = findStatement(file, offset) ?: return null
        var result: PsiElement? = null
        var curIndex = 0
        statement.acceptChildren(object : JavaRecursiveElementVisitor() {
            override fun visitCallExpression(callExpression: PsiCallExpression) {
                super.visitCallExpression(callExpression)
                val method = callExpression.resolveMethod()
                if (method != null && matchesSignature(method, calleeMember)) {
                    if (curIndex == sameLineCallIndex) {
                        result = when (callExpression) {
                            is PsiMethodCallExpression -> callExpression.methodExpression
                            is PsiNewExpression -> callExpression.classReference
                            else -> callExpression
                        }
                    }
                    curIndex++
                }
            }
        })
        return result
    }

    override fun findAllocation(file: PsiFile, offset: Int, jvmName: String): PsiElement? {
        val expectedClass = ClassUtil.findPsiClassByJVMName(file.manager, jvmName) ?: return null
        val statement = findStatement(file, offset) ?: return null
        var result: PsiElement? = null
        statement.acceptChildren(object : JavaRecursiveElementVisitor() {
            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val createdClass = expression.resolveConstructor()?.containingClass
                if (createdClass?.isEquivalentTo(expectedClass) == true) {
                    result = expression.node.findChildByType(JavaTokenType.NEW_KEYWORD)?.psi ?: expression
                }
            }
        })
        return result
    }

    private fun findStatement(file: PsiFile, offset: Int) =
            PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiStatement::class.java)

}
