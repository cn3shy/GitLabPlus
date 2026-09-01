package com.gitlabmr.api

import com.gitlabmr.model.CurrentUser
import com.gitlabmr.model.MrItem
import com.gitlabmr.util.HttpUtils
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<GitLabServerApi>()

/**
 * GitLab 服务器级 API (与具体项目无关)
 *
 * 用于 "查看 Merge Request"：服务器地址来自设置页配置列表 (host，可含端口)，
 * 协议在首次调用时自动探测 (https 优先)；以 Token 身份查询
 * created_by_me / assigned_to_me 的全局 MR 列表。
 *
 * 仅支持 v4 API —— 全局 merge_requests 接口的 scope 语义在 v3 中不存在。
 */
class GitLabServerApi(
    private val host: String,
    private val token: String,
) {

    /** 探测成功后的 API 基础地址，如 https://git.example.com:9000/api/v4 */
    private var apiBase: String? = null

    /**
     * 探测可用协议并校验 Token，返回当前登录用户。
     *
     * @throws TokenInvalidException Token 无效
     * @throws ApiException 无法连接 (网络错误 / 非 v4 服务)
     */
    fun resolveCurrentUser(): CurrentUser {
        apiBase?.let { return fetchUser(it) }

        val errors = mutableListOf<String>()
        for (scheme in listOf("https", "http")) {
            val base = "$scheme://$host/api/v4"
            try {
                val (code, body) = HttpUtils.get("$base/user", headers())
                when {
                    code == 200 -> {
                        apiBase = base
                        return parseUser(body)
                    }
                    code == 401 -> throw TokenInvalidException("Token 无效或已过期")
                    else -> errors.add("$scheme HTTP $code")
                }
            } catch (e: TokenInvalidException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("连接 GitLab [${base}] 失败: ${e.javaClass.simpleName}: ${e.message}")
                errors.add("$scheme ${e.javaClass.simpleName}")
            }
        }
        throw ApiException("无法连接 GitLab 服务器 \"${host}\" (${errors.joinToString("; ")})，请检查地址、网络或 GitLab 版本 (仅支持 v4 API)。")
    }

    /**
     * 拉取指定 scope 的 Merge Request (按更新时间倒序)
     *
     * 已合并 (merged) 的 MR 通常量大且很少再操作，只拉取前 100 条 (第一页)；
     * 其余状态分页拉取全部。
     *
     * @param scope created_by_me / assigned_to_me
     * @param state opened / merged / closed / all
     * @throws TokenInvalidException / ApiException
     */
    fun fetchMergeRequests(scope: String, state: String): List<MrItem> {
        val base = requireBase()
        val url = "$base/merge_requests"
        val headers = headers()
        val items = mutableListOf<MrItem>()

        var page = 1
        while (page <= 100) {  // 上限保护，避免死循环
            val (code, body) = HttpUtils.get(
                url, headers, mapOf(
                    "scope" to scope,
                    "state" to state,
                    "order_by" to "updated_at",
                    "sort" to "desc",
                    "per_page" to "100",
                    "page" to page.toString(),
                )
            )
            if (code != 200) throw ApiException("查询 MR 失败: HTTP $code")
            val arr = JsonParser.parseString(body).asJsonArray
            for (elem in arr) {
                parseMr(elem)?.let { items.add(it) }
            }
            if (arr.size() < 100) break  // 不足一页，说明已是最后一页
            if (state == "merged") break  // 已合并只取前 100 条
            page++
        }
        return items
    }

    // ------------------------------------------------------------------ //
    //  Internal helpers
    // ------------------------------------------------------------------ //

    /** 确保 API 基础地址已探测 */
    private fun requireBase(): String {
        apiBase?.let { return it }
        resolveCurrentUser()
        return apiBase ?: throw ApiException("未连接 GitLab 服务器")
    }

    private fun headers(): Map<String, String> = mapOf(
        "PRIVATE-TOKEN" to token,
        "Authorization" to "Bearer $token",
    )

    private fun fetchUser(base: String): CurrentUser {
        val (code, body) = HttpUtils.get("$base/user", headers())
        if (code == 401) throw TokenInvalidException("Token 无效或已过期")
        if (code != 200) throw ApiException("读取当前用户失败: HTTP $code")
        return parseUser(body)
    }

    private fun parseUser(body: String): CurrentUser {
        val obj = JsonParser.parseString(body).asJsonObject
        return CurrentUser(
            id = obj.get("id")?.asInt ?: 0,
            username = obj.get("username")?.asString?.trim() ?: "",
            name = obj.get("name")?.asString?.trim() ?: "",
        )
    }

    private fun parseMr(elem: JsonElement): MrItem? {
        val obj = elem.asJsonObject
        val webUrl = obj.get("web_url")?.asString ?: return null
        val author = obj.getAsJsonObject("author")
        return MrItem(
            iid = obj.get("iid")?.asInt ?: 0,
            title = obj.get("title")?.asString?.trim() ?: "",
            state = obj.get("state")?.asString?.trim() ?: "",
            webUrl = webUrl,
            sourceBranch = obj.get("source_branch")?.asString ?: "",
            targetBranch = obj.get("target_branch")?.asString ?: "",
            authorName = author?.get("name")?.asString?.takeIf { it.isNotBlank() }
                ?: author?.get("username")?.asString ?: "",
            updatedAt = obj.get("updated_at")?.asString
                ?: obj.get("created_at")?.asString ?: "",
        )
    }
}
