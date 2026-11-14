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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.core.Uplink
import com.allperiph.core.UplinkEvent
import com.allperiph.shared.event.EventBus
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApType
import com.allperiph.wireless.ControlTarget
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import com.allperiph.ui.miuix.applyEdgeToEdge
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.allperiph.R
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

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
 *    2026-11-13 遮挡批（几何自查 + 对齐 Moonlight 分层思路）：手机横屏高度不够把
 *    肩键排和十字/面键簇上下分开，改横向错开——L1/R1 锚到簇外缘（肩键在簇顶键
 *    外侧偏下，近似真机前视图的扳机/肩键错位），簇纵向锚点在短宽面板自动下沉；
 *    「组」开关改编辑态才出现（原常驻位正压 L2）。
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
        // v223.29.90（成宇 2026-11-13 截图「顶部被状态栏覆盖」）：applyEdgeToEdge 的
        // post-show 会打穿下面 applyLandscapeBarPref 的 hide（post 跑在 onResume 之后），
        // 所以把隐藏口径直接传进去 —— post 的动作与偏好一致，不再先 hide 再被抬回。
        // 立即再压一次是「不等 post、先按当前口径走」的保险；悬浮手柄宿主是 Service
        // overlay，层高状态栏，不受影响。
        val hideBar = getSharedPreferences("apx", MODE_PRIVATE)
            .getBoolean("landscape_hide_status", true)
        applyEdgeToEdge(hideSystemBars = hideBar)
        // v223.29.86 修复（用户原话「手柄页没有屏蔽状态栏」）：手柄页是强制横屏的独立
        // Activity 宿主，此前只 applyEdgeToEdge、从未读设置里的 landscape_hide_status
        // —— 同一份 apx 偏好，主壳横屏隐藏状态栏、这里却一直显示。补上同 Shell 的隐藏
        // 逻辑（默认开）：按 TRANSIENT_BY_SWIPE 短暂呼出半透明层，退出时随窗口销毁
        // 自然恢复，无需手动 show。
        // v223.29.87 追补：隐藏只在 onCreate 压一次不够 —— 厂商系统在通知/系统面板
        // 交互后会自行把状态栏抬回来，此后没人再压（2026-11-13 用户截图时钟叠在 L2 上）。
        // 抽成 applyLandscapeBarPref，onResume / onWindowFocusChanged 再压，回前台即恢复隐藏。
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

    /** v223.29.90 追补：焦点回到手柄页时再压一次 —— 通知面板/系统交互后系统会把
     *  状态栏自行抬回，而页面没退、onResume 不会再跑；这里补上无人再压的空档。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyLandscapeBarPref()
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

/**
 * 药丸槽位的宽高比（高 / 宽，issue#170 #4）：药丸的 sizeDp 语义 = **宽度**，高度按
 * 各自出厂比例随动（圆键宽高一致，比值恒 1，不在此表）。覆盖判定（轴线重合变红）
 * 也要用同一比例算按钮短边半径，见 [padSlotHalfExtents]。
 */
private val PAD_PILL_RATIO_H = mapOf(
    "L2" to 32f / 68f,
    "R2" to 32f / 68f,
    "Sel" to 30f / 76f,
    "Start" to 30f / 76f,
    "Shot" to 30f / 40f,
)

/**
 * 手柄面板的屏幕档位缩放系数（2026-11-13 用户「要对手机和平板做不同布局」）：
 *
 * 手机档恒为 1（布局与尺寸档完全不变）；平板 / 电视档把整套几何按面板**短边**等比
 * 放大 —— 键径 58dp、簇内间距、摇杆、功能键、配置卡全部吃同一个系数，观感与手机
 * 横屏一致，只是整体变大。系数 = 短边 / 360dp，封顶 1.5：超大屏（如 1280dp 高的
 * 平板横屏）键径到 87dp 封住，不无限放大。判定走 [DeviceFormPref]（短边 600dp
 * 判平板 + 设置页手动覆盖），不自己造判据。
 */
internal fun padPanelScale(W: Dp, H: Dp, ctx: android.content.Context): Float =
    if (DeviceFormPref.resolve(ctx, W.value, H.value) == DeviceFormPref.Form.Phone) {
        1f
    } else {
        (minOf(W.value, H.value) / 360f).coerceIn(1f, 1.5f)
    }

/**
 * 十字键/面键簇的横向锚点（issue#169 追评 2026-11-13「pad 上 app 手柄和悬浮球手柄都缺
 * ABXY」）：两簇按 0.13W/0.87W 布锚，簇中心到边键外缘最宽 [scale]×87dp —— 面板宽
 * W 过窄时（pad 高密度屏、分屏/自由窗口都可能出现），B 键右缘越出面板右缘，整簇
 * 被裁出屏外，看起来就是「缺 4 个键」。锚点先按百分比、再钳进「整簇可见」范围
 * （左右各留 16dp×scale 边距）；极窄面板两簇会互相靠近，但至少键都在屏上、点得到
 * 也拖得回来。⚠️ 与 padBaseCenters 的公式同源（吸附基准必须和实际布局一致，改一处
 * 必须同步另一处）。
 */
internal fun dpadAnchorX(W: Dp, scale: Float): Dp = (W * 0.13f).coerceAtLeast(103.dp * scale)

internal fun faceAnchorX(W: Dp, scale: Float): Dp = (W * 0.87f).coerceAtMost(W - 103.dp * scale)

/**
 * 十字/面键簇的纵向锚点：默认 0.37H；但短宽面板（手机横屏 360–411dp 高）上 0.37H
 * 会把簇顶两键（▲/△）顶进 L1/R1 一行（2026-11-13 几何自查实锤 L1×▲ 达 22×58dp）。
 * 下移条件：簇顶缘（锚点 − 87dp×scale）至少让开 L1/R1 底缘（0.125H + 64dp×scale）
 * 再留 8dp。下移后簇侧键（▶/□，底缘 = 锚点 + 29dp×scale）与双摇杆顶缘
 * （0.78H − stickSize/2）纵向净距 ≥8dp（H≥345dp×scale 时恒成立，再矮的窗口极罕见）。
 * ⚠️ 与 padBaseCenters 的公式同源（吸附基准必须和实际布局一致，改一处必须同步另一处）。
 */
internal fun clusterCy(H: Dp, scale: Float): Dp = maxOf(H * 0.37f, H * 0.125f + 101.dp * scale)

/**
 * 摇杆横向锚点：默认 0.27W / 0.73W；但极窄面板（W≈640dp 一档）上簇底键（▼/✕，
 * 底缘 = [clusterCy] + 87dp×scale）与摇杆顶带（0.78H − stickSize/2）纵向相叠、
 * 横向也吃进摇杆（▼ 右缘 = dpadAnchorX + 29dp×scale > 摇杆左缘），2026-11-13 几何
 * 自查实锤 16:9 面板 28×21dp。此时摇杆向内让开：左摇杆锚 ≥ dpadAnchorX + 37dp×scale
 * + stickSize/2（左缘让开 ▼ 右缘 8dp），右摇杆对称。簇与摇杆纵向不叠时不挪
 * （平板档 anchor 不受影响）。⚠️ 与 padBaseCenters 的公式同源（吸附基准必须和实际
 * 布局一致，改一处必须同步另一处）。
 */
internal fun stickAnchorX(W: Dp, H: Dp, stickSize: Dp, scale: Float, left: Boolean): Dp {
    val base = if (left) W * 0.27f else W * 0.73f
    val clusterBottom = clusterCy(H, scale) + 87.dp * scale
    val stickTop = H * 0.78f - stickSize / 2
    if (clusterBottom <= stickTop) return base
    val inner = if (left) dpadAnchorX(W, scale) + 37.dp * scale + stickSize / 2
    else faceAnchorX(W, scale) - 37.dp * scale - stickSize / 2
    return if (left) maxOf(base, inner) else minOf(base, inner)
}

/**
 * 各槽位默认中心坐标（dp 浮点，面板坐标系）—— issue#169 第 19 条对齐吸附用。
 *
 * ⚠️ 与 GamepadPanel 布局代码里的 align/offset 公式一一对应（改布局必须同步这里）：
 * 上左为原点，W/H 为面板整幅。功能键 Exit/Edit 的默认位
 * 也收录（12/60dp 起、40dp 见方，见 ✕/✎ 两格的 offset 公式）。
 * 十字/面键锚点走 [dpadAnchorX]/[faceAnchorX]（窄面板钳制，见函数注释）；
 * 纵向锚点走 [clusterCy]（短宽面板下移让开肩键排，见函数注释）。
 * 2026-11-13 平板布局批：固定 dp 全部乘 [scale]（[padPanelScale] 的返回值），
 * 摇杆尺寸 [stickSize] 由调用处传入（与布局侧同一表达式），保证吸附基准 = 实际布局。
 * 2026-11-13 遮挡批：L1/R1 横向锚到簇外缘（L1 左缘 = dpadAnchorX + 34dp×scale、
 * R1 右缘 = faceAnchorX − 34dp×scale，与簇顶键横向净距 5dp），配合 [clusterCy]
 * 下移后 L1/R1 与 ▲/△/▶/□ 全脱开。
 */
private fun padBaseCenters(W: Dp, H: Dp, stickSize: Dp, scale: Float): Map<String, Pair<Float, Float>> = mapOf(
    "L2" to ((138.dp * scale).value to (H * 0.02f + 16.dp * scale).value),
    "R2" to ((W - 138.dp * scale).value to (H * 0.02f + 16.dp * scale).value),
    "L1" to ((dpadAnchorX(W, scale) + 66.dp * scale).value to (H * 0.125f + 32.dp * scale).value),
    "R1" to ((faceAnchorX(W, scale) - 66.dp * scale).value to (H * 0.125f + 32.dp * scale).value),
    "DUp" to (dpadAnchorX(W, scale).value to (clusterCy(H, scale) - 58.dp * scale).value),
    "DDown" to (dpadAnchorX(W, scale).value to (clusterCy(H, scale) + 58.dp * scale).value),
    "DLeft" to ((dpadAnchorX(W, scale) - 58.dp * scale).value to clusterCy(H, scale).value),
    "DRight" to ((dpadAnchorX(W, scale) + 58.dp * scale).value to clusterCy(H, scale).value),
    "Y" to (faceAnchorX(W, scale).value to (clusterCy(H, scale) - 58.dp * scale).value),
    "A" to (faceAnchorX(W, scale).value to (clusterCy(H, scale) + 58.dp * scale).value),
    "X" to ((faceAnchorX(W, scale) - 58.dp * scale).value to clusterCy(H, scale).value),
    "B" to ((faceAnchorX(W, scale) + 58.dp * scale).value to clusterCy(H, scale).value),
    "Sel" to ((W * 0.40f).value to (H * 0.05f + 15.dp * scale).value),
    "Start" to ((W * 0.60f).value to (H * 0.05f + 15.dp * scale).value),
    // 摇杆也要防出屏：摇杆直径 = stickSize（布局侧同式 min(180dp×scale, H*0.38)），
    // 锚点走 stickAnchorX（窄面板簇/摇杆相叠时向外让开），再钳进「半径 + 8dp×scale 边距」内
    "LStick" to (stickAnchorX(W, H, stickSize, scale, left = true).coerceAtLeast(stickSize / 2 + 8.dp * scale).value to (H * 0.78f).value),
    "RStick" to (stickAnchorX(W, H, stickSize, scale, left = false).coerceAtMost(W - stickSize / 2 - 8.dp * scale).value to (H * 0.78f).value),
    "Exit" to ((32.dp * scale).value to (28.dp * scale).value),
    "Edit" to ((80.dp * scale).value to (28.dp * scale).value),
    // issue#170 #6：📷/组/复位/？ 四键入编辑体系，也进吸附与轴线判定。
    // 📷 在 Select/Start 中缝（高 30dp 默认，中心 = H*0.05 + 15）；
    // 组 / 复位 / ？ 默认锚在左下 12dp 列（？底距 12、组 58、复位 104，均 40dp 见方，
    // 中心 = 32 与「H − 底距 − 20」）—— 与布局侧 align(BottomStart) + padding 公式一致
    "Shot" to ((W / 2).value to (H * 0.05f + 15.dp * scale).value),
    "Group" to ((32.dp * scale).value to (H - 78.dp * scale).value),
    "ResetAll" to ((32.dp * scale).value to (H - 124.dp * scale).value),
    "Help" to ((32.dp * scale).value to (H - 32.dp * scale).value),
)

/**
 * 槽位的实时半边长（dp 浮点）：轴线重合判定（issue#170 #5）用 —— 轴线穿过对方
 * 「按钮体」才算命中。圆键 = 直径一半；药丸宽 = sizeDp 一半、高 = 宽 × 出厂比例
 * （[PAD_PILL_RATIO_H]，sizeDp 语义 = 宽）；摇杆 = 摇杆直径一半。
 * 读尺寸走 [GamepadPadConfig.sizeDp]，与按钮渲染同一套口径（实时偏移由调用方叠加）。
 */
private fun padSlotHalfExtents(ctx: Context, slot: String, stickSize: Dp): Pair<Float, Float> {
    if (slot in GamepadPadConfig.STICK_SLOTS) {
        val r = stickSize.value / 2f
        return r to r
    }
    val def = GamepadPadConfig.SLOT_DEFAULTS[slot] ?: return 29f to 29f
    val size = GamepadPadConfig.sizeDp(ctx, slot, def.second).toFloat()
    val ratioH = PAD_PILL_RATIO_H[slot]
    return if (ratioH == null) size / 2f to size / 2f else size / 2f to size * ratioH / 2f
}

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

    // 编辑说明（REQ 手柄编辑器 本批 4）：进编辑态首次自动弹操作提示（看过落盘不再自动弹），
    // 显示 6 秒或点任意按钮后淡出；面板左下「？」随时可重看。画在顶部提示列里，与出口
    // 状态文字同列顺序排布 —— 永不叠字，也不拦截手势。
    var showEditingTip by remember { mutableStateOf(false) }
    LaunchedEffect(showEditingTip, padEditing) {
        if (showEditingTip && padEditing) {
            delay(6000L)
            showEditingTip = false
        }
    }

    // 配置卡目标槽位：编辑态点某颗按钮弹出；保存后 bump padConfigVersion 让所有按钮重读。
    // 卡片画在面板里，不开 Compose Dialog：悬浮球 overlay 宿主是 Service 挂的窗口，
    // Dialog 要另开窗口会 BadToken。
    var padConfiguring by remember { mutableStateOf<String?>(null) }
    var padConfigVersion by remember { mutableIntStateOf(0) }

    // 编辑会话快照（REQ 手柄编辑器 二期 5）：进编辑态（✎）对配置全量快照，编辑面板
    // 「撤销」原样写回；✓ 收工即提交、快照丢弃。null = 没有快照（不在编辑态或已收工）。
    var padEditSnapshot by remember { mutableStateOf<Map<String, Any?>?>(null) }

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

    // 滑条拖动中的实时预览尺寸（存档口径 dp，issue#170 #4）：拖动帧只写内存、
    // 由 [padLiveSizes] 分发给对应按钮即时跟手，松手由配置卡统一落盘一次
    // （避免一次拖动写几十次 SharedPreferences）；落盘后清空回配置驱动。
    var padLiveSizes by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

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
        // 键径 58dp（出厂默认档），肩键 64dp，扳机 68×32 药丸，中央小件 76×30。
        // 2026-11-13 平板布局批：先按设备形态定缩放系数（手机=1，平板/电视按短边放大
        // 封顶 1.5），下面所有固定 dp 全部吃它 —— 手机档零变化，平板档整体等比放大。
        val padScale = padPanelScale(W, H, padCtx)
        val stickSize = minOf(180.dp * padScale, H * 0.38f)

        // —— 编辑对齐吸附（issue#169 第 19 条）：拖动时与其它槽位中心做坐标轴贴合 ——
        // 吸上的那根轴记进 padGuides，画布层给出加宽轴线提示；吸附半径 7dp 见 PAD_SNAP_DP
        // （平板档按 padScale 放大，与放大的键径保持同观感）。
        val centers = remember(W, H, stickSize, padScale) { padBaseCenters(W, H, stickSize, padScale) }
        val snapRadius = PAD_SNAP_DP.value * padScale
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
            // issue#170 #5（同模式修）：吸附参照也用对方实时中心（基准 + 各自偏移）——
            // 旧判定只看基准锚点，对方拖过位置后吸附轴还亮在旧坐标上，与轴线判定一个病根
            for ((s, oc) in centers) {
                val so = padOffsets[s] ?: (0f to 0f)
                val tx = oc.first + so.first
                val ty = oc.second + so.second
                if (sx == null && abs(nx - tx) <= snapRadius) sx = tx
                if (sy == null && abs(ny - ty) <= snapRadius) sy = ty
            }
            padGuides = sx to sy
            // issue#169 #18① 追评「对齐轴强制吸附导致无法移动」：吸附不再改写位移，
            // 只亮轴线提示 —— 拖动全程照常跟手，用户自己决定要不要对齐；
            // 参考线在半径内亮起、松手即撤（padMoveEnd 里清）。
            padMoveBy(slot, dx, dy)
        }

        // 对齐参考线（issue#169 第 19 条）：吸附生效时画加宽的十字轴提示，拖动结束即撤；
        // 垫在所有按键底下（Box 内第一个孩子），不挡编辑态的编辑白罩与拖拽面。
        // issue#170 #9：编辑中被点选（配置卡开着）的按钮，其横纵轴线全程常显，中心 =
        // 按钮实时中心（基准锚点 + 实时偏移，拖动跟手）。
        // issue#170 #5（二轮修 bug）：重合判定改「轴线穿过对方按钮体」——对方中心 =
        // 基准 + 各自实时偏移（旧判定漏加偏移，用户拖过的布局下永远测不准，这就是
        // 测试者说「你还没做」的原因），命中从「中心距 ≤ 2dp」放宽为对方中心到轴的
        // 距离 ≤ 对方半径（药丸按短边，见 [padSlotHalfExtents]）+ 2dp 余量，
        // 「叠按钮了」一眼就红。
        val guideColor = colorResource(R.color.overlay_pad_ok)
        val guideHitColor = colorResource(R.color.overlay_pad_warn)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lw = 2.5.dp.toPx()
            padGuides.first?.let { x ->
                drawLine(guideColor, Offset(x.dp.toPx(), 0f), Offset(x.dp.toPx(), size.height), lw)
            }
            padGuides.second?.let { y ->
                drawLine(guideColor, Offset(0f, y.dp.toPx()), Offset(size.width, y.dp.toPx()), lw)
            }
            val cfgSlot = padConfiguring
            if (padEditing && cfgSlot != null) {
                val base = centers[cfgSlot]
                if (base != null) {
                    val off = padOffsets[cfgSlot] ?: (0f to 0f)
                    val cx = base.first + off.first
                    val cy = base.second + off.second
                    // 全程 dp 浮点（旧实现 2dp 取 px 与 dp 混比，数值口径顺带修正）
                    val margin = 2f
                    val vHit = centers.any { (s, oc) ->
                        s != cfgSlot && run {
                            val so = padOffsets[s] ?: (0f to 0f)
                            val half = padSlotHalfExtents(padCtx, s, stickSize)
                            abs(oc.first + so.first - cx) <= half.first + margin
                        }
                    }
                    val hHit = centers.any { (s, oc) ->
                        s != cfgSlot && run {
                            val so = padOffsets[s] ?: (0f to 0f)
                            val half = padSlotHalfExtents(padCtx, s, stickSize)
                            abs(oc.second + so.second - cy) <= half.second + margin
                        }
                    }
                    drawLine(
                        if (vHit) guideHitColor else guideColor,
                        Offset(cx.dp.toPx(), 0f), Offset(cx.dp.toPx(), size.height), lw,
                    )
                    drawLine(
                        if (hHit) guideHitColor else guideColor,
                        Offset(0f, cy.dp.toPx()), Offset(size.width, cy.dp.toPx()), lw,
                    )
                }
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
        // 整组缩放（issue#170 #10）：随二轮 #4 捏边手势一并下线 —— 组合缩放只走
        // 配置卡滑条（组模式选中 ≥2 颗时弹组合卡同步落全部成员，见配置卡注释）。
        fun groupAwareTap(slot: String) {
            if (padGroupMode) {
                // issue#170 #7：组内已选 ≥2 颗时，点选中的成员不再「取消选中」而改弹
                // 一张组合配置卡（尺寸同步整组）；点未选中的成员仍是加入选择。
                if (slot in padGroupSel && padGroupSel.size >= 2) {
                    padConfiguring = slot
                } else {
                    padGroupSel = if (slot in padGroupSel) padGroupSel - slot else padGroupSel + slot
                }
            } else {
                padConfiguring = slot
            }
        }

        // 出口状态 + 指引（顶部居中；左右各让 170dp —— issue#168 第 8 条追补：
        // 文字整段落在两肩键之间，避让是几何上的不重叠，不靠绘制顺序兜底）
        // 2026-11-13 遮挡批：让位改随簇锚点自适应——L1/R1 横向挪到簇外缘后，
        // 窄面板（W≈640dp）上固定 170dp 会探进 L1/R1 带；改取「锚点到中轴距离
        // − 106dp×scale」（含 8dp 净距 + 键宽余量），常规宽度仍落在 170dp×padScale。
        val tipSide = run {
            val toAnchor = minOf(W / 2 - dpadAnchorX(W, padScale), faceAnchorX(W, padScale) - W / 2)
            (toAnchor - 106.dp * padScale).coerceIn(0.dp, 170.dp * padScale)
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 2026-11-13 用户截图「Create/Options 与说明文字互相压字」：两组芯片挂在
                // H*0.05（药丸高 30dp），提示从 12dp 起排多行折行正好叠进芯片带。整体
                // 下移到芯片带以下：芯片底 = H*0.05 + 30dp，这里留 4dp 间隙起排；
                // 左右让位见上方 tipSide 注释。平板档按 padScale 放大。
                .padding(top = H * 0.05f + 34.dp * padScale, start = tipSide, end = tipSide),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = statusLine,
                fontSize = ApType.caption,
                textAlign = TextAlign.Center, // issue#168 第 8 条：提示文字居中 + 自动换行
                color = if (statusOk) colorResource(R.color.overlay_pad_ok) else colorResource(R.color.overlay_pad_warn),
                modifier = Modifier.fillMaxWidth(),
            )
            if (adviceLine.isNotEmpty()) {
                Text(
                    text = adviceLine,
                    fontSize = ApType.micro,
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
                    fontSize = ApType.micro,
                    textAlign = TextAlign.Center,
                    color = colorResource(R.color.overlay_pad_warn),
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                )
            }
            // 编辑操作说明（REQ 本批 4）：首次进编辑态自动弹一行，6 秒或点按钮后淡出。
            // issue#170 #7：提示本体挪出提示列，改画为面板 Box 最后一个子项 —— 居中、
            // 最上层、至多 3 行，不再被后画的按钮盖住（见面板 Box 尾部的弹层）。
        }

        // 「？」重看编辑说明（REQ 本批 4）：只在编辑态出现，点一下重新弹顶部的操作提示。
        // 放左下角：该角空着（双摇杆锚在 27% / 73% 宽），触点与既有任何元素都不相交。
        // issue#170 #6：编辑态可拖（偏移落盘 Help 槽位）；tap 保留「重看说明」原语义，
        // 不进配置卡（本批口径：tap 透传原功能、只做拖动）。
        if (padEditing) {
            val helpDrag = padDrag("Help")
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp * padScale).offset(x = helpDrag.first, y = helpDrag.second)) {
                Pad(
                    label = stringResource(R.string.ui_gamepad_btn_tip_help),
                    bit = 0, width = 40.dp, height = 40.dp, slot = "Help", scale = padScale,
                    editing = padEditing, configVersion = padConfigVersion,
                    onDown = {
                        showEditingTip = true
                        Feedback.haptic(padCtx)
                    },
                    onConfigure = {
                        showEditingTip = true
                        Feedback.haptic(padCtx)
                    },
                    onMoveBy = { dx, dy -> padMoveBy("Help", dx, dy) },
                    onMoveEnd = { padMoveEnd("Help") },
                )
            }
        }

        // 退出（issue#169 第 21 条：编辑态也可拖可配；普通态仍直接退出）
        // issue#170 #4：滑条拖动中的实时预览尺寸优先（padLiveSizes，存档口径）
        val exitDrag = padDrag("Exit")
        // 存档口径统一：滑条实时预览值优先，无预览按配置档；显示尺寸 = 存档 × padScale
        val exitSize = (padLiveSizes["Exit"]
            ?: remember(padConfigVersion) { GamepadPadConfig.sizeDp(padCtx, "Exit", 40) }).dp * padScale
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 12.dp * padScale + exitDrag.first, y = 8.dp * padScale + exitDrag.second)) {
            Pad(label = "✕", bit = 0, width = exitSize, height = exitSize, scale = padScale, onDown = onExit)
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
        val editSize = (padLiveSizes["Edit"]
            ?: remember(padConfigVersion) { GamepadPadConfig.sizeDp(padCtx, "Edit", 40) }).dp * padScale
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 60.dp * padScale + editDrag.first, y = 8.dp * padScale + editDrag.second)) {
            Pad(
                label = if (padEditing) "✓" else "✎", bit = 0, width = editSize, height = editSize, scale = padScale,
                onDown = {
                    padEditing = !padEditing
                    if (!padEditing) {
                        padConfiguring = null
                        // ✓ 收工即提交：快照丢弃（REQ 二期 5），退出编辑顺带散组（issue#169 第 20 条）、撤操作提示
                        padEditSnapshot = null
                        padGroupMode = false
                        padGroupSel = emptySet()
                        showEditingTip = false
                    } else {
                        // 进编辑态先全量快照（REQ 二期 5）：「撤销」退回这一刻
                        padEditSnapshot = GamepadPadConfig.snapshot(padCtx)
                        if (!GamepadPadConfig.tipShown(padCtx)) {
                            // 进编辑态首次自动弹操作说明（REQ 本批 4）：看过落盘，不再自动弹
                            GamepadPadConfig.markTipShown(padCtx)
                            showEditingTip = true
                        }
                    }
                    Feedback.haptic(padCtx)
                },
            )
            if (padEditing) {
                FuncDragOverlay(
                    onTap = {
                        padEditing = false
                        padConfiguring = null
                        padEditSnapshot = null   // ✓ 收工即提交：快照丢弃（REQ 二期 5）
                        padGroupMode = false
                        padGroupSel = emptySet()
                        showEditingTip = false
                        Feedback.haptic(padCtx)
                    },
                    onMoveBy = { dx, dy -> padMoveBy("Edit", dx, dy) },
                    onMoveEnd = { padMoveEnd("Edit") },
                )
            }
        }
        // 按钮组开关（issue#169 第 20 条）：「组」进多选模式 —— 编辑态点选槽位成组
        // （亮白描边），拖任意一颗整组平移、保持相对位置；再点一次散组。
        // 2026-11-13 遮挡批：改编辑态才出现（原常驻，顶部 (108,8) 正压 L2 药丸 40×31dp）；
        // 挪到左下角「？」上方——该角空着（双摇杆锚在 27%/73% 宽，普通键最靠左的 ▼
        // 左缘 = dpadAnchorX−29dp×scale ≥ 74dp×scale，都碰不到 12–52dp×scale 列）。
        if (padEditing) {
            val groupDrag = padDrag("Group")
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp * padScale, bottom = 58.dp * padScale).offset(x = groupDrag.first, y = groupDrag.second)) {
                Pad(
                    label = stringResource(if (padGroupMode) R.string.ui_gamepad_btn_group_on else R.string.ui_gamepad_btn_group),
                    bit = 0, width = 40.dp, height = 40.dp, slot = "Group", scale = padScale,
                    editing = padEditing, configVersion = padConfigVersion,
                    onDown = {
                        padGroupMode = !padGroupMode
                        if (!padGroupMode) padGroupSel = emptySet()
                        Feedback.haptic(padCtx)
                    },
                    onConfigure = {
                        // 编辑态 tap 透传原功能（切组模式）；拖动只做移位（issue#170 #6）
                        padGroupMode = !padGroupMode
                        if (!padGroupMode) padGroupSel = emptySet()
                        Feedback.haptic(padCtx)
                    },
                    onMoveBy = { dx, dy -> padMoveBy("Group", dx, dy) },
                    onMoveEnd = { padMoveEnd("Group") },
                )
            }
        }

        // —— 撤销（REQ 手柄编辑器 二期 5）：退回进编辑态（✎）那一刻的完整配置 ——
        // 兜住整个编辑会话的误操作：改键 / 拖位 / 滑尺寸 / 恢复默认都能一键还原。
        // 写回后同步内存 state：偏移重读、实时尺寸清空、配置卡关掉、版本号 bump 让
        // 所有按钮重读存档；快照保留（可多次撤销到同一基线，直到 ✓ 收工）。放「复位」上方同列。
        if (padEditing) {
            // 撤销动作（onDown / onConfigure 同款透传，与「复位」按钮口径一致）
            val undoAll = {
                padEditSnapshot?.let { snap ->
                    GamepadPadConfig.restore(padCtx, snap)
                    padOffsets = OFFSET_SLOTS.associateWith { GamepadPadConfig.offset(padCtx, it) }
                    padLiveSizes = emptyMap()
                    padConfiguring = null
                    padConfigVersion++
                }
                Feedback.haptic(padCtx)
            }
            val undoDrag = padDrag("Undo")
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp * padScale, bottom = 150.dp * padScale).offset(x = undoDrag.first, y = undoDrag.second)) {
                Pad(
                    label = stringResource(R.string.ui_gamepad_btn_undo),
                    bit = 0, width = 40.dp, height = 40.dp, slot = "Undo", scale = padScale,
                    editing = padEditing, configVersion = padConfigVersion,
                    onDown = { undoAll() },
                    onConfigure = { undoAll() },   // 编辑态 tap 透传原功能；拖动只做移位（issue#170 #6）
                    onMoveBy = { dx, dy -> padMoveBy("Undo", dx, dy) },
                    onMoveEnd = { padMoveEnd("Undo") },
                )
            }
        }

        // —— 复位（issue#170 #5 追修）：编辑态一键清全部槽位的拖动偏移 ——
        // 场景：手柄槽位配置随覆盖更新保留，旧布局/旧拖拽留下的偏移可能把按钮顶出
        // 屏幕外，出屏按钮点不到、开不了单槽「恢复位置」——只有全量入口救得回来。
        // 只清偏移不动键位/尺寸（误点无破坏性，拖坏了再拖回来即可），放「组」上方同列。
        if (padEditing) {
            val resetDrag = padDrag("ResetAll")
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp * padScale, bottom = 104.dp * padScale).offset(x = resetDrag.first, y = resetDrag.second)) {
                Pad(
                    label = stringResource(R.string.ui_gamepad_btn_reset_all),
                    bit = 0, width = 40.dp, height = 40.dp, slot = "ResetAll", scale = padScale,
                    editing = padEditing, configVersion = padConfigVersion,
                    onDown = {
                        GamepadPadConfig.resetAllOffsets(padCtx)
                        // 内存偏移 state 一并归零（GamepadPadConfig.resetAllOffsets 不管这块）
                        padOffsets = padOffsets + OFFSET_SLOTS.associateWith { 0f to 0f }
                        padConfigVersion++
                        Feedback.haptic(padCtx)
                    },
                    onConfigure = {
                        // 编辑态 tap 透传原功能（全量清偏移）；拖动只做移位（issue#170 #6）
                        GamepadPadConfig.resetAllOffsets(padCtx)
                        padOffsets = padOffsets + OFFSET_SLOTS.associateWith { 0f to 0f }
                        padConfigVersion++
                        Feedback.haptic(padCtx)
                    },
                    onMoveBy = { dx, dy -> padMoveBy("ResetAll", dx, dy) },
                    onMoveEnd = { padMoveEnd("ResetAll") },
                )
            }
        }

        // —— 扳机 L2 / R2（顶部第一排，真机在肩键后面，屏上画成药丸） ——
        val l2Drag = padDrag("L2")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = 104.dp * padScale + l2Drag.first, y = H * 0.02f + l2Drag.second)) {
            Pad(label = "L2", bit = GamepadController.BTN_L2, width = 68.dp, height = 32.dp, pill = true, slot = "L2", scale = padScale, editing = padEditing, selected = padGroupMode && "L2" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["L2"], onConfigure = { groupAwareTap("L2") },
                onMoveBy = { dx, dy -> groupAwareMove("L2", dx, dy) }, onMoveEnd = { groupAwareEnd("L2") })
        }
        val r2Drag = padDrag("R2")
        Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = (-104).dp * padScale + r2Drag.first, y = H * 0.02f + r2Drag.second)) {
            Pad(label = "R2", bit = GamepadController.BTN_R2, width = 68.dp, height = 32.dp, pill = true, slot = "R2", scale = padScale, editing = padEditing, selected = padGroupMode && "R2" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["R2"], onConfigure = { groupAwareTap("R2") },
                onMoveBy = { dx, dy -> groupAwareMove("R2", dx, dy) }, onMoveEnd = { groupAwareEnd("R2") })
        }

        // —— 肩键 L1 / R1（第二排，横向锚到十字/面键簇外缘） ——
        // 2026-11-13 遮挡批：短宽面板上簇顶（▲/△）与肩键同带，纵向错不开（高度不够，
        // 再往下压就撞摇杆），改横向错开——L1 左缘锚 dpadAnchorX + 34dp×scale（与 ▲ 右缘
        // 净距 5dp），配合 clusterCy 下移后与 ▲/▶ 均无交叠；R1 对称锚 faceAnchorX − 34dp×scale。
        val l1Drag = padDrag("L1")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadAnchorX(W, padScale) + 34.dp * padScale + l1Drag.first, y = H * 0.125f + l1Drag.second)) {
            Pad(label = "L1", bit = GamepadController.BTN_L1, width = 64.dp, slot = "L1", scale = padScale, editing = padEditing, selected = padGroupMode && "L1" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["L1"], onConfigure = { groupAwareTap("L1") },
                onMoveBy = { dx, dy -> groupAwareMove("L1", dx, dy) }, onMoveEnd = { groupAwareEnd("L1") })
        }
        val r1Drag = padDrag("R1")
        // TopEnd 对齐下 offset.x 以右缘起算：右缘 = faceAnchorX − 34dp×scale（与 △ 左缘净距 5dp）
        Box(modifier = Modifier.align(Alignment.TopEnd).offset(x = faceAnchorX(W, padScale) - W - 34.dp * padScale + r1Drag.first, y = H * 0.125f + r1Drag.second)) {
            Pad(label = "R1", bit = GamepadController.BTN_R1, width = 64.dp, slot = "R1", scale = padScale, editing = padEditing, selected = padGroupMode && "R1" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["R1"], onConfigure = { groupAwareTap("R1") },
                onMoveBy = { dx, dy -> groupAwareMove("R1", dx, dy) }, onMoveEnd = { groupAwareEnd("R1") })
        }

        // —— 十字键（左侧中部；此前缺失，占报告位 12..15，见 GamepadController 注释） ——
        // 簇内四键中心间距 = 键径（58dp×padScale），拼成正十字：Up 在上、Down 在下、Left/Right 在侧。
        // 锚点走钳制版（dpadAnchorX）：窄面板上 0.13W−87dp 会越出左缘（见函数注释）
        // 纵向锚点走 clusterCy：短宽面板下移让开 L1/R1 第二排（见函数注释）
        val dpadCx = dpadAnchorX(W, padScale)
        val dpadCy = clusterCy(H, padScale)
        val dUpDrag = padDrag("DUp")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 29.dp * padScale + dUpDrag.first, y = dpadCy - 87.dp * padScale + dUpDrag.second)) {
            Pad(label = "▲", bit = GamepadController.BTN_DUP, slot = "DUp", scale = padScale, editing = padEditing, selected = padGroupMode && "DUp" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["DUp"], onConfigure = { groupAwareTap("DUp") },
                onMoveBy = { dx, dy -> groupAwareMove("DUp", dx, dy) }, onMoveEnd = { groupAwareEnd("DUp") })
        }
        val dDownDrag = padDrag("DDown")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 29.dp * padScale + dDownDrag.first, y = dpadCy + 29.dp * padScale + dDownDrag.second)) {
            Pad(label = "▼", bit = GamepadController.BTN_DDOWN, slot = "DDown", scale = padScale, editing = padEditing, selected = padGroupMode && "DDown" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["DDown"], onConfigure = { groupAwareTap("DDown") },
                onMoveBy = { dx, dy -> groupAwareMove("DDown", dx, dy) }, onMoveEnd = { groupAwareEnd("DDown") })
        }
        val dLeftDrag = padDrag("DLeft")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx - 87.dp * padScale + dLeftDrag.first, y = dpadCy - 29.dp * padScale + dLeftDrag.second)) {
            Pad(label = "◀", bit = GamepadController.BTN_DLEFT, slot = "DLeft", scale = padScale, editing = padEditing, selected = padGroupMode && "DLeft" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["DLeft"], onConfigure = { groupAwareTap("DLeft") },
                onMoveBy = { dx, dy -> groupAwareMove("DLeft", dx, dy) }, onMoveEnd = { groupAwareEnd("DLeft") })
        }
        val dRightDrag = padDrag("DRight")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = dpadCx + 29.dp * padScale + dRightDrag.first, y = dpadCy - 29.dp * padScale + dRightDrag.second)) {
            Pad(label = "▶", bit = GamepadController.BTN_DRIGHT, slot = "DRight", scale = padScale, editing = padEditing, selected = padGroupMode && "DRight" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["DRight"], onConfigure = { groupAwareTap("DRight") },
                onMoveBy = { dx, dy -> groupAwareMove("DRight", dx, dy) }, onMoveEnd = { groupAwareEnd("DRight") })
        }

        // —— 面键 ABXY（右侧中部，菱形位：Y 上 / B 右 / A 下 / X 左）——
        // issue#170 #7：面键文字统一用 ABXY 字母，不再用 PlayStation 图形符号
        //（PS5 槽位布局保留，只换显示文字；Exit 功能键的 ✕ 是退出键，不属面键，不动）。
        // 键位选择器与编辑弹窗的同名显示在 GamepadPadConfig.KEY_CHOICES 同步改。
        // 锚点走钳制版（faceAnchorX）：窄面板上 0.87W+87dp 越出右缘，ABXY 整簇被裁掉
        // 纵向锚点走 clusterCy（与十字键簇同一锚，见函数注释）
        val faceCx = faceAnchorX(W, padScale)
        val faceCy = clusterCy(H, padScale)
        val yDrag = padDrag("Y")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 29.dp * padScale + yDrag.first, y = faceCy - 87.dp * padScale + yDrag.second)) {
            Pad(label = "Y", bit = GamepadController.BTN_Y, slot = "Y", scale = padScale, editing = padEditing, selected = padGroupMode && "Y" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["Y"], onConfigure = { groupAwareTap("Y") },
                onMoveBy = { dx, dy -> groupAwareMove("Y", dx, dy) }, onMoveEnd = { groupAwareEnd("Y") })
        }
        val bDrag = padDrag("B")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx + 29.dp * padScale + bDrag.first, y = faceCy - 29.dp * padScale + bDrag.second)) {
            Pad(label = "B", bit = GamepadController.BTN_B, slot = "B", scale = padScale, editing = padEditing, selected = padGroupMode && "B" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["B"], onConfigure = { groupAwareTap("B") },
                onMoveBy = { dx, dy -> groupAwareMove("B", dx, dy) }, onMoveEnd = { groupAwareEnd("B") })
        }
        val aDrag = padDrag("A")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 29.dp * padScale + aDrag.first, y = faceCy + 29.dp * padScale + aDrag.second)) {
            Pad(label = "A", bit = GamepadController.BTN_A, slot = "A", scale = padScale, editing = padEditing, selected = padGroupMode && "A" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["A"], onConfigure = { groupAwareTap("A") },
                onMoveBy = { dx, dy -> groupAwareMove("A", dx, dy) }, onMoveEnd = { groupAwareEnd("A") })
        }
        val xDrag = padDrag("X")
        Box(modifier = Modifier.align(Alignment.TopStart).offset(x = faceCx - 87.dp * padScale + xDrag.first, y = faceCy - 29.dp * padScale + xDrag.second)) {
            Pad(label = "X", bit = GamepadController.BTN_X, slot = "X", scale = padScale, editing = padEditing, selected = padGroupMode && "X" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["X"], onConfigure = { groupAwareTap("X") },
                onMoveBy = { dx, dy -> groupAwareMove("X", dx, dy) }, onMoveEnd = { groupAwareEnd("X") })
        }

        // —— Select / Start（顶部中轴两侧，PS5 的 Create/Options 位；issue#170 #7 文字随通用叫法） ——
        val selDrag = padDrag("Sel")
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(x = W * 0.40f - W / 2 + selDrag.first, y = H * 0.05f + selDrag.second)) {
            Pad(label = "Select", bit = GamepadController.BTN_SELECT, width = 76.dp, height = 30.dp, pill = true, slot = "Sel", scale = padScale, editing = padEditing, selected = padGroupMode && "Sel" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["Sel"], onConfigure = { groupAwareTap("Sel") },
                onMoveBy = { dx, dy -> groupAwareMove("Sel", dx, dy) }, onMoveEnd = { groupAwareEnd("Sel") })
        }
        val startDrag = padDrag("Start")
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(x = W * 0.60f - W / 2 + startDrag.first, y = H * 0.05f + startDrag.second)) {
            Pad(label = "Start", bit = GamepadController.BTN_START, width = 76.dp, height = 30.dp, pill = true, slot = "Start", scale = padScale, editing = padEditing, selected = padGroupMode && "Start" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["Start"], onConfigure = { groupAwareTap("Start") },
                onMoveBy = { dx, dy -> groupAwareMove("Start", dx, dy) }, onMoveEnd = { groupAwareEnd("Start") })
        }

        // —— 手柄截图（issue#170 #7：Select/Start 正中间一枚）：无线模式让受控 PC 抓自己
        // 屏幕存 PNG（游戏画面留影，不是截图手机本机）。USB HID / 蓝牙 HID 报告是标准
        // 手柄位图格式带不动自定义命令，那两条出口下按钮只弹提示。bit=0 + onDown 走
        // 功能键语义（不发 HID）；窄面板（W < 580dp）上 0.2W 中缝放不下 40dp 中缝键，
        // 会与两侧药丸贴边 —— 键随 padScale 缩，超窄时宁挤不挪位（两侧键位是回验过的口径）。
        // issue#170 #6：📷 加 Shot 槽位进编辑体系 —— 编辑态可拖可配（配置卡只动外观：
        // 尺寸滑条 + 恢复位置，没有键位行）、普通态保留按下抓屏语义（onDown 原样）。
        val shotDrag = padDrag("Shot")
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(x = shotDrag.first, y = H * 0.05f + shotDrag.second)) {
            Pad(label = "📷", bit = 0, width = 40.dp, height = 30.dp, pill = true, slot = "Shot", scale = padScale,
                editing = padEditing, selected = padGroupMode && "Shot" in padGroupSel,
                configVersion = padConfigVersion, liveSizeDp = padLiveSizes["Shot"],
                onDown = { gamepadShot(padCtx) },
                onConfigure = { groupAwareTap("Shot") },
                onMoveBy = { dx, dy -> groupAwareMove("Shot", dx, dy) }, onMoveEnd = { groupAwareEnd("Shot") })
        }

        // —— 双摇杆（底部中轴两侧；PlayStation 系摇杆不贴角） ——
        // 编辑态：上层盖一块透明拖拽面接管触摸（摇杆本体收不到事件），拖 = 移位落盘；
        // 普通态不盖章，摇杆照常吃输入。本体是纯 Compose 摇杆（见 Stick 注释）。
        // 摇杆横向锚点走钳制版 stickAnchorX（簇/摇杆相叠时向外让开 + 防越出左右缘，见函数注释）
        val lsCx = stickAnchorX(W, H, stickSize, padScale, left = true).coerceAtLeast(stickSize / 2 + 8.dp * padScale)
        val rsCx = stickAnchorX(W, H, stickSize, padScale, left = false).coerceAtMost(W - stickSize / 2 - 8.dp * padScale)
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

        // —— 配置卡（编辑态点按钮弹出）：行高 44dp 起好点按 ——
        // REQ 手柄编辑器 本批 1/2/3：键位行改 14 键网格点选；新增「恢复默认」行，
        // 一键回落键位/尺寸/偏移四维。
        // issue#170 #4（二轮）：尺寸行四档点选改「无级滑条 + 数值输入」——拖动实时跟手、
        // 松手落一次盘（padLiveSizes 分发预览）；捏边缩放整路下线，缩放只走这里。
        // issue#170 #7（二轮）：组模式选中 ≥2 颗时点选中的成员，弹一张组合卡
        // （标题「组合 · N 颗」）——尺寸同步落全部成员、键位行隐藏（各成员不同，
        // 显示只读提示防误改）、「恢复位置」逐成员 reset。
        AnimatedVisibility(
            visible = padConfiguring != null,
            modifier = Modifier.align(Alignment.Center),
        ) {
            val slot = padConfiguring ?: return@AnimatedVisibility
            // 读一次 padConfigVersion 订阅重组：点选落盘后 curBit/curSize 才会刷新
            val cfgVer = padConfigVersion
            // 键位网格展开态记忆在槽位上：换目标槽位自动收起，重开同一槽位也从头收起
            var keyPickerOpen by remember(slot) { mutableStateOf(false) }
            // 组合态：组模式下目标槽位在选中集且 ≥2 颗 —— 整卡换组合口径（散组即回单颗卡）
            val isGroup = padGroupMode && padGroupSel.size >= 2 && slot in padGroupSel
            // 尺寸落盘目标：组合 = 全体选中成员（摇杆不读尺寸配置，跳过）；单颗 = 当前槽位
            val sizeTargets = if (isGroup) padGroupSel - GamepadPadConfig.STICK_SLOTS.toSet() else setOf(slot)
            // issue#170 #5（三轮）：卡顶加拖动横条，整卡可拖离中心避开压住的按钮；
            // 偏移随槽位重开复位（换目标槽位即回正，重新弹同一槽位也回正，行为可预期）
            // issue#170 #5（四轮，报告人 6871267905 点名两条）：①顶部把手改
            // **一条与卡等宽的细横线**（原 ic_apx_menu 是三条短横，与菜单把手
            // 视觉混淆，也看不出「整卡可拖」）；②无级尺寸滑条**竖着放卡片右侧**，
            // 卡宽收窄 —— 横屏里卡不再占满整屏宽（此前各行 fillMaxWidth
            // 把卡撑到满宽）。上限 280dp×scale：键位网格 5×44dp + 4×8dp 间距
            // = 252dp×scale 是最宽行，280 上限刚好包住网格不被挤。
            var cardDrag by remember(slot) { mutableStateOf(Offset.Zero) }
            Column(
                modifier = Modifier
                    .offset { IntOffset(cardDrag.x.roundToInt(), cardDrag.y.roundToInt()) }
                    .background(colorResource(R.color.overlay_scrim), RoundedCornerShape(14.dp * padScale))
                    .border(1.dp, colorResource(R.color.overlay_pad_stroke_idle), RoundedCornerShape(14.dp * padScale))
                    .widthIn(max = 280.dp * padScale)
                    .padding(12.dp * padScale),
            ) {
                // 拖动把手：等宽细横线（四轮报告人点名）；五轮（issue#170）按反馈
                // 收窄到 2/3 卡宽并居中——等宽横线与卡片边缘「观感有点不协调」。
                // 按住拖动整卡，只吃把手手势，卡内其余行不受影响
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp * padScale)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, amount ->
                                    change.consume()
                                    cardDrag = Offset(cardDrag.x + amount.x, cardDrag.y + amount.y)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 视觉横线改 2/3 卡宽、水平居中（issue#170 第五轮 #2，报告人原话
                    // 「手柄编辑卡顶部把手横线调整为三分之二长度居中吧，观感有点不协调」）：
                    // 外层把手 Box 的 contentAlignment=Center 负责居中，横线本身收窄到 2/3；
                    // 拖动手势仍挂在整条 16dp 高的外层 Box 上（fillMaxWidth 不动），只有观感变。
                    Box(
                        Modifier
                            .fillMaxWidth(2f / 3f)
                            .height(3.dp * padScale)
                            .background(
                                colorResource(R.color.overlay_pad_text).copy(alpha = 0.65f),
                                RoundedCornerShape(1.5.dp * padScale),
                            ),
                    )
                }
                // —— 布局改双列（issue#170 #5 四轮，报告人 6871267905 点名）：
                // 左列 = 标题 / 键位 / 尺寸数值与输入 / 恢复 / 完成；
                // 无级尺寸滑条竖着放**卡片右侧**（原横滑条占一整行，卡被撑到满宽）。
                // —— 编辑卡各口径声明上提到 Row 作用域（issue#170 #5 四轮）：
                // 竖滑条块在左列 Column 之外，必须能读到这组量；
                // 本地无 SDK 编不过这层作用域，CI 实测抓出后统一上提。
                val cfgCtx = LocalContext.current
                val defaults = GamepadPadConfig.SLOT_DEFAULTS[slot]
                    ?: (GamepadController.BTN_A to 58)
                val curBit = GamepadPadConfig.bit(cfgCtx, slot, defaults.first)
                val curSize = GamepadPadConfig.sizeDp(cfgCtx, slot, defaults.second)
                // 滑条拖动中的实时预览值（存档口径）优先于存档档位
                val shownSize = padLiveSizes[slot] ?: curSize
                // issue#169 第 21 条：功能键（✕/✎/📷）没有键位可改，只出「尺寸」行与「恢复默认」——
                // 键位行对着 0 号位显示「?」反而让用户误以为坏了
                val isFunc = GamepadPadConfig.FUNC_SLOTS.contains(slot)
                // issue#169 第 18③ 条：摇杆（LStick/RStick）键位/尺寸两行同样无效——
                // Stick 布局不读 size 配置、SLOT_DEFAULTS 里也没有摇杆兜底，
                // 之前显示出「键位:A + 尺寸 58dp」的假配置，怎么点都没反应。整体隐藏。
                val isStick = GamepadPadConfig.STICK_SLOTS.contains(slot)
                // 无级范围（issue#170 #4）：圆键 32–96、药丸 40–120（下限取 40 覆盖 📷
                // 出厂宽 40；方案写的 56 会让 📷 默认值落在范围外，实施取 40，见汇报）。
                // 组合态取全体成员范围的交集，滑条只在公共合法区间内动。
                fun slotSizeRange(s: String): IntRange =
                    if (PAD_PILL_RATIO_H.containsKey(s)) 40..120 else 32..96
                val sizeMin = if (isGroup) sizeTargets.maxOfOrNull { slotSizeRange(it).first } ?: 32 else slotSizeRange(slot).first
                val sizeMax = if (isGroup) sizeTargets.minOfOrNull { slotSizeRange(it).last } ?: 96 else slotSizeRange(slot).last
                // 尺寸落盘（滑条松手 / 输入提交统一入口）：组合态逐颗 setSize + 版本号
                // 递增让全体成员同帧重读；清预览态回配置驱动。
                fun commitSize(v: Int) {
                    sizeTargets.forEach { GamepadPadConfig.setSize(cfgCtx, it, v) }
                    padLiveSizes = emptyMap()
                    padConfigVersion++
                    Feedback.haptic(cfgCtx)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        // issue#170 #8：配置卡排版统一 —— 各行文字居中、行距一致，行宽拉满
                        // 让点按热区对齐，不再左对齐参差；issue#170 #5（三轮）：行距随整卡
                        // 缩小一并收敛（12dp→8dp×scale），保证全部行在小屏竖屏也能完整显示。
                        Text(
                            text = if (isGroup) stringResource(R.string.ui_gamepad_cfg_group_title, padGroupSel.size)
                            else stringResource(R.string.ui_gamepad_cfg_title, slot),
                            fontSize = (16 * padScale).sp,
                            color = colorResource(R.color.overlay_pad_text),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (isGroup) {
                            // 成员键位只读提示（issue#170 #7）：各成员键位互不相同，列出防止误以为
                            // 能统一改；bit=0 的槽位（📷/✕/✎）没有键位名，回落显示槽位名本身
                            val memberKeys = padGroupSel.sorted().joinToString(" / ") { s ->
                                val d = GamepadPadConfig.SLOT_DEFAULTS[s]
                                val lbl = GamepadPadConfig.labelOf(GamepadPadConfig.bit(cfgCtx, s, d?.first ?: 0))
                                if (lbl == "?") s else lbl
                            }
                            Text(
                                text = stringResource(R.string.ui_gamepad_cfg_key_members, memberKeys),
                                fontSize = (15 * padScale).sp,
                                color = colorResource(R.color.overlay_pad_text),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp * padScale),
                            )
                        } else if (!isFunc && !isStick) {
                            // 键位行：点开 / 收起 14 键网格（点选即落盘，保持展开方便连改多颗）
                            Text(
                                text = stringResource(R.string.ui_gamepad_cfg_key, GamepadPadConfig.labelOf(curBit)),
                                fontSize = (15 * padScale).sp,
                                color = colorResource(R.color.overlay_pad_text),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp * padScale)
                                    .height(34.dp * padScale)
                                    .clickable {
                                        keyPickerOpen = !keyPickerOpen
                                        Feedback.haptic(cfgCtx)
                                    },
                            )
                            if (keyPickerOpen) {
                                PadKeyPicker(
                                    currentBit = curBit,
                                    scale = padScale,
                                    onPick = { newBit ->
                                        GamepadPadConfig.set(cfgCtx, slot, newBit, curSize)
                                        padConfigVersion++
                                        Feedback.haptic(cfgCtx)
                                    },
                                )
                            }
                        }
                        // 尺寸行：无级滑条 + 数值输入（issue#170 #4），实时跟手、松手/提交落盘；
                        // 摇杆尺寸不受配置控制整行隐藏（issue#169 第 18③ 条）；组合态全体
                        // 成员都不是摇杆才显示（全摇杆组没有可同步的尺寸）。
                        // issue#170 #6：显示沿用实际渲染口径 —— 存档值 × padScale 才是屏上真实尺寸。
                        if (!isStick && sizeTargets.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.ui_gamepad_cfg_size, "${(shownSize * padScale).roundToInt()}dp"),
                                fontSize = (15 * padScale).sp,
                                color = colorResource(R.color.overlay_pad_text),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp * padScale),
                            )
                            PadSizeInput(
                                value = shownSize,
                                minValue = sizeMin,
                                maxValue = sizeMax,
                                scale = padScale,
                                onCommit = { v -> commitSize(v) },
                            )
                        }
                        // 恢复默认（REQ 本批 3）：清键位/尺寸/偏移四键，不弹确认 ——
                        // 改动可再调回来，且编辑态本身不发光标。内存里的偏移 state 也要归零，
                        // 否则按钮不回到默认位（存档清了、画面还在原地）。
                        // 摇杆只有位置可恢复，文案区分（issue#169 第 18④ 条）；
                        // 组合态逐成员 reset（issue#170 #7，按钮文案沿用「恢复位置」）。
                        Text(
                            text = stringResource(
                                if (isGroup || isStick) R.string.ui_gamepad_cfg_reset_pos else R.string.ui_gamepad_cfg_reset,
                            ),
                            fontSize = (15 * padScale).sp,
                            color = colorResource(R.color.overlay_pad_text),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp * padScale)
                                .height(34.dp * padScale)
                                .clickable {
                                    if (isGroup) {
                                        padGroupSel.forEach { s -> GamepadPadConfig.reset(cfgCtx, s) }
                                        padOffsets = padOffsets + padGroupSel.associateWith { 0f to 0f }
                                    } else {
                                        GamepadPadConfig.reset(cfgCtx, slot)
                                        padOffsets = padOffsets + (slot to (0f to 0f))
                                    }
                                    padConfigVersion++
                                    Feedback.haptic(cfgCtx)
                                },
                        )
                        // 完成：收起配置卡，顺带收起键位网格展开态
                        Text(
                            text = stringResource(R.string.ui_gamepad_cfg_done),
                            fontSize = (15 * padScale).sp,
                            color = colorResource(R.color.overlay_pad_ok),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp * padScale)
                                .height(34.dp * padScale)
                                .clickable {
                                    keyPickerOpen = false
                                    padConfiguring = null
                                },
                        )
                    }
                    if (!isStick && sizeTargets.isNotEmpty()) {
                        // 竖滑条（报告人点名「竖着放卡片右侧」）：上大下小，
                        // 拖动全程预览、抬手落盘（口径与横滑条同一份实现）。
                        PadSizeSlider(
                            value = shownSize,
                            minValue = sizeMin,
                            maxValue = sizeMax,
                            scale = padScale,
                            onPreview = { v ->
                                // 拖动帧只写内存预览，不落盘（避免一次拖动写几十次 SharedPreferences）
                                padLiveSizes = sizeTargets.associateWith { v }
                            },
                            onCommit = { v -> commitSize(v) },
                            vertical = true,
                        )
                    }
                }
            }
            // cfgVer 仅作订阅占位，避免未使用告警：下方不再直接引用
            @Suppress("UNUSED_EXPRESSION")
            cfgVer
        }

        // —— 编辑操作说明弹层（issue#170 #7）：「？」重看 / 首次进编辑态都走这里 ——
        // 排在面板 Box 最后 = 绘制在最上层，居中显示、至多 3 行，不再被后画的按钮盖住
        //（原先排在顶部提示列里，顺序在按钮之前）。淡出计时沿用 showEditingTip 的 6 秒逻辑。
        AnimatedVisibility(
            visible = padEditing && showEditingTip,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = stringResource(R.string.ui_gamepad_editing_tip),
                fontSize = (11 * padScale).sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                color = colorResource(R.color.overlay_pad_ok),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .background(
                        colorResource(R.color.overlay_scrim).copy(alpha = 0.72f),
                        RoundedCornerShape(percent = 50),
                    )
                    .border(
                        1.dp,
                        colorResource(R.color.overlay_pad_stroke_idle),
                        RoundedCornerShape(percent = 50),
                    )
                    .padding(horizontal = 14.dp * padScale, vertical = 5.dp * padScale),
            )
        }
    }
}

/**
 * 手柄截图（issue#170 #7）：无线模式发 0x4C 让受控 PC 抓自己屏幕存 PNG（游戏画面留影，
 * 不是截图手机本机）。出口判定与提示 —— 未连/非无线出口提示走无线；PC 报过能力协商
 * 且缺 bit6（电脑端没升级）提示升级；发送成功气泡回执。USB HID / 蓝牙 HID 出口下
 * controlClient 为空，统一落「需无线」提示（HID 报告格式带不动自定义命令，属能力边界）。
 */
private fun gamepadShot(ctx: android.content.Context) {
    val cc = ControlTarget.controlClient
    if (cc == null || !cc.ready) {
        Toast.makeText(ctx, R.string.ui_gamepad_shot_need_wireless, Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(
        ctx,
        if (cc.gamepadShot()) R.string.ui_gamepad_shot_sent else R.string.ui_gamepad_shot_peer_old,
        Toast.LENGTH_SHORT,
    ).show()
}

/**
 * 14 键位选择网格（REQ 手柄编辑器 本批 1，借鉴 AntiMicroX 指派选择器的可发现性）：
 * 5 键一行的圆钮格子，当前键位用 [R.color.overlay_pad_ok] 描边高亮；点任一键即落盘
 * （onPick），卡不收起 —— 连改多颗不用重开。不引第三方布局库，纯 Row + chunked。
 */
@Composable
private fun PadKeyPicker(currentBit: Int, scale: Float, onPick: (Int) -> Unit) {
    Column(
        modifier = Modifier.padding(top = 8.dp * scale),
        verticalArrangement = Arrangement.spacedBy(8.dp * scale),
    ) {
        GamepadPadConfig.KEY_CHOICES.chunked(5).forEach { rowChoices ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp * scale)) {
                rowChoices.forEach { (choice, label) ->
                    val picked = choice == currentBit
                    Box(
                        modifier = Modifier
                            .size(44.dp * scale) // 44dp 起步的既有点按热区口径（平板档随 scale 放大）
                            .background(colorResource(R.color.overlay_pad_key_idle), CircleShape)
                            .border(
                                if (picked) 2.dp else 1.dp,
                                colorResource(if (picked) R.color.overlay_pad_ok else R.color.overlay_pad_stroke_idle),
                                CircleShape,
                            )
                            .clickable { onPick(choice) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = label, fontSize = (14 * scale).sp, color = colorResource(R.color.overlay_pad_text))
                    }
                }
            }
        }
    }
}

/**
 * 无级尺寸滑条（issue#170 #4）：配置卡「尺寸」行的拖动件，替掉四档点选条。
 *
 * 手绘实现（Canvas 轨道 + pointerInput 映射），不引滑条库 —— 本面板是 Service
 * overlay 宿主、无 Miuix 主题包裹，第三方/库组件的主题读取在这层不可靠；
 * 手势骨架与 [PadKeyPicker] 同一套项目自绘风格。
 *
 * 口径：值是存档 dp（显示尺寸由父层 × padScale 换算，与按钮同一套）；
 * 拖动全程只走 [onPreview]（父层写内存预览、按钮实时跟手），抬手才 [onCommit]
 * 落一次盘 —— 一次拖动只写一次 SharedPreferences。点轨道任意位置即跳值（步进 1dp，
 * 无档位）。
 *
 * @param vertical 竖放（issue#170 #5 四轮，报告人点名「调节条竖着放卡片右侧」）：
 *   轨道画成纵向、上端 = 大值（「调大」往上推，与直觉一致）；手势/预览/落盘
 *   语义与横放完全同源，只换映射轴与绘制方向。调用处给固定宽高，
 *   不再吃 fillMaxWidth（那是横放占整行的旧口径）。
 */
@Composable
private fun PadSizeSlider(
    value: Int,
    minValue: Int,
    maxValue: Int,
    scale: Float,
    onPreview: (Int) -> Unit = {},
    onCommit: (Int) -> Unit = {},
    vertical: Boolean = false,
) {
    val sliderCtx = LocalContext.current
    var trackW by remember { mutableIntStateOf(0) }
    // 回调经 rememberUpdatedState 桥进手势协程（issue#168 第 5 条教训：重组换 lambda
    // 不重启手势），value 同理 —— pointerInput 的 key 不放 value，拖动中重组不掐协程
    val currentValue by rememberUpdatedState(value)
    val currentPreview by rememberUpdatedState(onPreview)
    val currentCommit by rememberUpdatedState(onCommit)
    // 轨道两端留把手半径的净空：把手圆心活动范围 = [padPx, trackW − padPx]
    //（px 换算走 LocalDensity —— 组合函数不是 Density 作用域，不能直接 toPx）
    val padPx = with(LocalDensity.current) { 12.dp.toPx() }
    val trackIdle = colorResource(R.color.overlay_pad_stroke_idle)
    val trackFill = colorResource(R.color.overlay_pad_ok)
    val knobColor = colorResource(R.color.overlay_pad_text)
    Box(
        modifier = (if (vertical) {
            Modifier.width(44.dp * scale).height(150.dp * scale)
        } else {
            Modifier.fillMaxWidth().height(30.dp * scale)
        })
            .onSizeChanged { trackW = if (vertical) it.height else it.width }
            .pointerInput(minValue, maxValue, trackW, vertical) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    Feedback.haptic(sliderCtx)
                    var last = 0
                    fun apply(coord: Float, commit: Boolean) {
                        if (trackW <= 2 * padPx) return
                        // 竖放：上端 = 大值 —— 用「坐标到轨道底端的距离」当进度；
                        // 横放沿用左端 = 小值的原映射
                        val prog = if (vertical) (trackW - coord) else coord
                        val frac = ((prog - padPx) / (trackW - 2 * padPx)).coerceIn(0f, 1f)
                        val v = (minValue + frac * (maxValue - minValue)).roundToInt().coerceIn(minValue, maxValue)
                        last = v
                        if (commit) currentCommit(v) else currentPreview(v)
                    }
                    val downCoord = if (vertical) down.position.y else down.position.x
                    apply(downCoord, commit = false)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val mine = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!mine.pressed) break
                        mine.consume()
                        apply(if (vertical) mine.position.y else mine.position.x, commit = false)
                    }
                    // 抬手按最后一帧的值落盘（currentCommit 语义：一次拖动只写一次）
                    currentCommit(if (last in minValue..maxValue) last else currentValue)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackT = 3.dp.toPx()
            val frac = if (maxValue > minValue) (value - minValue).toFloat() / (maxValue - minValue) else 0f
            if (vertical) {
                val cx = size.width / 2
                val w = size.height - 2 * padPx
                val knobCy = padPx + w * (1f - frac)
                // 底轨 + 已选段（自下而上填充；加粗到与把手同高，视觉上「选中」有分量）
                drawRoundRect(trackIdle, topLeft = Offset(cx - trackT / 2, padPx), size = Size(trackT, w), cornerRadius = CornerRadius(trackT / 2))
                drawRoundRect(trackFill, topLeft = Offset(cx - trackT / 2, padPx + (w - maxOf(w * frac, trackT))), size = Size(trackT, maxOf(w * frac, trackT)), cornerRadius = CornerRadius(trackT / 2))
                // 把手：实心圆 + 中心点，与面板按钮配色同语言
                drawCircle(knobColor, radius = 10.dp.toPx(), center = Offset(cx, knobCy))
                drawCircle(trackIdle, radius = 3.dp.toPx(), center = Offset(cx, knobCy))
            } else {
                val cy = size.height / 2
                val w = size.width - 2 * padPx
                val knobCx = padPx + w * frac
                // 底轨 + 已选段（加粗到与把手同高，视觉上「选中」有分量）
                drawRoundRect(trackIdle, topLeft = Offset(padPx, cy - trackT / 2), size = Size(w, trackT), cornerRadius = CornerRadius(trackT / 2))
                drawRoundRect(trackFill, topLeft = Offset(padPx, cy - trackT / 2), size = Size(maxOf(w * frac, trackT), trackT), cornerRadius = CornerRadius(trackT / 2))
                // 把手：实心圆 + 中心点，与面板按钮配色同语言
                drawCircle(knobColor, radius = 10.dp.toPx(), center = Offset(knobCx, cy))
                drawCircle(trackIdle, radius = 3.dp.toPx(), center = Offset(knobCx, cy))
            }
        }
    }
}

/**
 * 尺寸数值输入（issue#170 #4）：滑条旁的精确改入口 —— 键入任意值、回车 / 失焦提交，
 * 非数字与越界钳回合法值。纯 foundation 的 [BasicTextField]（overlay 无 Miuix 主题
 * 包裹，显式给 TextStyle，不读库主题）。注：悬浮手柄宿主是 Service 窗口、大概率
 * 无 IME 焦点，此入口主要服务 Activity 全屏宿主；悬浮层以滑条为主。
 */
@Composable
private fun PadSizeInput(
    value: Int,
    minValue: Int,
    maxValue: Int,
    scale: Float,
    onCommit: (Int) -> Unit,
) {
    // 记在当前值上：滑条拖动 / 落盘后输入框跟着刷新；编辑中值不变不重置
    var text by remember(value) { mutableStateOf(value.toString()) }
    fun commit() {
        val parsed = text.toIntOrNull()
        if (parsed != null && parsed in minValue..maxValue && parsed != value) onCommit(parsed)
        else text = value.toString()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp * scale),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 输入框包一层定尺寸 Box 居中 —— BasicTextField 单独给定高度时文字顶排，
        // 包层 Box 走 contentAlignment 垂直居中，不与 height 叠 modifier 打架
        Box(
            modifier = Modifier
                .width(64.dp * scale)
                .height(30.dp * scale)
                .background(colorResource(R.color.overlay_pad_key_idle), RoundedCornerShape(8.dp * scale))
                .border(1.dp, colorResource(R.color.overlay_pad_stroke_idle), RoundedCornerShape(8.dp * scale)),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { s -> text = s.filter { it.isDigit() }.take(3) },
                textStyle = TextStyle(
                    color = colorResource(R.color.overlay_pad_text),
                    fontSize = (14 * scale).sp,
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                singleLine = true,
                modifier = Modifier
                    .width(56.dp * scale)
                    .onFocusChanged { if (!it.isFocused) commit() },
            )
        }
        Text(
            text = "dp",
            fontSize = (13 * scale).sp,
            color = colorResource(R.color.overlay_pad_text_dim),
            modifier = Modifier.padding(start = 6.dp * scale),
        )
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
 * 虚拟摇杆容器：本体是**纯 Compose 摇杆**（Canvas 画圆底 / 描边 / 摇杆头 +
 * pointerInput 手势），外面包一层负责「编辑态拖拽移位」。编辑态盖一块透明拖拽面
 * 接管触摸——上层先于下层收到事件且全部消费，摇杆本体收不到；普通态不盖，
 * 摇杆照常吃输入。编辑态另加 8% 白罩提示「这东西在编辑」。
 *
 * issue#169 #17 追评「双摇杆无法同时操控」根因：旧实现用 AndroidView 包 JoystickView
 * （View 内多指认领本身正确），但双指分落两颗摇杆时走 Android ViewGroup 原生分发 ——
 * ACTION_DOWN 命中的第一个子 View 认领整个手势，第二根手指的 POINTER_DOWN 不会派给
 * 另一颗摇杆（ViewGroup 默认不开 motionEventSplitting），双摇杆同操永远不通；
 * LS/RS 的接线本身没错。改纯 Compose 后，每颗摇杆只命中落进自己边界的那根指针
 *（Compose 按 pointer 独立命中派发），双摇杆天然可同操。
 *
 * 手势语义照搬旧 JoystickView：落下即按摇杆键（L3/R3，issue#169 第 17 条），
 * 拖出 touch slop 先松键再进轴值模式（两条语义互斥），抬手 / 取消兜底回中，
 * 不会把 L3/R3 卡住。轴值线性映射 [-127,127]，超出最大行程锁在圆周上。
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
    // 手势协程里直接换算成 GamepadController.setButton。
    buttonBit: Int = -1,
) {
    val ctx = LocalContext.current
    // tap / 轴值 / 按键回调经 rememberUpdatedState 桥进手势协程，避免重组重启拖拽手势
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnAxis by rememberUpdatedState(onAxis)
    val currentOnButton by rememberUpdatedState { down: Boolean ->
        if (buttonBit >= 0) GamepadController.setButton(buttonBit, down)
    }
    // 摇杆头相对圆心的偏移（px）：手势协程里更新，Canvas 里读取重画
    var knob by remember { mutableStateOf(Offset.Zero) }
    // 摇杆配色走语义令牌（外观门面关卡：不写死色值），重组时预取 Color 供画布用
    val stickBase = colorResource(R.color.overlay_stick_base)
    val stickRing = colorResource(R.color.overlay_stick_ring)
    val stickKnob = colorResource(R.color.overlay_stick_knob)
    Box(modifier = modifier.size(size)) {
        // —— 摇杆本体：圆底 + 描边 + 摇杆头（配色与旧 JoystickView 一致） ——
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(size)
                .alpha(if (editing) 0.5f else 1f)
                // 编辑态不加手势：由上层拖拽面接管，避免「挪摇杆」和「扳摇杆」同时发生
                .then(if (editing) Modifier else Modifier.pointerInput(Unit) {
                    // 形参 size 是 Dp，会遮蔽 PointerInputScope.size（IntSize）——
                    // 先显式取 this.size 存成 px 尺寸，手势里全部用它（CI android 编译红修复）
                    val px = this.size
                    awaitEachGesture {
                        // 认领第一根落进本摇杆的手指；已有手指在操作时新手指不接管
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var buttonDown = true
                        currentOnButton(true)
                        val center = Offset(px.width / 2f, px.height / 2f)
                        val maxR = (minOf(px.width, px.height).toFloat() / 2f - 8.dp.toPx()) * 0.58f
                        fun applyKnob(x: Float, y: Float) {
                            var dx = x - center.x
                            var dy = y - center.y
                            val dist = hypot(dx, dy)
                            if (dist > maxR && dist > 0f) {
                                dx = dx / dist * maxR
                                dy = dy / dist * maxR
                            }
                            knob = Offset(dx, dy)
                            currentOnAxis(((dx / maxR) * 127f).toInt(), ((dy / maxR) * 127f).toInt())
                        }
                        applyKnob(down.position.x, down.position.y)
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val ev = awaitPointerEvent()
                            // 只跟认领的那一根：其余手指的移动（按按钮 / 划屏）不影响摇杆
                            val mine = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!mine.pressed) break
                            mine.consume()
                            val x = mine.position.x
                            val y = mine.position.y
                            // 拖出 slop = 转入轴值操控：先松开摇杆键（两种语义互斥）
                            if (buttonDown &&
                                (abs(x - down.position.x) > slop || abs(y - down.position.y) > slop)
                            ) {
                                buttonDown = false
                                currentOnButton(false)
                            }
                            applyKnob(x, y)
                        }
                        // 认领手指抬离 / 取消：键先复位、轴值回中，两条状态线各自兜底
                        if (buttonDown) currentOnButton(false)
                        knob = Offset.Zero
                        currentOnAxis(0, 0)
                    }
                }),
        ) {
            val r = minOf(this.size.width, this.size.height) / 2f - 8.dp.toPx()
            drawCircle(stickBase, radius = r)
            drawCircle(stickRing, radius = r, style = Stroke(width = 2.dp.toPx()))
            drawCircle(stickKnob, radius = r * 0.42f, center = center + knob)
        }
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
 * [pill] = true 画药丸形（扳机 / Select / Start / 📷），否则圆形；issue#170 #4 起
 * 尺寸走无级滑条，sizeDp 语义 = **宽度**，高度按出厂宽高比随动（圆键宽高一致，
 * 比值恒 1），见 [PAD_PILL_RATIO_H]。
 *
 * 2026-11-13 平板布局批：[scale] = 面板缩放系数（[padPanelScale]）。显示尺寸 =
 * 配置存档值 × scale（同一份配置在手机与平板上观感一致、互不串档）。
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
    // 2026-11-13 平板布局批：显示缩放系数，手机档恒为 1
    scale: Float = 1f,
    // issue#169 第 20 条：组模式下被点选中的按钮 —— 亮白描边优先于编辑绿，提示「整组一起动」
    selected: Boolean = false,
    // issue#170 #4：滑条拖动中的实时预览尺寸（存档口径 dp），优先于存档档位；
    // null = 无预览、按配置档渲染。父层经 padLiveSizes 分发，松手落盘后清空。
    liveSizeDp: Int? = null,
    onDown: (() -> Unit)? = null,
    onConfigure: (() -> Unit)? = null,
    onMoveBy: ((Float, Float) -> Unit)? = null,
    onMoveEnd: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    // 键位 / 尺寸按配置版本重读：保存配置后父级状态变化触发重组，这里就拿到新值
    val effBit = if (slot == null) bit else GamepadPadConfig.bit(ctx, slot, bit)
    // 显示口径三档优先级：滑条实时预览 > 配置档（× scale 换显示）> 调用方给定宽
    //（✕/✎ 走这条，调用方已自行乘过 scale，不再叠乘 —— 口径与重画批一致）
    val effW = when {
        liveSizeDp != null -> liveSizeDp.dp * scale
        slot == null -> width
        else -> GamepadPadConfig.sizeDp(ctx, slot, width.value.toInt()).dp * scale
    }
    // 药丸（issue#170 #4）：高度 = 宽度 × 出厂宽高比 —— 此前药丸高度固定，滑条只加宽
    // 不拔高，比例越来越扁。圆键恒正方（L1/R1 只传 width、height 形参还是默认 58，
    // 若按它缩会被压成椭圆 —— 比例随动只属于药丸）。
    val effH = if (pill) effW * (height.value / width.value) else effW
    // bit=0 的槽位（📷 Shot）没有键位映射，labelOf 会落「?」—— 保留调用方给的原文案
    val effLabel = if (slot == null || effBit == 0) label else GamepadPadConfig.labelOf(effBit)
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
    // ⚠️ 命名必须是 pressScale：外层形参 scale 是面板缩放系数（平板档），此前本地
    // animateFloatAsState 同名遮蔽了形参 —— 捏边手势时代存档尺寸每捏一次悄悄缩 2%
    //（issue#169 #18② 同批修；捏边已随 issue#170 #4 下线，命名约定保留防复发）。
    val pressScale by animateFloatAsState(
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
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .size(effW, effH)
            .background(bg, shape)
            .border(
                width = when {   // 描边随 scale 放大，平板档与放大的键径保持同观感
                    selected -> 3.dp * scale       // 选中描边加粗一档，和普通按压区分开
                    pressed || editing -> 2.dp * scale
                    else -> 1.dp * scale
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
                        // —— 编辑态：不发 HID。拖 = 移位，tap = 配置。 ——
                        // issue#170 #4：捏边缘无级调大整路下线（边缘环热区与点按/拖移抢
                        // 手指、组合态几乎不可用，见 issue#170 #6/#7），缩放只走配置卡滑条，
                        // FuncDragOverlay 与这里只剩「拖心=移动、点按=配置」两个语义。
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
            // 字号随 scale 放大：平板档的大键配大字，键帽文字不再相对显小
            fontSize = if (effLabel.length > 2) (13 * scale).sp else (17 * scale).sp,
            color = colorResource(R.color.overlay_pad_text),
        )
    }
}
