# 2026-11-12 体积优化轮：TV 端 release 同步开 R8（与 app/proguard-rules.pro 同口径）。
# TV 依赖 shared 模块的 UinputGamepad / UinputMouse 带 native 方法，同样用通用判据兜住；
# Manifest 组件由 AGP 自动 keep。
-keepclasseswithmembers class * {
    native <methods>;
}
-keepattributes SourceFile,LineNumberTable
