package com.gitlabmr.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
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
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel

/**
 * 设置子页:Settings → Other Settings → GitLabPlus → Access Token
 *
 * 各 GitLab 服务器的 Personal Access Token 的添加 / 修改 / 查看 / 删除。
 * Token 保存在插件本机配置文件中 (XML state);所有操作即时生效。
 */
class AccessTokenConfigurable : Configurable {

    private val service get() = GitLabMrConfigService.getInstance()

    private lateinit var tokenModel: DefaultTableModel
    private lateinit var tokenTable: JTable

    override fun getDisplayName(): String = "Access Token"

    override fun createComponent(): JComponent {
        // 迁移历史遗留的带协议 host 记录 (一次性)
        service.migrateLegacyHosts()

        tokenModel = object : DefaultTableModel(arrayOf("GitLab 地址", "Token 状态"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        tokenTable = JTable(tokenModel)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("添加...").apply { addActionListener { addToken() } })
            add(JButton("修改...").apply { addActionListener { editSelectedToken() } })
            add(JButton("查看").apply { addActionListener { showSelectedToken() } })
            add(JButton("删除").apply { addActionListener { deleteSelectedToken() } })
        }
        refreshTokenTable()

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(
                JBLabel(
                    "<html>管理各 GitLab 服务器的 Personal Access Token (操作立即生效)。<br>" +
                        "地址只填主机名 (可含端口)，如 <b>git.example.com:9000</b>。" +
                        "Token 在 GitLab → 用户设置 → Access Tokens 中创建，需勾选 api 权限。</html>"
                ),
                BorderLayout.NORTH,
            )
            add(JScrollPane(tokenTable), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    override fun isModified(): Boolean = false  // 所有操作即时生效，无待应用的修改
    override fun apply() {}
    override fun reset() {}

    // ------------------------------------------------------------------ //
    //  Token 增删改查
    // ------------------------------------------------------------------ //

    private fun refreshTokenTable() {
        while (tokenModel.rowCount > 0) tokenModel.removeRow(tokenModel.rowCount - 1)
        for (host in service.knownHosts().sorted()) {
            val status = if (service.loadToken(host) != null) "已保存" else "未设置"
            tokenModel.addRow(arrayOf(host, status))
        }
    }

    private fun selectedTokenHost(): String? {
        val row = tokenTable.selectedRow
        return if (row >= 0) tokenModel.getValueAt(row, 0) as String else null
    }

    private fun addToken() {
        val dialog = TokenEditDialog(null, "")
        if (!dialog.showAndGet()) return
        val result = dialog.getResult() ?: return
        service.saveToken(result.first, result.second)
        refreshTokenTable()
    }

    private fun editSelectedToken() {
        val host = selectedTokenHost()
        if (host == null) {
            Messages.showInfoMessage("请先在列表中选择一行 (或点击\"添加...\")", "GitLab MR")
            return
        }
        val dialog = TokenEditDialog(host, service.loadToken(host) ?: "")
        if (!dialog.showAndGet()) return
        val result = dialog.getResult() ?: return
        service.saveToken(result.first, result.second)
        refreshTokenTable()
    }

    private fun showSelectedToken() {
        val host = selectedTokenHost() ?: run {
            Messages.showInfoMessage("请先在列表中选择一行", "GitLab MR")
            return
        }
        val token = service.loadToken(host)
        Messages.showInfoMessage(
            if (token.isNullOrBlank()) "\"$host\" 尚未设置 Token"
            else "\"$host\" 的 Token:\n$token",
            "GitLab MR",
        )
    }

    private fun deleteSelectedToken() {
        val host = selectedTokenHost() ?: run {
            Messages.showInfoMessage("请先在列表中选择一行", "GitLab MR")
            return
        }
        val confirmed = Messages.showYesNoDialog(
            "确定删除 \"${host}\" 的 Token?", "GitLab MR", Messages.getQuestionIcon(),
        ) == Messages.YES
        if (confirmed) {
            service.removeToken(host)
            refreshTokenTable()
        }
    }
}

/**
 * Token 编辑弹窗 (existingHost 为空表示新增;修改时 host 只读)
 */
private class TokenEditDialog(
    existingHost: String?,
    currentToken: String,
) : DialogWrapper(true) {

    private val hostField = JTextField(existingHost ?: "", 30)
    private val tokenField = JPasswordField(currentToken, 30)

    init {
        title = if (existingHost == null) "添加 GitLab Token" else "修改 Token - $existingHost"
        hostField.isEditable = existingHost == null
        init()
    }

    override fun createCenterPanel(): JComponent {
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
        }
        val grid = JPanel(GridBagLayout())
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        grid.add(JLabel("GitLab 地址:"), gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        grid.add(hostField, gbc)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        grid.add(JLabel("Personal Access Token:"), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0
        grid.add(tokenField, gbc)

        return JPanel(BorderLayout()).apply {
            add(JBLabel("<html><br>在 GitLab → 用户设置 → Access Tokens 中创建，需勾选 api 权限</html>"), BorderLayout.NORTH)
            add(grid, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (hostField.text.trim().isEmpty()) {
            return ValidationInfo("GitLab 地址不能为空", hostField)
        }
        return null
    }

    /** 返回 (host, token)；token 可为空串表示清空 */
    fun getResult(): Pair<String, String>? {
        if (!isOK) return null
        return hostField.text.trim() to String(tokenField.password).trim()
    }
}
