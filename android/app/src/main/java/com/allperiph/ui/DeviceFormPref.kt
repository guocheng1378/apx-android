package com.allperiph.ui

import android.content.Context
import android.content.res.Configuration
import com.allperiph.R

/**
 * 设备形态（手机 / 平板 / 电视）—— 成宇 2026-11-05
 * 「是否有识别手机，平板，电视的设置」。
 *
 * ## 为什么需要一个「手动覆盖」
 *
 * [com.allperiph.ui.miuix.rememberApAdaptiveLayout] 一直是**按窗口宽度**分级的
 * （Compact / Medium / Expanded），这套判据不打算换 —— 它是唯一稳定的输入，
 * 分屏、折叠、投屏时都跟着实际窗口走，不会因为机型写死而错位。
 *
 * 但它分不出「形态」这一层语义：
 *   · 一台电视接到手机上跑本壳，窗口宽度也是 Expanded，可它**没有电池**、
 *     **没有触摸屏**、**遥控器只有方向键**，界面元素该更大、更少、更依赖焦点；
 *   · 一台折叠屏展开后也是 Expanded，但它就是手机，只是变宽了。
 *
 * 所以这里只加一件窗口宽度给不了的东西：**用户自己的选择**。
 * 默认 [AUTO] 保持现有行为不变（不改变任何排版），选别的才生效。
 *
 * ## 读取口径
 *
 * [resolve] 是唯一入口：先把偏好解成 [Form]，再叠加系统 `uiMode` 的电视判据。
 * 电视那条**不覆盖用户选择** —— 用户手动选了「手机」就按手机排，
 * 免得投屏 / 车机模式下界面突然变了个样。
 */
object DeviceFormPref {

    private const val PREF = "apx_form"
    private const val KEY_FORM = "form"

    /** 自动 / 强制手机 / 强制平板 / 强制电视 —— 顺序即设置页下拉框顺序 */
    const val AUTO = 0
    const val PHONE = 1
    const val TABLET = 2
    const val TV = 3

    /**
     * 下拉框文案。**必须走 strings.xml**（`scripts/check_ui_tokens.py` 的中文棘轮）：
     * 这里返回的是「按当前 Context 解析后的 4 条文案」，而不是写死的字面量 ——
     * 否则每加一处 UI 文案就要在同一提交里抬高 647 那个基线，等于把这条关卡做废。
     */
    fun labels(ctx: Context): List<String> = listOf(
        ctx.getString(R.string.ui_form_auto),
        ctx.getString(R.string.ui_form_phone),
        ctx.getString(R.string.ui_form_tablet),
        ctx.getString(R.string.ui_form_tv),
    )

    /** 每项的一句话解释，设置页副标题用（同样走 strings.xml） */
    fun summaries(ctx: Context): List<String> = listOf(
        ctx.getString(R.string.ui_form_sum_auto),
        ctx.getString(R.string.ui_form_sum_phone),
        ctx.getString(R.string.ui_form_sum_tablet),
        ctx.getString(R.string.ui_form_sum_tv),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun formIndex(ctx: Context): Int =
        prefs(ctx).getInt(KEY_FORM, AUTO).coerceIn(0, COUNT - 1)

    fun setFormIndex(ctx: Context, i: Int) {
        prefs(ctx).edit().putInt(KEY_FORM, i.coerceIn(0, COUNT - 1)).apply()
    }

    private const val COUNT = 4

    /** 解析后的实际形态 */
    enum class Form {
        Phone,
        Tablet,
        Tv;

        /** 是否按「大屏 / 非手持」口径排版（平板与电视共用的那一档） */
        val isLargeScreen: Boolean get() = this != Phone
    }

    /**
     * 当前生效形态。
     *
     * 判定顺序：
     *   1. 用户手动指定了形态 → 直接用（**不看** uiMode，用户说了算）；
     *   2. 系统报告 `UI_MODE_TYPE_TELEVISION` → [Form.Tv]；
     *   3. 否则按窗口宽度分级（与 [com.allperiph.ui.miuix.ApAdaptiveLayout.widthClass]
     *      同一条断点）：≥840dp 判平板，其余判手机。
     *
     * ⚠️ 第 3 步的宽度由调用方传入 `widthDp`（实测窗口宽度），**不要**在这里自己读
     * `resources.configuration.screenWidthDp` —— 本壳 Activity 声明了
     * `configChanges=orientation|screenSize`，旋转不回写时那个值会滞后一拍
     * （v223.29.8 / v223.29.9 修的就是这个坑）。
     */
    fun resolve(ctx: Context, widthDp: Float): Form {
        when (formIndex(ctx)) {
            PHONE -> return Form.Phone
            TABLET -> return Form.Tablet
            TV -> return Form.Tv
        }
        val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return Form.Tv
        return if (widthDp >= 840f) Form.Tablet else Form.Phone
    }

    /** 设置页显示用的当前值描述（「自动（当前：手机）」这种写法） */
    fun describe(ctx: Context, widthDp: Float): String {
        val i = formIndex(ctx)
        return if (i == AUTO) {
            ctx.getString(R.string.ui_form_auto_now, labels(ctx)[formToLabelIndex(resolve(ctx, widthDp))])
        } else {
            labels(ctx)[i]
        }
    }

    private fun formToLabelIndex(f: Form): Int = when (f) {
        Form.Phone -> PHONE
        Form.Tablet -> TABLET
        Form.Tv -> TV
    }
}