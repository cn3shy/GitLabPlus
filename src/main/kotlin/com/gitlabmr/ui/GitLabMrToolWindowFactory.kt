package com.gitlabmr.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * "查看 Merge Request" 侧边栏工具窗口工厂
 *
 * 面板与 ToolWindow 同生命周期，只在首次显示时创建；之后通过
 * "查看 Merge Request" 入口反复调出均复用同一面板 (保留上次查询结果)。
 */
class GitLabMrToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GitLabMrToolWindowPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
