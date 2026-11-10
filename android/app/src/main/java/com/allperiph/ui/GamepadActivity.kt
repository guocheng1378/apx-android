package com.allperiph.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.res.Configuration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.allperiph.core.Uplink
import com.allperiph.core.UplinkEvent
import com.allperiph.shared.event.EventBus
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import com.allperiph.ui.miuix.applyEdgeToEdge
import androidx.compose.ui.res.colorResource
import com.allperiph.R

/**
 * 全屏游戏手柄（v1.x 起，v223.25 起只剩壳）：双虚拟摇杆 + 常用按键，经 [GamepadController]
 * 送上 USB HID（Report ID 22）／ 蓝牙 HID（rid=4）／ 无线 0x07。
 *
 * 面板本体抽成了 [GamepadPanel]（Compose），主屏 Activity 与悬浮球 overlay 的
 * 「手柄」快捷项共用同一块 UI 与同一套出口状态接线 —— Activity 只负责 KEEP_SCREEN_ON
 * 和 finish() 的落地方式。
 */
class GamepadActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    ApMiuixTheme {
                        GamepadPanel(onExit = { finish() })
                    }
                }
            },
        )
    }
}

/**
 * 悬浮手柄的「强制横屏」容器（成宇 2026-11-04「悬浮板的手柄，没有强制横屏」）。
 *
 * 悬浮手柄是 Service 挂的 WindowManager overlay，**不是 Activity** —— 没有
 * `requestedOrientation` 可设，系统也不会因为一个 overlay 去转屏。主屏入口
 * （[GamepadActivity]）靠清单里 `screenOrientation="landscape"` 强制横屏，
 * 悬浮入口此前没有任何对策，竖屏打开时一堆控件就挤在半屏里。
 *
 * 这里的做法：竖屏时把内容按「横屏尺寸」（宽 = 屏高、高 = 屏宽）布局再旋转 90°，
 * 视觉与手感都变成横屏，用户把手机横过来拿正好是正的。旋转走
 * `graphicsLayer.rotationZ`，Compose 会把指针坐标一并变换 —— 摇杆与按键的命中区
 * 不会错位（这是不能用「只画一张旋转图」的理由）。已经是横屏时不加包裹，原样渲染。
 */
@Composable
internal fun LandscapePanel(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        content()
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(colorResource(R.color.overlay_scrim))) {
        val landWidth = maxHeight
        val landHeight = maxWidth
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = landWidth, height = landHeight)
                .graphicsLayer { rotationZ = 90f },
        ) { content() }
    }
}

/**
 * 手柄面板：全屏双摇杆 + 常用按键。
 *
 * 出口状态接线（事件订阅 + 500ms 轮询兜底）收在面板自己的 [DisposableEffect]：
 * 出口变化（蓝牙配对成功 / USB 挂上 / 无线目标连上）多数不发输入，轮询兜底照旧；
 * 面板销毁（退出 Activity / 收起悬浮面板）时退订并 [GamepadController.releaseAll]，
 * 避免收起后按键卡在按下态。
 */
@Composable
internal fun GamepadPanel(onExit: () -> Unit) {
    var statusLine by remember { mutableStateOf("") }
    var adviceLine by remember { mutableStateOf("") }
    var statusOk by remember { mutableStateOf(false) }

    // issue#168 第 5 条（自定义按钮）：长按任意实体键进入配置 —— padConfiguring 记
    // 当前配置的槽位，保存后 bump padConfigVersion 让所有按钮重读配置。配置卡片直接
    // 画在面板里，不开 Compose Dialog：悬浮球 overlay 宿主是 Service 挂的窗口，
    // Dialog 要另开窗口会 BadToken。
    var padConfiguring by remember { mutableStateOf<String?>(null) }
    var padConfigVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val ui = Handler(Looper.getMainLooper())
        val refresh = {
            val path = if (Uplink.observed) Uplink.current else GamepadController.predictedPath()
            val info = GamepadExit.describe(path)
            statusLine = info.headline
            adviceLine = info.advice
            // 无出口 / 只有降级映射时用告警色，明确「现在按下去没用」或「要看对端」
            statusOk = info.driverlessRealPad
        }
        refresh()
        val sub = EventBus.onMain<UplinkEvent> { refresh() }
        val task = object : Runnable {
            override fun run() {
                refresh()
                ui.postDelayed(this, 500L)
            }
        }
        ui.post(task)
        onDispose {
            ui.removeCallbacks(task)
            sub.dispose()
            GamepadController.releaseAll()
        }
    }

    // b64（issue#168 第 5 条）：面板从 edge-to-edge 起是贴屏顶画的，L1 / R1 顶到状态栏
    // 区域里点不到。整个根盒垫上状态栏 insets —— 悬浮球 overlay 宿主 insets 为 0，不受影响。
    // BoxWithConstraints 只为取本盒实宽：本面板可能套在 LandscapePanel 的旋转盒里，
    // LocalWindowInfo 的宽度是竖屏原窗宽，判定不了「横过来之后有多宽」——
    // §6.3 Expanded 判定（ABXY 键区是否移到中轴）必须用旋转后内容的实际宽度。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(colorResource(R.color.overlay_scrim))
    ) {
        // §6.3（ROADMAP 2026-11-11）：Expanded（内容实宽 ≥840dp，平板横屏）双摇杆分置
        // 两侧、按键区（ABXY）移到中间；手机横屏（Compact/Medium）维持原贴角布局不动。
        val expandedPad = maxWidth >= 840.dp
        // 出口状态 + 指引（顶部居中；左右各让 170dp —— issue#168 第 8 条追补：原来
        // 100dp 时文字的左右端仍落在 L1 / R1 的横向范围内，换行一长就被肩键盖住
        // 一截（文字画在肩键下层）。170dp 起文字整段落在两肩键之间，避让是几何上
        // 的不重叠，不再靠绘制顺序兜底）
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 170.dp, end = 170.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = statusLine,
                fontSize = 12.sp,
                textAlign = TextAlign.Center, // issue#168 第 8 条：提示文字居中 + 自动换行
                color = if (statusOk) colorResource(R.color.overlay_pad_ok) else colorResource(R.color.overlay_pad_warn),
                modifier = Modifier.fillMaxWidth(),
            )
            if (adviceLine.isNotEmpty()) {
                Text(
                    text = adviceLine,
                    fontSize = 10.5.sp,
                    textAlign = TextAlign.Center, // 同上
                    color = colorResource(R.color.overlay_pad_text_dim),
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                )
            }
        }

        // 退出
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 16.dp, y = 10.dp)) {
            Pad(label = "✕", bit = 0, size = 44.dp, onDown = onExit)
        }

        // 肩键 L1 / R1（顶部两角；L1 右移避开左上角的 ✕）
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 72.dp, y = 14.dp)) {
            Pad(
                label = "L1", bit = GamepadController.BTN_L1, size = 78.dp, slot = "L1",
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "L1" },
            )
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-24).dp, y = 14.dp)) {
            Pad(
                label = "R1", bit = GamepadController.BTN_R1, size = 78.dp, slot = "R1",
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "R1" },
            )
        }

        // Select / Start（底部中央）
        Box(modifier = Modifier.align(Alignment.BottomCenter).offset(x = (-64).dp, y = (-18).dp)) {
            Pad(
                label = "Sel", bit = GamepadController.BTN_SELECT, size = 64.dp, slot = "Sel",
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "Sel" },
            )
        }
        Box(modifier = Modifier.align(Alignment.BottomCenter).offset(x = 64.dp, y = (-18).dp)) {
            Pad(
                label = "Start", bit = GamepadController.BTN_START, size = 64.dp, slot = "Start",
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "Start" },
            )
        }

        // 左摇杆（左下）/ 右摇杆（右下）—— 自定义 View，走 AndroidView 互操作
        AndroidView(
            factory = { ctx ->
                JoystickView(ctx).also { v ->
                    v.onAxis = { x, y -> GamepadController.setLeftAxis(x, y) }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 28.dp, bottom = 24.dp)
                .size(220.dp),
        )
        AndroidView(
            factory = { ctx ->
                JoystickView(ctx).also { v ->
                    v.onAxis = { rx, ry -> GamepadController.setRightAxis(rx, ry) }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 22.dp)
                .size(150.dp),
        )

        // A/B/X/Y：右摇杆左侧菱形排布（任天堂式），坐标沿用原 View 版实测值。
        // §6.3 Expanded：整簇平移到屏幕中轴（「双摇杆分置两侧、中间放按键区」）——
        // 原布局簇心距右缘 221dp（四键横向占 -320..-122dp），平移量 = 221dp − 实宽/2
        // 让簇心落在中线上；同时整簇抬高 40dp，给仍在底部居中的 Sel/Start 让位。
        // 非 Expanded 时 dx/dy 为 0，四个偏移与原实测值完全一致，手机横屏不变。
        val abxyShift = if (expandedPad) 221.dp - maxWidth / 2 else 0.dp
        val abxyLift = if (expandedPad) (-40).dp else 0.dp
        Box(modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-180).dp + abxyShift, y = (-102).dp + abxyLift)) {
            Pad(label = "A", bit = GamepadController.BTN_A, slot = "A",
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "A" })
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-250).dp + abxyShift, y = (-60).dp + abxyLift)) {
            Pad(label = "B", bit = GamepadController.BTN_B, slot = "B",
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "B" })
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-250).dp + abxyShift, y = (-144).dp + abxyLift)) {
            Pad(label = "X", bit = GamepadController.BTN_X, slot = "X",
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "X" })
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-320).dp + abxyShift, y = (-102).dp + abxyLift)) {
            Pad(label = "Y", bit = GamepadController.BTN_Y, slot = "Y",
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "Y" })
        }

        // issue#168 第 5 条（自定义按钮）：内嵌配置卡 —— 键位、尺寸各一行，点按循环
        // 切换即保存；「完成」收起。padConfigVersion 变化会让所有按钮重读配置。
        padConfiguring?.let { slot ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .background(colorResource(R.color.overlay_scrim))
                    .padding(16.dp),
            ) {
                val cfgCtx = LocalContext.current
                val defaults = GamepadPadConfig.SLOT_DEFAULTS[slot]
                    ?: (GamepadController.BTN_A to 58)
                val curBit = GamepadPadConfig.bit(cfgCtx, slot, defaults.first)
                val curSize = GamepadPadConfig.sizeDp(cfgCtx, slot, defaults.second)
                Text(text = "自定义按钮：$slot", fontSize = 15.sp, color = colorResource(R.color.overlay_pad_text))
                Text(
                    text = "键位：${GamepadPadConfig.labelOf(curBit)}（点按切换）",
                    fontSize = 14.sp,
                    color = colorResource(R.color.overlay_pad_text),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable {
                            GamepadPadConfig.set(
                                cfgCtx, slot, GamepadPadConfig.nextBit(curBit), curSize,
                            )
                            padConfigVersion++
                            Feedback.haptic(cfgCtx)
                        },
                )
                Text(
                    text = "尺寸：${curSize}dp（点按切换）",
                    fontSize = 14.sp,
                    color = colorResource(R.color.overlay_pad_text),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable {
                            GamepadPadConfig.set(
                                cfgCtx, slot, curBit, GamepadPadConfig.nextSize(curSize),
                            )
                            padConfigVersion++
                            Feedback.haptic(cfgCtx)
                        },
                )
                Text(
                    text = "完成",
                    fontSize = 14.sp,
                    color = colorResource(R.color.overlay_pad_ok),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable { padConfiguring = null },
                )
            }
        }
    }
}

/**
 * 一个圆形按钮：按下 `setButton(true)`、抬起/取消 `setButton(false)`，
 * 按下时换底色 + 加粗描边作反馈（原 View 版靠 `GradientDrawable` 做同样的事）。
 *
 * [onDown] 非空时只执行它（✕ 退出键不需要发 HID 帧），此时 [bit] 传 0。
 *
 * 自定义（issue#168 第 5 条）：[slot] 非空的实体键长按 [onConfigure] 进入配置，
 * 键位与尺寸从 [GamepadPadConfig] 按 [configVersion] 重读 —— 落盘后 bump 版本号
 * 即全部生效。
 */
@Composable
private fun Pad(
    label: String,
    bit: Int,
    size: Dp = 58.dp,
    slot: String? = null,
    configVersion: Int = 0,
    onDown: (() -> Unit)? = null,
    onConfigure: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    // 键位 / 尺寸按配置版本重读：保存配置后父级状态变化触发重组，这里就拿到新值
    val effBit = if (slot == null) bit else GamepadPadConfig.bit(ctx, slot, bit)
    val effSize = if (slot == null) size else GamepadPadConfig.sizeDp(ctx, slot, size.value.toInt()).dp
    val effLabel = if (slot == null) label else GamepadPadConfig.labelOf(effBit)
    // issue#168 第 5 条（多指）：用「按住的手指数」而不是单布尔 —— 每根落进按钮的
    // 手指在各自协程里独立认领/抬起，互不阻塞（detectTapGestures 一次只跟一个手势
    // 序列，多指同按会被吞）。同一时刻任一手指按着就算按下。
    var pressCount by remember { mutableIntStateOf(0) }
    val pressed = pressCount > 0
    Box(
        modifier = Modifier
            .size(effSize)
            .background(
                color = if (pressed) colorResource(R.color.overlay_pad_key_pressed) else colorResource(R.color.overlay_pad_key_idle),
                shape = CircleShape,
            )
            .border(
                width = if (pressed) 2.dp else 1.dp,
                color = if (pressed) colorResource(R.color.overlay_pad_stroke_pressed) else colorResource(R.color.overlay_pad_stroke_idle),
                shape = CircleShape,
            )
            .pointerInput(effBit, slot, onDown, onConfigure) {
                // 逐指追踪：awaitFirstDown 认领落进按钮的那根手指，随后只追这一根
                // pointerId 直到它抬起；第二根手指（同一颗或别的按钮）不干扰本协程。
                // 长按（[ViewConfiguration.longPressTimeoutMillis]）且可配置的实体键
                // 触发一次 [onConfigure]，之后继续等抬起。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressCount++
                    if (onDown != null) onDown() else GamepadController.setButton(effBit, true)
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    var configured = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val mine = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!mine.pressed) break
                            mine.consume()
                            if (!configured && onConfigure != null && slot != null &&
                                mine.uptimeMillis - down.uptimeMillis >= longPressMs
                            ) {
                                configured = true
                                onConfigure()
                            }
                        }
                    } finally {
                        // 协程被取消（弹窗接管触摸 / 视图销毁）也要复位，防按键卡住
                        pressCount--
                        if (onDown == null) GamepadController.setButton(effBit, false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = effLabel,
            fontSize = if (effLabel.length > 2) 13.sp else 17.sp,
            color = colorResource(R.color.overlay_pad_text),
        )
    }
}
