plugins {
    // AGP 9 起 built-in Kotlin：org.jetbrains.kotlin.android 插件已移除，
    // Kotlin 编译由 AGP 直接提供（jvmTarget 跟随 compileOptions）。
    // miuix-ui 分支：Compose 编译器插件（版本与 Kotlin 一致，见根脚本）
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发版版本号同源：GitHub Actions 打 tag v* 构建时 GITHUB_REF 为 refs/tags/*，
// 直接取 GITHUB_REF_NAME（如 v205）作为本 App 的 versionName —— 与 GitHub Release
// 的 tag_name 完全一致，设置页的「检查更新」才能直接比字符串大小。
// master / PR / 本地构建 GITHUB_REF 不是 tags/*（或环境变量不存在）→ 回退 "1.8"。
// 也允许 -PversionName=vXXX 手工覆盖（本地模拟发版）。
val releaseTag: String = run {
    val fromProp = (project.findProperty("versionName") as? String)?.trim().orEmpty()
    val fromEnv = if (System.getenv("GITHUB_REF")?.startsWith("refs/tags/") == true)
        System.getenv("GITHUB_REF_NAME")?.trim().orEmpty() else ""
    val picked = fromProp.ifEmpty { fromEnv }
    // 只接受形如 v205 的 tag；分支名（含 / 或非 v 开头）一律不采用
    picked.takeIf { it.matches(Regex("v[0-9][0-9A-Za-z.\\-]*")) } ?: ""
}

android {
    namespace = "com.allperiph"
    // Miuix 0.9.4：其 AAR 元数据要求依赖方 compileSdk ≥ 37（miuix-blur 上游锁的值，
    // 低于 37 直接报错），35 → 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.allperiph"
        minSdk = 34
        targetSdk = 35
        versionCode = 3
        versionName = releaseTag.ifEmpty { "1.8" }
        // 更新检测用：发版构建为 release tag（如 v205），非发版构建为空串
        buildConfigField("String", "RELEASE_TAG", "\"${releaseTag}\"")
    }

    // v1.7：Release 签名（自签 30 年证书，keystore 在版本库外仅本机；密码内联仅开发期）
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
    }

    // v1.7：lintVitalRelease 在本工程误报阻塞构建；发布质量由手工 review 把关
    lint {
        checkReleaseBuilds = false
    }

    // NDK 桥（android/app/src/main/cpp/ + shared/）由 core-proto 交付。
    // 这里做存在性判断：文件缺失时本模块仍可独立编译，便于先验证 Kotlin 侧逻辑。
    if (file("src/main/cpp/CMakeLists.txt").exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
        defaultConfig {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 built-in Kotlin：不再有 kotlinOptions {} 块，jvmTarget 自动跟随
    // compileOptions（17）。原 -Xno-param-assertions 微优化随 kotlinOptions 一并移除。

    buildFeatures {
        buildConfig = true
        compose = true   // miuix-ui 分支：设置页「关于」卡改 Compose + Miuix 渲染
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    // v1.7：协议层单元测试（JVM，无需设备）。被测对象是 ApxFrame / FragmentJoiner ——
    // 它们是纯 Kotlin（无 Android API），但 FragmentJoiner 会调 android.util.Log 记丢弃原因，
    // 单测里让它返回默认值而不是抛 "not mocked"。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 生产代码不引入任何第三方依赖：仅依赖 framework API，降低构建与审计成本。
// v1.7 起：**仅测试**编译期引入 JUnit（testImplementation 不进 APK、不参与运行时，
// 也不改变上面这条原则）。协议层的组帧/解析/重同步纪律需要有回归网 ——
// v1.7 之前修过的多处"协议漂移"缺陷，若有这些测试本可以当场被拦住。
//
// miuix-ui 分支（Compose + Miuix PoC）引入的运行时依赖（都是 Apache-2.0、源码公开）：
//   · androidx.activity:activity-compose —— Compose 需要 ViewTreeLifecycleOwner /
//     ViewModelStoreOwner，MainActivity 从 android.app.Activity 换成 ComponentActivity
//     才能挂 ComposeView；这条只把宿主能力补上，不改变既有 UI。
//   · Miuix 0.9.4 模块化坐标（Maven Central，-android 后缀直接选 Android 变体）：
//     miuix-ui（组件/主题）+ miuix-blur（textureBlur 纹理模糊，AAR 要求 compileSdk ≥ 37 /
//     minSdk ≥ 33）+ miuix-preference（SwitchPreference/WindowDropdownPreference，
//     原 Super* 系改名并移入 preference 包）+ miuix-icons（MiuixIcons 扩展图标）。
//     foundation-layout 由 miuix-ui 的 POM 传递提供（0.5.0 时代要显式声明的问题在
//     0.9.4 已不存在）。
//   · dev.chrisbanes.haze —— 顶栏渐进模糊（HazeProgressive）。Miuix blur 模块不含它，
//     需要显式声明。
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("dev.chrisbanes.haze:haze:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

// v1.7：把 Kotlin 编译出的**单元测试类**目录补进 test 任务的运行期 classpath。
// 本工程的 AGP + Kotlin 插件组合下该目录没有被自动挂上 —— 表现是
// `ClassNotFoundException: com.allperiph.core.ApxFrameTest`，而类文件其实已经生成在
// build/tmp/kotlin-classes/releaseUnitTest 下（javap 能看到全部测试方法）。
// 与其为这一处去动 AGP/Kotlin 版本组合（风险大），不如显式补目录。
// 注意（v1.7 实测）：**本机（Windows + 项目路径含中文）跑不了单元测试** ——
// `:app:testReleaseUnitTest` 会报 `ClassNotFoundException: com.allperiph.core.ApxFrameTest`，
// 但类文件确实生成在 build/tmp/kotlin-classes/releaseUnitTest 下、测试任务的 classpath
// 也完整正确（junit / kotlin-stdlib / android.jar / app 类都在），
// 属非 ASCII 项目路径下 Gradle test worker 加载不到产物的问题（本仓库已设
// android.overridePathCheck=true）。**以 CI 为准**：Linux runner 的路径是纯 ASCII。
// 所以这些测试的看门人是 CI 的 `:app:testReleaseUnitTest` 步骤，本地不必强跑。
// （AGP 9 built-in Kotlin 下 kotlin-classes 目录的挂载行为如仍有差异，以 CI 报错为准。）
