cmake_minimum_required(VERSION 3.20)
project(allperiph_tools VERSION 0.1.0 LANGUAGES CXX)

# ============================================================================
# L5 · 验证工具三件套（REQ 任务书）
#
#   apx_sensor_dump  —— MS1 验收：系统是否已把手机传感器当作本机传感器
#   apx_gps_probe    —— MS3 验收：CDC ACM 的 COM 口能否读到 NMEA
#   apx_link_bench   —— USB bulk 吞吐与延迟，确认是否真跑在 SuperSpeed
#
# 本 CMake 复用 pc/host 的库（APXPC_BUILD_TOOLS=ON），不重复实现底层。
# 单独构建：
#   cmake -S pc/tools -B pc/tools/build -G "Visual Studio 17 2022" -A x64
# ============================================================================

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "构建类型" FORCE)
endif()

if(MSVC)
  add_compile_options(/utf-8 /EHsc /W3 /permissive- /Zc:__cplusplus)
  add_compile_definitions(_CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
endif()

# ------------------------------------------------------- 复用 pc/host 的库 ----
# 让 pc/host 把它的库导出出来，而不是在这里重复编译一份源码。
set(APXPC_BUILD_TOOLS ON CACHE BOOL "" FORCE)
set(APXPC_BUILD_UI    OFF CACHE BOOL "" FORCE)   # 工具不依赖图形面板
set(APXPC_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../host" "${CMAKE_CURRENT_BINARY_DIR}/host")

# ---------------------------------------------------------------- 工具目标 ----

add_executable(apx_sensor_dump "${CMAKE_CURRENT_SOURCE_DIR}/sensor_dump/main.cpp")
target_link_libraries(apx_sensor_dump PRIVATE ${APXPC_HOST_LIBS})

# 其余两件套的源码尚缺（见本目录 README「当前状态」），存在时才加入目标，
# 避免因缺失文件导致整个 tools 构建失败。

set(_gps_src "${CMAKE_CURRENT_SOURCE_DIR}/gps_probe/main.cpp")
if(EXISTS "${_gps_src}")
  add_executable(apx_gps_probe "${_gps_src}")
  target_link_libraries(apx_gps_probe PRIVATE ${APXPC_HOST_LIBS})
else()
  message(STATUS "[tools] 跳过 apx_gps_probe（${_gps_src} 尚未交付）")
endif()

set(_bench_src "${CMAKE_CURRENT_SOURCE_DIR}/link_bench/main.cpp")
if(EXISTS "${_bench_src}")
  add_executable(apx_link_bench "${_bench_src}")
  target_link_libraries(apx_link_bench PRIVATE ${APXPC_HOST_LIBS})
else()
  message(STATUS "[tools] 跳过 apx_link_bench（${_bench_src} 尚未交付）")
endif()

install(TARGETS apx_sensor_dump RUNTIME DESTINATION bin)

message(STATUS "[tools] 构建目标：apx_sensor_dump（MS1 验收）")
