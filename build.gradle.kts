plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.gitlabmr"
version = "1.2.4"

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
}
