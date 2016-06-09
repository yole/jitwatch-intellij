package ru.yole.jitwatch

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import org.adoptopenjdk.jitwatch.model.IMetaMember

class JitLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val languageSupport = LanguageSupport.forElement(element)
        if (languageSupport == DefaultJitLanguageSupport) return null

        if (!languageSupport.isMethod(element)) return null
        val modelService = JitWatchModelService.getInstance(element.project)
        if (modelService.model == null) return null
        val metaMember = modelService.getMetaMember(element) ?: return notCompiledMarker(element)
        return if (metaMember.isCompiled) metaMemberMarker(element, metaMember) else notCompiledMarker(element)
    }

    private fun notCompiledMarker(element: PsiElement): LineMarkerInfo<*> {
        return LineMarkerInfo(element,
                LanguageSupport.forElement(element).getNameRange(element),
                AllIcons.Actions.Suspend,
                Pass.UPDATE_ALL,
                { method -> "Not compiled"}, null, GutterIconRenderer.Alignment.CENTER)
    }

    private fun metaMemberMarker(method: PsiElement, metaMember: IMetaMember): LineMarkerInfo<*> {
        val decompiles = metaMember.compiledAttributes["decompiles"] ?: "0"
        val icon = if (decompiles.toInt() > 0) AllIcons.Actions.ForceRefresh else AllIcons.Actions.Compile
        return LineMarkerInfo(method,
                LanguageSupport.forElement(method).getNameRange(method),
                icon,
                Pass.UPDATE_ALL,
                { method -> buildCompiledTooltip(metaMember) },
                { e, elt ->
                    ToolWindowManager.getInstance(method.project).getToolWindow("JitWatch").activate {
                        JitToolWindow.getInstance(method.project)?.navigateToMember(elt)
                    }
                },
                GutterIconRenderer.Alignment.CENTER)
    }

    private fun buildCompiledTooltip(metaMember: IMetaMember): String {
        val compiler = metaMember.compiledAttributes["compiler"] ?: "?"
        val compileMillis = metaMember.compiledAttributes["compileMillis"] ?: "?"
        val bytecodeSize = metaMember.compiledAttributes["bytes"] ?: "?"
        val nativeSize = metaMember.compiledAttributes["nmsize"] ?: "?"
        val decompiles = metaMember.compiledAttributes["decompiles"]
        var message = "Compiled with $compiler in $compileMillis ms, bytecode size $bytecodeSize, native size $nativeSize"
        if (decompiles != null) {
            message += ". Decompiled $decompiles times"
        }
        return message
    }

    override fun collectSlowLineMarkers(elements: MutableList<PsiElement>, result: MutableCollection<LineMarkerInfo<PsiElement>>) {
    }
}
