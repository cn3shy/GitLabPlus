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

插件主页：[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34316-gitlabplus) https://plugins.jetbrains.com/plugin/34316-gitlabplus  

1. 打开插件主页点 **Install to IDE**，或 IDEA → Settings → Plugins → **Marketplace** → 搜索 `GitLabPlus` → 安装
2. 之后每次发版，IDE 会自动检测并提示更新

### 方式二：手动安装

1. 从 [Releases](https://github.com/cn3shy/GitLabPlus/releases) 下载 zip
2. IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk → 选择 zip 文件
3. 重启 IDEA

## 配置

### Access Token
Token 统一在 **Settings → Other Settings(其他设置)→ GitLabPlus → Access Token** 中配置(需在 GitLab → 用户设置 → Access Tokens 中创建,勾选 `api` 权限)。

### 项目记忆 
各项目上次使用的源 / 目标分支与审核人,可编辑或清除
### 定时提醒
定时查询"指给我的 Merge Request",有新指派或有更新时弹通知(默认关闭,间隔可配置;查询的是"查看 MR"窗口上次使用的那台服务器)

## 技术栈

- Kotlin + Gradle (Kotlin DSL)
- IntelliJ Platform SDK 2025.3+
- Gson (JSON 解析)
- Java HttpClient (网络请求)
