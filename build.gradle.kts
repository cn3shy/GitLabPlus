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
        // 2025.3 起 IDEA 统一发行 (旗舰版/社区版合并),统一版用 intellijIdea() 坐标
        intellijIdea("2025.3")
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
            sinceBuild = "253"
            // 不设上限 —— 允许在 2025.3 之后的新版 IDE 上安装运行
            untilBuild = provider { null }
        }
    }
}
