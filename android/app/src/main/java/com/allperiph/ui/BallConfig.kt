package com.allperiph.ui

import android.content.Context
import com.allperiph.R

/**
 * 悬浮球快捷项配置 —— 「能自己改功能」的落点。
 *
 * 此前菜单是写死的（连接模式三颗 + 触控板 / 设备 / 文件 / 退出），用户不能增删，
 * 这与 MIUI / HyperOS 快捷球的体验差很远：那边的球展开后是**用户自己挑的那几项**。
 *
 * 这里只做一件事：一张 id 清单（顺序即菜单顺序），其余交给
 * [FloatingBallService] 渲染、交给设置页「悬浮球功能」多选对话框编辑。
 *
 * ⚠️ [ALL] 是**全部可选项**，[DEFAULT] 只用于「用户从没改过」的首次体验 ——
 * 两者都不能空，否则菜单展开后是一颗空球（点哪儿都没反应，比不展开还糟）。
 *
 * 图标目前仍用 res/drawable 下的 ic_apx_* 矢量。⚠️ 2026-11-07 批 U-5（d2432a8）把
 * [iconRes] 的引用改成了 ic_outline_* 描边系列，但对应的 12 个资源没随提交落地 ——
 * 结果是 Android 构建（R.drawable.ic_outline_* 未解析）与「资源使用」关卡（12 个
 * ic_apx_* 变成死资源）双红。这里先回退引用保证可编译；等描边系列资源真的加进
 * res/drawable 后，把 [iconRes] 整体切过去、并顺手删掉随之变死的 ic_apx_*。
 */
object BallConfig {

    const val TOUCHPAD = "touchpad"
    const val DEVICES = "devices"
    const val FILES = "files"
    const val MODE_WIFI = "mode_wifi"
    const val MODE_BT = "mode_bt"
    const val MODE_USB = "mode_usb"
    const val CONSOLE = "console"
    const val KEYBOARD = "keyboard"
    const val GAMEPAD = "gamepad"
    const val CLIPBOARD = "clipboard"
    const val SCREEN = "screen"

    /**
     * 遥控器（2026-11-11 悬浮栏整合补挂）：原服务层悬浮栏四入口之一（拉起
     * [com.allperiph.ui.RemoteActivity] 完整页）—— 栏拆除后由球菜单承接，
     * 不丢入口。与 [SCREEN] 同属「拉起完整页」类。
     */
    const val REMOTE = "remote"

    /** 摄像头：手机画面回传本机（CameraPushActivity）。此前只能由 PC 面板或通知栏拉起，
     *  应用内没有任何入口 —— 用户「找不到摄像头」就是这里缺的。 */
    const val CAMERA = "camera"
    const val QUIT = "quit"

    /**
     * 物理键盘全局转发（无障碍，无需 root）—— 成宇 2026-11-07「悬浮球菜单加上
     * 切换开关，方便不用的时候关闭」。开关类快捷项：点一下就地翻转，不开面板。
     */
    const val KB_FORWARD = "kb_forward"

    /** 全部可选项（设置页勾选列表按此顺序排，也是菜单顺序） */
    val ALL: List<String> = listOf(
        TOUCHPAD, KEYBOARD, GAMEPAD, DEVICES, FILES, CLIPBOARD, SCREEN, REMOTE, CAMERA,
        MODE_WIFI, MODE_BT, MODE_USB, CONSOLE, KB_FORWARD, QUIT,
    )

    /**
     * 最新照片卡片（ROADMAP §5.2 ①，2026-11-12）：悬浮球菜单顶部**常驻**项 ——
     * 不进 [ALL]（设置页勾不到，也不受用户增删影响），每次展开菜单固定排在第一位。
     * 显示最新一张照片/截图的缩略图；点按或拖出即发往电脑（9512，见 [BallLatestPhoto]）。
     */
    const val PHOTO_CARD = "photo_card"

    /** 首次打开时的默认项 —— 与旧版写死菜单的主干一致 + 遥控器（悬浮栏整合后承接其入口）。
     * 直读项已随功能整体移除（issue#168 2026-11-11：开关闪退且实测无效，用户要求移除），
     * 老清单里存有的 pointer_read 会在 [enabled] 的白名单过滤里自然掉落。 */
    val DEFAULT: List<String> = listOf(
        TOUCHPAD, KEYBOARD, DEVICES, CLIPBOARD, FILES, KB_FORWARD, REMOTE, QUIT,
    )

    /** 快捷项直径（dp）：够手指点，又不至于把扇形撑得满屏（2026-11-11 用户「展开比例不对太大了」再降一档） */
    const val ITEM_DP = 46

    /** 扇形半径（dp）：球心到快捷项中心的距离（v70 整档下调后用户仍嫌大，2026-11-11 再降） */
    const val ARC_RADIUS_DP = 66

    // ———————————————— 菜单尺寸档（2026-11-07 成宇「悬浮球弹出的窗口要能调节大小」） ————————————————
    //
    // 此前快捷项直径 52dp、扇形半径 96dp 都是**写死的常量**：小屏机上扇形铺到半屏、
    // 点哪都像在挡内容；大屏机上 52dp 又偏小、边缘项容易点空。三档同时缩放
    // 「项直径 + 半径」，设置页点一下就切，下次展开即生效（菜单是每次现建的）。
    const val MENU_COMPACT = 0
    const val MENU_NORMAL = 1
    const val MENU_LARGE = 2

    private const val KEY_MENU_SCALE = "menu_scale"

    private const val PREF = "apx_ball_config"
    private const val KEY_ITEMS = "items"

    /**
     * 当前菜单尺寸档（越界值按标准档处理）。
     *
     * v223.29.73（UI 审查发现 8）：用户没改过时按屏幕定默认 —— 平板（最短边 ≥600dp，
     * 与 ApAdaptive 的 Medium 起点同口径）默认 Large 档，46dp/66dp 的标准档在平板上
     * 相对偏小；未存值以 -1 哨兵区分「没设过」，用户在设置页点过档位后永远尊重用户。
     */
    fun menuScale(ctx: Context): Int {
        val saved = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_MENU_SCALE, -1)
        if (saved in MENU_COMPACT..MENU_LARGE) return saved
        val shortDp = minOf(
            ctx.resources.configuration.screenWidthDp,
            ctx.resources.configuration.screenHeightDp,
        )
        return if (shortDp >= 600) MENU_LARGE else MENU_NORMAL
    }

    fun setMenuScale(ctx: Context, value: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MENU_SCALE, value.coerceIn(MENU_COMPACT, MENU_LARGE))
            .apply()
    }

    /** 快捷项直径（dp），随尺寸档（2026-11-11 三档同步下调，展开比例更收敛） */
    fun itemDp(ctx: Context): Int = when (menuScale(ctx)) {
        MENU_COMPACT -> 40
        MENU_LARGE -> 54
        else -> ITEM_DP
    }

    /** 扇形半径（dp），随尺寸档 —— 与 [itemDp] 同涨同缩，项之间的间距才不会被拉散 */
    fun arcRadiusDp(ctx: Context): Int = when (menuScale(ctx)) {
        MENU_COMPACT -> 54
        MENU_LARGE -> 78
        else -> ARC_RADIUS_DP
    }
    /** v223.25 升级补挂标记：键盘/手柄两项加入菜单后只补一次，尊重用户之后的手工增删 */
    private const val KEY_MIGRATED_V2 = "migrated_v2"

    fun labelRes(id: String): Int = when (id) {
        TOUCHPAD -> R.string.ui_ball_fn_touchpad
        DEVICES -> R.string.ui_ball_fn_devices
        FILES -> R.string.ui_ball_fn_files
        MODE_WIFI -> R.string.ui_ball_menu_wifi
        MODE_BT -> R.string.ui_ball_menu_bt
        MODE_USB -> R.string.ui_ball_menu_usb
        CONSOLE -> R.string.ui_ball_fn_console
        KEYBOARD -> R.string.ui_ball_fn_keyboard
        GAMEPAD -> R.string.ui_ball_fn_gamepad
        CLIPBOARD -> R.string.ui_ball_fn_clipboard
        SCREEN -> R.string.ui_ball_fn_screen
        REMOTE -> R.string.ui_remote_text_remote
        CAMERA -> R.string.ui_ball_fn_camera
        KB_FORWARD -> R.string.ui_ball_fn_kb_forward
        QUIT -> R.string.ui_ball_fn_quit
        else -> R.string.ui_ball_fn_touchpad
    }

    fun iconRes(id: String): Int = when (id) {
        TOUCHPAD -> R.drawable.ic_apx_touchpad
        DEVICES -> R.drawable.ic_apx_devices
        FILES -> R.drawable.ic_apx_files
        MODE_WIFI -> R.drawable.ic_apx_wifi
        MODE_BT -> R.drawable.ic_apx_bt
        MODE_USB -> R.drawable.ic_apx_usb
        CONSOLE -> R.drawable.ic_apx_expand
        KEYBOARD -> R.drawable.ic_apx_keyboard
        GAMEPAD -> R.drawable.ic_apx_gamepad
        CLIPBOARD -> R.drawable.ic_apx_clipboard
        SCREEN -> R.drawable.ic_apx_screen
        REMOTE -> R.drawable.ic_apx_remote
        CAMERA -> R.drawable.ic_apx_camera
        KB_FORWARD -> R.drawable.ic_apx_kb_fwd
        QUIT -> R.drawable.ic_apx_power
        else -> R.drawable.ic_apx_touchpad
    }

    /** 开关类快捷项（球上点一下翻转状态、不开面板）—— 与 [transportOf] 互斥 */
    fun isToggle(id: String): Boolean = id == KB_FORWARD

    /** 连接模式类快捷项对应的出口标识（[AgentController.TRANSPORTS] 同口径）；非模式类返回 null */
    fun transportOf(id: String): String? = when (id) {
        MODE_WIFI -> "wifi"
        MODE_BT -> "bt"
        MODE_USB -> "usb"
        else -> null
    }

    /**
     * 当前启用的快捷项。
     *
     * 存的是**逗号串**而不是 StringSet：Set 无序，而菜单顺序就是用户看到的顺序，
     * 顺序丢了这件事在 UI 上没法解释（用户勾选的先后会决定球的展开布局）。
     */
    fun enabled(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return DEFAULT
        val picked = raw.split(",").map { it.trim() }.filter { it in ALL }
        return if (picked.isEmpty()) DEFAULT else picked
    }

    fun setEnabled(ctx: Context, ids: List<String>) {
        val kept = ids.filter { it in ALL }.distinct()
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, if (kept.isEmpty()) DEFAULT.joinToString(",") else kept.joinToString(","))
            .apply()
    }

    /** v223.29 升级补挂标记：剪贴板 / 副屏两项加入菜单后只补一次 */
    private const val KEY_MIGRATED_V3 = "migrated_v3"

    /** 摄像头项加入菜单后的补挂标记（同样只补一次，之后尊重用户增删） */
    private const val KEY_MIGRATED_V4 = "migrated_v4"

    /** 全局转发开关项加入菜单后的补挂标记（同样只补一次；V5 历史上还含已移除的直读项） */
    private const val KEY_MIGRATED_V5 = "migrated_v5"

    /** 遥控器项（悬浮栏整合）加入菜单后的补挂标记（同样只补一次） */
    private const val KEY_MIGRATED_V6 = "migrated_v6"

    /**
     * 升级补挂（v223.25）：键盘 / 手柄两项加入菜单后，老用户已存过勾选清单的话看不见新项。
     * 这里只把**清单里没有**的两项追加到尾部，一次即可（标记位防重复）；用户之后删掉
     * 就尊重用户，不再补。
     */
    fun ensureNewItems(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_MIGRATED_V2, false)) {
            val cur = p.getString(KEY_ITEMS, null)
            if (cur != null) {
                val kept = cur.split(",").map { it.trim() }
                val add = listOf(KEYBOARD, GAMEPAD).filter { it !in kept }
                if (add.isNotEmpty()) {
                    p.edit().putString(KEY_ITEMS, (kept + add).joinToString(",")).apply()
                }
            }
            p.edit().putBoolean(KEY_MIGRATED_V2, true).apply()
        }
        // v223.29：成宇反馈「悬浮窗要加剪贴板，副屏功能」—— 同样只补一次，
        // 且仍尊重用户之后的手工增删（删了就删了，不再回补）
        if (!p.getBoolean(KEY_MIGRATED_V3, false)) {
            val cur = p.getString(KEY_ITEMS, null)
            if (cur != null) {
                val kept = cur.split(",").map { it.trim() }
                val add = listOf(CLIPBOARD, SCREEN).filter { it !in kept }
                if (add.isNotEmpty()) {
                    p.edit().putString(KEY_ITEMS, (kept + add).joinToString(",")).apply()
                }
            }
            p.edit().putBoolean(KEY_MIGRATED_V3, true).apply()
        }
        // 全局转发开关项：只补一次 —— 老用户已存过勾选清单的话，不补就永远看不到。
        // （root 直读项随功能移除，V5 迁移只剩转发一项；历史上 V5 只补过这两项，
        // 直读从未进过清单的老用户 V5 标记可能已置位，这里维持原判断不变即可。）
        if (!p.getBoolean(KEY_MIGRATED_V5, false)) {
            val cur = p.getString(KEY_ITEMS, null)
            if (cur != null) {
                val kept = cur.split(",").map { it.trim() }
                val add = listOf(KB_FORWARD).filter { it !in kept }
                if (add.isNotEmpty()) {
                    p.edit().putString(KEY_ITEMS, (kept + add).joinToString(",")).apply()
                }
            }
            p.edit().putBoolean(KEY_MIGRATED_V5, true).apply()
        }

        // 摄像头项：同样只补一次 —— 老用户已存过勾选清单的话，不补就永远看不到这一项
        if (!p.getBoolean(KEY_MIGRATED_V4, false)) {
            val cur = p.getString(KEY_ITEMS, null)
            if (cur != null) {
                val kept = cur.split(",").map { it.trim() }
                if (CAMERA !in kept) {
                    p.edit().putString(KEY_ITEMS, (kept + CAMERA).joinToString(",")).apply()
                }
            }
            p.edit().putBoolean(KEY_MIGRATED_V4, true).apply()
        }

        // 遥控器项（悬浮栏整合 V6，2026-11-11）：服务层悬浮栏拆除后其「遥控器」入口
        // 由球菜单承接 —— 老用户已存过勾选清单的话，不补就永远看不到这一项
        if (!p.getBoolean(KEY_MIGRATED_V6, false)) {
            val cur = p.getString(KEY_ITEMS, null)
            if (cur != null) {
                val kept = cur.split(",").map { it.trim() }
                if (REMOTE !in kept) {
                    p.edit().putString(KEY_ITEMS, (kept + REMOTE).joinToString(",")).apply()
                }
            }
            p.edit().putBoolean(KEY_MIGRATED_V6, true).apply()
        }
    }
}
