package com.allperiph.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View

/**
 * **View 体系弹窗的唯一入口**（2026-11-07 浮层收敛）。
 *
 * 为什么需要它：仓库里有两套弹窗通路，服务的是两套不同的渲染体系 ——
 *   · Compose 侧走 [com.allperiph.ui.miuix.ApComponents.DialogCard]（内部是
 *     `androidx.compose.ui.window.Dialog`），已统一，8 处调用方全在 `ui/compose/` 下；
 *   · View 侧此前各页自己 `AlertDialog.Builder(...)`，theme 传不传、按钮文案怎么定、
 *     危险动作用什么色，全凭各页记性。
 *
 * **不要**把 View 侧的弹窗改写成 Compose 的 `Dialog`：`FilePanelActivity`、
 * `HotkeyBoard`、`CustomKeyboardDialog`、`TouchpadActivity` 都是 `ComponentActivity`
 * 的 View 树，没有 Compose 宿主，套过去会直接崩。正确做法就是走这个门面。
 *
 * 分层选型见 `docs/ui-overlay-rules.md`。合规由 `scripts/check_appearance.py`
 * 第 6／7 条判红保证：`ui/` 下出现裸 `AlertDialog.Builder(` 即失败。
 */
object ApDialog {

    /**
     * 统一 theme 的 builder。需要 `setView` / `setOnShowListener` 这类
     * 只有 Builder 才给得到的自由度时用它，别自己 new。
     */
    fun builder(ctx: Context): AlertDialog.Builder =
        AlertDialog.Builder(ctx, ApPalette.dialogStyle())

    /** 二选一确认（不可逆动作）。按钮文案必须写具体动作，不要「确定／取消」。 */
    fun confirm(
        ctx: Context,
        title: CharSequence?,
        message: CharSequence?,
        positive: CharSequence,
        negative: CharSequence,
        onPositive: () -> Unit,
    ): AlertDialog = builder(ctx)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positive) { _, _ -> onPositive() }
        .setNegativeButton(negative, null)
        .create()
        .also { it.show() }

    /** 列表内高频选择（Popconfirm 场景的落地形态）。 */
    fun items(
        ctx: Context,
        title: CharSequence?,
        items: Array<out CharSequence>,
        onPick: (Int) -> Unit,
    ): AlertDialog = builder(ctx)
        .setTitle(title)
        .setItems(items) { _, which -> onPick(which) }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .also { it.show() }

    /** 带自定义 View 的确认框（限高滚动由调用方给的 View 自己负责）。 */
    fun customView(
        ctx: Context,
        title: CharSequence?,
        view: View,
        positive: CharSequence,
        negative: CharSequence,
        onPositive: () -> Unit,
    ): AlertDialog = builder(ctx)
        .setTitle(title)
        .setView(view)
        .setPositiveButton(positive) { _, _ -> onPositive() }
        .setNegativeButton(negative, null)
        .create()
        .also { it.show() }
}
