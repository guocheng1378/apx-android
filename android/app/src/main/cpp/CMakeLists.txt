# android/app/src/main/cpp/ —— JNI 桥（L1 / core-proto）
#
# 产出 libapx.so（target 名必须为 apx，对应 Kotlin 侧 System.loadLibrary("apx")）。
# 复用 <root>/shared 协议库，Kotlin 侧不手写任何字节序或 HID 描述符魔数。
cmake_minimum_required(VERSION 3.22.0)
project(apx_jni LANGUAGES CXX)

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
)
target_include_directories(apx PRIVATE "${APX_SHARED_DIR}/include")
target_link_libraries(apx PRIVATE apx_shared)

find_library(APX_LOG_LIB log)
if(APX_LOG_LIB)
    target_link_libraries(apx PRIVATE ${APX_LOG_LIB})
    target_compile_definitions(apx PRIVATE APX_HAVE_ANDROID_LOG=1)
endif()

target_compile_options(apx PRIVATE -Wall -Wextra)
