package ru.yole.jitwatch

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.containers.isNullOrEmpty
import org.adoptopenjdk.jitwatch.core.HotSpotLogParser
import org.adoptopenjdk.jitwatch.core.IJITListener
import org.adoptopenjdk.jitwatch.core.ILogParseErrorListener
import org.adoptopenjdk.jitwatch.core.JITWatchConfig
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.IReadOnlyJITDataModel
import org.adoptopenjdk.jitwatch.model.JITEvent
import org.adoptopenjdk.jitwatch.model.MetaClass
import org.adoptopenjdk.jitwatch.model.bytecode.*
import org.adoptopenjdk.jitwatch.treevisitor.TreeVisitor
import ru.yole.jitwatch.languages.LanguageSupport
import ru.yole.jitwatch.languages.forElement
import ru.yole.jitwatch.languages.getAllSupportedLanguages
import ru.yole.jitwatch.languages.matchesSignature
import java.io.File
import javax.swing.SwingUtilities

class JitWatchModelService(private val project: Project) {
    private val config = JITWatchConfig()
    private var _model: IReadOnlyJITDataModel? = null
    private var inlineAnalyzer: InlineAnalyzer? = null
    private val bytecodeAnnotations = mutableMapOf<MetaClass, Map<IMetaMember, BytecodeAnnotations>>()
    private val allLanguages = getAllSupportedLanguages()

    val model: IReadOnlyJITDataModel?
        get() = _model

    val inlineFailures: List<InlineFailureInfo>
        get() = inlineAnalyzer?.failures.orEmpty()

    fun loadLog(logFile: File) {
        bytecodeAnnotations.clear()

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
                parser.processLogFile(logFile, errorListener)
                _model = parser.model

                inlineAnalyzer = InlineAnalyzer(parser.model) { metaMember ->
                    val psiMember = getPsiMember(metaMember)
                    psiMember != null && ModuleUtil.findModuleForPsiElement(psiMember) != null
                }
                ApplicationManager.getApplication().runReadAction {
                    TreeVisitor.walkTree(_model, inlineAnalyzer)
                }

                SwingUtilities.invokeLater { modelUpdated() }
            }
        })
    }

    fun closeLog() {
        _model = null
        bytecodeAnnotations.clear()
        modelUpdated()
    }

    private fun modelUpdated() {
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    fun getMetaClass(cls: PsiElement?): MetaClass? {
        if (cls == null) return null
        return model?.let {
            val languageSupport = LanguageSupport.forLanguage(cls.language) ?: return null
            val classQName = languageSupport.getClassVMName(cls)
            it.packageManager.getMetaClass(classQName)
        }
    }

    fun getMetaMember(method: PsiElement): IMetaMember? {
        val languageSupport = LanguageSupport.forLanguage(method.language) ?: return null
        val metaClass = getMetaClass(languageSupport.getContainingClass(method)) ?: return null
        return metaClass.metaMembers.find { method.matchesSignature(it) }
    }

    fun getPsiMember(metaMember: IMetaMember): PsiElement? {
        val psiClass = getPsiClass(metaMember.metaClass) ?: return null
        val allMethods = LanguageSupport.forElement(psiClass).getAllMethods(psiClass)
        return allMethods.find { it.matchesSignature(metaMember )}
    }

    fun getPsiClass(metaClass: MetaClass): PsiElement? {
        for (languageSupport in allLanguages) {
            val psiClass = languageSupport.findClass(project, metaClass)
            if (psiClass != null) {
                return psiClass
            }
        }
        return null
    }

    fun loadBytecodeAsync(file: PsiFile, callback: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            loadBytecode(file)

            SwingUtilities.invokeLater {
                callback()
            }
        }
    }

    fun loadBytecode(file: PsiFile) {
        val module = ModuleUtil.findModuleForPsiElement(file) ?: return
        val outputRoots = CompilerModuleExtension.getInstance(module)!!.getOutputRoots(true)
                .map { it.canonicalPath }

        val allClasses = LanguageSupport.forElement(file).getAllClasses(file)
        for (cls in allClasses) {
            val memberAnnotations = hashMapOf<IMetaMember, BytecodeAnnotations>()
            val metaClass = ApplicationManager.getApplication().runReadAction(Computable { getMetaClass(cls) }) ?: continue
            metaClass.getClassBytecode(JitWatchModelService.getInstance(project).model, outputRoots)
            buildAllBytecodeAnnotations(metaClass, memberAnnotations)
            bytecodeAnnotations[metaClass] = memberAnnotations
        }
    }

    private fun buildAllBytecodeAnnotations(metaClass: MetaClass, target: MutableMap<IMetaMember, BytecodeAnnotations>) {
        for (metaMember in metaClass.metaMembers) {
            val annotations = try {
                BytecodeAnnotationBuilder().buildBytecodeAnnotations(metaMember, model)
            } catch (e: Exception) {
                LOG.error("Failed to build annotations", e)
                continue
            }
            target[metaMember] = annotations
        }
    }

    fun processBytecodeAnnotations(psiFile: PsiFile, callback: (method: PsiElement,
                                                                member: IMetaMember,
                                                                memberBytecode: MemberBytecode,
                                                                instruction: BytecodeInstruction,
                                                                annotations: List<LineAnnotation>) -> Unit) {
        val languageSupport = LanguageSupport.forLanguage(psiFile.language)
        for (cls in languageSupport.getAllClasses(psiFile)) {
            val metaClass = getMetaClass(cls)
            val classBC = metaClass?.classBytecode ?: continue
            val classAnnotations = bytecodeAnnotations[metaClass] ?: continue
            for (method in languageSupport.getAllMethods(cls)) {
                val member = classAnnotations.keys.find { method.matchesSignature(it) } ?: continue
                val annotations = classAnnotations[member] ?: continue
                val memberBytecode = classBC.getMemberBytecode(member) ?: continue
                for (instruction in memberBytecode.instructions) {
                    val annotationsForBCI = annotations.getAnnotationsForBCI(instruction.offset)
                    if (annotationsForBCI.isNullOrEmpty()) continue

                    callback(method, member, memberBytecode, instruction, annotationsForBCI)
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project) = ServiceManager.getService(project, JitWatchModelService::class.java)

        val LOG = Logger.getInstance(JitWatchModelService::class.java)
    }
}


fun MemberBytecode.findInstructionsForSourceLine(sourceLine: Int): List<BytecodeInstruction> {
    val lineEntryIndex = lineTable.entries.indexOfFirst { it.sourceOffset == sourceLine }
    if (lineEntryIndex >= 0) {
        val startBytecodeOffset = lineTable.entries[lineEntryIndex].bytecodeOffset
        val nextLineBytecodeOffset = if (lineEntryIndex < lineTable.entries.size - 1)
            lineTable.entries[lineEntryIndex+1].bytecodeOffset
        else
            -1

        return instructions.filter {
            it.offset >= startBytecodeOffset && (nextLineBytecodeOffset == -1 || it.offset < nextLineBytecodeOffset)
        }
    }
    return emptyList()

}
