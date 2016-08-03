package ru.yole.jitwatch

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.ui.JBColor
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.MemberSignatureParts
import org.adoptopenjdk.jitwatch.model.bytecode.BCAnnotationType
import org.adoptopenjdk.jitwatch.model.bytecode.BytecodeInstruction
import org.adoptopenjdk.jitwatch.model.bytecode.LineAnnotation
import org.adoptopenjdk.jitwatch.model.bytecode.MemberBytecode
import org.adoptopenjdk.jitwatch.util.ParseUtil
import ru.yole.jitwatch.languages.LanguageSupport
import java.awt.Color

class JitSourceAnnotator : ExternalAnnotator<PsiFile, List<Pair<PsiElement, LineAnnotation>>>() {
    override fun collectInformation(file: PsiFile): PsiFile? {
        return file
    }

    override fun doAnnotate(psiFile: PsiFile?): List<Pair<PsiElement, LineAnnotation>>? {
        if (psiFile == null) return null
        val service = JitWatchModelService.getInstance(psiFile.project)
        if (service.model == null) return null
        service.loadBytecode(psiFile)
        return ApplicationManager.getApplication().runReadAction(Computable {
            mapBytecodeAnnotationsToSource(psiFile)
        })
    }

    private fun mapBytecodeAnnotationsToSource(psiFile: PsiFile): List<Pair<PsiElement, LineAnnotation>> {
        val result = mutableListOf<Pair<PsiElement, LineAnnotation>>()
        val modelService = JitWatchModelService.getInstance(psiFile.project)

        modelService.processBytecodeAnnotations(psiFile) { method, member, memberBytecode, instruction, lineAnnotations ->
            for (lineAnnotation in lineAnnotations) {
                val sourceElement = mapBytecodeAnnotationToSource(method, member, memberBytecode, instruction, lineAnnotation) ?: continue
                result.add(sourceElement to lineAnnotation)
            }
        }

        return result
    }

    private fun mapBytecodeAnnotationToSource(method: PsiElement,
                                              member: IMetaMember,
                                              memberBytecode: MemberBytecode,
                                              instruction: BytecodeInstruction,
                                              lineAnnotation: LineAnnotation): PsiElement? {
        val languageSupport = LanguageSupport.forLanguage(method.language)

        val sourceLine = memberBytecode.lineTable.findSourceLineForBytecodeOffset(instruction.offset)
        if (sourceLine == -1) return null

        val psiFile = method.containingFile ?: return null
        val lineStartOffset = psiFile.findLineStart(sourceLine) ?: return null

        return when (lineAnnotation.type) {
            BCAnnotationType.INLINE_SUCCESS, BCAnnotationType.INLINE_FAIL -> {
                val index = findSameLineCallIndex(memberBytecode, sourceLine, instruction)
                val calleeMember = getMemberSignatureFromBytecodeComment(member, instruction)
                if (calleeMember == null) {
                    LOG.info("Can't find callee by comment: " + instruction.comment)
                    return null
                }
                languageSupport.findCallToMember(psiFile, lineStartOffset, calleeMember, index)
            }

            BCAnnotationType.ELIMINATED_ALLOCATION -> {
                val comment = instruction.comment.removePrefix("// class ")
                languageSupport.findAllocation(psiFile, lineStartOffset, comment)
            }

            else -> null
        }
    }

    private fun getMemberSignatureFromBytecodeComment(currentMember: IMetaMember,
                                                      instruction: BytecodeInstruction): MemberSignatureParts? {
        var comment: String? = instruction.commentWithMemberPrefixStripped ?: return null

        if (ParseUtil.bytecodeMethodCommentHasNoClassPrefix(comment)) {
            val currentClass = currentMember.metaClass.fullyQualifiedName.replace('.', '/')
            comment = currentClass + "." + comment
        }

        return MemberSignatureParts.fromBytecodeComment(comment)
    }

    private fun findSameLineCallIndex(memberBytecode: MemberBytecode,
                                      sourceLine: Int,
                                      invokeInstruction: BytecodeInstruction): Int {
        var result = -1
        val sameLineInstructions = memberBytecode.findInstructionsForSourceLine(sourceLine)
        for (instruction in sameLineInstructions) {
            if (instruction.opcode == invokeInstruction.opcode && instruction.comment == invokeInstruction.comment) {
                result++
            }
            if (instruction == invokeInstruction) {
                break
            }
        }
        return result.coerceAtLeast(0)
    }

    private fun PsiFile.findLineStart(sourceLine: Int): Int? {
        val document = PsiDocumentManager.getInstance(project).getDocument(this) ?: return null
        val adjustedLine = sourceLine - 1
        if (adjustedLine >= document.lineCount) return null
        var lineStartOffset = document.getLineStartOffset(adjustedLine)
        while (lineStartOffset < document.textLength && findElementAt(lineStartOffset) is PsiWhiteSpace) {
            lineStartOffset++
        }
        return lineStartOffset
    }

    override fun apply(file: PsiFile, annotationResult: List<Pair<PsiElement, LineAnnotation>>?, holder: AnnotationHolder) {
        if (annotationResult == null) return

        for (pair in annotationResult) {
            applyAnnotation(pair.first, pair.second, holder)
        }
    }

    private fun applyAnnotation(element: PsiElement, lineAnnotation: LineAnnotation, holder: AnnotationHolder) {
        val annotation = holder.createInfoAnnotation(element, lineAnnotation.annotation)
        val color: Color? = when (lineAnnotation.type) {
            BCAnnotationType.INLINE_SUCCESS, BCAnnotationType.ELIMINATED_ALLOCATION -> JBColor.GREEN

            BCAnnotationType.INLINE_FAIL -> JBColor.RED

            else -> null
        }

        if (color != null) {
            annotation.enforcedTextAttributes = underline(color)
        }
    }

    private fun underline(color: Color) = TextAttributes(null, null, color, EffectType.LINE_UNDERSCORE, 0)

    companion object {
        val LOG = Logger.getInstance(JitSourceAnnotator::class.java)
    }
}
