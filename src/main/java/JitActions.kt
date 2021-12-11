package ru.yole.jitwatch

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.DumbAwareAction
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
        loadLogAndShowUI(project, File(logFile.path))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

fun loadLogAndShowUI(project: Project, logFile: File) {
   registerToolWindows(project)

    JitWatchModelService.getInstance(project).loadLog(logFile) { errors ->
        if (!errors.isEmpty()) {
            Notifications.Bus.notify(
                    Notification("JitWatch",
                            "Log parse failed",
                            errors.first().first,
                            NotificationType.ERROR))

        } else {
            ToolWindowManager.getInstance(project).getToolWindow(JitReportToolWindow.ID)?.activate(null)
        }
    }
}

fun registerToolWindows(project: Project) {
    val toolWindowManager = ToolWindowManager.getInstance(project)
    val existingToolWindow = JitToolWindow.getToolWindow(project)
    if (existingToolWindow == null) {
        val jitWatchToolWindow = toolWindowManager.registerToolWindow(JitToolWindow.ID, false, ToolWindowAnchor.RIGHT, project, true)
        jitWatchToolWindow.contentManager.addContent(
                ContentFactory.SERVICE.getInstance().createContent(JitToolWindow(project), "", false)
        )

        val reportToolWindow = toolWindowManager.registerToolWindow(JitReportToolWindow.ID, false, ToolWindowAnchor.BOTTOM, project, true)
        reportToolWindow.contentManager.addContent(
                ContentFactory.SERVICE.getInstance().createContent(JitReportToolWindow(project), "", false)
        )
    }
}

class CloseLogAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        JitWatchModelService.getInstance(project).closeLog()

        val toolWindowManager = ToolWindowManager.getInstance(project)
        toolWindowManager.unregisterToolWindow(JitToolWindow.ID)
        toolWindowManager.unregisterToolWindow(JitReportToolWindow.ID)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && JitWatchModelService.getInstance(project).model != null
    }
}