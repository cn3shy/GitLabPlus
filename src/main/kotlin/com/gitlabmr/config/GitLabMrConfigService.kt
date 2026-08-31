package com.gitlabmr.config

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.State

/**
 * 插件级持久化配置。
 *
 * - Token 保存在 IDE 的 PasswordSafe (加密密钥库) 中，不再以明文 JSON 存储。
 * - 分支记忆 / 审核人记忆以 XML state 持久化。
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
        /** 已保存过 Token 的 GitLab 服务器地址 (Token 本体在 PasswordSafe 中，此处仅记录 host 供设置页展示) */
        var knownHosts: MutableSet<String> = mutableSetOf(),
    )

    private var state: State = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    // ------------------------------------------------------------------ //
    //  Token  (PasswordSafe)
    // ------------------------------------------------------------------ //

    private fun credentialAttributes(host: String): CredentialAttributes =
        // resetPassword = true: 绕过 PasswordSafe 的内存缓存,
        // 保证覆盖 / 删除 / 读取与底层密钥库即时一致 (否则 set(null) 删不掉旧值)
        CredentialAttributes("GitLabMR-$host", null, null, true)

    /**
     * 读取指定 host 对应的 Personal Access Token
     */
    fun loadToken(host: String): String? {
        return try {
            PasswordSafe.instance.getPassword(credentialAttributes(host))?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 保存 Token 到 PasswordSafe (token 为空串表示清空该 host 的记录)
     */
    fun saveToken(host: String, token: String) {
        val normalized = normalizeHost(host)
        val attrs = credentialAttributes(normalized)
        if (token.isEmpty()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials(null, token))
        }
        state.knownHosts.add(normalized)
        saveStateNow()
    }

    /**
     * 删除指定 host 的 Token (PasswordSafe + host 记录)
     *
     * 兼容历史数据：旧版本可能以未规范化的 host (带协议前缀) 存储，
     * 因此原始字符串与规范化后的 key 都要删一遍。
     */
    fun removeToken(host: String) {
        for (key in setOf(host.trim(), normalizeHost(host))) {
            PasswordSafe.instance.set(credentialAttributes(key), null)
            state.knownHosts.remove(key)
        }
        saveStateNow()
    }

    /**
     * 迁移历史遗留的未规范化 host 记录 (如 "http://git.example.com:9000"):
     * - knownHosts 条目规范化
     * - PasswordSafe 中的 Token 搬到规范化 key 下 (若新 key 尚无值) 并删除旧 key
     *
     * 幂等，无遗留数据时零开销。
     */
    fun migrateLegacyHosts() {
        val legacy = state.knownHosts.filter { it != normalizeHost(it) }
        if (legacy.isEmpty()) return
        for (old in legacy) {
            val normalized = normalizeHost(old)
            val legacyValue = try {
                PasswordSafe.instance.getPassword(credentialAttributes(old))
            } catch (e: Exception) {
                null
            }
            if (!legacyValue.isNullOrBlank() && loadToken(normalized).isNullOrBlank()) {
                PasswordSafe.instance.set(
                    credentialAttributes(normalized), Credentials(null, legacyValue)
                )
            }
            PasswordSafe.instance.set(credentialAttributes(old), null)
            state.knownHosts.remove(old)
            state.knownHosts.add(normalized)
        }
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
