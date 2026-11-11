package com.allperiph.ui

import android.content.Context

/**
 * 手柄自定义按钮配置（issue#168 第 5 条）：每颗按钮的键位与尺寸，按槽位落盘。
 *
 * 槽位即面板布局里的固定位置（A / B / X / Y / L1 / R1 / Sel / Start），配置跟着
 * 位置走：长按某颗按钮改完键位 / 尺寸后，下次进面板仍生效。这里只存本端的
 * 「槽位 → 按钮」换算表 —— 发送侧 [GamepadController] 收到的还是标准按钮位，
 * 协议与报告格式都不动。
 */
object GamepadPadConfig {

    private const val PREFS = "apx_gamepad_pad"
    private const val KEY_BIT = "bit."
    private const val KEY_SIZE = "size."
    private const val KEY_OFF_X = "offx."
    private const val KEY_OFF_Y = "offy."

    /** 可选键位（bit → 显示名）：与 [GamepadController] 的按钮位一一对应 */
    val KEY_CHOICES: List<Pair<Int, String>> = listOf(
        GamepadController.BTN_A to "A",
        GamepadController.BTN_B to "B",
        GamepadController.BTN_X to "X",
        GamepadController.BTN_Y to "Y",
        GamepadController.BTN_L1 to "L1",
        GamepadController.BTN_R1 to "R1",
        GamepadController.BTN_L2 to "L2",
        GamepadController.BTN_R2 to "R2",
        GamepadController.BTN_SELECT to "Sel",
        GamepadController.BTN_START to "Start",
    )

    /** 尺寸三档（dp）：小 / 中 / 大 */
    val SIZE_CHOICES = listOf(44, 58, 76)

    /** 各槽位的默认键位与尺寸（dp）：没改过配置时观感与旧版完全一致 */
    val SLOT_DEFAULTS = mapOf(
        "A" to (GamepadController.BTN_A to 58),
        "B" to (GamepadController.BTN_B to 58),
        "X" to (GamepadController.BTN_X to 58),
        "Y" to (GamepadController.BTN_Y to 58),
        "L1" to (GamepadController.BTN_L1 to 78),
        "R1" to (GamepadController.BTN_R1 to 78),
        "Sel" to (GamepadController.BTN_SELECT to 64),
        "Start" to (GamepadController.BTN_START to 64),
    )

    /** 读某槽位的键位（bit）；没改过返回默认值 */
    fun bit(ctx: Context, slot: String, default: Int): Int =
        prefs(ctx).getInt(KEY_BIT + slot, default)

    /** 读某槽位的按钮直径（dp）；没改过返回默认值 */
    fun sizeDp(ctx: Context, slot: String, default: Int): Int =
        prefs(ctx).getInt(KEY_SIZE + slot, default)

    /** 落盘某槽位的键位与尺寸 */
    fun set(ctx: Context, slot: String, newBit: Int, newSizeDp: Int) {
        prefs(ctx).edit()
            .putInt(KEY_BIT + slot, newBit)
            .putInt(KEY_SIZE + slot, newSizeDp)
            .apply()
    }

    /**
     * 读某槽位的拖动偏移（dp，相对面板默认位置）；没拖过返回 (0, 0)。
     * issue#168 追补：「长按拖动移动位置」——偏移随键位 / 尺寸一起落盘，下次进面板仍生效。
     */
    fun offset(ctx: Context, slot: String): Pair<Float, Float> =
        prefs(ctx).getFloat(KEY_OFF_X + slot, 0f) to prefs(ctx).getFloat(KEY_OFF_Y + slot, 0f)

    /** 落盘某槽位的拖动偏移（dp） */
    fun setOffset(ctx: Context, slot: String, offXDp: Float, offYDp: Float) {
        prefs(ctx).edit()
            .putFloat(KEY_OFF_X + slot, offXDp)
            .putFloat(KEY_OFF_Y + slot, offYDp)
            .apply()
    }

    /** bit 的显示名（配置界面用） */
    fun labelOf(bit: Int): String =
        KEY_CHOICES.firstOrNull { it.first == bit }?.second ?: "?"

    /** 键位循环切换的下一个 bit */
    fun nextBit(bit: Int): Int {
        val idx = KEY_CHOICES.indexOfFirst { it.first == bit }
        return KEY_CHOICES[(idx + 1).mod(KEY_CHOICES.size)].first
    }

    /** 尺寸循环切换的下一档（dp） */
    fun nextSize(sizeDp: Int): Int {
        val idx = SIZE_CHOICES.indexOf(sizeDp)
        return SIZE_CHOICES[(idx + 1).mod(SIZE_CHOICES.size)]
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
