package com.gitlabmr.notify

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 打开项目时启动定时提醒服务
 *
 * 服务是 APP 级的,每个项目打开都会调一次,内部幂等 (已调度则直接返回)。
 */
class MrNotifyStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        MrNotifyService.getInstance().start()
    }
}
