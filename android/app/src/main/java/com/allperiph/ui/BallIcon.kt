package com.allperiph.ui

import android.content.Context
import com.allperiph.R

/**
 * 悬浮球球面图标的选择器（issue#168 第 9 条，2026-11-11）。
 *
 * 需求原话：「悬浮球支持选图片皮肤或内置多种图标」—— 本版先做**内置图标**六选一
 * （触控板 / 键盘 / 手柄 / 副屏 / 相机 / 文件），图片皮肤要走相册取图 + 适配球面
 * 单色 tint（皮肤原图没有 alpha 蒙版的话观感会崩），留到后续单独一批。
 *
 * 存储沿用 [FloatingBallService] 的偏好文件（`apx_floating_ball`，字符串与服务端
 * `PREFS` 常量同源）：球本身就是这个文件在读，不为一个键另开碎片文件。
 * 图标名只存资源名（如 `ic_apx_keyboard`），不存资源 ID —— 混淆/资源重排后 ID 会漂，
 * 资源名是稳定的；落 `res()` 时查表换回 ID，查不到回落触控板（与布局默认 src 同源）。
 */
object BallIcon {

    /** 与 FloatingBallService.PREFS 同一份文件（见类注释，避免偏好文件碎片化） */
    private const val PREFS = "apx_floating_ball"
    private const val KEY_ICON = "ball_icon"

    /** 可选的内置图标（资源名），顺序即设置页展示顺序，触控板打头沿用默认值 */
    val CHOICES = listOf(
        "ic_apx_touchpad",
        "ic_apx_keyboard",
        "ic_apx_gamepad",
        "ic_apx_screen",
        "ic_apx_camera",
        "ic_apx_files",
    )

    /** 当前选中项（资源名）；没存过 / 存的项已被删资源时回落触控板 */
    fun picked(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ICON, CHOICES[0])?.takeIf { CHOICES.contains(it) } ?: CHOICES[0]

    /** 资源名 → drawable ID 的白名单查表：不用 getIdentifier（错名静默返回 0 难排查） */
    fun res(name: String): Int = when (name) {
        "ic_apx_keyboard" -> R.drawable.ic_apx_keyboard
        "ic_apx_gamepad" -> R.drawable.ic_apx_gamepad
        "ic_apx_screen" -> R.drawable.ic_apx_screen
        "ic_apx_camera" -> R.drawable.ic_apx_camera
        "ic_apx_files" -> R.drawable.ic_apx_files
        else -> R.drawable.ic_apx_touchpad   // 默认项同时兜底未知名（含 CHOICES[0] 与历史脏数据）
    }

    /** 当前选中图标的 drawable ID（给球面 ImageView 直接 setImageResource 用） */
    fun drawable(c: Context): Int = res(picked(c))

    /** 存下选择。只落盘，不碰球 —— 即时生效走 FloatingBallService.applyIcon，两步分开好测试 */
    fun set(c: Context, name: String) {
        require(CHOICES.contains(name)) { "未知悬浮球图标：$name" }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ICON, name).apply()
    }
}
