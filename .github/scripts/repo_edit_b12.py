#!/usr/bin/env python3
"""repo_edit_b12.py —— b17fix3（区间切除法，不用逐字节锚）。

b17fix2 的整块锚 count 失败（多层转义下逐字节比对脆弱）。本版改用定位切除：
  start = text.find("    val FULL: List<List<Key>> = listOf(")   # 全文件唯一
  end   = text.find("\n    )", start) + len("\n    )")            # FULL 闭括号（内部缩进均 >=8）
  new   = text[:start] + NEW_FULL + text[end:]
零逐字节比对，转义层怎么吃都不影响定位。失败模式只剩「标记不存在」，打印即知。"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
KL = "android/app/src/main/java/com/allperiph/ui/KeyLayouts.kt"
MARK = "    val FULL: List<List<Key>> = listOf("
ENDMARK = "\n    )"

NEW_FULL = r'''    val FULL: List<List<Key>> = listOf(
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


def main():
    full = os.path.join(ROOT, KL)
    if not os.path.exists(full):
        print("MISSING_FILE " + KL)
        return 1
    with open(full, "r", encoding="utf-8") as f:
        text = f.read()
    start = text.find(MARK)
    if start < 0:
        print("MARK_FAIL: val FULL not found")
        return 1
    tail = text.find(ENDMARK, start)
    if tail < 0:
        print("END_FAIL: FULL closing ')' not found")
        return 1
    end = tail + len(ENDMARK)
    old_seg = text[start:end]
    if old_seg == NEW_FULL:
        print("NO_CHANGES: FULL already in target form.")
        return 0
    new = text[:start] + NEW_FULL + text[end:]
    with open(full, "w", encoding="utf-8") as f:
        f.write(new)
    print("WROTE " + KL)
    print("".join(difflib.unified_diff(
        text.splitlines(keepends=True), new.splitlines(keepends=True),
        fromfile="a/" + KL, tofile="b/" + KL, n=3)))
    print("OK: wrote 1 file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
