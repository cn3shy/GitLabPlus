package com.gitlabmr.util

import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val LOG = logger<HttpUtils>()

/**
 * 轻量 HTTP 客户端工具类，替代 Python requests。
 *
 * 使用 Java 11+ HttpClient，无需额外依赖。
 */
object HttpUtils {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /**
     * 执行 GET 请求，返回状态码 + 响应体
     */
    fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
    ): Pair<Int, String> {
        val fullUrl = buildUrl(url, params)
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(fullUrl))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /**
     * 执行 POST 请求 (JSON body)，返回状态码 + 响应体
     */
    fun postJson(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
        timeoutSeconds: Long = 30,
    ): Pair<Int, String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val query = params.entries.joinToString("&") {
            "${urlEncode(it.key)}=${urlEncode(it.value)}"
        }
        val sep = if (base.contains("?")) "&" else "?"
        return "$base$sep$query"
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8)

    /**
     * 快速判断 URL 是否可达（HEAD 请求），返回 true/false
     */
    fun ping(url: String, timeoutSeconds: Long = 5): Boolean = try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
        val resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString())
        resp.statusCode() in 200..499
    } catch (e: Exception) {
        LOG.warn("Ping failed: ${e.message}")
        false
    }
}
