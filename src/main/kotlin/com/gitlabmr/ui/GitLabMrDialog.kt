package com.gitlabmr.ui

import com.gitlabmr.api.MrRepoCandidate
import com.gitlabmr.api.MrRepoGateway
import com.gitlabmr.config.GitLabMrConfigService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 历史弹出列表的分隔符与空列表占位; Git 分支名不能含空格, " → " 可安全解析 */
private const val HISTORY_SEPARATOR = " → "
private const val HISTORY_EMPTY = "（暂无历史记录）"

/**
 * 首行的初始值: 项目 + 分支 (连接阶段已取到历史/默认值, 不再二次请求)
 */
data class MrRowInit(
    val candidate: MrRepoCandidate,
    val source: String,
    val target: String,
)

/**
 * MR 创建对话框: 多行计划表, 每行创建一个 MR。
 *
 * - 每行 = 本地 Git 仓库对应的 GitLab 项目 + 源/目标分支 + 标题; 可新增/删除行, 同一项目可多行
 * - 分支下拉按需从该项目的远端分支拉取 (后台线程 + 缓存), 也可直接输入
 * - 标题每行独立, "生成"按该行项目的分支差异生成
 * - 审核人全局单选, 支持输入过滤; 审核人同时作为指派人 (沿用旧语义)
 */
class GitLabMrDialog(
    private val gateway: MrRepoGateway,
    private val candidates: List<MrRepoCandidate>,
    private val initialRows: List<MrRowInit>,
    private val defaultReviewerText: String,
) : DialogWrapper(true) {

    /** 对话框最终提交的每一行 */
    data class MrRowResult(
        val candidate: MrRepoCandidate,
        val source: String,
        val target: String,
        val title: String,
    )

    private val rows = mutableListOf<MrRowPanel>()
    private val rowsPanel = RowsPanel()
    private val comboReviewer = SearchableComboBox()

    /** 审核人历史 (跨项目共享): 蓝色链接, 点开列表后点选带入 */
    private val linkReviewer = ActionLink("历史") { showReviewerHistoryPopup() }.apply {
        setDropDownLinkIcon()
    }
    private var disposed = false

    /** 防"旧请求覆盖新结果" (连续点生成 / 连续增删行) */
    private var titleRequestId = 0
    private var reviewerRequestId = 0

    var reviewer: String = ""
        private set

    init {
        title = if (candidates.size == 1) {
            "创建 Merge Request - ${candidates.first().repoName}"
        } else {
            "创建 Merge Request"
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.border = JBUI.Borders.empty(8)

        val scroll = JBScrollPane(rowsPanel)
        scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scroll.border = JBUI.Borders.empty()
        panel.add(scroll, BorderLayout.CENTER)

        // 审核人 / 历史链接 / 新增行 / 提示 放在同一行; 审核人输入框保持较窄
        val south = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(2, 4, 2, 6)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        south.add(JLabel("审核人:"), gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.0
        linkReviewer.toolTipText = "点击查看最近使用的审核人（所有项目共享）"
        south.add(linkReviewer, gbc)
        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.0
        comboReviewer.editor.item = defaultReviewerText
        comboReviewer.preferredSize =
            Dimension(JBUI.scale(240), comboReviewer.preferredSize.height)
        south.add(comboReviewer, gbc)
        gbc.gridx = 3; gbc.gridy = 0; gbc.weightx = 0.0
        south.add(JButton("+ 新增行").apply {
            addActionListener { addRow(candidates.first(), null, null) }
        }, gbc)
        gbc.gridx = 4; gbc.gridy = 0; gbc.weightx = 1.0
        south.add(JLabel("每行创建一个 MR；分支可直接输入或从下拉选择").apply {
            foreground = JBColor.GRAY
        }, gbc)

        panel.add(south, BorderLayout.SOUTH)

        // 触发时选中了几个仓库就自动建几行; 没有选中时退回当前仓库一行
        if (initialRows.isEmpty()) {
            addRow(candidates.first(), null, null)
        } else {
            initialRows.forEach { addRow(it.candidate, it.source, it.target) }
        }
        panel.preferredSize = Dimension(JBUI.scale(940), JBUI.scale(560))
        return panel
    }

    fun rowResults(): List<MrRowResult> = rows.map {
        MrRowResult(it.candidate, it.source(), it.target(), it.fieldTitle.text.trim())
    }

    override fun getPreferredFocusedComponent(): JComponent? = rows.firstOrNull()?.comboProject

    override fun doOKAction() {
        if (rows.isEmpty()) {
            setErrorText("至少需要一行")
            return
        }

        val problems = mutableListOf<String>()
        rows.forEachIndexed { index, row ->
            val source = row.source()
            val target = row.target()
            when {
                source.isEmpty() -> problems += "第 ${index + 1} 行: 源分支不能为空"
                target.isEmpty() -> problems += "第 ${index + 1} 行: 目标分支不能为空"
                source == target -> problems += "第 ${index + 1} 行: 源分支与目标分支不能相同"
                row.fieldTitle.text.isBlank() -> problems += "第 ${index + 1} 行: 标题不能为空"
            }
        }
        if (problems.isNotEmpty()) {
            setErrorText(problems.joinToString("；"))
            return
        }
        setErrorText(null)

        // 沿用旧语义: "username (name)" 里取 username; 列表外的手输内容原样提交
        reviewer = comboReviewer.currentText().let {
            val index = it.indexOf(" (")
            if (index > 0) it.substring(0, index).trim() else it
        }
        super.doOKAction()
    }

    override fun show() {
        // 显示前再确保一次可编辑下拉的 UI 编辑器缓存就位 (见 ensureEditableEditor):
        // 否则 DarculaComboBoxUI.getPreferredSize 在 pack 时会因缓存为空而 NPE
        comboReviewer.ensureEditableEditor()
        rows.forEach { it.ensureEditors() }
        super.show()
    }

    override fun dispose() {
        disposed = true
        super.dispose()
    }

    // ------------------------------------------------------------------ //
    //  行管理
    // ------------------------------------------------------------------ //

    private fun addRow(candidate: MrRepoCandidate, source: String?, target: String?) {
        val row = MrRowPanel(candidate)
        rows.add(row)
        if (source != null && target != null) {
            row.comboSource.editor.item = source
            row.comboTarget.editor.item = target
        } else {
            // 新增行: 默认取跨项目共享的历史分支组合 (最近一条), 没有历史则 develop → develop
            row.applyDefaults(GitLabMrConfigService.getInstance().defaultBranchPair())
        }
        relayoutRows()
        ensureRowData(row)
    }

    private fun removeRow(row: MrRowPanel) {
        if (rows.size <= 1) return
        rows.remove(row)
        relayoutRows()
        refreshReviewerItems()
    }

    /** 行号写进每行的 TitledBorder, 校验提示里的"第 N 行"要对得上 */
    private fun relayoutRows() {
        rowsPanel.removeAll()
        rows.forEachIndexed { index, row ->
            row.border = BorderFactory.createTitledBorder("MR ${index + 1}")
            row.setRemovable(rows.size > 1)
            rowsPanel.add(row, GridBagConstraints().apply {
                gridx = 0
                gridy = index
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(3, 0, 3, 0)
            })
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    // ------------------------------------------------------------------ //
    //  后台加载 (分支 / 成员)
    // ------------------------------------------------------------------ //

    private fun ensureRowData(row: MrRowPanel) {
        val candidate = row.candidate
        ApplicationManager.getApplication().executeOnPooledThread {
            val branches = gateway.branches(candidate)
            val error = gateway.apiError(candidate)
            ApplicationManager.getApplication().invokeLater({
                // 行已删除或又换了项目: 丢弃过期结果
                if (disposed || row !in rows || row.candidate.projectKey != candidate.projectKey) {
                    return@invokeLater
                }
                row.applyBranches(branches)
                row.showWarn(error ?: if (branches.isEmpty()) "未取到分支列表（可手动输入分支名）" else null)
                refreshReviewerItems()
            }, ModalityState.any())
        }
    }

    /** 审核人候选 = 所有行所涉项目的成员并集; 用请求号防旧结果覆盖 */
    private fun refreshReviewerItems() {
        val id = ++reviewerRequestId
        val snapshot = rows.map { it.candidate }.distinctBy { it.projectKey }
        ApplicationManager.getApplication().executeOnPooledThread {
            val members = gateway.mergedMembers(snapshot)
            ApplicationManager.getApplication().invokeLater({
                if (disposed || id != reviewerRequestId) return@invokeLater
                val current = comboReviewer.currentText()
                val username = current.substringBefore(" (").trim()
                comboReviewer.setAllItems(members.map { "${it.username} (${it.name})" })
                // 记忆里存的是 username, 匹配到成员就升级为完整展示串 (与旧版默认审核人的表现一致)
                members.firstOrNull { it.username == username }?.let {
                    comboReviewer.editor.item = "${it.username} (${it.name})"
                }
            }, ModalityState.any())
        }
    }

    /** 点开最近使用的审核人列表 (跨项目共享), 点选后带入审核人 */
    private fun showReviewerHistoryPopup() {
        val reviewers = GitLabMrConfigService.getInstance().recentReviewers()
        val items = if (reviewers.isEmpty()) listOf(HISTORY_EMPTY) else reviewers
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("最近使用的审核人")
            .setNamerForFiltering { it }
            .setItemChosenCallback { chosen ->
                if (chosen != HISTORY_EMPTY) comboReviewer.setUsername(chosen)
            }
            .createPopup()
            .showUnderneathOf(linkReviewer)
    }

    // ------------------------------------------------------------------ //
    //  标题生成
    // ------------------------------------------------------------------ //

    private fun onGenerateTitle(row: MrRowPanel) {
        val index = rows.indexOf(row)
        if (index < 0) return
        val source = row.source()
        val target = row.target()
        if (source.isEmpty() || target.isEmpty()) {
            setErrorText("第 ${index + 1} 行: 请先填写源分支与目标分支")
            return
        }
        if (source == target) {
            setErrorText("第 ${index + 1} 行: 源分支与目标分支相同")
            return
        }
        setErrorText(null)
        row.setGenerating(true)

        val id = ++titleRequestId
        val candidate = row.candidate
        ApplicationManager.getApplication().executeOnPooledThread {
            val generated = gateway.generateTitle(candidate, source, target)
            ApplicationManager.getApplication().invokeLater({
                row.setGenerating(false)
                if (disposed || row !in rows || id != titleRequestId) return@invokeLater
                val currentIndex = rows.indexOf(row) + 1
                if (generated.isNotBlank()) {
                    row.setTitle(generated)
                } else {
                    setErrorText(
                        "第 $currentIndex 行: 未找到 $source → $target 的差异提交（或读取失败），" +
                            "标题未生成，可手动填写"
                    )
                }
            }, ModalityState.any())
        }
    }

    // ------------------------------------------------------------------ //
    //  单行面板
    // ------------------------------------------------------------------ //

    private inner class MrRowPanel(initialCandidate: MrRepoCandidate) : JPanel(BorderLayout(0, JBUI.scale(4))) {

        var candidate: MrRepoCandidate = initialCandidate
            private set

        val comboProject = JComboBox<MrRepoCandidate>(candidates.toTypedArray()).apply {
            selectedItem = initialCandidate
        }

        /** 历史分支组合 (跨项目共享): 蓝色链接, 点开列表后点选带入源/目标分支 */
        private val linkBranchHistory = ActionLink("历史") { showBranchHistoryPopup() }.apply {
            setDropDownLinkIcon()
        }

        val comboSource = JComboBox<String>().apply { ensureEditableEditor() }
        val comboTarget = JComboBox<String>().apply { ensureEditableEditor() }
        val fieldTitle = JBTextArea(2, 30).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        private val btnRemove = JButton("删除")
        private val btnGen = JButton("生成")
        private val lblWarn = JLabel().apply { isVisible = false }

        /** 标题是否是"生成"填进去的: 换项目时清掉, 手改过的不动 */
        private var titleAuto = false
        private var suppressTitleChange = false

        init {
            // 第一行: 项目 / 历史分支组合 / 源分支 → 目标分支
            val top = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(2, 4, 2, 4)
            }
            gbc.gridy = 0
            gbc.gridx = 0; gbc.weightx = 0.0
            top.add(JLabel("项目:"), gbc)
            gbc.gridx = 1; gbc.weightx = 0.5
            top.add(comboProject, gbc)
            gbc.gridx = 2; gbc.weightx = 0.0
            linkBranchHistory.toolTipText = "点击查看历史分支组合（所有项目共享）"
            top.add(linkBranchHistory, gbc)
            gbc.gridx = 3; gbc.weightx = 0.0
            top.add(JLabel("源分支:"), gbc)
            gbc.gridx = 4; gbc.weightx = 1.0
            top.add(comboSource, gbc)
            gbc.gridx = 5; gbc.weightx = 0.0
            top.add(JLabel("→ 目标分支:"), gbc)
            gbc.gridx = 6; gbc.weightx = 1.0
            top.add(comboTarget, gbc)
            add(top, BorderLayout.NORTH)

            // 第二行: 标题单独一行 (多行文本) + 生成 / 删除
            val titleRow = JPanel(BorderLayout(0, JBUI.scale(4)))
            titleRow.add(JLabel("标题:"), BorderLayout.WEST)
            // 让多行标题看起来像输入框: 使用文本框背景 + 可见边框
            fieldTitle.background = UIUtil.getTextFieldBackground()
            fieldTitle.border = JBUI.Borders.empty(2, 4)
            titleRow.add(JBScrollPane(fieldTitle).apply {
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = BorderFactory.createLineBorder(JBColor.border())
            }, BorderLayout.CENTER)
            titleRow.add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                add(btnGen)
                add(btnRemove)
            }, BorderLayout.EAST)
            add(titleRow, BorderLayout.CENTER)

            add(lblWarn, BorderLayout.SOUTH)

            comboProject.addActionListener {
                val picked = comboProject.selectedItem as? MrRepoCandidate ?: return@addActionListener
                if (picked.projectKey == candidate.projectKey) return@addActionListener
                candidate = picked
                // 分支历史已全局共享, 换项目时保留已填分支; 只清掉旧项目自动生成的标题
                if (titleAuto) {
                    fieldTitle.text = ""
                    titleAuto = false
                }
                ensureRowData(this@MrRowPanel)
            }
            btnRemove.addActionListener { removeRow(this@MrRowPanel) }
            btnGen.addActionListener { onGenerateTitle(this@MrRowPanel) }

            fieldTitle.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = onTitleEdited()
                override fun removeUpdate(e: DocumentEvent) = onTitleEdited()
                override fun changedUpdate(e: DocumentEvent) = onTitleEdited()
            })
        }

        private fun onTitleEdited() {
            if (!suppressTitleChange) titleAuto = false
        }

        /** 点开历史分支组合列表 (跨项目共享), 点选后带入该行源/目标分支 */
        private fun showBranchHistoryPopup() {
            val pairs = GitLabMrConfigService.getInstance().recentBranchPairs()
            val items = if (pairs.isEmpty()) {
                listOf(HISTORY_EMPTY)
            } else {
                pairs.map { "${it.source}$HISTORY_SEPARATOR${it.target}" }
            }
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(items)
                .setTitle("历史分支组合")
                .setNamerForFiltering { it }
                .setItemChosenCallback { chosen ->
                    if (chosen != HISTORY_EMPTY) applyHistoryPair(chosen)
                }
                .createPopup()
                .showUnderneathOf(linkBranchHistory)
        }

        private fun applyHistoryPair(display: String) {
            val at = display.indexOf(HISTORY_SEPARATOR)
            if (at <= 0) return
            comboSource.editor.item = display.substring(0, at).trim()
            comboTarget.editor.item = display.substring(at + HISTORY_SEPARATOR.length).trim()
            if (titleAuto) {
                fieldTitle.text = ""
                titleAuto = false
            }
        }

        fun source(): String = (comboSource.editor.item as? String).orEmpty().trim()

        fun target(): String = (comboTarget.editor.item as? String).orEmpty().trim()

        fun applyBranches(branches: List<String>) {
            // removeAllItems 会清空编辑器文本, 先抓后回填
            val source = source()
            val target = target()
            comboSource.removeAllItems()
            branches.forEach { comboSource.addItem(it) }
            comboSource.editor.item = source
            comboTarget.removeAllItems()
            branches.forEach { comboTarget.addItem(it) }
            comboTarget.editor.item = target
        }

        fun applyDefaults(history: GitLabMrConfigService.BranchEntry) {
            comboSource.editor.item = history.source.ifEmpty { "develop" }
            comboTarget.editor.item = history.target.ifEmpty { "develop" }
            if (titleAuto) {
                fieldTitle.text = ""
                titleAuto = false
            }
        }

        fun setTitle(text: String) {
            suppressTitleChange = true
            fieldTitle.text = text
            titleAuto = true
            suppressTitleChange = false
        }

        fun setGenerating(generating: Boolean) {
            btnGen.isEnabled = !generating
            btnGen.text = if (generating) "生成中..." else "生成"
        }

        fun setRemovable(removable: Boolean) {
            btnRemove.isEnabled = removable
        }

        /** 显示前兜底安装可编辑下拉的 UI 编辑器 (见 ensureEditableEditor) */
        fun ensureEditors() {
            comboSource.ensureEditableEditor()
            comboTarget.ensureEditableEditor()
        }

        fun showWarn(message: String?) {
            lblWarn.text = message.orEmpty()
            lblWarn.isVisible = !message.isNullOrBlank()
            revalidate()
        }
    }
}

/** 行容器: 宽度跟随视口, 高度按内容增长 (超出时由 JScrollPane 滚动) */
private class RowsPanel : JPanel(GridBagLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
