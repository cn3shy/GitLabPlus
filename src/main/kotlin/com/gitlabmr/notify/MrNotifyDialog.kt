package com.gitlabmr.notify

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 定时提醒的弹窗 (非模态,一直留在屏幕上直到手动关闭)
 *
 * 用 DialogWrapper 而不是自己定位气球:窗口摆在哪儿交给平台 (相对 IDEA 主窗口居中),
 * 不用自己算屏幕坐标,也就没有气球那套"定位算错跑到右下角"的问题。
 *
 * 非模态且不抢焦点 (setFocusableWindowState(false)):提醒归提醒,既不阻塞操作,
 * 也不会把 IDEA 顶到前台打断别的工作,按钮还可以连着点。
 */
class MrNotifyDialog(
    project: Project?,
    private val titleText: String,
    private val html: String,
    private val actions: List<Pair<String, () -> Unit>>,
) : DialogWrapper(project, false) {

    init {
        setTitle("GitLab MR")
        isResizable = false
        setModal(false)  // 不阻塞操作
        init()
        // 不抢焦点:否则弹出来会把 IDEA 顶到前台,在别的窗口里干活时被打断
        window?.setFocusableWindowState(false)
    }

    override fun createCenterPanel(): JComponent {
        val label = JBLabel("<html><b>$titleText</b><br>$html</html>").apply {
            // MR 标题可能很长:不换行会把窗口撑到屏幕外,而窗口又不能缩放、
            // 不抢焦点也按不了 Esc,那样连"关闭"都点不到
            setAllowAutoWrapping(true)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12, 12, 4, 12)
            add(label, BorderLayout.CENTER)
        }
    }

    /**
     * 用自定义按钮替换默认的 确定 / 取消
     *
     * 提醒里的按钮点完不关窗口 —— 一个提醒往往要连着操作两次 (先开浏览器、再开列表),
     * 关窗口单独给一个"关闭"。
     */
    override fun createActions(): Array<Action> {
        val actionButtons = actions.map { (label, onClick) ->
            object : DialogWrapper.DialogWrapperAction(label) {
                override fun doAction(e: ActionEvent?) = onClick()
            }
        }
        val closeButton = object : DialogWrapper.DialogWrapperAction("关闭") {
            override fun doAction(e: ActionEvent?) = close(CANCEL_EXIT_CODE)
        }
        return (actionButtons + closeButton).toTypedArray()
    }

    /**
     * 关闭时清掉静态引用
     *
     * 否则 companion 会一直攥着这个已销毁的窗口 (以及它持有的 Project),
     * 直到下一次提醒到来 —— 那条可能永远不来。
     */
    override fun dispose() {
        if (current === this) current = null
        super.dispose()
    }

    companion object {

        private val LOG = Logger.getInstance(MrNotifyDialog::class.java)

        /** 当前挂着的提醒窗口:新提醒来了先关掉旧的,避免开一屏 */
        private var current: MrNotifyDialog? = null

        fun show(project: Project?, title: String, html: String, actions: List<Pair<String, () -> Unit>>) {
            // ModalityState.any():设置页是模态的,点"立即检查"时用默认模态状态的话,
            // 这个 runnable 会被推迟到设置对话框关闭之后才跑,用户当场看不到弹窗。
            ApplicationManager.getApplication().invokeLater({
                try {
                    // close 对已关闭的窗口是幂等的
                    current?.close(CANCEL_EXIT_CODE)
                    current = MrNotifyDialog(project, title, html, actions).also { it.show() }
                } catch (t: Throwable) {
                    LOG.warn("显示提醒窗口失败", t)
                }
            }, ModalityState.any())
        }
    }
}
