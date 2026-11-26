plugins {
    // AGP 9 起 built-in Kotlin：org.jetbrains.kotlin.android 插件已移除
    id("com.android.library")
}

android {
    namespace = "com.allperiph.shared"
    // 取两端最小值：TV 需要兼容 API 23，shared 模块不能比 TV 更高
    compileSdk = 35

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 built-in Kotlin：kotlinOptions {} 已移除，jvmTarget 跟随 compileOptions

    buildFeatures {
        buildConfig = false
    }

    lint {
        checkReleaseBuilds = false
    }
}

// 零第三方依赖原则的例外（2026-11-26，TV 配对批）：zxing core 是纯 Java 的 QR
// 编解码库（不申请任何权限、无传递依赖），配对二维码的生成（TV 配对页 / 手机
// 配对码弹窗）与解码（手机扫码连接）都靠它。用 api 让 :app / :tv 直接可用。
dependencies {
    api("com.google.zxing:core:3.5.3")
}
