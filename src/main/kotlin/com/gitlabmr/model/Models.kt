package com.gitlabmr.model

/**
 * GitLab 远程仓库信息
 *
 * @param scheme  协议 (https / http)
 * @param host    GitLab 主机地址
 * @param port    端口 (0 表示协议默认端口，如 http://host:9000 中的 9000)
 * @param projectPath 项目路径 (group/subgroup/project)
 */
data class GitLabInfo(
    val scheme: String,
    val host: String,
    val port: Int,
    val projectPath: String,
)

/**
 * 获取 project ID 后解析出的实际连接参数
 *
 * @param apiVersion GitLab API 版本 (v4 / v3)
 * @param usedScheme  实际成功访问的协议
 */
data class ProjectInfo(
    val projectId: Int,
    val apiVersion: String,
    val usedScheme: String,
)

/**
 * GitLab 项目成员
 */
data class Member(
    val id: Int,
    val username: String,
    val name: String,
)

/**
 * MR 提交结果
 */
data class MrResult(
    val success: Boolean,
    val webUrl: String?,
    val errorMessage: String?,
)

/**
 * 用于 UI 与 API 之间传递的 MR 创建参数
 */
data class MrCreateParams(
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val description: String,
    val assigneeUsernames: String,
    val reviewerUsernames: String,
)

/**
 * 主干分支规则：只能作为 MR 的目标分支，不能作为源分支
 */
object TrunkBranches {
    val SET = setOf("develop", "release", "master")

    fun isTrunk(branch: String): Boolean = branch in SET
}

// ---------------------------------------------------------------------- //
//  查看 Merge Request
// ---------------------------------------------------------------------- //

/**
 * 当前登录用户 (GET /user)，Token 身份的展示与归属判断依据
 */
data class CurrentUser(
    val id: Int,
    val username: String,
    val name: String,
)

/**
 * 服务器级 MR 列表条目 (全局 /merge_requests 接口返回的精简模型)
 *
 * @param createdByMe  是否命中 "我创建的" (scope=created_by_me)
 * @param assignedToMe 是否命中 "指给我的" (scope=assigned_to_me)
 */
data class MrItem(
    val iid: Int,
    val title: String,
    val state: String,
    val webUrl: String,
    val sourceBranch: String,
    val targetBranch: String,
    val authorName: String,
    val updatedAt: String,
    val createdByMe: Boolean = false,
    val assignedToMe: Boolean = false,
) {
    /**
     * 从 web_url 解析项目路径 (group/subgroup/project)。
     * web_url 形如 https://host/group/sub/project/-/merge_requests/12
     */
    fun projectPath(): String? {
        val idx = webUrl.indexOf("/-/")
        if (idx <= 0) return null
        val before = webUrl.substring(0, idx)
        val schemeIdx = before.indexOf("://")
        val authorityAndPath = if (schemeIdx >= 0) before.substring(schemeIdx + 3) else before
        val firstSlash = authorityAndPath.indexOf('/')
        return if (firstSlash > 0) authorityAndPath.substring(firstSlash + 1) else null
    }

    /** 顶层 Group 名 (项目路径第一段)，分组展示用 */
    fun groupName(): String = projectPath()?.substringBefore('/') ?: "其他"

    /** 更新时间的展示文本，如 "2024-05-01 12:34" */
    fun updatedAtText(): String = updatedAt.take(16).replace('T', ' ')

    /** 命中的查询范围标签 */
    fun scopeTags(): String = buildList {
        if (createdByMe) add("我创建")
        if (assignedToMe) add("指给我")
    }.joinToString(" / ")
}

/**
 * "查看 Merge Request" 查询结果 (Action 与列表对话框之间传递)
 */
data class MrListResult(
    val user: CurrentUser?,
    val items: List<MrItem>,
    val error: String?,
    val tokenProblem: Boolean,
)
