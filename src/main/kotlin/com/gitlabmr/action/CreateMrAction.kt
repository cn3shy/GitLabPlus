package com.gitlabmr.action

import com.gitlabmr.api.ApiException
import com.gitlabmr.api.GitLabApi
import com.gitlabmr.api.TokenInvalidException
import com.gitlabmr.config.GitLabMrConfigService
import com.gitlabmr.config.GitLabMrSettingsConfigurable
import com.gitlabmr.model.MrCreateParams
import com.gitlabmr.ui.GitLabMrDialog
import com.gitlabmr.util.GitLabUtils
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File

private val LOG = logger<CreateMrAction>()

/**
 * 插件 Action 入口
 *
 * 对应 Python 版 [main()] 主函数 —— 在 IDEA 中通过菜单 / 快捷键触发。
 *
 * 流程:
 * 1. 获取当前项目的 Git 仓库根目录
 * 2. 读取 remote origin URL 并解析 GitLab 信息
 * 3. 获取 Token（配置 > 弹窗输入），解析 Project ID
 * 4. 远程读取分支 + 成员列表
 * 5. 弹出 MR 创建对话框（自动生成标题）
 * 6. 创建 MR 并保存分支/审核人记忆
 */
class CreateMrAction : AnAction(
    "创建 Merge Request",
    "为当前项目创建 GitLab Merge Request",
    null,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            Messages.showWarningDialog("未找到打开的项目", "GitLab MR")
            return
        }

        // ---- 1. 获取 Git 仓库根目录 ----
        // 在哪个 project 窗口/文件上触发，就定位哪个仓库
        val repository = findGitRepository(project, e)
        if (repository == null) {
            Messages.showWarningDialog("当前项目不是 Git 仓库，无法创建 MR。", "GitLab MR")
            return
        }
        val repoName = repository.root.name
        val repoRoot = File(repository.root.path)

        // ---- 2. 读取 remote URL ----
        val remoteUrl = getGitRemoteUrl(repoRoot)
        if (remoteUrl.isNullOrBlank()) {
            Messages.showWarningDialog("未找到 origin 远程仓库，请检查。", "GitLab MR")
            return
        }

        val info = try {
            GitLabUtils.parseGitLabInfo(remoteUrl)
        } catch (ex: Exception) {
            Messages.showErrorDialog("解析远程 URL 失败: ${ex.message}", "GitLab MR")
            return
        }

        val projectKey = GitLabUtils.projectKey(info)
        val config = GitLabMrConfigService.getInstance()

        // 加载默认分支（上次记录 > develop）
        val lastBranches = config.loadLastBranches(projectKey)
        val defaultSource = lastBranches.source.ifEmpty { "develop" }
        val defaultTarget = lastBranches.target.ifEmpty { "develop" }

        // ---- 3+4. Token 读取 + Project 解析 + 分支/成员获取 ----
        // Token 统一在 Settings → Tools → GitLab MR 中配置，运行时不再弹输入框
        var api: GitLabApi? = null
        var branches: List<String> = emptyList()
        var members: List<com.gitlabmr.model.Member> = emptyList()
        var errorMsg: String? = null
        var tokenProblem = false

        ProgressManager.getInstance().run(object : Task.Modal(project, "连接 GitLab...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                // 迁移历史遗留的带协议 host 记录 (幂等，后台线程执行)
                config.migrateLegacyHosts()

                // Token key 与设置页统一 (规范化 + 带端口)，避免读取到历史孤儿记录
                val tokenHostKey = GitLabUtils.hostKey(info)
                val apiToken = config.loadToken(tokenHostKey)
                if (apiToken.isNullOrBlank()) {
                    tokenProblem = true
                    errorMsg = "尚未配置 \"${tokenHostKey}\" 的 Token"
                    return
                }

                indicator.text = "正在连接 GitLab..."
                val apiInstance = GitLabApi(info, apiToken)
                try {
                    indicator.text = "正在解析项目..."
                    apiInstance.resolveProject()
                    api = apiInstance

                    indicator.text = "正在获取分支列表..."
                    branches = apiInstance.fetchRemoteBranches()

                    indicator.text = "正在获取项目成员..."
                    members = apiInstance.fetchProjectMembers()
                } catch (ex: TokenInvalidException) {
                    tokenProblem = true
                    errorMsg = "Token 无效或已过期，请在设置中更新"
                } catch (ex: ApiException) {
                    errorMsg = "获取项目 ID 失败: ${ex.message}"
                }
            }
        })

        if (errorMsg != null) {
            if (tokenProblem) {
                // Token 问题：通知气泡 + "打开设置"按钮，引导用户到设置页配置
                val notification = Notification(
                    "GitLab MR", "GitLab MR",
                    "${errorMsg}。<br>GitLab 服务器: <b>${GitLabUtils.hostKey(info)}</b>",
                    NotificationType.WARNING,
                )
                notification.addAction(object : AnAction("打开设置...") {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, GitLabMrSettingsConfigurable::class.java)
                    }
                })
                Notifications.Bus.notify(notification, project)
            } else {
                Messages.showErrorDialog(errorMsg!!, "GitLab MR")
            }
            return
        }
        if (api == null) {
            Messages.showErrorDialog("连接 GitLab 失败，请重试。", "GitLab MR")
            return
        }

        // ---- 5. 弹出 MR 创建对话框 ----
        // 源/目标分支均不做过滤，都展示全部远程分支
        val sourceBranches = branches
        val targetBranches = branches
        val defaultReviewer = config.loadLastReviewers(projectKey)
        val apiRef = api!!  // non-null 断言

        val dialog = GitLabMrDialog(
            repoName = repoName,
            defaultSource = defaultSource,
            defaultTarget = defaultTarget,
            defaultReviewer = defaultReviewer,
            sourceBranches = sourceBranches,
            targetBranches = targetBranches,
            members = members,
            onGenerateTitle = { source, target ->
                try {
                    val subjects = apiRef.fetchDiffCommitSubjects(source, target)
                    if (subjects.isEmpty()) "" else subjects.take(10).joinToString(", ")
                } catch (ex: Exception) {
                    LOG.warn("生成标题失败: ${ex.message}")
                    ""
                }
            },
        )

        if (!dialog.showAndGet()) return

        // ---- 6. 创建 MR ----
        val params = MrCreateParams(
            sourceBranch = dialog.sourceBranch,
            targetBranch = dialog.targetBranch,
            title = dialog.mrTitle,
            description = "",
            assigneeUsernames = dialog.reviewer,
            reviewerUsernames = dialog.reviewer,
        )

        ProgressManager.getInstance().run(object : Task.Modal(project, "创建 Merge Request...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "正在创建 Merge Request..."
                val result = apiRef.createMr(params)
                ApplicationManager.getApplication().invokeLater {
                    if (result.success) {
                        val url = result.webUrl ?: ""
                        // Notification content 按 HTML 解析：换行用 <br>，链接必须用 <a href> 才可点击
                        val message = if (url.isNotEmpty()) {
                            "${params.sourceBranch} → ${params.targetBranch} 创建成功<br><a href=\"$url\">$url</a>"
                        } else {
                            "${params.sourceBranch} → ${params.targetBranch} 创建成功"
                        }
                        Notifications.Bus.notify(
                            Notification("GitLab MR", "GitLab MR", message, NotificationType.INFORMATION),
                            project,
                        )
                        // 保存记忆
                        config.saveLastBranches(projectKey, params.sourceBranch, params.targetBranch)
                        if (params.reviewerUsernames.isNotEmpty()) {
                            config.saveLastReviewers(projectKey, params.reviewerUsernames)
                        }
                    } else {
                        Messages.showErrorDialog(
                            "创建 MR 失败: ${result.errorMessage}", "GitLab MR"
                        )
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------ //
    //  Git 仓库工具
    // ------------------------------------------------------------------ //

    /**
     * 定位当前操作的 Git 仓库 (多仓库项目中按优先级选择):
     * 1. 当前打开/选中文件所属的仓库 (在哪个 project 窗口运行就取哪个项目)
     * 2. 项目根目录所属的仓库
     * 3. 兜底: 项目中的第一个仓库
     */
    private fun findGitRepository(project: Project, e: AnActionEvent): GitRepository? {
        return try {
            val repoManager = GitRepositoryManager.getInstance(project)

            val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (currentFile != null) {
                repoManager.getRepositoryForFile(currentFile)?.let { return it }
            }

            val basePath = project.basePath
            if (basePath != null) {
                LocalFileSystem.getInstance().findFileByPath(basePath)?.let { root ->
                    repoManager.getRepositoryForFile(root)?.let { return it }
                }
            }

            repoManager.repositories.firstOrNull()
        } catch (ex: Exception) {
            LOG.warn("获取 Git 仓库失败: ${ex.message}")
            null
        }
    }

    /**
     * 调用 git 命令获取 remote origin URL
     */
    private fun getGitRemoteUrl(repoRoot: File): String? {
        return try {
            val process = ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(repoRoot)
                .redirectErrorStream(false)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            if (code == 0 && text.isNotEmpty()) text else null
        } catch (e: Exception) {
            LOG.warn("获取 remote URL 失败: ${e.message}")
            null
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
