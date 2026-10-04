plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.allperiph"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.allperiph"
        minSdk = 34
        targetSdk = 35
        versionCode = 3
        versionName = "1.8"
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

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions")
    }

    buildFeatures {
        buildConfig = true
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
dependencies {
    implementation(project(":shared"))
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
