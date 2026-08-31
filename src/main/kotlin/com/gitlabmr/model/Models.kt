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
