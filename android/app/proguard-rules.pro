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

# ---- 依赖库里的桌面残留（CI 首跑 R8 实测 2026-11-12）----
# 传递依赖里带了 com.sun.jna，其 Native.getWindowHandle0 引用 java.awt.Component；
# 安卓上两者都不会被调用到，但 R8 缺类校验直接 FAIL。登记 dontwarn 让 R8 安心裁掉。
-dontwarn java.awt.**

# ---- JNA 结构体映射（v86 修复：语音引擎加载崩溃）----
# 现象：真机加载 Vosk Model() 时报 Can't obtain peer field ID for class com.sun.jna.Pointer。
# 原因：JNA 的 Structure 靠反射按字段名对齐 native 内存布局，R8 裁剪/改名后 JNI 反查 peer 字段落空；
#       因此 -dontwarn 不够，必须整体保留 JNA 本体。
# 措辞依据：用户截图原话「识别引擎初始化失败：Can't obtain peer field ID for class com.sun.jna.Pointer」。
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ---- Vosk 引擎本体（v87 修复：JNA 修好后的下一层）----
# 现象：v86 真机语音报「识别引擎初始化失败: org.vosk.LibVosk」（用户截图 2026-11-13 02:54）——
#       JNA 通了、轮到引擎类本身：LibVosk 是 JNA 接口映射，native 符号按**方法名**查找，
#       R8 改名/裁剪方法后符号落空（NoClassDefFoundError / UnsatisfiedLinkError 均可能，
#       用户侧表现为原因行只剩类名）。与 JNA 同理整体保留，符号名原样在。
-keep class org.vosk.** { *; }
