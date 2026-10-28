// 顶层构建脚本：只声明插件版本，具体配置在 app/build.gradle.kts
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    // miuix-ui 分支（Compose + Miuix PoC）：Kotlin 2.0 起 Compose 编译器随 Kotlin 发布
    // （同一个 Gradle 插件，版本必须与 Kotlin 完全一致）。app 模块启用 Compose 后必须挂它。
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
}
