package com.allperiph.ui

import android.content.Context
import com.allperiph.R

/**
 * 整套外观主题（成宇 2026-11-04「做成主题最好」）。
 *
 * ## 为什么要有它
 * 现在「按键长什么样 / 按下去画什么 / 震不震 / 响不响」分散在四份偏好里：
 *   · [ApSkin]     三个模块各自的强调色（触控板 / 键盘 / 快捷键）
 *   · [KeyPref]    键帽材质、按键特效、键位密度
 *   · [ApFeel]     震动档位、音效档位
 *   · [ApBackdrop] 背景（预设底色 / 相册图 / 动态）
 * 用户想「换个整体感觉」得逐个下拉改五项以上，改完还不知道凑起来好不好看。
 *
 * 这里把「一套搭配」命名固化成一个 [Preset]，一键写进上面四处；写入口只有 [apply]，
 * 因此「整套主题」与手动逐项设置的存储口径完全一致 —— 不是第二套状态机。
 *
 * ## 与「导出/导入主题」的关系
 * [ApSkin.exportJson] / [importJson] 带走的是**当前选择**（含键帽、特效、密度）。
 * 这里的预设是**起点**：一键套上之后仍可逐项微调，微调结果照样能导出成文件。
 *
 * ## 关于震动与音效
 * 预设不改 [ApFeel.HAPTIC_LABELS] 的档位含义，只给一个搭配建议值；用户在
 * 「快捷键与外设 → 通用」里随时可以再改。档位为「关」时 [Feedback] 内部会拦，
 * 所以「静默」这套是真的完全不震。
 */
object ApThemePresets {

    /**
     * @param skinId 三个模块共用的一套皮肤 id（空 = 跟随主题）
     * @param keycap [KeyPref.KEYCAP_FLAT] / MECHANICAL / GLASS
     * @param fx     [KeyPref.FX_NONE] / RIPPLE / HIGHLIGHT / BOUNCE
     * @param haptic [ApFeel.HAPTIC_LABELS] 下标
     * @param sound  [ApFeel.SOUND_LABELS] 下标
     */
    data class Preset(
        val id: String,
        /** 名称文案（走 strings.xml，CI 的 i18n 棘轮按 Kotlin 里的中文字面量计数） */
        val nameRes: Int,
        /** 一句话说明这套搭配 */
        val descRes: Int,
        val skinId: String,
        val keycap: Int,
        val fx: Int,
        val haptic: Int,
        val sound: Int,
    )

    /** 内置四套（顺序即界面顺序）；都只在「外观」维度取一个明确搭配 */
    val ALL: List<Preset> = listOf(
        Preset(
            id = "quiet",
            nameRes = R.string.ui_theme_preset_quiet,
            descRes = R.string.ui_theme_preset_quiet_desc,
            skinId = "",
            keycap = KeyPref.KEYCAP_FLAT,
            fx = KeyPref.FX_NONE,
            haptic = 0,
            sound = 0,
        ),
        Preset(
            id = "mech",
            nameRes = R.string.ui_theme_preset_mech,
            descRes = R.string.ui_theme_preset_mech_desc,
            skinId = "blue",
            keycap = KeyPref.KEYCAP_MECHANICAL,
            fx = KeyPref.FX_RIPPLE,
            haptic = 2,
            sound = 3,
        ),
        Preset(
            id = "glass",
            nameRes = R.string.ui_theme_preset_glass,
            descRes = R.string.ui_theme_preset_glass_desc,
            skinId = "cyan",
            keycap = KeyPref.KEYCAP_GLASS,
            fx = KeyPref.FX_HIGHLIGHT,
            haptic = 1,
            sound = 2,
        ),
        Preset(
            id = "bouncy",
            nameRes = R.string.ui_theme_preset_bouncy,
            descRes = R.string.ui_theme_preset_bouncy_desc,
            skinId = "violet",
            keycap = KeyPref.KEYCAP_MECHANICAL,
            fx = KeyPref.FX_BOUNCE,
            haptic = 3,
            sound = 1,
        ),
    )

    fun name(ctx: Context, p: Preset): String = ctx.getString(p.nameRes)
    fun desc(ctx: Context, p: Preset): String = ctx.getString(p.descRes)

    /** 一键套用：皮肤 + 键帽 + 特效 + 震动 + 音效，一次写完 */
    fun apply(ctx: Context, p: Preset) {
        ApSkin.SCOPES.forEach { (scope, _) ->
            ApSkin.set(ctx, scope, p.skinId.ifBlank { null })
        }
        KeyPref.setKeycapStyle(ctx, p.keycap)
        KeyPref.setEffect(ctx, p.fx)
        ApFeel.setHaptic(ctx, p.haptic)
        ApFeel.setSound(ctx, p.sound)
        applyRevState.intValue += 1
    }

    /**
     * 「刚刚套过主题」的版本号（Compose 可观察）。
     *
     * 设置页里键帽 / 特效 / 震动 / 音效四个下拉的初值都是 `remember{}` 首次组合读到的，
     * 一键套完之后那几个下拉仍显示旧档，与真正生效的配置不一致。以这个版本号为 key
     * 重读初值即可 —— 不必 recreate 整个界面（这套只改外观偏好，重建反而更重）。
     */
    private val applyRevState = androidx.compose.runtime.mutableIntStateOf(0)
    internal val applyRev: Int get() = applyRevState.intValue

    /**
     * 当前状态是否与某个预设一致（设置页用来高亮「现在就是这套」）。
     * 只比这五个维度，不看密度/背景 —— 那两项与「观感主题」关系较弱，
     * 比进去会让几乎所有用户都落成「自定义」。
     */
    fun matches(ctx: Context, p: Preset): Boolean {
        val skinOk = ApSkin.SCOPES.all { (scope, _) -> ApSkin.picked(ctx, scope) == p.skinId }
        return skinOk &&
            KeyPref.keycapStyleIndex(ctx) == p.keycap &&
            KeyPref.effectIndex(ctx) == p.fx &&
            ApFeel.hapticIndex(ctx) == p.haptic &&
            ApFeel.soundIndex(ctx) == p.sound
    }
}