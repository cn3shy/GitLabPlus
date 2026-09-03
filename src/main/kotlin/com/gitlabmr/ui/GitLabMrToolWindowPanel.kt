package com.gitlabmr.ui

import com.gitlabmr.api.ApiException
import com.gitlabmr.api.GitLabServerApi
import com.gitlabmr.api.TokenInvalidException
import com.gitlabmr.config.GitLabMrConfigService
import com.gitlabmr.model.MrItem
import com.gitlabmr.model.MrListResult
import com.gitlabmr.model.MrScopes
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.xml.util.XmlStringUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

private val LOG = logger<GitLabMrToolWindowPanel>()

/** 状态 / 范围下拉选项 (label 显示 / value 传 API) */
private class StateOption(val label: String, val value: String) {
    override fun toString(): String = label
}

/** Group 分组节点 */
private data class GroupInfo(val name: String, val count: Int)

/** 项目分组节点 */
private data class ProjectInfo(val path: String, val count: Int)

/**
 * "查看 Merge Request" 侧边栏工具窗口面板 (参考 Project 视图，停靠在 IDE 侧边栏)
 *
 * - 顶部一行过滤：服务器下拉 (设置页配置列表) + 状态 + 范围；"查询" 按钮固定在右侧，
 *   窗口再窄也始终可见 (旧对话框一行 FlowLayout 排不下会把查询按钮挤出去)
 * - tree 上方工具栏：全部展开 / 全部收缩 (旧版在顶部过滤行，窄窗口同样会被挤掉)
 * - 中部：树形展示，Group → 项目 → MR 三层，默认全部展开，双击 / 回车在浏览器打开 MR 页面
 * - 底部：当前用户与命中统计；面板打开时按上次查询条件自动查询一次
 */
class GitLabMrToolWindowPanel(private val project: Project) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private var comboHost: JComboBox<String> = JComboBox()
    private val comboState = JComboBox(
        arrayOf(
            StateOption("已打开", "opened"),
            StateOption("已合并", "merged"),
            StateOption("已关闭", "closed"),
        )
    )
    private val comboScope = JComboBox(
        arrayOf(
            StateOption("我创建的", MrScopes.CREATED_BY_ME),
            StateOption("指给我的", MrScopes.ASSIGNED_TO_ME),
        )
    )

    private lateinit var tree: Tree
    private lateinit var treeModel: DefaultTreeModel
    private lateinit var statusLabel: JBLabel
    private lateinit var btnQuery: JButton

    // 渲染器必须在数组等初始化之后、UI 构建之前声明，声明顺序不能后移
    private val mrRenderer = object : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as DefaultMutableTreeNode
            when (val obj = node.userObject) {
                is MrItem -> {
                    append("!${obj.iid} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(obj.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  [${stateLabel(obj.state)}]", stateAttributes(obj.state))
                    append("  ${obj.sourceBranch} → ${obj.targetBranch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = tooltipHtml(obj)
                }
                is GroupInfo -> {
                    append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" (${obj.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ProjectInfo -> {
                    append(obj.path, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(" (${obj.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is String -> {
                    val failed = obj.startsWith("查询失败")
                    append(obj, if (failed) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }

    init {
        border = JBUI.Borders.empty(8)
        add(buildTop(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildBottom(), BorderLayout.SOUTH)

        // 默认载入上次查询条件 (值不在可选项中时保持第一项，兼容旧版本存的 "all")
        val config = GitLabMrConfigService.getInstance()
        selectOption(comboState, config.loadLastViewState())
        selectOption(comboScope, config.loadLastViewScope())
        refresh()  // 首次打开按上次条件自动查询
    }

    // ------------------------------------------------------------------ //
    //  UI 构建
    // ------------------------------------------------------------------ //

    /**
     * 顶部过滤行：服务器 + 状态 + 范围 (中间，可压缩) + 查询按钮 (EAST，常驻可见)
     */
    private fun buildTop(): JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        val filters = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, JBUI.scale(2), 0, JBUI.scale(2))
        }
        gbc.gridx = 0; gbc.weightx = 0.0; filters.add(JLabel("服务器:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; filters.add(comboHost, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0; filters.add(JLabel("状态:"), gbc)
        gbc.gridx = 3; gbc.weightx = 0.6; filters.add(comboState, gbc)
        gbc.gridx = 4; gbc.weightx = 0.0; filters.add(JLabel("范围:"), gbc)
        gbc.gridx = 5; gbc.weightx = 0.6; filters.add(comboScope, gbc)

        btnQuery = JButton("查询").apply { addActionListener { refresh() } }
        add(filters, BorderLayout.CENTER)
        add(btnQuery, BorderLayout.EAST)
    }

    /**
     * 中部：tree 上方工具栏 (全部展开 / 全部收缩 + 操作提示) + MR 树
     */
    private fun buildCenter(): JComponent = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        treeModel = DefaultTreeModel(DefaultMutableTreeNode("正在加载..."))
        tree = Tree(treeModel).apply {
            isRootVisible = true
            cellRenderer = mrRenderer
            toggleClickCount = 0  // 禁用双击展开/收起，双击留给"打开链接"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) openSelectedUrl()
                }
            })
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "openMr")
            actionMap.put("openMr", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = openSelectedUrl()
            })
        }

        val toolbar = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                add(JButton("全部展开").apply { addActionListener { expandAll(true) } })
                add(JButton("全部收缩").apply { addActionListener { expandAll(false) } })
            }
            add(buttons, BorderLayout.WEST)
            add(JBLabel("双击条目在浏览器打开").apply { foreground = JBColor.GRAY }, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JScrollPane(tree), BorderLayout.CENTER)
    }

    /** 底部：当前用户与命中统计 */
    private fun buildBottom(): JComponent = JPanel(BorderLayout()).apply {
        statusLabel = JBLabel(" ")
        add(statusLabel, BorderLayout.WEST)
    }

    /** 按 value 选中下拉项，无匹配时不改动 (保持第一项) */
    private fun selectOption(combo: JComboBox<StateOption>, value: String) {
        for (i in 0 until combo.itemCount) {
            if (combo.getItemAt(i).value == value) {
                combo.selectedIndex = i
                return
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  查询与树构建
    // ------------------------------------------------------------------ //

    private fun selectedState(): String = (comboState.selectedItem as? StateOption)?.value ?: "opened"

    private fun selectedScope(): String =
        (comboScope.selectedItem as? StateOption)?.value ?: MrScopes.ALL

    /**
     * 同步设置页中保存的服务器列表 (设置后无需重启插件)：
     * 下拉里补充新增的服务器，选中的服务器被移除时回退到记忆服务器 / 第一个
     */
    private fun refreshHostOptions() {
        val config = GitLabMrConfigService.getInstance()
        val hosts = config.knownHosts()
            .filter { !config.loadToken(it).isNullOrBlank() }
            .sorted()
        if (hosts.isEmpty()) {
            comboHost.model = DefaultComboBoxModel()
            return
        }
        val current = comboHost.selectedItem as? String
        comboHost.model = DefaultComboBoxModel(hosts.toTypedArray())
        if (current != null && current in hosts) {
            comboHost.selectedItem = current
        } else {
            val defaultHost = config.loadLastViewHost().takeIf { it in hosts } ?: hosts.first()
            comboHost.selectedItem = defaultHost
        }
    }

    private fun refresh() {
        refreshHostOptions()
        val host = comboHost.selectedItem as? String
        if (host == null) {
            setRootText("没有已配置 Token 的服务器，请到 Settings → Tools → GitLab MR 添加")
            statusLabel.text = " "
            return
        }
        val state = selectedState()
        val scope = selectedScope()

        btnQuery.isEnabled = false
        setRootText("正在加载...")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "查询 Merge Request...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = try {
                    queryMergeRequests(host, state, scope)
                } catch (ex: Exception) {
                    MrListResult(null, emptyList(), "查询失败: ${ex.message}", false)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    btnQuery.isEnabled = true
                    applyResult(host, result)
                }
            }
        })
    }

    /**
     * 查询指定服务器上的 MR，合并去重
     *
     * @param scope [MrScopes.ALL] 时查询 created_by_me + assigned_to_me 合并，
     *              否则只查指定范围
     */
    private fun queryMergeRequests(host: String, state: String, scope: String): MrListResult {
        val config = GitLabMrConfigService.getInstance()
        val token = config.loadToken(host)
            ?: return MrListResult(null, emptyList(), "尚未配置 \"${host}\" 的 Token", true)

        val api = GitLabServerApi(host, token)
        return try {
            val user = api.resolveCurrentUser()

            val created = if (scope != MrScopes.ASSIGNED_TO_ME) {
                api.fetchMergeRequests(MrScopes.CREATED_BY_ME, state)
            } else emptyList()
            val assigned = if (scope != MrScopes.CREATED_BY_ME) {
                api.fetchMergeRequests(MrScopes.ASSIGNED_TO_ME, state)
            } else emptyList()

            // 两个 scope 合并去重 (web_url 唯一)，同时打上命中标记
            val merged = LinkedHashMap<String, MrItem>()
            for (mr in created) merged[mr.webUrl] = mr.copy(createdByMe = true)
            for (mr in assigned) {
                merged[mr.webUrl] = (merged[mr.webUrl] ?: mr).copy(assignedToMe = true)
            }

            config.saveLastViewHost(host)
            config.saveLastViewState(state)
            config.saveLastViewScope(scope)
            MrListResult(
                user = user,
                items = merged.values.sortedByDescending { it.updatedAt },
                error = null,
                tokenProblem = false,
                truncated = state == "merged" && (created.size >= 100 || assigned.size >= 100),
            )
        } catch (ex: TokenInvalidException) {
            MrListResult(null, emptyList(), "Token 无效或已过期，请在设置中更新", true)
        } catch (ex: ApiException) {
            MrListResult(null, emptyList(), ex.message ?: "查询失败", false)
        } catch (ex: Exception) {
            LOG.warn("查询 MR 列表失败: ${ex.message}")
            MrListResult(null, emptyList(), "查询失败: ${ex.message}", false)
        }
    }

    private fun applyResult(host: String, result: MrListResult) {
        // ---- 错误 ----
        if (result.error != null) {
            val root = DefaultMutableTreeNode("查询失败: ${result.error}")
            if (result.tokenProblem) {
                root.add(DefaultMutableTreeNode("请到 Settings → Tools → GitLab MR 检查 \"${host}\" 的 Token"))
            }
            setRoot(root)
            statusLabel.text = " "
            return
        }

        val items = result.items
        if (items.isEmpty()) {
            setRootText("没有符合条件的 Merge Request")
            statusLabel.text = " "
            return
        }

        // ---- Group → 项目 → MR 三层树 ----
        val truncatedTip = if (result.truncated) " (已合并仅显示前 100 条)" else ""
        val root = DefaultMutableTreeNode("GitLab: ${host} (${items.size} 个 MR)$truncatedTip")
        for ((groupName, groupMrs) in items.groupBy { it.groupName() }.toSortedMap()) {
            val groupNode = DefaultMutableTreeNode(GroupInfo(groupName, groupMrs.size))
            for ((path, projMrs) in groupMrs.groupBy { it.projectPath() ?: it.webUrl }.toSortedMap()) {
                val projNode = DefaultMutableTreeNode(ProjectInfo(path, projMrs.size))
                projMrs.sortedByDescending { it.updatedAt }.forEach { mr ->
                    projNode.add(DefaultMutableTreeNode(mr))
                }
                groupNode.add(projNode)
            }
            root.add(groupNode)
        }
        setRoot(root)

        val createdCount = items.count { it.createdByMe }
        val assignedCount = items.count { it.assignedToMe }
        statusLabel.text =
            "当前用户: ${result.user?.username ?: "-"} · 共 ${items.size} 个 (我创建 $createdCount · 指给我 $assignedCount)"
    }

    private fun setRootText(text: String) = setRoot(DefaultMutableTreeNode(text))

    private fun setRoot(root: DefaultMutableTreeNode) {
        treeModel.setRoot(root)
        treeModel.reload()
        expandAll(true)
    }

    /** 展开 / 收起全部节点 (root 始终可见) */
    private fun expandAll(expand: Boolean) {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        val path = TreePath(root.path)
        if (expand) {
            for (row in 0 until tree.rowCount) tree.expandRow(row)
            tree.expandPath(path)
        } else {
            for (row in 0 until tree.rowCount) tree.collapseRow(row)
            tree.collapsePath(path)  // 收缩时也把 root 折起，只留一行总览
        }
    }

    // ------------------------------------------------------------------ //
    //  打开链接
    // ------------------------------------------------------------------ //

    private fun openSelectedUrl() {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val mr = node.userObject as? MrItem ?: return
        BrowserUtil.browse(mr.webUrl)
    }

    // ------------------------------------------------------------------ //
    //  展示辅助
    // ------------------------------------------------------------------ //

    private fun stateLabel(state: String): String = when (state) {
        "opened" -> "已打开"
        "merged" -> "已合并"
        "closed" -> "已关闭"
        "locked" -> "锁定"
        else -> state
    }

    private fun stateAttributes(state: String): SimpleTextAttributes = when (state) {
        "opened" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
        "merged", "closed", "locked" -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
    }

    private fun tooltipHtml(mr: MrItem): String {
        val esc = { s: String -> XmlStringUtil.escapeString(s) }
        return buildString {
            append("<html><b>!${mr.iid} ${esc(mr.title)}</b><br>")
            append("状态: ${stateLabel(mr.state)} · 分支: ${esc(mr.sourceBranch)} → ${esc(mr.targetBranch)}<br>")
            if (mr.authorName.isNotEmpty()) append("作者: ${esc(mr.authorName)} · ")
            append("更新: ${mr.updatedAtText()}<br>")
            append("范围: ${mr.scopeTags()} · 目录: ${esc(mr.projectPath() ?: "-")}<br>")
            append("链接: ${esc(mr.webUrl)}")
            append("</html>")
        }
    }

    companion object {
        /** 与 plugin.xml 中 <toolWindow> 的 id 一致 */
        const val TOOL_WINDOW_ID = "GitLabPlus"
    }
}
