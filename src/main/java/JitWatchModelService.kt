package ru.yole.jitwatch

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.adoptopenjdk.jitwatch.core.HotSpotLogParser
import org.adoptopenjdk.jitwatch.core.IJITListener
import org.adoptopenjdk.jitwatch.core.ILogParseErrorListener
import org.adoptopenjdk.jitwatch.core.JITWatchConfig
import org.adoptopenjdk.jitwatch.model.IReadOnlyJITDataModel
import org.adoptopenjdk.jitwatch.model.JITEvent
import java.io.File

class JitWatchModelService(private val project: Project) {
    private val config = JITWatchConfig()
    private var model: IReadOnlyJITDataModel? = null

    fun loadLog(logFile: VirtualFile) {
        val jitListener = object : IJITListener {
            override fun handleLogEntry(entry: String?) {
            }

            override fun handleErrorEntry(entry: String?) {
            }

            override fun handleReadComplete() {
            }

            override fun handleJITEvent(event: JITEvent?) {
            }

            override fun handleReadStart() {
            }
        }

        val parseErrors = mutableListOf<Pair<String, String>>()
        val errorListener = ILogParseErrorListener { title, body -> parseErrors.add(title to body) }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading compilation log", false) {
            override fun run(indicator: ProgressIndicator) {
                val parser = HotSpotLogParser(jitListener)
                parser.config = config
                parser.processLogFile(File(logFile.canonicalPath), errorListener)
                model = parser.model
            }
        })
    }

    companion object {
        fun getInstance(project: Project) = ServiceManager.getService(project, JitWatchModelService::class.java)
    }
}
