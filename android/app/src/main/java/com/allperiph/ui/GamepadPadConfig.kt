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
     * 「拖动移动 · 点按改键 · 滑条改大小」（issue#170 #4：捏边缩放已改配置卡滑条），
     * 看过就不再自动弹 —— 编辑面板的「？」仍可随时重看。独立 tip. 前缀，不进槽位四键体系。
     */
    private const val KEY_TIP_SHOWN = "tip.editing_shown"

    /** 可选键位（bit → 显示名）：与 [GamepadController] 的按钮位一一对应。
     *  issue#170 #7：面键与中键显示名统一通用叫法 —— ABXY 字母（不用图形符号）、
     *  Select / Start（原 Create / Options 是 PS5 双关叫法）。 */
    val KEY_CHOICES: List<Pair<Int, String>> = listOf(
        GamepadController.BTN_A to "A",
        GamepadController.BTN_B to "B",
        GamepadController.BTN_X to "X",
        GamepadController.BTN_Y to "Y",
        GamepadController.BTN_L1 to "L1",
        GamepadController.BTN_R1 to "R1",
        GamepadController.BTN_L2 to "L2",
        GamepadController.BTN_R2 to "R2",
        GamepadController.BTN_SELECT to "Select",
        GamepadController.BTN_START to "Start",
        GamepadController.BTN_DUP to "▲",
        GamepadController.BTN_DDOWN to "▼",
        GamepadController.BTN_DLEFT to "◀",
        GamepadController.BTN_DRIGHT to "▶",
    )

    // 尺寸档（issue#170 #4 随批删除）：四档点选条已改无级滑条，无档位概念 ——
    // 圆键 32–96dp、药丸 40–120dp，范围由 UI 层按槽位形状给出，存储仍是 int dp。

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
        // issue#170 #6：📷 截图键入编辑体系（bit=0，无键位行，默认 40dp 宽、药丸语义）；
        // 「组 / 复位 / ？」三个编辑态专属按钮也占槽位 —— 它们不进配置卡（tap 保留原
        // 功能：切组 / 清偏移 / 弹说明），槽位只为了偏移落盘与「复位」全量清偏移覆盖
        "Shot" to (0 to 40),
        "Group" to (0 to 40),
        "ResetAll" to (0 to 40),
        "Help" to (0 to 40),
        // REQ 手柄编辑器 二期 5：「撤销」也占槽位（编辑态可拖，与组 / 复位同规格）
        "Undo" to (0 to 40),
    )

    /**
     * 功能键槽位（issue#169 第 21 条）：✕ / ✎ 同样可拖可配，但没有键位 ——
     * 配置卡对这些槽位只出「尺寸」行，不出「键位」行（0 号位不在 [KEY_CHOICES]）。
     * issue#170 #6 追加：📷（Shot）同样 bit=0、无键位行，一并走「只配尺寸」路径。
     */
    val FUNC_SLOTS = listOf("Exit", "Edit", "Shot")

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
     * 一键清空全部槽位的拖动偏移（issue#170 #5 追修：配置随覆盖更新/跨版本保留，
     * 旧布局时代拖出来的偏移可能把按钮顶出屏幕，而出屏的按钮点不到、开不了
     * 单槽「恢复位置」——只有全量入口救得回来）。只清 offx./offy.，键位与尺寸
     * 存档不动；调用方要自己把内存里的偏移 state 一并归零（同 [reset] 的注意）。
     */
    fun resetAllOffsets(ctx: Context) {
        prefs(ctx).edit().apply {
            (SLOT_DEFAULTS.keys + STICK_SLOTS).forEach { slot ->
                remove(KEY_OFF_X + slot)
                remove(KEY_OFF_Y + slot)
            }
        }.apply()
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

    // —— 编辑会话快照（REQ 手柄编辑器 二期 5，2026-11-15）——
    // 进编辑态（✎）时对当前配置文件全量快照，编辑面板「撤销」原样写回：兜住整个
    // 编辑会话的误操作（改键 / 拖位 / 滑尺寸 / 恢复默认都算），单次拖动的撤销栈放三期。
    // 快照含 tip.editing_shown 在内的**全部键**，恢复即回到进编辑那一刻的完整状态；
    // ✓ 收工由调用方丢弃快照（释放内存），不落盘。

    /** 全量快照当前配置（含编辑说明看过标记等全部键，键位/尺寸/偏移四类前缀自然覆盖） */
    fun snapshot(ctx: Context): Map<String, Any?> = prefs(ctx).all

    /**
     * 把快照原样写回（先清后写，快照里没有的键一并清掉 —— 严格回到快照那一刻）。
     * 值类型按 SharedPreferences 支持的五种逐个落；读取方（bit/sizeDp/offset）会
     * 自动回落默认值，无需额外处理。
     */
    fun restore(ctx: Context, snap: Map<String, Any?>) {
        prefs(ctx).edit().clear().apply {
            snap.forEach { (k, v) ->
                when (v) {
                    is Int -> putInt(k, v)
                    is Float -> putFloat(k, v)
                    is Boolean -> putBoolean(k, v)
                    is Long -> putLong(k, v)
                    is String -> putString(k, v)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, v as Set<String>)
                }
            }
        }.apply()
    }

    /** bit 的显示名（配置界面用）：面键按 ABXY 字母显示（issue#170 #7，见 [KEY_CHOICES]） */
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
