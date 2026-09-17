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
 * 设置子页:Settings → Other Settings → GitLabPlus → 项目记忆
 *
 * 各项目上次使用的源 / 目标分支与审核人 (创建 MR 时自动带入),可编辑或清除。
 */
class ProjectMemoryConfigurable : Configurable {

    private val service get() = GitLabMrConfigService.getInstance()

    private lateinit var memModel: DefaultTableModel
    private lateinit var memTable: JTable

    override fun getDisplayName(): String = "项目记忆"

    override fun createComponent(): JComponent {
        memModel = object : DefaultTableModel(
            arrayOf("项目 (地址/路径)", "上次源分支", "上次目标分支", "上次审核人"), 0
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        memTable = JTable(memModel)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("编辑...").apply { addActionListener { editSelectedMemory() } })
            add(JButton("清除").apply { addActionListener { deleteSelectedMemory() } })
        }
        refreshMemoryTable()

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(
                JBLabel("<html>每个项目上次使用的源 / 目标分支与审核人 (创建 MR 时自动带入)。</html>"),
                BorderLayout.NORTH,
            )
            add(JScrollPane(memTable), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    override fun isModified(): Boolean = false  // 所有操作即时生效，无待应用的修改
    override fun apply() {}
    override fun reset() {}

    // ------------------------------------------------------------------ //
    //  项目记忆增删改
    // ------------------------------------------------------------------ //

    private fun refreshMemoryTable() {
        while (memModel.rowCount > 0) memModel.removeRow(memModel.rowCount - 1)
        for (key in service.projectKeys().sorted()) {
            val branches = service.loadLastBranchesByKey(key)
            memModel.addRow(
                arrayOf(key, branches.source, branches.target, service.loadLastReviewersByKey(key))
            )
        }
    }

    private fun selectedMemoryKey(): String? {
        val row = memTable.selectedRow
        return if (row >= 0) memModel.getValueAt(row, 0) as String else null
    }

    private fun editSelectedMemory() {
        val key = selectedMemoryKey() ?: run {
            Messages.showInfoMessage("请先在列表中选择一行", "GitLab MR")
            return
        }
        val dialog = MemoryEditDialog(
            key,
            service.loadLastBranchesByKey(key),
            service.loadLastReviewersByKey(key),
        )
        if (!dialog.showAndGet()) return
        val (source, target, reviewers) = dialog.getResult() ?: return
        service.updateLastBranches(key, source, target)
        service.updateLastReviewers(key, reviewers)
        refreshMemoryTable()
    }

    private fun deleteSelectedMemory() {
        val key = selectedMemoryKey() ?: run {
            Messages.showInfoMessage("请先在列表中选择一行", "GitLab MR")
            return
        }
        val confirmed = Messages.showYesNoDialog(
            "确定清除 \"${key}\" 的全部记忆?", "GitLab MR", Messages.getQuestionIcon(),
        ) == Messages.YES
        if (confirmed) {
            service.removeProjectMemory(key)
            refreshMemoryTable()
        }
    }
}

/**
 * 项目记忆编辑弹窗 (projectKey 只读)
 */
private class MemoryEditDialog(
    private val projectKey: String,
    branches: GitLabMrConfigService.BranchEntry,
    reviewers: String,
) : DialogWrapper(true) {

    private val sourceField = JTextField(branches.source, 24)
    private val targetField = JTextField(branches.target, 24)
    private val reviewerField = JTextField(reviewers, 24)

    init {
        title = "编辑项目记忆 - $projectKey"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
        }
        val grid = JPanel(GridBagLayout())
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        grid.add(JLabel("项目:"), gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        grid.add(JLabel(projectKey), gbc)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        grid.add(JLabel("上次源分支:"), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0
        grid.add(sourceField, gbc)
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        grid.add(JLabel("上次目标分支:"), gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0
        grid.add(targetField, gbc)
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        grid.add(JLabel("上次审核人:"), gbc)
        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 1.0
        grid.add(reviewerField, gbc)

        return JPanel(BorderLayout()).apply {
            add(
                JBLabel("<html><br>审核人多个用英文逗号分隔；清空表示不记忆。</html>"),
                BorderLayout.NORTH,
            )
            add(grid, BorderLayout.CENTER)
        }
    }

    /** 返回 (源分支, 目标分支, 审核人) */
    fun getResult(): Triple<String, String, String>? {
        if (!isOK) return null
        return Triple(sourceField.text.trim(), targetField.text.trim(), reviewerField.text.trim())
    }
}
