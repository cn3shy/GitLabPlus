package com.gitlabmr.notify

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SystemNotifications
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import javax.swing.JFrame

/**
 * 通知方式 (可叠加,设置页勾选;默认只开 [BALLOON])
 *
 * [BALLOON] 走 IDE 的通知系统 (plugin.xml 里 id="GitLab MR" 的那个通知组);
 * [WINDOW] 是自建的对话框 (见 [MrNotifyDialog]);
 * [SYSTEM_FLASH] 跟通知系统无关,由 [NotifyChannels] 直接调系统能力。
 */
enum class NotifyChannel(val displayName: String, val hint: String) {
    /** 默认:IDE 右上角气泡,几秒后自动消失 */
    BALLOON("notice 气泡通知", "IDE 右上角弹出气泡,几秒后自动消失 (默认)"),

    /** 非模态弹窗,不自动消失,需手动关闭 */
    WINDOW("IDEA 窗口弹窗通知", "弹窗一直留在屏幕上,不自动消失、需手动关闭 (不阻塞操作,也不抢焦点)"),

    /**
     * 系统通知 + 任务栏闪烁 (合并成一条通道)
     *
     * 两者都是"人不在 IDE 前面"时的提醒手段,单用哪个都容易漏,合起来才闭环:
     * 系统通知带内容、闪烁持续到你切回来。
     *
     * 注意只有闪烁那半是 Windows 专属;系统通知三个平台都走(各平台有自己的实现)。
     */
    SYSTEM_FLASH("系统通知 + 任务栏闪烁", "IDE 不在前台时发到操作系统通知中心,并让任务栏图标闪动 (闪烁仅 Windows)"),
}

/**
 * 通知气泡与弹窗之外的两条通道:系统通知与任务栏闪烁
 *
 * 都与平台的 Notification 无关,失败一律吞掉并记日志 —— 提醒失败不该影响主流程
 * (JNA 在个别发行版上可能缺失,系统托盘也可能被系统禁用)。
 */
object NotifyChannels {

    private val LOG = Logger.getInstance(NotifyChannels::class.java)

    /**
     * 系统通知 (OS 通知中心)
     *
     * 平台自身负责降级:没有托盘 / 不支持的平台上是空实现,不会抛异常。
     * 内容按纯文本渲染,不支持 HTML。
     *
     * 注意平台的既定语义:仅在 IDE 窗口不在前台、且 IDE 没关掉"系统通知"时才真的发送
     * (IDE 在前台时有气泡/弹窗,再发一遍系统通知只会重复吵人)。这是有意保持的行为。
     */
    fun sendSystem(title: String, content: String) {
        // ModalityState.any():设置页是模态的,点"立即检查"时不能用默认模态状态 ——
        // 那样这些 runnable 会被推迟到设置对话框关闭之后才执行,等于当场什么都不发。
        ApplicationManager.getApplication().invokeLater({
            try {
                // 签名里三个参数的真实含义:三个平台都把第二个参数 (subtitle) 当通知标题,
                // 第一个参数被忽略 (Windows 是 TrayIcon.displayMessage(subtitle, content),
                // macOS 是 setTitle:subtitle)。标题必须传在 subtitle 上,传空串就没有标题。
                SystemNotifications.getInstance().notify(title, title, content)
            } catch (t: Throwable) {
                LOG.warn("发送系统通知失败", t)
            }
        }, ModalityState.any())
    }

    /**
     * 闪烁任务栏上的 IDEA 图标 (Windows 的 FlashWindowEx)
     *
     * 用 FLASHW_TIMERNOFG:持续闪到窗口被激活为止 (即"切回 IDE 后自动停"),
     * 而不是固定闪几下。非 Windows 平台直接跳过。
     */
    fun flashTaskbar(project: Project?) {
        if (!SystemInfo.isWindows) return
        ApplicationManager.getApplication().invokeLater({
            try {
                val frame = findIdeWindow(project) ?: return@invokeLater
                val handle = Native.getWindowPointer(frame) ?: return@invokeLater
                if (Pointer.nativeValue(handle) == 0L) return@invokeLater
                val info = WinUser.FLASHWINFO()
                info.hWnd = WinNT.HANDLE(handle)
                info.dwFlags = WinUser.FLASHW_ALL or WinUser.FLASHW_TIMERNOFG
                info.uCount = 0
                info.dwTimeout = 0
                info.write()
                User32.INSTANCE.FlashWindowEx(info)
            } catch (t: Throwable) {
                LOG.warn("闪烁任务栏图标失败", t)
            }
        }, ModalityState.any())
    }

    /**
     * 定位要闪烁的 IDE 窗口
     *
     * 定时提醒不带项目上下文 —— 关掉全部项目停在欢迎页时 currentProject() 返回 null,
     * 而 getFrame(null) 给不出窗口,这一步会静默跳过 ("任务栏闪动"就不生效了)。
     * 这时退而取任意一个已打开的 IDE 窗口 (IdeFrame 的实现类就是 JFrame)。
     */
    private fun findIdeWindow(project: Project?): JFrame? {
        WindowManager.getInstance().getFrame(project)?.let { return it }
        return WindowManager.getInstance().allProjectFrames.firstOrNull() as? JFrame
    }
}
