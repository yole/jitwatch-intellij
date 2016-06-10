package ru.yole.jitwatch

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseTreePopupStep
import com.intellij.pom.Navigatable
import com.intellij.ui.SimpleTextAttributes
import org.adoptopenjdk.jitwatch.chain.CompileChainWalker
import org.adoptopenjdk.jitwatch.chain.CompileNode
import org.adoptopenjdk.jitwatch.model.IMetaMember
import ru.yole.jitwatch.languages.LanguageSupport
import ru.yole.jitwatch.languages.forElement

class InlineTreeStructure(val project: Project, val root: CompileNode) : AbstractTreeStructure() {
    override fun getRootElement() = InlineTreeNodeDescriptor(project, null, root, true)

    override fun createDescriptor(element: Any?, parentDescriptor: NodeDescriptor<*>?) = element as NodeDescriptor<*>

    override fun getParentElement(element: Any?) = (element as InlineTreeNodeDescriptor).parentDescriptor

    override fun getChildElements(element: Any?) =
            (element as InlineTreeNodeDescriptor).compileNode.children
                    .map { InlineTreeNodeDescriptor(project, element as NodeDescriptor<*>, it )}
                    .toTypedArray()

    override fun commit() {
    }

    override fun hasSomethingToCommit() = false
}

class InlineTreeNodeDescriptor(project: Project,
                               parentDescriptor: NodeDescriptor<*>?,
                               val compileNode: CompileNode,
                               val isRoot: Boolean = false)
        : PresentableNodeDescriptor<CompileNode>(project, parentDescriptor)
{
    override fun update(presentation: PresentationData) {
        presentation.addText(compileNode.member?.presentableName() ?: "<Unknown>",
                if (compileNode.isInlined || isRoot)
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                else
                    SimpleTextAttributes.ERROR_ATTRIBUTES)
        presentation.tooltip = compileNode.tooltipText
    }

    override fun getElement() = compileNode
}

class ShowInlineStructureAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val metaMember = findTargetMember(e) ?: return
        val project = e.project!!
        val modelService = JitWatchModelService.getInstance(project)
        val compileChainWalker = CompileChainWalker(modelService.model)
        val compileNode = compileChainWalker.buildCallTree(metaMember.journal) ?: return
        val treeStructure = InlineTreeStructure(project, compileNode)
        val popupStep = object : BaseTreePopupStep<InlineTreeNodeDescriptor>(project, "Inline", treeStructure) {
            override fun isRootVisible() = true

            override fun onChosen(selectedValue: InlineTreeNodeDescriptor?, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue != null) {
                    val psiMember = JitWatchModelService.getInstance(project).getPsiMember(selectedValue.compileNode.member)
                    (psiMember as? Navigatable)?.navigate(true)
                }
                return PopupStep.FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createTree(popupStep).showInBestPositionFor(e.dataContext)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = findTargetMember(e) != null
    }

    private fun findTargetMember(e: AnActionEvent): IMetaMember? {
        val project = e.project ?: return null
        val modelService = JitWatchModelService.getInstance(project)
        if (modelService.model == null) return null
        val psiElement = e.getData(LangDataKeys.PSI_ELEMENT) ?: return null
        val languageSupport = LanguageSupport.forElement(psiElement)
        if (languageSupport.isMethod(psiElement)) {
            return modelService.getMetaMember(psiElement)
        }
        return null
    }
}

fun IMetaMember.presentableName(): String {
    return (metaClass?.name ?: "<unknown>") + "." + memberName
}
