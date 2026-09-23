cmake_minimum_required(VERSION 3.20)
project(allperiph_host VERSION 0.1.0 LANGUAGES CXX)

# ---------------------------------------------------------------- 基本设置
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "构建类型" FORCE)
endif()

option(APXPC_BUILD_UI             "构建 Win32 控制面板（仅 Windows）"        "${WIN32}")
option(APXPC_BUILD_SDK            "构建第三方 SDK 静态库"                    OFF)
option(APXPC_BUILD_EXAMPLES       "构建 SDK 示例程序"                        OFF)
option(APXPC_BUILD_TOOLS          "由 pc/tools 的 CMake 调用时置 ON"         OFF)
option(APXPC_USE_LIBUSB           "优先使用 libusb 做 USB 发现/批量传输"      OFF)
option(APXPC_BUILD_WIN_BACKENDS   "启用经典/WinRT 传感器与 HID 后端（需匹配 SDK 常量）" OFF)

set(APXPC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")

# core-proto 的 shared/ 协议库（唯一真源；缺失即 FATAL_ERROR，无 compat 替身）
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/ApxShared.cmake")

# ---------------------------------------------------------------- 编译选项
if(MSVC)
  add_compile_options(/utf-8 /EHsc /W3 /permissive- /Zc:__cplusplus)
  add_compile_definitions(_CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
else()
  add_compile_options(-Wall -Wextra -Wno-unused-parameter)
endif()

include(CheckIncludeFileCXX)

# .rc（应用图标资源）需要显式启用 RC 语言
if(WIN32)
  enable_language(RC)
endif()

# C++/WinRT（Windows.Devices.Sensors）需要 Windows SDK + cppwinrt 生成头
if(WIN32)
  check_include_file_cxx("winrt/Windows.Devices.Sensors.h" APXPC_HAVE_CPPWINRT)
  if(APXPC_HAVE_CPPWINRT)
    message(STATUS "[host] 检测到 C++/WinRT Sensors，启用 WinRT 传感器后端")
  else()
    message(STATUS "[host] 未检测到 winrt 头，仅使用经典 Sensor API (ISensorManager) 后端")
  endif()
endif()

# libusb（可选）
set(LIBUSB_TARGET "")
if(APXPC_USE_LIBUSB)
  find_path(LIBUSB_INCLUDE_DIR NAMES libusb-1.0/libusb.h libusb.h)
  find_library(LIBUSB_LIBRARY NAMES usb-1.0 libusb-1.0)
  if(LIBUSB_INCLUDE_DIR AND LIBUSB_LIBRARY)
    add_library(apx_libusb INTERFACE)
    target_include_directories(apx_libusb INTERFACE "${LIBUSB_INCLUDE_DIR}")
    target_link_libraries(apx_libusb INTERFACE "${LIBUSB_LIBRARY}")
    set(LIBUSB_TARGET apx_libusb)
    message(STATUS "[host] 启用 libusb: ${LIBUSB_LIBRARY}")
  else()
    message(STATUS "[host] 未找到 libusb，使用平台原生实现")
    set(APXPC_USE_LIBUSB OFF)
  endif()
endif()

# ---------------------------------------------------------------- 源码组织
set(APXPC_COMMON_SRC
  "${APXPC_ROOT}/src/log/log.cpp"
  "${APXPC_ROOT}/src/platform/clock.cpp"
  "${APXPC_ROOT}/src/util/crc32.cpp"
)

set(APXPC_DISCOVERY_SRC
  "${APXPC_ROOT}/src/discovery/descriptor_parse.cpp"
  "${APXPC_ROOT}/src/discovery/monitor.cpp"
)

set(APXPC_CTRL_SRC
  "${APXPC_ROOT}/src/ctrl/ctrl_frame.cpp"
  "${APXPC_ROOT}/src/ctrl/ctrl_client.cpp"
)

set(APXPC_SENSORS_SRC
  "${APXPC_ROOT}/src/sensors/sensor_reader_factory.cpp"
)

set(APXPC_SDK_SRC
  "${APXPC_ROOT}/sdk/src/allperiph_sdk.cpp"
)

if(WIN32)
  list(APPEND APXPC_DISCOVERY_SRC
    "${APXPC_ROOT}/src/discovery/usb_enum_win.cpp"
    "${APXPC_ROOT}/src/discovery/usb_types.cpp"
    "${APXPC_ROOT}/src/discovery/discovery.cpp")
  list(APPEND APXPC_CTRL_SRC
    "${APXPC_ROOT}/src/ctrl/hid_transport_win.cpp"
    "${APXPC_ROOT}/src/ctrl/bulk_transport_win.cpp")
  if(APXPC_BUILD_WIN_BACKENDS)
    list(APPEND APXPC_SENSORS_SRC
      "${APXPC_ROOT}/src/sensors/sensor_reader_win_sensorapi.cpp")
    if(APXPC_HAVE_CPPWINRT AND EXISTS "${APXPC_ROOT}/src/sensors/sensor_reader_winrt.cpp")
      list(APPEND APXPC_SENSORS_SRC "${APXPC_ROOT}/src/sensors/sensor_reader_winrt.cpp")
    endif()
  else()
    # 本机 SDK 不导出 SENSOR_TYPE_* 常量，使用可链接占位
    list(APPEND APXPC_SENSORS_SRC "${APXPC_ROOT}/src/sensors/sensor_reader_win_stub.cpp")
  endif()
else()
  if(LIBUSB_TARGET)
    list(APPEND APXPC_DISCOVERY_SRC "${APXPC_ROOT}/src/discovery/usb_enum_libusb.cpp")
    list(APPEND APXPC_CTRL_SRC "${APXPC_ROOT}/src/ctrl/bulk_transport_libusb.cpp")
  else()
    list(APPEND APXPC_DISCOVERY_SRC "${APXPC_ROOT}/src/discovery/usb_enum_sysfs.cpp")
  endif()
  list(APPEND APXPC_CTRL_SRC "${APXPC_ROOT}/src/ctrl/hid_transport_hidraw.cpp")
  list(APPEND APXPC_SENSORS_SRC "${APXPC_ROOT}/src/sensors/sensor_reader_iio.cpp")
endif()

# ---------------------------------------------------------------- 目标：core
add_library(apxpc_core STATIC ${APXPC_COMMON_SRC})
target_include_directories(apxpc_core PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_core PUBLIC apx_shared)
target_compile_features(apxpc_core PUBLIC cxx_std_20)

# ---------------------------------------------------------------- 目标：discovery
add_library(apxpc_discovery STATIC ${APXPC_DISCOVERY_SRC})
target_include_directories(apxpc_discovery PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_discovery PUBLIC apxpc_core ${LIBUSB_TARGET})

# ---------------------------------------------------------------- 目标：ctrl
add_library(apxpc_ctrl STATIC ${APXPC_CTRL_SRC})
target_include_directories(apxpc_ctrl PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_ctrl PUBLIC apxpc_core apxpc_discovery ${LIBUSB_TARGET})

# ---------------------------------------------------------------- 目标：sensors
add_library(apxpc_sensors STATIC ${APXPC_SENSORS_SRC})
target_include_directories(apxpc_sensors PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_sensors PUBLIC apxpc_core)

# ---------------------------------------------------------------- 平台链接库
set(APXPC_PLATFORM_LIBS "")
if(WIN32)
  set(APXPC_PLATFORM_LIBS setupapi cfgmgr32 hid winusb user32 gdi32 comctl32 ole32 propsys)
  target_link_libraries(apxpc_discovery PUBLIC setupapi cfgmgr32)
  target_link_libraries(apxpc_ctrl     PUBLIC setupapi hid winusb cfgmgr32)
  target_link_libraries(apxpc_sensors  PUBLIC ole32 propsys)
endif()
if(UNIX AND NOT APPLE)
  target_link_libraries(apxpc_core PUBLIC pthread)
endif()

# ---------------------------------------------------------------- 目标：sdk
if(APXPC_BUILD_SDK)
  add_library(apxpc_sdk STATIC ${APXPC_SDK_SRC})
  target_include_directories(apxpc_sdk PUBLIC "${APXPC_ROOT}/sdk/include")
  target_link_libraries(apxpc_sdk PUBLIC apxpc_core apxpc_discovery apxpc_ctrl apxpc_sensors)
  set(APXPC_HOST_LIBS apxpc_core apxpc_discovery apxpc_ctrl apxpc_sensors apxpc_sdk)
else()
  set(APXPC_HOST_LIBS apxpc_core apxpc_discovery apxpc_ctrl apxpc_sensors)
endif()

# ---------------------------------------------------------------- 目标：常驻服务（Web 控制面 + 热键 + 托盘 + 仲裁）
set(APXPC_SERVICE_SRC
  "${APXPC_ROOT}/src/net/json.cpp"
  "${APXPC_ROOT}/src/net/http_server.cpp"
  "${APXPC_ROOT}/src/config/app_config.cpp"
  "${APXPC_ROOT}/src/bandwidth/arbiter.cpp"
  "${APXPC_ROOT}/src/display/display_control.cpp"
  "${APXPC_ROOT}/src/hotkey/hotkey_manager.cpp"
  "${APXPC_ROOT}/src/api/action_router.cpp"
  "${APXPC_ROOT}/src/app/host_service.cpp"
  "${APXPC_ROOT}/src/tray/tray_win32.cpp"
)
add_library(apxpc_service STATIC ${APXPC_SERVICE_SRC})
# v1：无线数据通道用 apx_shared 的 APX 帧协议（writeHeader/crc32）
target_link_libraries(apxpc_service PUBLIC apx_shared)
target_include_directories(apxpc_service PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_service PUBLIC apxpc_core apxpc_discovery apxpc_sensors ${LIBUSB_TARGET})
if(WIN32)
  target_link_libraries(apxpc_service PUBLIC ws2_32 shell32 user32 ole32)
endif()

# ---------------------------------------------------------- 目标：Wi‑Fi 控制通道
# 无蓝牙适配器的 PC（本机实测无任何蓝牙设备）上承载触控板 / 键盘 / 多媒体：
# 手机做服务端（TCP 9500），本端做客户端主动连入，收到 streamId=3 控制帧后
# 用 SendInput 注入系统 —— 免驱，符合架构 §2.2「无线模式零新驱动」。
set(APXPC_WIRELESS_SRC
  "${APXPC_ROOT}/src/wireless/wireless_link.cpp"
  "${APXPC_ROOT}/src/wireless/beacon_listener.cpp"
  "${APXPC_ROOT}/src/wireless/wireless_session.cpp"
)
add_library(apxpc_wireless STATIC ${APXPC_WIRELESS_SRC})
target_include_directories(apxpc_wireless PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_wireless PUBLIC apxpc_core)
if(WIN32)
  target_link_libraries(apxpc_wireless PUBLIC ws2_32)
endif()

# ---------------------------------------------------------------- 目标：apxhost 可执行文件
add_executable(apxhost "${APXPC_ROOT}/src/host_main.cpp")
target_link_libraries(apxhost PRIVATE apxpc_core apxpc_discovery apxpc_sensors apxpc_service apxpc_wireless)
if(WIN32)
  target_link_libraries(apxhost PRIVATE ws2_32 shell32 user32 hid setupapi cfgmgr32)
endif()

# ---------------------------------------------------------------- 目标：桌面端面板
# 纯 Win32 + common controls（不引 Qt/wx），兑现 apxpc/ui/panel.hpp 的 runPanel。
set(APXPC_UI_SRC
  "${APXPC_ROOT}/src/ui/panel_win32.cpp"
)
add_library(apxpc_ui STATIC ${APXPC_UI_SRC})
target_include_directories(apxpc_ui PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_ui PUBLIC apxpc_core apxpc_wireless apxpc_service)
if(WIN32)
  # gdiplus：面板自绘（Windows 自带组件，不算第三方依赖）
  target_link_libraries(apxpc_ui PUBLIC comctl32 user32 shell32 gdi32 gdiplus)
endif()

# 桌面端可执行：GUI 子系统（双击无控制台）。入口仍是 main()，用 /ENTRY 绕开
# MSVC 对 WinMain 的强制要求。图标资源（res/apx.rc → apx.ico）打进 exe，
# 窗口与托盘共用同一枚。
set(APXDESKTOP_SRC "${APXPC_ROOT}/src/desktop_main.cpp")
if(WIN32)
  list(APPEND APXDESKTOP_SRC "${APXPC_ROOT}/res/apx.rc")
endif()
add_executable(apxdesktop ${APXDESKTOP_SRC})
target_link_libraries(apxdesktop PRIVATE apxpc_ui apxpc_wireless apxpc_core)
if(WIN32)
  set_target_properties(apxdesktop PROPERTIES WIN32_EXECUTABLE TRUE)
  target_link_options(apxdesktop PRIVATE "/ENTRY:mainCRTStartup")
  target_link_libraries(apxdesktop PRIVATE ws2_32 shell32 user32 comctl32 gdi32 gdiplus setupapi cfgmgr32)

  # 安装包要内嵌桌面端可执行文件。用固定路径的副本做载荷，好处是 .rc 的路径在
  # **配置阶段**就完全确定 —— 换成 $<CONFIG> 的话，多配置生成器会让 file(GENERATE)
  # 为每个配置各写一遍同名文件，内容互相覆盖，结果不确定。
  set(APX_PAYLOAD_DIR "${CMAKE_BINARY_DIR}/payload")
  set(APX_PAYLOAD_EXE "${APX_PAYLOAD_DIR}/apxdesktop.exe")
  file(TO_NATIVE_PATH "${APX_PAYLOAD_EXE}" APX_PAYLOAD_EXE_NATIVE)
  configure_file("${APXPC_ROOT}/res/setup_payload.rc.in"
                 "${CMAKE_BINARY_DIR}/setup_payload.rc" @ONLY)

  add_custom_command(TARGET apxdesktop POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${APX_PAYLOAD_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:apxdesktop>" "${APX_PAYLOAD_EXE}"
    COMMENT "暂存安装包载荷 apxdesktop.exe")

  # ------------------------------------------------------------ 目标：安装包
  # 自包含、免第三方打包工具（NSIS/Inno/WiX 都不在目标机器上）：把 apxdesktop.exe
  # 作为 RCDATA 打进 apxsetup.exe，运行时释放到 %LOCALAPPDATA%\AllPeriph。
  add_executable(apxsetup
    "${APXPC_ROOT}/src/setup_main.cpp"
    "${CMAKE_BINARY_DIR}/setup_payload.rc")
  target_include_directories(apxsetup PRIVATE "${APXPC_ROOT}/include")
  target_link_libraries(apxsetup PRIVATE shell32 ole32 shlwapi user32 advapi32)
  set_target_properties(apxsetup PROPERTIES WIN32_EXECUTABLE TRUE)
  target_link_options(apxsetup PRIVATE "/ENTRY:mainCRTStartup")
  # 必须先完成 apxdesktop（含其 POST_BUILD 载荷暂存），资源才能找到载荷文件
  add_dependencies(apxsetup apxdesktop)
endif()

# ---------------------------------------------------------------- 示例
if(APXPC_BUILD_SDK AND APXPC_BUILD_EXAMPLES)
  add_executable(sensor_watch "${APXPC_ROOT}/sdk/examples/sensor_watch.c")
  target_link_libraries(sensor_watch PRIVATE apxpc_sdk)
  set_target_properties(sensor_watch PROPERTIES C_STANDARD 99)
endif()

# ---------------------------------------------------------------- 单元测试（离线可跑）
option(APXPC_BUILD_TESTS "构建宿主离线单元测试" ON)
if(APXPC_BUILD_TESTS)
  enable_testing()
  add_executable(arbiter_test "${APXPC_ROOT}/tests/arbiter_test.cpp")
  target_link_libraries(arbiter_test PRIVATE apxpc_service)
  if(WIN32)
    target_link_libraries(arbiter_test PRIVATE ws2_32 hid setupapi cfgmgr32)
  endif()
  add_test(NAME arbiter_test COMMAND arbiter_test)
endif()

# ---------------------------------------------------------------- 安装
install(TARGETS apxhost apxdesktop RUNTIME DESTINATION bin)
if(APXPC_BUILD_SDK)
  install(TARGETS apxpc_sdk ARCHIVE DESTINATION lib)
  install(DIRECTORY "${APXPC_ROOT}/sdk/include/" DESTINATION include)
endif()

message(STATUS "[host] 平台=${CMAKE_SYSTEM_NAME} UI=${APXPC_BUILD_UI} libusb=${APXPC_USE_LIBUSB} shared=${APX_HAVE_SHARED}")
