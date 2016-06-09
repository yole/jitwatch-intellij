package ru.yole.jitwatch

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.io.File

class LoadLogAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
        val fileChooser = FileChooserFactory.getInstance().createFileChooser(fileChooserDescriptor, project, null)
        val logFile = fileChooser.choose(project).singleOrNull() ?: return

        registerToolWindows(project)

        JitWatchModelService.getInstance(project).loadLog(File(logFile.path)) {
            ToolWindowManager.getInstance(project).getToolWindow("JitWatch Report").activate(null)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun registerToolWindows(project: Project) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val existingToolWindow = toolWindowManager.getToolWindow("JitWatch")
        if (existingToolWindow == null) {
            val jitWatchToolWindow = toolWindowManager.registerToolWindow("JitWatch", false, ToolWindowAnchor.RIGHT, project, true)
            jitWatchToolWindow.contentManager.addContent(
                    ContentFactory.SERVICE.getInstance().createContent(JitToolWindow(project), "", false)
            )

            val reportToolWindow = toolWindowManager.registerToolWindow("JitWatch Report", false, ToolWindowAnchor.BOTTOM, project, true)
            reportToolWindow.contentManager.addContent(
                    ContentFactory.SERVICE.getInstance().createContent(JitReportToolWindow(project), "", false)
            )
        }
    }
}

class CloseLogAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        JitWatchModelService.getInstance(project).closeLog()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && JitWatchModelService.getInstance(project).model != null
    }
}