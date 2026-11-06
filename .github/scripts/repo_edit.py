#!/usr/bin/env python3
"""repo-edit：锚点替换编辑器（两阶段：先全量校验命中次数，全过才写盘）。

批 8（2026-11-06）：Build #1269 pc job Configure 死因修复 —— CMake 中途重入
（上游 project()+GNUInstallDirs cache 写入触发），第二次直接 FetchContent_Populate(wil)
撞 CMP0169 FATAL "Content already populated"。
  · wil 段 + Vigem 段改 GetProperties + if(NOT *_POPULATED) 幂等守卫（重入免疫）
  · 顺手纠正 DLL 逻辑：上游硬编码 STATIC，BUILD_SHARED_LIBS 无效；
    改用上游专有 option ViGEmClient_DLL=ON（CMP0077 NEW 可用普通变量压过）
    → 生成 ViGEmClientShared（OUTPUT_NAME=ViGEmClient、自带 setupapi 链接）；
    VIGEM_DYNAMIC/VIGEM_EXPORTS 必须补（Client.h 实测；不补则 DLL 零导出）
  · add_subdirectory 加 if(NOT TARGET) 守卫防重入双加
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

EDITS = [
    ("pc/host/CMakeLists.txt", [
        ('''  FetchContent_Declare(wil
    GIT_REPOSITORY https://github.com/microsoft/wil.git
    GIT_TAG        v1.0.240803.1
    GIT_SHALLOW    TRUE
  )
  FetchContent_Populate(wil)''',
         '''  FetchContent_Declare(wil
    GIT_REPOSITORY https://github.com/microsoft/wil.git
    GIT_TAG        v1.0.240803.1
    GIT_SHALLOW    TRUE
  )
  # 重入安全（Build #1269 教训）：CMake 中途可整文件重入，第二次直接 Populate =
  # CMP0169 FATAL "Content already populated"。GetProperties 幂等，重入无害。
  FetchContent_GetProperties(wil)
  if(NOT wil_POPULATED)
    FetchContent_Populate(wil)
  endif()''', 1),
        ('''  FetchContent_Populate(ViGEmClientSrc)
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
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/Release")''',
         '''  # 重入安全同上（Build #1269）：幂等守卫，重入时跳过重复 Populate/add_subdirectory。
  FetchContent_GetProperties(ViGEmClientSrc)
  if(NOT ViGEmClientSrc_POPULATED)
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
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/Release")''', 1),
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
