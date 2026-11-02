package com.allperiph.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import com.allperiph.R

/**
 * 按键反馈（v1.31）：震动 + 声音，**强度与音色都能在设置页换**。
 *
 * · 震动档位（[ApFeel.HAPTIC_LABELS]）：关 / 轻 / 标准 / 强 —— 用 [VibrationEffect.createOneShot]
 *   直接给时长与振幅，比预置 EFFECT_CLICK 更可控（也不受各家 ROM 效果表的差异影响）；
 * · 音效档位（[ApFeel.SOUND_LABELS]）：关 / 点击 / 键盘 / 机械轴 ——
 *   全部走自带 SoundPool（媒体流），不受系统「触摸音效」开关影响。
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

    /**
     * 按键音：随 [ApFeel] 的音效档位。
     *
     * ⚠️ 三档**全部**走自带 SoundPool，不再用 `View.playSoundEffect` ——
     * 后者被系统「触摸音效」开关（`Settings.System.SOUND_EFFECTS_ENABLED`）管着，
     * 本机实测这个开关就是 `0`（关闭），于是"点击 / 键盘"两档一声不响，
     * 用户看到的现象正是"音效没有"。档位既然由 app 自己给，就别再受系统开关摆布。
     *
     * 三档用同一段 res/raw/mech_click，靠音量 + 音高区分：
     *   · 点击：轻、原调（短促"嗒"）
     *   · 键盘：中等、略高（更像笔记本键盘）
     *   · 机械轴：最响、原调（段落感最明显）
     */
    fun sound(v: View) {
        val ctx = v.context
        when (ApFeel.soundIndex(ctx)) {
            1 -> playKeySound(ctx, 0.30f, 1.00f)
            2 -> playKeySound(ctx, 0.55f, 1.20f)
            3 -> playKeySound(ctx, 0.85f, 1.00f)
        }
    }

    // —————————————————————————— 键音（SoundPool） ——————————————————————————

    private var pool: SoundPool? = null
    private var clickId = 0

    /**
     * 播放一次键音。
     *
     * 音轨走 **USAGE_MEDIA**（媒体流）而不是 SONIFICATION：后者落到系统流上，
     * 系统音量低或静音时依旧听不见，用户又要说"没声音"。媒体流一般有音量，
     * 且音量给得小（0.3~0.85），不会盖住正在放的音频。
     */
    private fun playKeySound(ctx: Context, volume: Float, pitch: Float) {
        if (pool == null) {
            pool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            clickId = pool!!.load(ctx, R.raw.mech_click, 1)
        }
        pool?.play(clickId, volume, volume, 1, 0, pitch)
    }
}
