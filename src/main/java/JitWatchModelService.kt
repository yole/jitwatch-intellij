package ru.yole.jitwatch

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.containers.isNullOrEmpty
import com.intellij.util.io.isFile
import org.adoptopenjdk.jitwatch.core.HotSpotLogParser
import org.adoptopenjdk.jitwatch.core.IJITListener
import org.adoptopenjdk.jitwatch.core.ILogParseErrorListener
import org.adoptopenjdk.jitwatch.core.JITWatchConfig
import org.adoptopenjdk.jitwatch.core.JITWatchConstants.*
import org.adoptopenjdk.jitwatch.model.*
import org.adoptopenjdk.jitwatch.model.bytecode.*
import org.adoptopenjdk.jitwatch.treevisitor.TreeVisitor
import org.adoptopenjdk.jitwatch.util.ParseUtil
import org.adoptopenjdk.jitwatch.util.StringUtil
import ru.yole.jitwatch.languages.LanguageSupport
import ru.yole.jitwatch.languages.forElement
import ru.yole.jitwatch.languages.getAllSupportedLanguages
import ru.yole.jitwatch.languages.matchesSignature
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.SwingUtilities

class JitWatchModelService(private val project: Project) {
    private val config = JITWatchConfig()
    private var _model: IReadOnlyJITDataModel? = null
    private var inlineAnalyzer: InlineAnalyzer? = null
    private val bytecodeAnnotations = mutableMapOf<MetaClass, Map<IMetaMember, BytecodeAnnotations>>()
    private val allLanguages = getAllSupportedLanguages()
    private val updateListeners = mutableListOf<() -> Unit>()

    val model: IReadOnlyJITDataModel?
        get() = _model

    val inlineFailures: List<InlineFailureInfo>
        get() = inlineAnalyzer?.failures.orEmpty()
    val inlineFailureGroups: List<InlineFailureGroup>
        get() = inlineAnalyzer?.failureGroups.orEmpty()

    fun addUpdateListener(listener: () -> Unit) {
        updateListeners.add(listener)
    }

    fun loadLog(logFile: File, callback: (List<Pair<String, String>>) -> Unit = {}) {
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

                SwingUtilities.invokeLater {
                    modelUpdated()
                    callback(parseErrors)
                }
            }
        })
    }

    fun closeLog() {
        _model = null
        inlineAnalyzer = null
        bytecodeAnnotations.clear()
        modelUpdated()
    }

    private fun modelUpdated() {
        DaemonCodeAnalyzer.getInstance(project).restart()
        for (listener in updateListeners) {
            listener()
        }
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
        val javapPath = findJavapPath(module)

        val allClasses = LanguageSupport.forElement(file).getAllClasses(file)
        for (cls in allClasses) {
            val memberAnnotations = hashMapOf<IMetaMember, BytecodeAnnotations>()
            val metaClass = ApplicationManager.getApplication().runReadAction(Computable { getMetaClass(cls) }) ?: continue
            metaClass.getClassBytecode(JitWatchModelService.getInstance(project).model, outputRoots, javapPath)
            buildAllBytecodeAnnotations(metaClass, memberAnnotations)
            bytecodeAnnotations[metaClass] = memberAnnotations
        }
    }

    private fun findJavapPath(module: Module): Path? {
        val sdk = ModuleRootManager.getInstance(module).sdk ?: return null
        val javaSdk = JavaSdk.getInstance()
        if (sdk.sdkType != javaSdk) return null
        val binPath = javaSdk.getBinPath(sdk)
        val exeName = if (SystemInfo.isWindows) "javap.exe" else "javap"
        val result = Paths.get(binPath, exeName)
        return if (result.isFile()) result else null
    }

    private fun buildAllBytecodeAnnotations(metaClass: MetaClass, target: MutableMap<IMetaMember, BytecodeAnnotations>) {
        for (metaMember in metaClass.metaMembers) {
            val annotations = try {
                IJBytecodeAnnotationBuilder().buildBytecodeAnnotations(metaMember, model)
            } catch (e: Exception) {
                LOG.error("Failed to build annotations", e)
                continue
            }
            target[metaMember] = annotations
        }
    }

    private class IJBytecodeAnnotationBuilder : BytecodeAnnotationBuilder() {
        override fun buildInlineAnnotation(parseDictionary: IParseDictionary,
                                           methodAttrs: MutableMap<String, String>,
                                           callAttrs: MutableMap<String, String>,
                                           reason: String,
                                           inlined: Boolean): String {
            val holder = methodAttrs[ATTR_HOLDER]
            val methodName = methodAttrs[ATTR_NAME]
            val calleeClass = ParseUtil.lookupType(holder, parseDictionary)
            val calleeMethod = StringUtil.replaceXMLEntities(methodName)
            val builder = StringBuilder(calleeClass ?: "<unknown>")
            builder.append(".").append(calleeMethod)
            builder.append(if (inlined) " inlined " else " not inlined ")
            builder.append("(").append(reason).append(")")

            if (callAttrs.containsKey(ATTR_COUNT)) {
                builder.append(". Count: ").append(callAttrs[ATTR_COUNT])
            }
            if (methodAttrs.containsKey(ATTR_IICOUNT)) {
                builder.append(". iicount: ").append(methodAttrs[ATTR_IICOUNT])
            }
            if (methodAttrs.containsKey(ATTR_BYTES)) {
                builder.append(". Bytes: ").append(methodAttrs[ATTR_BYTES])
            }
            return builder.toString()
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
