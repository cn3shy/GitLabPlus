plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.gitlabmr"
// 单一来源:gradle.properties 的 pluginVersion;CI 打 tag 时用 -PpluginVersion 覆盖
version = property("pluginVersion") as String

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Gson 用于 JSON 解析 (IntelliJ Platform 自带，但显式声明避免歧义)
    implementation("com.google.code.gson:gson:2.10.1")

    intellijPlatform {
        intellijIdeaCommunity("2023.1")
        // 编译时需要 git4idea API (GitRepository / GitRepositoryManager)
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "231"
            // 不设上限 —— 允许在 2023.1 之后的新版 IDE 上安装运行
            untilBuild = provider { null }
        }
    }

    publishing {
        // JetBrains Marketplace 发布 token,仅 CI 环境变量提供(本地不发布)
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
