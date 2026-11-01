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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration

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
    // 用户反馈「触摸板没有画触摸的形状」：旧版只有一个跟随主指针的圆点，
    // 多指看不到、滑动看不到方向。这里改成**每根手指一个圈 + 最近轨迹尾迹**，
    // 抬手依旧全部清空（旧的"抬手留蓝点挡显示"问题不能复发）。
    var fingers by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var trail by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var pressed by remember { mutableStateOf(false) }
    val accent = remember { ApAccent.resolved(ctx) }

    val dragTask = remember {
        Runnable {
            onLongPressArmDrag()
            hintVisible = true
        }
    }
    // issue #52 问题 4：双指滚动常被判成双指右键。根因是长按计时器不看位移 ——
    // 两指一落就排 550ms，滚动途中照样触发。这里加"位移超过 touchSlop 就作废"的门禁。
    // （变量必须先声明再被下面的 Runnable 捕获，Kotlin 不允许前向引用局部变量）
    val slop = LocalViewConfiguration.current.touchSlop
    var downX by remember { mutableStateOf(0f) }
    var downY by remember { mutableStateOf(0f) }
    var scrolledAway by remember { mutableStateOf(false) }
    val rightDragTask = remember { Runnable { if (!scrolledAway) onLongPressRightDrag() } }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    Row(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.weight(1f)) {
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
                            trail = emptyList()
                            fingers = (0 until ev.pointerCount).map { Offset(ev.getX(it), ev.getY(it)) }
                            downX = ev.x
                            downY = ev.y
                            scrolledAway = false
                            handler.postDelayed(dragTask, 550)
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            fingers = (0 until ev.pointerCount).map { Offset(ev.getX(it), ev.getY(it)) }
                            if (ev.pointerCount == 2) handler.postDelayed(rightDragTask, 550)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val now = (0 until ev.pointerCount).map { Offset(ev.getX(it), ev.getY(it)) }
                            fingers = now
                            // 尾迹只记主指针，最多 24 个点（约半秒），够看出滑动方向又不糊成一片
                            now.firstOrNull()?.let { trail = (trail + it).takeLast(24) }
                            // 位移一旦超过 slop，本次触摸就是"滑动/滚动"，不再是长按候选
                            if (!scrolledAway) {
                                val dx = ev.x - downX
                                val dy = ev.y - downY
                                if (dx * dx + dy * dy > slop * slop) {
                                    scrolledAway = true
                                    handler.removeCallbacks(dragTask)
                                    handler.removeCallbacks(rightDragTask)
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            pressed = false
                            handler.removeCallbacks(dragTask)
                            handler.removeCallbacks(rightDragTask)
                            // 抬手即隐：旧版被控时屏幕正中留一个蓝点，反馈里被点名"挡显示"
                            fingers = emptyList()
                            trail = emptyList()
                        }
                    }
                    // 先更新光标，再把原始事件交给模块 —— 与旧顺序一致（先画后发）
                    onGesture(ev)
                    true
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 尾迹：越旧越淡越细，用来体现滑动方向与速度
                trail.forEachIndexed { i, p ->
                    val k = (i + 1f) / trail.size.toFloat()
                    drawCircle(
                        color = Color(accent).copy(alpha = 0.12f * k),
                        radius = with(density) { (12.dp * k).toPx() },
                        center = p,
                    )
                }
                // 每根手指一个圈：主指最大，其余略小，避免两指重合时看不出是几指
                fingers.forEachIndexed { i, p ->
                    val r = with(density) { if (pressed) 26.dp.toPx() else 22.dp.toPx() } * (1f - 0.12f * i)
                    drawCircle(color = Color(accent).copy(alpha = 0.20f), radius = r, center = p)
                    drawCircle(color = Color(accent).copy(alpha = 0.85f), radius = r * 0.42f, center = p)
                }
            }
        }

        // —— 快捷键网格：窄屏在下方，宽屏（横屏/平板）挪到右侧独立列 ——
        if (!wide) HotkeyGrid(ctx = ctx)
      }
      // 横屏（issue #52 建议 4）：保持顶栏与底栏不变，只把快捷键挪到右侧窄列，
      // 主区仍是手势面 —— 不做"右上角按钮组"，也不隐藏底栏
      if (wide) {
          Box(modifier = Modifier.width(320.dp).fillMaxHeight()) {
              HotkeyGrid(ctx = ctx)
          }
      }
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
