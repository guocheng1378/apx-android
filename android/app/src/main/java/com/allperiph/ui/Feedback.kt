package com.allperiph.ui

import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.SoundEffectConstants
import android.view.View

/**
 * 按键反馈（v1.31）：震动 + 声音，**强度与音色都能在设置页换**。
 *
 * · 震动档位（[ThemeSkin.HAPTIC_LABELS]）：关 / 轻 / 标准 / 强 —— 用 [VibrationEffect.createOneShot]
 *   直接给时长与振幅，比预置 EFFECT_CLICK 更可控（也不受各家 ROM 效果表的差异影响）；
 * · 音效档位（[ThemeSkin.SOUND_LABELS]）：关 / 点击 / 键盘敲击 ——
 *   `View.playSoundEffect` 跟随系统「触摸音效」开关与通知音量。
 *
 * 需要 `android.permission.VIBRATE`（已在 AndroidManifest 声明）。
 */
object Feedback {

    /** 按键标准反馈：一下震动 + 一声轻响（档位可在设置页设成"关"） */
    fun tap(v: View) {
        haptic(v)
        sound(v)
    }

    /** 只震动（长按连发等场景避免声音刷屏） */
    fun haptic(v: View) {
        // (时长 ms, 振幅 1..255)：轻 / 标准 / 强；"关"直接返回
        val ms: Long
        val amp: Int
        when (ThemeSkin.hapticIndex(v.context)) {
            1 -> { ms = 12L; amp = 70 }
            2 -> { ms = 18L; amp = 150 }
            3 -> { ms = 28L; amp = 255 }
            else -> return
        }
        val vib = v.context.getSystemService(Vibrator::class.java) ?: return
        if (!vib.hasVibrator()) return
        vib.vibrate(VibrationEffect.createOneShot(ms, amp))
    }

    /** 按键音：随 [ThemeSkin] 的音效档位（"键盘"用系统键盘敲击音） */
    fun sound(v: View) {
        when (ThemeSkin.soundIndex(v.context)) {
            1 -> v.playSoundEffect(SoundEffectConstants.CLICK)
            2 -> v.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        }
    }
}
