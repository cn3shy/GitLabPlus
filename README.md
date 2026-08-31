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
- 基于 Personal Access Token 认证（Token 加密保存在 IDE 密钥库中）
- 自动获取远程分支列表和项目成员列表
- 基于源/目标分支差异 commit 自动生成 MR 标题（自动过滤 merge / jenkins / update 开头的提交）
- 分支规则校验：主干分支（develop / release / master）只能作为目标分支
- 记住上次使用的分支和审核人，下次自动填充
- 下拉选择审核人（自动匹配项目成员）

## 快捷键

`Ctrl+Shift+M` — 打开创建 MR 对话框

也可以通过 **VCS → GitLab MR → 创建 Merge Request** 或 **Tools → GitLab MR → 创建 Merge Request** 触发。

## 构建

```bash
# 需要 JDK 17+
./gradlew buildPlugin
```

构建产物在 `build/distributions/` 目录下，可直接在 IDEA 中安装。

## 安装

### 方式一：自定义插件仓库（推荐，支持自动更新）

1. IDEA → Settings → Plugins → ⚙ → **Manage Plugin Repositories** → 添加：
   `https://raw.githubusercontent.com/cn3shy/GitLabPlus/main/updatePlugins.xml`
2. Plugins → **Marketplace** 页搜索 `GitLabPlus` 安装
3. 之后每次发版，IDE 会自动检测并提示更新

### 方式二：手动安装

1. 从 [Releases](https://github.com/cn3shy/GitLabPlus/releases) 下载 zip
2. IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk → 选择 zip 文件
3. 重启 IDEA

## CI / 自动发布

GitHub Actions（`.github/workflows/build.yml`）：

- **推送到 `main` 或手动触发**：自动构建，插件 zip 上传到 Actions Artifacts（保留 30 天）
- **打 `v*` tag**（如 `v1.2.4`）：除构建外，还会——
  - 自动创建 GitHub Release 并附上插件 zip
  - 重新生成 `updatePlugins.xml` 并提交回仓库（自定义插件仓库索引，指向最新 Release 附件）

> 版本号统一维护在 `gradle.properties` 的 `pluginVersion`，发版时改它并打对应 tag（CI 会用 tag 号强制覆盖构建版本）。

## 配置

首次使用时会弹出对话框输入 GitLab Personal Access Token（需在 GitLab → 用户设置 → Access Tokens 中创建，勾选 `api` 权限）。Token 保存在 IDE 的加密密钥库中。

## 技术栈

- Kotlin + Gradle (Kotlin DSL)
- IntelliJ Platform SDK 2023.1+
- Gson (JSON 解析)
- Java HttpClient (网络请求)

> AI生成