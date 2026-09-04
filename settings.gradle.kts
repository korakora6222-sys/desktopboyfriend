pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 火山引擎语音 SDK 仓库
        maven { url = uri("https://artifact.bytedance.com/repository/Volcengine/") }
    }
}

rootProject.name = "萌宠桌面"
include(":app")
