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
