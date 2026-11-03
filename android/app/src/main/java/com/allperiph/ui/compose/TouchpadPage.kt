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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
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
    // 手势面要尽量大：主壳不再容器级垫 64dp 顶栏与 bottomGap 底栏（v223.23 真机反馈
    // 「可操作区域压的很小」），本页连顶部 64dp 都不留 —— 手势面直接全屏，
    // 顶栏/底栏只是浮在手势面上层的玻璃；手势提示也改为叠加层，不再挤压手势面高度。
    // （本页自己的手势状态已整块搬进 [ApTouchpadSurface]，与悬浮球浮窗面板共用一份。）
    // 审查条 2.3：侧列此前固定 320dp，600dp 屏上手势面只剩 280dp（手机横屏正好落在
    // 640–720dp 这一段）。改成按宽度取比例并夹在 180–320dp：600dp → 228dp（手势面 372dp），
    // 842dp 以上才回到 320dp 的旧观感。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val wide = screenWidthDp >= 600
    val sideWidth = (screenWidthDp * 0.38f).coerceIn(180f, 320f).dp
    Row(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.weight(1f)) {
        // —— 手势面（与悬浮球浮窗面板**同一份**实现：ApTouchpadSurface）——
        // 手势识别 + 长按 550ms 拖拽/右键拖拽 + slop 门禁 + 光标绘制都在那个共用面里，
        // 悬浮面板不再各写半条链路（成宇 2026-11-02「悬浮窗触摸板手势和触摸板页手势不一样」）。
        ApTouchpadSurface(
            onGesture = onGesture,
            onLongPressArmDrag = onLongPressArmDrag,
            onLongPressRightDrag = onLongPressRightDrag,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        // —— 快捷键网格：窄屏在下方，宽屏（横屏/平板）挪到右侧独立列 ——
        if (!wide) HotkeyGrid(ctx = ctx, topBar = 0.dp, bottomGap = bottomGap)
      }
      // 横屏（issue #52 建议 4）：保持顶栏与底栏不变，只把快捷键挪到右侧窄列，
      // 主区仍是手势面 —— 不做"右上角按钮组"，也不隐藏底栏
      if (wide) {
          Box(modifier = Modifier.width(sideWidth).fillMaxHeight()) {
              HotkeyGrid(ctx = ctx, topBar = topBar, bottomGap = bottomGap)
          }
      }
    }
}

/**
 * 快捷键网格（v223.29.3 重做手势）。
 *
 * 成宇 2026-11-04「竖屏时触摸板的编辑快捷键改成长按按键编辑，可以自己左右拖动」：
 * 此前每块只能**轻点发送**，改一颗键得去最下面按「编辑快捷键」开管理列表、再逐行翻到「改」，
 * 排序更是只能靠列表里的 ↑↓。现在三件事都落在 chip 自己身上（与旧操控面 TouchpadActivity
 * 的 HotkeyBoard 同一套语义，只是手势在 Compose 侧重实现）：
 *
 *   · **轻点** → 发送一次组合键，并累计使用次数（自动排序照旧）；
 *   · **长按** → 原地松手即编辑这一颗（打开同一份 HotkeyBoard.editDialog）；
 *   · **长按后拖动** → 重排：落位判据与 HotkeyBoard.reorder 一致（先按中心 Y 找最近一行，
 *     行内再按中心 X 找最近一块），松手即落位并落盘，同时关掉自动排序
 *     （否则刚排好的顺序会被使用频次顶回去 —— 与 HotkeyBoard.preferManualOrder 同口径）。
 *
 * 长按既当编辑入口又当拖动入口，靠位移阈值区分：长按成立后手指一动就转成拖动意图，
 * 原地松手才是编辑（与 ComboChip 的「按住 / 拖动」分派同一做法）。
 *
 * ★ 手势是这一格的**单一真源**：down 在 Initial pass 就被本手势消费，内层 chip 的
 * `clickable` 在触摸路径上收不到这次按下（否则长按松手时它会先看到「全部抬起」，
 * 把组合键顺带发一次 —— 长按编辑变成「编辑 + 发送」）。语义节点仍由 clickable 提供，
 * 读屏点击走 HotkeyChip 的 onClick。
 *
 * 数据仍只有一份：[HotkeyStore]（自动排序开启时按频次展示）→ 编辑走 HotkeyBoard.editDialog，
 * 发送走 [HidKeys.combo]。**管理入口（成宇 2026-11-04「触摸板还是有个快捷键管理」）**：底部那枚「快捷键管理」
 * 按钮已删除。「新建 / 恢复默认 / 自动排序」只能从管理列表进（长按只管已有的那一颗），
 * 所以改走长按兜底 —— **长按网格空白处**打开管理列表；列表被删空时另画一枚「＋」格子
 * （否则整页空白，连按的地方都没有）。
 */
@Composable
private fun HotkeyGrid(ctx: Context, topBar: Dp, bottomGap: Dp) {
    // 主题色只能在**组合期**读：MiuixTheme 是 @Composable getter，放进 onClick
    // 会报 COMPOSABLE_INVOCATION（CI 第一次就是死在这里）。
    val chipTextColor = MiuixTheme.colorScheme.onSurface.toArgb()
    val chipBgColor = MiuixTheme.colorScheme.surface.toArgb()
    val subTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
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

    // 拖动状态：只记「当前拖的是哪一块」与累计位移，落位在松手时一次算清。
    // xy 坐标表随 editVersion 重建：每次落位/编辑后布局都会重排，旧矩形立刻过期。
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragDx by remember { mutableStateOf(0f) }
    var dragDy by remember { mutableStateOf(0f) }
    val cells = remember(editVersion) { androidx.compose.runtime.mutableStateMapOf<Int, Rect>() }

    /** 松手落位：把 [from] 挪到「原位置 + 累计位移」最近的那一格（判据同 HotkeyBoard.reorder） */
    fun drop(from: Int, dx: Float, dy: Float) {
        val origin = cells[from] ?: return
        if (cells.size < 2 || items.size < 2) return
        val cx = origin.center.x + dx
        val cy = origin.center.y + dy
        // 第一级：按中心 Y 找最近的一行
        val rows = cells.entries.groupBy { it.value.top }
        val nearestRow = rows.entries.minByOrNull { (_, g) ->
            kotlin.math.abs(g.map { it.value.center.y }.average().toFloat() - cy)
        }?.value ?: return
        // 第二级：行内按中心 X 找最近的一块
        val target = nearestRow.minByOrNull { kotlin.math.abs(it.value.center.x - cx) }?.key ?: return
        if (target == from || target !in items.indices) return
        val next = items.toMutableList()
        val moved = next.removeAt(from)
        next.add(target.coerceIn(0, next.size), moved)
        HotkeyStore.save(ctx, next)
        if (HotkeyStore.isAutoSort(ctx)) HotkeyStore.setAutoSort(ctx, false)
        editVersion++
    }

    // 管理列表的唯一出口（新建 / 恢复默认 / 自动排序）与编辑单颗共用的 Style。
    // ⚠️ 三处调用（空白长按、空列表「＋」、长按单颗改）必须共用这一个 Style 与一个
    // HotkeyBoard 构造 —— 早先这段在文件里重复了三份，改一处忘一处就分叉。
    val host = ctx as? android.app.Activity
    val kbStyle: () -> com.allperiph.ui.HotkeyBoard.Style = {
        com.allperiph.ui.HotkeyBoard.Style(
            chipText = chipTextColor,
            chipBg = chipBgColor,
            chipRadiusDp = 12,
            padVDp = 12,
            perRow = 4,
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
        // 横屏右列顶部避开玻璃顶栏；底部避开浮动底栏（窄屏 / 宽屏同样生效）
        modifier = Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp + topBar, bottom = 8.dp + bottomGap)
            // 长按空白处 = 管理列表（删除底部入口后的兜底）。
            // 键位格自己在 Initial pass 消费按下，所以这里只在没有格子的地方生效，
            // 不会与「长按单颗改这一颗」抢手势。
            .pointerInput(items.size) {
                detectTapGestures(onLongPress = { openManager() })
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 空列表兜底（成宇 2026-11-04「删空时」）：留一枚「＋」格子当入口。
        // 底部那枚「快捷键管理」按钮本轮删除（成宇 2026-11-04「触摸板还是有个快捷键管理」），
        // 「新建 / 恢复默认 / 自动排序」改走长按兜底 —— 长按空白处（见下面 Column 的
        // detectTapGestures）打开管理列表；列表被删空时还要有个能按到的东西，
        // 否则整页空白，连长按都无处落指。
        if (items.isEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(chipBgColor))
                        .clickable { openManager() }
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { openManager() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "＋", fontSize = 20.sp, color = Color(chipTextColor))
                }
                // 补空格：与 4 列网格同一形状，避免这一格被拉得奇宽
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
                            // 位置表：落位判据要用每块在窗口里的矩形。
                            // ⚠️ 正在被拖的那一格**不更新** —— 它已经随 offset 平移了，
                            // 写进去的话 drop() 里「原位置 + 累计位移」会把位移算两遍，
                            // 落位落到隔壁去（真机手感就是"拖一格跳两格"）。
                            .onGloballyPositioned { coords ->
                                if (dragIndex != index) cells[index] = coords.boundsInWindow()
                            }
                            .alpha(if (dragging) 0.85f else 1f)
                            // ★ 手势是这一格的**单一真源**：在 **Initial pass** 先把 down 消费掉，
                            //   让内层 chip 自己的 clickable 在**触摸**路径上永远收不到这次按下
                            //   （Compose 的事件派发是 Initial pass 父先子后，而 clickable 用
                            //   `awaitFirstDown()` 默认只认未消费的 down，于是它直接跳过本次手势）。
                            //   为什么必须这样：若两条路都活着，长按松手时 clickable 会在自己的
                            //   Main pass 先看到「全部抬起」并触发 onClick —— 结果是「长按编辑」
                            //   顺带把组合键也发了一次（重复发送）。
                            //   clickable 的**语义节点**不受影响，无障碍「点击」仍会走 HotkeyChip
                            //   的 onClick（发送），因此这里不再需要为读屏另补语义。
                            .pointerInput(index, items.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    val slop = viewConfiguration.touchSlop
                                    var liftedEarly = false
                                    var movedEarly = false
                                    // 长按超时窗口：抬手 → 轻点；先动 → 当滑动处理（放弃）
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
                                        // 轻点：发一次组合键（并累计使用次数，自动排序照旧）
                                        ApMotion.tap(ctx)
                                        HidKeys.combo(combo)
                                        HotkeyStore.bump(ctx, combo)
                                        return@awaitEachGesture
                                    }
                                    if (movedEarly) {
                                        // 长按窗口内就滑走了：不是这块的意图，放弃本次手势
                                        // （down 已在 Initial pass 消费，chip 也不会误触发送）
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                                            ev.changes.forEach { it.consume() }
                                            if (ev.changes.none { it.pressed }) break
                                        }
                                        return@awaitEachGesture
                                    }
                                    // —— 长按成立：先给一下震动，手感上「咔」一声 ——
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
                                            // 原地按着不动也要吃掉事件，避免内层任何组件被激活
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
                                        // 原地松手 = 编辑这一颗（与「＋」/ 空白长按同一个 Style）
                                        val act = host ?: return@awaitEachGesture
                                        newBoard(act).editDialog(index) { editVersion++ }
                                    }
                                }
                            },
                    ) {
                        HotkeyChip(ctx = ctx, combo = combo, modifier = Modifier.fillMaxWidth())
                    }
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
    // 快捷键皮肤（审查条 1.2）：`ApSkin.HOTKEY` 此前全仓零消费者——设置页「皮肤」段有
    // 「快捷键」一行，选了任何预设都没有任何界面变化。迁移前是有的（旧 MainActivity:871
    // 与 TouchpadFragment:271 用 ThemeSkin.HOTKEY 的强调色给 chip 上色）。这里接回：
    // 走 ApPalette.accentOf(ctx, ApSkin.HOTKEY)——选了皮肤取它，没选就落回主题主色，
    // 与全局 MiuixTheme 同一取色口径（不含另一套 ThemeSkin 值）。
    val tint = remember(ctx) {
        Color(com.allperiph.ui.ApPalette.accentOf(ctx, com.allperiph.ui.ApSkin.HOTKEY))
    }
    ApComponents.TabChip(
        text = combo.label,
        selected = false,
        accent = tint,
        onClick = {
            ApMotion.tap(ctx)
            HidKeys.combo(combo)
            HotkeyStore.bump(ctx, combo)
        },
        modifier = modifier,
    )
}
