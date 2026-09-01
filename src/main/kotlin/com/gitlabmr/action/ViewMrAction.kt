package com.gitlabmr.action

import com.gitlabmr.api.ApiException
import com.gitlabmr.api.GitLabServerApi
import com.gitlabmr.api.TokenInvalidException
import com.gitlabmr.config.GitLabMrConfigService
import com.gitlabmr.config.GitLabMrSettingsConfigurable
import com.gitlabmr.model.MrItem
import com.gitlabmr.model.MrListResult
import com.gitlabmr.ui.GitLabMrListDialog
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

private val LOG = logger<ViewMrAction>()

/**
 * 查看 Merge Request 入口
 *
 * 与创建 MR 不同，查询不依赖当前项目的 git remote，而是面向设置页配置列表中
 * 已保存 Token 的 GitLab 服务器，以 Token 身份查询:
 * - 我创建的 MR (scope=created_by_me)
 * - 指给我的 MR (scope=assigned_to_me)
 *
 * 流程:
 * 1. 读取配置的服务器列表，无 Token 时通过通知引导设置页
 * 2. 弹出列表对话框（自动查询上次使用的服务器）
 * 3. 对话框内按 Group 分组展示，双击 / 回车打开 MR 页面
 */
class ViewMrAction : AnAction(
    "查看 Merge Request",
    "查看我创建的与指给我的 GitLab Merge Request",
    null,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            Messages.showWarningDialog("未找到打开的项目", "GitLab MR")
            return
        }

        val config = GitLabMrConfigService.getInstance()
        config.migrateLegacyHosts()

        // 只列出已配置 Token 的服务器
        val hosts = config.knownHosts()
            .filter { !config.loadToken(it).isNullOrBlank() }
            .sorted()
        if (hosts.isEmpty()) {
            notifyNeedToken(project)
            return
        }

        val defaultHost = config.loadLastViewHost().takeIf { it in hosts } ?: hosts.first()
        val dialog = GitLabMrListDialog(project, hosts, defaultHost) { host, state ->
            queryMergeRequests(config, host, state)
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ //
    //  查询 (在 Task 后台线程中执行)
    // ------------------------------------------------------------------ //

    /**
     * 查询指定服务器上我创建的 + 指给我的 MR，合并去重
     */
    private fun queryMergeRequests(
        config: GitLabMrConfigService,
        host: String,
        state: String,
    ): MrListResult {
        val token = config.loadToken(host)
            ?: return MrListResult(null, emptyList(), "尚未配置 \"${host}\" 的 Token", true)

        val api = GitLabServerApi(host, token)
        return try {
            val user = api.resolveCurrentUser()
            val created = api.fetchMergeRequests(SCOPE_CREATED_BY_ME, state)
            val assigned = api.fetchMergeRequests(SCOPE_ASSIGNED_TO_ME, state)

            // 两个 scope 合并去重 (web_url 唯一)，同时打上命中标记
            val merged = LinkedHashMap<String, MrItem>()
            for (mr in created) merged[mr.webUrl] = mr.copy(createdByMe = true)
            for (mr in assigned) {
                merged[mr.webUrl] = (merged[mr.webUrl] ?: mr).copy(assignedToMe = true)
            }

            config.saveLastViewHost(host)
            MrListResult(user, merged.values.sortedByDescending { it.updatedAt }, null, false)
        } catch (ex: TokenInvalidException) {
            MrListResult(null, emptyList(), "Token 无效或已过期，请在设置中更新", true)
        } catch (ex: ApiException) {
            MrListResult(null, emptyList(), ex.message ?: "查询失败", false)
        } catch (ex: Exception) {
            LOG.warn("查询 MR 列表失败: ${ex.message}")
            MrListResult(null, emptyList(), "查询失败: ${ex.message}", false)
        }
    }

    /** 未配置任何 Token 时，通知气泡 + "打开设置"按钮引导用户 */
    private fun notifyNeedToken(project: Project) {
        val notification = Notification(
            "GitLab MR", "GitLab MR",
            "尚未配置任何 GitLab 服务器的 Token。<br>请先在 Settings → Tools → GitLab MR 中添加。",
            NotificationType.WARNING,
        )
        notification.addAction(object : AnAction("打开设置...") {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, GitLabMrSettingsConfigurable::class.java)
            }
        })
        Notifications.Bus.notify(notification, project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    companion object {
        private const val SCOPE_CREATED_BY_ME = "created_by_me"
        private const val SCOPE_ASSIGNED_TO_ME = "assigned_to_me"
    }
}
