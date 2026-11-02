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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import com.allperiph.ui.miuix.ApAccent
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApMotion
import top.yukonga.miuix.kmp.basic.Text
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

    // 审查条 2.3：侧列此前固定 320dp，600dp 屏上手势面只剩 280dp（手机横屏正好落在
    // 640–720dp 这一段）。改成按宽度取比例并夹在 180–320dp：600dp → 228dp（手势面 372dp），
    // 842dp 以上才回到 320dp 的旧观感。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val wide = screenWidthDp >= 600
    val sideWidth = (screenWidthDp * 0.38f).coerceIn(180f, 320f).dp
    Row(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.weight(1f)) {
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
            // 手势提示改为叠加层：浮在手势区顶部，不再占一行挤压手势面。
            // v223.27：服务没启动时这条提示**常驻**并换成真实原因 —— 此时触控板模块根本
            // 没注册（AgentController.module(TOUCHPAD) 为 null），feed() 收到的手势全被
            // 丢弃，用户看到的就是「触摸板控制不了、界面不动」，而界面不给任何解释。
            // 判定用 AgentController.running（启动开关的单一真相，1s 轮询之外这里直接读）。
            val serviceOff = !com.allperiph.ui.AgentController.running
            // 服务在跑也未必有出口：没选受控设备、USB 没挂上、蓝牙没连 —— 手势同样发不出去。
            // [HidMouse.exit] 只是**预计**出口（读内存，不发送），正是这里要的判据；
            // 旧界面在无出口时同样只打日志、屏幕上什么都不说，用户只能得出「触摸板坏了」。
            val noExit = !serviceOff && !com.allperiph.hid.HidMouse.available()
            if (hintVisible || serviceOff || noExit) {
                Text(
                    text = when {
                        serviceOff -> ctx.getString(R.string.ui_touchpad_hint_service_off)
                        noExit -> ctx.getString(R.string.ui_touchpad_hint_no_uplink)
                        else -> ctx.getString(R.string.ui_touchpad_text_cursor_swipe)
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topBar + 8.dp, start = 16.dp, end = 16.dp),
                )
            }
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

/** 快捷键网格：数据来自 [HotkeyStore]，发送走 [HidKeys.combo] / [HidKeys.hold] */
@Composable
private fun HotkeyGrid(ctx: Context, topBar: Dp, bottomGap: Dp) {
    // 管理器改完就 +1，让下面的 remember 重算 —— 否则改完要退出重进才看到新网格
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

    Column(
        // 横屏右列顶部避开玻璃顶栏；底部避开浮动底栏（窄屏/宽屏同样生效）
        modifier = Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp + topBar, bottom = 8.dp + bottomGap),
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
        // 编辑入口（审查 v5 §9.2-B）：v223.27 把键盘页的组合键面板与编辑弹窗（KbWindow）
        // 整块删掉后，主壳里就没有「改一颗组合键」的地方了 —— 键盘页只能改自定义布局。
        // 这里复用旧控制台那份 HotkeyBoard.managerDialog()（上移 / 下移 / 改键 / 删除 /
        // 新建 / 自动排序），数据仍是同一份 HotkeyStore。列表为空时也画这一个按钮，
        // 免得「全删光之后再也没有入口」。
        val act = ctx as? android.app.Activity
        if (act != null) {
            ApComponents.ActionButton(
                text = ctx.getString(R.string.ui_hotkey_title_edit),
                onClick = {
                    ApMotion.tap(ctx)
                    com.allperiph.ui.HotkeyBoard(
                        act,
                        android.widget.LinearLayout(act),
                        {
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
                        },
                        { editVersion++ },
                    ).managerDialog()
                },
            )
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
