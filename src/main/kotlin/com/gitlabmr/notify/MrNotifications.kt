package com.gitlabmr.notify

import com.gitlabmr.model.MrCreateParams
import com.gitlabmr.model.MrItem
import com.gitlabmr.model.MrScopes
import com.gitlabmr.ui.GitLabMrToolWindowPanel
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.xml.util.XmlStringUtil

/**
 * 插件通知 (plugin.xml 中 notificationGroup "GitLab MR",气泡展示)
 *
 * 通知内容按 HTML 解析,所以文本要转义;正文只放关键信息、不放链接文本
 * (要打开 MR 用按钮),按钮统一两个:
 * "打开 MR" 浏览器直达 / "查看列表" 打开侧边栏工具窗口。
 */
object MrNotifications {

    /** 与 plugin.xml 中 <notificationGroup id="..."> 一致 */
    private const val GROUP_ID = "GitLab MR"

    /** 通知里最多列出的 MR 条数,超出折叠成一行 */
    private const val MAX_ITEMS = 5

    /** "打开 MR"一次最多打开几个标签页 (一次提醒可能命中很多条) */
    private const val MAX_OPEN_URLS = 10

    /**
     * 定时提醒:有新指派 / 有更新的"指给我的 MR"
     */
    fun notifyAssignedMrs(host: String, mrs: List<MrItem>) {
        if (mrs.isEmpty()) return
        val project = currentProject()
        val title = if (mrs.size == 1) {
            "有新的指给你的 Merge Request"
        } else {
            "${mrs.size} 个指给你的 Merge Request 有新动态"
        }
        val notification = Notification(GROUP_ID, title, assignedContent(host, mrs), NotificationType.INFORMATION)
        addMrActions(notification, project, mrs.map { it.webUrl }, MrScopes.ASSIGNED_TO_ME)
        notify(notification, project)
    }

    /**
     * MR 创建成功:只列合并关键信息 (编号 / 标题 / 分支 / 审核人 / 项目),不显示链接文本
     */
    fun notifyMrCreated(project: Project, projectKey: String, params: MrCreateParams, webUrl: String?) {
        val notification = Notification(
            GROUP_ID,
            "Merge Request 创建成功",
            createdContent(projectKey, params, webUrl),
            NotificationType.INFORMATION,
        )
        addMrActions(notification, project, listOfNotNull(webUrl?.takeIf { it.isNotBlank() }), MrScopes.CREATED_BY_ME)
        notify(notification, project)
    }

    // ------------------------------------------------------------------ //
    //  通知正文
    // ------------------------------------------------------------------ //

    private fun assignedContent(host: String, mrs: List<MrItem>): String = buildString {
        append("<b>").append(escape(host)).append("</b><br>")
        for (mr in mrs.take(MAX_ITEMS)) {
            append("!").append(mr.iid).append(" ").append(escape(mr.title))
            mr.projectPath()?.let { append(" (").append(escape(it)).append(")") }
            append("<br>")
        }
        if (mrs.size > MAX_ITEMS) {
            append("… 还有 ").append(mrs.size - MAX_ITEMS).append(" 个")
        }
    }

    /**
     * 创建成功的正文:MR 编号从 web_url 尾部 (/merge_requests/<iid>) 取,取不到就只显示标题
     */
    private fun createdContent(projectKey: String, params: MrCreateParams, webUrl: String?): String = buildString {
        val iid = webUrl?.substringAfterLast("/merge_requests/", "")
            ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        val title = params.title.trim()
        if (title.isNotEmpty()) {
            if (iid != null) append("!").append(iid).append(" ")
            append("<b>").append(escape(title)).append("</b><br>")
        } else if (iid != null) {
            append("!").append(iid).append("<br>")
        }
        append(escape(params.sourceBranch)).append(" → ").append(escape(params.targetBranch)).append("<br>")
        if (params.reviewerUsernames.isNotBlank()) {
            append("审核人:").append(escape(params.reviewerUsernames)).append("<br>")
        }
        append("项目:").append(escape(projectKey))
    }

    // ------------------------------------------------------------------ //
    //  按钮与发送
    // ------------------------------------------------------------------ //

    /**
     * 统一挂按钮:"打开 MR" (浏览器直达) + "查看列表" (侧边栏工具窗口,需要项目)
     *
     * @param urls      "打开 MR"要打开的 MR 链接;多条时一次性全部打开 (上限 [MAX_OPEN_URLS])
     * @param listScope "查看列表"打开窗口后自动查询的范围:
     *                  创建成功 → 我创建的;指给我提醒 → 指给我的
     */
    private fun addMrActions(
        notification: Notification,
        project: Project?,
        urls: List<String>,
        listScope: String,
    ) {
        val targets = urls.filter { it.isNotBlank() }.distinct().take(MAX_OPEN_URLS)
        if (targets.isNotEmpty()) {
            val label = if (targets.size == 1) "打开 MR" else "打开全部 ${targets.size} 个 MR"
            notification.addAction(object : NotificationAction(label) {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    targets.forEach { BrowserUtil.browse(it) }
                    notification.expire()
                }
            })
        }
        if (project != null) {
            notification.addAction(object : NotificationAction("查看列表") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    GitLabMrToolWindowPanel.showWithScope(project, listScope)
                    notification.expire()
                }
            })
        }
    }

    private fun notify(notification: Notification, project: Project?) {
        if (project != null) {
            Notifications.Bus.notify(notification, project)
        } else {
            Notifications.Bus.notify(notification)
        }
    }

    /** 定时提醒没有具体项目上下文,取第一个打开的项目 (只用于"查看列表"按钮) */
    private fun currentProject(): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

    private fun escape(text: String): String = XmlStringUtil.escapeString(text)
}
