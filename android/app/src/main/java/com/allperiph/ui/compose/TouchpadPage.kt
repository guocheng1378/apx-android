package com.allperiph.ui.compose

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import com.allperiph.ui.miuix.ApAccent
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApMotion
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 触控板页（阶段 3）：手势面 + 液态光标 + 快捷键网格。
 *
 * ## 与旧实现的关系
 * 旧链路是 `MainActivity.onTouchEvent → TouchpadFragment.onTouchEvent → feedGesture +
 * TouchCursorView.onGesture`（三处 MotionEvent 处理 + 一个自绘 View）。
 * 这里把**展示**全部换成 Compose，**事件**仍走同一条 `TouchpadModule.feed(MotionEvent)` ——
 * 手势识别是模块层逻辑（`touchpad/`），不属于界面层，迁移不改它。
 *
 * 关键取舍：手势面用 `Modifier.pointerInteropFilter` 直接取到原始 MotionEvent，
 * 而不是 `detectDragGestures`。后者会把事件拆成 `PointerInputChange`，
 * 还原成 MotionEvent 时丢失 `pointerCount`／`actionMasked` 的多指语义，
 * 而模块的双指右键拖拽正好依赖这两个字段 —— 换掉等于把双指手势废掉。
 *
 * ## 与旧实现的差异（如实记录）
 *  · 液态光标：旧 `TouchCursorView` 有拖尾/形变（多帧残影），这里是最小可用版
 *    （跟随手指的圆点 + 按压缩放），观感差异需要在真机上确认是否可接受；
 *  · 横屏分栏（`TouchpadSplit`）未迁移：本页固定单列；
 *  · 顶部「文件发送 / 剪贴板」两个入口未迁移，仍由旧主界面提供。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadPage(
    ctx: Context,
    onGesture: (MotionEvent) -> Unit,
    onLongPressArmDrag: () -> Unit,
    onLongPressRightDrag: () -> Unit,
) {
    val density = LocalDensity.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    var hintVisible by remember { mutableStateOf(true) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val accent = remember { ApAccent.resolved(ctx) }

    val dragTask = remember {
        Runnable {
            onLongPressArmDrag()
            hintVisible = true
        }
    }
    val rightDragTask = remember { Runnable { onLongPressRightDrag() } }

    Column(modifier = Modifier.fillMaxSize()) {
        // —— 手势提示（手指落下后收起，长按锁定拖拽时重新出现）——
        if (hintVisible) {
            Text(
                text = ctx.getString(R.string.ui_touchpad_text_cursor_swipe),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // —— 手势面 ——
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInteropFilter { ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pressed = true
                            hintVisible = false
                            cursor = Offset(ev.x, ev.y)
                            handler.postDelayed(dragTask, 550)
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            if (ev.pointerCount == 2) handler.postDelayed(rightDragTask, 550)
                        }
                        MotionEvent.ACTION_MOVE -> cursor = Offset(ev.x, ev.y)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            pressed = false
                            handler.removeCallbacks(dragTask)
                            handler.removeCallbacks(rightDragTask)
                        }
                    }
                    // 先更新光标，再把原始事件交给模块 —— 与旧顺序一致（先画后发）
                    onGesture(ev)
                    true
                },
        ) {
            val pos = cursor
            if (pos != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = with(density) { if (pressed) 26.dp.toPx() else 22.dp.toPx() }
                    drawCircle(
                        color = Color(accent).copy(alpha = 0.22f),
                        radius = r,
                        center = pos,
                    )
                    drawCircle(
                        color = Color(accent).copy(alpha = 0.85f),
                        radius = r * 0.42f,
                        center = pos,
                    )
                }
            }
        }

        // —— 快捷键网格 ——
        HotkeyGrid(ctx = ctx)
    }
}

/** 快捷键网格：数据来自 [HotkeyStore]，发送走 [HidKeys.combo] / [HidKeys.hold] */
@Composable
private fun HotkeyGrid(ctx: Context) {
    val items = remember {
        val list = HotkeyStore.load(ctx)
        if (HotkeyStore.isAutoSort(ctx)) {
            val usage = HotkeyStore.loadUsage(ctx)
            list.sortedByDescending { usage[HotkeyStore.comboKey(it)] ?: 0 }
        } else {
            list
        }
    }
    if (items.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { combo ->
                    HotkeyChip(ctx = ctx, combo = combo, modifier = Modifier.weight(1f))
                }
                // 补空格：网格形状稳定（与旧 HotkeyBoard 的同一约定）
                repeat(4 - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HotkeyChip(ctx: Context, combo: HidKeys.Combo, modifier: Modifier = Modifier) {
    TextButton(
        text = combo.label,
        onClick = {
            ApMotion.tap(ctx)
            HidKeys.combo(combo)
            HotkeyStore.bump(ctx, combo)
        },
        modifier = modifier,
    )
}
