package com.gitlabmr.action

import com.gitlabmr.api.ApiException
import com.gitlabmr.api.MrRepoCandidate
import com.gitlabmr.api.MrRepoGateway
import com.gitlabmr.api.TokenInvalidException
import com.gitlabmr.config.AccessTokenConfigurable
import com.gitlabmr.config.GitLabMrConfigService
import com.gitlabmr.model.MrCreateOutcome
import com.gitlabmr.model.MrCreateParams
import com.gitlabmr.model.MrResult
import com.gitlabmr.notify.MrNotifications
import com.gitlabmr.ui.GitLabMrDialog
import com.gitlabmr.ui.MrRowInit
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
 * 1. 获取当前项目的 Git 仓库根目录, 扫描 IDE 中已登记的其他 Git 仓库
 * 2. 读取各仓库 origin URL 并解析 GitLab 项目 (当前仓库必须可用, 其余非 GitLab 仓库跳过)
 * 3. 读取当前服务器 Token, 预取当前项目的分支/成员 (首行与审核人候选直接命中缓存)
 * 4. 弹出 MR 创建对话框: 多行计划表, 每行 = 一个项目 + 源/目标分支 + 标题
 * 5. 逐行创建 MR, 汇总成功/失败; 成功后按项目保存分支与审核人记忆
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
        val repoRoot: File = findGitRepository(project, e)?.let { File(it.root.path) }
            ?: findGitRootByWalkingUp(e, project)
            ?: run {
                Messages.showWarningDialog(
                    "未找到 Git 仓库，无法创建 MR。\n\n" +
                        "可能原因:\n" +
                        "1. 当前选中的目录 / 项目根目录不在 Git 仓库内\n" +
                        "2. 多仓库项目未在 Settings → Version Control 中登记 Git 根目录",
                    "GitLab MR"
                )
                return
            }

        // ---- 1.5 触发时选中的仓库 (右键 / 项目视图多选): 每个选中项目自动建一行 ----
        val selectedRoots = selectedRepoRoots(project, e)

        val config = GitLabMrConfigService.getInstance()
        val gateway = MrRepoGateway(project)
        var discovery: MrRepoGateway.Discovery? = null
        var primary: MrRepoCandidate? = null
        var errorMsg: String? = null
        var tokenProblem = false

        ProgressManager.getInstance().run(object : Task.Modal(project, "连接 GitLab...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                // 迁移历史遗留的带协议 host 记录 (幂等，后台线程执行)
                config.migrateLegacyHosts()

                indicator.text = "正在扫描本地 Git 仓库..."
                val found = gateway.discoverLocalRepos(repoRoot, repoRoot.name, selectedRoots)
                discovery = found
                // 有选中项目时以选中项为准; 没选中才保持"触发仓库不可用就失败"的旧语义
                if (found.selected.isEmpty() && found.currentRepoError != null) {
                    errorMsg = found.currentRepoError
                    return
                }
                if (found.candidates.isEmpty()) {
                    errorMsg = "未找到可用的 GitLab 项目，请检查仓库的 origin 远程地址。"
                    return
                }
                val cur = found.selected.firstOrNull() ?: found.candidates.first()
                primary = cur

                indicator.text = "正在解析项目..."
                val api = gateway.apiFor(cur)
                if (api == null) {
                    tokenProblem = true
                    errorMsg = gateway.apiError(cur) ?: "尚未配置该服务器的 Token"
                    return
                }
                try {
                    api.resolveProject()

                    indicator.text = "正在获取分支列表..."
                    gateway.branches(cur)

                    indicator.text = "正在获取项目成员..."
                    gateway.members(cur)
                } catch (ex: TokenInvalidException) {
                    tokenProblem = true
                    errorMsg = "Token 无效或已过期，请在设置中更新"
                } catch (ex: ApiException) {
                    errorMsg = "获取项目 ID 失败: ${ex.message}"
                }
            }
        })

        val problem = errorMsg
        if (problem != null) {
            val primaryInfo = primary?.info
            if (tokenProblem && primaryInfo != null) {
                // Token 问题：通知气泡 + "打开设置"按钮，引导用户到设置页配置
                val hostKey = GitLabUtils.hostKey(primaryInfo)
                val notification = Notification(
                    "GitLab MR", "GitLab MR",
                    "$problem。<br>GitLab 服务器: <b>$hostKey</b>",
                    NotificationType.WARNING,
                )
                notification.addAction(object : AnAction("打开设置...") {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, AccessTokenConfigurable::class.java)
                    }
                })
                Notifications.Bus.notify(notification, project)
            } else {
                Messages.showErrorDialog(problem, "GitLab MR")
            }
            return
        }

        val found = discovery ?: run {
            Messages.showErrorDialog("连接 GitLab 失败，请重试。", "GitLab MR")
            return
        }
        val first = primary ?: run {
            Messages.showErrorDialog("连接 GitLab 失败，请重试。", "GitLab MR")
            return
        }

        // ---- 2. 弹出 MR 创建对话框 (多行计划表) ----
        // 触发时选中了几个仓库就自动建几行; 没有多选时只有当前仓库一行
        // 分支默认值来自跨项目共享的全局历史 (最近使用的一条)
        val defaults = config.defaultBranchPair()
        val initialRepos = if (found.selected.isNotEmpty()) found.selected else listOf(first)
        val initialRows = initialRepos.map { candidate ->
            MrRowInit(candidate, defaults.source, defaults.target)
        }
        val dialog = GitLabMrDialog(
            gateway = gateway,
            candidates = found.candidates,
            initialRows = initialRows,
            defaultReviewerText = config.recentReviewers().firstOrNull() ?: "",
        )
        if (!dialog.showAndGet()) return

        val rowList = dialog.rowResults()
        if (rowList.isEmpty()) return
        val reviewer = dialog.reviewer
        val outcomes = mutableListOf<MrCreateOutcome>()

        // ---- 3. 逐行创建 MR ----
        ProgressManager.getInstance().run(object : Task.Modal(project, "创建 Merge Request...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0

                rowList.forEachIndexed { index, row ->
                    indicator.fraction = index.toDouble() / rowList.size
                    indicator.text = "正在创建 Merge Request (${index + 1}/${rowList.size})"
                    indicator.text2 = "${row.candidate.info.projectPath}: ${row.source} → ${row.target}"

                    // 逐行收口: createMr 开头的 resolveProject() 在内部 try 之外, 会抛 Token/Api 异常,
                    // 不单独 catch 的话一行失败会中断整批
                    val result = try {
                        val api = gateway.apiFor(row.candidate)
                        if (api == null) {
                            MrResult(false, null, gateway.apiError(row.candidate) ?: "无法连接 GitLab")
                        } else {
                            api.createMr(
                                MrCreateParams(
                                    sourceBranch = row.source,
                                    targetBranch = row.target,
                                    title = row.title,
                                    description = "",
                                    assigneeUsernames = reviewer,
                                    reviewerUsernames = reviewer,
                                )
                            )
                        }
                    } catch (ex: TokenInvalidException) {
                        MrResult(false, null, "Token 无效或已过期，请在设置中更新")
                    } catch (ex: Exception) {
                        MrResult(false, null, ex.message ?: "未知错误")
                    }

                    outcomes += MrCreateOutcome(
                        projectPath = row.candidate.info.projectPath,
                        projectKey = row.candidate.projectKey,
                        sourceBranch = row.source,
                        targetBranch = row.target,
                        title = row.title,
                        success = result.success,
                        webUrl = result.webUrl,
                        errorMessage = result.errorMessage,
                    )
                }
                indicator.fraction = 1.0
            }
        })

        ApplicationManager.getApplication().invokeLater {
            val successes = outcomes.filter { it.success }
            val failures = outcomes.filterNot { it.success }

            // 成功后记录到跨项目共享的全局历史 (分支组合 + 审核人)
            successes.forEach { outcome ->
                config.rememberBranchPair(outcome.sourceBranch, outcome.targetBranch)
            }
            config.rememberReviewer(reviewer)

            if (successes.isNotEmpty()) {
                MrNotifications.notifyMrsCreated(project, successes, failures, reviewer)
            } else {
                Messages.showErrorDialog(failureReport(outcomes), "GitLab MR")
            }
        }
    }

    /** 全部失败时的汇总文案: 单行保持旧版语气, 多行逐条列出 */
    private fun failureReport(outcomes: List<MrCreateOutcome>): String {
        if (outcomes.size == 1) {
            return "创建 MR 失败: ${outcomes.first().errorMessage ?: "未知错误"}"
        }
        return buildString {
            append("创建 Merge Request 失败：")
            for (outcome in outcomes) {
                append("\n").append(outcome.projectPath).append("  ")
                    .append(outcome.sourceBranch).append(" → ").append(outcome.targetBranch)
                    .append("  —  ").append(outcome.errorMessage ?: "未知错误")
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Git 仓库工具
    // ------------------------------------------------------------------ //

    /**
     * 触发时选中文件/目录所属的仓库 (按选择顺序去重)。
     *
     * 右键 / 项目视图多选时 VIRTUAL_FILE_ARRAY 有多个文件, 每个文件定位到它所在的 Git 仓库,
     * 对话框会为每个选中项目自动建一行; 从主菜单或编辑器触发一般只有一个, 退化为当前仓库。
     */
    private fun selectedRepoRoots(project: Project, e: AnActionEvent): List<File> {
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return emptyList()
        val manager = GitRepositoryManager.getInstance(project)
        val seen = LinkedHashSet<String>()
        val roots = mutableListOf<File>()
        for (file in selected) {
            val root = try {
                manager.getRepositoryForFile(file)?.let { File(it.root.path) }
            } catch (ex: Exception) {
                null
            } ?: findGitRootByWalkingUp(File(file.path))
            if (root != null && seen.add(root.absolutePath)) roots.add(root)
        }
        return roots
    }

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
     * git4idea 未注册任何仓库时的兜底:
     * 从选中的文件/目录 (无选中时项目根目录) 开始逐级向上查找 .git，
     * 找到即认为该目录是仓库根。覆盖 "项目打开在仓库上级目录 / VCS 根未登记" 的场景。
     */
    private fun findGitRootByWalkingUp(e: AnActionEvent, project: Project): File? {
        val start = e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { File(it.path) }
            ?: project.basePath?.let { File(it) }
            ?: return null
        return findGitRootByWalkingUp(start)
    }

    private fun findGitRootByWalkingUp(start: File): File? {
        var dir = if (start.isDirectory) start else start.parentFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
