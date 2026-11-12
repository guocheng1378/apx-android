# 2026-11-12 体积优化轮：release 开 R8（isMinifyEnabled=true）。
# 原则：只 keep 有「运行期按名字/符号找代码」风险的点，其余全部交给 R8 裁剪；
# Manifest 声明的四大组件 AGP 会自动生成 keep 规则，不需要在这里重复登记。

# ---- JNI 静态绑定（apx_jni.cpp / apxtv 按函数符号 Java_类_方法 查找）----
# 本机代码链接走 Java_* 符号约定，R8 若给类改名/内联会直接断链（UnsatisfiedLinkError）。
# 用通用判据：凡声明了 native 方法的类整体保留（当前覆盖 ApxNative / UsbBulkChannel /
# AlsaPcm / FfsChannel / HidFeature / UinputGamepad / UinputMouse，新增本机类自动带上）。
-keepclasseswithmembers class * {
    native <methods>;
}

# ---- 崩溃栈可读 ----
# mapping.txt 随 CI 归档，但真机现场拿到的是运行栈；保留行号让栈直接对到源码行。
-keepattributes SourceFile,LineNumberTable
