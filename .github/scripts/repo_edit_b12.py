#!/usr/bin/env python3
"""repo_edit_b12.py —— b20（2026-11-08「win键呢」）。

b19 删顶部修饰排把 Win 唯一入口带走了。FULL 底排补回两个 Win，15u 重排：
Ctrl1.5 Win1.5 Alt1.5 Space6 Alt1.5 Win1.5 Ctrl1.5 = 15u。
label=\"Win\" 属 MOD_LABELS → KeyGridLayout 转 toggleSticky（与 Ctrl/Alt 同路径）。"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
KL = "android/app/src/main/java/com/allperiph/ui/KeyLayouts.kt"

OLD = '''        listOf(
            Key("Ctrl", 0, 1.5f), Key("Alt", 0, 1.5f), Key("Space", SPACE, 9f),
            Key("Alt", 0, 1.5f), Key("Ctrl", 0, 1.5f),
        ),
    )'''

NEW = '''        listOf(
            Key("Ctrl", 0, 1.5f), Key("Win", 0, 1.5f), Key("Alt", 0, 1.5f), Key("Space", SPACE, 6f),
            Key("Alt", 0, 1.5f), Key("Win", 0, 1.5f), Key("Ctrl", 0, 1.5f),
        ),
    )'''

EDITS = [(KL, [(OLD, NEW, 1)])]


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
        print("".join(difflib.unified_diff(
            text.splitlines(keepends=True), new.splitlines(keepends=True),
            fromfile="a/" + rel, tofile="b/" + rel, n=3)))
    print("OK: wrote " + str(len(plan)) + " file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
