package com.allperiph.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF14161B))) {
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

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF14161B))) {
        // 出口状态 + 指引（顶部居中；左右各让 100dp 避开 L1 / R1，也避开左上角 ✕）
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 100.dp, end = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = statusLine,
                fontSize = 12.sp,
                color = if (statusOk) Color(0xFF8BD450) else Color(0xFFFFB44C),
                modifier = Modifier.fillMaxWidth(),
            )
            if (adviceLine.isNotEmpty()) {
                Text(
                    text = adviceLine,
                    fontSize = 10.5.sp,
                    color = Color(0xFFB9BDC7),
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
            Pad(label = "L1", bit = GamepadController.BTN_L1, size = 78.dp)
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-24).dp, y = 14.dp)) {
            Pad(label = "R1", bit = GamepadController.BTN_R1, size = 78.dp)
        }

        // Select / Start（底部中央）
        Box(modifier = Modifier.align(Alignment.BottomCenter).offset(x = (-64).dp, y = (-18).dp)) {
            Pad(label = "Sel", bit = GamepadController.BTN_SELECT, size = 64.dp)
        }
        Box(modifier = Modifier.align(Alignment.BottomCenter).offset(x = 64.dp, y = (-18).dp)) {
            Pad(label = "Start", bit = GamepadController.BTN_START, size = 64.dp)
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

        // A/B/X/Y：右摇杆左侧菱形排布（任天堂式），坐标沿用原 View 版实测值
        Box(modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-180).dp, y = (-102).dp)) {
            Pad(label = "A", bit = GamepadController.BTN_A)
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-250).dp, y = (-60).dp)) {
            Pad(label = "B", bit = GamepadController.BTN_B)
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-250).dp, y = (-144).dp)) {
            Pad(label = "X", bit = GamepadController.BTN_X)
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-320).dp, y = (-102).dp)) {
            Pad(label = "Y", bit = GamepadController.BTN_Y)
        }
    }
}

/**
 * 一个圆形按钮：按下 `setButton(true)`、抬起/取消 `setButton(false)`，
 * 按下时换底色 + 加粗描边作反馈（原 View 版靠 `GradientDrawable` 做同样的事）。
 *
 * [onDown] 非空时只执行它（✕ 退出键不需要发 HID 帧），此时 [bit] 传 0。
 */
@Composable
private fun Pad(
    label: String,
    bit: Int,
    size: Dp = 58.dp,
    onDown: (() -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .background(
                color = if (pressed) Color(0x66FFFFFF) else Color(0x2EFFFFFF),
                shape = CircleShape,
            )
            .border(
                width = if (pressed) 2.dp else 1.dp,
                color = if (pressed) Color(0xDDFFFFFF) else Color(0x66FFFFFF),
                shape = CircleShape,
            )
            .pointerInput(bit, onDown) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        if (onDown != null) onDown() else GamepadController.setButton(bit, true)
                        tryAwaitRelease()
                        pressed = false
                        if (onDown == null) GamepadController.setButton(bit, false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = if (label.length > 2) 13.sp else 17.sp,
            color = Color(0xFFEAEAEA),
        )
    }
}
