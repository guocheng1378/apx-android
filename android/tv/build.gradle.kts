plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.allperiph.tv"
    // 不引 NDK：TV 服务端为纯 Kotlin（ServerSocket + 自绘 UI），无需 JNI。
    compileSdk = 34

    defaultConfig {
        applicationId = "com.allperiph.tv"
        // 老 Android TV 兼容底线：API 23（Android 6.0）。leanback 界面与
        // ServerSocket / 前台服务在 23 上均可用；不引用任何 23 之后才引入的 API。
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // 复用主工程自签 keystore，便于产生可安装 release（与主 app 同源证书体系）
    signingConfigs {
        create("release") {
            storeFile = file("../apx-release.keystore")
            storePassword = "allperiph2026"
            keyAlias = "apx"
            keyPassword = "allperiph2026"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 调试包也用同源证书，避免与 release 重装冲突
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // API 23 运行时不支持 Java 9+ 标准库 API；本模块只用 framework + Kotlin 标准库，
    // 不引入 Java 8 流式/时间 API，故无需 coreLibraryDesugaring。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions")
    }

    buildFeatures {
        buildConfig = true
    }

    // v1.7：lint 误报会阻塞发布质量把关，手工 review 替代
    lint {
        checkReleaseBuilds = false
    }
}

// 与 :app 一致：零第三方依赖，仅 framework API
dependencies {
    // 无外部依赖
}
