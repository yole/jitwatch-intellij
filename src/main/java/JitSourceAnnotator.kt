package ru.yole.jitwatch

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.ui.JBColor
import com.intellij.util.containers.isNullOrEmpty
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.bytecode.*
import org.adoptopenjdk.jitwatch.util.ParseUtil

class JitSourceAnnotator : ExternalAnnotator<PsiFile, List<Pair<PsiElement, LineAnnotation>>>() {
    override fun collectInformation(file: PsiFile): PsiFile? {
        return file
    }

    override fun doAnnotate(psiFile: PsiFile?): List<Pair<PsiElement, LineAnnotation>>? {
        if (psiFile == null) return null
        val service = JitWatchModelService.getInstance(psiFile.project)
        val languageSupport = LanguageSupport.forLanguage(psiFile.language) ?: return emptyList()
        val cls = languageSupport.getAllClasses(psiFile).firstOrNull() ?: return emptyList()

        val (classBC, bytecodeAnnotations) = service.loadBytecode(cls) ?: return null
        return ApplicationManager.getApplication().runReadAction(Computable {
            mapBytecodeAnnotationsToSource(psiFile, classBC, bytecodeAnnotations)
        })
    }

    private fun mapBytecodeAnnotationsToSource(psiFile: PsiFile,
                                               classBC: ClassBC,
                                               bytecodeAnnotations: Map<IMetaMember, BytecodeAnnotations>): List<Pair<PsiElement, LineAnnotation>> {
        val result = mutableListOf<Pair<PsiElement, LineAnnotation>>()
        val languageSupport = LanguageSupport.forLanguage(psiFile.language) ?: return emptyList()

        for (cls in languageSupport.getAllClasses(psiFile)) {
            for (method in languageSupport.getAllMethods(cls)) {
                val member = bytecodeAnnotations.keys.find { languageSupport.matchesSignature(it, method) } ?: continue
                val annotations = bytecodeAnnotations[member] ?: continue
                val memberBytecode = classBC.getMemberBytecode(member) ?: continue
                for (instruction in memberBytecode.instructions) {
                    val annotationsForBCI = annotations.getAnnotationsForBCI(instruction.offset)
                    if (annotationsForBCI.isNullOrEmpty()) continue
                    for (lineAnnotation in annotationsForBCI) {
                        val sourceElement = mapBytecodeAnnotationToSource(method, member, memberBytecode, instruction, lineAnnotation)
                        if (sourceElement != null) {
                            result.add(sourceElement to lineAnnotation)
                        }
                    }
                }
            }
        }

        return result
    }

    private fun mapBytecodeAnnotationToSource(method: PsiElement,
                                              member: IMetaMember,
                                              memberBytecode: MemberBytecode,
                                              instruction: BytecodeInstruction,
                                              annotation: LineAnnotation): PsiElement? {
        val languageSupport = LanguageSupport.forLanguage(method.language) ?: return null

        val sourceLine = memberBytecode.lineTable.findSourceLineForBytecodeOffset(instruction.offset)
        if (sourceLine == -1) return null

        val psiFile = method.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(method.project).getDocument(psiFile) ?: return null
        var lineStartOffset = document.getLineStartOffset(sourceLine - 1)
        while (lineStartOffset < document.textLength && psiFile.findElementAt(lineStartOffset) is PsiWhiteSpace) {
            lineStartOffset++
        }

        if (annotation.type == BCAnnotationType.INLINE_SUCCESS || annotation.type == BCAnnotationType.INLINE_FAIL) {
            val model = JitWatchModelService.getInstance(method.project).model
            val calleeMember = ParseUtil.getMemberFromBytecodeComment(model, member, instruction) ?: return null
            return languageSupport.findCallToMember(psiFile, lineStartOffset, calleeMember)
        }
        return null
    }

    override fun apply(file: PsiFile, annotationResult: List<Pair<PsiElement, LineAnnotation>>?, holder: AnnotationHolder) {
        if (annotationResult == null) return

        for (pair in annotationResult) {
            applyAnnotation(pair.first, pair.second, holder)
        }
    }

    private fun applyAnnotation(element: PsiElement, lineAnnotation: LineAnnotation, holder: AnnotationHolder) {
        when (lineAnnotation.type) {
            BCAnnotationType.INLINE_SUCCESS -> holder.createInfoAnnotation(element, lineAnnotation.annotation)
                .enforcedTextAttributes = underline(JBColor.GREEN)

            BCAnnotationType.INLINE_FAIL -> holder.createInfoAnnotation(element, lineAnnotation.annotation)
                    .enforcedTextAttributes = underline(JBColor.RED)
        }
    }

    private fun underline(color: JBColor?) = TextAttributes(null, null, color, EffectType.LINE_UNDERSCORE, 0)
}
