package com.gitlabmr.notify

import com.gitlabmr.config.GitLabMrConfigService
import com.gitlabmr.model.MrCreateOutcome
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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.xml.util.XmlStringUtil

/**
 * 插件通知 (plugin.xml 中 notificationGroup "GitLab MR",气泡展示)
 *
 * 通知内容按 HTML 解析,所以文本要转义;正文只放关键信息、不放链接文本
 * (要打开 MR 用按钮),按钮统一两个:
 * "打开 MR" 浏览器直达 / "查看列表" 打开侧边栏工具窗口。
 *
 * 设置页的"通知方式"(气泡 / 常驻弹窗 / 系统通知 + 闪烁)只作用于**定时提醒** ——
 * 那是"人不在 IDE 前面"时才需要的提醒手段。创建 MR 成功的通知固定只走 notice 气泡,
 * 因为那是你自己刚做的操作,再弹常驻窗口、闪任务栏只是打扰。
 */
object MrNotifications {

    /** 与 plugin.xml 中 <notificationGroup id="..."> 一致 (仅 notice 气泡用) */
    private const val GROUP_ID = "GitLab MR"

    /** 通知里最多列出的 MR 条数,超出折叠成一行 */
    private const val MAX_ITEMS = 5

    /** 批量创建结果里最多列出的条数 (成功 + 失败合计) */
    private const val MAX_BATCH_ITEMS = 12

    /** "打开 MR"一次最多打开几个标签页 (一次提醒可能命中很多条) */
    private const val MAX_OPEN_URLS = 10

    /**
     * 定时提醒:有新指派 / 有更新的"指给我的 MR"
     *
     * @return 是否真的发出了提醒。一个通知通道都没勾时返回 false,调用方据此决定要不要
     *         把这批 MR 记为"已提醒" —— 没发出去却记上,用户之后打开通道就再也收不到了。
     */
    fun notifyAssignedMrs(host: String, mrs: List<MrItem>): Boolean {
        if (mrs.isEmpty()) return false
        val title = if (mrs.size == 1) {
            "有新的指给你的 Merge Request"
        } else {
            "${mrs.size} 个指给你的 Merge Request 有新动态"
        }
        return deliver(
            project = currentProject(),
            title = title,
            html = assignedContent(host, mrs),
            urls = mrs.map { it.webUrl },
            listScope = MrScopes.ASSIGNED_TO_ME,
        )
    }

    /**
     * MR 创建成功:只列合并关键信息 (编号 / 标题 / 分支 / 审核人 / 项目),不显示链接文本
     *
     * 固定走 notice 气泡,不看设置页的"通知方式":创建是你自己刚发起的操作,
     * 常驻弹窗 / 系统通知 / 任务栏闪烁是给"人不在 IDE 前面"的提醒用的,用在这里只会打扰。
     */
    fun notifyMrCreated(project: Project, projectKey: String, params: MrCreateParams, webUrl: String?) {
        notifyByBalloon(
            project = project,
            title = "Merge Request 创建成功",
            html = createdContent(projectKey, params, webUrl),
            urls = listOfNotNull(webUrl?.takeIf { it.isNotBlank() }),
            listScope = MrScopes.CREATED_BY_ME,
        )
    }

    /**
     * 批量创建 MR 的结果: 成功与失败列在同一封通知里
     *
     * 单行全成功时复用 [notifyMrCreated], 展示与旧版完全一致 (编号 / 标题 / 分支 / 审核人 / 项目);
     * 多行时才用批量正文, 失败行给出可读原因。
     */
    fun notifyMrsCreated(
        project: Project,
        successes: List<MrCreateOutcome>,
        failures: List<MrCreateOutcome>,
        reviewer: String,
    ) {
        if (successes.size == 1 && failures.isEmpty()) {
            val outcome = successes.first()
            notifyMrCreated(
                project = project,
                projectKey = outcome.projectKey,
                params = MrCreateParams(
                    sourceBranch = outcome.sourceBranch,
                    targetBranch = outcome.targetBranch,
                    title = outcome.title,
                    description = "",
                    assigneeUsernames = reviewer,
                    reviewerUsernames = reviewer,
                ),
                webUrl = outcome.webUrl,
            )
            return
        }

        val title = if (failures.isEmpty()) {
            "${successes.size} 个 Merge Request 创建成功"
        } else {
            "${successes.size} 个成功，${failures.size} 个失败"
        }
        notifyByBalloon(
            project = project,
            title = title,
            html = batchCreatedContent(successes, failures, reviewer),
            urls = successes.mapNotNull { it.webUrl?.takeIf { url -> url.isNotBlank() } },
            listScope = MrScopes.CREATED_BY_ME,
            openLabel = if (successes.size > 1) "打开全部成功的 ${successes.size} 个 MR" else null,
        )
    }

    // ------------------------------------------------------------------ //
    //  通道分发
    // ------------------------------------------------------------------ //

    /**
     * 按设置页勾选的通知方式发一条**定时提醒**
     *
     * 三条通道互不影响:气泡带按钮、系统通知与闪烁只做提醒。一条都没勾就什么都不发。
     * 创建 MR 成功的通知不走这里 —— 它固定只弹 notice 气泡,见 [notifyMrCreated]。
     *
     * @return 是否至少启用了一条通道 (即"提醒有没有发出去")
     */
    private fun deliver(
        project: Project?,
        title: String,
        html: String,
        urls: List<String>,
        listScope: String,
    ): Boolean {
        val config = GitLabMrConfigService.getInstance()
        var sent = false

        // 三条通道各管各的:勾了几个就都发,互不顶替
        if (config.isChannelEnabled(NotifyChannel.WINDOW)) {
            MrNotifyDialog.show(project, title, html, buildActions(project, urls, listScope))
            sent = true
        }
        if (config.isChannelEnabled(NotifyChannel.BALLOON)) {
            notifyByBalloon(project, title, html, urls, listScope)
            sent = true
        }
        if (config.isChannelEnabled(NotifyChannel.SYSTEM_FLASH)) {
            NotifyChannels.sendSystem(title, plainText(html))
            NotifyChannels.flashTaskbar(project)
            sent = true
        }

        return sent
    }

    /**
     * 发一个 notice 气泡通知 (通知组 "GitLab MR")
     *
     * 按钮点完不 expire 通知:操作可能要点多次 (先开浏览器、再开列表),
     * 而且过期后的通知在通知中心里会变成不可点的灰态。
     */
    private fun notifyByBalloon(
        project: Project?,
        title: String,
        html: String,
        urls: List<String>,
        listScope: String,
        openLabel: String? = null,
    ) {
        val notification = Notification(GROUP_ID, title, html, NotificationType.INFORMATION)
        buildActions(project, urls, listScope, openLabel).forEach { (label, onClick) ->
            notification.addAction(object : NotificationAction(label) {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) = onClick()
            })
        }
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

    /**
     * 批量正文: 成功行 "!12 标题 (项目路径) 源 → 目标", 失败行 "项目路径 源 → 目标 — 失败:原因"
     */
    private fun batchCreatedContent(
        successes: List<MrCreateOutcome>,
        failures: List<MrCreateOutcome>,
        reviewer: String,
    ): String = buildString {
        val all = successes + failures
        for (outcome in all.take(MAX_BATCH_ITEMS)) {
            if (outcome.success) {
                val iid = outcome.webUrl?.substringAfterLast("/merge_requests/", "")
                    ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                if (iid != null) append("!").append(iid).append(" ")
                append("<b>").append(escape(outcome.title)).append("</b>")
                append(" (").append(escape(outcome.projectPath)).append(") ")
                append(escape(outcome.sourceBranch)).append(" → ")
                    .append(escape(outcome.targetBranch)).append("<br>")
            } else {
                append(escape(outcome.projectPath)).append(" ")
                append(escape(outcome.sourceBranch)).append(" → ").append(escape(outcome.targetBranch))
                append(" — 失败:").append(escape(outcome.errorMessage ?: "未知错误")).append("<br>")
            }
        }
        if (all.size > MAX_BATCH_ITEMS) {
            append("… 还有 ").append(all.size - MAX_BATCH_ITEMS).append(" 条<br>")
        }
        if (reviewer.isNotBlank()) {
            append("审核人:").append(escape(reviewer))
        }
    }

    /**
     * HTML 正文转纯文本 (系统通知不支持 HTML)
     *
     * 正文只用了 <br> 和 <b> 两种标签,先还原换行再去标签;实体一律交给平台解码 ——
     * 手写解码表迟早漏 (XmlStringUtil.escapeString 会把不换行空格转成 `&nbsp;`),
     * 而且要自己处理 `&amp;` 与二次解码的先后顺序。
     */
    private fun plainText(html: String): String =
        StringUtil.unescapeXml(html.replace("<br>", "\n").replace(Regex("<[^>]*>"), "")).trim()

    // ------------------------------------------------------------------ //
    //  按钮与发送
    // ------------------------------------------------------------------ //

    /**
     * 统一的两个按钮:"打开 MR" (浏览器直达) + "查看列表" (侧边栏工具窗口,需要项目)
     *
     * 返回"文案 → 动作"而不是直接挂到通知上,是因为常驻弹窗 ( [MrNotifyDialog] )
     * 不认 NotificationAction,两边共用这一份按钮定义。
     *
     * @param urls      "打开 MR"要打开的 MR 链接;多条时一次性全部打开 (上限 [MAX_OPEN_URLS])
     * @param listScope "查看列表"打开窗口后自动查询的范围:
     *                  创建成功 → 我创建的;指给我提醒 → 指给我的
     */
    private fun buildActions(
        project: Project?,
        urls: List<String>,
        listScope: String,
        openLabel: String? = null,
    ): List<Pair<String, () -> Unit>> {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val targets = urls.filter { it.isNotBlank() }.distinct().take(MAX_OPEN_URLS)
        if (targets.isNotEmpty()) {
            val label = openLabel
                ?: if (targets.size == 1) "打开 MR" else "打开全部 ${targets.size} 个 MR"
            actions += label to { targets.forEach { BrowserUtil.browse(it) } }
        }
        if (project != null) {
            actions += "查看列表" to {
                // 通知会长期留在通知中心 (按钮刻意不 expire),点它时项目可能早就关了
                if (!project.isDisposed) GitLabMrToolWindowPanel.showWithScope(project, listScope)
            }
        }
        return actions
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
