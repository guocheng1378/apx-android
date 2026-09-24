package com.allperiph.camera

import android.content.Context

/**
 * 摄像头参数：前后摄、分辨率、帧率。
 * 持久化到独立 SharedPreferences；推流参数改后需重开模块生效。
 */
object CameraPrefs {
    private const val PREFS = "apx_camera_exp"
    private const val K_FACING = "facing"   // 0=后置, 1=前置
    private const val K_W = "width"
    private const val K_H = "height"
    private const val K_FPS = "fps"

    data class Config(
        val facing: Int = 0,
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 20,
    )

    fun load(c: Context): Config {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            facing = p.getInt(K_FACING, 0),
            width = p.getInt(K_W, 1280),
            height = p.getInt(K_H, 720),
            fps = p.getInt(K_FPS, 20),
        )
    }

    fun save(c: Context, cfg: Config) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt(K_FACING, cfg.facing)
            putInt(K_W, cfg.width)
            putInt(K_H, cfg.height)
            putInt(K_FPS, cfg.fps)
            apply()
        }
    }
}
