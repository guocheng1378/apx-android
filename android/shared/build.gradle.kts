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

// 零第三方依赖：仅 framework API，与 :app / :tv 保持一致原则
dependencies {
}
