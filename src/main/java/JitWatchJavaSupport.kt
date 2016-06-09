package ru.yole.jitwatch

import com.intellij.debugger.engine.JVMNameUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.util.ClassUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import org.adoptopenjdk.jitwatch.model.IMetaMember

class JitWatchJavaSupport : JitWatchLanguageSupport<PsiClass, PsiMethod> {
    override fun getAllClasses(file: PsiFile): List<PsiClass> =
        ApplicationManager.getApplication().runReadAction(Computable {
            PsiTreeUtil.collectElementsOfType(file, PsiClass::class.java).toList()
        })

    override fun getAllMethods(cls: PsiClass): List<PsiMethod> = (cls.methods + cls.constructors).toList()

    override fun findMethodAtOffset(file: PsiFile, offset: Int): PsiMethod? =
        PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiMethod::class.java)

    override fun getClassVMName(cls: PsiClass): String?  = JVMNameUtil.getClassVMName(cls)

    override fun getContainingClass(method: PsiMethod): PsiClass? = method.containingClass

    override fun matchesSignature(member: IMetaMember, method: PsiMethod): Boolean {
        val psiMethodName = if (method.isConstructor)
            JVMNameUtil.getClassVMName(method.containingClass)?.substringAfterLast('.') ?: method.name
        else
            method.name
        if (member.memberName != psiMethodName) return false

        val paramTypes = member.paramTypeNames zip method.parameterList.parameters.map { it.type.jvmText() }
        if (paramTypes.any { it.first != it.second})
            return false

        val psiMethodReturnTypeName = if (method.isConstructor) "void" else method.returnType?.jvmText() ?: ""
        if (member.returnTypeName != psiMethodReturnTypeName)
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

    override fun findCallToMember(file: PsiFile, offset: Int, calleeMember: IMetaMember): PsiElement? {
        val statement = findStatement(file, offset) ?: return null
        var result: PsiCallExpression? = null
        statement.acceptChildren(object : JavaRecursiveElementVisitor() {
            override fun visitCallExpression(callExpression: PsiCallExpression) {
                super.visitCallExpression(callExpression)
                val method = callExpression.resolveMethod()
                if (method != null && matchesSignature(calleeMember, method)) {
                    result = callExpression
                }
            }
        })
        return result
    }

    override fun findAllocation(file: PsiFile, offset: Int, jvmName: String): PsiElement? {
        val expectedClass = ClassUtil.findPsiClassByJVMName(file.manager, jvmName) ?: return null
        val statement = findStatement(file, offset) ?: return null
        var result: PsiNewExpression? = null
        statement.acceptChildren(object : JavaRecursiveElementVisitor() {
            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val createdClass = expression.resolveConstructor()?.containingClass
                if (createdClass?.isEquivalentTo(expectedClass) == true) {
                    result = expression
                }
            }
        })
        return result
    }

    private fun findStatement(file: PsiFile, offset: Int) =
            PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiStatement::class.java)

}
