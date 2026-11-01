package com.allperiph.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * 全屏游戏手柄（v1.x）：双虚拟摇杆 + 常用按键，经 [GamepadController]
 * 送上 USB HID（Report ID 22）／ 蓝牙 HID（rid=4）／ 无线 0x07。
 *
 * **本轮迁 Compose**（`ThemePref` 下线的一步；外观总控已拆成 ApPalette / ApSkin）：
 *  - 根布局从 `FrameLayout + addView + FrameLayout.LayoutParams.gravity/margins` 换成
 *    `Box + Modifier.align/offset`；
 *  - 按键从「无按下态的 TextView + OnTouchListener + GradientDrawable」换成
 *    `Box + pointerInput(detectTapGestures(onPress = { … tryAwaitRelease() … }))`，
 *    按下态由 `mutableStateOf` 驱动，圆形底/描边用 `background` + `border` + `CircleShape`；
 *  - 出口状态行的 500ms 轮询与 `EventBus.onMain<UplinkEvent>` 订阅保持原样，只是把
 *    结果写进 `mutableStateOf` 而不是 `TextView.text`；
 *  - **摇杆仍是 [JoystickView]**（`AndroidView` 包裹）：它是自定义 `View`、带自己的触摸
 *    采样与阻尼逻辑，整块重写属于「拿可用性换一致性」，本轮不动；
 *  - 不再需要 `attachBaseContext(ThemePref.wrap)` 与 `ApPalette.applyRuntimeTheme`，
 *    取色走 `ApMiuixTheme`。
 */
class GamepadActivity : Activity() {

    /** 出口状态行（每 500ms 兜底刷新一次；出口变化不一定会发输入）。 */
    private val ui = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 500L)
        }
    }

    private var uplinkSub: EventBus.Disposable? = null

    /** 状态行/指引行文案与颜色，Compose 侧直接读这两个状态 */
    private var statusLine by mutableStateOf("")
    private var adviceLine by mutableStateOf("")
    private var statusOk by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    ApMiuixTheme {
                        GamepadScreen()
                    }
                }
            },
        )
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // 出口变化（蓝牙配对成功 / USB 挂上 / 无线目标连上）多数不发输入，
        // 所以除了事件订阅，再加一个低频轮询兜底。
        uplinkSub?.dispose()
        uplinkSub = EventBus.onMain<UplinkEvent> { refreshStatus() }
        ui.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refreshTask)
        uplinkSub?.dispose()
        uplinkSub = null
        GamepadController.releaseAll() // 离开时复位，避免按键卡在按下态
    }

    /** 状态行 + 指引行：按当前出口刷新（发过输入看真实出口，否则看预计出口）。 */
    private fun refreshStatus() {
        val path = if (Uplink.observed) Uplink.current else GamepadController.predictedPath()
        val info = GamepadExit.describe(path)
        statusLine = info.headline
        adviceLine = info.advice
        // 无出口 / 只有降级映射时用告警色，明确「现在按下去没用」或「要看对端」
        statusOk = info.driverlessRealPad
    }

    @Composable
    private fun GamepadScreen() {
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
                Pad(label = "✕", bit = 0, size = 44.dp, onDown = { finish() })
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

            // 左摇杆（左下）／右摇杆（右下）—— 自定义 View，走 AndroidView 互操作
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
}
