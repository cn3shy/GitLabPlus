plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    `maven-publish`
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
}

// 发布插件 zip 到 GitHub Packages (Maven 仓库)
// 注意:GitHub Packages 同一版本不允许重复发布,故由 CI 仅在打 v* tag 时执行 publish
publishing {
    publications {
        create<MavenPublication>("plugin") {
            artifactId = rootProject.name
            artifact(tasks.named("buildPlugin")) {
                extension = "zip"
            }
            pom {
                name.set("GitLabPlus")
                description.set("IntelliJ IDEA 插件:快速创建 GitLab Merge Request")
                url.set("https://github.com/cn3shy/GitLabPlus")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/cn3shy/GitLabPlus")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").getOrElse("")
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").getOrElse("")
            }
        }
    }
}
