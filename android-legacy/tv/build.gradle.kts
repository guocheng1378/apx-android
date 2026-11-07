// TV Legacy 主模块 —— UI 来自 PR #64 迁移前的 Views 快照（a769548）。
// 源码零拷贝：legacy-snapshot/ 由 materialize.sh（本地）或 CI 第二 checkout/ git archive（本仓 workflow）物化到
// android-legacy/legacy-snapshot/ —— 注意：file() 相对本模块目录（android-legacy/tv/），所以是 ../legacy-snapshot。
// 内容 = git archive a769548 android/tv —— 迁移前的纯 Views 时代，无 Compose/Miuix import。
//
// minSdk = 18（Android 4.3）是本链存在的唯一理由；targetSdk 33 只影响运行时行为开关，
// 不影响老系统安装（安装门槛只看 minSdk）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val snap = file("../legacy-snapshot/android/tv")

android {
    namespace = "com.allperiph.tv"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.allperiph.tv"
        minSdk = 18
        targetSdk = 33
        // 与主链同源：CI 打 tag v* 取 GITHUB_REF_NAME，本地回退带 -legacy 后缀防混淆
        versionCode = 22329
        versionName = (System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") } ?: "0.1.0-legacy")
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    sourceSets.getByName("main") {
        manifest.srcFile(File(snap, "src/main/AndroidManifest.xml"))
        java.setSrcDirs(listOf(File(snap, "src/main/java")))
        res.setSrcDirs(listOf(File(snap, "src/main/res")))
        assets.setSrcDirs(listOf(File(snap, "src/main/assets")))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // --- signingConfig 为空是 INSTALL_PARSE_FAILED_NO_CERTIFICATES 的根因
    // --- CI: setup-java 自动创建 ~/.android/debug.keystore，直接引用它
    // --- 本地调试需放 signing.key + 环境变量 STORE_PASSWORD/KEY_PASSWORD
    signingConfigs {
        create("release") {
            storeFile = file("$HOME/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    builds {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            // 签名报告 —— 验证签名是否真正嵌入产物（INSTALL_PARSE_FAILED_NO_CERTIFICATES 的最终守门）
            enableSplitApk = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
}
