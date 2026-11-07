#!/usr/bin/env python3
"""repo_edit_b12.py —— b17fix4（修 Build #1383 的两处 Configuration 未定义）。

b17 两处横屏判断包名写错：`android.content.Configuration` 不存在，
Orientation 常量在 `android.content.res.Configuration`（res 子包）下：
  KeyboardPage.kt   QuickLayout  wide 定义处
  TouchpadActivity  quickKeyboard 横屏分支处
修法：`android.content.Configuration.` -> `android.content.res.Configuration.`（纯 ASCII 锚）。"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
BAD = "android.content.Configuration.ORIENTATION_LANDSCAPE"
GOOD = "android.content.res.Configuration.ORIENTATION_LANDSCAPE"

EDITS = [
    ("android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt", [(BAD, GOOD, 1)]),
    ("android/app/src/main/java/com/allperiph/touchpad/TouchpadActivity.kt", [(BAD, GOOD, 1)]),
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
