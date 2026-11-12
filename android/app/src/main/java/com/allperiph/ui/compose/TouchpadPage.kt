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
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    // issue#168 2026-11-12「pad 竖屏时触控板页不左右分栏，保持上下布局」：
    // 双栏只认**横屏** —— 竖屏 pad（宽 ≥600dp）此前也会进 wide 右列，快捷键挤成竖条；
    // 回落单栏后走与手机竖屏同一套「手势面 + 下方快捷键区」布局。
    // 手机竖屏本就 twoPane=false，不受影响。
    val wide = adaptive.twoPane && adaptive.landscape
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
        modifier = Modifier.fillMaxSize(),
        // 2026-11-12 用户拍板「手势区也改撑满，其他页不动」：此前 Row 级左右挖孔
        // insets 把横屏手势面左右各垫掉一条（与键盘页 8b91988 同一稿 02 旧口径）。
        // 手势面整面无文字、指头落点不受键帽约束，挖孔回避废止 —— insets 只保留
        // 给快捷键栏（有文字，维持原垫边不变），手势面左右铺到屏缘。
    ) {
      Column(modifier = Modifier.weight(1f)) {
        ApTouchpadSurface(
            onGesture = onGesture,
            onLongPressArmDrag = onLongPressArmDrag,
            onLongPressRightDrag = onLongPressRightDrag,
            // issue#168（2026-11-11 触控板页清单）：竖屏快捷键区此前按屏高固定吃 1/3，
            // 3 排内容只占其中一半，底下剩一大块空档（2026-11-12 用户「切换后底下留了
            // 空地方」）。现改为快捷键栏高度固定 3 排（见 HotkeyGrid.fixedRows 与
            // HOTKEY_FIXED_ROWS），手势面吃剩下的全部高度；切换模板条数变化时，
            // 栏高不变、手势面也不再跟着跳。
            // 横屏双栏（wide）不动：手势面整高、快捷键在右列。
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        if (!wide) {
            Box(modifier = Modifier.padding(start = insets.left, end = insets.right)) {
                HotkeyGrid(ctx = ctx, topBar = 0.dp, bottomGap = bottomGap, fixedRows = HOTKEY_FIXED_ROWS)
            }
        }
      }
      if (wide) {
          Box(modifier = Modifier.width(sideWidth).fillMaxHeight()
              .padding(end = insets.right)) {
              HotkeyGrid(ctx = ctx, topBar = topBar, bottomGap = bottomGap)
          }
      }
    }
}

@Composable
private fun HotkeyGrid(ctx: Context, topBar: Dp, bottomGap: Dp, fixedRows: Int = 0) {
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
    // issue#168（2026-11-11 触控板页清单）：快捷键多到显示不下时可竖向滚动，滚动条按
    // 用户要求放在**左侧**（与全屏触控板页右列的滚动条同侧）。芯片区的竖向拖动仍归
    // 长按排序（Initial pass 消费优先于滚动），滚动主要走滚动条拖动与空隙处滑动。
    val gridScroll = androidx.compose.foundation.rememberScrollState()
    // 2026-11-12（用户「固定成3排，可以上下滑动」）：fixedRows > 0 时整块快捷键区
    // 固定为该行数的高度 —— 不足 3 排也占满（切换模板时条数变化不再引起整页跳动、
    // 也不再剩一块跟着屏高放大的空档），超过时上方 verticalScroll 照常滚动。
    // 总高 = 行数 × 芯片高（TabChip 的 TAB_CHIP_MIN_HEIGHT_DP 下限）+ 行间距
    // （与下方 spacedBy(6.dp) 同一口径）+ 自身边距（上 8+topBar / 下 8+bottomGap）。
    // fixedRows = 0（横屏右列）保持原样：高度随内容、超出滚外层给的高度。
    val hostModifier = if (fixedRows > 0) {
        Modifier.height(
            ApComponents.TAB_CHIP_MIN_HEIGHT_DP.dp * fixedRows +
                6.dp * (fixedRows - 1) +
                8.dp * 2 + topBar + bottomGap,
        )
    } else {
        Modifier
    }
    Box(modifier = Modifier.fillMaxWidth().then(hostModifier)) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp + topBar, bottom = 8.dp + bottomGap)
                .verticalScroll(gridScroll)
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
        // issue#168（2026-11-11 触控板页清单）：按钮宽按文字长度自适应 —— 每 2 个字占一个
        // 按钮位（4 个字占 2 位），一行总位数仍是 4 位、装不下折行；不再 4 枚等宽把长
        // 文案截断。packBySlots 返回 (条目下标, 占位数)，拖拽排序的 cells / drop 仍按
        // 条目下标走，排序逻辑零改动。
        packBySlots(items).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { (index, slots) ->
                    // packBySlots 只回「条目下标 + 占位数」；芯片本体仍取 items[index]，
                    // 与拖拽 cells / drop 用同一份下标，排序逻辑零改动。
                    val combo = items[index]
                    val dragging = dragIndex == index
                    Box(
                        modifier = Modifier
                            .weight(slots.toFloat())
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
                repeat(SLOTS_PER_ROW - row.sumOf { it.second }) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
        }
        // 左侧竖向滚动条：内容不超高时不画（VScrollbar 内部 maxValue<=0 直接返回）
        ApComponents.VScrollbar(
            scrollState = gridScroll,
            alignEnd = false,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 2.dp)
                .fillMaxHeight(),
        )
    }
}

/** 一行总按钮位数：与旧 4 枚等宽的每行个数一致，自适应只改每枚占几位。 */
private const val SLOTS_PER_ROW = 4

/**
 * 竖屏快捷键栏固定行数（2026-11-12 用户「固定成3排，可以上下滑动」）：
 * 不足 3 排也占满 3 排高，超过 3 排出竖向滚动 —— 换模板时栏高恒定，
 * 底下不再剩一块跟着屏高放大的空档。
 */
private const val HOTKEY_FIXED_ROWS = 3

/**
 * 按标签字数把快捷键打包成行（issue#168 2026-11-11「每 2 个字占一个按钮位」）：
 * 每条占 ceil(字数 / 2) 位（至少 1 位、至多整行），一行装不下就折到下一行。
 * 返回的条目下标与 [HotkeyStore.load] 列表对齐，拖拽排序按它落位。
 */
private fun packBySlots(list: List<HidKeys.Combo>): List<List<Pair<Int, Int>>> {
    val rows = mutableListOf<List<Pair<Int, Int>>>()
    var cur = mutableListOf<Pair<Int, Int>>()
    var used = 0
    list.forEachIndexed { idx, combo ->
        val slots = ((combo.label.length + 1) / 2).coerceIn(1, SLOTS_PER_ROW)
        if (used + slots > SLOTS_PER_ROW && cur.isNotEmpty()) {
            rows.add(cur)
            cur = mutableListOf()
            used = 0
        }
        cur.add(idx to slots)
        used += slots
    }
    if (cur.isNotEmpty()) rows.add(cur)
    return rows
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
