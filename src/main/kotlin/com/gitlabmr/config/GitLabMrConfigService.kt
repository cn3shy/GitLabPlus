package com.gitlabmr.config

import com.gitlabmr.notify.NotifyChannel
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
        /** 历史版本的项目记忆: 仅用于升级时一次性并入全局历史, 之后不再写入 */
        var lastReviewers: MutableMap<String, String> = mutableMapOf(),
        /** 历史版本的项目记忆: 仅用于升级时一次性并入全局历史, 之后不再写入 */
        var lastBranches: MutableMap<String, BranchEntry> = mutableMapOf(),
        /** 全局共享的历史分支组合 (跨项目), 最近使用的在前 */
        var recentBranchPairs: MutableList<BranchEntry> = mutableListOf(),
        /** 全局共享的最近审核人 (跨项目), 最近使用的在前 */
        var recentReviewers: MutableList<String> = mutableListOf(),
        /** key = 规范化后的 host，value = Personal Access Token (明文存于本机 XML) */
        var tokens: MutableMap<String, String> = mutableMapOf(),
        /** 已保存过 Token 的 GitLab 服务器地址 (供设置页展示) */
        var knownHosts: MutableSet<String> = mutableSetOf(),
        /** "查看 Merge Request" 上次使用的服务器 (host，可含端口) */
        var lastViewHost: String = "",
        /** "查看 Merge Request" 上次使用的状态过滤 (opened / merged / closed / all) */
        var lastViewState: String = "",
        /** "查看 Merge Request" 上次使用的范围过滤 (all / created_by_me / assigned_to_me) */
        var lastViewScope: String = "",
        /** 定时提醒:是否开启 (默认关闭) */
        var notifyEnabled: Boolean = false,
        /** 定时提醒:检查间隔 (分钟) */
        var notifyIntervalMinutes: Int = DEFAULT_NOTIFY_INTERVAL_MINUTES,
        /** 已提醒过的 MR:key = web_url,value = 上次提醒时的 updated_at (值变化视为"有更新") */
        var notifiedMrs: MutableMap<String, String> = mutableMapOf(),
        /** 通知方式:notice 气泡 (默认开启) */
        var notifyViaBalloon: Boolean = true,
        /** 通知方式:常驻弹窗 (不自动消失) */
        var notifyViaWindow: Boolean = false,
        /** 通知方式:系统通知 + 任务栏闪烁 (合并成一条通道,两者一起才有闭环) */
        var notifyViaSystemFlash: Boolean = false,
    )

    private var state: State = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
        migrateProjectMemory()
    }

    /**
     * 历史版本按项目保存的分支 / 审核人, 升级时一次性并入跨项目共享的全局历史;
     * 幂等: 全局历史已有数据时只清理遗留字段, 不再重复迁移。
     */
    private fun migrateProjectMemory() {
        if (state.recentBranchPairs.isEmpty()) {
            val seen = HashSet<Pair<String, String>>()
            for (entry in state.lastBranches.values) {
                if (entry.source.isBlank() || entry.target.isBlank() || entry.source == entry.target) continue
                if (!seen.add(entry.source to entry.target)) continue
                state.recentBranchPairs.add(entry)
                if (state.recentBranchPairs.size >= MAX_BRANCH_PAIRS) break
            }
        }
        if (state.recentReviewers.isEmpty()) {
            val seen = HashSet<String>()
            outer@ for (reviewers in state.lastReviewers.values) {
                for (name in reviewers.split(",")) {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty() || !seen.add(trimmed)) continue
                    state.recentReviewers.add(trimmed)
                    if (state.recentReviewers.size >= MAX_RECENT_REVIEWERS) break@outer
                }
            }
        }
        state.lastBranches.clear()
        state.lastReviewers.clear()
    }

    // ------------------------------------------------------------------ //
    //  Token  (插件 XML state；PasswordSafe 仅作历史遗留数据迁移来源)
    // ------------------------------------------------------------------ //

    private fun credentialAttributes(host: String): CredentialAttributes =
        // isPasswordMemoryOnly = true: 绕过 PasswordSafe 的内存缓存,
        // 保证覆盖 / 删除 / 读取与底层密钥库即时一致 (否则 set(null) 删不掉旧值)
        CredentialAttributes("GitLabMR-$host", null, true)

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
    //  查看 Merge Request (上次查询条件：服务器 / 状态 / 范围)
    // ------------------------------------------------------------------ //

    fun loadLastViewHost(): String = state.lastViewHost

    fun loadLastViewState(): String = state.lastViewState

    fun loadLastViewScope(): String = state.lastViewScope

    fun saveLastViewHost(host: String) {
        state.lastViewHost = host
        saveStateNow()
    }

    fun saveLastViewState(state: String) {
        this.state.lastViewState = state
        saveStateNow()
    }

    fun saveLastViewScope(scope: String) {
        this.state.lastViewScope = scope
        saveStateNow()
    }

    // ------------------------------------------------------------------ //
    //  全局历史 (跨项目共享): 分支组合 / 审核人
    // ------------------------------------------------------------------ //

    /** 全局共享的历史分支组合 (最近使用的在前, 去重) */
    fun recentBranchPairs(): List<BranchEntry> =
        state.recentBranchPairs.distinctBy { it.source to it.target }

    /**
     * 记录一对成功创建过 MR 的分支: 全局去重并置顶, 最多保留 [MAX_BRANCH_PAIRS] 条。
     *
     * 跨项目共享: 不同仓库常用同一组分支名 (如 dev-ss → dev), 创建对话框里可直接点选带出。
     */
    fun rememberBranchPair(source: String, target: String) {
        val pair = BranchEntry(source.trim(), target.trim())
        if (pair.source.isEmpty() || pair.target.isEmpty() || pair.source == pair.target) return
        state.recentBranchPairs.removeAll { it.source == pair.source && it.target == pair.target }
        state.recentBranchPairs.add(0, pair)
        trimBranchPairs()
        saveStateNow()
    }

    /** 编辑一条历史分支组合 (历史记忆设置页): 旧值删除, 新值去重后置顶 */
    fun updateBranchPair(oldSource: String, oldTarget: String, newSource: String, newTarget: String) {
        val pair = BranchEntry(newSource.trim(), newTarget.trim())
        if (pair.source.isEmpty() || pair.target.isEmpty() || pair.source == pair.target) return
        state.recentBranchPairs.removeAll {
            (it.source == oldSource && it.target == oldTarget) ||
                (it.source == pair.source && it.target == pair.target)
        }
        state.recentBranchPairs.add(0, pair)
        trimBranchPairs()
        saveStateNow()
    }

    /** 删除一条历史分支组合 */
    fun removeBranchPair(source: String, target: String) {
        if (state.recentBranchPairs.removeAll { it.source == source && it.target == target }) {
            saveStateNow()
        }
    }

    private fun trimBranchPairs() {
        while (state.recentBranchPairs.size > MAX_BRANCH_PAIRS) {
            state.recentBranchPairs.removeAt(state.recentBranchPairs.size - 1)
        }
    }

    /** 新行默认分支: 全局历史里最近的一条, 没有历史则 develop → develop */
    fun defaultBranchPair(): BranchEntry =
        recentBranchPairs().firstOrNull() ?: BranchEntry("develop", "develop")

    /** 全局共享的最近审核人 (最近使用的在前, 去重) */
    fun recentReviewers(): List<String> = state.recentReviewers.distinct()

    /** 记录最近使用的审核人: 全局去重并置顶, 最多保留 [MAX_RECENT_REVIEWERS] 条 */
    fun rememberReviewer(reviewer: String) {
        val name = reviewer.trim()
        if (name.isEmpty()) return
        state.recentReviewers.removeAll { it == name }
        state.recentReviewers.add(0, name)
        trimReviewers()
        saveStateNow()
    }

    /** 编辑一条历史审核人 (历史记忆设置页): 旧值删除, 新值去重后置顶 */
    fun updateReviewer(oldName: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        state.recentReviewers.removeAll { it == oldName || it == name }
        state.recentReviewers.add(0, name)
        trimReviewers()
        saveStateNow()
    }

    /** 删除一条历史审核人 */
    fun removeReviewer(name: String) {
        if (state.recentReviewers.removeAll { it == name }) {
            saveStateNow()
        }
    }

    private fun trimReviewers() {
        while (state.recentReviewers.size > MAX_RECENT_REVIEWERS) {
            state.recentReviewers.removeAt(state.recentReviewers.size - 1)
        }
    }

    // ------------------------------------------------------------------ //
    //  定时提醒 (指给我的 MR 轮询)
    // ------------------------------------------------------------------ //

    fun isNotifyEnabled(): Boolean = state.notifyEnabled

    fun setNotifyEnabled(enabled: Boolean) {
        state.notifyEnabled = enabled
        saveStateNow()
    }

    fun notifyIntervalMinutes(): Int = state.notifyIntervalMinutes.coerceIn(
        MIN_NOTIFY_INTERVAL_MINUTES,
        MAX_NOTIFY_INTERVAL_MINUTES,
    )

    fun setNotifyIntervalMinutes(minutes: Int) {
        state.notifyIntervalMinutes = minutes.coerceIn(
            MIN_NOTIFY_INTERVAL_MINUTES,
            MAX_NOTIFY_INTERVAL_MINUTES,
        )
        saveStateNow()
    }

    // 通知方式可叠加(勾选组合),每种一个开关;默认只开 notice 气泡

    fun isChannelEnabled(channel: NotifyChannel): Boolean = when (channel) {
        NotifyChannel.BALLOON -> state.notifyViaBalloon
        NotifyChannel.WINDOW -> state.notifyViaWindow
        NotifyChannel.SYSTEM_FLASH -> state.notifyViaSystemFlash
    }

    fun setChannelEnabled(channel: NotifyChannel, enabled: Boolean) {
        when (channel) {
            NotifyChannel.BALLOON -> state.notifyViaBalloon = enabled
            NotifyChannel.WINDOW -> state.notifyViaWindow = enabled
            NotifyChannel.SYSTEM_FLASH -> state.notifyViaSystemFlash = enabled
        }
        saveStateNow()
    }

    /** 该 MR 是否需要提醒:没提醒过,或上次提醒时的 updated_at 与现在不同 (有新提交/评论/状态变化) */
    fun shouldNotifyMr(webUrl: String, updatedAt: String): Boolean = state.notifiedMrs[webUrl] != updatedAt

    /** 记录某 MR 已提醒 (updated_at 一起存,用于判断后续是否有更新) */
    fun markMrNotified(webUrl: String, updatedAt: String) {
        state.notifiedMrs[webUrl] = updatedAt
        trimNotifiedMrs()
        saveStateNow()
    }

    /** 提醒记录上限保护:超出后按 updated_at 淘汰最旧的一批,避免无限增长 */
    private fun trimNotifiedMrs() {
        val overflow = state.notifiedMrs.size - MAX_NOTIFIED_MR_ENTRIES
        if (overflow <= 0) return
        state.notifiedMrs.entries
            .sortedBy { it.value }
            .take(overflow)
            .forEach { state.notifiedMrs.remove(it.key) }
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

    companion object {
        /** 定时提醒默认间隔 (分钟) */
        const val DEFAULT_NOTIFY_INTERVAL_MINUTES: Int = 10
        const val MIN_NOTIFY_INTERVAL_MINUTES: Int = 1
        const val MAX_NOTIFY_INTERVAL_MINUTES: Int = 24 * 60

        /** 已提醒 MR 记录的条数上限 */
        const val MAX_NOTIFIED_MR_ENTRIES: Int = 500

        /** 全局历史分支组合的保留条数 */
        const val MAX_BRANCH_PAIRS: Int = 15

        /** 全局最近审核人的保留条数 */
        const val MAX_RECENT_REVIEWERS: Int = 15

        fun getInstance(): GitLabMrConfigService =
            ApplicationManager.getApplication().getService(GitLabMrConfigService::class.java)
    }
}
