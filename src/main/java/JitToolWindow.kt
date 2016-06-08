package ru.yole.jitwatch

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Result
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretAdapter
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.util.containers.isNullOrEmpty
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.MetaClass
import org.adoptopenjdk.jitwatch.model.bytecode.BCAnnotationType
import org.adoptopenjdk.jitwatch.model.bytecode.BytecodeAnnotations
import java.awt.CardLayout
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.jvm.isAccessible

class JitToolWindow(private val project: Project) : JPanel(CardLayout()), Disposable {
    private val MESSAGE_CARD = "Message"
    private val EDITOR_CARD = "Editor"

    private val messageLabel = JLabel()
    private val cardLayout = layout as CardLayout
    private val bytecodeDocument = EditorFactory.getInstance().createDocument("")
    private val bytecodeEditor = EditorFactory.getInstance().createEditor(bytecodeDocument, project, PlainTextFileType.INSTANCE, true).apply {
        settings.isLineNumbersShown = false
        settings.isFoldingOutlineShown = false
        settings.isLineMarkerAreaShown = false
    }

    private val modelService = JitWatchModelService.getInstance(project)

    private var bytecodeTextBuilder: BytecodeTextBuilder? = null
    private var activeSourceEditor: Editor? = null
    private var activeSourceFile: PsiFile? = null

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerAdapter() {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                updateContent(event.newFile, event.newEditor)
            }
        })

        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretAdapter() {
            override fun caretPositionChanged(e: CaretEvent) {
                if (e.editor != activeSourceEditor) return
                syncBytecodeToEditor(e.newPosition)
            }
        }, this)

        setupUI()
        val fileEditorManager = FileEditorManager.getInstance(project)
        updateContent(fileEditorManager.selectedFiles.firstOrNull(), fileEditorManager.selectedEditors.firstOrNull(), true)
    }

    private fun setupUI() {
        add(messageLabel, MESSAGE_CARD)
        add(bytecodeEditor.component, EDITOR_CARD)
    }

    private fun updateContent(file: VirtualFile?, fileEditor: FileEditor?, syncCaret: Boolean = false) {
        activeSourceEditor = (fileEditor as? TextEditor)?.editor
            ?: return showMessage("Please open a text editor")

        if (modelService.model == null)
            return showMessage("Please open a HotSpot compilation log")

        activeSourceFile = PsiManager.getInstance(project).findFile(file!!)
            ?: return showMessage("Please select a source file")
        val languageSupport = LanguageSupport.forLanguage(activeSourceFile!!.language)
            ?: return showMessage("Please select a file in a supported language")

        val psiClass = languageSupport.getAllClasses(activeSourceFile!!).firstOrNull()
            ?: return showMessage("Please select a Java file that contains classes")

        modelService.loadBytecodeAsync(psiClass) { classBC, annotationsMap ->
            if (classBC != null && annotationsMap != null) {
                showBytecode(modelService.getMetaClass(psiClass)!!, annotationsMap)
                if (syncCaret) {
                    syncBytecodeToEditor(activeSourceEditor!!.caretModel.logicalPosition)
                }
            }
            else {
                showMessage("Couldn't load bytecode for selected class")
            }
        }
    }

    private fun showMessage(message: String) {
        messageLabel.text = message
        cardLayout.show(this, MESSAGE_CARD)
    }

    private fun showBytecode(metaClass: MetaClass, annotationsMap: Map<IMetaMember, BytecodeAnnotations>) {
        cardLayout.show(this, EDITOR_CARD)

        bytecodeTextBuilder = BytecodeTextBuilder(metaClass)
        object : WriteCommandAction<Unit>(project) {
            override fun run(result: Result<Unit>) {
                bytecodeDocument.replaceString(0, bytecodeDocument.textLength, bytecodeTextBuilder!!.text)
            }
        }.execute()

        renderBytecodeAnnotations(metaClass, annotationsMap)
    }

    private fun renderBytecodeAnnotations(metaClass: MetaClass,
                                          annotationsMap: Map<IMetaMember, BytecodeAnnotations>) {
        val markupModel = DocumentMarkupModel.forDocument(bytecodeDocument, project, true)
        markupModel.removeAllHighlighters()

        for (member in metaClass.metaMembers) {
            val annotations = annotationsMap[member] ?: continue
            for (instruction in member.memberBytecode.instructions) {
                val annotationsForBCI = annotations.getAnnotationsForBCI(instruction.offset)
                if (annotationsForBCI.isNullOrEmpty()) continue
                val line = bytecodeTextBuilder!!.findLine(member, instruction.offset) ?: continue
                val color = getColorForBytecodeAnnotation(annotationsForBCI.first().type)
                highlightBytecodeLine(line, color,
                        annotationsForBCI.joinToString(separator = "\n") { it.annotation },
                        markupModel)
            }
        }
    }

    private fun getColorForBytecodeAnnotation(type: BCAnnotationType): Color? = when(type) {
        BCAnnotationType.BRANCH -> JBColor.BLUE
        BCAnnotationType.ELIMINATED_ALLOCATION -> JBColor.GRAY
        BCAnnotationType.INLINE_FAIL -> JBColor.RED
        BCAnnotationType.INLINE_SUCCESS -> JBColor.GREEN.darker().darker()
        BCAnnotationType.UNCOMMON_TRAP -> JBColor.MAGENTA
        else -> null
    }

    private fun highlightBytecodeLine(line: Int, color: Color?, tooltip: String, markupModel: MarkupModel) {
        val document = bytecodeEditor.document
        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val textAttributes = TextAttributes(color, null, null, null, 0)
        val highlighter = markupModel.addRangeHighlighter(
                (lineStartOffset + 4).coerceAtMost(lineEndOffset),
                lineEndOffset,
                HighlighterLayer.SYNTAX,
                textAttributes, HighlighterTargetArea.EXACT_RANGE) as RangeHighlighterEx

        val highlightInfo = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .range(highlighter.startOffset, highlighter.endOffset)
            .description(tooltip)
            .textAttributes(textAttributes)
            .unescapedToolTip(tooltip)
            .createUnconditionally()

        val highlighterProperty = HighlightInfo::class.members.find { it.name == "highlighter" }
                as KMutableProperty1<HighlightInfo, RangeHighlighterEx>
        highlighterProperty.isAccessible = true
        highlighterProperty.set(highlightInfo, highlighter)

        highlighter.errorStripeTooltip = highlightInfo
    }

    private fun syncBytecodeToEditor(caretPosition: LogicalPosition) {
        val caretOffset = activeSourceEditor!!.logicalPositionToOffset(caretPosition)
        val languageSupport = LanguageSupport.forLanguage(activeSourceFile!!.language)
        val methodAtCaret = languageSupport.findMethodAtOffset(activeSourceFile!!, caretOffset) ?: return
        val metaMember = modelService.getMetaMember(methodAtCaret) ?: return
        val lineTableEntry = metaMember.memberBytecode.lineTable.getEntryForSourceLine(caretPosition.line + 1) ?: return
        val bytecodeLine = bytecodeTextBuilder?.findLine(metaMember, lineTableEntry.bytecodeOffset) ?: return
        moveCaretInBytecode(bytecodeLine)
    }

    private fun moveCaretInBytecode(bytecodeLine: Int) {
        bytecodeEditor.caretModel.moveToLogicalPosition(LogicalPosition(bytecodeLine, 0))
        bytecodeEditor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    fun navigateToMember(member: PsiElement) {
        val metaMember = modelService.getMetaMember(member) ?: return
        val bytecodeLine = bytecodeTextBuilder?.findLine(metaMember) ?: return
        moveCaretInBytecode(bytecodeLine)
    }

    override fun dispose() {
    }

    companion object {
        fun getInstance(project: Project): JitToolWindow? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("JitWatch") ?: return null
            return toolWindow.contentManager.getContent(0)?.component as? JitToolWindow
        }
    }
}

class JitToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(
                ContentFactory.SERVICE.getInstance().createContent(JitToolWindow(project), "", false)
        )
    }
}
