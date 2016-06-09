package ru.yole.jitwatch

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import java.io.File

class LoadLogAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
        val fileChooser = FileChooserFactory.getInstance().createFileChooser(fileChooserDescriptor, project, null)
        val logFile = fileChooser.choose(project).singleOrNull() ?: return
        JitWatchModelService.getInstance(project).loadLog(File(logFile.path))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
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