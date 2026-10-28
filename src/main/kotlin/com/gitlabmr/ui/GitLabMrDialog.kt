package com.gitlabmr.ui

import com.gitlabmr.api.MrRepoCandidate
import com.gitlabmr.api.MrRepoGateway
import com.gitlabmr.config.GitLabMrConfigService
import com.intellij.icons.AllIcons
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
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 历史弹出列表的分隔符与空列表占位; Git 分支名不能含空格, " → " 可安全解析 */
private const val HISTORY_SEPARATOR = " → "
private const val HISTORY_EMPTY = "（暂无历史记录）"

/** 分支历史下拉的占位项: 选中真实条目填入所有行后回到占位符, 便于再次选择 */
private const val HISTORY_PLACEHOLDER = "点击选择组合"

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
 *   行内为三段式: "MR N" 加粗标题 + 垃圾桶删除按钮 / 项目与分支下拉 / 标题 + 🔄 刷新按钮, 行间细灰线分隔
 * - 分支下拉按需从该项目的远端分支拉取 (后台线程 + 缓存), 也可直接输入
 * - 标题每行独立, 行内 🔄 按该行项目的分支差异刷新;
 *   底部 "生成全部标题" 逐条串行生成, 源/目标无效的行跳过并汇总提示
 * - "分支历史" 下拉选中后填入所有行的源/目标分支 (跨项目共享历史)
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

    /** 分支历史下拉 (跨项目共享): 选中后填入所有行的源/目标分支, 随后回到占位符 */
    private val comboBranchHistory = JComboBox<String>()

    private val btnGenAll = JButton("生成全部标题")
    private var disposed = false

    /** 防"旧请求覆盖新结果" (连续点刷新 / 连续增删行) */
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
        panel.border = JBUI.Borders.empty(16)

        val scroll = JBScrollPane(rowsPanel)
        scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scroll.border = JBUI.Borders.empty()
        panel.add(scroll, BorderLayout.CENTER)

        panel.add(buildSouthPanel(), BorderLayout.SOUTH)

        // 触发时选中了几个仓库就自动建几行; 没有选中时退回当前仓库一行
        if (initialRows.isEmpty()) {
            addRow(candidates.first(), null, null)
        } else {
            initialRows.forEach { addRow(it.candidate, it.source, it.target) }
        }
        refreshBranchHistoryItems()
        panel.preferredSize = Dimension(JBUI.scale(940), JBUI.scale(560))
        return panel
    }

    /**
     * 底部两行 (各自独立 FlowLayout, 互不影响列宽):
     * - 分支历史下拉 + [+ 新增行] + [生成全部标题]
     * - 审核人 + 历史链接 + 灰色提示文字 (同一行)
     */
    private fun buildSouthPanel(): JComponent {
        // ---- 行 1: 分支历史 / 新增行 / 生成全部标题 ----
        val rowHistory = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        rowHistory.add(JLabel("分支历史:"))
        comboBranchHistory.preferredSize =
            Dimension(JBUI.scale(220), comboBranchHistory.preferredSize.height)
        comboBranchHistory.toolTipText = "点击查看历史分支组合（所有项目共享），选中后填入所有行"
        // 程序化回填占位符也会触发 ActionEvent, 只处理用户点选的真实条目
        comboBranchHistory.addActionListener {
            if (comboBranchHistory.selectedIndex > 0) onBranchHistorySelected()
        }
        rowHistory.add(comboBranchHistory)
        rowHistory.add(JButton("+ 新增行").apply {
            addActionListener { addRow(candidates.first(), null, null) }
        })
        btnGenAll.toolTipText = "逐条按各行的源/目标分支差异生成标题，无效的行自动跳过并汇总提示"
        btnGenAll.addActionListener { onGenerateAllTitles() }
        rowHistory.add(btnGenAll)

        // ---- 行 2: 审核人 / 历史链接 / 提示文字 ----
        val rowReviewer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        rowReviewer.add(JLabel("审核人:"))
        linkReviewer.toolTipText = "点击查看最近使用的审核人（所有项目共享）"
        rowReviewer.add(linkReviewer)
        comboReviewer.editor.item = defaultReviewerText
        comboReviewer.preferredSize =
            Dimension(JBUI.scale(240), comboReviewer.preferredSize.height)
        rowReviewer.add(comboReviewer)
        rowReviewer.add(JLabel("💡 每行创建一个 MR；分支可自定义。").apply {
            foreground = JBColor.GRAY
        })

        val south = JPanel()
        south.layout = BoxLayout(south, BoxLayout.Y_AXIS)
        south.add(rowHistory)
        south.add(Box.createVerticalStrut(JBUI.scale(4)))
        south.add(rowReviewer)
        return south
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

    /** 行号写进每行的 "MR N" 标题, 校验提示里的"第 N 行"要对得上; 行间细灰线分隔 */
    private fun relayoutRows() {
        rowsPanel.removeAll()
        rows.forEachIndexed { index, row ->
            row.setIndexLabel(index + 1)
            row.setRemovable(rows.size > 1)
            rowsPanel.add(row, GridBagConstraints().apply {
                gridx = 0
                gridy = index * 2
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 0, 4, 0)
            })
            if (index < rows.size - 1) {
                rowsPanel.add(JSeparator(), GridBagConstraints().apply {
                    gridx = 0
                    gridy = index * 2 + 1
                    weightx = 1.0
                    weighty = 0.0
                    fill = GridBagConstraints.HORIZONTAL
                    insets = Insets(0, 4, 0, 4)
                })
            }
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    // ------------------------------------------------------------------ //
    //  分支历史 (跨项目共享, 填入所有行)
    // ------------------------------------------------------------------ //

    /** 重装分支历史下拉候选: 首项占位符, 其后为历史组合 (无历史时显示空占位) */
    private fun refreshBranchHistoryItems() {
        val pairs = GitLabMrConfigService.getInstance().recentBranchPairs()
        val items = mutableListOf(HISTORY_PLACEHOLDER)
        if (pairs.isEmpty()) {
            items += HISTORY_EMPTY
        } else {
            pairs.forEach { items += "${it.source}$HISTORY_SEPARATOR${it.target}" }
        }
        comboBranchHistory.model = DefaultComboBoxModel(items.toTypedArray())
        comboBranchHistory.selectedIndex = 0
    }

    /** 分支历史选中: 填入所有行的源/目标分支, 随后回到占位符以便再次选择 */
    private fun onBranchHistorySelected() {
        val chosen = comboBranchHistory.selectedItem as? String ?: return
        if (chosen == HISTORY_PLACEHOLDER || chosen == HISTORY_EMPTY) {
            if (chosen == HISTORY_EMPTY) comboBranchHistory.selectedIndex = 0
            return
        }
        rows.forEach { it.applyHistoryPair(chosen) }
        comboBranchHistory.selectedIndex = 0
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

    /** 行内 🔄: 单行刷新标题 (逻辑与旧"生成"一致) */
    private fun onRefreshTitle(row: MrRowPanel) {
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

    /**
     * 底部 "生成全部标题": 逐条串行生成, 按钮显示进度 (n/N);
     * 源/目标为空或相同的行跳过并汇总提示。生成期间行内 🔄 一并禁用, 避免与单行刷新互相覆盖。
     */
    private fun onGenerateAllTitles() {
        val id = ++titleRequestId
        val invalid = mutableListOf<String>()
        val tasks = mutableListOf<Triple<MrRowPanel, String, String>>()
        rows.forEachIndexed { index, row ->
            val source = row.source()
            val target = row.target()
            when {
                source.isEmpty() || target.isEmpty() ->
                    invalid += "第 ${index + 1} 行: 请先填写源分支与目标分支"
                source == target -> invalid += "第 ${index + 1} 行: 源分支与目标分支相同"
                else -> tasks += Triple(row, source, target)
            }
        }
        setErrorText(if (invalid.isEmpty()) null else invalid.joinToString("；"))
        if (tasks.isEmpty()) return

        tasks.forEach { it.first.setGenerating(true) }
        btnGenAll.isEnabled = false
        btnGenAll.text = if (tasks.size > 1) "生成中 (0/${tasks.size})..." else "生成中..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val failures = mutableListOf<String>()
            tasks.forEachIndexed { i, task ->
                val (row, source, target) = task
                if (tasks.size > 1) {
                    ApplicationManager.getApplication().invokeLater({
                        if (!disposed) btnGenAll.text = "生成中 (${i + 1}/${tasks.size})..."
                    }, ModalityState.any())
                }
                val generated = gateway.generateTitle(row.candidate, source, target)
                ApplicationManager.getApplication().invokeLater({
                    row.setGenerating(false)
                    if (disposed || id != titleRequestId || row !in rows) return@invokeLater
                    if (generated.isNotBlank()) {
                        row.setTitle(generated)
                    } else {
                        val currentIndex = rows.indexOf(row) + 1
                        failures += "第 $currentIndex 行: 未找到 $source → $target 的差异提交（或读取失败），" +
                            "标题未生成，可手动填写"
                    }
                }, ModalityState.any())
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                btnGenAll.isEnabled = true
                btnGenAll.text = "生成全部标题"
                if (failures.isNotEmpty() && id == titleRequestId) {
                    setErrorText(failures.joinToString("；"))
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

        val comboSource = JComboBox<String>().apply { ensureEditableEditor() }
        val comboTarget = JComboBox<String>().apply { ensureEditableEditor() }
        val fieldTitle = JBTextArea(2, 30).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        /** 行头 "MR N" 加粗标题 */
        private val lblIndex = JLabel().apply { font = font.deriveFont(Font.BOLD) }

        private val btnRemove = JButton(AllIcons.General.Remove).apply {
            toolTipText = "删除此 MR"
        }
        private val btnRefresh = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "刷新标题"
        }
        private val lblWarn = JLabel().apply { isVisible = false }

        /** 标题是否是"生成"填进去的: 换项目时清掉, 手改过的不动 */
        private var titleAuto = false
        private var suppressTitleChange = false

        init {
            // 行头: "MR N" 加粗居左, 垃圾桶删除按钮居右
            val header = JPanel(BorderLayout(0, 0))
            header.add(lblIndex, BorderLayout.WEST)
            header.add(btnRemove, BorderLayout.EAST)
            add(header, BorderLayout.NORTH)

            // 分支行: [项目 ▼] [源分支 ▼] → [目标分支 ▼]
            val branches = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(2, 4, 2, 4)
            }
            gbc.gridy = 0
            gbc.gridx = 0; gbc.weightx = 0.5
            branches.add(comboProject, gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            branches.add(comboSource, gbc)
            gbc.gridx = 2; gbc.weightx = 0.0
            branches.add(JLabel("→").apply { horizontalAlignment = SwingConstants.CENTER }, gbc)
            gbc.gridx = 3; gbc.weightx = 1.0
            branches.add(comboTarget, gbc)
            add(branches, BorderLayout.CENTER)

            // 标题行: "标题:" + 多行文本框 + 🔄 刷新按钮; 警告文字在标题下方
            val titleRow = JPanel(BorderLayout(0, JBUI.scale(4)))
            titleRow.add(JLabel("标题:"), BorderLayout.WEST)
            // 让多行标题看起来像输入框: 使用文本框背景 + 可见边框
            fieldTitle.background = UIUtil.getTextFieldBackground()
            fieldTitle.border = JBUI.Borders.empty(2, 4)
            titleRow.add(JBScrollPane(fieldTitle).apply {
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = BorderFactory.createLineBorder(JBColor.border())
            }, BorderLayout.CENTER)
            titleRow.add(btnRefresh, BorderLayout.EAST)

            val bottom = JPanel(BorderLayout(0, JBUI.scale(2)))
            bottom.add(titleRow, BorderLayout.NORTH)
            bottom.add(lblWarn, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)

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
            btnRefresh.addActionListener { onRefreshTitle(this@MrRowPanel) }

            fieldTitle.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = onTitleEdited()
                override fun removeUpdate(e: DocumentEvent) = onTitleEdited()
                override fun changedUpdate(e: DocumentEvent) = onTitleEdited()
            })
        }

        private fun onTitleEdited() {
            if (!suppressTitleChange) titleAuto = false
        }

        /** 历史分支组合填入该行 (由底部下拉统一触发); 自动生成的旧标题清掉, 手填的不动 */
        fun applyHistoryPair(display: String) {
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
            btnRefresh.isEnabled = !generating
            btnRefresh.toolTipText = if (generating) "生成中..." else "刷新标题"
        }

        fun setRemovable(removable: Boolean) {
            btnRemove.isEnabled = removable
        }

        fun setIndexLabel(index: Int) {
            lblIndex.text = "MR $index"
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
