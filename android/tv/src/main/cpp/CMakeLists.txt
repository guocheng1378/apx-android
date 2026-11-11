# android/tv/src/main/cpp/ —— 被控端原生桥（目前只有虚拟手柄 uinput）
#
# 产出 libapxtv.so（target 名必须为 apxtv，对应 Kotlin 侧 System.loadLibrary("apxtv")）。
# 只依赖系统头（linux/uinput.h）与 liblog，不引用 shared/，因此本模块可独立构建。
cmake_minimum_required(VERSION 3.22.0)
project(apx_tv_uinput LANGUAGES C)

add_library(apxtv SHARED apx_uinput.c)

# JNI 符号名里的类名：Java_<APX_JNI_CLASS>_<method>
# fix：v1.8 起 UinputGamepad 已提取到共享模块 com.allperiph.shared.input（手机端与
# TV 端共用同一个 Kotlin 类），符号名必须与之一致；旧值 com_allperiph_tv_core_
# UinputGamepad 是提取前的包名，导致 JVM 解析不到符号（No implementation ...），
# TV 端虚拟手柄自 v1.8 起静默不可用。
target_compile_definitions(apxtv PRIVATE APX_JNI_CLASS=com_allperiph_shared_input_UinputGamepad)

# 虚拟鼠标（uinput）走同一份 apx_uinput.c，但 JNI 符号挂在 UinputMouse 类下；
# 类名宏由这里传入，避免与手柄的符号冲突。
target_compile_definitions(apxtv PRIVATE APX_JNI_CLASS_MOUSE=com_allperiph_shared_input_UinputMouse)

find_library(APX_LOG_LIB log)
if(APX_LOG_LIB)
  target_link_libraries(apxtv PRIVATE ${APX_LOG_LIB})
endif()

target_compile_options(apxtv PRIVATE -Wall -Wextra)
# 16KB 页对齐（Android 15+ 要求，链接期对齐即可）
target_link_options(apxtv PRIVATE -Wl,-z,max-page-size=16384)
