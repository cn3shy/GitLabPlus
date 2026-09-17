package com.gitlabmr.config

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 设置根页:Settings → Other Settings → GitLabPlus
 *
 * 自身不承载配置项,只作为分组节点;子页由 plugin.xml 通过 parentId 挂到它下面:
 * - Access Token:各 GitLab 服务器的 Personal Access Token 管理
 * - 项目记忆:各项目上次使用的源/目标分支与审核人
 * - 定时提醒:定时检查"指给我的 MR"并弹通知
 */
class GitLabPlusSettingsConfigurable : Configurable {

    override fun getDisplayName(): String = "GitLabPlus"

    override fun createComponent(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(
            JBLabel(
                "<html>GitLabPlus 设置:请在左侧选择子页 ——<br><br>" +
                    "· <b>Access Token</b>:管理各 GitLab 服务器的 Personal Access Token<br>" +
                    "· <b>项目记忆</b>:各项目上次使用的源 / 目标分支与审核人<br>" +
                    "· <b>定时提醒</b>:定时检查" + "指给我的 Merge Request" + "并弹通知</html>"
            ),
            BorderLayout.NORTH,
        )
    }

    // 根页没有自己的配置项;所有子页的操作都是即时生效
    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}
}
