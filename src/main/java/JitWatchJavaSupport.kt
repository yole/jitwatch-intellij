package ru.yole.jitwatch

import com.intellij.debugger.engine.JVMNameUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.adoptopenjdk.jitwatch.model.IMetaMember

class JitWatchJavaSupport : JitWatchLanguageSupport<PsiClass, PsiMethod> {
    override fun getAllClasses(file: PsiFile): List<PsiClass> =
        ApplicationManager.getApplication().runReadAction(Computable { (file as? PsiJavaFile)?.classes?.toList().orEmpty() })

    override fun getAllMethods(cls: PsiClass): List<PsiMethod> = (cls.methods + cls.constructors).toList()

    override fun findMethodAtOffset(file: PsiFile, offset: Int): PsiMethod? =
        PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiMethod::class.java)

    override fun getClassVMName(cls: PsiClass): String?  = JVMNameUtil.getClassVMName(cls)

    override fun getContainingClass(method: PsiMethod): PsiClass? = method.containingClass

    override fun matchesSignature(member: IMetaMember, method: PsiMethod): Boolean {
        if (member.memberName != method.name) return false
        val paramTypes = member.paramTypeNames zip method.parameterList.parameters.map { it.type.canonicalText }
        if (paramTypes.any { it.first != it.second})
            return false
        val psiMethodReturnTypeName = if (method.isConstructor) "void" else method.returnType?.canonicalText ?: ""
        if (member.returnTypeName != psiMethodReturnTypeName)
            return false
        return true
    }

    override fun findCallToMember(psiFile: PsiFile, offset: Int, calleeMember: IMetaMember): PsiElement? {
        val statement = PsiTreeUtil.getParentOfType(psiFile.findElementAt(offset), PsiStatement::class.java) ?: return null
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

}
