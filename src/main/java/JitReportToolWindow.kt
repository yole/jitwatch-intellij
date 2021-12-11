package ru.yole.jitwatch

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.NavigatablePsiElement
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import org.adoptopenjdk.jitwatch.model.IMetaMember
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import kotlin.comparisons.compareBy

class JitReportToolWindow(val project: Project) : JPanel(BorderLayout()) {
    private val modelService = JitWatchModelService.getInstance(project)
    private val reportTable = TableView<InlineFailureGroup>()
    private val reportTableModel = ListTableModel<InlineFailureGroup>(
            CalleeColumnInfo, CalleeSizeColumnInfo, CalleeCountColumnInfo, CallSiteColumnInfo, ReasonColumnInfo
    ).apply {
        isSortable = true
    }

    init {
        reportTable.setModelAndUpdateColumns(reportTableModel)
        add(JBScrollPane(reportTable), BorderLayout.CENTER)

        updateData()

        reportTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val columnAtPoint = reportTable.columnAtPoint(e.point)
                val rowAtPoint = reportTable.rowAtPoint(e.point)
                if (columnAtPoint < 0 || rowAtPoint < 0) return

                val col = reportTable.convertColumnIndexToModel(columnAtPoint)
                val row = reportTable.convertRowIndexToModel(rowAtPoint)
                val failureGroup = reportTableModel.getItem(row)
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                    when (col) {
                        0 -> navigateToMember(failureGroup.callee)
                        3 -> showCallSitesPopup(e, failureGroup.callSites)
                    }
                }
            }
        })

        modelService.addUpdateListener { updateData() }
    }

    private fun updateData() {
        reportTableModel.items = modelService.inlineFailureGroups
    }

    private fun navigateToMember(member: IMetaMember, bci: Int? = null) {
        val psiMethod = JitWatchModelService.getInstance(project).getPsiMember(member) ?: return
        val memberBC = member.memberBytecode
        if (memberBC != null && bci != null) {
            val sourceLine = memberBC.lineTable.findSourceLineForBytecodeOffset(bci)
            if (sourceLine != -1) {
                OpenFileDescriptor(project, psiMethod.containingFile.virtualFile, sourceLine - 1, 0).navigate(true)
                return
            }
        }
        (psiMethod as? NavigatablePsiElement)?.navigate(true)
    }

    private fun showCallSitesPopup(e: MouseEvent, callSites: List<InlineCallSite>) {
        val popupStep = object : BaseListPopupStep<InlineCallSite>("Call Sites", callSites) {
            override fun getTextFor(value: InlineCallSite) = value.member.presentableName()

            override fun onChosen(selectedValue: InlineCallSite?, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue != null) {
                    navigateToMember(selectedValue.member, selectedValue.bci)
                }
                return PopupStep.FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(popupStep).show(RelativePoint.fromScreen(e.locationOnScreen))
    }

    companion object {
        const val ID = "JITWatch Report"
    }
}

object CalleeColumnInfo : ColumnInfo<InlineFailureGroup, String>("Callee") {
    override fun valueOf(item: InlineFailureGroup) = item.callee.fullyQualifiedMemberName

    override fun getComparator() = compareBy<InlineFailureGroup> { it.callee.fullyQualifiedMemberName }

    override fun getRenderer(item: InlineFailureGroup?) = LinkRenderer
}

object CalleeSizeColumnInfo : ColumnInfo<InlineFailureGroup, Int>("Callee Size") {
    override fun valueOf(item: InlineFailureGroup) = item.calleeSize

    override fun getComparator() = compareBy<InlineFailureGroup> { it.calleeSize }
}

object CalleeCountColumnInfo : ColumnInfo<InlineFailureGroup, Int>("Invocations") {
    override fun valueOf(item: InlineFailureGroup) = item.calleeInvocationCount

    override fun getComparator() = compareBy<InlineFailureGroup> { it.calleeInvocationCount }
}

object CallSiteColumnInfo : ColumnInfo<InlineFailureGroup, Int>("Call Sites") {
    override fun valueOf(item: InlineFailureGroup) = item.callSites.size

    override fun getRenderer(item: InlineFailureGroup?) = LinkRenderer

    override fun getComparator() = compareBy<InlineFailureGroup> { it.callSites.size }
}

object LinkRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        if (value != null) {
            append(value.toString(), SimpleTextAttributes.LINK_ATTRIBUTES)
        }
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
}

object ReasonColumnInfo : ColumnInfo<InlineFailureGroup, String>("Reason") {
    override fun valueOf(item: InlineFailureGroup): String? {
        return item.reasons.joinToString()
    }
}
