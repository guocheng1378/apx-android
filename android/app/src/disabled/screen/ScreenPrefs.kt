package com.allperiph.screen

import android.content.Context

/**
 * 副屏实验参数：分辨率、是否启用触控回送。
 * [ScreenThread] 启动时读取；改完需在状态页重启「副屏」开关后生效。
 */
object ScreenPrefs {
    private const val PREFS = "apx_screen_exp"
    private const val K_W = "width"
    private const val K_H = "height"
    private const val K_TOUCH = "touch"

    data class Config(
        val width: Int = 1280,
        val height: Int = 720,
        val touch: Boolean = false,
    )

    fun load(c: Context): Config {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            width = p.getInt(K_W, 1280),
            height = p.getInt(K_H, 720),
            touch = p.getBoolean(K_TOUCH, false),
        )
    }

    fun save(c: Context, cfg: Config) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt(K_W, cfg.width)
            putInt(K_H, cfg.height)
            putBoolean(K_TOUCH, cfg.touch)
            apply()
        }
    }
}
