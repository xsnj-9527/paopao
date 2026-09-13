pluginManagement {
    repositories {
        // 阿里云镜像优先（国内网络环境更稳定），google/mavenCentral 兜底
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // TinyPinyin（拼音首字母排序）
        maven("https://jitpack.io")
    }
}

rootProject.name = "DeepSeekBuddy"
include(":app")

// Agent 内核抽成了独立的纯 JVM 工程：不依赖 Android，可以脱离模拟器单测与跑评测。
// 用 composite build 而不是 include(":agent-core")，是为了让 agent-core 保持
// 自己的 settings.gradle.kts —— 这样它能被单独构建、单独测试、单独发布，
// 同时 :app 又能像依赖普通构件一样依赖它。
includeBuild("agent-core")
