plugins {
    // AGP 9 起 built-in Kotlin：org.jetbrains.kotlin.android 插件已移除
    id("com.android.application")
    // 2026-11-01 界面层迁 Compose + Miuix：版本与根脚本、:app 完全一致
    id("org.jetbrains.kotlin.plugin.compose")
}

// 与 :app 同源：CI 打 tag v* 构建时取 GITHUB_REF_NAME（如 v205），
// master / PR / 本地构建回退 "0.1.0"。同样支持 -PversionName=vXXX 覆盖。
val releaseTag: String = run {
    val fromProp = (project.findProperty("versionName") as? String)?.trim().orEmpty()
    val fromEnv = if (System.getenv("GITHUB_REF")?.startsWith("refs/tags/") == true)
        System.getenv("GITHUB_REF_NAME")?.trim().orEmpty() else ""
    val picked = fromProp.ifEmpty { fromEnv }
    picked.takeIf { it.matches(Regex("v[0-9][0-9A-Za-z.\\-]*")) } ?: ""
}

android {
    namespace = "com.allperiph.tv"
    // 37：Miuix 0.9.4 的 AAR 元数据要求依赖方 compileSdk ≥ 37（与 :app 同一门槛）；
    // 此前是 35（只为满足 :shared 的 35），现在由 Miuix 决定
    compileSdk = 37

    defaultConfig {
        applicationId = "com.allperiph.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = releaseTag.ifEmpty { "0.1.0" }
        // Android 14 要求 foregroundService 必须声明类型，否则 startForeground() 会抛异常
        // TV 端用 connectedDevice（与手机端一致），允许在被控时保持前台服务
        manifestPlaceholders["foregroundServiceType"] = "connectedDevice"
    }

    // 复用主工程自签 keystore
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
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 built-in Kotlin：kotlinOptions {} 已移除，jvmTarget 跟随 compileOptions

    buildFeatures {
        buildConfig = true
        compose = true   // 2026-11-01：电视端主屏改 Compose + Miuix 渲染
    }

    if (file("src/main/cpp/CMakeLists.txt").exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":shared"))
    // 电视端只引 miuix-ui + miuix-icons：
    //  · miuix-blur 的 AAR 要求 minSdk ≥ 33，本模块 minSdk 23（老电视），不能引；
    //  · miuix-preference 是设置类组件，电视端没有设置页，不引。
    // 三条坐标与 :app 完全一致，不新增清单外依赖。
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
}
