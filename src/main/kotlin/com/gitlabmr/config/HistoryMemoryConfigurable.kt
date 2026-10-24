package com.gitlabmr.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel

/**
 * 设置子页: Settings → Other Settings → GitLabPlus → 历史记忆
 *
 * 跨项目共享的历史分支组合与审核人 (创建 MR 时通过"历史"链接带入), 可编辑或删除。
 */
class HistoryMemoryConfigurable : Configurable {

    private val service get() = GitLabMrConfigService.getInstance()

    private lateinit var branchModel: DefaultTableModel
    private lateinit var branchTable: JTable
    private lateinit var reviewerModel: DefaultTableModel
    private lateinit var reviewerTable: JTable

    override fun getDisplayName(): String = "历史记忆"

    override fun createComponent(): JComponent {
        branchModel = readOnlyModel(arrayOf("源分支", "目标分支"))
        branchTable = JTable(branchModel)
        reviewerModel = readOnlyModel(arrayOf("审核人"))
        reviewerTable = JTable(reviewerModel)

        val sections = JPanel(GridLayout(2, 1, 0, JBUI.scale(8))).apply {
            add(section("历史分支组合（所有项目共享）", branchTable, ::editBranchPair, ::deleteBranchPair))
            add(section("历史审核人（所有项目共享）", reviewerTable, ::editReviewer, ::deleteReviewer))
        }
        refresh()

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(
                JBLabel("<html>创建 MR 时通过\"历史\"链接带入的记录；编辑后可重新管理，删除后不再出现在历史列表中。</html>"),
                BorderLayout.NORTH,
            )
            add(sections, BorderLayout.CENTER)
        }
    }

    override fun isModified(): Boolean = false  // 所有操作即时生效，无待应用的修改
    override fun apply() {}
    override fun reset() = refresh()

    // ------------------------------------------------------------------ //
    //  列表与区块
    // ------------------------------------------------------------------ //

    private fun readOnlyModel(columns: Array<String>): DefaultTableModel =
        object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

    private fun section(
        title: String,
        table: JTable,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ): JPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        add(JBLabel(title), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JButton("编辑...").apply { addActionListener { onEdit() } })
            add(JButton("删除").apply { addActionListener { onDelete() } })
        }, BorderLayout.SOUTH)
    }

    private fun refresh() {
        while (branchModel.rowCount > 0) branchModel.removeRow(branchModel.rowCount - 1)
        service.recentBranchPairs().forEach { branchModel.addRow(arrayOf(it.source, it.target)) }
        while (reviewerModel.rowCount > 0) reviewerModel.removeRow(reviewerModel.rowCount - 1)
        service.recentReviewers().forEach { reviewerModel.addRow(arrayOf(it)) }
    }

    // ------------------------------------------------------------------ //
    //  历史分支组合
    // ------------------------------------------------------------------ //

    private fun selectedBranchPair(): Pair<String, String>? {
        val row = branchTable.selectedRow
        if (row < 0) return null
        return branchModel.getValueAt(row, 0) as String to branchModel.getValueAt(row, 1) as String
    }

    private fun editBranchPair() {
        val (oldSource, oldTarget) = selectedBranchPair() ?: run {
            Messages.showInfoMessage("请先在列表中选择一条分支组合", "GitLab MR")
            return
        }
        val dialog = BranchPairEditDialog(oldSource, oldTarget)
        if (!dialog.showAndGet()) return
        val (source, target) = dialog.getResult() ?: return
        if (source.isEmpty() || target.isEmpty()) {
            Messages.showWarningDialog("源分支与目标分支不能为空", "GitLab MR")
            return
        }
        if (source == target) {
            Messages.showWarningDialog("源分支与目标分支不能相同", "GitLab MR")
            return
        }
        service.updateBranchPair(oldSource, oldTarget, source, target)
        refresh()
    }

    private fun deleteBranchPair() {
        val (source, target) = selectedBranchPair() ?: run {
            Messages.showInfoMessage("请先在列表中选择一条分支组合", "GitLab MR")
            return
        }
        val confirmed = Messages.showYesNoDialog(
            "确定删除历史分支组合 \"$source → $target\"?", "GitLab MR", Messages.getQuestionIcon(),
        ) == Messages.YES
        if (confirmed) {
            service.removeBranchPair(source, target)
            refresh()
        }
    }

    // ------------------------------------------------------------------ //
    //  历史审核人
    // ------------------------------------------------------------------ //

    private fun selectedReviewer(): String? {
        val row = reviewerTable.selectedRow
        return if (row >= 0) reviewerModel.getValueAt(row, 0) as String else null
    }

    private fun editReviewer() {
        val oldName = selectedReviewer() ?: run {
            Messages.showInfoMessage("请先在列表中选择一位审核人", "GitLab MR")
            return
        }
        val input = Messages.showInputDialog(
            "审核人 username:", "编辑历史审核人", Messages.getQuestionIcon(), oldName, null,
        )?.trim() ?: return
        if (input.isEmpty()) {
            Messages.showWarningDialog("审核人不能为空", "GitLab MR")
            return
        }
        service.updateReviewer(oldName, input)
        refresh()
    }

    private fun deleteReviewer() {
        val name = selectedReviewer() ?: run {
            Messages.showInfoMessage("请先在列表中选择一位审核人", "GitLab MR")
            return
        }
        val confirmed = Messages.showYesNoDialog(
            "确定删除历史审核人 \"$name\"?", "GitLab MR", Messages.getQuestionIcon(),
        ) == Messages.YES
        if (confirmed) {
            service.removeReviewer(name)
            refresh()
        }
    }
}

/**
 * 历史分支组合编辑弹窗
 */
private class BranchPairEditDialog(
    oldSource: String,
    oldTarget: String,
) : DialogWrapper(true) {

    private val sourceField = JTextField(oldSource, 24)
    private val targetField = JTextField(oldTarget, 24)

    init {
        title = "编辑历史分支组合"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
        }
        val grid = JPanel(GridBagLayout())
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        grid.add(JLabel("源分支:"), gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        grid.add(sourceField, gbc)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        grid.add(JLabel("目标分支:"), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0
        grid.add(targetField, gbc)
        return grid
    }

    /** 返回 (源分支, 目标分支)；未确认时返回 null */
    fun getResult(): Pair<String, String>? {
        if (!isOK) return null
        return sourceField.text.trim() to targetField.text.trim()
    }
}
