# shared/ —— C++17、零第三方依赖的公共协议库（Android NDK 与 PC MSVC 共用）
#
# 单独构建（PC 端验证）：
#   cmake -S shared -B shared/build && cmake --build shared/build
#   ./shared/build/apx_selftest        # Windows: shared\build\Debug\apx_selftest.exe
#
# 被 android/app/src/main/cpp/CMakeLists.txt 或 pc/host 以 add_subdirectory 引入时，
# 只产出静态库目标 apx_shared，默认不构建自测程序。
cmake_minimum_required(VERSION 3.16)
project(apx_shared LANGUAGES CXX)

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    set(APX_IS_TOP_LEVEL ON)
else()
    set(APX_IS_TOP_LEVEL OFF)
endif()

option(APX_BUILD_SELFTEST "构建自测程序 apx_selftest" ${APX_IS_TOP_LEVEL})

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(apx_shared STATIC
    src/sensors_id.cpp
    src/frame.cpp
    src/units.cpp
    src/hid_layout.cpp
    src/hid_descriptor.cpp
    src/clock.cpp
)
target_include_directories(apx_shared PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_compile_features(apx_shared PUBLIC cxx_std_17)

# MSVC：按 UTF-8 解释源码，关闭非标准扩展带来的告警噪音
if(MSVC)
    # 必须是**目录级** add_compile_options，而不是只给 apx_shared 加 target_compile_options：
    # 本工程源码含中文注释与字符串，MSVC 默认按本地代码页（简中为 936/GBK）解释，
    # 会先报 C4819，再连带产生「常量中有换行符 C2001」这类完全看不出根因的语法错误。
    # 之前只给库目标加了 /utf-8，自测程序没继承到，一编译就炸 —— 记录在此避免重蹈。
    add_compile_options(/utf-8 /W3)
else()
    add_compile_options(-Wall -Wextra)
endif()

# Linux 老 glibc 需要 -lrt；Android/Windows 不需要
if(UNIX AND NOT ANDROID AND NOT APPLE)
    find_library(APX_RT_LIBRARY rt)
    if(APX_RT_LIBRARY)
        target_link_libraries(apx_shared PUBLIC ${APX_RT_LIBRARY})
    endif()
endif()

if(APX_BUILD_SELFTEST)
    enable_testing()
    add_executable(apx_selftest tests/apx_selftest.cpp)
    target_link_libraries(apx_selftest PRIVATE apx_shared)
    add_test(NAME apx_selftest COMMAND apx_selftest)
endif()

message(STATUS "[apx] shared 协议库：C++17，自测=${APX_BUILD_SELFTEST}")
