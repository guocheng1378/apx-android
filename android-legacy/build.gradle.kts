// 根级无配置：插件版本由 settings.pluginManagement 统一解析（AGP 7.4.2 + Kotlin 1.9.24）。
// 版本选择依据：AGP 7.4.x 是最后一条对 minSdk 18 无压力的稳定线（Gradle 7.5 / JDK 11 官方配对），
// compileSdk 33 是它的 tested 上限，旧代码时代用到的 API 都 <= 33。
