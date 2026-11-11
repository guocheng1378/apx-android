package com.allperiph.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo

/**
 * 方向锁定（2026-11-02 审查条 1.1 的回归修复）。
 *
 * ## 丢的是什么
 * 旧版有三个入口的「方向锁定」：顶栏 `btnOrientLock`、横屏快捷条 `btnLandLock`、设置页
 * `swOrientLock`，实现是 `MainActivity.restoreOrientLock()` / `setOrientLock()`——写
 * `SCREEN_ORIENTATION_LOCKED` 并把状态存进偏好。迁移到 Compose 主壳时整条链路一起没了：
 * 顶栏按钮在 `d00424e` 消失、设置页开关随 `page_settings.xml` 在 `401e080` 删除、
 * `restoreOrientLock` 随旧 `MainActivity.kt` 在 `c6734c8` 删除。此后全仓零
 * `requestedOrientation`，用户无法把界面钉在某个朝向。
 *
 * ## 与「重力判读自动切页」的关系（这一条容易搞混，写清楚）
 * 两者管的是**不同东西**，`b6dbdca` 恢复的重力判读就是为「系统开着方向锁」的场景准备的：
 *  · 方向锁定 = 版式（横/竖排、顶栏是否盖状态栏）钉住，`configuration.orientation` 不再翻转；
 *  · 重力判读 = 页面在「触控板 ↔ 键盘」之间自动切换，读的是加速度计，**不看**
 *    `configuration`，所以系统方向锁关不住它。
 * 因此本开关打开时**同时暂停重力切页**：用户按下「锁定」的意图是「别动」，只钉版式却让
 * 页面被重力翻走，等于锁了个寂寞。关闭后版式回到 `sensor`、重力切页恢复（旧行为）。
 *
 * 偏好沿用壳级那一个 `apx` 文件（与 `cursor_lock` 同处），不新开文件、不新造状态机。
 */
object OrientLock {

    /** 与光标锁定 / LocalSend 开关同一个偏好文件（`ApShellActivity` 与旧控制台都在用） */
    const val PREF = "apx"
    private const val KEY = "orient_lock"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, false)

    /**
     * 横持自动切到键盘页（成宇 2026-11-04「横竖屏切换好像被你强制了」）。
     *
     * 默认**关**：旧行为是「一转手机就换页」，与系统「竖屏锁定」直接冲突（重力判读不看
     * 系统方向锁），用户体感就是被强制。需要边走边用手势面的人可以显式打开。
     * 与 [isOn] 正交：开锁时不管这个开关都不切页。
     */
    private const val KEY_AUTO_SWAP = "orient_auto_swap"

    fun autoSwapEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_SWAP, false)

    fun setAutoSwap(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_SWAP, on).apply()
    }

    fun set(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, on).apply()
    }

    /**
     * 按偏好把当前 Activity 的朝向钉住 / 放开。
     *
     *  · 开 → [ActivityInfo.SCREEN_ORIENTATION_LOCKED]（钉在**当前**朝向，旋转手机不变版式）；
     *  · 关 → [ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED]（**跟随系统**）。
     *
     * ⚠️ 2026-11-04 改口径（成宇「横竖屏切换好像被你强制了」）：这里原来是
     * `SCREEN_ORIENTATION_SENSOR`，语义是「App 自己决定朝向」—— 系统里明明开了
     * 「旋转锁定 / 竖屏锁定」，App 还是会被转过去，用户体感就是「被它强制了」。
     * 改成 `UNSPECIFIED` 后朝向交回系统：用户锁了竖屏，App 就竖着；没锁则照常跟随重力。
     * 想钉住某一边仍可用本开关（开 = LOCKED）。
     *
     * 幂等：值没变时不重设，避免 onCreate/onResume 每次都被当成一次配置变更。
     */
    fun apply(activity: Activity) {
        val want = if (isOn(activity)) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            // 关 = 交回系统（尊重系统「旋转锁定」）。别再写 SENSOR —— 那会无视
            // 用户的系统级锁定，正是「被 App 强制横屏」的来源。
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (activity.requestedOrientation != want) activity.requestedOrientation = want
    }
}
