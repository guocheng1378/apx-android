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

    // --- 签名：解决 INSTALL_PARSE_FAILED_NO_CERTIFICATES（裸 APK 无法安装）
    // --- CI 在构建前用 keytool 生成 $HOME/legacy-release.jks（见 workflow），这里只引用；
    //     注意 Kotlin 字符串不展开 $HOME，必须走 System.getenv。
    // --- TODO(phase 2)：每次构建重新生成密钥 = 后续升级会签名不一致，届时固定到稳定 keystore。
    signingConfigs {
        create("release") {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            storeFile = File(home, "legacy-release.jks")
            storePassword = "android"
            keyAlias = "tv-legacy"
            keyPassword = "android"
        }
    }

    // --- AGP DSL 是 buildTypes（run#7/#8 连挂根因：误写 builds、误加不存在的 enableSplitApk 属性）
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":shared"))
}
