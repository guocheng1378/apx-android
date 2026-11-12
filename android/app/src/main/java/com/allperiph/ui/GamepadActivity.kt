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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.hypot

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

/** 摇杆/按键/功能键共用的偏移槽位全集：按键槽位（含 Exit/Edit 功能键）+ 双摇杆（只挪位置，不改键） */
private val OFFSET_SLOTS = GamepadPadConfig.SLOT_DEFAULTS.keys + GamepadPadConfig.STICK_SLOTS

/** 对齐吸附半径（issue#169 第 19 条）：拖动中与另一槽位中心差 ≤ 它就吸上并出轴线提示 */
private val PAD_SNAP_DP = 7.dp

/** 边缘无级调整的直径上下限（dp），issue#169 第 18 条 */
private const val PAD_RESIZE_MIN_DP = 36f
private const val PAD_RESIZE_MAX_DP = 120f

/**
 * 十字键/面键簇的横向锚点（issue#169 追评 2026-11-13「pad 上 app 手柄和悬浮球手柄都缺
 * ABXY」）：两簇按 0.13W/0.87W 布锚，簇中心到边键外缘最宽 87dp —— 面板宽 W < 669dp 时
 * （pad 高密度屏、分屏/自由窗口都可能出现），B 键右缘 0.87W+87dp 超出面板右缘，整簇
 * 被裁出屏外，看起来就是「缺 4 个键」。锚点先按百分比、再钳进「整簇可见」范围
 * （左右各留 16dp 边距，103dp = 87dp 簇半径 + 16dp）；极窄面板两簇会互相靠近，
 * 但至少键都在屏上、点得到也拖得回来。⚠️ 与 padBaseCenters 的公式同源（吸附基准
 * 必须和实际布局一致，改一处必须同步另一处）。
 */
internal fun dpadAnchorX(W: Dp): Dp = (W * 0.13f).coerceAtLeast(103.dp)

internal fun faceAnchorX(W: Dp): Dp = (W * 0.87f).coerceAtMost(W - 103.dp)

/**
 * 各槽位默认中心坐标（dp 浮点，面板坐标系）—— issue#169 第 19 条对齐吸附用。
 *
 * ⚠️ 与 GamepadPanel 布局代码里的 align/offset 公式一一对应（改布局必须同步这里）：
 * 上左为原点，W/H 为面板整幅。功能键 Exit/Edit 的默认位
 * 也收录（12/60dp 起、40dp 见方，见 ✕/✎ 两格的 offset 公式）。
 * 十字/面键锚点走 [dpadAnchorX]/[faceAnchorX]（窄面板钳制，见函数注释）。
 */
private fun padBaseCenters(W: Dp, H: Dp): Map<String, Pair<Float, Float>> = mapOf(
    "L2" to (138.dp.value to (H * 0.02f + 16.dp).value),
    "R2" to ((W - 138.dp).value to (H * 0.02f + 16.dp).value),
    "L1" to (142.dp.value to (H * 0.125f + 32.dp).value),
    "R1" to ((W - 142.dp).value to (H * 0.125f + 32.dp).value),
    "DUp" to (dpadAnchorX(W).value to (H * 0.37f - 58.dp).value),
    "DDown" to (dpadAnchorX(W).value to (H * 0.37f + 58.dp).value),
    "DLeft" to ((dpadAnchorX(W) - 58.dp).value to (H * 0.37f).value),
    "DRight" to ((dpadAnchorX(W) + 58.dp).value to (H * 0.37f).value),
    "Y" to (faceAnchorX(W).value to (H * 0.37f - 58.dp).value),
    "A" to (faceAnchorX(W).value to (H * 0.37f + 58.dp).value),
    "X" to ((faceAnchorX(W) - 58.dp).value to (H * 0.37f).value),
    "B" to ((faceAnchorX(W) + 58.dp).value to (H * 0.37f).value),
    "Sel" to ((W * 0.40f).value to (H * 0.05f + 15.dp).value),
    "Start" to ((W * 0.60f).value to (H * 0.05f + 15.dp).value),
    // 摇杆也要防出屏：摇杆直径封顶 180dp（与 GamepadPanel 的 stickSize 同式），
    // W*0.27 ± 半径在窄面板会越界，锚点钳进「半径 + 8dp 边距」内
    "LStick" to ((W * 0.27f).coerceAtLeast(minOf(180.dp, H * 0.38f) / 2 + 8.dp).value to (H * 0.78f).value),
    "RStick" to ((W * 0.73f).coerceAtMost(W - minOf(180.dp, H * 0.38f) / 2 - 8.dp).value to (H * 0.78f).value),
    "Exit" to (32.dp.value to 28.dp.value),
    "Edit" to (80.dp.value to 28.dp.value),
)

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

    // 按钮组（issue#169 第 20 条）：编辑态开「组」模式后，点选多颗槽位成组（亮白描边），
    // 拖动任意一颗 = 整组平移同一增量，成员相对位置保持不变；关组模式即散组。
    var padGroupMode by remember { mutableStateOf(false) }
    var padGroupSel by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 每槽位附加偏移（dp）：编辑态拖动实时改、松手落盘（[GamepadPadConfig.setOffset]），
    // 下次进面板仍生效。按键与摇杆共用这一套。
    val padCtx = LocalContext.current
    var padOffsets by remember {
        mutableStateOf(OFFSET_SLOTS.associateWith { GamepadPadConfig.offset(padCtx, it) })
    }
    // 编辑对齐参考线（issue#169 第 19 条）：拖动中吸附生效时记 (x, y) 轴线坐标（dp 浮点），
    // 松手由 padMoveEnd 撤掉 —— 只在拖动过程中可见
    var padGuides by remember { mutableStateOf<Pair<Float?, Float?>>(null to null) }
    val padMoveBy: (String, Float, Float) -> Unit = { slot, dx, dy ->
        val cur = padOffsets[slot] ?: (0f to 0f)
        padOffsets = padOffsets + (slot to ((cur.first + dx) to (cur.second + dy)))
    }
    val padMoveEnd: (String) -> Unit = { slot ->
        val off = padOffsets[slot] ?: (0f to 0f)
        GamepadPadConfig.setOffset(padCtx, slot, off.first, off.second)
        // 松手即撤对齐轴线（issue#169 第 19 条）：提示只属于「正在拖」
        padGuides = null to null
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

        // —— 编辑对齐吸附（issue#169 第 19 条）：拖动时与其它槽位中心做坐标轴贴合 ——
        // 吸上的那根轴记进 padGuides，画布层给出加宽轴线提示；吸附半径 7dp 见 PAD_SNAP_DP。
        val centers = remember(W, H) { padBaseCenters(W, H) }
        fun snapMove(slot: String, dx: Float, dy: Float) {
            val base = centers[slot]
            if (base == null) {
                padMoveBy(slot, dx, dy)
                return
            }
            val cur = padOffsets[slot] ?: (0f to 0f)
            val nx = base.first + cur.first + dx
            val ny = base.second + cur.second + dy
            var sx: Float? = null
            var sy: Float? = null
            for ((_, oc) in centers) {
                if (sx == null && abs(nx - oc.first) <= PAD_SNAP_DP.value) sx = oc.first
                if (sy == null && abs(ny - oc.second) <= PAD_SNAP_DP.value) sy = oc.second
            }
            padGuides = sx to sy
            // 吸附时把这一帧位移改写为「贴到轴上」的差值；未吸上的轴照常跟手
            padMoveBy(
                slot,
                sx?.let { it - base.first - cur.first } ?: dx,
                sy?.let { it - base.second - cur.second } ?: dy,
            )
        }

        // 对齐参考线（issue#169 第 19 条）：吸附生效时画加宽的十字轴提示，拖动结束即撤；
        // 垫在所有按键底下（Box 内第一个孩子），不挡编辑态的编辑白罩与拖拽面。
        val guideColor = colorResource(R.color.overlay_pad_ok)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lw = 2.5.dp.toPx()
            padGuides.first?.let { x ->
                drawLine(guideColor, Offset(x.dp.toPx(), 0f), Offset(x.dp.toPx(), size.height), lw)
            }
            padGuides.second?.let { y ->
                drawLine(guideColor, Offset(0f, y.dp.toPx()), Offset(size.width, y.dp.toPx()), lw)
            }
        }

        // —— 按钮组的移动/点选分发（issue#169 第 20 条）：组内槽位拖动 = 整组同增量 ——
        // 组移动不做单键吸附（组相对位置不能被吸附改写）；点选只在组模式下翻转选中。
        fun groupAwareMove(slot: String, dx: Float, dy: Float) {
            if (padGroupMode && slot in padGroupSel) {
                padGroupSel.forEach { padMoveBy(it, dx, dy) }
            } else {
                snapMove(slot, dx, dy)
            }
        }
        fun groupAwareEnd(slot: String) {
            if (padGroupMode && slot in padGroupSel) {
                padGroupSel.forEach { padMoveEnd(it) }
            } else {
                padMoveEnd(slot)
            }
        }
        fun groupAwareTap(slot: String) {
            if (padGroupMode) {
                padGroupSel = if (slot in padGroupSel) padGroupSel - slot else padGroupSel + slot
            } else {
                padConfiguring = slot
            }
        }

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

        // 退出（issue#169 第 21 条：编辑态也可拖可配；普通态仍直接退出）
        val exitDrag = padDrag("Exit")
        val exitSize = remember(padConfigVersion) { GamepadPadConfig.sizeDp(padCtx, "Exit", 40) }
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 12.dp + exitDrag.first, y = 8.dp + exitDrag.second)) {
            Pad(label = "✕", bit = 0, width = exitSize.dp, height = exitSize.dp, onDown = onExit)
            if (padEditing) {
                FuncDragOverlay(
                    onTap = { padConfiguring = "Exit" },
                    onMoveBy = { dx, dy -> padMoveBy("Exit", dx, dy) },
                    onMoveEnd = { padMoveEnd("Exit") },
                )
            }
        }
        // 编辑开关（Moonlight 式模式切换）：✎ 进编辑、✓ 收工。退出编辑顺带收起配置卡。
        // 编辑态永远保持普通手势路径（editing 不随开关变）：它必须一直点得动。
        // issue#169 第 21 条：编辑态下这颗键也可拖动移位；点按仍保留「收工」语义 ——
        // 它是编辑态唯一的退出口，不能被拖拽面吞掉。
        val editDrag = padDrag("Edit")
        val editSize = remember(padConfigVersion) { GamepadPadConfig.sizeDp(padCtx, "Edit", 40) }
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 60.dp + editDrag.first, y = 8.dp + editDrag.second)) {
            Pad(
                label = if (padEditing) "✓" else "✎", bit = 0, width = editSize.dp, height = editSize.dp,
                onDown = {
                    padEditing = !padEditing
                    if (!padEditing) {
                        padConfiguring = null
                        // 退出编辑顺带散组（issue#169 第 20 条）
                        padGroupMode = false
                        padGroupSel = emptySet()
                    }
                    Feedback.haptic(padCtx)
                },
            )
            if (padEditing) {
                FuncDragOverlay(
                    onTap = {
                        padEditing = false
                        padConfiguring = null
                        padGroupMode = false
                        padGroupSel = emptySet()
                        Feedback.haptic(padCtx)
                    },
                    onMoveBy = { dx, dy -> padMoveBy("Edit", dx, dy) },
                    onMoveEnd = { padMoveEnd("Edit") },
                )
            }
        }
        // 按钮组开关（issue#169 第 20 条）：「组」进多选模式 —— 编辑态点选槽位成组
        // （亮白描边），拖任意一颗整组平移、保持相对位置；再点一次散组。
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 108.dp, y = 8.dp)) {
            Pad(
                label = stringResource(if (padGroupMode) R.string.ui_gamepad_btn_group_on else R.string.ui_gamepad_btn_group),
                bit = 0, width = 40.dp, height = 40.dp,
                onDown = {
                    padGroupMode = !padGroupMode
                    if (!padGroupMode) padGroupSel = emptySet()
                    Feedback.haptic(padCtx)
                },
            )
        }

        // —— 扳机 L2 / R2（顶部第一排，真机在肩键后面，屏上画成药丸） ——
        val l2Drag = padDrag("L2")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 104.dp + l2Drag.first, y = H * 0.02f + l2Drag.second)) {
            Pad(label = "L2", bit = GamepadController.BTN_L2, width = 68.dp, height = 32.dp, pill = true, slot = "L2", editing = padEditing, selected = padGroupMode && "L2" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("L2") },
                onMoveBy = { dx, dy -> groupAwareMove("L2", dx, dy) }, onMoveEnd = { groupAwareEnd("L2") })
        }
        val r2Drag = padDrag("R2")
        Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-104).dp + r2Drag.first, y = H * 0.02f + r2Drag.second)) {
            Pad(label = "R2", bit = GamepadController.BTN_R2, width = 68.dp, height = 32.dp, pill = true, slot = "R2", editing = padEditing, selected = padGroupMode && "R2" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("R2") },
                onMoveBy = { dx, dy -> groupAwareMove("R2", dx, dy) }, onMoveEnd = { groupAwareEnd("R2") })
        }

        // —— 肩键 L1 / R1（第二排） ——
        val l1Drag = padDrag("L1")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 110.dp + l1Drag.first, y = H * 0.125f + l1Drag.second)) {
            Pad(label = "L1", bit = GamepadController.BTN_L1, width = 64.dp, slot = "L1", editing = padEditing, selected = padGroupMode && "L1" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("L1") },
                onMoveBy = { dx, dy -> groupAwareMove("L1", dx, dy) }, onMoveEnd = { groupAwareEnd("L1") })
        }
        val r1Drag = padDrag("R1")
        Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-110).dp + r1Drag.first, y = H * 0.125f + r1Drag.second)) {
            Pad(label = "R1", bit = GamepadController.BTN_R1, width = 64.dp, slot = "R1", editing = padEditing, selected = padGroupMode && "R1" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("R1") },
                onMoveBy = { dx, dy -> groupAwareMove("R1", dx, dy) }, onMoveEnd = { groupAwareEnd("R1") })
        }

        // —— 十字键（左侧中部；此前缺失，占报告位 12..15，见 GamepadController 注释） ——
        // 簇内四键中心间距 = 键径（58dp），拼成正十字：Up 在上、Down 在下、Left/Right 在侧。
        // 锚点走钳制版（dpadAnchorX）：窄面板上 0.13W−87dp 会越出左缘（见函数注释）
        val dpadCx = dpadAnchorX(W)
        val dpadCy = H * 0.37f
        val dUpDrag = padDrag("DUp")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 29.dp + dUpDrag.first, y = dpadCy - 87.dp + dUpDrag.second)) {
            Pad(label = "▲", bit = GamepadController.BTN_DUP, slot = "DUp", editing = padEditing, selected = padGroupMode && "DUp" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("DUp") },
                onMoveBy = { dx, dy -> groupAwareMove("DUp", dx, dy) }, onMoveEnd = { groupAwareEnd("DUp") })
        }
        val dDownDrag = padDrag("DDown")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 29.dp + dDownDrag.first, y = dpadCy + 29.dp + dDownDrag.second)) {
            Pad(label = "▼", bit = GamepadController.BTN_DDOWN, slot = "DDown", editing = padEditing, selected = padGroupMode && "DDown" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("DDown") },
                onMoveBy = { dx, dy -> groupAwareMove("DDown", dx, dy) }, onMoveEnd = { groupAwareEnd("DDown") })
        }
        val dLeftDrag = padDrag("DLeft")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 87.dp + dLeftDrag.first, y = dpadCy - 29.dp + dLeftDrag.second)) {
            Pad(label = "◀", bit = GamepadController.BTN_DLEFT, slot = "DLeft", editing = padEditing, selected = padGroupMode && "DLeft" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("DLeft") },
                onMoveBy = { dx, dy -> groupAwareMove("DLeft", dx, dy) }, onMoveEnd = { groupAwareEnd("DLeft") })
        }
        val dRightDrag = padDrag("DRight")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx + 29.dp + dRightDrag.first, y = dpadCy - 29.dp + dRightDrag.second)) {
            Pad(label = "▶", bit = GamepadController.BTN_DRIGHT, slot = "DRight", editing = padEditing, selected = padGroupMode && "DRight" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("DRight") },
                onMoveBy = { dx, dy -> groupAwareMove("DRight", dx, dy) }, onMoveEnd = { groupAwareEnd("DRight") })
        }

        // —— 面键 ✕○□△（右侧中部，PS5 菱形位：△ 上 / ○ 右 / ✕ 下 / □ 左） ——
        // 锚点走钳制版（faceAnchorX）：窄面板上 0.87W+87dp 越出右缘，ABXY 整簇被裁掉
        val faceCx = faceAnchorX(W)
        val faceCy = H * 0.37f
        val yDrag = padDrag("Y")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 29.dp + yDrag.first, y = faceCy - 87.dp + yDrag.second)) {
            Pad(label = "△", bit = GamepadController.BTN_Y, slot = "Y", editing = padEditing, selected = padGroupMode && "Y" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("Y") },
                onMoveBy = { dx, dy -> groupAwareMove("Y", dx, dy) }, onMoveEnd = { groupAwareEnd("Y") })
        }
        val bDrag = padDrag("B")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx + 29.dp + bDrag.first, y = faceCy - 29.dp + bDrag.second)) {
            Pad(label = "○", bit = GamepadController.BTN_B, slot = "B", editing = padEditing, selected = padGroupMode && "B" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("B") },
                onMoveBy = { dx, dy -> groupAwareMove("B", dx, dy) }, onMoveEnd = { groupAwareEnd("B") })
        }
        val aDrag = padDrag("A")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 29.dp + aDrag.first, y = faceCy + 29.dp + aDrag.second)) {
            Pad(label = "✕", bit = GamepadController.BTN_A, slot = "A", editing = padEditing, selected = padGroupMode && "A" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("A") },
                onMoveBy = { dx, dy -> groupAwareMove("A", dx, dy) }, onMoveEnd = { groupAwareEnd("A") })
        }
        val xDrag = padDrag("X")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 87.dp + xDrag.first, y = faceCy - 29.dp + xDrag.second)) {
            Pad(label = "□", bit = GamepadController.BTN_X, slot = "X", editing = padEditing, selected = padGroupMode && "X" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("X") },
                onMoveBy = { dx, dy -> groupAwareMove("X", dx, dy) }, onMoveEnd = { groupAwareEnd("X") })
        }

        // —— Create / Options（顶部中轴两侧，PS5 的 Share/Options 位） ——
        val selDrag = padDrag("Sel")
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(x = W * 0.40f - W / 2 + selDrag.first, y = H * 0.05f + selDrag.second)) {
            Pad(label = "Create", bit = GamepadController.BTN_SELECT, width = 76.dp, height = 30.dp, pill = true, slot = "Sel", editing = padEditing, selected = padGroupMode && "Sel" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("Sel") },
                onMoveBy = { dx, dy -> groupAwareMove("Sel", dx, dy) }, onMoveEnd = { groupAwareEnd("Sel") })
        }
        val startDrag = padDrag("Start")
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(x = W * 0.60f - W / 2 + startDrag.first, y = H * 0.05f + startDrag.second)) {
            Pad(label = "Options", bit = GamepadController.BTN_START, width = 76.dp, height = 30.dp, pill = true, slot = "Start", editing = padEditing, selected = padGroupMode && "Start" in padGroupSel,
                configVersion = padConfigVersion, onConfigure = { groupAwareTap("Start") },
                onMoveBy = { dx, dy -> groupAwareMove("Start", dx, dy) }, onMoveEnd = { groupAwareEnd("Start") })
        }

        // —— 双摇杆（底部中轴两侧；PlayStation 系摇杆不贴角） ——
        // 编辑态：上层盖一块透明拖拽面接管触摸（摇杆本体收不到事件），拖 = 移位落盘；
        // 普通态不盖章，摇杆照常吃输入。JoystickView 自身不动。
        // 摇杆横向锚点同样钳制（与 padBaseCenters 同式）：窄面板防摇杆越出左右缘
        val lsCx = (W * 0.27f).coerceAtLeast(stickSize / 2 + 8.dp)
        val rsCx = (W * 0.73f).coerceAtMost(W - stickSize / 2 - 8.dp)
        Stick(
            slot = "LStick",
            modifier = Modifier.align(Alignment.TopStart)
                .offset(x = lsCx - stickSize / 2 + padDrag("LStick").first, y = H * 0.78f - stickSize / 2 + padDrag("LStick").second),
            size = stickSize, editing = padEditing,
            selected = padGroupMode && "LStick" in padGroupSel,
            onTap = { groupAwareTap("LStick") },
            onMoveBy = { dx, dy -> groupAwareMove("LStick", dx, dy) }, onMoveEnd = { groupAwareEnd("LStick") },
            onAxis = { x, y -> GamepadController.setLeftAxis(x, y) },
            buttonBit = GamepadController.BTN_L3,
        )
        Stick(
            slot = "RStick",
            modifier = Modifier.align(Alignment.TopStart)
                .offset(x = rsCx - stickSize / 2 + padDrag("RStick").first, y = H * 0.78f - stickSize / 2 + padDrag("RStick").second),
            size = stickSize, editing = padEditing,
            selected = padGroupMode && "RStick" in padGroupSel,
            onTap = { groupAwareTap("RStick") },
            onMoveBy = { dx, dy -> groupAwareMove("RStick", dx, dy) }, onMoveEnd = { groupAwareEnd("RStick") },
            onAxis = { rx, ry -> GamepadController.setRightAxis(rx, ry) },
            buttonBit = GamepadController.BTN_R3,
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
                // issue#169 第 21 条：功能键（✕/✎）没有键位可改，只出「尺寸」行 ——
                // 键位行对着 0 号位显示「?」反而让用户误以为坏了
                val isFunc = GamepadPadConfig.FUNC_SLOTS.contains(slot)
                Text(text = "自定义：$slot", fontSize = 16.sp, color = colorResource(R.color.overlay_pad_text))
                if (!isFunc) {
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
                }
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
 * 功能键（✕ / ✎）编辑态拖拽面（issue#169 第 21 条）。
 *
 * 手势骨架与 [Stick] 的编辑拖拽层同一套（拖 = 移位、tap = 点按动作）：
 * · 拖动：实时改面板层 offset，松手落盘（[GamepadPadConfig.setOffset]）；
 * · tap：交给调用方 —— ✕ 弹尺寸配置卡，✎ 保留「收工」语义（它是编辑态
 *   唯一的退出口，不能被拖拽面吞掉）。
 * 只有编辑态才挂这层面（父级 `if (padEditing)` 控制），普通态 ✕ / ✎ 照常响应点按。
 */
// BoxScope 扩展：调用点都在父 Box 的内容 lambda 里（✕ / ✎ 两处），
// matchParentSize 是 BoxScope 成员 —— 不带接收者时裸 Modifier 链上
// 引不到它（fd842c1 的 CI 实锤 Unresolved reference）。
// 语义也不能换成 fillMaxSize：这里要的是「贴住父 Box 的内容盒」
// （= 40dp 功能键大小），fillMaxSize 会吃满父级约束（整屏）。
@Composable
private fun BoxScope.FuncDragOverlay(
    onTap: () -> Unit,
    onMoveBy: (Float, Float) -> Unit,
    onMoveEnd: () -> Unit,
) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(Unit) {
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
                    if (moved) onMoveEnd() else onTap()
                }
            },
    )
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
    // issue#169 第 20 条：组模式选中态 + 拖拽面 tap 点选（与 Pad 的组选行为对齐）
    selected: Boolean = false,
    onTap: (() -> Unit)? = null,
    onMoveBy: (Float, Float) -> Unit,
    onMoveEnd: () -> Unit,
    onAxis: (Int, Int) -> Unit,
    // issue#169 第 17 条：点住未拖 = 摇杆键（L3/R3）。位号由调用方给（<0 表示无），
    // JoystickView 的 onButton 回调直接换算成 GamepadController.setButton。
    buttonBit: Int = -1,
) {
    val ctx = LocalContext.current
    // tap 回调经 rememberUpdatedState 桥进手势协程，避免重组重启拖拽手势
    val currentOnTap by rememberUpdatedState(onTap)
    Box(modifier = modifier.size(size)) {
        AndroidView(
            factory = { c ->
                JoystickView(c).also { v ->
                    v.onAxis = onAxis
                    // 摇杆键：点住未拖 = L3/R3 按下，拖出 slop 或抬手 = 抬起
                    v.onButton = { down ->
                        if (buttonBit >= 0) GamepadController.setButton(buttonBit, down)
                    }
                }
            },
            // 编辑态禁用摇杆本体：JoystickView 在 isEnabled=false 时交出触摸，
            // 由上层拖拽面接管，避免「挪摇杆」和「扳摇杆」同时发生
            update = { v -> v.isEnabled = !editing },
            modifier = Modifier.size(size).alpha(if (editing) 0.5f else 1f),
        )
        if (editing) {
            Box(modifier = Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f), CircleShape))
            // 组选中视觉：3dp 亮白描边圆圈（优先级只管画在最上层，语义与 Pad 一致）
            if (selected) {
                Box(modifier = Modifier.matchParentSize().border(3.dp, colorResource(R.color.overlay_pad_stroke_pressed), CircleShape))
            }
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
                            // 未过 slop 就抬手 = tap：组模式下点选摇杆，普通模式交给配置卡
                            if (moved) onMoveEnd() else currentOnTap?.invoke()
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
    // issue#169 第 20 条：组模式下被点选中的按钮 —— 亮白描边优先于编辑绿，提示「整组一起动」
    selected: Boolean = false,
    onDown: (() -> Unit)? = null,
    onConfigure: (() -> Unit)? = null,
    onMoveBy: ((Float, Float) -> Unit)? = null,
    onMoveEnd: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    // 键位 / 尺寸按配置版本重读：保存配置后父级状态变化触发重组，这里就拿到新值
    val effBit = if (slot == null) bit else GamepadPadConfig.bit(ctx, slot, bit)
    // issue#169 第 18 条：编辑态捏边缘无级调整 —— 手势里实时改的本地尺寸优先于配置档
    var liveSize by remember { mutableStateOf<Float?>(null) }
    val effW = liveSize?.dp
        ?: (if (slot == null) width else GamepadPadConfig.sizeDp(ctx, slot, width.value.toInt()).dp)
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
            selected -> colorResource(R.color.overlay_pad_stroke_pressed) // 选中 = 亮白，优先于编辑绿
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
                width = when {
                    selected -> 3.dp        // 选中描边加粗一档，和普通按压区分开
                    pressed || editing -> 2.dp
                    else -> 1.dp
                },
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
                        // —— 编辑态：不发 HID。捏边缘 = 无级调大，拖 = 移位，tap = 配置。 ——
                        Feedback.haptic(ctx)
                        var moved = false
                        // issue#169 第 18 条：按下点落在边缘环（外圈 12dp）即进调尺寸手势；
                        // 直径 = 2 ×（手指到中心距离），拖动全程实时跟随，每帧落盘。
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val radiusPx = minOf(effW.value, effH.value) / 2f * density
                        val edgePx = 12.dp.toPx()
                        var resizing = !slot.isNullOrEmpty() &&
                            hypot(down.position.x - cx, down.position.y - cy) > radiusPx - edgePx
                        // resizing 成立 = slot 非空（见上一行的 isNullOrEmpty 判定），
                        // 但那是 var 初始化处的判定，编译器不会跨语句 smart cast；
                        // setSize 形参是非空 String —— 取一份局部非空副本落盘，
                        // 否则 CI 编译红（fd842c1 实锤「String? 但 String expected」）。
                        val slotKey = if (slot.isNullOrEmpty()) null else slot
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
                            if (resizing) {
                                // 边缘无级调大：直径跟着手指到中心的距离走（限制在合理档位内）
                                val d = (2f * hypot(x - cx, y - cy) / density)
                                    .coerceIn(PAD_RESIZE_MIN_DP, PAD_RESIZE_MAX_DP)
                                liveSize = d
                                slotKey?.let { GamepadPadConfig.setSize(ctx, it, d.toInt()) }
                            } else if (moved) {
                                // 按住拖动：按钮实时跟手（面板层改 offset），松手落盘
                                val onMoveNow = currentOnMoveBy
                                if (onMoveNow != null) onMoveNow((x - lastX) / density, (y - lastY) / density)
                            }
                            lastX = x
                            lastY = y
                        }
                        if (moved) {
                            if (resizing) {
                                // 尺寸已落盘，本地实时值退回配置驱动（数值相同，观感无跳变）
                                liveSize = null
                            } else {
                                currentOnMoveEnd?.invoke()
                            }
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
