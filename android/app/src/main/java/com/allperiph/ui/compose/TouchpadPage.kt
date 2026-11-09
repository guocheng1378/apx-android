package com.allperiph.ui.compose

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApMotion
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import com.allperiph.ui.miuix.rememberApInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.withTimeoutOrNull
import com.allperiph.ui.Feedback
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

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
 *  · 横屏分栏（`TouchpadSplit`）未迁移：本页不做左右两块手势面（宽屏只把快捷键列挪到右列，
 *    见下方 `wide` 分支）；分屏拖拽胶水已随 `401e080` 删掉；
 *  · 旧触控板页那排入口（发送文件 / 发送剪贴板 / 副屏 / 手柄 / 遥控器 / 编辑快捷键）**没有**
 *    迁到本页，而是落到首页「快捷入口」卡（2026-11-02 审查条 1.6）：本页手势面要全屏，
 *    再叠一排按钮会把手势面压回去（v223.23 真机反馈「可操作区域太小」）。此前这段注释说
 *    「仍由旧主界面提供」，而旧主界面已在 `c6734c8` 删除 —— 注释与实现相反的漂移一并修掉。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadPage(
    ctx: Context,
    onGesture: (MotionEvent) -> Unit,
    onLongPressArmDrag: () -> Unit,
    onLongPressRightDrag: () -> Unit,
    topBar: Dp = 0.dp,
    bottomGap: Dp = 0.dp,
) {
    val adaptive = rememberApAdaptiveLayout()
    val screenWidthDp = adaptive.width.value
    val shortSide = minOf(screenWidthDp, adaptive.height.value)
    val wide = adaptive.twoPane
    val sideTarget = when {
        screenWidthDp >= 840f -> 268.dp
        screenWidthDp >= 600f -> 292.dp
        else -> (screenWidthDp * 0.38f).coerceIn(180f, 320f).dp
    }
    val sideWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = sideTarget,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = ApMotion.durationMs(ctx, 200),
            easing = ApMotion.EnterEasing,
        ),
        label = "sideWidth",
    )
    val insets = rememberApInsets()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = insets.left, end = insets.right),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        ApTouchpadSurface(
            onGesture = onGesture,
            onLongPressArmDrag = onLongPressArmDrag,
            onLongPressRightDrag = onLongPressRightDrag,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        if (!wide) HotkeyGrid(ctx = ctx, topBar = 0.dp, bottomGap = bottomGap)
      }
      if (wide) {
          Box(modifier = Modifier.width(sideWidth).fillMaxHeight()) {
              HotkeyGrid(ctx = ctx, topBar = topBar, bottomGap = bottomGap)
          }
      }
    }
}

@Composable
private fun HotkeyGrid(ctx: Context, topBar: Dp, bottomGap: Dp) {
    val chipTextColor = MiuixTheme.colorScheme.onSurface.toArgb()
    val chipBgColor = MiuixTheme.colorScheme.surface.toArgb()
    val subTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
    val gridFx = com.allperiph.ui.KeyPref.effectIndex(ctx)
    var editVersion by remember { mutableIntStateOf(0) }
    val items = remember(editVersion) {
        val list = HotkeyStore.load(ctx)
        if (HotkeyStore.isAutoSort(ctx)) {
            val usage = HotkeyStore.loadUsage(ctx)
            list.sortedByDescending { usage[HotkeyStore.comboKey(it)] ?: 0 }
        } else {
            list
        }
    }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragDx by remember { mutableStateOf(0f) }
    var dragDy by remember { mutableStateOf(0f) }
    val cells = remember(editVersion) { androidx.compose.runtime.mutableStateMapOf<Int, Rect>() }
    fun drop(from: Int, dx: Float, dy: Float) {
        val origin = cells[from] ?: return
        if (cells.size < 2 || items.size < 2) return
        val cx = origin.center.x + dx
        val cy = origin.center.y + dy
        val rows = cells.entries.groupBy { it.value.top }
        val nearestRow = rows.entries.minByOrNull { (_, g) ->
            kotlin.math.abs(g.map { it.value.center.y }.average().toFloat() - cy)
        }?.value ?: return
        val target = nearestRow.minByOrNull { kotlin.math.abs(it.value.center.x - cx) }?.key ?: return
        if (target == from || target !in items.indices) return
        val next = items.toMutableList()
        val moved = next.removeAt(from)
        next.add(target.coerceIn(0, next.size), moved)
        HotkeyStore.save(ctx, next)
        if (HotkeyStore.isAutoSort(ctx)) HotkeyStore.setAutoSort(ctx, false)
        editVersion++
    }
    val host = ctx as? android.app.Activity
    val kbStyle: () -> com.allperiph.ui.HotkeyBoard.Style = {
        com.allperiph.ui.HotkeyBoard.Style(
            chipText = chipTextColor,
            chipBg = chipBgColor,
            chipRadiusDp = com.allperiph.ui.HotkeyBoard.Style.HOME_CHIP_RADIUS_DP,
            padVDp = com.allperiph.ui.HotkeyBoard.Style.HOME_PAD_V_DP,
            perRow = com.allperiph.ui.HotkeyBoard.Style.HOME_PER_ROW,
            accent = chipTextColor,
            textPrimary = chipTextColor,
            textSecondary = subTextColor,
        )
    }
    fun newBoard(act: android.app.Activity) = com.allperiph.ui.HotkeyBoard(
        act,
        android.widget.LinearLayout(act),
        kbStyle,
        { editVersion++ },
    )
    val openManager: () -> Unit = {
        host?.let {
            ApMotion.tap(ctx)
            newBoard(it).managerDialog()
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp + topBar, bottom = 8.dp + bottomGap)
            .pointerInput(items.size) {
                detectTapGestures(onLongPress = { openManager() })
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (items.isEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(chipBgColor))
                        .apPress(gridFx, MiuixTheme.colorScheme.primary, openManager)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { openManager() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "＋", fontSize = 20.sp, color = Color(chipTextColor))
                }
                repeat(3) { Box(modifier = Modifier.weight(1f)) }
            }
        }
        items.chunked(4).forEachIndexed { rowIdx, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEachIndexed { colIdx, combo ->
                    val index = rowIdx * 4 + colIdx
                    val dragging = dragIndex == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .zIndex(if (dragging) 1f else 0f)
                            .offset {
                                if (dragging) IntOffset(dragDx.toInt(), dragDy.toInt())
                                else IntOffset(0, 0)
                            }
                            .onGloballyPositioned { coords ->
                                if (dragIndex != index) cells[index] = coords.boundsInWindow()
                            }
                            .alpha(if (dragging) 0.85f else 1f)
                            .pointerInput(index, items.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    val slop = viewConfiguration.touchSlop
                                    var liftedEarly = false
                                    var movedEarly = false
                                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!ch.pressed) {
                                                liftedEarly = true
                                                break
                                            }
                                            if ((ch.position - down.position).getDistance() > slop) {
                                                movedEarly = true
                                                break
                                            }
                                        }
                                        true
                                    }
                                    if (liftedEarly) {
                                        ApMotion.tap(ctx)
                                        HidKeys.combo(combo)
                                        HotkeyStore.bump(ctx, combo)
                                        return@awaitEachGesture
                                    }
                                    if (movedEarly) {
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                                            ev.changes.forEach { it.consume() }
                                            if (ev.changes.none { it.pressed }) break
                                        }
                                        return@awaitEachGesture
                                    }
                                    Feedback.haptic(ctx)
                                    var moved = false
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) {
                                            ch.consume()
                                            break
                                        }
                                        val d = ch.positionChange()
                                        if (!moved && d.getDistance() > slop) {
                                            moved = true
                                            dragIndex = index
                                        }
                                        if (moved) {
                                            dragDx += d.x
                                            dragDy += d.y
                                            ch.consume()
                                        } else {
                                            ch.consume()
                                        }
                                    }
                                    val dx = dragDx
                                    val dy = dragDy
                                    val wasDrag = moved
                                    dragIndex = -1
                                    dragDx = 0f
                                    dragDy = 0f
                                    if (wasDrag) {
                                        drop(index, dx, dy)
                                    } else {
                                        val act = host ?: return@awaitEachGesture
                                        newBoard(act).editDialog(index) { editVersion++ }
                                    }
                                }
                            },
                    ) {
                        HotkeyChip(ctx = ctx, combo = combo, modifier = Modifier.fillMaxWidth())
                    }
                }
                repeat(4 - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HotkeyChip(ctx: Context, combo: HidKeys.Combo, modifier: Modifier = Modifier) {
    val tint = remember(ctx) {
        Color(com.allperiph.ui.ApPalette.accentOf(ctx, com.allperiph.ui.ApSkin.HOTKEY))
    }
    val chipFx = remember(ctx) { com.allperiph.ui.KeyPref.effectIndex(ctx) }
    ApComponents.TabChip(
        text = combo.label,
        selected = false,
        accent = tint,
        onClick = {
            ApMotion.tap(ctx)
            HidKeys.combo(combo)
            HotkeyStore.bump(ctx, combo)
        },
        modifier = modifier
            .apKeyFeel(downFeel = false),
        fx = chipFx,
    )
}
