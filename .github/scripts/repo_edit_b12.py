#!/usr/bin/env python3
"""repo_edit_b12.py —— b17fix（复用 repo-edit-b12.yml 通道）。

b17（e7abc734）编译失败修复：KeyLayouts.kt 122 区两条错 ——
  122:40 Unresolved 'plus' on receiver of type 'KeyLayouts.Key'
  122:9  Unresolved 'listOf' on receiver of type 'String'
两条错指向同一根因：119-121（Caps 拼接链）某处引号/括号未闭合，把 122 行吞进
字符串，导致 122 的 listOf 被当 String 后缀、listOf(Key(...)) 参数位出现 Key + ...。

修法（根除嫌疑，不留跨行拼接）：
  1. FULL 四条 `listOf(...) + map + listOf(...)` 跨行拼接链全部改**纯手写单个
     listOf**（字母 usage 手写常量表，A0x04..Z0x1D 连续已核对）——消灭 map/+ 解析链。
  2. `Key("'\"", 0x34)` 的 label 改 unicode 转义 `"\u0027\u0022"`（值仍为 '"'）——
     消灭单/双引号视觉混淆层（121 行头号嫌疑）。
  3. 数字行 / Ctrl 行（纯 listOf，编译已过）原样保留不动。
weight 合计不变（Tab/Caps/Shift 行仍 15u）。KeyboardPage / TouchpadActivity 未报错，
本批只动 KeyLayouts.kt 一文件。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
KL = "android/app/src/main/java/com/allperiph/ui/KeyLayouts.kt"

OLD_FULL = '''    val FULL: List<List<Key>> = listOf(
        listOf(Key("Esc", ESC)) + (1..12).map { Key("F$it", fn(it)) },
        listOf(
            Key("`~", 0x35), Key("1!", d(1)), Key("2@", d(2)), Key("3#", d(3)), Key("4$", d(4)),
            Key("5%", d(5)), Key("6^", d(6)), Key("7&", d(7)), Key("8*", d(8)), Key("9(", d(9)),
            Key("0)", d(0)), Key("-_", 0x2D), Key("=+", 0x2E), Key("⌫", BACKSPACE, 2f),
        ),
        listOf(Key("Tab", TAB, 1.5f)) +
            "QWERTYUIOP".map { Key(it.toString(), 0x04 + (it - 'A')) } +
            listOf(Key("[{", 0x2F), Key("]}", 0x30), Key("\\|", 0x31, 1.5f)),
        listOf(Key("Caps", 0x39, 1.75f)) +
            "ASDFGHJKL".map { Key(it.toString(), 0x04 + (it - 'A')) } +
            listOf(Key(";:", 0x33), Key("'\"", 0x34), Key("⏎", ENTER, 2.25f)),
        listOf(Key("Shift", 0, 2.25f)) +
            "ZXCVBNM".map { Key(it.toString(), 0x04 + (it - 'A')) } +
            listOf(Key(",<", 0x36), Key(".>", 0x37), Key("/?", 0x38), Key("Shift", 0, 2.75f)),
        listOf(
            Key("Ctrl", 0, 1.5f), Key("Alt", 0, 1.5f), Key("Space", SPACE, 9f),
            Key("Alt", 0, 1.5f), Key("Ctrl", 0, 1.5f),
        ),
    )'''

NEW_FULL = '''    val FULL: List<List<Key>> = listOf(
        listOf(
            Key("Esc", ESC), Key("F1", fn(1)), Key("F2", fn(2)), Key("F3", fn(3)), Key("F4", fn(4)),
            Key("F5", fn(5)), Key("F6", fn(6)), Key("F7", fn(7)), Key("F8", fn(8)), Key("F9", fn(9)),
            Key("F10", fn(10)), Key("F11", fn(11)), Key("F12", fn(12)),
        ),
        listOf(
            Key("`~", 0x35), Key("1!", d(1)), Key("2@", d(2)), Key("3#", d(3)), Key("4$", d(4)),
            Key("5%", d(5)), Key("6^", d(6)), Key("7&", d(7)), Key("8*", d(8)), Key("9(", d(9)),
            Key("0)", d(0)), Key("-_", 0x2D), Key("=+", 0x2E), Key("⌫", BACKSPACE, 2f),
        ),
        listOf(
            Key("Tab", TAB, 1.5f), Key("Q", 0x14), Key("W", 0x1A), Key("E", 0x08), Key("R", 0x15),
            Key("T", 0x17), Key("Y", 0x1C), Key("U", 0x18), Key("I", 0x0C), Key("O", 0x12),
            Key("P", 0x13), Key("[{", 0x2F), Key("]}", 0x30), Key("\\|", 0x31, 1.5f),
        ),
        listOf(
            Key("Caps", 0x39, 1.75f), Key("A", 0x04), Key("S", 0x16), Key("D", 0x07),
            Key("F", 0x09), Key("G", 0x0A), Key("H", 0x0B), Key("J", 0x0D), Key("K", 0x0E),
            Key("L", 0x0F), Key(";:", 0x33), Key("\u0027\u0022", 0x34), Key("⏎", ENTER, 2.25f),
        ),
        listOf(
            Key("Shift", 0, 2.25f), Key("Z", 0x1D), Key("X", 0x1B), Key("C", 0x06),
            Key("V", 0x19), Key("B", 0x05), Key("N", 0x11), Key("M", 0x10),
            Key(",<", 0x36), Key(".>", 0x37), Key("/?", 0x38), Key("Shift", 0, 2.75f),
        ),
        listOf(
            Key("Ctrl", 0, 1.5f), Key("Alt", 0, 1.5f), Key("Space", SPACE, 9f),
            Key("Alt", 0, 1.5f), Key("Ctrl", 0, 1.5f),
        ),
    )'''

EDITS = [(KL, [(OLD_FULL, NEW_FULL, 1)])]


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
                print("  old[0:120]=" + old[:120].replace("\n", "\\n"))
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
