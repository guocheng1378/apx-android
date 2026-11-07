#!/usr/bin/env python3
"""repo_edit_b12.py —— b17 内容（复用已验证通道：repo-edit-b12.yml）。

b17（2026-11-08）方案 C「按现实真键盘排」（用户拍板）。既定口径（用户历史要求）：
横屏适配电脑全键盘、竖屏保持现状 —— 15u 行制只上横屏（竖屏 15u 每键仅 27dp 不可用）。

调研已收口，机器全现成，本批只补三件事：
  1. KeyLayouts.FULL：6 行 ANSI 15u 行制（Esc+F区行、数字双标行、Tab/Caps/Shift 变宽
     行、底行 Space9）——Key(label, usage, weight) 带宽结构现成；双标 label（"1!"）
     usage=底键，**tap 自带 sticky** → sticky Shift 在时自动出上档（真键盘语义，
     发送层零改动）；Caps=0x39 显式给出（KEY_CHOICES 不含 Caps，label 反查落 0）。
  2. 横屏分支两处：KeyboardPage.QuickLayout（weight(6f) 对齐竖屏 6 行比例）+
     操控面 quickKeyboard（weight 6f 同比例）——竖屏路径原样不动。
  3. 修饰分支两处：KeyGridLayout（Compose）+ keyGrid（原生）——FULL 的 Shift/Ctrl/Alt
     裸 usage 无意义，按 MOD_LABELS 转 toggleSticky；NUMPAD/GRID9/ARROWS/FN 无修饰词
     label，行为不变。

已知首版边界（如实）：Compose 侧 FULL 修饰键无 sticky 高亮联动（功能正常）；双标键面
并排显示非叠字；行0 F 区 13 等分不强求 15u 对齐（真键盘 F 区本就分组）；操控面横屏
键盘区宽度待真机。中文棘轮 643 不动（新代码无中文字符串字面量，仅注释）。
两阶段写盘：7 锚全中才落盘。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

KL = "android/app/src/main/java/com/allperiph/ui/KeyLayouts.kt"
KB = "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt"
TP = "android/app/src/main/java/com/allperiph/touchpad/TouchpadActivity.kt"

EDITS = [
    # —— 1/2. KeyLayouts：FULL 数据（15u 行制 ANSI）
    (KL, [
        ('''            Key("Esc", ESC), Key("Tab", TAB), Key("Space", SPACE, 2f),
            Key("⏎", ENTER), Key("⌫", BACKSPACE), Key("Del", DELETE),
        ),
    )
}''',
         '''            Key("Esc", ESC), Key("Tab", TAB), Key("Space", SPACE, 2f),
            Key("⏎", ENTER), Key("⌫", BACKSPACE), Key("Del", DELETE),
        ),
    )

    /**
     * b17 横屏全键盘（方案 C「按现实真键盘排」）：15u 行制 ANSI 比例，只上横屏
     * （既定口径：横屏电脑全键盘、竖屏保持 letterRows；竖屏 15u 每键仅 27dp 不可用）。
     * 双标键面（"1!" = 主字符+上档）：usage 是**底键**，点击出主字符；sticky Shift 在时
     * HidKeys.tap 自带 sticky mod → 自动出上档（真键盘语义，发送层零改动）。
     * 变宽 weight：Tab1.5 / Caps1.75 / Shift2.25 / Enter2.25 / ⌫2 / Space9；行首错位由
     * 首键宽度自然形成。Caps=0x39 显式给出（KEY_CHOICES 不含 Caps，label 反查落 0）。
     * Shift/Ctrl/Alt usage=0：渲染层按 MOD_LABELS 分支转 toggleSticky（裸修饰无意义）。
     * 行0（Esc+F1..F12）行内 13 等分 —— 真键盘 F 区本与主区分组，不强求 15u 对齐。
     */
    val FULL: List<List<Key>> = listOf(
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
    )
}''', 1),
    ]),
    # —— 3/4/5. KeyboardPage：wide 声明 / 横屏分支 / KeyGridLayout 修饰分支
    (KB, [
        ('''    val ctx = LocalContext.current
    val rows = KeyPref.letterRows(ctx)''',
         '''    val ctx = LocalContext.current
    val rows = KeyPref.letterRows(ctx)
    // b17 方案 C：横屏切真键盘（15u 行制），竖屏保持 letterRows 紧凑排
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.Configuration.ORIENTATION_LANDSCAPE''', 1),
        ('''        rows.forEach { labels ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                labels.forEach { label ->
                    KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                        HidKeys.tapLabel(label)''',
         '''        if (wide) {
            // b17 方案 C：横屏按现实真键盘排（15u 行制变宽）；weight(6f) 对齐竖屏 6 行比例
            Box(modifier = Modifier.weight(6f).fillMaxWidth()) {
                KeyGridLayout(com.allperiph.ui.KeyLayouts.FULL, dens, keycap, fx)
            }
        } else rows.forEach { labels ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                labels.forEach { label ->
                    KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                        HidKeys.tapLabel(label)''', 1),
        ('''                row.forEach { k ->
                    KeyBox(k.label, Modifier.weight(k.weight).fillMaxHeight(), dens, keycap, fx) {
                        HidKeys.tap(k.usage)
                    }
                }''',
         '''                row.forEach { k ->
                    KeyBox(k.label, Modifier.weight(k.weight).fillMaxHeight(), dens, keycap, fx) {
                        // b17：FULL 的 Shift/Ctrl/Alt 是修饰词 → 转 sticky（裸 usage 无意义）；
                        // NUMPAD/GRID9/ARROWS/FN 无修饰词 label，行为不变
                        val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == k.label }?.second
                        if (bit != null) HidKeys.toggleSticky(bit) else HidKeys.tap(k.usage)
                    }
                }''', 1),
    ]),
    # —— 6/7. 操控面：keyGrid 修饰分支 + quickKeyboard 横屏分支
    (TP, [
        ('''            row.forEach { k ->
                r.addView(keyCap(k.label, k.usage), LinearLayout.LayoutParams(0, -1, k.weight).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }''',
         '''            row.forEach { k ->
                // b17：FULL 的 Shift/Ctrl/Alt → toggleSticky（与 quickKeyboard 修饰排同口径）
                val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == k.label }?.second
                val cap = keyCap(k.label, bit != null) {
                    if (bit != null) HidKeys.toggleSticky(bit) else HidKeys.tap(k.usage)
                }
                r.addView(cap, LinearLayout.LayoutParams(0, -1, k.weight).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }''', 1),
        ('''        com.allperiph.ui.KeyPref.letterRows(this).forEach { labels ->''',
         '''        if (resources.configuration.orientation == android.content.Configuration.ORIENTATION_LANDSCAPE) {
            // b17 方案 C：横屏按现实真键盘排（15u 行制变宽）；weight 6f 对齐竖屏 6 行比例
            col.addView(keyGrid(com.allperiph.ui.KeyLayouts.FULL), LinearLayout.LayoutParams(-1, 0, 6f))
            return col
        }
        com.allperiph.ui.KeyPref.letterRows(this).forEach { labels ->''', 1),
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
