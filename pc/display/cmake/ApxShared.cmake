# ApxShared.cmake
#
# 作用：把 core-proto 产出的 shared/（C++17 公共协议库）接入 pc/display。
# - 找到 shared/include/apx/frame.h -> 直接编译 shared/src/*.cpp 并链接（唯一真源）
# - 找不到 -> FATAL_ERROR（协议唯一真源在 shared/，不允许任何替身副本；
#   详见 docs/REQ-五路回报.md §9 与 reports/MAIN-INTERVENTIONS.md 的协议双源裁决）
#
# 对外提供：INTERFACE/STATIC 目标 apx_shared（include 目录 = .../shared/include）

if(TARGET apx_shared)
  return()
endif()

# 本仓库的 shared/ 位于**仓库根**（pc/ 之外），因此向上探测含 shared/include/apx/frame.h 的目录。
if(NOT DEFINED APX_ROOT)
  set(APX_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../.." CACHE PATH "工程根目录（含 shared/）")
endif()
get_filename_component(APX_ROOT "${APX_ROOT}" ABSOLUTE)

set(_apx_candidates
  "${APX_ROOT}"
  "${CMAKE_CURRENT_LIST_DIR}/../../.."   # pc/display/cmake -> 仓库根
  "${CMAKE_CURRENT_LIST_DIR}/../.."      # pc/display/cmake -> pc
  "${CMAKE_CURRENT_LIST_DIR}/.."         # pc/display/cmake -> pc/display
  "${CMAKE_SOURCE_DIR}/.."
  "${CMAKE_SOURCE_DIR}"
)

set(APX_SHARED_INC "")
foreach(_c ${_apx_candidates})
  if(EXISTS "${_c}/shared/include/apx/frame.h")
    get_filename_component(_c "${_c}" ABSOLUTE)
    set(APX_SHARED_INC "${_c}/shared/include")
    set(APX_ROOT "${_c}")
    break()
  endif()
endforeach()

message(STATUS "[apx] 工程根: ${APX_ROOT}")

if(APX_SHARED_INC)
  set(APX_HAVE_SHARED ON)
  file(GLOB APX_SHARED_SRCS CONFIGURE_DEPENDS "${APX_ROOT}/shared/src/*.cpp")
  if(APX_SHARED_SRCS)
    add_library(apx_shared STATIC ${APX_SHARED_SRCS})
  else()
    add_library(apx_shared INTERFACE)
  endif()
  target_include_directories(apx_shared PUBLIC "${APX_SHARED_INC}")
  target_compile_features(apx_shared PUBLIC cxx_std_17)
  message(STATUS "[apx] 使用 shared/ 协议库 (${APX_ROOT}/shared)")
else()
  message(FATAL_ERROR
    "[apx] 未找到 shared/include/apx/frame.h —— 协议唯一真源缺失。\n"
    "       pc/display 不再提供 compat 替身（历史副本已删除，避免布局漂移）。\n"
    "       请在仓库根目录（含 shared/ 的目录）下重新配置 CMake。")
endif()
