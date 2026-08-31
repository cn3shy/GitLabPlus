package com.gitlabmr.config

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.State

/**
 * 插件级持久化配置。
 *
 * - Token 直接保存在插件 XML state (本机配置文件) 中，不再依赖 PasswordSafe 等凭证后端，
 *   避免 IDE/插件升级后凭证库读取失败导致 Token "丢失"需要重新配置。
 * - 分支记忆 / 审核人记忆同样以 XML state 持久化。
 *
 * 与 Python 版 [~/.gitlab_mr_config.json] 对应。
 */
@State(
    name = "com.gitlabmr.config.GitLabMrConfigService",
    storages = [Storage("gitlab-mr-plugin.xml")]
)
@Service(Service.Level.APP)
class GitLabMrConfigService : PersistentStateComponent<GitLabMrConfigService.State> {

    data class BranchEntry(var source: String = "", var target: String = "")

    data class State(
        /** key = "$host/$projectPath" */
        var lastReviewers: MutableMap<String, String> = mutableMapOf(),
        /** key = "$host/$projectPath" */
        var lastBranches: MutableMap<String, BranchEntry> = mutableMapOf(),
        /** key = 规范化后的 host，value = Personal Access Token (明文存于本机 XML) */
        var tokens: MutableMap<String, String> = mutableMapOf(),
        /** 已保存过 Token 的 GitLab 服务器地址 (供设置页展示) */
        var knownHosts: MutableSet<String> = mutableSetOf(),
    )

    private var state: State = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    // ------------------------------------------------------------------ //
    //  Token  (插件 XML state；PasswordSafe 仅作历史遗留数据迁移来源)
    // ------------------------------------------------------------------ //

    private fun credentialAttributes(host: String): CredentialAttributes =
        // resetPassword = true: 绕过 PasswordSafe 的内存缓存,
        // 保证覆盖 / 删除 / 读取与底层密钥库即时一致 (否则 set(null) 删不掉旧值)
        CredentialAttributes("GitLabMR-$host", null, null, true)

    /**
     * 读取指定 host 对应的 Personal Access Token。
     *
     * 优先读插件 XML state；无值时回退读 PasswordSafe (历史版本的存储位置)，
     * 读到即自动搬进 XML 并清理 PasswordSafe，旧版本升级后首次读取即完成迁移。
     */
    fun loadToken(host: String): String? {
        val key = normalizeHost(host)
        state.tokens[key]?.takeIf { it.isNotBlank() }?.let { return it }
        val legacy = try {
            PasswordSafe.instance.getPassword(credentialAttributes(key))?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        if (legacy != null) {
            state.tokens[key] = legacy
            clearLegacyPasswordSafe(key)
            saveStateNow()
        }
        return legacy
    }

    /**
     * 保存 Token 到插件 XML state (token 为空串表示清空该 host 的记录)，
     * 同时清理历史版本遗留在 PasswordSafe 中的条目
     */
    fun saveToken(host: String, token: String) {
        val normalized = normalizeHost(host)
        if (token.isEmpty()) {
            state.tokens.remove(normalized)
        } else {
            state.tokens[normalized] = token
            state.knownHosts.add(normalized)
        }
        clearLegacyPasswordSafe(normalized)
        saveStateNow()
    }

    /**
     * 删除指定 host 的 Token (XML state + 历史 PasswordSafe 遗留条目)
     *
     * 兼容历史数据：旧版本可能以未规范化的 host (带协议前缀) 存储，
     * 因此原始字符串与规范化后的 key 都要删一遍。
     */
    fun removeToken(host: String) {
        for (key in setOf(host.trim(), normalizeHost(host))) {
            state.tokens.remove(key)
            state.knownHosts.remove(key)
            clearLegacyPasswordSafe(key)
        }
        saveStateNow()
    }

    /** 清理历史版本遗留在 PasswordSafe 中的 Token 条目 (幂等，凭证库不可用时忽略) */
    private fun clearLegacyPasswordSafe(host: String) {
        try {
            PasswordSafe.instance.set(credentialAttributes(host), null)
        } catch (e: Exception) {
            // XML 中已是权威数据，清理失败不影响功能
        }
    }

    /**
     * 迁移历史遗留数据 (幂等，无遗留时零开销)：
     * - 未规范化的 host 记录 (如 "http://git.example.com:9000") 规范化，
     *   XML 中对应 Token 搬到规范化 key 下
     * - 仍只存在于 PasswordSafe 的 Token 经 [loadToken] 懒迁移搬进 XML
     */
    fun migrateLegacyHosts() {
        val legacy = state.knownHosts.filter { it != normalizeHost(it) }
        val hasTokenlessHost = state.knownHosts.any { state.tokens[it].isNullOrBlank() }
        if (legacy.isEmpty() && !hasTokenlessHost) return
        for (old in legacy) {
            val normalized = normalizeHost(old)
            if (!state.tokens[old].isNullOrBlank() && state.tokens[normalized].isNullOrBlank()) {
                state.tokens[normalized] = state.tokens[old]!!
            }
            state.tokens.remove(old)
            clearLegacyPasswordSafe(old)
            state.knownHosts.remove(old)
            state.knownHosts.add(normalized)
        }
        // 缺 Token 的 host 触发一次懒迁移 (从 PasswordSafe 搬进 XML)
        for (host in state.knownHosts) loadToken(host)
        saveStateNow()
    }

    /**
     * 已保存过 Token 的 host 列表 (供设置页展示)
     */
    fun knownHosts(): Set<String> = state.knownHosts

    // ------------------------------------------------------------------ //
    //  项目记忆 (上次分支 / 审核人) —— 供设置页查看与编辑
    // ------------------------------------------------------------------ //

    /** 所有有记忆的项目 key ("$host/$projectPath") */
    fun projectKeys(): Set<String> = state.lastBranches.keys + state.lastReviewers.keys

    fun loadLastBranchesByKey(key: String): BranchEntry = state.lastBranches[key] ?: BranchEntry()

    fun loadLastReviewersByKey(key: String): String = state.lastReviewers[key] ?: ""

    fun updateLastBranches(key: String, source: String, target: String) {
        state.lastBranches[key] = BranchEntry(source.trim(), target.trim())
        saveStateNow()
    }

    fun updateLastReviewers(key: String, reviewers: String) {
        val trimmed = reviewers.trim()
        if (trimmed.isEmpty()) state.lastReviewers.remove(key) else state.lastReviewers[key] = trimmed
        saveStateNow()
    }

    /** 删除某个项目的全部记忆 */
    fun removeProjectMemory(key: String) {
        state.lastBranches.remove(key)
        state.lastReviewers.remove(key)
        saveStateNow()
    }

    // ------------------------------------------------------------------ //
    //  内部
    // ------------------------------------------------------------------ //

    /** 规范化 host：去掉协议前缀与尾部斜杠，避免同一服务器出现多条不同写法的记录 */
    private fun normalizeHost(raw: String): String =
        raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("ssh://")
            .trimEnd('/')

    /** 立即触发配置落盘，避免依赖 IDE 的周期性保存导致设置页/重启后数据"消失" */
    private fun saveStateNow() {
        try {
            ApplicationManager.getApplication().saveSettings()
        } catch (e: Exception) {
            // 忽略保存时机异常，状态仍在内存与最终退出保存中
        }
    }

    // ------------------------------------------------------------------ //
    //  Reviewers
    // ------------------------------------------------------------------ //

    fun loadLastReviewers(projectKey: String): String =
        state.lastReviewers[projectKey] ?: ""

    fun saveLastReviewers(projectKey: String, usernames: String) {
        state.lastReviewers[projectKey] = usernames
    }

    // ------------------------------------------------------------------ //
    //  Branches
    // ------------------------------------------------------------------ //

    fun loadLastBranches(projectKey: String): BranchEntry =
        state.lastBranches[projectKey] ?: BranchEntry()

    fun saveLastBranches(projectKey: String, source: String, target: String) {
        state.lastBranches[projectKey] = BranchEntry(source, target)
    }

    companion object {
        fun getInstance(): GitLabMrConfigService =
            ApplicationManager.getApplication().getService(GitLabMrConfigService::class.java)
    }
}
