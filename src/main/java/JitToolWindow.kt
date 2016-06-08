package ru.yole.jitwatch

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.Result
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretAdapter
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.content.ContentFactory
import org.adoptopenjdk.jitwatch.model.MetaClass
import java.awt.CardLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

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
    private var editor: Editor? = null
    private var psiFile: PsiJavaFile? = null

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerAdapter() {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                updateContent(event.newFile, event.newEditor)
            }
        })

        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretAdapter() {
            override fun caretPositionChanged(e: CaretEvent) {
                if (e.editor != editor) return
                syncBytecodeToEditor(e.newPosition)
            }
        }, this)

        setupUI()
        val fileEditorManager = FileEditorManager.getInstance(project)
        updateContent(fileEditorManager.selectedFiles.firstOrNull(), fileEditorManager.selectedEditors.firstOrNull())
    }

    private fun setupUI() {
        add(messageLabel, MESSAGE_CARD)
        add(bytecodeEditor.component, EDITOR_CARD)
    }

    private fun updateContent(file: VirtualFile?, fileEditor: FileEditor?) {
        editor = (fileEditor as? TextEditor)?.editor
            ?: return showMessage("Please open a text editor")

        if (modelService.model == null)
            return showMessage("Please open a HotSpot compilation log")
        if (file?.fileType != JavaFileType.INSTANCE)
            return showMessage("Please select a Java file")

        psiFile = PsiManager.getInstance(project).findFile(file!!) as? PsiJavaFile
            ?: return showMessage("Please select a Java file")
        val psiClass = psiFile!!.classes.firstOrNull()
            ?: return showMessage("Please select a Java file that contains classes")

        val metaClass = modelService.getMetaClass(psiClass)
            ?: return showMessage("Please select a file for which compilation information is available")

        loadAndShowBytecode(file, metaClass)
    }

    private fun showMessage(message: String) {
        messageLabel.text = message
        cardLayout.show(this, MESSAGE_CARD)
    }

    private fun loadAndShowBytecode(file: VirtualFile, metaClass: MetaClass) {
        val module = ModuleUtil.findModuleForFile(file, project)
            ?: return showMessage("Please select a file under a source root")
        val outputRoots = CompilerModuleExtension.getInstance(module)!!.getOutputRoots(true)
            .map { it.canonicalPath }

        ApplicationManager.getApplication().executeOnPooledThread {
            val classBC = metaClass.getClassBytecode(JitWatchModelService.getInstance(project).model, outputRoots)
            SwingUtilities.invokeLater {
                if (classBC == null) {
                    showMessage("Cannot find bytecode for selected class")
                }
                else {
                    showBytecode(file, metaClass)
                }
            }
        }
    }

    private fun showBytecode(file: VirtualFile, metaClass: MetaClass) {
        cardLayout.show(this, EDITOR_CARD)

        bytecodeTextBuilder = BytecodeTextBuilder(metaClass)
        object : WriteCommandAction<Unit>(project) {
            override fun run(result: Result<Unit>) {
                bytecodeDocument.replaceString(0, bytecodeDocument.textLength, bytecodeTextBuilder!!.text)
            }
        }.execute()
    }

    private fun syncBytecodeToEditor(caretPosition: LogicalPosition) {
        val caretOffset = editor!!.logicalPositionToOffset(caretPosition)
        val elementAtCaret = psiFile!!.findElementAt(caretOffset) ?: return
        val methodAtCaret = PsiTreeUtil.getParentOfType(elementAtCaret, PsiMethod::class.java) ?: return
        val metaMember = modelService.getMetaMember(methodAtCaret) ?: return
        val lineTableEntry = metaMember.memberBytecode.lineTable.getEntryForSourceLine(caretPosition.line + 1) ?: return
        val bytecodeLine = bytecodeTextBuilder?.findLine(metaMember, lineTableEntry.bytecodeOffset) ?: return
        bytecodeEditor.caretModel.moveToLogicalPosition(LogicalPosition(bytecodeLine, 0))
        bytecodeEditor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    override fun dispose() {
    }
}

class JitToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(
                ContentFactory.SERVICE.getInstance().createContent(JitToolWindow(project), "", false)
        )
    }
}
