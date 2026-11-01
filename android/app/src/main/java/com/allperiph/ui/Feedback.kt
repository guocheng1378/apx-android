package com.allperiph.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.SoundEffectConstants
import android.view.View
import com.allperiph.R

/**
 * 按键反馈（v1.31）：震动 + 声音，**强度与音色都能在设置页换**。
 *
 * · 震动档位（[ApFeel.HAPTIC_LABELS]）：关 / 轻 / 标准 / 强 —— 用 [VibrationEffect.createOneShot]
 *   直接给时长与振幅，比预置 EFFECT_CLICK 更可控（也不受各家 ROM 效果表的差异影响）；
 * · 音效档位（[ApFeel.SOUND_LABELS]）：关 / 点击 / 键盘敲击 ——
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
    fun haptic(v: View) = haptic(v.context)

    /**
     * 只震动 —— **没有 View 的场景**（模块层，如触控板手势）走这个重载。
     *
     * ★ 全项目只有这一处真正调 Vibrator，别处不要再自己拿 Vibrator：
     * 此前触控板长按走的是 `createPredefined(EFFECT_CLICK)` 的旁路，两个后果 ——
     *   1. 预置效果各 ROM 支持不一，不少机型直接没反应（用户报"长按没震动"）；
     *   2. 它绕开了 [ApFeel.hapticIndex] 档位，设置里选了"关"照样震。
     * 统一走这里：振幅/时长自己给（不受 ROM 效果表影响），且尊重用户的档位设置。
     */
    fun haptic(context: Context) {
        // (时长 ms, 振幅 1..255)：轻 / 标准 / 强；"关"直接返回
        val ms: Long
        val amp: Int
        when (ApFeel.hapticIndex(context)) {
            1 -> { ms = 12L; amp = 70 }
            2 -> { ms = 18L; amp = 150 }
            3 -> { ms = 28L; amp = 255 }
            else -> return
        }
        val vib = context.getSystemService(Vibrator::class.java) ?: return
        if (!vib.hasVibrator()) return
        // 防御：按键反馈属非关键路径，个别 ROM 即便声明了 VIBRATE 仍可能抛
        // SecurityException，绝不能让它把整个页面带崩（曾实测点页签闪退）。
        runCatching {
            if (KeyPref.isMechanical(context)) {
                // 机械键帽：三段波形（按下冲击 → 触点停顿 → 回弹），模拟实体轴的段落感
                vib.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 10, 20, 6),
                        intArrayOf(0, amp, 0, amp / 2),
                        -1,
                    )
                )
            } else {
                vib.vibrate(VibrationEffect.createOneShot(ms, amp))
            }
        }
    }

    /** 按键音：随 [ApFeel] 的音效档位（"键盘"用系统键盘敲击音） */
    fun sound(v: View) {
        when (ApFeel.soundIndex(v.context)) {
            1 -> v.playSoundEffect(SoundEffectConstants.CLICK)
            2 -> v.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
            3 -> mechSound(v.context)
        }
    }

    // —————————————————————————— 机械轴音 ——————————————————————————

    private var pool: SoundPool? = null
    private var clickId = 0

    /**
     * 机械轴音：SoundPool 播 res/raw/mech_click（短噪声脉冲，红/茶轴手感）。
     * 不用 playSoundEffect——它跟随系统「触摸音效」开关与通知音量，关掉就没声；
     * 机械档要独立可听，音色也可控（青轴的"咔哒"感靠双段脉冲合成）。
     */
    private fun mechSound(ctx: Context) {
        if (pool == null) {
            pool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build()
                )
                .build()
            clickId = pool!!.load(ctx, R.raw.mech_click, 1)
        }
        pool?.play(clickId, 0.8f, 0.8f, 1, 0, 1f)
    }
}
