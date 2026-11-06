#!/usr/bin/env python3
"""repo-edit：锚点替换编辑器（两阶段：先全量校验命中次数，全过才写盘）。

批 11（2026-11-06）：Build #1275 实测 —— 重入已消灭（Configuring done 34.6s
一遍跑通），Generate 阶段挂在一个空变量：deprecated 单参 FetchContent_Populate
跑完未回填 ViGEmClientSrc_SOURCE_DIR（clone 成功），add_library 拼出
"/src/ViGEmClient.cpp"。修：不再赌回填，两分支后统一硬设 SOURCE_DIR
（FetchContent 默认路径 ${CMAKE_BINARY_DIR}/_deps/<name小写>-src 确定，
CI 日志与报错路径均已实证）。
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
  endif()''',
         '''  if(EXISTS "${CMAKE_BINARY_DIR}/_deps/vigemclientsrc-src/CMakeLists.txt")
    set(ViGEmClientSrc_POPULATED TRUE)
  else()
    FetchContent_Populate(ViGEmClientSrc)
  endif()
  # 源码目录不赌 deprecated 单参 Populate 的变量回填（Build #1275 实测拿到空串，
  # add_library 拼成 "/src/ViGEmClient.cpp"）——FetchContent 默认落盘路径是确定的
  # ${CMAKE_BINARY_DIR}/_deps/<name小写>-src（CI 日志与报错路径双实证），两分支统一硬设。
  set(ViGEmClientSrc_SOURCE_DIR "${CMAKE_BINARY_DIR}/_deps/vigemclientsrc-src")''', 1),
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
