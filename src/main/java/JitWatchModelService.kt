package ru.yole.jitwatch

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.debugger.engine.JVMNameUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import org.adoptopenjdk.jitwatch.core.HotSpotLogParser
import org.adoptopenjdk.jitwatch.core.IJITListener
import org.adoptopenjdk.jitwatch.core.ILogParseErrorListener
import org.adoptopenjdk.jitwatch.core.JITWatchConfig
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.IReadOnlyJITDataModel
import org.adoptopenjdk.jitwatch.model.JITEvent
import org.adoptopenjdk.jitwatch.model.MetaClass
import org.adoptopenjdk.jitwatch.model.bytecode.BytecodeAnnotationBuilder
import org.adoptopenjdk.jitwatch.model.bytecode.BytecodeAnnotations
import org.adoptopenjdk.jitwatch.model.bytecode.ClassBC
import java.io.File
import javax.swing.SwingUtilities

class JitWatchModelService(private val project: Project) {
    private val config = JITWatchConfig()
    private var _model: IReadOnlyJITDataModel? = null
    private val bytecodeAnnotations = mutableMapOf<VirtualFile, Map<IMetaMember, BytecodeAnnotations>>()

    val model: IReadOnlyJITDataModel?
        get() = _model

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
                _model = parser.model

                SwingUtilities.invokeLater { modelUpdated() }
            }
        })
    }

    private fun modelUpdated() {
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    fun getMetaClass(cls: PsiClass?): MetaClass? {
        if (cls == null) return null
        return model?.let {
            val classQName = JVMNameUtil.getClassVMName(cls)
            it.packageManager.getMetaClass(classQName)
        }
    }

    fun getMetaMember(method: PsiMethod): IMetaMember? {
        val metaClass = getMetaClass(method.containingClass) ?: return null
        return metaClass.metaMembers.find { it.matchesSignature(method) }
    }

    fun loadBytecodeAsync(psiClass: PsiClass, callback: (ClassBC?, Map<IMetaMember, BytecodeAnnotations>?) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val bytecodeResult = loadBytecode(psiClass)

            SwingUtilities.invokeLater {
                callback(bytecodeResult?.first, bytecodeResult?.second)
            }
        }
    }

    fun loadBytecode(psiClass: PsiClass): Pair<ClassBC, Map<IMetaMember, BytecodeAnnotations>>? {
        val module = ModuleUtil.findModuleForPsiElement(psiClass) ?: return null
        val outputRoots = CompilerModuleExtension.getInstance(module)!!.getOutputRoots(true)
                .map { it.canonicalPath }
        val metaClass = ApplicationManager.getApplication().runReadAction(Computable { getMetaClass(psiClass) }) ?: return null

        val classBC = metaClass.getClassBytecode(JitWatchModelService.getInstance(project).model, outputRoots)

        return classBC to bytecodeAnnotations.getOrPut(psiClass.containingFile.virtualFile) {
            buildAllBytecodeAnnotations(metaClass)
        }
    }

    private fun buildAllBytecodeAnnotations(metaClass: MetaClass): Map<IMetaMember, BytecodeAnnotations> =
            metaClass.metaMembers.associate {
                it to BytecodeAnnotationBuilder().buildBytecodeAnnotations(it, model)
            }

    companion object {
        fun getInstance(project: Project) = ServiceManager.getService(project, JitWatchModelService::class.java)
    }
}

fun IMetaMember.matchesSignature(method: PsiMethod): Boolean {
    if (memberName != method.name) return false
    val paramTypes = paramTypeNames zip method.parameterList.parameters.map { it.type.canonicalText }
    if (paramTypes.any { it.first != it.second})
        return false
    val psiMethodReturnTypeName = if (method.isConstructor) "void" else method.returnType?.canonicalText ?: ""
    if (returnTypeName != psiMethodReturnTypeName)
        return false
    return true
}
