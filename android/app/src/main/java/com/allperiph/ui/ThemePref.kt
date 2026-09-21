package com.allperiph.ui

import android.content.Context
import android.content.res.Configuration

/**
 * 主题方案（MIUIX 架构：跟随系统 / 浅色 / 深色）。
 *
 * 实现走 [Context.createConfigurationContext] 覆盖 uiMode —— 不依赖 androidx、
 * 不需要任何权限；深色取值来自 `res/values-night/`，与亮色共用同一套
 * `@color/md_*` 令牌，所以全 App（主页 / 键盘面板 / 弹窗）一处改动全生效。
 *
 * 用法：Activity 覆盖 [android.content.ContextWrapper.attachBaseContext] 调 [wrap]；
 * 用户改选后 [set] + `recreate()` 即可立刻换肤。
 */
object ThemePref {
    const val AUTO = 0
    const val LIGHT = 1
    const val DARK = 2

    val LABELS = listOf("跟随系统", "浅色", "深色")

    private const val PREF = "apx_theme"
    private const val KEY_MODE = "mode"

    fun get(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_MODE, AUTO)

    fun set(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply()
    }

    /** 把用户选择注入资源配置；[AUTO] 时原样返回，交给系统日夜模式决定 */
    fun wrap(base: Context): Context {
        val mode = get(base)
        if (mode == AUTO) return base
        val conf = Configuration(base.resources.configuration)
        val night = if (mode == DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(conf)
    }
}
