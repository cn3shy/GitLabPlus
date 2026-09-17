package com.gitlabmr.notify

import com.gitlabmr.api.ApiException
import com.gitlabmr.api.GitLabServerApi
import com.gitlabmr.api.TokenInvalidException
import com.gitlabmr.config.GitLabMrConfigService
import com.gitlabmr.model.MrItem
import com.gitlabmr.model.MrScopes
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 一次检查的结果 (设置页"立即检查"展示用)
 *
 * @param host  实际查询的服务器 (未选择服务器时为 null)
 * @param total 服务器上"指给我的、状态为已打开"的 MR 总数
 * @param fresh 本次需要提醒的 (新指派 / 已提醒过又有更新)
 * @param error 失败原因 (未选择服务器 / Token / 网络等),成功时为 null
 */
data class NotifyCheckResult(
    val host: String?,
    val total: Int,
    val fresh: List<MrItem>,
    val error: String?,
)

/**
 * 定时提醒服务 (APP 级,整个 IDE 只跑一个)
 *
 * 每 [GitLabMrConfigService.notifyIntervalMinutes] 分钟检查一次"指给我的 Merge Request":
 * 查询上次"查看 MR"用的那台服务器 (scope=assigned_to_me, state=opened),
 * 与本地已提醒记录比对,新指派或已提醒过又有更新的弹通知。
 *
 * 默认关闭 ([GitLabMrConfigService.isNotifyEnabled]);开关与间隔在
 * Settings → Other Settings → GitLabPlus → 定时提醒 中配置。
 */
@Service(Service.Level.APP)
class MrNotifyService : Disposable {

    private var future: ScheduledFuture<*>? = null

    /**
     * 启动轮询 (幂等)。首次检查延迟 [FIRST_CHECK_DELAY_SECONDS] 秒,避开 IDE 启动高峰;
     * 间隔在调度时确定,改间隔后需要 [restart] 才会生效。
     */
    fun start() {
        if (future?.isCancelled == false) return
        val intervalMinutes = GitLabMrConfigService.getInstance().notifyIntervalMinutes()
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { runCheck() },
            FIRST_CHECK_DELAY_SECONDS,
            intervalMinutes.toLong() * 60,
            TimeUnit.SECONDS,
        )
        LOG.info("定时提醒已调度:每 $intervalMinutes 分钟检查一次\"指给我的 MR\"(开关:${GitLabMrConfigService.getInstance().isNotifyEnabled()})")
    }

    /** 设置页改间隔 / 开关后调用:按新配置重新调度 */
    fun restart() {
        stop()
        start()
    }

    fun stop() {
        future?.cancel(false)
        future = null
    }

    override fun dispose() {
        stop()
    }

    /** 定时轮询入口:开关关闭时直接跳过 (不改动调度,开关随时可切换) */
    private fun runCheck() {
        try {
            if (!GitLabMrConfigService.getInstance().isNotifyEnabled()) return
            checkOnce(notify = true)
        } catch (t: Throwable) {
            LOG.warn("定时检查\"指给我的 MR\"失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 检查一次 (查询 + 必要时弹通知 + 记录已提醒)
     *
     * 任何网络 / Token 问题都收敛成 [NotifyCheckResult.error],不向外抛异常,
     * 方便设置页"立即检查"直接展示结果。
     *
     * @param notify 是否真的弹通知 (设置页手动检查时也弹,保证"所见即所得")
     */
    fun checkOnce(notify: Boolean = true): NotifyCheckResult {
        val config = GitLabMrConfigService.getInstance()
        val host = config.loadLastViewHost().trim()
        if (host.isEmpty()) {
            return NotifyCheckResult(null, 0, emptyList(), "尚未选择服务器:请先在 GitLabPlus 工具窗口查询一次")
        }
        val token = config.loadToken(host)
        if (token.isNullOrBlank()) {
            return NotifyCheckResult(host, 0, emptyList(), "尚未配置 \"${host}\" 的 Token")
        }

        return try {
            val api = GitLabServerApi(host, token)
            val mrs = api.fetchMergeRequests(MrScopes.ASSIGNED_TO_ME, "opened")
            val fresh = mrs.filter { config.shouldNotifyMr(it.webUrl, it.updatedAt) }
            if (fresh.isNotEmpty()) {
                if (notify) MrNotifications.notifyAssignedMrs(host, fresh)
                for (mr in fresh) config.markMrNotified(mr.webUrl, mr.updatedAt)
            }
            NotifyCheckResult(host, mrs.size, fresh, null)
        } catch (e: TokenInvalidException) {
            NotifyCheckResult(host, 0, emptyList(), "Token 无效或已过期,请在 Access Token 页更新")
        } catch (e: ApiException) {
            NotifyCheckResult(host, 0, emptyList(), e.message ?: "查询失败")
        } catch (e: Exception) {
            LOG.warn("检查\"指给我的 MR\"失败", e)
            NotifyCheckResult(host, 0, emptyList(), "查询失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /** 启动后首次检查延迟 (秒) */
        private const val FIRST_CHECK_DELAY_SECONDS = 30L

        private val LOG = logger<MrNotifyService>()

        fun getInstance(): MrNotifyService =
            ApplicationManager.getApplication().getService(MrNotifyService::class.java)
    }
}
