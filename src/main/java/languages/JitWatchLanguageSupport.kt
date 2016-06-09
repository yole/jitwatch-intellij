package ru.yole.jitwatch.languages

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.MemberSignatureParts
import org.adoptopenjdk.jitwatch.model.MetaClass

interface JitWatchLanguageSupport<ClassT : PsiElement, MethodT : PsiElement> {
    fun getAllClasses(file: PsiFile): List<ClassT>
    fun findClass(project: Project, metaClass: MetaClass): ClassT?
    fun getAllMethods(cls: ClassT): List<MethodT>
    fun isMethod(element: PsiElement): Boolean
    fun findMethodAtOffset(file: PsiFile, offset: Int): MethodT?
    fun getNameRange(element: PsiElement): TextRange
    fun getClassVMName(cls: ClassT): String?
    fun getContainingClass(method: MethodT): ClassT?
    fun matchesSignature(method: MethodT, memberName: String, paramTypeNames: List<String>, returnTypeName: String): Boolean
    fun findCallToMember(file: PsiFile, offset: Int, calleeMember: MemberSignatureParts, sameLineCallIndex: Int): PsiElement?
    fun findAllocation(file: PsiFile, offset:Int, jvmName: String): PsiElement?
}

object DefaultJitLanguageSupport : JitWatchLanguageSupport<PsiElement, PsiElement> {
    override fun getAllClasses(file: PsiFile) = emptyList<PsiElement>()
    override fun findClass(project: Project, metaClass: MetaClass) = null
    override fun getAllMethods(cls: PsiElement) = emptyList<PsiElement>()
    override fun isMethod(element: PsiElement) = false
    override fun findMethodAtOffset(file: PsiFile, offset: Int) = null
    override fun getNameRange(element: PsiElement) = element.textRange
    override fun getClassVMName(cls: PsiElement) = null
    override fun getContainingClass(method: PsiElement) = null
    override fun matchesSignature(method: PsiElement, memberName: String, paramTypeNames: List<String>, returnTypeName: String) = false
    override fun findCallToMember(file: PsiFile, offset: Int, calleeMember: MemberSignatureParts, sameLineCallIndex: Int) = null
    override fun findAllocation(file: PsiFile, offset: Int, jvmName: String) = null
}

private val EP_NAME = "ru.yole.jitwatch.languageSupport"

val LanguageSupport = LanguageExtension<JitWatchLanguageSupport<PsiElement, PsiElement>>(
        EP_NAME, DefaultJitLanguageSupport)

fun <T> LanguageExtension<T>.forElement(element: PsiElement) = forLanguage(element.language)

fun <CT : PsiElement, MT : PsiElement>
        JitWatchLanguageSupport<CT, MT>.matchesSignature(method: MT, metaMember: IMetaMember): Boolean {
    return matchesSignature(method, metaMember.memberName, metaMember.paramTypeNames.toList(), metaMember.returnTypeName)
}

fun <CT : PsiElement, MT : PsiElement>
        JitWatchLanguageSupport<CT, MT>.matchesSignature(method: MT, signature: MemberSignatureParts): Boolean {
    return matchesSignature(method, signature.memberName, signature.paramTypes.toList(), signature.returnType)
}

fun PsiElement.matchesSignature(metaMember: IMetaMember) = LanguageSupport.forElement(this).matchesSignature(this, metaMember)

fun getAllSupportedLanguages(): List<JitWatchLanguageSupport<PsiElement, PsiElement>> {
    val extensions = Extensions.getExtensions(EP_NAME)
    val supportedLanguageNames = extensions.map { (it as LanguageExtensionPoint<*>).language }
    val supportedLanguages = supportedLanguageNames.map { Language.findLanguageByID(it)}.filterNotNull()
    return supportedLanguages.map { LanguageSupport.forLanguage(it) }
}