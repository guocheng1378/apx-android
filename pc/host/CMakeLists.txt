cmake_minimum_required(VERSION 3.20)
project(allperiph_host VERSION 0.1.0 LANGUAGES CXX)

# ---------------------------------------------------------------- macOS 部署目标（尽早，影响后续所有目标）
# CoreAudio Process Tap 是 macOS 14.2 的 API（API_AVAILABLE）—— 要让链接器把它标成
# weak_import、运行时 @available 才能真正降级，整体部署目标必须压到 14.2 以下；
# 否则产物直接按当前系统版本链接，旧系统 dyld 拒启，版本门白写。
# 12.0 覆盖仓内现有全部 Apple API（NSURLSession/AppKit/CoreGraphics 均为 10.x 级）；
# 未被用户显式指定时才接管，尊重外部 -DCMAKE_OSX_DEPLOYMENT_TARGET 传入。
if(APPLE AND (NOT DEFINED CMAKE_OSX_DEPLOYMENT_TARGET OR CMAKE_OSX_DEPLOYMENT_TARGET STREQUAL ""))
  set(CMAKE_OSX_DEPLOYMENT_TARGET "12.0" CACHE STRING "macOS 最低部署版本" FORCE)
  message(STATUS "[host] macOS 部署目标 = 12.0（为 14.2 Tap API 弱链接留空间）")
endif()

# ---------------------------------------------------------------- WebView2 SDK（仅 Windows + 开启 WebView2 面板）
if(WIN32 AND APXPC_BUILD_UI AND APXPC_BUILD_UI_WEBVIEW2)
  include(FetchContent)
  set(FETCHCONTENT_QUIET OFF)
  set(WEBVIEW2_NUGET_VERSION "1.0.4129.50")
  FetchContent_Declare(WebView2SDK
    URL      https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/${WEBVIEW2_NUGET_VERSION}/microsoft.web.webview2.${WEBVIEW2_NUGET_VERSION}.nupkg
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
  )
  FetchContent_MakeAvailable(WebView2SDK)
  foreach(_wv2_var WebView2SDK_SOURCE_DIR webview2sdk_SOURCE_DIR WEBVIEW2SDK_SOURCE_DIR)
    if(DEFINED ${_wv2_var} AND NOT "${${_wv2_var}}" STREQUAL "")
      set(WEBVIEW2_SDK_DIR "${${_wv2_var}}")
      break()
    endif()
  endforeach()
  if(NOT DEFINED WEBVIEW2_SDK_DIR OR "${WEBVIEW2_SDK_DIR}" STREQUAL "")
    file(GLOB_RECURSE WV2_H_PATH "${CMAKE_BINARY_DIR}/_deps/*/WebView2.h"
         "${CMAKE_SOURCE_DIR}/_deps/*/WebView2.h")
    if(WV2_H_PATH)
      get_filename_component(WV2_H_DIR "${WV2_H_PATH}" DIRECTORY)
      get_filename_component(WEBVIEW2_SDK_DIR "${WV2_H_DIR}" DIRECTORY)
      get_filename_component(WEBVIEW2_SDK_DIR "${WEBVIEW2_SDK_DIR}" DIRECTORY)
      get_filename_component(WEBVIEW2_SDK_DIR "${WEBVIEW2_SDK_DIR}" DIRECTORY)
    endif()
  endif()
  message(STATUS "[host] WEBVIEW2_SDK_DIR=${WEBVIEW2_SDK_DIR}")

  FetchContent_Declare(wil
    GIT_REPOSITORY https://github.com/microsoft/wil.git
    GIT_TAG        v1.0.240803.1
    GIT_SHALLOW    TRUE
  )
  FetchContent_Populate(wil)
  if(NOT TARGET wil::headers)
    add_library(wil::headers INTERFACE IMPORTED)
    set_target_properties(wil::headers PROPERTIES
      INTERFACE_INCLUDE_DIRECTORIES "${wil_SOURCE_DIR}/include"
    )
  endif()
endif()
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "构建类型" FORCE)
endif()

option(APXPC_BUILD_UI             "构建桌面控制面板（仅 Windows）"        "${WIN32}")
option(APXPC_BUILD_UI_WEBVIEW2    "使用 WebView2 面板（替代旧 Win32 GDI+ 自绘）" ON)
option(APXPC_BUILD_SDK            "构建第三方 SDK 静态库"                    OFF)
option(APXPC_BUILD_EXAMPLES       "构建 SDK 示例程序"                        OFF)
option(APXPC_BUILD_TOOLS          "由 pc/tools 的 CMake 调用时置 ON"         OFF)
option(APXPC_USE_LIBUSB           "优先使用 libusb 做 USB 发现/批量传输"      OFF)
option(APXPC_BUILD_WIN_BACKENDS   "启用经典/WinRT 传感器与 HID 后端（需匹配 SDK 常量）" OFF)

# ---------------------------------------------------------------- 发版版本号同源（2026-10-23）
# 与 android/app/build.gradle.kts 完全同构：GitHub Actions 打 tag v* 构建时
# GITHUB_REF 为 refs/tags/*，取 GITHUB_REF_NAME（如 v205）注入 APXPC_VERSION_STRING ——
# 面板关于页 / `apxhost --version` / 卸载项 DisplayVersion 与 Release tag、App
# versionName 完全一致。此前 PC 侧硬编码 version.hpp 默认值（0.3.1），三端版本号不同源。
# 非发版构建与本地构建不注入，保持 version.hpp 的 #ifndef 默认值（预期行为）。
# 注意：必须放在任何 add_* 目标之前 —— 目标在创建时继承目录级 COMPILE_DEFINITIONS。
if(DEFINED ENV{GITHUB_REF} AND "$ENV{GITHUB_REF}" MATCHES "^refs/tags/v[0-9]")
  set(APXPC_RELEASE_TAG "$ENV{GITHUB_REF_NAME}")
  add_compile_definitions(APXPC_VERSION_STRING="${APXPC_RELEASE_TAG}")
  message(STATUS "[host] 发版构建：APXPC_VERSION_STRING=${APXPC_RELEASE_TAG}")
endif()

set(APXPC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/ApxShared.cmake")

if(MSVC)
  add_compile_options(/utf-8 /EHsc /W3 /permissive- /Zc:__cplusplus)
  add_compile_definitions(_CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
  set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} /Zi")
  set(CMAKE_EXE_LINKER_FLAGS_RELEASE "${CMAKE_EXE_LINKER_FLAGS_RELEASE} /DEBUG /OPT:REF /OPT:ICF")
else()
  add_compile_options(-Wall -Wextra -Wno-unused-parameter)
endif()

include(CheckIncludeFileCXX)
if(WIN32)
  enable_language(RC)
endif()
if(WIN32)
  check_include_file_cxx("winrt/Windows.Devices.Sensors.h" APXPC_HAVE_CPPWINRT)
  if(APXPC_HAVE_CPPWINRT)
    message(STATUS "[host] 检测到 C++/WinRT Sensors，启用 WinRT 传感器后端")
  else()
    message(STATUS "[host] 未检测到 winrt 头，仅使用经典 Sensor API (ISensorManager) 后端")
  endif()
endif()

set(LIBUSB_TARGET "")
if(APXPC_USE_LIBUSB)
  find_path(APXPC_USB_INCLUDE_DIR NAMES libusb-1.0/libusb.h libusb.h)
  find_library(APXPC_USB_LIBRARY NAMES usb-1.0 libusb-1.0)
  if(APXPC_USB_INCLUDE_DIR AND APXPC_USB_LIBRARY)
    add_library(apx_libusb INTERFACE)
    target_include_directories(apx_libusb INTERFACE "${APXPC_USB_INCLUDE_DIR}")
    target_link_libraries(apx_libusb INTERFACE "${APXPC_USB_LIBRARY}")
    set(LIBUSB_TARGET apx_libusb)
    message(STATUS "[host] 启用 libusb: ${APXPC_USB_LIBRARY}")
  else()
    message(STATUS "[host] 未找到 libusb，使用平台原生实现")
    set(APXPC_USE_LIBUSB OFF)
  endif()
endif()

# ---------------------------------------------------------------- 源码组织
set(APXPC_COMMON_SRC
  "${APXPC_ROOT}/src/log/log.cpp"
  # 日志清单 + 按时间范围导出（issue #52 建议 5）：只依赖标准库，三端共用。
  "${APXPC_ROOT}/src/log/log_export.cpp"
  "${APXPC_ROOT}/src/platform/clock.cpp"
  "${APXPC_ROOT}/src/util/crc32.cpp"
  # 本机端口占用探测：面板用它区分「9511 已由另一个实例提供」与「真的起不来」。
  # 放 core 而不是面板 TU 里 —— 它要能被真起 socket 的用例覆盖（tests/tcp_probe_test.cpp）。
  "${APXPC_ROOT}/src/net/tcp_probe.cpp"
  # Sunshine 只读检测（装没装/在跑没跑）：mac/Linux 副屏出路 = 用户自装 Sunshine
  # + Moonlight（ROADMAP §2.5，不内嵌 —— Moonlight 客户端 GPLv3 与 MIT 冲突）。
  # panel_http（两端 exe 的源列表）无条件 include 它，所以进 core 而非按平台分支。
  "${APXPC_ROOT}/src/screen/sunshine_detect.cpp"
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
      list(APPEND APXPC_SENSORS_SRC
        "${APXPC_ROOT}/src/sensors/sensor_reader_winrt.cpp")
    endif()
  else()
    list(APPEND APXPC_SENSORS_SRC
      "${APXPC_ROOT}/src/sensors/sensor_reader_win_stub.cpp")
  endif()
else()
  if(LIBUSB_TARGET)
    list(APPEND APXPC_DISCOVERY_SRC
      "${APXPC_ROOT}/src/discovery/usb_enum_libusb.cpp")
    list(APPEND APXPC_CTRL_SRC
      "${APXPC_ROOT}/src/ctrl/bulk_transport_libusb.cpp")
  elseif(APPLE)
    # mac：既没有 /sys（sysfs 后端恒返回空枚举 → 面板 USB 状态永远 false），
    # 默认也不装 libusb。IOKit 是系统自带框架，零外部依赖，走它。
    list(APPEND APXPC_DISCOVERY_SRC
      "${APXPC_ROOT}/src/discovery/usb_enum_iokit.cpp")
  else()
    list(APPEND APXPC_DISCOVERY_SRC
      "${APXPC_ROOT}/src/discovery/usb_enum_sysfs.cpp")
  endif()
  # ★ discovery.cpp（usb::Device → discovery::DeviceInfo 包装层，纯跨平台、零 Win32 API）
  #   此前只进 WIN32 分支 —— 非 Windows 链接 apxhost 时缺 apxpc::discovery::enumerate()
  #   符号。Linux/mac 从未链接过 PC 宿主（PC CI 只有 Windows），一直没暴露；mac-port 首次踩出。
  list(APPEND APXPC_DISCOVERY_SRC "${APXPC_ROOT}/src/discovery/discovery.cpp")
  # ★ usb_types.cpp：Device/Interface/Endpoint 的非 inline 方法（looksLikeApxGadget /
  #   vidPid / roleName…），纯跨平台（snprintf + 字符串映射）—— 静态库二级依赖：
  #   discovery.cpp 拉入 sysfs 后端后，sysfs.o 引用 looksLikeApxGadget 随即缺失，同样
  #   此前只进 WIN32 分支。三端行为同源的必要成员。
  list(APPEND APXPC_DISCOVERY_SRC "${APXPC_ROOT}/src/discovery/usb_types.cpp")
  list(APPEND APXPC_CTRL_SRC "${APXPC_ROOT}/src/ctrl/hid_transport_hidraw.cpp")
  list(APPEND APXPC_SENSORS_SRC "${APXPC_ROOT}/src/sensors/sensor_reader_iio.cpp")
endif()

# ---------------------------------------------------------------- 目标：core
add_library(apxpc_core STATIC ${APXPC_COMMON_SRC})
target_include_directories(apxpc_core PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_core PUBLIC apx_shared)
target_compile_features(apxpc_core PUBLIC cxx_std_20)
if(WIN32)
  # tcp_probe.cpp 在 Windows 上走 winsock（WSASocket/select/getsockopt）——
  # 静态库自身不必链，但**使用者**必须带上 ws2_32，在这里用 INTERFACE 传播出去。
  target_link_libraries(apxpc_core INTERFACE ws2_32)
endif()

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
  target_link_libraries(apxpc_discovery PUBLIC setupapi cfgmgr32 hid)
  target_link_libraries(apxpc_ctrl     PUBLIC setupapi hid winusb cfgmgr32)
  target_link_libraries(apxpc_sensors  PUBLIC ole32 propsys)
endif()
if(UNIX AND NOT APPLE)
  target_link_libraries(apxpc_core PUBLIC pthread)
endif()
if(APPLE)
  # usb_enum_iokit.cpp：IORegistry（IOKit）+ CFType/CFString/CFNumber（CoreFoundation）
  target_link_libraries(apxpc_discovery PUBLIC "-framework IOKit" "-framework CoreFoundation")
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

# ---------------------------------------------------------------- 目标：常驻服务（9511 受控端 + 热键 + 托盘 + 仲裁）
set(APXPC_SERVICE_SRC
  "${APXPC_ROOT}/src/net/json.cpp"
  "${APXPC_ROOT}/src/config/app_config.cpp"
  "${APXPC_ROOT}/src/bandwidth/arbiter.cpp"
  "${APXPC_ROOT}/src/display/display_control.cpp"
  "${APXPC_ROOT}/src/hotkey/hotkey_manager.cpp"
  "${APXPC_ROOT}/src/api/action_router.cpp"
  "${APXPC_ROOT}/src/app/host_service.cpp"
  "${APXPC_ROOT}/src/app/update_check.cpp"
  "${APXPC_ROOT}/src/tray/tray_win32.cpp"
  # ★ 开机自启：web_panel.cpp 的 tray.setAutostart 需要链接此实现
  "${APXPC_ROOT}/src/autostart.cpp"
)
if(APPLE)
  # update_check 的 macOS HTTP 实现（NSURLSession / ObjC++）。必须在 add_library 前
  # enable OBJCXX，否则 .mm 无法识别语言。
  enable_language(OBJCXX)
  list(APPEND APXPC_SERVICE_SRC "${APXPC_ROOT}/src/app/update_check_mac.mm")
  # mac 托盘（NSStatusItem + NSMenu）：**v223.7 已交付**（src/tray/tray_mac.mm）。
  # 此前这里写的是「ROADMAP §2.4 待办 ②」，那条待办已过时（ROADMAP §2.4 桌面壳
  # 一行的「托盘仍无」同期已更正）。
  # 托盘主机进 service 静态库 —— apxhost 与 apxdesktop 都链它，一处实现两处可用；
  # 同时 tray_win32.cpp 的「非 Windows 空桩」已按 __APPLE__ 排除，不会重复定义。
  list(APPEND APXPC_SERVICE_SRC "${APXPC_ROOT}/src/tray/tray_mac.mm")
endif()
add_library(apxpc_service STATIC ${APXPC_SERVICE_SRC})
target_link_libraries(apxpc_service PUBLIC apx_shared)
target_include_directories(apxpc_service PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_service PUBLIC apxpc_core apxpc_discovery apxpc_sensors ${LIBUSB_TARGET})
if(WIN32)
  # advapi32：RegOpenKeyEx/RegSetValueEx/RegDeleteValue（autostart.cpp 注册表操作）
  # winhttp：更新检测（update_check.cpp，与 webpanel/apxsetup 同依赖）
  target_link_libraries(apxpc_service PUBLIC ws2_32 shell32 user32 ole32 advapi32 winhttp)
endif()
if(APPLE)
  # update_check_mac.mm：NSURLSession 的 completionHandler block 跨异步生命周期持有
  # 对象，必须 ARC；静态库内只有这一个 .mm，按语言粒度开选项不影响其他 C++ TU。
  # ARC 对本库内的 .mm 全开（tray_mac.mm 与 update_check_mac.mm 都按 ARC 写）。
  target_compile_options(apxpc_service PRIVATE $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)
  # AppKit：tray_mac.mm 的 NSStatusItem / NSMenu / NSEvent / NSUserNotification。
  target_link_libraries(apxpc_service PUBLIC "-framework Foundation" "-framework AppKit")
endif()

# ---------------------------------------------------------- 目标：统一控制面（9511）
set(APXPC_WIRELESS_SRC
  "${APXPC_ROOT}/src/wireless/wireless_session.cpp"
  "${APXPC_ROOT}/src/wireless/ctrl9511.cpp"
  "${APXPC_ROOT}/src/wireless/file_receiver.cpp"
  "${APXPC_ROOT}/src/wireless/file_sender.cpp"
)
if(WIN32)
  list(APPEND APXPC_WIRELESS_SRC "${APXPC_ROOT}/src/wireless/input_injector_win.cpp"
                                  "${APXPC_ROOT}/src/wireless/input_capture_win.cpp")
elseif(APPLE)
  list(APPEND APXPC_WIRELESS_SRC "${APXPC_ROOT}/src/wireless/input_injector_mac.mm"
                                  "${APXPC_ROOT}/src/wireless/input_capture_mac.mm")
elseif(UNIX)
  list(APPEND APXPC_WIRELESS_SRC "${APXPC_ROOT}/src/wireless/input_injector_lin.cpp"
                                  "${APXPC_ROOT}/src/wireless/input_capture_lin.cpp")
endif()
add_library(apxpc_wireless STATIC ${APXPC_WIRELESS_SRC})
target_include_directories(apxpc_wireless PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_wireless PUBLIC apxpc_core)
if(WIN32)
  target_link_libraries(apxpc_wireless PUBLIC ws2_32 Iphlpapi)
elseif(APPLE)
  enable_language(OBJCXX)
  target_link_libraries(apxpc_wireless PUBLIC "-framework CoreGraphics" "-framework AppKit" "-framework ApplicationServices")
endif()

# ---------------------------------------------------------------- 目标：LocalSend 互传（PC 收发两端）
# 手机端 com.allperiph.localsend 早就完整（多播发现 + 五个 HTTP 端点），PC 端此前一个字节都没有，
# 于是「手机 ↔ 官方桌面版」能通、「手机 ↔ 本工程 PC 端」不通。这里补的就是 PC 这一半：
#   · 接收端：加入多播组、周期 announce、实现 info/register/prepare-upload/upload/cancel、落盘
#   · 发送端：register → prepare-upload → upload 三步推送（PC → 手机 / PC → 官方客户端）
set(APXPC_LOCALSEND_SRC
  "${APXPC_ROOT}/src/localsend/local_send_receiver.cpp"
  "${APXPC_ROOT}/src/localsend/local_send_sender.cpp"
  "${APXPC_ROOT}/src/localsend/sha256.cpp"
)
add_library(apxpc_localsend STATIC ${APXPC_LOCALSEND_SRC})
target_include_directories(apxpc_localsend PUBLIC "${APXPC_ROOT}/include")
# json 的实现在 apxpc_service（src/net/json.cpp 属该库），协议层直接复用，不另造解析器
target_link_libraries(apxpc_localsend PUBLIC apxpc_service)
if(WIN32)
  target_link_libraries(apxpc_localsend PUBLIC ws2_32)
endif()

# ---------------------------------------------------------------- 目标：媒体通道
set(APXPC_MEDIA_SRC
  "${APXPC_ROOT}/src/media/media_session.cpp"
  "${APXPC_ROOT}/src/media/audio_capture.cpp"
  "${APXPC_ROOT}/src/media/mic_bridge.cpp"
  "${APXPC_ROOT}/src/media/touch_inject.cpp"
)
if(APPLE)
  # 系统声音采集（CoreAudio Process Tap，14.2+）：CATapDescription 是 ObjC 类，
  # 必须 .mm；OBJCXX 在 service 块已 enable，这里幂等再声明一次防顺序调整。
  enable_language(OBJCXX)
  list(APPEND APXPC_MEDIA_SRC "${APXPC_ROOT}/src/media/audio_capture_mac.mm")
endif()
add_library(apxpc_media STATIC ${APXPC_MEDIA_SRC})
target_include_directories(apxpc_media PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_media PUBLIC apxpc_core)
if(WIN32)
  target_link_libraries(apxpc_media PUBLIC ws2_32 ole32 uuid)
endif()
if(APPLE)
  # mic_bridge 的 macOS 实现：CoreAudio AudioQueue 播放 + 默认输出设备名；
  # audio_capture_mac.mm（Tap）所需的 API 全部在 CoreAudio（无需额外框架）。
  target_link_libraries(apxpc_media PUBLIC "-framework CoreAudio" "-framework AudioToolbox")
  # ARC：audio_capture_mac.mm 的 CATapDescription/NSDictionary 对象句柄
  target_compile_options(apxpc_media PRIVATE $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)
endif()
target_link_libraries(apxpc_wireless PUBLIC apxpc_media)

# ---------------------------------------------------------------- 目标：副屏推流
set(APXDISP_BUILD_APP   OFF CACHE BOOL "" FORCE)
set(APXDISP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory("${APXPC_ROOT}/../display" "${CMAKE_BINARY_DIR}/apxdisp_in_host")

set(APXPC_SCREEN_SRC
  "${APXPC_ROOT}/src/media/screen_push.cpp"
)
add_library(apxpc_screen STATIC ${APXPC_SCREEN_SRC})
target_include_directories(apxpc_screen PUBLIC "${APXPC_ROOT}/include")
target_link_libraries(apxpc_screen PUBLIC apxpc_media apxdisp_pipeline)
if(WIN32)
  target_link_libraries(apxpc_screen PUBLIC winmm)
endif()

# ---------------------------------------------------------------- 目标：apxhost 可执行文件
# panel_http.cpp：浏览器面板后端（方案 B）。直接进 exe 源列表（非静态库），
# 让 panel 的全部符号依赖都在 apxhost 既有的 link 列表里（objs 先于 libs，无序问题）。
add_executable(apxhost "${APXPC_ROOT}/src/host_main.cpp"
                       "${APXPC_ROOT}/src/ui/panel_http.cpp")
target_link_libraries(apxhost PRIVATE apxpc_core apxpc_discovery apxpc_sensors apxpc_service apxpc_wireless apxpc_media apxpc_localsend)
if(WIN32)
  target_link_libraries(apxhost PRIVATE ws2_32 shell32 user32 hid setupapi cfgmgr32)
endif()

# ---------------------------------------------------------------- 目标：桌面端面板
if(WIN32 AND APXPC_BUILD_UI)
  if(APXPC_BUILD_UI_WEBVIEW2)
    set(APXPC_WEBPANEL_SRC
      "${APXPC_ROOT}/src/ui/web_panel.cpp"
      "${APXPC_ROOT}/src/ui/webview2_runtime.cpp")
    add_library(apxpc_webpanel STATIC ${APXPC_WEBPANEL_SRC})
    target_include_directories(apxpc_webpanel PUBLIC "${APXPC_ROOT}/include")
    # apxpc_localsend：面板里的「互传」卡（buildState 的 localsend 段 + toggle / openDir 动作）
    target_link_libraries(apxpc_webpanel PUBLIC apxpc_core apxpc_wireless apxpc_media apxpc_screen apxpc_service apxpc_discovery apxpc_localsend)

    if(DEFINED WEBVIEW2_SDK_DIR)
      target_include_directories(apxpc_webpanel PUBLIC
        "${WEBVIEW2_SDK_DIR}/build/native/include")
      if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        set(WV2_LIB_DIR "${WEBVIEW2_SDK_DIR}/build/native/x64")
      elseif(CMAKE_SIZEOF_VOID_P EQUAL 4)
        set(WV2_LIB_DIR "${WEBVIEW2_SDK_DIR}/build/native/x86")
      else()
        set(WV2_LIB_DIR "${WEBVIEW2_SDK_DIR}/build/native/arm64")
      endif()
      target_link_directories(apxpc_webpanel PUBLIC "${WV2_LIB_DIR}")
      target_link_libraries(apxpc_webpanel PUBLIC WebView2Loader.dll.lib)
    endif()
    if(TARGET wil::headers)
      target_link_libraries(apxpc_webpanel PUBLIC wil::headers)
    elseif(EXISTS "${wil_SOURCE_DIR}/include")
      target_include_directories(apxpc_webpanel PUBLIC "${wil_SOURCE_DIR}/include")
    endif()
    target_link_libraries(apxpc_webpanel PUBLIC user32 shell32 ole32 shlwapi winhttp)
  else()
    set(APXPC_UI_SRC
      "${APXPC_ROOT}/src/ui/panel_win32.cpp"
      "${APXPC_ROOT}/src/ui/file_panel_win32.cpp"
      "${APXPC_ROOT}/src/ui/remote_input_win32.cpp"
      "${APXPC_ROOT}/src/ui/settings_win32.cpp"
    )
    add_library(apxpc_ui STATIC ${APXPC_UI_SRC})
    target_include_directories(apxpc_ui PUBLIC "${APXPC_ROOT}/include")
    target_link_libraries(apxpc_ui PUBLIC apxpc_core apxpc_wireless apxpc_service apxpc_media apxpc_screen)
    if(WIN32)
      target_link_libraries(apxpc_ui PUBLIC comctl32 user32 shell32 gdi32 gdiplus)
    endif()
  endif()
endif()

set(APXDESKTOP_SRC "${APXPC_ROOT}/src/desktop_main.cpp")
if(WIN32)
  list(APPEND APXDESKTOP_SRC "${APXPC_ROOT}/res/apx.rc")
elseif(APPLE)
  # HTTP 面板后端（runWebPanelHttp）直接进 exe 源（同 apxhost 的做法）：objs 先于 libs，
  # 无线/媒体/service 符号都在下方链接面里，无静态库顺序问题。Win 不需要（走 webpanel）。
  list(APPEND APXDESKTOP_SRC "${APXPC_ROOT}/src/ui/panel_http.cpp")
endif()
add_executable(apxdesktop ${APXDESKTOP_SRC})

if(APXPC_BUILD_UI AND APXPC_BUILD_UI_WEBVIEW2 AND WIN32)
  target_link_libraries(apxdesktop PRIVATE apxpc_webpanel apxpc_wireless apxpc_media apxpc_screen apxpc_service apxpc_core apxpc_localsend)
elseif(APXPC_BUILD_UI AND WIN32)
  target_link_libraries(apxdesktop PRIVATE apxpc_ui apxpc_wireless apxpc_core)
else()
  # 无 WebView2 壳的平台（mac/Linux，或 Win 关 UI）：desktop_main 的宿主栈与 HTTP 面板
  # （mac 进 exe 的 panel_http.cpp）需要与 apxhost **同一符号面**。此前这里只链 core ——
  # mac 一编 apxdesktop 就会缺 wireless/media/service 全套符号（历史从不编，一直没暴露；
  # macos.yml 此前只 build --target apxhost，本次纳入 apxdesktop 后补齐）。
  # Win 无 UI 的罕见构建同样落此分支，链全集无害（库都在）。
  target_link_libraries(apxdesktop PRIVATE apxpc_core apxpc_discovery apxpc_sensors
    apxpc_service apxpc_wireless apxpc_media apxpc_localsend)
endif()

if(WIN32)
  set_target_properties(apxdesktop PROPERTIES WIN32_EXECUTABLE TRUE)
  target_link_options(apxdesktop PRIVATE
    "/ENTRY:mainCRTStartup"
    "/MANIFEST:EMBED,ID=1"
    "/MANIFESTINPUT:${APXPC_ROOT}/res/apx.manifest")
  target_link_libraries(apxdesktop PRIVATE ws2_32 shell32 user32 comctl32 gdi32 gdiplus setupapi cfgmgr32 advapi32)

  if(APXPC_BUILD_UI_WEBVIEW2)
    find_program(APXPC_NODE_EXE NAMES node node.exe)
    find_program(APXPC_NPM_EXE NAMES npm npm.cmd)
    set(APXPC_WEB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../web")
    set(APXPC_WEB_DIST "${APXPC_WEB_DIR}/dist")
    set(APXPC_WEB_OUT_DIR "$<TARGET_FILE_DIR:apxdesktop>/web")
    if(APXPC_NODE_EXE AND APXPC_NPM_EXE AND EXISTS "${APXPC_WEB_DIR}/package.json")
      add_custom_command(TARGET apxdesktop PRE_BUILD
        COMMAND "${APXPC_NPM_EXE}" run build
        WORKING_DIRECTORY "${APXPC_WEB_DIR}"
        COMMENT "[host] 构建 WebView2 前端（Vite + React）"
        VERBATIM)
      add_custom_command(TARGET apxdesktop POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory "${APXPC_WEB_OUT_DIR}"
        COMMAND ${CMAKE_COMMAND} -E copy_directory "${APXPC_WEB_DIST}" "${APXPC_WEB_OUT_DIR}"
        COMMENT "[host] 拷贝前端 dist → apxdesktop/web/"
        VERBATIM)
    else()
      message(WARNING "[host] WebView2 前端未构建")
    endif()
    if(DEFINED WEBVIEW2_SDK_DIR AND EXISTS "${WEBVIEW2_SDK_DIR}/build/native")
      if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        set(WV2_DLL_DIR "${WEBVIEW2_SDK_DIR}/build/native/x64")
      elseif(CMAKE_SIZEOF_VOID_P EQUAL 4)
        set(WV2_DLL_DIR "${WEBVIEW2_SDK_DIR}/build/native/x86")
      else()
        set(WV2_DLL_DIR "${WEBVIEW2_SDK_DIR}/build/native/arm64")
      endif()
      add_custom_command(TARGET apxdesktop POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
          "${WV2_DLL_DIR}/WebView2Loader.dll"
          "$<TARGET_FILE_DIR:apxdesktop>/WebView2Loader.dll"
        COMMENT "拷贝 WebView2Loader.dll"
        VERBATIM)
    endif()
  endif()

  set(APX_PAYLOAD_DIR "${CMAKE_BINARY_DIR}/payload")
  set(APX_PAYLOAD_EXE "${APX_PAYLOAD_DIR}/apxdesktop.exe")
  file(TO_NATIVE_PATH "${APX_PAYLOAD_EXE}" APX_PAYLOAD_EXE_NATIVE)
  configure_file("${APXPC_ROOT}/res/setup_payload.rc.in"
                 "${CMAKE_BINARY_DIR}/setup_payload.rc" @ONLY)
  add_custom_command(TARGET apxdesktop POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${APX_PAYLOAD_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:apxdesktop>" "${APX_PAYLOAD_EXE}"
    COMMENT "暂存安装包载荷 apxdesktop.exe")

  add_executable(apxsetup
    "${APXPC_ROOT}/src/setup_main.cpp"
    "${APXPC_ROOT}/src/ui/webview2_runtime.cpp"
    "${CMAKE_BINARY_DIR}/setup_payload.rc")
  target_include_directories(apxsetup PRIVATE "${APXPC_ROOT}/include")
  target_link_libraries(apxsetup PRIVATE shell32 ole32 shlwapi user32 advapi32 winhttp)
  set_target_properties(apxsetup PROPERTIES WIN32_EXECUTABLE TRUE)
  target_link_options(apxsetup PRIVATE
    "/ENTRY:mainCRTStartup"
    "/MANIFEST:EMBED,ID=1"
    "/MANIFESTINPUT:${APXPC_ROOT}/res/apx.manifest")
  add_dependencies(apxsetup apxdesktop)
  add_custom_command(TARGET apxsetup POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
      "$<TARGET_FILE_DIR:apxdesktop>/WebView2Loader.dll"
      "$<TARGET_FILE_DIR:apxsetup>/WebView2Loader.dll"
    COMMAND ${CMAKE_COMMAND} -E make_directory "$<TARGET_FILE_DIR:apxsetup>"
    COMMAND ${CMAKE_COMMAND} -E copy_directory
      "$<TARGET_FILE_DIR:apxdesktop>/web"
      "$<TARGET_FILE_DIR:apxsetup>/web"
    COMMENT "为 apxsetup 附带 WebView2Loader.dll + web/ 伴随文件"
    VERBATIM)
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

  add_executable(utf16_convert_test "${APXPC_ROOT}/tests/utf16_convert_test.cpp")
  if(WIN32)
    target_link_libraries(utf16_convert_test PRIVATE user32 ole32)
  endif()
  add_test(NAME utf16_convert_test COMMAND utf16_convert_test)

  add_executable(ctrl_frame_test "${APXPC_ROOT}/tests/ctrl_frame_test.cpp")
  target_link_libraries(ctrl_frame_test PRIVATE apxpc_ctrl)
  if(WIN32)
    target_link_libraries(ctrl_frame_test PRIVATE ws2_32 hid setupapi cfgmgr32)
  endif()
  add_test(NAME ctrl_frame_test COMMAND ctrl_frame_test)

  # 相对位移换算 / 拖动类型判定：纯头文件算术，不链任何库。
  # 盯的是 issue #47 第 6 条那个 macOS 注入缺陷（增量写了、落点没动）。
  add_executable(relative_move_test "${APXPC_ROOT}/tests/relative_move_test.cpp")
  target_include_directories(relative_move_test PRIVATE "${APXPC_ROOT}/include")
  add_test(NAME relative_move_test COMMAND relative_move_test)

  # 手柄免驱降级的算术（边沿/死区/缩放/滚轮限速）：同样是纯头文件算术，不链库。
  # 盯的是 issue #47 第 5 条 —— macOS/Windows 变不出真手柄，降级路径必须真能出东西。
  add_executable(gamepad_fallback_test "${APXPC_ROOT}/tests/gamepad_fallback_test.cpp")
  target_include_directories(gamepad_fallback_test PRIVATE "${APXPC_ROOT}/include")
  add_test(NAME gamepad_fallback_test COMMAND gamepad_fallback_test)

  # 本机端口占用探测：真起监听 → 探到 → 关掉 → 探不到。
  # 盯的是 v223.7 已交付的「9511 已在运行检测」（apxpc::net::tcp_probe）——这条判错的后果是面板不再起受控端，
  # 却又没人提供，手机就永远发现不了本机（静默失效），所以必须有用例钉住。
  add_executable(tcp_probe_test "${APXPC_ROOT}/tests/tcp_probe_test.cpp")
  target_link_libraries(tcp_probe_test PRIVATE apxpc_core)
  add_test(NAME tcp_probe_test COMMAND tcp_probe_test)

  # 手机模式状态帧（0x31）位布局：C++ 面板侧与 Android ModeStatus.kt 对拍的那份期望值。
  # 盯的是 issue #47 的「面板三个开关改成真实状态」——位序漂移不会崩，只会显示错模式。
  add_executable(phone_mode_test "${APXPC_ROOT}/tests/phone_mode_test.cpp")
  target_include_directories(phone_mode_test PRIVATE "${APXPC_ROOT}/include")
  add_test(NAME phone_mode_test COMMAND phone_mode_test)

  # v198 无线会话生命周期压测：worker 在 stop() 之后不得重建 beacon 线程，
  # 否则 stop() 末尾 join 收不回，进程无法退出（必须 kill）。
  add_executable(wireless_session_lifecycle_test
                 "${APXPC_ROOT}/tests/wireless_session_lifecycle_test.cpp")
  # ⚠️ 必须同时链 apxpc_wireless：WirelessSession 的实现在
  #    src/wireless/wireless_session.cpp（APXPC_WIRELESS_SRC），不在 APXPC_SERVICE_SRC 里。
  #    此前只链 apxpc_service → LNK2019 / Undefined symbols（构造/析构/startAuto/stop 四个符号）。
  #    这个错误一直存在，只是**测试从未被编译过**（CI 没跑 ctest、macOS 又把 BUILD_TESTS 关了），
  #    所以没暴露；2026-10 把 ctest 接进 CI 后两端同时红了才看见。
  target_link_libraries(wireless_session_lifecycle_test PRIVATE apxpc_service apxpc_wireless)
  if(WIN32)
    target_link_libraries(wireless_session_lifecycle_test PRIVATE ws2_32 hid setupapi cfgmgr32)
  endif()
  add_test(NAME wireless_session_lifecycle_test COMMAND wireless_session_lifecycle_test)
  # 140 轮 start/stop，每轮含 50ms 相位错开 —— 给足超时，避免 CI 慢机误判死锁
  set_tests_properties(wireless_session_lifecycle_test PROPERTIES TIMEOUT 300)

  # LocalSend 协议层：SHA-256 标准向量 + prepare-upload JSON 契约 + 文件名清洗。
  # 纯函数、不占端口不起线程，所以不会像上面那个用例那样受 CI 机器负载影响。
  add_executable(localsend_protocol_test
                 "${APXPC_ROOT}/tests/localsend_protocol_test.cpp")
  target_link_libraries(localsend_protocol_test PRIVATE apxpc_localsend)
  add_test(NAME localsend_protocol_test COMMAND localsend_protocol_test)

  # LocalSend 接收端「真开端口」的集成测试：prepare-upload → upload → 落盘 → 校验，
  # 外加重名、未知会话、缺 Content-Length、清单不合法、stop() 收得回来。
  # 只在 127.0.0.1 上收发（不依赖局域网/组播），客户端自带 5s 超时，
  # 所以出问题会是「这条用例失败」而不是「整步卡到 workflow 超时」。
  add_executable(localsend_upload_test
                 "${APXPC_ROOT}/tests/localsend_upload_test.cpp")
  target_link_libraries(localsend_upload_test PRIVATE apxpc_localsend)
  add_test(NAME localsend_upload_test COMMAND localsend_upload_test)
  set_tests_properties(localsend_upload_test PROPERTIES TIMEOUT 120)

  # LocalSend 发送端：纯函数（fileType 表 / prepare 报文契约 / 应答解析）走假对端验证
  # register → prepare → upload 三步，并验取消能真中断且通知对端 cancel。
  # 同样只在 127.0.0.1 上跑，自带超时，失败是「这条用例红」而不是整步卡死。
  add_executable(localsend_sender_test
                 "${APXPC_ROOT}/tests/localsend_sender_test.cpp")
  target_link_libraries(localsend_sender_test PRIVATE apxpc_localsend)
  add_test(NAME localsend_sender_test COMMAND localsend_sender_test)
  set_tests_properties(localsend_sender_test PROPERTIES TIMEOUT 120)
endif()

# ---------------------------------------------------------------- 安装
install(TARGETS apxhost apxdesktop RUNTIME DESTINATION bin)
if(APXPC_BUILD_SDK)
  install(TARGETS apxpc_sdk ARCHIVE DESTINATION lib)
  install(DIRECTORY "${APXPC_ROOT}/sdk/include/" DESTINATION include)
endif()

message(STATUS "[host] 平台=${CMAKE_SYSTEM_NAME} UI=${APXPC_BUILD_UI} libusb=${APXPC_USE_LIBUSB} shared=${APX_HAVE_SHARED}")