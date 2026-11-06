#!/usr/bin/env python3
"""repo-edit：锚点替换编辑器（两阶段：先全量校验命中次数，全过才写盘）。

批 7（2026-11-06）：手柄 TCP 出口收口（阶段二借力第三方的最后一步：让 ViGEmBus 真手柄对用户可达）。
侦察结论：0x07 收发/Win ViGEm 动态加载/Linux uinput 均已落地（PR #24），欠的是：
  · DLL 未入包 → LoadLibrary 永远失败（功能假活）；② ROADMAP 三处仍写「尚未接入」；
  · 降级日志还说「本平台无真手柄注入能力」（过期假话）。
  · CMake — FetchContent 上游源码（tag v1.21.222.0，MIT）编成 DLL：VIGEM_DYNAMIC+
    VIGEM_EXPORTS 补导出宏、setupapi 补链（上游没链）、输出到 build_host/Release；
    apxhost 加构建依赖（不链接，保持 GetProcAddress 零链接依赖设计）
  · build.yml — artifact 清单 + portable zip 各加 ViGEmClient.dll（Copy-Item 缺文件
    会直接报错 → CI 自验）
  · AllPeriph.iss — 安装器 Files 段加 DLL
  · input_injector_win.cpp — 降级日志改为「未检测到驱动/DLL，装驱动后重启面板即启用」
  · ROADMAP — §2.2 手柄条 + 两处「见§2.2尚未接入」引用 翻新为已接入现状
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

EDITS = [
    ("pc/host/CMakeLists.txt", [
        ('''endif()
set(CMAKE_CXX_STANDARD 20)''',
         '''endif()

# ---- ViGEmClient.dll（Windows 真手柄，阶段二「借力第三方」的运行时配套）----
# input_injector_win.cpp 以 LoadLibrary/GetProcAddress 动态加载本 DLL（链接期零依赖，
# 设计不变）；这里只负责按上游源码把它编成 DLL 并落到 exe 同目录，随安装器/portable 分发。
# 上游 = nefarius/ViGEmClient，MIT 许可，版权声明见其 include/ViGEm/Client.h。
if(WIN32)
  include(FetchContent)
  FetchContent_Declare(ViGEmClientSrc
    GIT_REPOSITORY https://github.com/nefarius/ViGEmClient.git
    GIT_TAG        v1.21.222.0
    GIT_SHALLOW    TRUE
  )
  FetchContent_Populate(ViGEmClientSrc)
  set(BUILD_SHARED_LIBS ON)   # 仅本作用域：上游 add_library 未写 SHARED → 按此变量出 DLL
  add_subdirectory("${ViGEmClientSrc_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/vigemclient_src")
  unset(BUILD_SHARED_LIBS)
  if(NOT TARGET ViGEmClient)
    message(FATAL_ERROR "ViGEmClient target 未生成——上游 CMakeLists 结构可能已变，核对 GIT_TAG")
  endif()
  # Client.h：VIGEM_DYNAMIC 才走 dllexport/dllimport 分支，VIGEM_EXPORTS 决定导出方向；
  # 上游 CMake 两者都没定义（给静态用），出 DLL 必须补。setupapi 是上游漏链（SetupDi*）。
  target_compile_definitions(ViGEmClient PRIVATE VIGEM_DYNAMIC VIGEM_EXPORTS)
  target_link_libraries(ViGEmClient PRIVATE setupapi)
  set_target_properties(ViGEmClient PROPERTIES
    OUTPUT_NAME "ViGEmClient"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/Release"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/Release")
endif()
set(CMAKE_CXX_STANDARD 20)''', 1),
        ('''target_link_libraries(apxhost PRIVATE apxpc_core apxpc_discovery apxpc_sensors apxpc_service apxpc_wireless apxpc_media apxpc_localsend)''',
         '''target_link_libraries(apxhost PRIVATE apxpc_core apxpc_discovery apxpc_sensors apxpc_service apxpc_wireless apxpc_media apxpc_localsend)
if(WIN32 AND TARGET ViGEmClient)
  # EXCLUDE_FROM_ALL：靠构建依赖把 DLL 编出来（只编不链，保持动态加载零链接依赖）
  add_dependencies(apxhost ViGEmClient)
endif()''', 1),
    ]),
    (".github/workflows/build.yml", [
        ('''            build_host/Release/WebView2Loader.dll
            build_host/Release/web/''',
         '''            build_host/Release/WebView2Loader.dll
            build_host/Release/ViGEmClient.dll
            build_host/Release/web/''', 1),
        ('''          Copy-Item dist/pc-exes-webview2/WebView2Loader.dll portable/''',
         '''          Copy-Item dist/pc-exes-webview2/WebView2Loader.dll portable/
          Copy-Item dist/pc-exes-webview2/ViGEmClient.dll portable/''', 1),
    ]),
    ("scripts/installer/AllPeriph.iss", [
        ('''Source: "{#ArtifactsDir}\\WebView2Loader.dll"; DestDir: "{app}";     Flags: ignoreversion''',
         '''Source: "{#ArtifactsDir}\\WebView2Loader.dll"; DestDir: "{app}";     Flags: ignoreversion
Source: "{#ArtifactsDir}\\ViGEmClient.dll";  DestDir: "{app}";     Flags: ignoreversion''', 1),
    ]),
    ("pc/host/src/wireless/input_injector_win.cpp", [
        ('''            std::fprintf(stderr, "[AllPeriph] 手柄经网络接入：本平台无真手柄注入能力，已降级为"
                                 "「按钮→键盘 + 左摇杆→鼠标 + 右摇杆→滚动」；"
                                 "要真手柄需 ViGEmBus 之类的虚拟 HID 驱动\\n");''',
         '''            std::fprintf(stderr, "[AllPeriph] 手柄经网络接入：未检测到 ViGEmBus 驱动或 ViGEmClient.dll，"
                                 "本帧起降级为「按钮→键盘 + 左摇杆→鼠标 + 右摇杆→滚动」；"
                                 "安装 ViGEmBus 驱动（ViGEmClient.dll 已随安装包/便携包分发）后重启面板即启用真手柄\\n");''', 1),
    ]),
    ("docs/ROADMAP.md", [
        ('''**尚未接入**：手柄（`SendInput` 无法模拟游戏手柄，需 ViGEmBus 等第三方驱动，
归入架构 §2.2「阶段二 借力第三方」）。''',
         '''**手柄真注入已接入（2026-11-06 核对源码，PR #24 落地）**：0x07 手柄帧 →
Windows `input_injector_win.cpp` 运行时探测 `ViGEmClient.dll`（2026-11-06 起随包分发）
+ ViGEmBus 驱动注入虚拟 X360，缺任一则降级「按钮→键盘 + 摇杆→鼠标/滚动」并如实记日志；
Linux 走 `/dev/uinput` 真手柄；macOS 无 CGEvent 手柄通道仅降级（AMFI 限制见 `gamepad_fallback.hpp`）。''', 1),
        ('''   剩真机配对验证；TCP 出口受 ViGEmBus 限制（见 §2.2「尚未接入」）。''',
         '''   剩真机配对验证；TCP 出口已接入（ViGEmBus 运行时探测 + 降级，见 §2.2 手柄条）。''', 1),
        ('''> native 蓝牙描述符均已存在，本轮把键盘接进蓝牙分支。手柄仍无 TCP 出口（发送面受控目标）：
> 手柄（`ui/GamepadController`）尚无 TCP 出口 —— 因 `SendInput` 无法模拟手柄，
> 需第三方虚拟手柄驱动，见 §2.2「尚未接入」。''',
         '''> native 蓝牙描述符均已存在，本轮把键盘接进蓝牙分支。手柄 TCP 出口后于 PR #24 落地
> （2026-11-06 核对）：`ui/GamepadController` → 受控目标 0x07 → Windows ViGEmBus 虚拟
> X360（驱动缺失则键盘降级并如实记日志，DLL 随包分发，见 §2.2 手柄条）。''', 1),
    ]),
]


def main():
    plan = []
    failed = False
    for rel, reps in EDITS:
        full = os.path.join(ROOT, rel)
        if not os.path.exists(full):
            print("MISSING_FILE " + rel)
            failed = True
            continue
        with open(full, "r", encoding="utf-8") as f:
            text = f.read()
        new = text
        for old, repl, want in reps:
            got = new.count(old)
            if got != want:
                print("ANCHOR_FAIL " + rel + " count=" + str(got) + " want=" + str(want))
                print("  old[0:80]=" + old[:80].replace("\n", "\\n"))
                failed = True
            else:
                new = new.replace(old, repl)
        if new != text:
            plan.append((rel, text, new))
    if failed:
        print("VALIDATE_FAILED: 有锚点未命中，不写盘。")
        return 1
    if not plan:
        print("NO_CHANGES: 所有编辑的期望文本均已存在。")
        return 0
    for rel, text, new in plan:
        full = os.path.join(ROOT, rel)
        with open(full, "w", encoding="utf-8") as f:
            f.write(new)
        print("WROTE " + rel)
        diff = "".join(difflib.unified_diff(
            text.splitlines(keepends=True), new.splitlines(keepends=True),
            fromfile="a/" + rel, tofile="b/" + rel, n=3))
        print(diff)
    print("OK: wrote " + str(len(plan)) + " file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
