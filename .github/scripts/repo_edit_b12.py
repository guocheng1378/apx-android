#!/usr/bin/env python3
"""repo_edit_b12.py —— b16 内容（复用已验证通道：repo-edit-b12.yml）。v2 修正见文末。

b16（2026-11-08）键盘布局两处用户反馈：「上面没有半角符号，回车键的位置也不对」。根因（源码实证）：
  1. KeyPref 只有 DIGIT_ROW[1-0-=] + 3 字母行 + FN_ROW，整套无半角符号排；
  2. FN_ROW = [Esc Tab Space Enter ⌫ Del]，Enter 卡在第 4 位（不在右端）；
  3. **只加键位会「按了没反应」**：usageOf 对 !@#$ 等上档符号返回 0，tap(0) 静默忽略
     （HID Keyboard Page 无上档符号独立 usage，它们是 Shift+底键组合）——
     所以本批同时给 HidKeys 补 tapLabel(label)：上档符号走 send(sticky|SHIFT, 底键)
     60ms 释放（与 tap 同时序），其余透传 usageByLabel→tap；
  4. 调用点 6 处全部换 tapLabel：KeyboardPage（快捷/游戏/自定义 3 处）+ 操控面
     TouchpadActivity 3 处 —— letterRows 是两边共用数据源（改一处两边变），
     漏掉操控面就会出现「主屏键盘有符号、触控板页符号键点不动」的半成品。
     TouchpadActivity 678 行注释「letterRows 含符号行」正是历史证据：符号行本来就是
     设计内的，后来从行表里丢了。

v2（run #6 失败后修正）：KeyboardPage 第 3 锚（CustomLayout）多写了闭括号行，其中
Row 层是凭结构猜的、没逐行 sed 实证，count=0（其余 9 锚全过、两阶段零部分写盘）。
修法：锚 1 先替换会把 214 行同文本改掉，第 3 锚因此缩成单行（基于 new 的累积替换，
此时 count 必为 1）—— 排序依赖写在 EDITS 列表顺序里，勿倒序。

改动：KeyPref（符号排 + Enter 归右端 + 行数注释）、HidKeys（SHIFTED 表 + tapLabel）、
KeyboardPage×3、TouchpadActivity×3。行表 5 行→6 行（+修饰排=7 weight 行），
KeyBox heightIn(min=48dp) 保底可点；横屏超高观感待真机（如实不吹）。
中文棘轮 643 不动（新增全部为符号/英文 label/注释）。两阶段写盘：10 锚全中才落盘。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

KP = "android/app/src/main/java/com/allperiph/ui/KeyPref.kt"
HK = "android/app/src/main/java/com/allperiph/hid/HidKeys.kt"
KB = "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt"
TP = "android/app/src/main/java/com/allperiph/touchpad/TouchpadActivity.kt"

EDITS = [
    (KP, [
        ('''     * 当前排列方案下的**完整键盘**行表（数字行 + 3 行字母 + 功能行，共 5 行）。
     * 数字行与功能行三套共用，只换字母行的顺序。''',
         '''     * 当前排列方案下的**完整键盘**行表（符号排 + 数字行 + 3 行字母 + 功能行，共 6 行；
     * b16 补半角符号排 —— 用户报「上面没有半角符号」）。
     * 数字行与功能行三套共用，只换字母行的顺序。''', 1),
        ('''    private val DIGIT_ROW = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=")
    private val FN_ROW = listOf("Esc", "Tab", "Space", "Enter", "⌫", "Del")''',
         '''    // b16 半角符号排：上档符号无独立 HID usage，发送走 HidKeys.tapLabel（Shift+底键组合）
    private val SYMBOL_ROW = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "_", "+")
    private val DIGIT_ROW = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=")
    // b16：Enter 归右端（用户报「回车键的位置也不对」，从第 4 位移到行末）
    private val FN_ROW = listOf("Esc", "Tab", "Space", "⌫", "Del", "Enter")''', 1),
        ('''        return listOf(DIGIT_ROW) + letters + listOf(FN_ROW)''',
         '''        return listOf(SYMBOL_ROW, DIGIT_ROW) + letters + listOf(FN_ROW)''', 1),
    ]),
    (HK, [
        ('''    /** 键面标签 → Keyboard Page (0x07) usage code；未识别返回 0 */
    fun usageOf(label: String): Int = when (label) {''',
         '''    /** 上档符号 → (修饰位, 底键 usage)：US 布局 Shift+数字/-/= 的标准产出（b16） */
    private val SHIFTED: Map<String, Pair<Int, Int>> = mapOf(
        "!" to (MOD_SHIFT to 0x1E), "@" to (MOD_SHIFT to 0x1F), "#" to (MOD_SHIFT to 0x20),
        "$" to (MOD_SHIFT to 0x21), "%" to (MOD_SHIFT to 0x22), "^" to (MOD_SHIFT to 0x23),
        "&" to (MOD_SHIFT to 0x24), "*" to (MOD_SHIFT to 0x25), "(" to (MOD_SHIFT to 0x26),
        ")" to (MOD_SHIFT to 0x27), "_" to (MOD_SHIFT to 0x2D), "+" to (MOD_SHIFT to 0x2E),
    )

    /**
     * 键面 label 发送入口（b16）：上档符号按 Shift+底键组合发送，其余透传
     * [usageByLabel] → [tap]。行表里的符号键必须走本函数 —— usageOf 对上档符号
     * 返回 0，直接 tap 会被静默忽略（「加了符号键没反应」的坑）。时序与 [tap] 同款：
     * 60ms 自动全释放、发完清 sticky（sticky Shift 与本表 Shift 叠加无害）。
     */
    fun tapLabel(label: String): Int {
        SHIFTED[label]?.let { (mod, usage) ->
            handler.removeCallbacks(releaseTask)
            send(sticky or mod, intArrayOf(usage))
            handler.postDelayed(releaseTask, 60)
            val used = sticky
            sticky = 0
            return used
        }
        return tap(usageByLabel(label))
    }

    /** 键面标签 → Keyboard Page (0x07) usage code；未识别返回 0 */
    fun usageOf(label: String): Int = when (label) {''', 1),
    ]),
    (KB, [
        ('''                    KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                        HidKeys.tap(HidKeys.usageByLabel(label))
                        sticky = 0 // tap 自动清 sticky，同步燄灭修饰键（与操控面 refreshMods 同口径）''',
         '''                    KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                        HidKeys.tapLabel(label)
                        sticky = 0 // tap 自动清 sticky，同步燄灭修饰键（与操控面 refreshMods 同口径）''', 1),
        ('''                        KeyBox(text, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                            HidKeys.tap(HidKeys.usageByLabel(text))
                            sticky = 0
                        }''',
         '''                        KeyBox(text, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                            HidKeys.tapLabel(text)
                            sticky = 0
                        }''', 1),
        # v2：单行锚 —— 依赖锚 1 先替换（214 行同文本已变），此后本行 count=1
        ('''                            HidKeys.tap(HidKeys.usageByLabel(label))''',
         '''                            HidKeys.tapLabel(label)''', 1),
    ]),
    (TP, [
        ('''                    HidKeys.tap(HidKeys.usageByLabel(text)) // letterRows 含符号行，按 label 反查更全''',
         '''                    HidKeys.tapLabel(text) // letterRows 含符号行，按 label 反查更全（b16 符号走 tapLabel）''', 1),
        ('''                        HidKeys.toggleSticky(bit)
                    } else {
                        HidKeys.tap(HidKeys.usageByLabel(text))
                    }''',
         '''                        HidKeys.toggleSticky(bit)
                    } else {
                        HidKeys.tapLabel(text)
                    }''', 1),
        ('''                val b = keyCap(text, false) {
                    HidKeys.tap(HidKeys.usageByLabel(text))
                    refreshMods()
                }''',
         '''                val b = keyCap(text, false) {
                    HidKeys.tapLabel(text)
                    refreshMods()
                }''', 1),
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
