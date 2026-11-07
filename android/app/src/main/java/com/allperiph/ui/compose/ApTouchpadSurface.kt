package com.allperiph.ui.compose

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.miuix.ApAccent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 触控板手势面（**唯一实现**）—— 主壳触控板页与悬浮球浮窗面板共用这一份。
 *
 * ## 为什么必须抽出来（成宇 2026-11-02）
 * 悬浮面板此前只写了半条链路：`setOnTouchListener { feed(ev) }`，
 * 而主壳触控板页还会做「长按 550ms 进入拖拽 / 双指长按 550ms 右键拖拽」，
 * 且带 slop 门禁（位移一超 touchSlop 就作废，免得双指滚动被判成右键）。
 * 于是面板里长按没有任何反应、双指滚动还会误触发右键 —— 用户的原话是
 * 「悬浮窗触摸板手势和触摸板页手势不一样」。两处各写一份必然再跑偏，
 * 所以手势识别连同光标绘制一起收口到这里。
 *
 * ## 三条腿都在这里
 * 1. 事件：`MotionEvent` 原样交给 [onGesture] → `TouchpadModule.feed` → `GestureEngine`；
 *    **不走** `detectDragGestures`，它会丢 `pointerCount` / `actionMasked` 的多指语义，
 *    而双指右键拖拽正好依赖这两个字段；
 * 2. 长按：550ms 排程 + slop 门禁（[LocalViewConfiguration] 的 touchSlop）；
 * 3. 观感：每指一个圈 + 主指尾迹（抬手全清），颜色取强调色 [ApAccent]。
 *
 * ## 提示层
 * [hint] 为 null 时只按需显示内置提示（服务没启动 / 无出口）；给了非 null 就常驻显示它。
 * 悬浮面板传自己的标题说明，主壳页传 null 让它按状态给原因。
 *
 * @param onGesture 原始事件出口；返回 false 表示模块没注册（事件会被丢弃）
 * @param onLongPressArmDrag 长按 550ms → 拖拽锁定
 * @param onLongPressRightDrag 双指长按 550ms → 右键拖拽
 * @param hint 常驻提示（悬浮面板用）；null = 按服务/出口状态自动给
 * @param showFallbackHint 是否允许显示内置的「服务未启动 / 没出口」提示
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ApTouchpadSurface(
    onGesture: (MotionEvent) -> Unit,
    onLongPressArmDrag: () -> Unit,
    onLongPressRightDrag: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    showFallbackHint: Boolean = true,
) {
    val density = LocalDensity.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    var hintVisible by remember { mutableStateOf(true) }
    var fingers by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var trail by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var pressed by remember { mutableStateOf(false) }
    val slop = LocalViewConfiguration.current.touchSlop
    var downX by remember { mutableStateOf(0f) }
    var downY by remember { mutableStateOf(0f) }
    var scrolledAway by remember { mutableStateOf(false) }
    // 变量必须先声明再被 Runnable 捕获 —— Kotlin 不允许前向引用局部变量
    val dragTask = remember { Runnable { onLongPressArmDrag(); hintVisible = true } }
    val rightDragTask = remember { Runnable { if (!scrolledAway) onLongPressRightDrag() } }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accent = remember { ApAccent.resolved(ctx) }
    // ★ 判据是**触控板模块是否真的在跑**，而不是编排层的意愿位
    //   `AgentController.running`。两者会分叉，且分叉出来的正是本轮的现场：
    //   主界面被系统回收 / 被划掉之后，进程由悬浮球的闹钟自愈单起
    //   （见 FloatingBallKeepAlive），球与浮窗都在，模块也已由
    //   [com.allperiph.ui.FloatingBallService.ensureTouchpadReady] 拉起 ——
    //   可 `running` 仍是 false，旧判据于是让浮窗顶着「服务未启动」的提示，
    //   而手势其实发得出去（成宇 2026-11-02「触控板页提示服务未启动，与状态页
    //   显示运行中不一致」）。改成读模块状态：它既是「手势能不能发」的真正判据，
    //   也和状态页显示的是同一件事。
    //   另外轮询一次：模块起来得比首帧晚（start 含 root/configfs 阻塞操作，
    //   走 io 线程），不轮询的话提示会一直停在「服务未启动」。
    var moduleOn by remember {
        mutableStateOf(
            (com.allperiph.ui.AgentController.module(com.allperiph.core.ModuleId.TOUCHPAD)
                as? com.allperiph.touchpad.TouchpadModule)?.state?.isActive == true
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val on = (com.allperiph.ui.AgentController.module(com.allperiph.core.ModuleId.TOUCHPAD)
                as? com.allperiph.touchpad.TouchpadModule)?.state?.isActive == true
            if (on != moduleOn) moduleOn = on
            kotlinx.coroutines.delay(700L)
        }
    }
    val serviceOff = !moduleOn
    // 模块在跑也未必有出口：没选受控设备、USB 没挂上、蓝牙没连 —— 手势同样发不出去。
    // [HidMouse.exit] 只是预计出口（读内存，不发送），正是这里要的判据。
    val noExit = !serviceOff && !com.allperiph.hid.HidMouse.available()
    val autoHint = when {
        serviceOff -> ctx.getString(R.string.ui_touchpad_hint_service_off)
        noExit -> ctx.getString(R.string.ui_touchpad_hint_no_uplink)
        else -> ctx.getString(R.string.ui_touchpad_text_cursor_swipe)
    }

    Box(
        modifier = modifier.pointerInteropFilter { ev ->
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
        val shown = hint ?: autoHint
        val forceShow = hint != null || (showFallbackHint && (serviceOff || noExit))
        if (hintVisible || forceShow) {
            Text(
                text = shown,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                // 颜色代替长句（成宇 2026-11-07）：服务没开 / 没出口 = 红色
                // 阻断警示，正常手势说明保持灰 —— 不读字也能分辨能不能用。
                color = if (serviceOff || noExit)
                    androidx.compose.ui.res.colorResource(R.color.module_state_error)
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
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
}
