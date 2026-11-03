package com.allperiph.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationAttributes
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

    /**
     * 键帽的**按下态**反馈：**只出声、不震动**。
     *
     * 震动已经由 `apKeyFeel`（ui\/compose\/ApPress.kt）在按下的瞬间给过了（那才是实体键的手感）。
     * 若这里再调一次 [tap]，一次按键会震两下（按下 + 抬手），用户只会觉得「震得发麻」。
     * 所以键帽的 onClick 走这里：声音留在抬手（与真实键盘的「松开响」一致），
     * 震动留在按下。其它按钮（页签 / 图块 / 页面级按钮）没有按下态手势，仍用 [tap]。
     */
    fun release(v: View) = sound(v)

    /** 只震动（长按连发等场景避免声音刷屏） */
    fun haptic(v: View) = haptic(v.context)

    /**
     * 长按「按住」反馈：按下手指、**还没抬手**就先给一次震动。
     *
     * 成宇 2026-11-04「长按按键没震动，是否有做用」：此前所有可点面（键帽、页签、
     * 媒体图块、低栏）都只接在 `clickable` 的 onClick 上 —— 反馈发生在**抬手**那一刻，
     * 而且长按本身根本没有承载任何动作。指尖压下去到抬起来这段时间里一点触觉都没有，
     * 手感上就是「按了半天没反应，松开才咔一下」。
     *
     * 这里给「按下即震」一个统一入口：键帽在按下时调 [down]，长按成立时再调
     * [Feedback.haptic]；两者共用 [haptic] 的档位与属性，不新造第二套参数。
     */
    fun down(v: View) = haptic(v.context)

    /**
     * 按键类触觉的属性：**必须显式给**，否则振动会被系统静默丢掉（成宇 2026-11-03
     * 「震动也没有，还有其他震动也没了」）。
     *
     * 真机取证（2509FPN0BC / HyperOS 4）：
     *   · `settings get system haptic_feedback_enabled` = `0`；
     *   · 由此 `dumpsys vibrator_manager` 的 `VibrationIntensities` 里
     *     **UNKNOWN = OFF、TOUCH = OFF、IME = OFF**（其余 MEDIUM）；
     *   · 本 App 的历史条目：`usage: TOUCH` 共 62 条**全部 `ignored_for_settings`**
     *     （一条都没真正执行），机械档的 waveform 则落 `usage: UNKNOWN`，同样被丢。
     *
     * 根因是 `vibrate(effect)` 不带属性时，系统按 `USAGE_UNKNOWN`（部分机型推导成
     * `USAGE_TOUCH`）判档，而这两档恰好被用户的系统开关关了 —— 于是「设置页里选了
     * 『强』照样不震」。仓库里没有任何代码写过那个系统开关，所以这不是我们改坏的。
     *
     * 修法：本 App 的档位由**设置页的「震动」下拉**表达，系统开关不该再叠一层否决 ——
     * 显式声明 `USAGE_HARDWARE_FEEDBACK`（「硬件/按键反馈」语义，与按键振动完全对口，
     * 且该档在本机是 MEDIUM = 开）。语义比 UNKNOWN 准确，也不再依赖哪个 ROM 怎么推导。
     */
    private fun attrs(): VibrationAttributes =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

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
        val attributes = attrs()
        runCatching {
            if (KeyPref.isMechanical(context)) {
                // 机械键帽：三段波形（按下冲击 → 触点停顿 → 回弹），模拟实体轴的段落感
                vib.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 10, 20, 6),
                        intArrayOf(0, amp, 0, amp / 2),
                        -1,
                    ),
                    attributes,
                )
            } else {
                vib.vibrate(VibrationEffect.createOneShot(ms, amp), attributes)
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