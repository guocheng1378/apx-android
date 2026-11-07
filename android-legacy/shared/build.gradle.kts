// :shared 零拷贝引用主仓当前源码 —— 协议常量与 PC 端保持一致（协议关卡两端一致的前提）。
// 只有构建配置在这里：AGP 7.4 library + minSdk 18 + JVM target 1.8（Dalvik 只认 <= 8 的字节码）。
// 零第三方依赖（README「只依赖 framework API」），因此不需要任何外部坐标。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.allperiph.shared"
    compileSdk = 33

    sourceSets.getByName("main") {
        manifest.srcFile("../../android/shared/src/main/AndroidManifest.xml")
        java.setSrcDirs(listOf("../../android/shared/src/main/java"))
    }

    defaultConfig {
        minSdk = 18
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
}
