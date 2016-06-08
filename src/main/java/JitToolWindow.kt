package ru.yole.jitwatch

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.Result
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.ui.content.ContentFactory
import org.adoptopenjdk.jitwatch.model.MetaClass
import org.adoptopenjdk.jitwatch.model.bytecode.ClassBC
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
    private val bytecodeTextField = EditorFactory.getInstance().createEditor(bytecodeDocument, project, PlainTextFileType.INSTANCE, true).apply {
        settings.isLineNumbersShown = false
        settings.isFoldingOutlineShown = false
        settings.isLineMarkerAreaShown = false
    }
    private var bytecodeTextBuilder: BytecodeTextBuilder? = null

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                updateContent(event.newFile)
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                updateContent(file)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                updateContent(null)
            }
        })

        setupUI()
        updateContent(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    private fun setupUI() {
        add(messageLabel, MESSAGE_CARD)
        add(bytecodeTextField.component, EDITOR_CARD)
    }

    private fun updateContent(file: VirtualFile?) {
        val modelService = JitWatchModelService.getInstance(project)
        if (modelService.model == null)
            return showMessage("Please open a HotSpot compilation log")
        if (file?.fileType != JavaFileType.INSTANCE)
            return showMessage("Please select a Java file")

        val psiFile = PsiManager.getInstance(project).findFile(file!!) as? PsiJavaFile
            ?: return showMessage("Please select a Java file")
        val psiClass = psiFile.classes.firstOrNull()
            ?: return showMessage("Please select a Java file that contains classes")

        val metaClass = modelService.getMetaClass(psiClass)
            ?: return showMessage("Please select a file for which compilation information is available")

        showBytecode(file, metaClass)
    }

    private fun showMessage(message: String) {
        messageLabel.text = message
        cardLayout.show(this, MESSAGE_CARD)
    }

    private fun showBytecode(file: VirtualFile, metaClass: MetaClass) {
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
                    showBytecode(file, metaClass, classBC)
                }
            }
        }
    }

    private fun showBytecode(file: VirtualFile, metaClass: MetaClass, classBC: ClassBC) {
        cardLayout.show(this, EDITOR_CARD)

        bytecodeTextBuilder = BytecodeTextBuilder(classBC)
        object : WriteCommandAction<Unit>(project) {
            override fun run(result: Result<Unit>) {
                bytecodeDocument.replaceString(0, bytecodeDocument.textLength, bytecodeTextBuilder!!.text)
            }
        }.execute()
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
