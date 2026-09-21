package com.allperiph.ui

/**
 * 扩展键盘布局（v1.32）：**数字小键盘 / 九宫格 / 方向键 / F 区** 四套专用键位。
 *
 * 每键 = [Key.label]（键面文字）+ [Key.usage]（USB HID Keyboard Page 0x07 的 usage code）
 * + [Key.weight]（键宽权重，用来做宽空格 / 双宽 0）。
 *
 * usage 全部**显式写出**而不是交给 `HidKeys.usageOf(label)`：
 * 小键盘的 ⏎ / Num 等键面文字与 usage 并非一一对应，靠标签反查会落到 0（发不出键）。
 *
 * 主页键盘页与操控面键盘页共用这一份数据，避免两边布局写歪。
 */
object KeyLayouts {

    data class Key(val label: String, val usage: Int, val weight: Float = 1f)

    // —————————————————————————— usage 常量 ——————————————————————————

    private const val ESC = 0x29
    private const val TAB = 0x2B
    private const val ENTER = 0x28
    private const val BACKSPACE = 0x2A
    private const val SPACE = 0x2C
    private const val DOT = 0x37
    private const val INSERT = 0x49
    private const val HOME = 0x4A
    private const val PGUP = 0x4B
    private const val DELETE = 0x4C
    private const val END = 0x4D
    private const val PGDN = 0x4E
    private const val RIGHT = 0x4F
    private const val LEFT = 0x50
    private const val DOWN = 0x51
    private const val UP = 0x52

    /** 主键区数字（0 是 0x27，1..9 从 0x1E 起） */
    private fun d(n: Int) = if (n == 0) 0x27 else 0x1E + (n - 1)

    /** F1..F12（0x3A..0x45） */
    private fun fn(n: Int) = 0x39 + n

    // 小键盘专用 usage：走 Keypad 语义，PC 侧与实体小键盘一致（受 NumLock 影响）
    private const val KP_NUM = 0x53
    private const val KP_SLASH = 0x54
    private const val KP_STAR = 0x55
    private const val KP_MINUS = 0x56
    private const val KP_PLUS = 0x57
    private const val KP_ENTER = 0x58
    private const val KP_1 = 0x59
    private const val KP_2 = 0x5A
    private const val KP_3 = 0x5B
    private const val KP_4 = 0x5C
    private const val KP_5 = 0x5D
    private const val KP_6 = 0x5E
    private const val KP_7 = 0x5F
    private const val KP_8 = 0x60
    private const val KP_9 = 0x61
    private const val KP_0 = 0x62
    private const val KP_DOT = 0x63

    // —————————————————————————— 四套布局 ——————————————————————————

    /** 数字小键盘：运算键在上，5 行 4 列，末行双宽 0 / . */
    val NUMPAD: List<List<Key>> = listOf(
        listOf(Key("Num", KP_NUM), Key("/", KP_SLASH), Key("*", KP_STAR), Key("-", KP_MINUS)),
        listOf(Key("7", KP_7), Key("8", KP_8), Key("9", KP_9), Key("+", KP_PLUS)),
        listOf(Key("4", KP_4), Key("5", KP_5), Key("6", KP_6), Key("⏎", KP_ENTER)),
        listOf(Key("1", KP_1), Key("2", KP_2), Key("3", KP_3), Key("⌫", BACKSPACE)),
        listOf(Key("0", KP_0, 2f), Key(".", KP_DOT, 2f)),
    )

    /** 九宫格：手机数字键盘的肌肉记忆（拨号 / 输入纯数字最快） */
    val GRID9: List<List<Key>> = listOf(
        listOf(Key("1", d(1)), Key("2", d(2)), Key("3", d(3))),
        listOf(Key("4", d(4)), Key("5", d(5)), Key("6", d(6))),
        listOf(Key("7", d(7)), Key("8", d(8)), Key("9", d(9))),
        listOf(Key(".", DOT), Key("0", d(0)), Key("⌫", BACKSPACE)),
    )

    /** 方向键 + 编辑键：T 型方向键在中间行，方便单手盲按 */
    val ARROWS: List<List<Key>> = listOf(
        listOf(Key("Esc", ESC), Key("⌫", BACKSPACE), Key("Del", DELETE), Key("Tab", TAB)),
        listOf(Key("Home", HOME), Key("↑", UP), Key("PgUp", PGUP), Key("⏎", ENTER)),
        listOf(Key("←", LEFT), Key("↓", DOWN), Key("→", RIGHT), Key("PgDn", PGDN)),
        listOf(Key("End", END), Key("Ins", INSERT), Key("Space", SPACE, 2f)),
    )

    /** F 区：上两行 F1–F12，末行补编辑键（游戏 / 软件功能键场景） */
    val FN: List<List<Key>> = listOf(
        (1..6).map { Key("F$it", fn(it)) },
        (7..12).map { Key("F$it", fn(it)) },
        listOf(
            Key("Esc", ESC), Key("Tab", TAB), Key("Space", SPACE, 2f),
            Key("⏎", ENTER), Key("⌫", BACKSPACE), Key("Del", DELETE),
        ),
    )
}
