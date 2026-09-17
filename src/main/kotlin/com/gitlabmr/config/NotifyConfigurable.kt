package com.gitlabmr.config

import com.gitlabmr.notify.MrNotifyService
import com.gitlabmr.notify.NotifyCheckResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 设置子页:Settings → Other Settings → GitLabPlus → 定时提醒
 *
 * 开关 (默认关闭) + 检查间隔 + 当前轮询的服务器,并提供"立即检查"手动跑一次。
 * 所有操作即时生效 (改间隔会重新调度轮询)。
 */
class NotifyConfigurable : Configurable {

    private val service get() = GitLabMrConfigService.getInstance()

    /** reset() 载入配置期间屏蔽控件事件,避免把"载入"当成"用户修改" */
    private var loading = false

    private val enableBox = JBCheckBox("启用定时提醒(定时查询\"指给我的 Merge Request\"并弹通知)")

    private val intervalSpinner = JSpinner(
        SpinnerNumberModel(
            GitLabMrConfigService.DEFAULT_NOTIFY_INTERVAL_MINUTES,
            GitLabMrConfigService.MIN_NOTIFY_INTERVAL_MINUTES,
            GitLabMrConfigService.MAX_NOTIFY_INTERVAL_MINUTES,
            1,
        )
    )

    private val hostLabel = JBLabel()
    private val statusLabel = JBLabel(" ")
    private val checkButton = JButton("立即检查")

    override fun getDisplayName(): String = "定时提醒"

    override fun createComponent(): JComponent {
        enableBox.addActionListener {
            if (loading) return@addActionListener
            service.setNotifyEnabled(enableBox.isSelected)
            intervalSpinner.isEnabled = enableBox.isSelected
            statusLabel.text = " "
        }
        intervalSpinner.addChangeListener {
            if (loading) return@addChangeListener
            service.setNotifyIntervalMinutes(
                (intervalSpinner.value as? Int) ?: GitLabMrConfigService.DEFAULT_NOTIFY_INTERVAL_MINUTES
            )
            // 间隔在调度时就固定了,改完要按新间隔重新调度
            MrNotifyService.getInstance().restart()
        }
        checkButton.addActionListener { checkNow() }

        val form = FormBuilder.createFormBuilder()
            .addComponent(
                JBLabel(
                    "<html>定时查询<b>指给我的 Merge Request</b>,有新指派或有更新时弹通知 (点击通知可直接打开 MR)。<br>" +
                        "查询的是 \"查看 MR\" 窗口上次使用的那台服务器,状态只取\"已打开\"。<br>" +
                        "IDE 启动后不会立刻查询,首次检查在启动约 30 秒后。</html>"
                )
            )
            .addSeparator()
            .addComponent(enableBox)
            .addLabeledComponent(JBLabel("检查间隔 (分钟):"), intervalSpinner)
            .addComponent(hostLabel)
            .addSeparator()
            .addComponent(checkButton)
            .addComponent(statusLabel)
            .panel

        loadFromConfig()
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(form, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean = false  // 即时生效,无待应用的修改
    override fun apply() {}
    override fun reset() = loadFromConfig()

    // ------------------------------------------------------------------ //
    //  内部
    // ------------------------------------------------------------------ //

    private fun loadFromConfig() {
        loading = true
        try {
            enableBox.isSelected = service.isNotifyEnabled()
            intervalSpinner.value = service.notifyIntervalMinutes()
            intervalSpinner.isEnabled = enableBox.isSelected
            val host = service.loadLastViewHost()
            hostLabel.text = if (host.isBlank()) {
                "轮询服务器:未选择 —— 请先在 GitLabPlus 工具窗口查询一次"
            } else {
                "轮询服务器:$host"
            }
            statusLabel.text = " "
        } finally {
            loading = false
        }
    }

    /**
     * 手动检查一次:结果写在状态标签上 (发现新的 / 有更新的 MR 时同样弹通知)
     *
     * 设置对话框是模态的:后台线程里的 invokeLater 默认取 NON_MODAL,会被推迟到
     * 对话框关闭之后才执行(表现:标签一直停在"正在检查...")。所以这里在 EDT
     * (按钮回调)上先取当前模态状态,回写界面时带上它。
     */
    private fun checkNow() {
        val modalityState = ModalityState.current()
        checkButton.isEnabled = false
        statusLabel.foreground = UIUtil.getLabelForeground()
        statusLabel.text = " 正在检查..."
        ApplicationManager.getApplication().executeOnPooledThread {
            // 兜底:后台线程里的任何异常都必须变成可见结果,否则界面会一直卡在"正在检查..."
            val result = try {
                MrNotifyService.getInstance().checkOnce(notify = true)
            } catch (t: Throwable) {
                NotifyCheckResult(null, 0, emptyList(), "检查失败:${t.javaClass.simpleName}: ${t.message}")
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    checkButton.isEnabled = true
                    showResult(result)
                },
                modalityState,
            )
        }
    }

    private fun showResult(result: NotifyCheckResult) {
        statusLabel.text = " " + when {
            result.error != null -> "检查失败:${result.error}"
            result.fresh.isEmpty() -> "检查完成:${result.host} 上指给你的已打开 MR 共 ${result.total} 个,没有新变化"
            else -> "检查完成:发现 ${result.fresh.size} 个新的 / 有更新的 MR (共 ${result.total} 个):" +
                result.fresh.take(3).joinToString(", ") { "!${it.iid}" } +
                if (result.fresh.size > 3) " …" else ""
        }
        statusLabel.foreground = if (result.error != null) JBColor.RED else UIUtil.getLabelForeground()
    }
}
