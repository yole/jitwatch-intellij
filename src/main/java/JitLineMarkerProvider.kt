package ru.yole.jitwatch

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.adoptopenjdk.jitwatch.model.IMetaMember

class JitLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val method = element as? PsiMethod ?: return null
        val modelService = JitWatchModelService.getInstance(element.project)
        if (modelService.model == null) return null
        val metaMember = modelService.getMetaMember(method) ?: return notCompiledMarker(method)
        return if (metaMember.isCompiled) metaMemberMarker(element, metaMember) else notCompiledMarker(method)
    }

    private fun notCompiledMarker(method: PsiMethod): LineMarkerInfo<*> {
        return LineMarkerInfo(method, method.nameRange(), AllIcons.Actions.Suspend, Pass.UPDATE_ALL,
                { method -> "Not compiled"}, null, GutterIconRenderer.Alignment.CENTER)
    }

    private fun metaMemberMarker(method: PsiMethod, metaMember: IMetaMember): LineMarkerInfo<*> {
        val decompiles = metaMember.compiledAttributes["decompiles"] ?: "0"
        val icon = if (decompiles.toInt() > 0) AllIcons.Actions.ForceRefresh else AllIcons.Actions.Compile
        return LineMarkerInfo(method, method.nameRange(),
                icon,
                Pass.UPDATE_ALL,
                { method -> buildCompiledTooltip(metaMember) }, null, GutterIconRenderer.Alignment.CENTER)
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

private fun PsiMethod.nameRange() = nameIdentifier?.textRange ?: textRange
