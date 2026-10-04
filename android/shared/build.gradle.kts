plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions")
    }

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
