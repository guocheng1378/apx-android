# android/tv/src/main/cpp/ —— 被控端原生桥（目前只有虚拟手柄 uinput）
#
# 产出 libapxtv.so（target 名必须为 apxtv，对应 Kotlin 侧 System.loadLibrary("apxtv")）。
# 只依赖系统头（linux/uinput.h）与 liblog，不引用 shared/，因此本模块可独立构建。
cmake_minimum_required(VERSION 3.22.0)
project(apx_tv_uinput LANGUAGES C)

add_library(apxtv SHARED apx_uinput.c)

# JNI 符号名里的类名：Java_<APX_JNI_CLASS>_<method>
target_compile_definitions(apxtv PRIVATE APX_JNI_CLASS=com_allperiph_tv_core_UinputGamepad)

find_library(APX_LOG_LIB log)
if(APX_LOG_LIB)
  target_link_libraries(apxtv PRIVATE ${APX_LOG_LIB})
endif()

target_compile_options(apxtv PRIVATE -Wall -Wextra)
# 16KB 页对齐（Android 15+ 要求，链接期对齐即可）
target_link_options(apxtv PRIVATE -Wl,-z,max-page-size=16384)
