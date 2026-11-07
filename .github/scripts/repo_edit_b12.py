#!/usr/bin/env python3
"""repo_edit_b12.py —— b19（2026-11-08「ctrl 那一排不要了，下面有」）。

QuickLayout 顶部 sticky 修饰排（Ctrl/Alt/Shift/Win）横屏不画：FULL 底排自带
Ctrl/Alt/Shift（KeyGridLayout 已把修饰 label 转 toggleSticky，功能不变）。
竖屏 letterRows 保留原修饰排（竖屏无 FULL）。锚为纯 ASCII 结构行，count=1。"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
KB = "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt"

OLD = '''        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HidKeys.MOD_LABELS.forEach { (text, bit) ->
                ModKey(
                    label = text,
                    active = sticky and bit != 0,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    dens = dens,
                    keycap = keycap,
                    fx = fx,
                ) {
                    HidKeys.toggleSticky(bit)
                    sticky = flipSticky(sticky, bit)
                }
            }
        }'''

NEW = '''        // b19「ctrl 那一排不要了，下面有」：横屏不画 sticky 修饰排（FULL 底排自带 Ctrl/Alt/Shift），竖屏保留
        if (!wide) Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HidKeys.MOD_LABELS.forEach { (text, bit) ->
                ModKey(
                    label = text,
                    active = sticky and bit != 0,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    dens = dens,
                    keycap = keycap,
                    fx = fx,
                ) {
                    HidKeys.toggleSticky(bit)
                    sticky = flipSticky(sticky, bit)
                }
            }
        }'''

EDITS = [(KB, [(OLD, NEW, 1)])]


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
