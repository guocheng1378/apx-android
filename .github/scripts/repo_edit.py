#!/usr/bin/env python3
"""repo-edit：锚点替换编辑器（两阶段：先全量校验命中次数，全过才写盘）。

批 10（2026-11-06）：Build #1272 实测 —— 两个 populate 守卫（wil 变量 / vigem
磁盘）在重入下全部生效，死点收敛到 add_subdirectory：binary-dir 占用记录跨重入
保留、TARGET 被清，第二次 add 必死。且上游 tag 的 CMakeLists 是极简版
（Project+add_library，无 ViGEmClient_DLL——批 8 找错了目标）。故弃用 add_subdirectory：
  · 上游 project()/option()/GNUInstallDirs 的 cache 写入即重入触发器 → 删之重入大概率消失
  · 改本作用域直接 add_library(SHARED)：无 binary-dir 状态，if(NOT TARGET) 自愈
  · .rc 仅版本资源，排除可免 enable_language(RC)
  · 磁盘守卫取源码块保留（两轮实测有效）；目标名 ViGEmClient 与 apxhost 依赖/artifact 对齐
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

EDITS = [
    ("pc/host/CMakeLists.txt", [
        ('''  if(EXISTS "${CMAKE_BINARY_DIR}/_deps/vigemclientsrc-src/CMakeLists.txt")
    set(ViGEmClientSrc_SOURCE_DIR "${CMAKE_BINARY_DIR}/_deps/vigemclientsrc-src")
    set(ViGEmClientSrc_POPULATED TRUE)
  else()
    FetchContent_Populate(ViGEmClientSrc)
  endif()
  if(NOT TARGET ViGEmClientShared)
    # 上游硬编码 add_library(... STATIC)，BUILD_SHARED_LIBS 对它无效（Build #1269
    # 实测推断）；出 DLL 的专有开关 = option ViGEmClient_DLL（默认 OFF）。
    # 上游 cmake_minimum_required 3.20.2 → CMP0077 NEW：普通变量可压过 option() 默认值。
    set(ViGEmClient_DLL ON)
    add_subdirectory("${ViGEmClientSrc_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/vigemclient_src")
  endif()
  if(NOT TARGET ViGEmClientShared)
    message(FATAL_ERROR "ViGEmClientShared 未生成——上游 option 名/结构可能已变，核对 GIT_TAG")
  endif()
  # Client.h：VIGEM_DYNAMIC 才走 dllexport/dllimport 分支，VIGEM_EXPORTS 决定导出方向。
  # 实测上游 shared 分支也不定义这两个宏（默认路径给静态），不补则 DLL 零导出，
  # 运行期 LoadLibrary 成功但 GetProcAddress 全 NULL。setupapi：上游已链，此处幂等保底。
  target_compile_definitions(ViGEmClientShared PRIVATE VIGEM_DYNAMIC VIGEM_EXPORTS)
  target_link_libraries(ViGEmClientShared PRIVATE setupapi)
  set_target_properties(ViGEmClientShared PROPERTIES
    OUTPUT_NAME "ViGEmClient"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/Release"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/Release")''',
         '''  if(EXISTS "${CMAKE_BINARY_DIR}/_deps/vigemclientsrc-src/CMakeLists.txt")
    set(ViGEmClientSrc_SOURCE_DIR "${CMAKE_BINARY_DIR}/_deps/vigemclientsrc-src")
    set(ViGEmClientSrc_POPULATED TRUE)
  else()
    FetchContent_Populate(ViGEmClientSrc)
  endif()
  # 不再 add_subdirectory 上游（Build #1269→#1272 三轮实锤）：上游 project()/option()/
  # GNUInstallDirs 的 cache 写入会触发 CMake 途中重入，而重入清 TARGET 却保留
  # binary-dir 占用记录 → 第二次 add_subdirectory 必死（:88 "already used"）。改为
  # 本作用域直接建 DLL：add_library 无 binary-dir 状态，随文件重执行 if(NOT TARGET)
  # 即自愈；也顺带拆掉了重入触发器。上游 tag 的 CMakeLists 是极简版
  # （Project + add_library EXCLUDE_FROM_ALL），源码三编译单元；.rc 仅版本资源，
  # 排除掉可免 enable_language(RC)。Client.h：VIGEM_DYNAMIC 才走 dllexport/dllimport，
  # VIGEM_EXPORTS 定方向，不补则 DLL 零导出（GetProcAddress 全 NULL）。
  if(NOT TARGET ViGEmClient)
    add_library(ViGEmClient SHARED
      "${ViGEmClientSrc_SOURCE_DIR}/src/ViGEmClient.cpp")
    target_include_directories(ViGEmClient PUBLIC "${ViGEmClientSrc_SOURCE_DIR}/include")
    target_compile_definitions(ViGEmClient PRIVATE VIGEM_DYNAMIC VIGEM_EXPORTS)
    target_link_libraries(ViGEmClient PRIVATE setupapi ole32)
    set_target_properties(ViGEmClient PROPERTIES
      OUTPUT_NAME "ViGEmClient"
      RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/Release"
      RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/Release")
  endif()''', 1),
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
