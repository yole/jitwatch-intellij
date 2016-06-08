package ru.yole.jitwatch

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.util.containers.isNullOrEmpty
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.bytecode.*
import org.adoptopenjdk.jitwatch.util.ParseUtil

class JitSourceAnnotator : ExternalAnnotator<PsiClass, List<Pair<PsiElement, LineAnnotation>>>() {
    override fun collectInformation(file: PsiFile): PsiClass? {
        return (file as? PsiJavaFile)?.classes?.firstOrNull()
    }

    override fun doAnnotate(collectedInfo: PsiClass?): List<Pair<PsiElement, LineAnnotation>>? {
        if (collectedInfo == null) return null
        val service = JitWatchModelService.getInstance(collectedInfo.project)
        val (classBC, bytecodeAnnotations) = service.loadBytecode(collectedInfo) ?: return null
        return ApplicationManager.getApplication().runReadAction(Computable {
            mapBytecodeAnnotationsToSource(collectedInfo, classBC, bytecodeAnnotations)
        })
    }

    private fun mapBytecodeAnnotationsToSource(psiClass: PsiClass,
                                               classBC: ClassBC,
                                               bytecodeAnnotations: Map<IMetaMember, BytecodeAnnotations>): List<Pair<PsiElement, LineAnnotation>> {
        val result = mutableListOf<Pair<PsiElement, LineAnnotation>>()
        for (method in psiClass.methods + psiClass.constructors) {
            val member = bytecodeAnnotations.keys.find { it.matchesSignature(method) } ?: continue
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
        return result
    }

    private fun mapBytecodeAnnotationToSource(method: PsiMethod,
                                              member: IMetaMember,
                                              memberBytecode: MemberBytecode,
                                              instruction: BytecodeInstruction,
                                              annotation: LineAnnotation): PsiElement? {
        val sourceLine = memberBytecode.lineTable.findSourceLineForBytecodeOffset(instruction.offset)
        if (sourceLine == -1) return null

        val psiFile = method.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(method.project).getDocument(psiFile) ?: return null
        var lineStartOffset = document.getLineStartOffset(sourceLine - 1)
        while (lineStartOffset < document.textLength && psiFile.findElementAt(lineStartOffset) is PsiWhiteSpace) {
            lineStartOffset++
        }
        val statement = PsiTreeUtil.getParentOfType(psiFile.findElementAt(lineStartOffset), PsiStatement::class.java) ?: return null

        if (annotation.type == BCAnnotationType.INLINE_SUCCESS || annotation.type == BCAnnotationType.INLINE_FAIL) {
            val model = JitWatchModelService.getInstance(method.project).model
            val calleeMember = ParseUtil.getMemberFromBytecodeComment(model, member, instruction) ?: return null
            return findCallToMember(statement, calleeMember)
        }
        return null
    }

    private fun findCallToMember(context: PsiElement, calleeMember: IMetaMember): PsiCallExpression? {
        var result: PsiCallExpression? = null
        context.acceptChildren(object : JavaRecursiveElementVisitor() {
            override fun visitCallExpression(callExpression: PsiCallExpression) {
                super.visitCallExpression(callExpression)
                val method = callExpression.resolveMethod()
                if (method != null && calleeMember.matchesSignature(method)) {
                    result = callExpression
                }
            }
        })
        return result
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
