---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'ec7b3632-104e-4a70-9ba8-22bd94cf6b10'
  PropagateID: 'ec7b3632-104e-4a70-9ba8-22bd94cf6b10'
  ReservedCode1: '9f3581b3-e97f-40b0-ad44-812212b4cc75'
  ReservedCode2: '9f3581b3-e97f-40b0-ad44-812212b4cc75'
---

# GitLabPlus (IntelliJ IDEA 插件)

[![Build](https://github.com/cn3shy/GitLabPlus/actions/workflows/build.yml/badge.svg)](https://github.com/cn3shy/GitLabPlus/actions/workflows/build.yml)

在 IntelliJ IDEA 中快速创建 GitLab Merge Request，从 Python CLI 脚本迁移而来。

## 功能

- 自动识别当前项目的 GitLab 远程仓库（支持 HTTPS / SSH URL）
- 基于 Personal Access Token 认证（Token 保存在插件本机配置文件中，升级插件无需重新配置）
- 自动获取远程分支列表和项目成员列表
- 基于源/目标分支差异 commit 自动生成 MR 标题（自动过滤 merge / jenkins / update 开头的提交）
- 分支规则校验：主干分支（develop / release / master）只能作为目标分支
- 记住上次使用的分支和审核人，下次自动填充
- 下拉选择审核人（自动匹配项目成员）
- 查看 Merge Request：查询配置服务器上**我创建的**与**指给我的** MR，在 IDE 侧边栏工具窗口中按目录（Group）分组展示，双击直达 MR 页面
  - 支持范围单选（我创建的 / 指给我的）、状态筛选（已打开 / 已合并 / 已关闭）与远程目录过滤
  - 已合并状态只查询前 100 条（按更新时间倒序）
  - 树上方提供全部展开 / 全部收缩；鼠标悬停 MR 显示浮动详情（作者 / 指派给 / 分支 / 目录 / 链接）
  - 右下角"重启窗口"按钮可重建该窗口（重新读取设置并重新查询），等同关闭后重新打开

## 入口

- **顶部工具栏**：主工具栏右侧的"创建 Merge Request"按钮（保持独立按钮，不进分组）
- **主菜单 / 项目右键菜单**：Git（VCS）菜单与 Project 视图右键 → Git → **创建 Merge Request**（直接位于 Git 菜单下，无子菜单）
- **查看 Merge Request**：IDE 右侧边栏 **GitLabPlus** 工具窗格按钮（插件图标，未激活自动灰化 / 激活显示彩色；也可双击侧边栏按钮打开），面板可停靠 / 拖放，与 Project 视图同款交互；面向设置页配置列表中已保存 Token 的 GitLab 服务器查询（不依赖当前项目），支持多服务器切换。

## 构建

```bash
# 需要 JDK 17+
./gradlew buildPlugin
```

构建产物在 `build/distributions/` 目录下，可直接在 IDEA 中安装。

## 安装

### 方式一：JetBrains Marketplace（推荐，自动更新）

插件主页：https://plugins.jetbrains.com/plugin/34316-gitlabplus

1. 打开插件主页点 **Install to IDE**，或 IDEA → Settings → Plugins → **Marketplace** → 搜索 `GitLabPlus` → 安装
2. 之后每次发版，IDE 会自动检测并提示更新

### 方式二：手动安装

1. 从 [Releases](https://github.com/cn3shy/GitLabPlus/releases) 下载 zip
2. IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk → 选择 zip 文件
3. 重启 IDEA

## CI / 自动发布

GitHub Actions（`.github/workflows/build.yml`），**打 `v*` tag**（如 `v2609.17.1`）时触发，依次：

1. 构建插件 zip
2. `./gradlew publishPlugin` 上传到 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34316-gitlabplus)（IDE 的"自动更新"由市场提供，不再需要自定义插件仓库索引）
3. 创建 GitHub Release 并附上插件 zip（方便手动下载安装）

> - 版本号统一维护在 `gradle.properties` 的 `pluginVersion`,格式 `年月.日.序号`(如 `2609.17.1` = 26 年 9 月 17 日的第 1 个版本,当天再发第 2 个就是 `2609.17.2`),发版时改它并打同号 tag(CI 会用 tag 号强制覆盖构建版本)。
> - 需要在 GitHub 仓库配置 Secret **`PUBLISH_TOKEN`**：到 [JetBrains Marketplace](https://plugins.jetbrains.com) → 头像 → **My Tokens** 生成，填到 Settings → Secrets and variables → Actions。

## 配置

Token 统一在 **Settings → Other Settings(其他设置)→ GitLabPlus → Access Token** 中配置(需在 GitLab → 用户设置 → Access Tokens 中创建,勾选 `api` 权限)。Token 保存在插件本机配置文件(`options/gitlab-mr-plugin.xml`)中,与 IDE 凭证后端无关,升级插件 / 重启 IDE 均不会丢失;历史版本保存在密钥库中的 Token 会自动迁移。

同一节点下还有两个子页:

- **项目记忆**:各项目上次使用的源 / 目标分支与审核人,可编辑或清除
- **定时提醒**:定时查询"指给我的 Merge Request",有新指派或有更新时弹通知(默认关闭,间隔可配置;查询的是"查看 MR"窗口上次使用的那台服务器)

## 技术栈

- Kotlin + Gradle (Kotlin DSL)
- IntelliJ Platform SDK 2025.3+
- Gson (JSON 解析)
- Java HttpClient (网络请求)

> AI生成