#!/usr/bin/env python3
"""repo_edit_b12.py —— b21（2026-11-08「标常见的，按了没长亮」）。

A) KeyLayouts.FULL 行0 F1..F12 加常见功能标（label = \"F1\\n静音\" 两行渲染，
   点击仍发 fn(n)；Kotlin 层 \\n 转义由 raw 三引号保真写入）。
B) KeyGridLayout 补 sticky 高亮（按了没长亮）：
   - 函数头加 sticky 本地镜像（stickySnapshot 初值，与 GameLayout 同口径）；
   - KeyBox 调用加命名参数 active = bit != null && sticky and bit != 0；
   - lambda 内 toggle 后 flipSticky、tap 普通键后清零燄灭。
   KeyBox 参数顺序硬约定：onClick 压轴，active 只能命名参数（CI 踩过）。"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
KL = "android/app/src/main/java/com/allperiph/ui/KeyLayouts.kt"
KB = "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt"

F_OLD = '''        listOf(
            Key("Esc", ESC), Key("F1", fn(1)), Key("F2", fn(2)), Key("F3", fn(3)), Key("F4", fn(4)),
            Key("F5", fn(5)), Key("F6", fn(6)), Key("F7", fn(7)), Key("F8", fn(8)), Key("F9", fn(9)),
            Key("F10", fn(10)), Key("F11", fn(11)), Key("F12", fn(12)),
        ),'''

# raw：保留字面 \\n（Kotlin 字符串转义换行），Python 层不吞
F_NEW = r'''        listOf(
            Key("Esc", ESC), Key("F1\n静音", fn(1)), Key("F2\n音量-", fn(2)), Key("F3\n音量+", fn(3)), Key("F4\n亮度-", fn(4)),
            Key("F5\n亮度+", fn(5)), Key("F6\n截屏", fn(6)), Key("F7\n后退", fn(7)), Key("F8\n前进", fn(8)), Key("F9\n刷新", fn(9)),
            Key("F10\n全屏", fn(10)), Key("F11\n桌面", fn(11)), Key("F12\n锁屏", fn(12)),
        ),'''

A_OLD = '''private fun KeyGridLayout(
    rows: List<List<KeyLayouts.Key>>,
    dens: KeyPref.Density,
    keycap: Int,
    fx: Int,
) {
    Column(modifier = Modifier.fillMaxSize()) {'''

A_NEW = '''private fun KeyGridLayout(
    rows: List<List<KeyLayouts.Key>>,
    dens: KeyPref.Density,
    keycap: Int,
    fx: Int,
) {
    // b21「按了没长亮」：sticky 本地镜像驱动修饰键高亮（与 GameLayout 同口径：toggle 后 flip、tap 普通键清零）
    var sticky by remember { mutableIntStateOf(stickySnapshot()) }
    Column(modifier = Modifier.fillMaxSize()) {'''

B1_OLD = '''                    KeyBox(k.label, Modifier.weight(k.weight).fillMaxHeight(), dens, keycap, fx) {'''

B1_NEW = '''                    val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == k.label }?.second
                    KeyBox(
                        k.label,
                        Modifier.weight(k.weight).fillMaxHeight(),
                        dens, keycap, fx,
                        active = bit != null && sticky and bit != 0,
                    ) {'''

B2_OLD = '''                        val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == k.label }?.second
                        if (bit != null) HidKeys.toggleSticky(bit) else HidKeys.tap(k.usage)'''

B2_NEW = '''                        if (bit != null) {
                            HidKeys.toggleSticky(bit)
                            sticky = flipSticky(sticky, bit)
                        } else {
                            HidKeys.tap(k.usage)
                            sticky = 0
                        }'''

EDITS = [
    (KL, [(F_OLD, F_NEW, 1)]),
    (KB, [(A_OLD, A_NEW, 1), (B1_OLD, B1_NEW, 1), (B2_OLD, B2_NEW, 1)]),
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
                print("  old[0:100]=" + old[:100].replace("\n", "\\n"))
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
