pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// foojay-resolver-convention 负责自动下载构建所需 JDK;本机已装 JDK 17,注释掉避免依赖插件源。
// 如需恢复,取消注释并确保能访问 Gradle Plugin Portal。
// plugins {
//     id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
// }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://api.xposed.info/")
    }
}

rootProject.name = "xiaoai-plug"
include(":app")
