#!/usr/bin/env python3
"""repo_edit_b12.py —— b19（2026-11-08「顶上的那一排可以不要了」）。

主屏键盘页去掉顶部页签行（8 套 TabChip 一排）：只在悬浮球面板形态（onClose != null）
画页签，主屏（onClose == null）直接键位顶上去，键位区多出 ~48dp。
定位：ASCII 锚（Row + horizontalScroll 组合）+ 括号配平找 Row lambda 闭合，
不依赖中文逐字节；闭合点校验必须落在独立的 '}' 行，否则拒写。"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
KPAGE = "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt"

ANCHOR = """ Row(
 modifier = Modifier.fillMaxWidth()
 .horizontalScroll(rememberScrollState())"""


def find_block_end(text, start):
    """从 Row( 起按 () {} 配平找 content lambda 闭合的 '}'，返回闭合行尾偏移。"""
    depth = 0
    i = start
    while i < len(text):
        c = text[i]
        if c in "({":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                # 闭合行必须是独立 '}'（去空白后）
                ls = text.rfind("\n", 0, i) + 1
                le = text.find("\n", i)
                if le < 0:
                    le = len(text)
                line = text[ls:le]
                if line.strip() != "}":
                    print("CLOSE_CHECK_FAIL: line=" + repr(line))
                    return -1
                end = le + 1 if le < len(text) else le
                return end
        i += 1
    print("BAL_FAIL: no closing brace")
    return -1


def indent(seg):
    out = []
    for ln in seg.splitlines(keepends=True):
        out.append((" " + ln) if ln.strip() else ln)
    return "".join(out)


def main():
    full = os.path.join(ROOT, KPAGE)
    if not os.path.exists(full):
        print("MISSING_FILE " + KPAGE)
        return 1
    with open(full, "r", encoding="utf-8") as f:
        text = f.read()
    if text.count(ANCHOR) != 1:
        print("ANCHOR_FAIL count=" + str(text.count(ANCHOR)) + " want=1")
        return 1
    start = text.find(ANCHOR)
    end = find_block_end(text, start)
    if end < 0:
        return 1
    old_seg = text[start:end]
    if old_seg.lstrip().startswith("if (onClose != null) {"):
        print("NO_CHANGES: tabs row already gated.")
        return 0
    new_seg = " if (onClose != null) {\n" + indent(old_seg) + " }\n"
    new = text[:start] + new_seg + text[end:]
    with open(full, "w", encoding="utf-8") as f:
        f.write(new)
    print("WROTE " + KPAGE)
    print("".join(difflib.unified_diff(
        text.splitlines(keepends=True), new.splitlines(keepends=True),
        fromfile="a/" + KPAGE, tofile="b/" + KPAGE, n=3)))
    print("OK: wrote 1 file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
