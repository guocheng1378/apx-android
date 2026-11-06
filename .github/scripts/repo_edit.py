#!/usr/bin/env python3
"""repo-edit：锚点替换编辑器（两阶段：先全量校验命中次数，全过才写盘）。

批 9（2026-11-06）：Build #1271 实测 —— 重入依旧，wil 的 GetProperties 守卫
第二次成功跳过，但 ViGEmClientSrc 的同款守卫没兜住（:77 直接 Populate → 2043
FATAL 再现）。变量/状态机守卫在这个 content 上不可靠，改用磁盘事实判断：
_deps/vigemclientsrc-src/CMakeLists.txt 存在 = 已 populate → 跳过并回填
SOURCE_DIR/POPULATED 变量；不存在才走直接 Populate。其余结构不动。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

EDITS = [
    ("pc/host/CMakeLists.txt", [
        ('''  # 重入安全同上（Build #1269）：幂等守卫，重入时跳过重复 Populate/add_subdirectory。
  FetchContent_GetProperties(ViGEmClientSrc)
  if(NOT ViGEmClientSrc_POPULATED)
    FetchContent_Populate(ViGEmClientSrc)
  endif()''',
         '''  # 重入自愈（Build #1269→#1271 两轮实测）：变量/GetProperties 守卫对本 content
  # 在重入场景没兜住（wil 兜住了、它没兜住），改用磁盘事实：落盘即视为已 populate，
  # 绝不再走直接 Populate（第二次执行 = CMP0169 2043 FATAL，CI 日志实锤两轮）。
  if(EXISTS "${CMAKE_BINARY_DIR}/_deps/vigemclientsrc-src/CMakeLists.txt")
    set(ViGEmClientSrc_SOURCE_DIR "${CMAKE_BINARY_DIR}/_deps/vigemclientsrc-src")
    set(ViGEmClientSrc_POPULATED TRUE)
  else()
    FetchContent_Populate(ViGEmClientSrc)
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
