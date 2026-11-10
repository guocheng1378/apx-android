package com.allperiph.notify

import android.content.Context

/**
 * 通知同步开关（2026-11-11）：把手机通知推给 PC 托盘（0x40/0x42，能力位 bit0）。
 *
 * **默认关** —— 通知内容属于敏感个人信息（验证码、聊天摘要都可能进来），必须用户
 * 明确打开才生效；同时还需要系统「通知使用权」授权（Manifest 里的
 * ApxNotificationListener 声明，设置页有跳转入口）。两层都齐了才真正推。
 */
object NotificationSyncPref {
    private const val FILE = "apx_notify_sync"
    private const val KEY_ENABLED = "enabled"

    fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
