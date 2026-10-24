package com.gitlabmr.api

import com.gitlabmr.config.GitLabMrConfigService
import com.gitlabmr.model.GitLabInfo
import com.gitlabmr.model.Member
import com.gitlabmr.util.GitLabUtils
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.repo.GitRemote
import git4idea.repo.GitRepositoryManager
import java.io.File

private val LOG = logger<MrRepoGateway>()

/**
 * 一行 MR 的目标项目: 本地 Git 仓库 origin 远程 URL 解析出的 GitLab 项目
 *
 * @param repoName `.git` 上级目录名 (本地仓库名), 行内下拉只展示它
 */
data class MrRepoCandidate(val info: GitLabInfo, val repoName: String) {
    val projectKey: String get() = GitLabUtils.projectKey(info)

    override fun toString(): String = repoName
}

/**
 * 本地 Git 仓库扫描 + 按项目缓存的分支 / 成员 / 标题生成。
 *
 * 缓存生命周期 = 一次创建流程, 不跨次复用 (分支与成员会变, 缓存久了就是错的)。
 * 所有读取方法串行化: 两行选同一项目不会重复请求, 也避免并发打满 GitLab。
 */
class MrRepoGateway(private val project: Project) {

    /**
     * @param selected 触发时选中文件所属的可用项目 (按选择顺序去重), 用于自动建行
     * @param currentRepoError 无选择时当前上下文仓库不可用的原因
     */
    data class Discovery(
        val candidates: List<MrRepoCandidate>,
        val selected: List<MrRepoCandidate>,
        val currentRepoError: String?,
    )

    private val lock = Any()
    private val apis = HashMap<String, GitLabApi>()
    private val branchCache = HashMap<String, List<String>>()
    private val memberCache = HashMap<String, List<Member>>()

    /** projectKey -> 不可用原因 (无 Token / 取不到数据), 供行内提示与失败汇总 */
    private val errors = HashMap<String, String>()

    /**
     * 扫描本地仓库: 触发时选中的项目排最前 (右键 / 项目视图多选), 其次是当前上下文仓库,
     * 最后是 IDE 已登记的其他仓库; 非 GitLab 仓库静默跳过; 按 projectKey 去重。
     *
     * 每个仓库可能触发一次 git 子进程读取 remote, 必须在后台线程调用。
     */
    fun discoverLocalRepos(
        currentRoot: File?,
        currentName: String?,
        selectedRoots: List<File> = emptyList(),
    ): Discovery = synchronized(lock) {
        val seen = LinkedHashMap<String, MrRepoCandidate>()
        val selected = mutableListOf<MrRepoCandidate>()
        var currentError: String? = null

        // 1) 触发时选中的仓库: 顺序保留, 每个选中项目自动建一行
        for (root in selectedRoots) {
            val candidate = candidateOf(root) ?: continue
            if (seen.putIfAbsent(candidate.projectKey, candidate) == null) {
                selected.add(candidate)
            }
        }

        // 2) 当前上下文仓库 (向上找到但未登记的仓库也要能用)
        if (currentRoot != null) {
            val url = originUrl(currentRoot)
            if (url.isNullOrBlank()) {
                currentError = "未找到 origin 远程仓库，请检查。"
            } else {
                try {
                    val info = GitLabUtils.parseGitLabInfo(url)
                    seen.putIfAbsent(
                        GitLabUtils.projectKey(info),
                        MrRepoCandidate(info, currentName ?: currentRoot.name),
                    )
                } catch (ex: Exception) {
                    currentError = "解析远程 URL 失败: ${ex.message}"
                }
            }
        }

        // 3) IDE 已登记的其他仓库
        for (repo in GitRepositoryManager.getInstance(project).repositories) {
            val root = File(repo.root.path)
            // 优先用 git4idea 已解析的 remote (0 子进程), 未就绪时回落 git 命令
            val url = repo.remotes.firstOrNull { it.name == GitRemote.ORIGIN }?.firstUrl
                ?.takeIf { it.isNotBlank() }
                ?: originUrl(root)
                ?: continue
            try {
                val info = GitLabUtils.parseGitLabInfo(url)
                seen.putIfAbsent(GitLabUtils.projectKey(info), MrRepoCandidate(info, root.name))
            } catch (ex: Exception) {
                LOG.info("跳过非 GitLab 仓库 ${root.name}: ${ex.message}")
            }
        }

        Discovery(seen.values.toList(), selected, currentError)
    }

    /** 解析单个本地仓库; 非 Git 目录或非 GitLab 仓库返回 null */
    private fun candidateOf(root: File): MrRepoCandidate? {
        val url = originUrl(root) ?: return null
        return try {
            MrRepoCandidate(GitLabUtils.parseGitLabInfo(url), root.name)
        } catch (ex: Exception) {
            LOG.info("跳过非 GitLab 仓库 ${root.name}: ${ex.message}")
            null
        }
    }

    /** 返回 null 表示该项目不可用, 原因见 [apiError] */
    fun apiFor(candidate: MrRepoCandidate): GitLabApi? = synchronized(lock) {
        val key = candidate.projectKey
        apis[key]?.let { return@synchronized it }

        val hostKey = GitLabUtils.hostKey(candidate.info)
        val token = GitLabMrConfigService.getInstance().loadToken(hostKey)
        if (token.isNullOrBlank()) {
            errors[key] = "尚未配置 \"$hostKey\" 的 Token"
            return@synchronized null
        }
        GitLabApi(candidate.info, token).also { apis[key] = it }
    }

    fun apiError(candidate: MrRepoCandidate): String? =
        synchronized(lock) { errors[candidate.projectKey] }

    fun branches(candidate: MrRepoCandidate): List<String> = synchronized(lock) {
        val key = candidate.projectKey
        branchCache[key]?.let { return@synchronized it }

        val api = apiFor(candidate) ?: return@synchronized emptyList()
        val list = try {
            api.fetchRemoteBranches()
        } catch (ex: Exception) {
            LOG.warn("读取 $key 分支失败: ${ex.message}")
            emptyList()
        }
        // fetchRemoteBranches 内部吞异常返回空表, 无法区分"没分支"和"取失败"
        if (list.isEmpty()) errors.putIfAbsent(key, "未取到分支列表（可手动输入分支名）")
        branchCache[key] = list
        list
    }

    fun members(candidate: MrRepoCandidate): List<Member> = synchronized(lock) {
        val key = candidate.projectKey
        memberCache[key]?.let { return@synchronized it }

        val api = apiFor(candidate) ?: return@synchronized emptyList()
        val list = try {
            api.fetchProjectMembers()
        } catch (ex: Exception) {
            LOG.warn("读取 $key 成员失败: ${ex.message}")
            emptyList()
        }
        memberCache[key] = list
        list
    }

    /** 按该行项目的分支差异生成标题; 失败返回空串 (由 UI 提示可手动填写) */
    fun generateTitle(candidate: MrRepoCandidate, source: String, target: String): String =
        synchronized(lock) {
            val api = apiFor(candidate) ?: return@synchronized ""
            try {
                val subjects = api.fetchDiffCommitSubjects(source, target)
                if (subjects.isEmpty()) "" else subjects.take(10).joinToString(", ")
            } catch (ex: Exception) {
                LOG.warn("生成标题失败: ${ex.message}")
                ""
            }
        }

    /** 审核人候选: 各行项目的成员并集 (按 username 去重排序) */
    fun mergedMembers(candidates: List<MrRepoCandidate>): List<Member> {
        val merged = LinkedHashMap<String, Member>()
        for (candidate in candidates) {
            for (member in members(candidate)) {
                merged.putIfAbsent(member.username, member)
            }
        }
        return merged.values.sortedBy { it.username }
    }

    /** git 命令读取 origin URL (git4idea remote 未就绪时的兜底) */
    private fun originUrl(repoRoot: File): String? = try {
        val process = ProcessBuilder("git", "remote", "get-url", "origin")
            .directory(repoRoot)
            .redirectErrorStream(false)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        if (code == 0 && text.isNotEmpty()) text else null
    } catch (ex: Exception) {
        LOG.warn("获取 remote URL 失败: ${ex.message}")
        null
    }
}
