package com.gitlabmr.util

import com.gitlabmr.model.GitLabInfo
import java.util.regex.Pattern

/**
 * GitLab 远程 URL 解析工具，对应 Python 版 [parse_gitlab_info]。
 *
 * 支持以下格式:
 * - HTTPS:  https://gitlab.com/group/subgroup/project.git
 * - SSH:    git@gitlab.com:group/subgroup/project.git
 *           ssh://git@gitlab.com:22/group/project.git
 */
object GitLabUtils {

    private val SSH_PATTERN: Pattern = Pattern.compile("""^([^@]+@)?([^:]+):(.+)$""")

    /**
     * 解析 GitLab 远程仓库 URL
     *
     * @param remoteUrl Git remote URL
     * @return GitLabInfo
     */
    fun parseGitLabInfo(remoteUrl: String): GitLabInfo {
        // 尝试 HTTP/HTTPS
        if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
            val uri = java.net.URI.create(remoteUrl)
            val scheme = uri.scheme
            val host = uri.host
            val port = if (uri.port > 0) uri.port else 0
            var path = uri.path.trimStart('/')
            if (path.endsWith(".git")) {
                path = path.dropLast(4)
            }
            return GitLabInfo(scheme, host, port, path)
        }

        // 尝试 ssh://
        if (remoteUrl.startsWith("ssh://")) {
            val uri = java.net.URI.create(remoteUrl)
            val host = uri.host ?: uri.userInfo?.let { extractHostFromUserInfo(it) } ?: ""
            val port = if (uri.port > 0) uri.port else 0
            var path = uri.path.trimStart('/')
            if (path.endsWith(".git")) {
                path = path.dropLast(4)
            }
            return GitLabInfo("https", host, port, path)
        }

        // 尝试 git@host:path 格式
        val matcher = SSH_PATTERN.matcher(remoteUrl)
        if (matcher.matches()) {
            val host = matcher.group(2)
            var path = matcher.group(3)
            if (path.endsWith(".git")) {
                path = path.dropLast(4)
            }
            return GitLabInfo("https", host, 0, path)
        }

        throw IllegalArgumentException("无法解析的远程 URL 格式: $remoteUrl")
    }

    private fun extractHostFromUserInfo(userInfo: String): String? {
        // userInfo 格式: git@host
        val idx = userInfo.indexOf('@')
        return if (idx >= 0) userInfo.substring(idx + 1) else null
    }

    /**
     * 构建项目 URL 编码后的路径 (用于 API 调用)
     */
    fun encodeProjectPath(projectPath: String): String =
        projectPath.replace("/", "%2F")

    /**
     * 构建带端口的基础 URL (port = 0 表示使用协议默认端口)
     */
    fun baseUrl(info: GitLabInfo, scheme: String): String =
        if (info.port > 0) "$scheme://${info.host}:${info.port}" else "$scheme://${info.host}"

    /**
     * 生成 host 标识 (含端口)，作为 Token / 记忆的统一存储 key。
     * 创建 MR 读取 Token 与设置页管理 Token 必须使用同一个 key。
     */
    fun hostKey(info: GitLabInfo): String =
        if (info.port > 0) "${info.host}:${info.port}" else info.host

    /**
     * 生成项目唯一标识 key，用于配置记忆
     */
    fun projectKey(info: GitLabInfo): String =
        "${hostKey(info)}/${info.projectPath}"
}
