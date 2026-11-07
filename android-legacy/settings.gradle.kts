// APX TV Legacy —— Android 4.3 (API 18) 兼容构建链。
// 为什么独立一条链：主仓 AGP 9 的 minSdk 下限卡死（androidx 生态实际 >= 21，Miuix >= 24），
// 而 legacy 要在 4.3 的 Dalvik 上跑 —— AGP/Gradle/JDK/Kotlin/compileSdk 全部各走各的，
// 不与主仓 `android/` 共用任何构建配置。源码零拷贝，见 materialize.sh。
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "7.4.2"
        id("com.android.library") version "7.4.2"
        id("org.jetbrains.kotlin.android") version "1.9.24"
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}

rootProject.name = "apx-tv-legacy"
include(":tv", ":shared")
