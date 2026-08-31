package com.gitlabmr.api

import com.gitlabmr.model.GitLabInfo
import com.gitlabmr.model.Member
import com.gitlabmr.model.MrCreateParams
import com.gitlabmr.model.MrResult
import com.gitlabmr.model.ProjectInfo
import com.gitlabmr.util.HttpUtils
import com.gitlabmr.util.GitLabUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<GitLabApi>()

/** MR 标题生成时排除的 commit 前缀 (不区分大小写) —— 机器人提交与琐碎更新不参与标题 */
private val EXCLUDED_COMMIT_PREFIXES = listOf("merge", "jenkins", "update")

/**
 * 封装 GitLab REST API 调用，对应 Python 版中多个 fetch_* 函数。
 *
 * 构造函数接收 [GitLabInfo] + token，在首次调用前通过 [resolveProject] 获取
 * ProjectInfo (project_id / api_version / used_scheme)。
 */
class GitLabApi(
    private val info: GitLabInfo,
    private val token: String,
) {

    private var projectInfo: ProjectInfo? = null

    private val gson = Gson()

    // ------------------------------------------------------------------ //
    //  Public API
    // ------------------------------------------------------------------ //

    /**
     * 获取 ProjectInfo (project_id + api_version + used_scheme)
     *
     * 与 Python 版 [get_project_id] 对应，尝试 scheme×api_version 组合。
     * @throws TokenInvalidException Token 无效
     * @throws ApiException 其他 API 错误
     */
    fun resolveProject(): ProjectInfo {
        projectInfo?.let { return it }

        val encodedPath = GitLabUtils.encodeProjectPath(info.projectPath)
        val schemes = mutableListOf(info.scheme, "https", "http").distinct()

        for (scheme in schemes) {
            val baseUrl = GitLabUtils.baseUrl(info, scheme)
            for (apiVersion in listOf("v4", "v3")) {
                val url = "$baseUrl/api/$apiVersion/projects/$encodedPath"
                val headers = apiHeaders()
                try {
                    val (code, body) = HttpUtils.get(url, headers)
                    when {
                        code == 200 -> {
                            val obj = JsonParser.parseString(body).asJsonObject
                            val id = obj.get("id").asInt
                            val pi = ProjectInfo(id, apiVersion, scheme)
                            projectInfo = pi
                            return pi
                        }
                        code == 401 -> throw TokenInvalidException("Token 无效或已过期")
                        else -> LOG.warn("查询项目失败: $scheme $apiVersion HTTP $code: ${body.take(150)}")
                    }
                } catch (e: TokenInvalidException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("连接失败 [${baseUrl}] (${e.javaClass.simpleName}: ${e.message})，尝试其他协议...")
                    break  // 网络层错误，换 scheme
                }
            }
        }
        throw ApiException("无法获取项目 ID，请检查账号权限或网络。")
    }

    /**
     * 读取远程分支列表 (分页拉取全部，GitLab 每页最多 100 条)
     */
    fun fetchRemoteBranches(): List<String> {
        val pi = resolveProject()
        val url = "${apiBase(pi)}/projects/${pi.projectId}/repository/branches"
        val headers = apiHeaders()
        val names = mutableListOf<String>()
        try {
            var page = 1
            while (page <= 100) {  // 上限保护，避免死循环
                val (code, body) = HttpUtils.get(
                    url, headers, mapOf("per_page" to "100", "page" to page.toString())
                )
                if (code != 200) break
                val arr = JsonParser.parseString(body).asJsonArray
                for (elem in arr) {
                    val name = elem.asJsonObject.get("name")?.asString?.trim()
                    if (!name.isNullOrEmpty()) names.add(name)
                }
                if (arr.size() < 100) break  // 不足一页，说明已是最后一页
                page++
            }
        } catch (e: Exception) {
            LOG.warn("读取远程分支失败: ${e.message}")
        }
        return names.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 读取项目成员列表 (含继承成员，分页拉取全部)
     */
    fun fetchProjectMembers(): List<Member> {
        val pi = resolveProject()
        val baseUrl = apiBase(pi)
        val headers = apiHeaders()
        val urls = mutableListOf<String>()
        if (pi.apiVersion == "v4") {
            urls.add("$baseUrl/projects/${pi.projectId}/members/all")
        }
        urls.add("$baseUrl/projects/${pi.projectId}/members")

        val seen = mutableSetOf<String>()
        val members = mutableListOf<Member>()
        for (url in urls) {
            try {
                var page = 1
                while (page <= 100) {  // 上限保护，避免死循环
                    val (code, body) = HttpUtils.get(
                        url, headers, mapOf("per_page" to "100", "page" to page.toString())
                    )
                    if (code != 200) break
                    val arr = JsonParser.parseString(body).asJsonArray
                    for (elem in arr) {
                        val obj = elem.asJsonObject
                        val username = obj.get("username")?.asString?.trim() ?: continue
                        if (username.isEmpty() || username in seen) continue
                        seen.add(username)
                        members.add(Member(
                            id = obj.get("id")?.asInt ?: 0,
                            username = username,
                            name = obj.get("name")?.asString?.trim() ?: "",
                        ))
                    }
                    if (arr.size() < 100) break  // 不足一页，说明已是最后一页
                    page++
                }
            } catch (e: Exception) {
                // 忽略单个 URL 的异常
            }
        }
        return members
    }

    /**
     * 读取两个分支之间的差异 commit 标题列表 (双向)
     *
     * 与 Python 版 [fetch_diff_commit_subjects] 对应。
     */
    fun fetchDiffCommitSubjects(sourceBranch: String, targetBranch: String): List<String> {
        val pi = resolveProject()
        val subjects = mutableListOf<String>()
        subjects.addAll(fetchCompareCommits(pi, targetBranch, sourceBranch))
        subjects.addAll(fetchCompareCommits(pi, sourceBranch, targetBranch))
        return subjects
    }

    /**
     * 创建 Merge Request
     */
    fun createMr(params: MrCreateParams): MrResult {
        val pi = resolveProject()
        val baseUrl = apiBase(pi)
        val url = "$baseUrl/projects/${pi.projectId}/merge_requests"
        val headers = apiHeaders(json = true)

        val assigneeIds = getUserIds(baseUrl, params.assigneeUsernames)
        val reviewerIds = getUserIds(baseUrl, params.reviewerUsernames)

        val payload = JsonObject().apply {
            addProperty("source_branch", params.sourceBranch)
            addProperty("target_branch", params.targetBranch)
            addProperty("title", params.title)
            addProperty("description", params.description)
            addProperty("remove_source_branch", true)
            if (pi.apiVersion == "v4") {
                if (assigneeIds.isNotEmpty()) {
                    add("assignee_ids", gson.toJsonTree(assigneeIds))
                }
                if (reviewerIds.isNotEmpty()) {
                    add("reviewer_ids", gson.toJsonTree(reviewerIds))
                }
            } else {
                if (assigneeIds.isNotEmpty()) {
                    addProperty("assignee_id", assigneeIds.first())
                }
            }
        }

        return try {
            val (code, body) = HttpUtils.postJson(url, headers, payload.toString())
            if (code == 200 || code == 201) {
                val resp = JsonParser.parseString(body).asJsonObject
                val webUrl = resp.get("web_url")?.asString
                    ?: resp.get("url")?.asString
                MrResult(success = true, webUrl = webUrl, errorMessage = null)
            } else {
                MrResult(success = false, webUrl = null, errorMessage = "HTTP $code: ${body.take(300)}")
            }
        } catch (e: Exception) {
            MrResult(success = false, webUrl = null, errorMessage = e.message ?: "未知错误")
        }
    }

    // ------------------------------------------------------------------ //
    //  Internal helpers
    // ------------------------------------------------------------------ //

    /** API 基础地址 (含实际使用的协议、端口与 api 版本) */
    private fun apiBase(pi: ProjectInfo): String =
        "${GitLabUtils.baseUrl(info, pi.usedScheme)}/api/${pi.apiVersion}"

    private fun apiHeaders(json: Boolean = false): Map<String, String> {
        val h = mutableMapOf(
            "PRIVATE-TOKEN" to token,
            "Authorization" to "Bearer $token",
        )
        if (json) h["Content-Type"] = "application/json"
        return h
    }

    private fun fetchCompareCommits(
        pi: ProjectInfo,
        fromRef: String,
        toRef: String,
    ): List<String> {
        val url = "${apiBase(pi)}/projects/${pi.projectId}/repository/compare"
        val headers = apiHeaders()
        val params = mapOf("from" to fromRef, "to" to toRef, "per_page" to "100")
        return try {
            val (code, body) = HttpUtils.get(url, headers, params, 15)
            if (code != 200) return emptyList()
            val obj = JsonParser.parseString(body).asJsonObject
            val commits = obj.get("commits")?.asJsonArray ?: return emptyList()
            commits.mapNotNull { c ->
                c.asJsonObject.get("title")?.asString?.trim()
            }.filter { subject ->
                subject.isNotEmpty() &&
                    EXCLUDED_COMMIT_PREFIXES.none { subject.startsWith(it, ignoreCase = true) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getUserIds(baseUrl: String, usernames: String): List<Int> {
        if (usernames.isBlank()) return emptyList()
        val ids = mutableListOf<Int>()
        for (name in usernames.split(",")) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) continue
            try {
                val (code, body) = HttpUtils.get(
                    "$baseUrl/users",
                    apiHeaders(),
                    mapOf("username" to trimmed),
                )
                if (code == 200) {
                    val arr = JsonParser.parseString(body).asJsonArray
                    if (arr.size() > 0) {
                        ids.add(arr[0].asJsonObject.get("id").asInt)
                    } else {
                        LOG.warn("未找到用户 '$trimmed'，跳过")
                    }
                } else {
                    LOG.warn("查询用户 '$trimmed' 失败，跳过")
                }
            } catch (e: Exception) {
                LOG.warn("查询用户 '$trimmed' 异常: ${e.message}")
            }
        }
        return ids
    }
}

// ---------------------------------------------------------------------- //
//  Exceptions
// ---------------------------------------------------------------------- //

class TokenInvalidException(message: String) : Exception(message)
class ApiException(message: String) : Exception(message)
