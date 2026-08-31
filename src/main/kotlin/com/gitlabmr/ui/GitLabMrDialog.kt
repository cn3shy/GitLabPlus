package com.gitlabmr.ui

import com.gitlabmr.model.Member
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * MR 创建对话框
 *
 * 对应 Python 版 [interactive_gui] —— 用 IDEA 原生 UI 组件替代 Tkinter。
 *
 * 功能:
 * - 源分支 / 目标分支 / 审核人 三列并排
 * - 源/目标分支均展示全部远程分支，不做主干过滤
 * - 标题手动编辑，点击"重新生成"按钮才调用 API 按差异 commit 生成
 * - 审核人下拉选择 (有成员列表时) 或手动输入
 */
class GitLabMrDialog(
    private val repoName: String,
    private val defaultSource: String,
    private val defaultTarget: String,
    private val defaultReviewer: String,
    private val sourceBranches: List<String>,
    private val targetBranches: List<String>,
    private val members: List<Member>,
    private val onGenerateTitle: (source: String, target: String) -> String,
) : DialogWrapper(true) {

    private lateinit var comboSource: JComboBox<String>
    private lateinit var comboTarget: JComboBox<String>
    private var comboReviewer: JComboBox<String>? = null
    private var entryReviewer: JBTextField? = null
    private lateinit var textTitle: JBTextArea

    /** 用户最终填写的 MR 标题 (不能叫 title —— 会与 java.awt.Dialog#getTitle 意外重写冲突) */
    var mrTitle: String = ""
        private set
    var sourceBranch: String = ""
        private set
    var targetBranch: String = ""
        private set
    var reviewer: String = ""
        private set

    init {
        setTitle("创建 Merge Request - $repoName")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        // ---- 分支行 ----
        val branchPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(2, 2, 2, 6)
        }

        gbc.apply { gridx = 0; gridy = 0; weightx = 0.0 }
        branchPanel.add(JLabel("源分支:"), gbc)
        gbc.apply { gridx = 0; gridy = 1; weightx = 1.0 }
        comboSource = JComboBox(sourceBranches.toTypedArray()).apply {
            isEditable = true
            selectedItem = defaultSource
        }
        branchPanel.add(comboSource, gbc)

        gbc.apply { gridx = 1; gridy = 0; weightx = 0.0 }
        branchPanel.add(JLabel("目标分支:"), gbc)
        gbc.apply { gridx = 1; gridy = 1; weightx = 1.0 }
        comboTarget = JComboBox(targetBranches.toTypedArray()).apply {
            isEditable = true
            selectedItem = defaultTarget
        }
        branchPanel.add(comboTarget, gbc)

        gbc.apply { gridx = 2; gridy = 0; weightx = 0.0 }
        branchPanel.add(JLabel("审核人:"), gbc)
        gbc.apply { gridx = 2; gridy = 1; weightx = 1.0 }
        if (members.isNotEmpty()) {
            val memberMap = members.associateBy { "${it.username} (${it.name})" }
            val displayNames = memberMap.keys.toList()
            comboReviewer = JComboBox(displayNames.toTypedArray()).apply {
                isEditable = true
                // 尝试选中默认审核人
                val defaultReviewerName = defaultReviewer.split(",").firstOrNull()?.trim() ?: ""
                val matchedMember = members.find { it.username == defaultReviewerName }
                if (matchedMember != null) {
                    selectedItem = "${matchedMember.username} (${matchedMember.name})"
                }
            }
            branchPanel.add(comboReviewer!!, gbc)
        } else {
            entryReviewer = JBTextField(defaultReviewer.split(",").firstOrNull()?.trim() ?: "")
            branchPanel.add(entryReviewer!!, gbc)
        }

        panel.add(branchPanel, BorderLayout.NORTH)

        // ---- 标题区域 ----
        val titlePanel = JPanel(BorderLayout(0, 4))
        val titleButtonRow = JPanel(BorderLayout())
        titleButtonRow.add(JLabel("标题 (可点击\"重新生成\"自动填写):"), BorderLayout.WEST)
        val btnRefresh = JButton("重新生成").apply {
            addActionListener { refreshTitle() }
        }
        titleButtonRow.add(btnRefresh, BorderLayout.EAST)
        titlePanel.add(titleButtonRow, BorderLayout.NORTH)

        textTitle = JBTextArea(3, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JScrollPane(textTitle)
        titlePanel.add(scrollPane, BorderLayout.CENTER)
        panel.add(titlePanel, BorderLayout.CENTER)

        // 不在切换分支时自动生成标题 —— 仅点击"重新生成"按钮时才调用 API 生成
        panel.preferredSize = java.awt.Dimension(620, 300)
        return panel
    }

    private fun refreshTitle() {
        val source = (comboSource.editor.item as? String ?: defaultSource).trim().ifEmpty { defaultSource }
        val target = (comboTarget.editor.item as? String ?: defaultTarget).trim().ifEmpty { defaultTarget }
        textTitle.text = onGenerateTitle(source, target)
    }

    override fun doOKAction() {
        val source = (comboSource.editor.item as? String ?: defaultSource).trim().ifEmpty { defaultSource }
        val target = (comboTarget.editor.item as? String ?: defaultTarget).trim().ifEmpty { defaultTarget }

        val titleText = textTitle.text.trim()
        if (titleText.isEmpty()) {
            setError("标题不能为空")
            return
        }

        mrTitle = titleText
        sourceBranch = source
        targetBranch = target
        reviewer = comboReviewer?.let { combo ->
            val selected = (combo.editor.item as? String ?: "").trim()
            if (selected.isEmpty()) return@let ""
            // 如果是 "username (name)" 格式，提取 username
            val parenIdx = selected.indexOf(" (")
            if (parenIdx > 0) selected.substring(0, parenIdx) else selected
        } ?: entryReviewer?.text?.trim() ?: ""

        super.doOKAction()
    }

    /**
     * 在对话框底部显示错误信息
     */
    private fun setError(message: String) {
        setErrorText(message)
    }
}
