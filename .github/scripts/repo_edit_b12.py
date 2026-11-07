#!/usr/bin/env python3
"""repo_edit_b12.py —— b18（键盘页上移，2026-11-08「键盘页可以再往上走走」）。

ApShellActivity 横屏 ApKeyboardPage 顶部让位 40 -> 24dp（注释里 40-24 论证过的安全值）：
修饰键排顶 = 24 + 30（页签）= 54dp，与低栏下沿 56dp 叠 2dp（低栏透明悬浮，可穿）；
省出的 16dp 摊给键位区，每行 +2.7dp。竖屏 topGap 不动（真实顶栏让位）。"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
SHELL = "android/app/src/main/java/com/allperiph/ui/compose/ApShellActivity.kt"

EDITS = [(SHELL, [(
    "topBar = if (landscape) 40.dp else topGap,",
    "topBar = if (landscape) 24.dp else topGap,",
    1,
)])]


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
