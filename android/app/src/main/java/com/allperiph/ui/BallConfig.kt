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
 */
object BallConfig {

    const val TOUCHPAD = "touchpad"
    const val DEVICES = "devices"
    const val FILES = "files"
    const val MODE_WIFI = "mode_wifi"
    const val MODE_BT = "mode_bt"
    const val MODE_USB = "mode_usb"
    const val CONSOLE = "console"
    const val QUIT = "quit"

    /** 全部可选项（设置页勾选列表按此顺序排，也是菜单顺序） */
    val ALL: List<String> = listOf(
        TOUCHPAD, DEVICES, FILES, MODE_WIFI, MODE_BT, MODE_USB, CONSOLE, QUIT,
    )

    /** 首次打开时的默认四项 —— 与旧版写死菜单的主干一致 */
    val DEFAULT: List<String> = listOf(TOUCHPAD, DEVICES, FILES, QUIT)

    /** 快捷项直径（dp）：够手指点，又不至于把扇形撑得满屏 */
    const val ITEM_DP = 52

    /** 扇形半径（dp）：球心到快捷项中心的距离 */
    const val ARC_RADIUS_DP = 96

    private const val PREF = "apx_ball_config"
    private const val KEY_ITEMS = "items"

    fun labelRes(id: String): Int = when (id) {
        TOUCHPAD -> R.string.ui_ball_fn_touchpad
        DEVICES -> R.string.ui_ball_fn_devices
        FILES -> R.string.ui_ball_fn_files
        MODE_WIFI -> R.string.ui_ball_menu_wifi
        MODE_BT -> R.string.ui_ball_menu_bt
        MODE_USB -> R.string.ui_ball_menu_usb
        CONSOLE -> R.string.ui_ball_fn_console
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
        QUIT -> R.drawable.ic_apx_power
        else -> R.drawable.ic_apx_touchpad
    }

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
}
