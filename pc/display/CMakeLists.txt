cmake_minimum_required(VERSION 3.20)
project(allperiph_display VERSION 0.1.0 LANGUAGES CXX)

# ============================================================================
# L4 · PC 副屏驱动与推流（docs/ARCHITECTURE.md §5）
#
# 本 CMake 只构建「用户态」部分：抓屏 / 编码 / 组帧 / 传输 / 触控注入 / 编排 / CLI。
# IddCx 虚拟显示器驱动（pc/display/idd）是 UMDF 驱动，必须用 WDK 的 MSBuild 构建，
# 见 pc/display/idd/README.md 与 idd/build.ps1。
#
# 快速开始（Windows / VS2022 / Windows SDK 10.0.22621+）：
#   cmake -S pc/display -B pc/display/build -G "Visual Studio 17 2022" -A x64
#   cmake --build pc/display/build --config Release
#   pc\display\build\Release\apxdisp.exe --self-test
# ============================================================================

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "构建类型" FORCE)
endif()

# ------------------------------------------------------------------ 选项 ----
option(APXDISP_BUILD_APP      "构建 apxdisp 命令行程序"                 ON)
option(APXDISP_USE_LIBUSB     "使用 libusb 作为跨平台 USB 后端（可选）" OFF)
option(APXDISP_HAVE_NVENC_SDK "已放置 NVIDIA Video Codec SDK 头文件"    OFF)
option(APXDISP_HAVE_VPL_SDK   "已放置 Intel oneVPL / MediaSDK 头文件"   OFF)
option(APXDISP_HAVE_AMF_SDK   "已放置 AMD AMF SDK 头文件"              OFF)
option(APXDISP_ENABLE_DDA     "启用 Desktop Duplication 抓屏（Windows）" ON)

if(APXDISP_HAVE_NVENC_SDK)
  add_compile_definitions(APXDISP_HAVE_NVENC_SDK=1)
endif()
if(APXDISP_HAVE_VPL_SDK)
  add_compile_definitions(APXDISP_HAVE_VPL_SDK=1)
endif()
if(APXDISP_HAVE_AMF_SDK)
  add_compile_definitions(APXDISP_HAVE_AMF_SDK=1)
endif()

set(APXDISP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")

# core-proto 的 shared/ 协议库（唯一真源；缺失即 FATAL_ERROR，无 compat 替身）
include("${APXDISP_ROOT}/cmake/ApxShared.cmake")

# -------------------------------------------------------------- 编译选项 ----
if(MSVC)
  add_compile_options(/utf-8 /EHsc /W3 /permissive- /Zc:__cplusplus)
  add_compile_definitions(
    _CRT_SECURE_NO_WARNINGS
    NOMINMAX
    WIN32_LEAN_AND_MEAN
    UNICODE
    _UNICODE
  )
else()
  add_compile_options(-Wall -Wextra -Wno-unused-parameter)
endif()

# ------------------------------------------------------------ 源码组织 ----
set(APXDISP_COMMON_SRC
  "${APXDISP_ROOT}/common/log.cpp"
  "${APXDISP_ROOT}/common/clock.cpp"
  "${APXDISP_ROOT}/common/crc32.cpp"
)

set(APXDISP_CAPTURE_SRC
  "${APXDISP_ROOT}/capture/capture_factory.cpp"
  "${APXDISP_ROOT}/capture/null_capture.cpp"
)

set(APXDISP_ENCODE_SRC
  "${APXDISP_ROOT}/encode/encoder_factory.cpp"
  "${APXDISP_ROOT}/encode/raw_lz4_encoder.cpp"
  "${APXDISP_ROOT}/encode/lz4.cpp"
)

set(APXDISP_TRANSPORT_SRC
  "${APXDISP_ROOT}/transport/frame_writer.cpp"
  "${APXDISP_ROOT}/transport/loopback_transport.cpp"
  "${APXDISP_ROOT}/transport/ctrl_channel.cpp"
  "${APXDISP_ROOT}/transport/link_monitor.cpp"
  "${APXDISP_ROOT}/transport/transport_factory.cpp"
)

set(APXDISP_INJECT_SRC
  "${APXDISP_ROOT}/inject/inject_factory.cpp"
  "${APXDISP_ROOT}/inject/touch_frame.cpp"
)

set(APXDISP_PIPELINE_SRC
  "${APXDISP_ROOT}/pipeline/pipeline.cpp"
)

if(WIN32)
  if(APXDISP_ENABLE_DDA)
    list(APPEND APXDISP_CAPTURE_SRC "${APXDISP_ROOT}/capture/dda_capture_win.cpp")
    add_compile_definitions(APXDISP_ENABLE_DDA=1)
  endif()
  list(APPEND APXDISP_ENCODE_SRC    "${APXDISP_ROOT}/encode/mf_encoder.cpp")
  list(APPEND APXDISP_ENCODE_SRC    "${APXDISP_ROOT}/encode/nvenc_encoder.cpp")
  list(APPEND APXDISP_ENCODE_SRC    "${APXDISP_ROOT}/encode/qsv_encoder.cpp")
  list(APPEND APXDISP_ENCODE_SRC    "${APXDISP_ROOT}/encode/amf_encoder.cpp")
  list(APPEND APXDISP_TRANSPORT_SRC "${APXDISP_ROOT}/transport/usb_transport_win.cpp")
  list(APPEND APXDISP_TRANSPORT_SRC "${APXDISP_ROOT}/transport/tcp_transport_win.cpp")
  list(APPEND APXDISP_INJECT_SRC    "${APXDISP_ROOT}/inject/inject_sendinput_win.cpp")
  list(APPEND APXDISP_INJECT_SRC    "${APXDISP_ROOT}/inject/inject_synthetic_pointer_win.cpp")
else()
  list(APPEND APXDISP_ENCODE_SRC    "${APXDISP_ROOT}/encode/nvenc_encoder.cpp")
endif()

if(APXDISP_USE_LIBUSB)
  find_path(LIBUSB_INCLUDE_DIR NAMES libusb-1.0/libusb.h libusb.h)
  find_library(LIBUSB_LIBRARY NAMES usb-1.0 libusb-1.0)
  if(LIBUSB_INCLUDE_DIR AND LIBUSB_LIBRARY)
    add_library(apxdisp_libusb INTERFACE)
    target_include_directories(apxdisp_libusb INTERFACE "${LIBUSB_INCLUDE_DIR}")
    target_link_libraries(apxdisp_libusb INTERFACE "${LIBUSB_LIBRARY}")
    list(APPEND APXDISP_TRANSPORT_SRC "${APXDISP_ROOT}/transport/usb_transport_libusb.cpp")
    add_compile_definitions(APXDISP_USE_LIBUSB=1)
    message(STATUS "[display] 启用 libusb: ${LIBUSB_LIBRARY}")
  else()
    message(STATUS "[display] 未找到 libusb，禁用该后端")
    set(APXDISP_USE_LIBUSB OFF)
  endif()
endif()

# ---------------------------------------------------------------- 目标 ----
add_library(apxdisp_common STATIC ${APXDISP_COMMON_SRC})
target_include_directories(apxdisp_common PUBLIC "${APXDISP_ROOT}")
target_link_libraries(apxdisp_common PUBLIC apx_shared)
target_compile_features(apxdisp_common PUBLIC cxx_std_20)

add_library(apxdisp_capture STATIC ${APXDISP_CAPTURE_SRC})
target_include_directories(apxdisp_capture PUBLIC "${APXDISP_ROOT}")
target_link_libraries(apxdisp_capture PUBLIC apxdisp_common)

add_library(apxdisp_encode STATIC ${APXDISP_ENCODE_SRC})
target_include_directories(apxdisp_encode PUBLIC "${APXDISP_ROOT}")
target_link_libraries(apxdisp_encode PUBLIC apxdisp_common)

add_library(apxdisp_transport STATIC ${APXDISP_TRANSPORT_SRC})
target_include_directories(apxdisp_transport PUBLIC "${APXDISP_ROOT}")
target_link_libraries(apxdisp_transport PUBLIC apxdisp_common)

add_library(apxdisp_inject STATIC ${APXDISP_INJECT_SRC})
target_include_directories(apxdisp_inject PUBLIC "${APXDISP_ROOT}")
target_link_libraries(apxdisp_inject PUBLIC apxdisp_common)

add_library(apxdisp_pipeline STATIC ${APXDISP_PIPELINE_SRC})
target_include_directories(apxdisp_pipeline PUBLIC "${APXDISP_ROOT}")
# apxdisp_inject 是**必需**依赖：Pipeline 会调 createInjector / parseTouchFrame
# （触控注入与触控帧解析）。此前漏声明，只因为 apxdisp.exe 自己又链了一遍才没暴露；
# 一旦有别的目标单独链接 apxdisp_pipeline（如桌面端面板复用副屏管线），
# 就会在链接期报 createInjector / parseTouchFrame 未解析。
target_link_libraries(apxdisp_pipeline PUBLIC apxdisp_common apxdisp_capture apxdisp_encode apxdisp_transport apxdisp_inject)

if(WIN32)
  target_link_libraries(apxdisp_capture    PUBLIC d3d11 dxgi dxguid)
  target_link_libraries(apxdisp_encode     PUBLIC mfplat mfuuid ole32)
  target_link_libraries(apxdisp_transport  PUBLIC setupapi winusb cfgmgr32 ws2_32 uuid)
  target_link_libraries(apxdisp_inject     PUBLIC user32)
endif()
if(APXDISP_USE_LIBUSB)
  target_link_libraries(apxdisp_transport PUBLIC apxdisp_libusb)
endif()
if(UNIX AND NOT APPLE)
  target_link_libraries(apxdisp_common PUBLIC pthread)
endif()

if(APXDISP_BUILD_APP)
  add_executable(apxdisp "${APXDISP_ROOT}/app/main.cpp")
  target_link_libraries(apxdisp PRIVATE
    apxdisp_common apxdisp_capture apxdisp_encode apxdisp_transport apxdisp_inject apxdisp_pipeline
    apx_shared)
  if(WIN32)
    # GUID_DEVINTERFACE_USB_DEVICE 等接口 GUID 定义在 uuid.lib
    # winmm：timeBeginPeriod(1)——把系统定时器粒度从默认 15.6ms 提到 1ms，
    # 否则抓屏节流的 sleep 会被撑大，30fps 目标实测只能跑出 ~21fps。
    target_link_libraries(apxdisp PRIVATE uuid winmm)
  endif()
  install(TARGETS apxdisp RUNTIME DESTINATION bin)
endif()

# ---------------------------------------------------------------- 单元测试（离线可跑）
option(APXDISP_BUILD_TESTS "构建 display 离线单元测试" ON)
if(APXDISP_BUILD_TESTS AND WIN32)
  enable_testing()
  add_executable(tcp_transport_test "${APXDISP_ROOT}/tests/tcp_transport_test.cpp")
  target_link_libraries(tcp_transport_test PRIVATE apxdisp_transport)
  add_test(NAME tcp_transport_test COMMAND tcp_transport_test)
endif()

# ---------------------------------------------------------------- 提示 ----
message(STATUS "[display] 平台=${CMAKE_SYSTEM_NAME} DDA=${APXDISP_ENABLE_DDA} libusb=${APXDISP_USE_LIBUSB} shared=${APX_HAVE_SHARED}")
