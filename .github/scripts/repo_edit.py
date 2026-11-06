#!/usr/bin/env python3
"""repo-edit：单文件锚点替换（两阶段：先全量校验命中次数，全过才写盘）。

批5尾巴 v2（2026-11-06）：ROADMAP §2.4 补「浏览器面板摄像头」行。
 v1 失败原因（Run #69）：把要插入的新行当成了锚点（文件里不存在 → count=0），
 两阶段校验拦截、零写盘。v2 改为在真实存在的「副屏」行后插入。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

EDITS = [
    ("docs/ROADMAP.md", [
        ('''| 副屏 | ❌ | 虚拟显示器仅 Windows；`screenPush=nullptr`，面板显示未初始化形态 + `screen.toggle` 明确拒绝 |''',
         '''| 副屏 | ❌ | 虚拟显示器仅 Windows；`screenPush=nullptr`，面板显示未初始化形态 + `screen.toggle` 明确拒绝 |
| 浏览器面板摄像头 | ✅ | `panel_http.cpp` + `bridge.ts`：0x10「camera」拉起手机采集（跨平台，与 §2.3 同通道）；收流计数/尺寸/停滞实时上卡片；**无原生预览窗与虚拟摄像头**（camera_view 仅 Windows，CMake `if(WIN32)`，诚实标注） |''', 1),
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
