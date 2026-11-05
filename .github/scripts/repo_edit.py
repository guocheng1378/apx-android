#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 mac-ball-fix（修 macOS Build #582 链接缺符号，2026-11-06）：
  · pc/host/CMakeLists.txt —— APPLE 分支补挂 src/ball/ball_mac.mm 进 apxpc_service。
根因：M4-c(b) 写的 ball_mac.mm（定义 apxpc::ball::toggleBallFan）从未挂进任何
target；desktop_main 的 ballhotkey::onBallHot 引用它 → mac arm64 链接
Undefined symbols。安全性已本地核实：ball_win32.cpp 整文件 _WIN32 守卫（mac 空
TU 不会重复定义）；ball_mac.mm 无手动内存管理（ARC 全开可编）；AppKit 已链接。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import os
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


CMAKE = "pc/host/CMakeLists.txt"
EDIT_OLD = T('  list(APPEND APXPC_SERVICE_SRC "${APXPC_ROOT}/src/tray/tray_mac.mm")',
             "endif()")
EDIT_NEW = T('  list(APPEND APXPC_SERVICE_SRC "${APXPC_ROOT}/src/tray/tray_mac.mm")',
             "  # mac 球壳（M4-c(b) 的 src/ball/ball_mac.mm）此前从未挂进任何 target：desktop_main 的",
             "  # ballhotkey::onBallHot 引用 apxpc::ball::toggleBallFan()，mac arm64 链接即缺符号",
             "  # （#582：Undefined symbols for architecture arm64）。补挂进 service 静态库，与 tray 同口径：",
             "  # ball_win32.cpp 整文件 _WIN32 守卫（mac 空 TU，不会重复定义）；ball_mac.mm 无手动内存管理，",
             "  # ARC 已按 $<COMPILE_LANGUAGE:OBJCXX> 全开；AppKit/Foundation 已在下面链接。",
             '  list(APPEND APXPC_SERVICE_SRC "${APXPC_ROOT}/src/ball/ball_mac.mm")',
             "endif()")


def main() -> int:
    if not os.path.exists("pc/host/src/ball/ball_mac.mm"):
        print("PROBE MISSING: pc/host/src/ball/ball_mac.mm 不存在，拒绝补挂")
        return 1
    try:
        with open(CMAKE, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print(f"open failed: {e}")
        return 1
    n = text.count(EDIT_OLD)
    if n != 1:
        print(f"VALIDATION FAILED：{CMAKE} edit#1: count={n} expect=1")
        print("---anchor---")
        print(EDIT_OLD)
        return 1
    if "ball_mac.mm" in text and "list(APPEND APXPC_SERVICE_SRC" in text and \
            text.count("ball_mac.mm") > 1:
        print("already wired (ball_mac.mm referenced), nothing to do")
        return 0
    if "${APXPC_ROOT}/src/ball/ball_mac.mm" in text:
        print("already wired, nothing to do")
        return 0
    text = text.replace(EDIT_OLD, EDIT_NEW)
    with open(CMAKE, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"OK {CMAKE}: 1 edit(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
