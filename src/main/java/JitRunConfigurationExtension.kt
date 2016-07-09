package ru.yole.jitwatch

import com.intellij.execution.CommonJavaRunConfigurationParameters
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ToolWindowManager
import org.jdom.Element
import java.awt.BorderLayout
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

private const val JITWATCH_ENABLED_ATTRIBUTE = "jitwatch-enabled"

data class JitWatchSettings(var enabled: Boolean = false, var lastLogPath: File? = null) {
    companion object {
        private val KEY: Key<JitWatchSettings> = Key.create("ru.yole.jitwatch.settings")

        fun getOrCreate(configuration: RunConfigurationBase): JitWatchSettings {
            var settings = configuration.getUserData(KEY)
            if (settings == null) {
                settings = JitWatchSettings()
                configuration.putUserData(KEY, settings)
            }
            return settings
        }

        fun clear(configuration: RunConfigurationBase) {
            configuration.putUserData(KEY, null)
        }
    }
}

class JitRunConfigurationExtension : RunConfigurationExtension() {
    override fun <P : RunConfigurationBase> createEditor(configuration: P): SettingsEditor<P>? {
        return JitRunConfigurationEditor()
    }

    override fun cleanUserData(runConfigurationBase: RunConfigurationBase) {
        JitWatchSettings.clear(runConfigurationBase)
    }

    override fun getEditorTitle() = "JITWatch"

    override fun isApplicableFor(configuration: RunConfigurationBase) = configuration is CommonJavaRunConfigurationParameters

    override fun readExternal(runConfiguration: RunConfigurationBase, element: Element) {
        val settings = JitWatchSettings.getOrCreate(runConfiguration)
        settings.enabled = element.getAttributeValue(JITWATCH_ENABLED_ATTRIBUTE) == "true"
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase, element: Element) {
        val settings = JitWatchSettings.getOrCreate(runConfiguration)
        if (settings.enabled) {
            element.setAttribute(JITWATCH_ENABLED_ATTRIBUTE, "true")
        }
    }

    override fun <T : RunConfigurationBase> updateJavaParameters(configuration: T,
                                                                 params: JavaParameters,
                                                                 runnerSettings: RunnerSettings?) {
        val settings = JitWatchSettings.getOrCreate(configuration)
        if (settings.enabled) {
            val logPath = FileUtil.generateRandomTemporaryPath()
            val vmOptions = params.vmParametersList
            vmOptions.add("-XX:+UnlockDiagnosticVMOptions")
            vmOptions.add("-XX:+TraceClassLoading")
            vmOptions.add("-XX:+PrintCompilation")
            vmOptions.add("-XX:LogFile=" + logPath.absolutePath)
            settings.lastLogPath = logPath
        }
    }

    override fun attachToProcess(configuration: RunConfigurationBase, handler: ProcessHandler, runnerSettings: RunnerSettings?) {
        val logPath = JitWatchSettings.getOrCreate(configuration).lastLogPath
        if (logPath != null) {
            handler.addProcessListener(object : ProcessAdapter() {
                override fun processTerminated(event: ProcessEvent?) {
                    ApplicationManager.getApplication().invokeLater {
                        loadLogAndShowUI(configuration.project, logPath)
                    }
                }
            })
        }
    }
}

private class JitRunConfigurationEditor<T : RunConfigurationBase> : SettingsEditor<T>() {
    private val editorPanel = JPanel(BorderLayout())
    private val enabledCheckbox = JCheckBox("Log compilation")

    init {
        editorPanel.add(enabledCheckbox, BorderLayout.NORTH)
    }

    override fun applyEditorTo(s: T) {
        val settings = JitWatchSettings.getOrCreate(s)
        settings.enabled = enabledCheckbox.isSelected
    }

    override fun resetEditorFrom(s: T) {
        val settings = JitWatchSettings.getOrCreate(s)
        enabledCheckbox.isSelected = settings.enabled
    }

    override fun createEditor(): JComponent {
        return editorPanel
    }
}
