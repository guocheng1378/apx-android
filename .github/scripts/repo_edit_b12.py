#!/usr/bin/env python3
"""repo_edit_b12.py —— b22（2026-11-08「对齐现实键盘……小键盘，其他的可以在页签调」）。

横屏 FULL 升 84 键 16u 形态：右列 Del/Home/PgUp/↑/PgDn + 底排 ←↓→（全部用常量区
现成 usage）。F 区组缝 / PrtSc 行 / Menu 键不进主键盘（归页签）。底排 15→13u 给
右列 3 箭头腾位：Ctrl1.5 Win1 Alt1 Space6 Alt1 Win1 Ctrl1.5 = 13，总 16u。
锚全 ASCII（fn(12)/2f/0x31/ENTER/2.75f/b20底排段），NEW 含箭头符号仅写入不匹配。"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
KL = "android/app/src/main/java/com/allperiph/ui/KeyLayouts.kt"

EDITS = [(KL, [
    # 行0 右端 Del（fn(12) 是 FULL 行0 独有尾标；FN 定义是 fn(it) 不匹配）
    ("fn(12)),\n        ),", "fn(12)), Key(\"Del\", DELETE),\n        ),", 1),
    # 行1 右端 Home（2f)+ 换行+8空格闭括号：数字行独有）
    ("2f),\n        ),", "2f), Key(\"Home\", HOME),\n        ),", 1),
    # 行2 右端 PgUp（0x31 只在 \\| 键）
    ("0x31, 1.5f),\n        ),", "0x31, 1.5f), Key(\"PgUp\", PGUP),\n        ),", 1),
    # 行3 右端 ↑（ENTER, 2.25f 只在 Caps 行尾）
    ("ENTER, 2.25f),\n        ),", "ENTER, 2.25f), Key(\"↑\", UP),\n        ),", 1),
    # 行4 右端 PgDn（Shift 2.75f 只在行4 右 Shift）
    ("Key(\"Shift\", 0, 2.75f),\n        ),", "Key(\"Shift\", 0, 2.75f), Key(\"PgDn\", PGDN),\n        ),", 1),
    # 行5 底排 15→13u + 右列 ←↓→（锚 = b20 的 6f 底排段）
    (
        """        listOf(
            Key("Ctrl", 0, 1.5f), Key("Win", 0, 1.5f), Key("Alt", 0, 1.5f), Key("Space", SPACE, 6f),
            Key("Alt", 0, 1.5f), Key("Win", 0, 1.5f), Key("Ctrl", 0, 1.5f),
        ),""",
        """        listOf(
            Key("Ctrl", 0, 1.5f), Key("Win", 0, 1f), Key("Alt", 0, 1f), Key("Space", SPACE, 6f),
            Key("Alt", 0, 1f), Key("Win", 0, 1f), Key("Ctrl", 0, 1.5f),
            Key("←", LEFT), Key("↓", DOWN), Key("→", RIGHT),
        ),""",
        1,
    ),
])]


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
                print("  old[0:90]=" + old[:90].replace("\n", "\\n"))
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
