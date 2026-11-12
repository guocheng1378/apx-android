package com.allperiph.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.res.stringResource
import com.allperiph.R
import kotlin.math.abs

/**
 * 全屏游戏手柄（v1.x 起，v223.25 起只剩壳）：双虚拟摇杆 + 常用按键，经 [GamepadController]
 * 送上 USB HID（Report ID 22）／ 蓝牙 HID（rid=4）／ 无线 0x07。
 *
 * 2026-11-12 手柄页重画（用户「手柄ui 重画大小，ps5布局，长按修改不行，有的按键
 * 是要长按出效果的，找成熟的代码复现手柄页」）：
 *
 *  · **布局照 DualSense 重排**：L2/R2 压在 L1/R1 上方（真机就是扳机在肩键后面）、
 *    十字键在左侧（此前根本没有十字键）、面键 ✕○□△ 在右侧、双摇杆在底部中轴两侧
 *    （PlayStation 系没有「左摇杆在角上」的布局）、Create/Options 在顶部中轴两侧。
 *    尺寸改按屏高比例取（摇杆 ≈ 屏高 38%、按键 58dp 基准），小屏不再挤、大屏不再空。
 *  · **普通模式 / 编辑模式分离**（借 Moonlight 虚拟手柄的模式设计，github.com/
 *    moonlight-stream/moonlight-android 的 virtual_controller 包）：长按从此只属于
 *    游戏——按多久键就按多久（「有的按键是要长按出效果的」），不再触发配置卡；
 *    改键 / 挪位置走右上角「✎ 编辑」开关，编辑态里点按出配置卡、按住拖动移位、
 *    摇杆也一样能拖，全部即时落盘。此前「长按修改」和游戏长按抢同一根手指，
 *    两头都不讨好（用户「长按修改不行」的根因）。
 *  · **动效与震动**：按下缩放 + 底色过渡（90ms），每次按下即震（走全局手感档位，
 *    「关」档静默）；配置卡带进出场动画。
 */
class GamepadActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()
        // v223.29.86 修复（用户原话「手柄页没有屏蔽状态栏」）：手柄页是强制横屏的独立
        // Activity 宿主，此前只 applyEdgeToEdge、从未读设置里的 landscape_hide_status
        // —— 同一份 apx 偏好，主壳横屏隐藏状态栏、这里却一直显示。补上同 Shell 的隐藏
        // 逻辑（默认开）：按 TRANSIENT_BY_SWIPE 短暂呼出半透明层，退出时随窗口销毁
        // 自然恢复，无需手动 show。悬浮手柄宿主是 Service overlay，层高状态栏，不受影响。
        // v223.29.87 追补：隐藏只在 onCreate 压一次不够 —— 厂商系统在通知/系统面板
        // 交互后会自行把状态栏抬回来，此后没人再压（2026-11-13 用户截图时钟叠在 L2 上）。
        // 抽成 applyLandscapeBarPref，onResume 再压一次，回前台即恢复隐藏。
        applyLandscapeBarPref()
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

    /** 回前台重压一次横屏状态栏（设置开关读实时偏好，隐藏/显示都跟着开关走） */
    override fun onResume() {
        super.onResume()
        applyLandscapeBarPref()
    }

    /** v223.29.86 从 onCreate 内联抽出的横屏状态栏口径，onResume 复用（见上注释） */
    private fun applyLandscapeBarPref() {
        val prefs = getSharedPreferences("apx", MODE_PRIVATE)
        val ctrl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (prefs.getBoolean("landscape_hide_status", true)) {
            ctrl.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } else {
            ctrl.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
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
internal fun LandscapePanel(
    landscape: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val cfg = LocalConfiguration.current
    // 横竖判定（2026-11-12「很多界面都有这样的问题」系统性排查）：传 null（GamepadActivity
    // 这类自带 Configuration 的宿主）才信 cfg；悬浮面板宿主是 Service 挂的 overlay 窗口，
    // 服务的 Configuration 滞后/冻结（同根因见 rememberApAdaptiveLayout 的注释），由
    // 调用方按 realScreenSize 实测传入 —— 这里只做判定，不猜配置。
    val isLandscape = landscape ?: (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE)
    if (isLandscape) {
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

/** 摇杆/按键共用的偏移槽位全集：按键槽位 + 双摇杆（摇杆只挪位置，不改键） */
private val OFFSET_SLOTS = GamepadPadConfig.SLOT_DEFAULTS.keys + GamepadPadConfig.STICK_SLOTS

/**
 * 手柄面板：DualSense 式布局 + 编辑模式。
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

    // 编辑模式（Moonlight 式三态里取两态：普通 / 编辑；改尺寸走配置卡不做整面缩放）：
    // 普通模式所有键 = 纯游戏输入，长按按住不放即长按键；编辑模式 = 拖动移位 + 点按配置。
    var padEditing by remember { mutableStateOf(false) }

    // 配置卡目标槽位：编辑态点某颗按钮弹出；保存后 bump padConfigVersion 让所有按钮重读。
    // 卡片画在面板里，不开 Compose Dialog：悬浮球 overlay 宿主是 Service 挂的窗口，
    // Dialog 要另开窗口会 BadToken。
    var padConfiguring by remember { mutableStateOf<String?>(null) }
    var padConfigVersion by remember { mutableIntStateOf(0) }

    // 每槽位附加偏移（dp）：编辑态拖动实时改、松手落盘（[GamepadPadConfig.setOffset]），
    // 下次进面板仍生效。按键与摇杆共用这一套。
    val padCtx = LocalContext.current
    var padOffsets by remember {
        mutableStateOf(OFFSET_SLOTS.associateWith { GamepadPadConfig.offset(padCtx, it) })
    }
    val padMoveBy: (String, Float, Float) -> Unit = { slot, dx, dy ->
        val cur = padOffsets[slot] ?: (0f to 0f)
        padOffsets = padOffsets + (slot to ((cur.first + dx) to (cur.second + dy)))
    }
    val padMoveEnd: (String) -> Unit = { slot ->
        val off = padOffsets[slot] ?: (0f to 0f)
        GamepadPadConfig.setOffset(padCtx, slot, off.first, off.second)
    }
    fun padDrag(slot: String): Pair<Dp, Dp> {
        val off = padOffsets[slot] ?: (0f to 0f)
        return off.first.dp to off.second.dp
    }

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

    // b64（issue#168 第 5 条）：曾给整个根盒垫状态栏 insets 避让，v84 又叠了 32dp 安全垫 ——
    // 方向反了：成宇 2026-11-12 22:4x「我要的是所有覆盖到状态栏」—— 他要的不是「让开状态栏」
    // 而是「盖住状态栏」：面板顶格画、✕/✎ 落在原状态栏带里，状态栏不再占住面板顶上一条。
    // overlay 宿主 insets 恒 0 且悬浮窗口层高于状态栏，面板本来就能盖上去；Activity 宿主
    // 横屏默认开「横屏隐藏状态栏」，insets 归零同样顶格。所以顶部任何让位都不留，铺满整屏。
    //
    // BoxWithConstraints 只为取本盒实宽：本面板可能套在 LandscapePanel 的旋转盒里，
    // LocalWindowInfo 的宽度是竖屏原窗宽，判定不了「横过来之后有多宽」。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.overlay_scrim))
    ) {
        val W = maxWidth
        val H = maxHeight

        // 尺寸口径（重画）：摇杆 ≈ 屏高 38%（封顶 180dp，小屏等比缩），十字/面键簇
        // 键径 58dp（SIZE_CHOICES 中档），肩键 64dp，扳机 68×32 药丸，中央小件 76×30。
        val stickSize = minOf(180.dp, H * 0.38f)

        // 出口状态 + 指引（顶部居中；左右各让 170dp —— issue#168 第 8 条追补：
        // 文字整段落在两肩键之间，避让是几何上的不重叠，不靠绘制顺序兜底）
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 2026-11-13 用户截图「Create/Options 与说明文字互相压字」：两组芯片挂在
                // H*0.05（药丸高 30dp），提示从 12dp 起排多行折行正好叠进芯片带。整体
                // 下移到芯片带以下：芯片底 = H*0.05 + 30dp，这里留 4dp 间隙起排；
                // 左右 170dp 让位不变（避开两肩 L1/R1）。
                .padding(top = H * 0.05f + 34.dp, start = 170.dp, end = 170.dp),
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
            // issue#169 #15「Mac 不支持手柄需提示注明」：被控机系统选了 macOS 时常驻提示
            // （macOS 拿不到虚拟 HID 手柄的 Apple 受限授权，无线出口只能降级成鼠标/键盘；
            // 蓝牙/USB 是系统级 HID 手柄，部分应用不认）。读设置页「被控机系统」下拉，
            // 默认 Windows 不弹，存量用户零打扰。
            if (com.allperiph.hid.PeerOs.current(padCtx) == com.allperiph.hid.PeerOs.MACOS) {
                Text(
                    text = stringResource(R.string.ui_gamepad_hint_macos),
                    fontSize = 10.5.sp,
                    textAlign = TextAlign.Center,
                    color = colorResource(R.color.overlay_pad_warn),
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                )
            }
        }

        // 退出（普通/编辑两态都可用，不进编辑体系）
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 12.dp, y = 8.dp)) {
            Pad(label = "✕", bit = 0, width = 40.dp, height = 40.dp, onDown = onExit)
        }
        // 编辑开关（Moonlight 式模式切换）：✎ 进编辑、✓ 收工。退出编辑顺带收起配置卡。
        // 编辑态永远保持普通手势路径（editing 不随开关变）：它必须一直点得动。
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 60.dp, y = 8.dp)) {
            Pad(
                label = if (padEditing) "✓" else "✎", bit = 0, width = 40.dp, height = 40.dp,
                onDown = {
                    padEditing = !padEditing
                    if (!padEditing) padConfiguring = null
                    Feedback.haptic(padCtx)
                },
            )
        }

        // —— 扳机 L2 / R2（顶部第一排，真机在肩键后面，屏上画成药丸） ——
        val l2Drag = padDrag("L2")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 104.dp + l2Drag.first, y = H * 0.02f + l2Drag.second)) {
            Pad(label = "L2", bit = GamepadController.BTN_L2, width = 68.dp, height = 32.dp, pill = true, slot = "L2", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "L2" },
                onMoveBy = { dx, dy -> padMoveBy("L2", dx, dy) }, onMoveEnd = { padMoveEnd("L2") })
        }
        val r2Drag = padDrag("R2")
        Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-104).dp + r2Drag.first, y = H * 0.02f + r2Drag.second)) {
            Pad(label = "R2", bit = GamepadController.BTN_R2, width = 68.dp, height = 32.dp, pill = true, slot = "R2", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "R2" },
                onMoveBy = { dx, dy -> padMoveBy("R2", dx, dy) }, onMoveEnd = { padMoveEnd("R2") })
        }

        // —— 肩键 L1 / R1（第二排） ——
        val l1Drag = padDrag("L1")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 110.dp + l1Drag.first, y = H * 0.125f + l1Drag.second)) {
            Pad(label = "L1", bit = GamepadController.BTN_L1, width = 64.dp, slot = "L1", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "L1" },
                onMoveBy = { dx, dy -> padMoveBy("L1", dx, dy) }, onMoveEnd = { padMoveEnd("L1") })
        }
        val r1Drag = padDrag("R1")
        Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-110).dp + r1Drag.first, y = H * 0.125f + r1Drag.second)) {
            Pad(label = "R1", bit = GamepadController.BTN_R1, width = 64.dp, slot = "R1", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "R1" },
                onMoveBy = { dx, dy -> padMoveBy("R1", dx, dy) }, onMoveEnd = { padMoveEnd("R1") })
        }

        // —— 十字键（左侧中部；此前缺失，占报告位 12..15，见 GamepadController 注释） ——
        // 簇内四键中心间距 = 键径（58dp），拼成正十字：Up 在上、Down 在下、Left/Right 在侧。
        val dpadCx = W * 0.13f
        val dpadCy = H * 0.37f
        val dUpDrag = padDrag("DUp")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 29.dp + dUpDrag.first, y = dpadCy - 87.dp + dUpDrag.second)) {
            Pad(label = "▲", bit = GamepadController.BTN_DUP, slot = "DUp", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "DUp" },
                onMoveBy = { dx, dy -> padMoveBy("DUp", dx, dy) }, onMoveEnd = { padMoveEnd("DUp") })
        }
        val dDownDrag = padDrag("DDown")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 29.dp + dDownDrag.first, y = dpadCy + 29.dp + dDownDrag.second)) {
            Pad(label = "▼", bit = GamepadController.BTN_DDOWN, slot = "DDown", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "DDown" },
                onMoveBy = { dx, dy -> padMoveBy("DDown", dx, dy) }, onMoveEnd = { padMoveEnd("DDown") })
        }
        val dLeftDrag = padDrag("DLeft")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 87.dp + dLeftDrag.first, y = dpadCy - 29.dp + dLeftDrag.second)) {
            Pad(label = "◀", bit = GamepadController.BTN_DLEFT, slot = "DLeft", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "DLeft" },
                onMoveBy = { dx, dy -> padMoveBy("DLeft", dx, dy) }, onMoveEnd = { padMoveEnd("DLeft") })
        }
        val dRightDrag = padDrag("DRight")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx + 29.dp + dRightDrag.first, y = dpadCy - 29.dp + dRightDrag.second)) {
            Pad(label = "▶", bit = GamepadController.BTN_DRIGHT, slot = "DRight", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "DRight" },
                onMoveBy = { dx, dy -> padMoveBy("DRight", dx, dy) }, onMoveEnd = { padMoveEnd("DRight") })
        }

        // —— 面键 ✕○□△（右侧中部，PS5 菱形位：△ 上 / ○ 右 / ✕ 下 / □ 左） ——
        val faceCx = W * 0.87f
        val faceCy = H * 0.37f
        val yDrag = padDrag("Y")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 29.dp + yDrag.first, y = faceCy - 87.dp + yDrag.second)) {
            Pad(label = "△", bit = GamepadController.BTN_Y, slot = "Y", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "Y" },
                onMoveBy = { dx, dy -> padMoveBy("Y", dx, dy) }, onMoveEnd = { padMoveEnd("Y") })
        }
        val bDrag = padDrag("B")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx + 29.dp + bDrag.first, y = faceCy - 29.dp + bDrag.second)) {
            Pad(label = "○", bit = GamepadController.BTN_B, slot = "B", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "B" },
                onMoveBy = { dx, dy -> padMoveBy("B", dx, dy) }, onMoveEnd = { padMoveEnd("B") })
        }
        val aDrag = padDrag("A")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 29.dp + aDrag.first, y = faceCy + 29.dp + aDrag.second)) {
            Pad(label = "✕", bit = GamepadController.BTN_A, slot = "A", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "A" },
                onMoveBy = { dx, dy -> padMoveBy("A", dx, dy) }, onMoveEnd = { padMoveEnd("A") })
        }
        val xDrag = padDrag("X")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 87.dp + xDrag.first, y = faceCy - 29.dp + xDrag.second)) {
            Pad(label = "□", bit = GamepadController.BTN_X, slot = "X", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "X" },
                onMoveBy = { dx, dy -> padMoveBy("X", dx, dy) }, onMoveEnd = { padMoveEnd("X") })
        }

        // —— Create / Options（顶部中轴两侧，PS5 的 Share/Options 位） ——
        val selDrag = padDrag("Sel")
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(x = W * 0.40f - W / 2 + selDrag.first, y = H * 0.05f + selDrag.second)) {
            Pad(label = "Create", bit = GamepadController.BTN_SELECT, width = 76.dp, height = 30.dp, pill = true, slot = "Sel", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "Sel" },
                onMoveBy = { dx, dy -> padMoveBy("Sel", dx, dy) }, onMoveEnd = { padMoveEnd("Sel") })
        }
        val startDrag = padDrag("Start")
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(x = W * 0.60f - W / 2 + startDrag.first, y = H * 0.05f + startDrag.second)) {
            Pad(label = "Options", bit = GamepadController.BTN_START, width = 76.dp, height = 30.dp, pill = true, slot = "Start", editing = padEditing,
                configVersion = padConfigVersion, onConfigure = { padConfiguring = "Start" },
                onMoveBy = { dx, dy -> padMoveBy("Start", dx, dy) }, onMoveEnd = { padMoveEnd("Start") })
        }

        // —— 双摇杆（底部中轴两侧；PlayStation 系摇杆不贴角） ——
        // 编辑态：上层盖一块透明拖拽面接管触摸（摇杆本体收不到事件），拖 = 移位落盘；
        // 普通态不盖章，摇杆照常吃输入。JoystickView 自身不动。
        Stick(
            slot = "LStick",
            modifier = Modifier.align(Alignment.TopStart)
                .offset(x = W * 0.27f - stickSize / 2 + padDrag("LStick").first, y = H * 0.78f - stickSize / 2 + padDrag("LStick").second),
            size = stickSize, editing = padEditing,
            onMoveBy = { dx, dy -> padMoveBy("LStick", dx, dy) }, onMoveEnd = { padMoveEnd("LStick") },
            onAxis = { x, y -> GamepadController.setLeftAxis(x, y) },
        )
        Stick(
            slot = "RStick",
            modifier = Modifier.align(Alignment.TopStart)
                .offset(x = W * 0.73f - stickSize / 2 + padDrag("RStick").first, y = H * 0.78f - stickSize / 2 + padDrag("RStick").second),
            size = stickSize, editing = padEditing,
            onMoveBy = { dx, dy -> padMoveBy("RStick", dx, dy) }, onMoveEnd = { padMoveEnd("RStick") },
            onAxis = { rx, ry -> GamepadController.setRightAxis(rx, ry) },
        )

        // —— 配置卡（编辑态点按钮弹出）：键位、尺寸各一行，行高 44dp 起好点按 ——
        AnimatedVisibility(
            visible = padConfiguring != null,
            modifier = Modifier.align(Alignment.Center),
        ) {
            val slot = padConfiguring ?: return@AnimatedVisibility
            Column(
                modifier = Modifier
                    .background(colorResource(R.color.overlay_scrim), RoundedCornerShape(14.dp))
                    .border(1.dp, colorResource(R.color.overlay_pad_stroke_idle), RoundedCornerShape(14.dp))
                    .padding(18.dp),
            ) {
                val cfgCtx = LocalContext.current
                val defaults = GamepadPadConfig.SLOT_DEFAULTS[slot]
                    ?: (GamepadController.BTN_A to 58)
                val curBit = GamepadPadConfig.bit(cfgCtx, slot, defaults.first)
                val curSize = GamepadPadConfig.sizeDp(cfgCtx, slot, defaults.second)
                Text(text = "自定义：$slot", fontSize = 16.sp, color = colorResource(R.color.overlay_pad_text))
                Text(
                    text = "键位：${GamepadPadConfig.labelOf(curBit)}（点按切换）",
                    fontSize = 15.sp,
                    color = colorResource(R.color.overlay_pad_text),
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .height(40.dp)
                        .clickable {
                            GamepadPadConfig.set(cfgCtx, slot, GamepadPadConfig.nextBit(curBit), curSize)
                            padConfigVersion++
                            Feedback.haptic(cfgCtx)
                        },
                )
                Text(
                    text = "尺寸：${curSize}dp（点按切换）",
                    fontSize = 15.sp,
                    color = colorResource(R.color.overlay_pad_text),
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .height(40.dp)
                        .clickable {
                            GamepadPadConfig.set(cfgCtx, slot, curBit, GamepadPadConfig.nextSize(curSize))
                            padConfigVersion++
                            Feedback.haptic(cfgCtx)
                        },
                )
                Text(
                    text = "完成",
                    fontSize = 15.sp,
                    color = colorResource(R.color.overlay_pad_ok),
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .height(40.dp)
                        .clickable { padConfiguring = null },
                )
            }
        }
    }
}

/**
 * 虚拟摇杆容器：本体是 [JoystickView]（AndroidView，多指认领与回中都在里面），
 * 外面包一层负责「编辑态拖拽移位」。编辑态盖一块透明拖拽面接管触摸——上层先于
 * 下层收到事件且全部消费，摇杆本体收不到；普通态不盖，摇杆照常吃输入。
 * 编辑态另加 8% 白罩提示「这东西在编辑」。
 */
@Composable
private fun Stick(
    slot: String,
    modifier: Modifier,
    size: Dp,
    editing: Boolean,
    onMoveBy: (Float, Float) -> Unit,
    onMoveEnd: () -> Unit,
    onAxis: (Int, Int) -> Unit,
) {
    val ctx = LocalContext.current
    Box(modifier = modifier.size(size)) {
        AndroidView(
            factory = { c ->
                JoystickView(c).also { v -> v.onAxis = onAxis }
            },
            // 编辑态禁用摇杆本体：JoystickView 在 isEnabled=false 时交出触摸，
            // 由上层拖拽面接管，避免「挪摇杆」和「扳摇杆」同时发生
            update = { v -> v.isEnabled = !editing },
            modifier = Modifier.size(size).alpha(if (editing) 0.5f else 1f),
        )
        if (editing) {
            Box(modifier = Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f), CircleShape))
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(slot) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            Feedback.haptic(ctx)
                            var moved = false
                            var lastX = down.position.x
                            var lastY = down.position.y
                            val slopPx = viewConfiguration.touchSlop
                            while (true) {
                                val ev = awaitPointerEvent()
                                val mine = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!mine.pressed) break
                                mine.consume()
                                val x = mine.position.x
                                val y = mine.position.y
                                if (!moved && (abs(x - down.position.x) > slopPx || abs(y - down.position.y) > slopPx)) {
                                    moved = true
                                }
                                if (moved) {
                                    onMoveBy((x - lastX) / density, (y - lastY) / density)
                                }
                                lastX = x
                                lastY = y
                            }
                            if (moved) onMoveEnd()
                        }
                    },
            )
        }
    }
}

/**
 * 一颗按钮（PS5 版）：
 *
 * · **普通模式**：按下 `setButton(true)`、抬起/取消 `setButton(false)`，按多久键就
 *   按多久——游戏要的长按效果（压扳机、蓄力跳）直接就是「一直按着」。此前长按会
 *   半路弹配置卡，游戏长按被掐断、配置也常弹错，两头都不是（用户「长按修改不行」）。
 *   按下即缩放到底色过渡 + 一次震动反馈（手感档位「关」时静默）。
 * · **编辑模式**（[editing] = true，由面板「✎」开关统一控制，借 Moonlight 虚拟手柄
 *   的模式设计）：不发 HID——点按（未过 slop 就抬手）弹配置卡，按住拖动实时移位、
 *   松手落盘；边框换醒目色提示在编辑。
 *
 * [onDown] 非空时只执行它（✕ / ✎ 这类功能键不需要发 HID 帧），此时 [bit] 传 0。
 *
 * 手势协程要点（沿用 issue#168 第 5 条的教训）：回调经 [rememberUpdatedState] 桥进
 * 协程，pointerInput 的 key 只放 [effBit] / [slot] / [editing] 三个语义值——出口状态
 * 每 500ms 刷一次重组，若把每轮新建的 lambda 放进 key，手势协程会被反复取消重启，
 * 按住经常活不到阈值。
 *
 * [pill] = true 画药丸形（扳机 / Create / Options），否则圆形；配置里的尺寸档
 * 只作用于宽度（药丸高度固定，圆键宽高一致）。
 */
@Composable
private fun Pad(
    label: String,
    bit: Int,
    width: Dp = 58.dp,
    height: Dp = 58.dp,
    pill: Boolean = false,
    slot: String? = null,
    configVersion: Int = 0,
    editing: Boolean = false,
    onDown: (() -> Unit)? = null,
    onConfigure: (() -> Unit)? = null,
    onMoveBy: ((Float, Float) -> Unit)? = null,
    onMoveEnd: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    // 键位 / 尺寸按配置版本重读：保存配置后父级状态变化触发重组，这里就拿到新值
    val effBit = if (slot == null) bit else GamepadPadConfig.bit(ctx, slot, bit)
    val effW = if (slot == null) width else GamepadPadConfig.sizeDp(ctx, slot, width.value.toInt()).dp
    val effH = if (pill) height else effW
    val effLabel = if (slot == null) label else GamepadPadConfig.labelOf(effBit)
    // 回调经 rememberUpdatedState 桥进手势协程：重组换 lambda 不再重启手势
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnConfigure by rememberUpdatedState(onConfigure)
    val currentOnMoveBy by rememberUpdatedState(onMoveBy)
    val currentOnMoveEnd by rememberUpdatedState(onMoveEnd)
    // 多指：用「按住的手指数」而不是单布尔 —— 每根落进按钮的手指在各自协程里
    // 独立认领/抬起，互不阻塞。同一时刻任一手指按着就算按下。
    var pressCount by remember { mutableIntStateOf(0) }
    val pressed = pressCount > 0 && !editing
    // 动效：按下 90ms 缩到 0.92 + 底色过渡（手感档位只管震动，动效不做开关，成本极低）
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else if (editing) 1.02f else 1f,
        animationSpec = tween(90),
        label = "padScale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            pressed -> colorResource(R.color.overlay_pad_key_pressed)
            editing -> colorResource(R.color.overlay_pad_key_idle)
            else -> colorResource(R.color.overlay_pad_key_idle)
        },
        animationSpec = tween(90),
        label = "padBg",
    )
    val border by animateColorAsState(
        targetValue = when {
            editing -> colorResource(R.color.overlay_pad_ok)
            pressed -> colorResource(R.color.overlay_pad_stroke_pressed)
            else -> colorResource(R.color.overlay_pad_stroke_idle)
        },
        animationSpec = tween(90),
        label = "padBorder",
    )
    val shape = if (pill) RoundedCornerShape(percent = 50) else CircleShape
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(effW, effH)
            .background(bg, shape)
            .border(
                width = if (pressed || editing) 2.dp else 1.dp,
                color = border,
                shape = shape,
            )
            .pointerInput(effBit, slot, editing) {
                // 逐指追踪：awaitFirstDown 认领落进按钮的那根手指，随后只追这一根
                // pointerId 直到它抬起；第二根手指（同一颗或别的按钮）不干扰本协程。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val onDownNow = currentOnDown
                    if (editing) {
                        // —— 编辑态：不发 HID。拖 = 移位，tap = 配置。 ——
                        Feedback.haptic(ctx)
                        var moved = false
                        var lastX = down.position.x
                        var lastY = down.position.y
                        val slopPx = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val mine = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!mine.pressed) break
                            mine.consume()
                            val x = mine.position.x
                            val y = mine.position.y
                            if (!moved && (abs(x - down.position.x) > slopPx || abs(y - down.position.y) > slopPx)) {
                                moved = true
                            }
                            if (moved) {
                                // 按住拖动：按钮实时跟手（面板层改 offset），松手落盘
                                val onMoveNow = currentOnMoveBy
                                if (onMoveNow != null) onMoveNow((x - lastX) / density, (y - lastY) / density)
                            }
                            lastX = x
                            lastY = y
                        }
                        if (moved) {
                            currentOnMoveEnd?.invoke()
                        } else {
                            currentOnConfigure?.invoke()
                        }
                    } else if (onDownNow != null) {
                        // —— 功能键（✕ / ✎）：只执行回调，不发 HID ——
                        onDownNow()
                        while (true) {
                            val event = awaitPointerEvent()
                            val mine = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!mine.pressed) break
                            mine.consume()
                        }
                    } else {
                        // —— 普通模式：纯游戏输入。长按 = 一直按着这颗键，不做任何别的。 ——
                        pressCount++
                        GamepadController.setButton(effBit, true)
                        Feedback.haptic(ctx)   // 按下即震，走全局手感档位（「关」静默）
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val mine = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!mine.pressed) break
                                mine.consume()
                            }
                        } finally {
                            // 协程被取消（弹窗接管触摸 / 视图销毁）也要复位，防按键卡住
                            pressCount--
                            GamepadController.setButton(effBit, false)
                        }
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
