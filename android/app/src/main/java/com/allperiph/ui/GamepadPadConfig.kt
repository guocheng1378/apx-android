package com.allperiph.ui

import android.content.Context

/**
 * 手柄自定义按钮配置（issue#168 第 5 条）：每颗按钮的键位与尺寸，按槽位落盘。
 *
 * 槽位即面板布局里的固定位置（A / B / X / Y / L1 / R1 / Sel / Start），配置跟着
 * 位置走：长按某颗按钮改完键位 / 尺寸后，下次进面板仍生效。这里只存本端的
 * 「槽位 → 按钮」换算表 —— 发送侧 [GamepadController] 收到的还是标准按钮位，
 * 协议与报告格式都不动。
 *
 * 2026-11-12 手柄页重画（用户「手柄ui 重画大小，ps5布局」）：槽位扩到 L2/R2 与
 * 十字键四向；摇杆也可拖动移位（槽位 LStick / RStick，只存偏移不改键）。
 *
 * issue#169 第 21 条：✕ / ✎ 功能键也进编辑体系（槽位 Exit / Edit）——
 * 拖动移位 + 尺寸配置都可用，只是**没有键位**（[KEY_CHOICES] 里没有它们对应的
 * 游戏按钮），配置卡里对这两个槽位隐藏「键位」行（见 [FUNC_SLOTS]）。
 */
object GamepadPadConfig {

    private const val PREFS = "apx_gamepad_pad"
    private const val KEY_BIT = "bit."
    private const val KEY_SIZE = "size."
    private const val KEY_OFF_X = "offx."
    private const val KEY_OFF_Y = "offy."

    /**
     * 编辑说明的「看过」标记（REQ 手柄编辑器 本批 4）：进编辑态首次弹操作提示
     * 「拖动移动 · 捏边缩放 · 点按改键」，看过就不再自动弹 —— 编辑面板的「？」
     * 仍可随时重看。独立 tip. 前缀，不进槽位四键体系。
     */
    private const val KEY_TIP_SHOWN = "tip.editing_shown"

    /** 可选键位（bit → 显示名）：与 [GamepadController] 的按钮位一一对应 */
    val KEY_CHOICES: List<Pair<Int, String>> = listOf(
        GamepadController.BTN_A to "✕",
        GamepadController.BTN_B to "○",
        GamepadController.BTN_X to "□",
        GamepadController.BTN_Y to "△",
        GamepadController.BTN_L1 to "L1",
        GamepadController.BTN_R1 to "R1",
        GamepadController.BTN_L2 to "L2",
        GamepadController.BTN_R2 to "R2",
        GamepadController.BTN_SELECT to "Create",
        GamepadController.BTN_START to "Options",
        GamepadController.BTN_DUP to "▲",
        GamepadController.BTN_DDOWN to "▼",
        GamepadController.BTN_DLEFT to "◀",
        GamepadController.BTN_DRIGHT to "▶",
    )

    /** 尺寸档（dp）：功能键 40 起，按键三档小 / 中 / 大（issue#169 第 21 条补 40 给 ✕/✎） */
    val SIZE_CHOICES = listOf(40, 44, 58, 76)

    /** 各槽位的默认键位与尺寸（dp）：没改过配置时与重画后的 PS5 版一致 */
    val SLOT_DEFAULTS = mapOf(
        "A" to (GamepadController.BTN_A to 58),
        "B" to (GamepadController.BTN_B to 58),
        "X" to (GamepadController.BTN_X to 58),
        "Y" to (GamepadController.BTN_Y to 58),
        "L1" to (GamepadController.BTN_L1 to 64),
        "R1" to (GamepadController.BTN_R1 to 64),
        "L2" to (GamepadController.BTN_L2 to 68),
        "R2" to (GamepadController.BTN_R2 to 68),
        "Sel" to (GamepadController.BTN_SELECT to 76),
        "Start" to (GamepadController.BTN_START to 76),
        "DUp" to (GamepadController.BTN_DUP to 58),
        "DDown" to (GamepadController.BTN_DDOWN to 58),
        "DLeft" to (GamepadController.BTN_DLEFT to 58),
        "DRight" to (GamepadController.BTN_DRIGHT to 58),
        // issue#169 第 21 条：✕ / ✎ 功能键也占槽位（偏移 + 尺寸），bit=0 表示不发 HID
        "Exit" to (0 to 40),
        "Edit" to (0 to 40),
    )

    /**
     * 功能键槽位（issue#169 第 21 条）：✕ / ✎ 同样可拖可配，但没有键位 ——
     * 配置卡对这两个槽位只出「尺寸」行，不出「键位」行（0 号位不在 [KEY_CHOICES]）。
     */
    val FUNC_SLOTS = listOf("Exit", "Edit")

    /** 摇杆槽位：只支持拖动移位（存偏移），不进键位/尺寸配置卡 */
    val STICK_SLOTS = listOf("LStick", "RStick")

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

    /** 只落盘尺寸（issue#169 第 18 条：编辑态捏边缘无级调整，键位不动） */
    fun setSize(ctx: Context, slot: String, newSizeDp: Int) {
        prefs(ctx).edit().putInt(KEY_SIZE + slot, newSizeDp).apply()
    }

    /**
     * 读某槽位的拖动偏移（dp，相对面板默认位置）；没拖过返回 (0, 0)。
     * 编辑模式下拖动实时改、松手落盘，下次进面板仍生效。
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

    /**
     * 恢复某槽位默认（REQ 手柄编辑器 本批 3，配置卡「恢复默认」行）：清掉该槽位的
     * 键位 / 尺寸 / 偏移 x / 偏移 y 四个键，读端自动回落 [SLOT_DEFAULTS] 与 (0,0) ——
     * 不新增 key、不迁移，老配置直接兼容。注意：这只清存档，调用方要自己把
     * 内存里的偏移 state 一并归零（padOffsets 不在本对象管辖内）。
     */
    fun reset(ctx: Context, slot: String) {
        prefs(ctx).edit()
            .remove(KEY_BIT + slot)
            .remove(KEY_SIZE + slot)
            .remove(KEY_OFF_X + slot)
            .remove(KEY_OFF_Y + slot)
            .apply()
    }

    /** 编辑说明是否已经自动弹过（true = 不再自动弹，「？」入口不受影响） */
    fun tipShown(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TIP_SHOWN, false)

    /** 标记编辑说明已看过（首次自动弹时置位，只置一次） */
    fun markTipShown(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_TIP_SHOWN, true).apply()
    }

    /** bit 的显示名（配置界面用）：面键按 PlayStation 符号显示，与 PS5 布局对应 */
    fun labelOf(bit: Int): String =
        KEY_CHOICES.firstOrNull { it.first == bit }?.second ?: "?"

    /**
     * 设备作用域（2026-11-14「对应切换」批二期）：连着受控设备时键位/尺寸/偏移
     * 落在该设备自己的文件里 —— 每台设备各存一套手柄面板（打电脑一套、打电视一套），
     * 连上谁自动换谁的面板；没连设备 = 原文件（存量行为不变）。
     */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(
        com.allperiph.wireless.DeviceProfiles.scopedPrefsName(ctx, PREFS),
        Context.MODE_PRIVATE,
    )
}
