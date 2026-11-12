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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    // 横屏右列宽度收口到共用口径（issue#168 第 7 条「操控面与首页统一」）：
    // ApComponents.touchpadSideWidth 是唯一一份（平板 268/292 定宽、手机 38% 夹取 180–320），
    // 操控面 ConsoleTouchpadPage 的右列同调这里，宽度不再各写各的。
    val sideTarget = ApComponents.touchpadSideWidth(screenWidthDp)
    val sideWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = sideTarget,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = ApMotion.durationMs(ctx, 200),
            easing = ApMotion.EnterEasing,
        ),
        label = "sideWidth",
    )
    val insets = rememberApInsets()
    // 背景框分栏（issue#168 2026-11-12 #2「触控板和快捷键要分别加上背景框分栏，
    // 触控只在触控框内起作用」）：手势面与快捷键区各自包一张 surfaceContainer 圆角卡，
    // 中间留缝 —— 两块区域边界一眼可辨，触控引擎只在手势卡内吃事件（ApTouchpadSurface
    // 本来就只在自己 bounds 内消费 MotionEvent，卡片化不改变事件路由）。
    // 横屏手势面不加框：e7c917d 用户拍板「手势区也改撑满」，框会把左右又缩回去；
    // 框只给竖屏手势卡与两处快捷键区。
    val cardColor = MiuixTheme.colorScheme.surfaceContainer
    val cardShape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier.fillMaxSize(),
        // 2026-11-12 用户拍板「手势区也改撑满，其他页不动」：此前 Row 级左右挖孔
        // insets 把横屏手势面左右各垫掉一条（与键盘页 8b91988 同一稿 02 旧口径）。
        // 手势面整面无文字、指头落点不受键帽约束，挖孔回避废止 —— insets 只保留
        // 给快捷键栏（有文字，维持原垫边不变），手势面左右铺到屏缘。
    ) {
      Column(modifier = Modifier.weight(1f)) {
        if (wide) {
            // 横屏：手势面整高撑满（e7c917d 口径，无框）、快捷键在右列卡片里。
            ApTouchpadSurface(
                onGesture = onGesture,
                onLongPressArmDrag = onLongPressArmDrag,
                onLongPressRightDrag = onLongPressRightDrag,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            // 竖屏（issue#168 2026-11-12 #2）：下方 1/3 给快捷键区 —— 手势卡 weight(2f) +
            // 快捷键卡 weight(1f)，不再用「固定 3 排高」的旧口径（用户本条原话
            // 「竖屏时，快捷键占下方三分之一区域」为准）；快捷键多于一卡时卡内滚动
            //（见 HotkeyGrid），标题行固定在卡顶不随网格滚。
            Box(
                modifier = Modifier.fillMaxWidth().weight(2f)
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .clip(cardShape)
                    .background(cardColor),
            ) {
                ApTouchpadSurface(
                    onGesture = onGesture,
                    onLongPressArmDrag = onLongPressArmDrag,
                    onLongPressRightDrag = onLongPressRightDrag,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    // 「抬高快捷键区域，目前过低太靠近小白条」：底部垫到手势导航条之上
                    //（ insets.bottom = 导航条/小白条让出的高度，竖屏非零）。
                    .padding(start = insets.left, end = insets.right, bottom = insets.bottom)
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    .clip(cardShape)
                    .background(cardColor),
            ) {
                HotkeyGrid(ctx = ctx, topBar = 0.dp, bottomGap = 0.dp, showHead = true)
            }
        }
      }
      if (wide) {
          // 横屏右列（issue#168 2026-11-12 #2）：与全屏触控板（操控面）的右列同款 ——
          // 「常用快捷键 / 编辑」标题行固定在顶、网格超出滚动；整列包背景框与手势面分栏。
          Box(modifier = Modifier.width(sideWidth).fillMaxHeight()
              .padding(start = 8.dp, end = insets.right, top = 8.dp, bottom = 8.dp)
              .clip(cardShape)
              .background(cardColor)) {
              HotkeyGrid(ctx = ctx, topBar = topBar, bottomGap = bottomGap, showHead = true)
          }
      }
    }
}

@Composable
private fun HotkeyGrid(
    ctx: Context,
    topBar: Dp,
    bottomGap: Dp,
    /**
     * 顶部「常用快捷键 / 编辑」标题行（issue#168 2026-11-12 #2：与全屏触控板
     * HotkeyHead 同款样式，编辑入口同一套 HotkeyBoard 管理弹窗）——标题行固定
     * 在卡顶，不随网格滚动。
     */
    showHead: Boolean = false,
    /** 标题行的「编辑」按钮：HotkeyBoard 管理弹窗要 Activity 宿主，悬浮触控板面板
     * （Service overlay，FloatingHotkeyCard）开不了 —— 传 false 只画标题，编辑去 app 内。 */
    showEdit: Boolean = true,
) {
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
    // 2026-11-12（issue#168 #2「快捷键占下方三分之一，显示不全加滑动条」）：
    // 高度改由外层卡片决定（竖屏 weight(1f) = 屏高 1/3、横屏右列整列），
    // 标题行固定在顶（showHead）、网格区 weight 吃剩余高度并竖向滚动 ——
    // 「固定 3 排高」的旧口径随竖屏 1/3 布局废止（HOTKEY_FIXED_ROWS 已删）。
    // 2026-11-12 图2「…」修复：BoxWithConstraints 量卡片实际宽，窄列（横屏右列
    // 268dp、悬浮面板横屏 240dp 定宽）每行从 4 位降到 3 位 —— 4 位时 1-slot 芯片
    // 只有 50dp 出头，扣掉 TabChip 内边距后放不下两个 14sp 汉字，整排省略成「…」
    //（第 4 排起 2-slot 芯片反而正常，同源同宽却两副面孔即由此来）。宽卡片
    //（竖屏整宽、悬浮面板竖屏）仍是 4 位不减密度。
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 窄列判定：内容宽 280dp 以下降密度。280 = 4 位 × 60dp（两字 14sp 加内边距
        // 的舒适下限）+ 行距余量，卡在 280 附近的边界值真机观感需要在 v76 验证。
        val slotsPerRow = if (maxWidth < 280.dp) 3 else SLOTS_PER_ROW
        Column(modifier = Modifier.fillMaxSize()) {
            if (showHead) {
                // 标题行样式对齐全屏触控板 HotkeyHead：粗体标题 + primary「编辑」，
                // 点击弹 HotkeyBoard 管理弹窗（与网格长按同一入口）。
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp + topBar, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ui_touchpad_text_hotkey),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (showEdit) {
                        Text(
                            text = stringResource(R.string.ui_common_edit),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { openManager() }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp + bottomGap)
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
                repeat(slotsPerRow - 1) { Box(modifier = Modifier.weight(1f)) }
            }
        }
        // issue#168（2026-11-11 触控板页清单）：按钮宽按文字长度自适应 —— 每 2 个字占一个
        // 按钮位（4 个字占 2 位），一行总位数仍是 4 位、装不下折行；不再 4 枚等宽把长
        // 文案截断。2026-11-12 图2 修复：窄列一行降到 3 位（见上方 slotsPerRow）。
        // packBySlots 返回 (条目下标, 占位数)，拖拽排序的 cells / drop 仍按
        // 条目下标走，排序逻辑零改动。
        packBySlots(items, slotsPerRow).forEach { row ->
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
                repeat(slotsPerRow - row.sumOf { it.second }) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
        }
        } // 标题行之外的内容列（fillMaxSize）闭合：滚动条要画在 Box 层（盖在标题+网格上）
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
 * 按标签字数把快捷键打包成行（issue#168 2026-11-11「每 2 个字占一个按钮位」）：
 * 每条占 ceil(字数 / 2) 位（至少 1 位、至多整行），一行装不下就折到下一行。
 * [slotsPerRow] 一行总位数：宽卡 4、窄列 3（图2「…」修复，见 HotkeyGrid 注释）。
 * 返回的条目下标与 [HotkeyStore.load] 列表对齐，拖拽排序按它落位。
 */
private fun packBySlots(
    list: List<HidKeys.Combo>,
    slotsPerRow: Int = SLOTS_PER_ROW,
): List<List<Pair<Int, Int>>> {
    val rows = mutableListOf<List<Pair<Int, Int>>>()
    var cur = mutableListOf<Pair<Int, Int>>()
    var used = 0
    list.forEachIndexed { idx, combo ->
        val slots = ((combo.label.length + 1) / 2).coerceIn(1, slotsPerRow)
        if (used + slots > slotsPerRow && cur.isNotEmpty()) {
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
        // 内边距减到 10dp：1-slot 芯片在窄列里每 slot 只有 50–60dp，14dp×2 的
        // 内边距会把两个字挤出文字区（图2 前三排整排「…」的直接原因）；
        // 网格窄列同时降到 3 位（见 HotkeyGrid slotsPerRow），两边一起把字救回来。
        chipHPad = 10.dp,
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

/**
 * 悬浮触控板面板（FloatingBallService.expand）用的快捷键卡：[HotkeyGrid] 的公共入口。
 *
 * issue#168 2026-11-12 #8「悬浮球打开的悬浮触控板布局对齐 app 内触控板横竖屏布局」：
 * 面板里补上 app 内同款的「常用快捷键」卡 —— 同一份 [HotkeyStore] 数据、同一套
 * 点击直发 / 拖拽排序 / 卡内滚动。悬浮面板宿主是 Service（无 Activity），HotkeyBoard
 * 管理弹窗开不了，编辑按钮不画（长按同样空操作已由 openManager 兜住），编辑去 app 内触控板页。
 */
@Composable
fun FloatingHotkeyCard(ctx: Context, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer),
    ) {
        HotkeyGrid(ctx = ctx, topBar = 0.dp, bottomGap = 0.dp, showHead = true, showEdit = false)
    }
}
