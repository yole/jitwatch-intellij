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
import java.awt.Color

class JitSourceAnnotator : ExternalAnnotator<PsiFile, List<Pair<PsiElement, List<LineAnnotation>>>>() {
    override fun collectInformation(file: PsiFile): PsiFile? {
        return file
    }

    override fun doAnnotate(psiFile: PsiFile?): List<Pair<PsiElement, List<LineAnnotation>>>? {
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
                                               bytecodeAnnotations: Map<IMetaMember, BytecodeAnnotations>): List<Pair<PsiElement, List<LineAnnotation>>> {
        val result = mutableListOf<Pair<PsiElement, List<LineAnnotation>>>()
        val languageSupport = LanguageSupport.forLanguage(psiFile.language) ?: return emptyList()

        for (cls in languageSupport.getAllClasses(psiFile)) {
            for (method in languageSupport.getAllMethods(cls)) {
                val member = bytecodeAnnotations.keys.find { languageSupport.matchesSignature(it, method) } ?: continue
                val annotations = bytecodeAnnotations[member] ?: continue
                val memberBytecode = classBC.getMemberBytecode(member) ?: continue
                for (instruction in memberBytecode.instructions) {
                    val annotationsForBCI = annotations.getAnnotationsForBCI(instruction.offset)
                    if (annotationsForBCI.isNullOrEmpty()) continue

                    val elementToAnnotationsMap = mutableMapOf<PsiElement, MutableList<LineAnnotation>>()
                    for (lineAnnotation in annotationsForBCI) {
                        val sourceElement = mapBytecodeAnnotationToSource(method, member, memberBytecode, instruction, lineAnnotation)
                        if (sourceElement != null) {
                            val list = elementToAnnotationsMap.getOrPut(sourceElement) { mutableListOf<LineAnnotation>() }
                            list.add(lineAnnotation)
                        }
                    }
                    elementToAnnotationsMap.mapTo(result) { it.key to it.value }
                }
            }
        }

        return result
    }

    private fun mapBytecodeAnnotationToSource(method: PsiElement,
                                              member: IMetaMember,
                                              memberBytecode: MemberBytecode,
                                              instruction: BytecodeInstruction,
                                              lineAnnotation: LineAnnotation): PsiElement? {
        val languageSupport = LanguageSupport.forLanguage(method.language) ?: return null

        val sourceLine = memberBytecode.lineTable.findSourceLineForBytecodeOffset(instruction.offset)
        if (sourceLine == -1) return null

        val psiFile = method.containingFile ?: return null
        val lineStartOffset = psiFile.findLineStart(sourceLine) ?: return null

        return when (lineAnnotation.type) {
            BCAnnotationType.INLINE_SUCCESS, BCAnnotationType.INLINE_FAIL -> {
                val model = JitWatchModelService.getInstance(method.project).model
                val calleeMember = ParseUtil.getMemberFromBytecodeComment(model, member, instruction) ?: return null
                languageSupport.findCallToMember(psiFile, lineStartOffset, calleeMember)
            }

            BCAnnotationType.ELIMINATED_ALLOCATION -> {
                val comment = instruction.comment.removePrefix("// class ")
                languageSupport.findAllocation(psiFile, lineStartOffset, comment)
            }

            else -> null
        }
    }

    private fun PsiFile.findLineStart(sourceLine: Int): Int? {
        val document = PsiDocumentManager.getInstance(project).getDocument(this) ?: return null
        var lineStartOffset = document.getLineStartOffset(sourceLine - 1)
        while (lineStartOffset < document.textLength && findElementAt(lineStartOffset) is PsiWhiteSpace) {
            lineStartOffset++
        }
        return lineStartOffset
    }

    override fun apply(file: PsiFile, annotationResult: List<Pair<PsiElement, List<LineAnnotation>>>?, holder: AnnotationHolder) {
        if (annotationResult == null) return

        for (pair in annotationResult) {
            applyAnnotation(pair.first, pair.second, holder)
        }
    }

    private fun applyAnnotation(element: PsiElement, lineAnnotations: List<LineAnnotation>, holder: AnnotationHolder) {
        val annotation = holder.createInfoAnnotation(element, lineAnnotations.joinToString(separator = "\n") { it.annotation })
        var color: Color? = null
        if (lineAnnotations.any { it.type == BCAnnotationType.INLINE_FAIL }) {
            color = JBColor.RED
        }
        else if (lineAnnotations.any { it.type == BCAnnotationType.INLINE_SUCCESS || it.type == BCAnnotationType.ELIMINATED_ALLOCATION }) {
            color = JBColor.GREEN
        }
        if (color != null) {
            annotation.enforcedTextAttributes = underline(color)
        }
    }

    private fun underline(color: Color) = TextAttributes(null, null, color, EffectType.LINE_UNDERSCORE, 0)
}
