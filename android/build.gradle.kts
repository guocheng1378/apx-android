// 顶层构建脚本：只声明插件版本，具体配置在 app/build.gradle.kts
// Miuix 0.9.4 升级（miuix-ui 分支）：AGP 8.7.3 → 9.4.1、Kotlin 2.1.21 → 2.4.20。
// AGP 9 起强制 built-in Kotlin：org.jetbrains.kotlin.android 插件已移除（各模块直接用
// com.android.* 插件），Compose 编译器插件保留（版本必须与 built-in Kotlin 一致）。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    // miuix-ui 分支（Compose + Miuix PoC）：Kotlin 2.0 起 Compose 编译器随 Kotlin 发布
    // （同一个 Gradle 插件，版本必须与 Kotlin 完全一致）。app 模块启用 Compose 后必须挂它。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
