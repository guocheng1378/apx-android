# android/app/src/main/cpp/ —— JNI 桥（L1 / core-proto）
#
# 产出 libapx.so（target 名必须为 apx，对应 Kotlin 侧 System.loadLibrary("apx")）。
# 复用 /shared 协议库，Kotlin 侧不手写任何字节序或 HID 描述符魔数。
cmake_minimum_required(VERSION 3.22.0)

# ⚠️ 这里**必须**同时 enable C —— 本工程含一个 C 源文件 apx_uinput.c。
# 只写 LANGUAGES CXX 时 CMake 无法把 .c 映射到任何已启用语言，会把它当
# "未知语言源文件"**静默跳过**：编译链接全绿、CI 全绿，但 .so 里根本没有那批
# JNI 符号，运行期才炸成
#   "No implementation found for boolean
#    com.allperiph.shared.input.UinputGamepad.openGamepad() ... is the library loaded?"
# 虚拟手柄 / 摇杆由此静默不可用（真机 issue#43 日志复现，产物符号表实测为空）。
project(apx_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# shared/ 位于仓库根目录（本文件在 android/app/src/main/cpp 下，往上 5 级）
get_filename_component(APX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(APX_SHARED_DIR "${APX_ROOT}/shared")
if(NOT EXISTS "${APX_SHARED_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "未找到 ${APX_SHARED_DIR}/CMakeLists.txt：shared/ 未就位")
endif()

# 作为子目录引入时不要构建自测程序
set(APX_BUILD_SELFTEST OFF CACHE BOOL "构建 apx_selftest" FORCE)
add_subdirectory("${APX_SHARED_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/apx_shared")

add_library(apx SHARED
  apx_jni.cpp
  apx_uinput.c
)
target_include_directories(apx PRIVATE "${APX_SHARED_DIR}/include")
target_link_libraries(apx PRIVATE apx_shared)

# 虚拟手柄（uinput）的 JNI 符号名里要带类名：Java_<APX_JNI_CLASS>_<method>。
# 各模块 Kotlin 包名不同，所以用编译期宏传进来，避免为每个模块复制两份符号名。
# fix：v1.8 起 UinputGamepad 从 com.allperiph.controlled 提取到了共享模块
# com.allperiph.shared.input —— 符号名必须跟着类走，否则 JVM 按新包名解析时报
# "No implementation for ... openGamepad() (tried Java_...) is the library loaded?"，
# 虚拟手柄（摇杆）静默不可用（真机被控日志 UinputGamepad W 已复现）。
target_compile_definitions(apx PRIVATE APX_JNI_CLASS=com_allperiph_shared_input_UinputGamepad)

# 虚拟鼠标（uinput）走同一份 apx_uinput.c，但 JNI 符号挂在 UinputMouse 类下；
# 类名宏由这里传入，避免与手柄的符号冲突。
target_compile_definitions(apx PRIVATE APX_JNI_CLASS_MOUSE=com_allperiph_shared_input_UinputMouse)

find_library(APX_LOG_LIB log)
if(APX_LOG_LIB)
  target_link_libraries(apx PRIVATE ${APX_LOG_LIB})
  target_compile_definitions(apx PRIVATE APX_HAVE_ANDROID_LOG=1)
endif()

target_compile_options(apx PRIVATE -Wall -Wextra)
target_link_options(apx PRIVATE -Wl,-z,max-page-size=16384)
