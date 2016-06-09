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
    fun findAllocation(file: PsiFile, offset:Int, jvmName: String): PsiElement?
}

object DefaultJitLanguageSupport : JitWatchLanguageSupport<PsiElement, PsiElement> {
    override fun getAllClasses(file: PsiFile) = emptyList<PsiElement>()
    override fun getAllMethods(cls: PsiElement) = emptyList<PsiElement>()
    override fun findMethodAtOffset(file: PsiFile, offset: Int) = null
    override fun getClassVMName(cls: PsiElement) = null
    override fun getContainingClass(method: PsiElement) = null
    override fun matchesSignature(member: IMetaMember, method: PsiElement) = false
    override fun findCallToMember(file: PsiFile, offset: Int, calleeMember: IMetaMember) = null
    override fun findAllocation(file: PsiFile, offset: Int, jvmName: String) = null
}

val LanguageSupport = LanguageExtension<JitWatchLanguageSupport<PsiElement, PsiElement>>(
        "ru.yole.jitwatch.languageSupport", DefaultJitLanguageSupport)

fun <T> LanguageExtension<T>.forElement(element: PsiElement) = forLanguage(element.language)
