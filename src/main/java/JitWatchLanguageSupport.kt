package ru.yole.jitwatch

import com.intellij.lang.LanguageExtension
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.adoptopenjdk.jitwatch.model.IMetaMember

interface JitWatchLanguageSupport<ClassT : PsiElement, MethodT : PsiElement> {
    fun getAllClasses(file: PsiFile): List<ClassT>
    fun getAllMethods(cls: ClassT): List<MethodT>
    fun findMethodAtOffset(file: PsiFile, offset: Int): MethodT?
    fun getClassVMName(cls: ClassT): String?
    fun getContainingClass(method: MethodT): ClassT?
    fun matchesSignature(member: IMetaMember, method: MethodT): Boolean
    fun findCallToMember(file: PsiFile, offset:Int, calleeMember: IMetaMember): PsiElement?

}

val LanguageSupport = LanguageExtension<JitWatchLanguageSupport<PsiElement, PsiElement>>("ru.yole.jitwatch.languageSupport")
