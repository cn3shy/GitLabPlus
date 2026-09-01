package com.gitlabmr.ui

import com.gitlabmr.model.MrItem
import com.gitlabmr.model.MrListResult
import com.gitlabmr.model.MrScopes
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.xml.util.XmlStringUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.ItemEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Action
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

/** 状态 / 范围下拉选项 (label 显示 / value 传 API) */
private class StateOption(val label: String, val value: String) {
    override fun toString(): String = label
}

/** Group 分组节点 */
private data class GroupInfo(val name: String, val count: Int)

/** 项目分组节点 */
private data class ProjectInfo(val path: String, val count: Int)

/**
 * Merge Request 列表对话框 ("查看 Merge Request")
 *
 * - 顶部一行过滤：服务器下拉 (设置页配置列表) + 状态 + 范围下拉 + 全部展开 / 全部收缩 + 刷新；
 *   服务器 / 状态 / 范围变化即重新查询 (已合并状态仅查前 100 条)
 * - 中部：树形展示，Group → 项目 → MR 三层，默认全部展开，双击 / 回车在浏览器打开 MR 页面
 * - 底部：当前用户与命中统计；构造后立即自动查询默认服务器
 */
class GitLabMrListDialog(
    private val project: Project,
    hosts: List<String>,
    defaultHost: String,
    private val onQuery: (host: String, state: String, scope: String) -> MrListResult,
) : DialogWrapper(project, true) {

    private val comboHost = JComboBox(hosts.toTypedArray())
    private val comboState = JComboBox(
        arrayOf(
            StateOption("已打开", "opened"),
            StateOption("已合并", "merged"),
            StateOption("已关闭", "closed"),
            StateOption("全部", "all"),
        )
    )
    private val comboScope = JComboBox(
        arrayOf(
            StateOption("全部", MrScopes.ALL),
            StateOption("我创建的", MrScopes.CREATED_BY_ME),
            StateOption("指给我的", MrScopes.ASSIGNED_TO_ME),
        )
    )

    private lateinit var tree: Tree
    private lateinit var treeModel: DefaultTreeModel
    private lateinit var statusLabel: JBLabel

    // 渲染器必须在 init() (createCenterPanel) 之前初始化，声明顺序不能后移
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
        title = "查看 Merge Request"
        init()
        comboHost.addItemListener { if (it.stateChange == ItemEvent.SELECTED) refresh() }
        comboState.addItemListener { if (it.stateChange == ItemEvent.SELECTED) refresh() }
        comboScope.addItemListener { if (it.stateChange == ItemEvent.SELECTED) refresh() }
        refresh()  // 首次自动加载
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        panel.border = JBUI.Borders.empty(8)

        // ---- 顶部一行:服务器 + 状态 + 范围 + 全部展开/收缩 + 刷新 ----
        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JLabel("服务器:"))
            add(comboHost)
            add(JLabel("状态:"))
            add(comboState)
            add(JLabel("范围:"))
            add(comboScope)
            add(JButton("全部展开").apply { addActionListener { expandAll(true) } })
            add(JButton("全部收缩").apply { addActionListener { expandAll(false) } })
            add(JButton("刷新").apply { addActionListener { refresh() } })
        }

        // ---- 中部:MR 树 (Group → 项目 → MR) ----
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

        // ---- 底部:统计 + 操作提示 ----
        statusLabel = JBLabel(" ")
        val south = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.WEST)
            add(JBLabel("双击条目在浏览器打开").apply { foreground = JBColor.GRAY }, BorderLayout.EAST)
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(JScrollPane(tree), BorderLayout.CENTER)
        panel.add(south, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(720), JBUI.scale(520))
        return panel
    }

    /** 仅"关闭"一个按钮 (浏览型对话框，无确认语义) */
    override fun createActions(): Array<out Action> = arrayOf(
        object : AbstractAction("关闭") {
            override fun actionPerformed(e: ActionEvent) = doCancelAction()
        }
    )

    // ------------------------------------------------------------------ //
    //  查询与树构建
    // ------------------------------------------------------------------ //

    private fun selectedState(): String = (comboState.selectedItem as? StateOption)?.value ?: "opened"

    private fun selectedScope(): String =
        (comboScope.selectedItem as? StateOption)?.value ?: MrScopes.ALL

    private fun refresh() {
        val host = comboHost.selectedItem as? String ?: return
        val state = selectedState()
        val scope = selectedScope()

        setRootText("正在加载...")
        ProgressManager.getInstance().run(object : Task.Modal(project, "查询 Merge Request...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = try {
                    onQuery(host, state, scope)
                } catch (ex: Exception) {
                    MrListResult(null, emptyList(), "查询失败: ${ex.message}", false)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    applyResult(host, result)
                }
            }
        })
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
}
