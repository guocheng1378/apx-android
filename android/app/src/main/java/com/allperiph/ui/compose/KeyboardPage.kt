package com.allperiph.ui.compose

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.ui.Feedback
import com.allperiph.ui.KeyLayouts
import com.allperiph.ui.KeyPref
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.enterStagger
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import com.allperiph.ui.miuix.rememberEnterStagger
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 键盘页（Compose + Miuix）：一页两个面板，**与被控操控面（`TouchpadActivity`）键盘页同款布局**。
 *
 * 布局口径（用户要求「键盘布局应该和被控页的一样」）：
 * - 快捷 / 遥控 / 游戏 / 数字 / 九宫格 / 方向 / F 区 / 自定义，共 8 套，
 *   名称与顺序与操控面 `kbNames` 完全一致，两边的数据源也共用
 *   （`KeyPref.letterRows` + `KeyLayouts`），改一处两边同时变；
 * - 「快捷」= `HidKeys.MOD_LABELS` sticky 修饰排 + `KeyPref.letterRows` 五行（与 `quickKeyboard` 同款）；
 * - 「遥控」走 `HotkeyController` Consumer 位图（与 `remoteKeyboard` 同一条链路），
 *   关机 / 重启只在主屏保留（opcode 0x22 需要被控端 root，且有利于远程救机）；
 * - 右端的「全键盘」是同一套键盘的**弹窗形态**（旧 `KeyboardPanels` 就是弹窗，习惯保留），
 *   从任何页面一眼可开，键位与页签跟主体那块一模一样。
 *
 * 「快捷面板」是主屏自己的东西：`HotkeyStore` 里的组合键网格（单击发送 / 长按编辑 /
 * 新增 / 模板行），与被控操控面的 8 套键位互不影响。
 *
 * v223.24 修正在两处：
 * 1. 候选键 usage 全部**显式写出** —— 此前用 `usageOf("F11")` 这类**多字符标签**反查，
 *    而 `usageOf` 只认单字符，落到首字母的 usage（全屏发成 f、返回发成 b、音量发成 v），
 *    正是用户报的「快捷键发不对」；存量坏值由 [HidKeys.USAGE_REPAIRS] 在读取时修掉；
 * 2. 媒体键以**真实 Consumer usage** 落库，[HidKeys.combo] 统一路由到 HotkeyController
 *    位图（与操控面媒体键同一条链路）—— 否则 0xB2 会进键盘报告，对端认不出。
 *
 * 2026-11-09 界面稿 01/03/04（边距分档 + 限宽居中，与状态页 / 设置页同口径）：
 * <520dp 保留本页既有 12dp（键盘吃满宽度，稿 01 的 16dp 是内容页边距）；
 * 520–599dp → 24dp（稿 03）；≥600dp → 32dp（稿 01「16→24→32」）；
 * ≥840dp 内容限宽 680dp 居中（稿 04，超宽平板键帽不被拉成巨块）；
 * 左右另按实际 displayCutout 垫边（稿 02，横屏刘海侧按键不被遮）。
 */
@Composable
fun ApKeyboardPage(
    topBar: Dp = 0.dp,
    bottomGap: Dp = 0.dp,
) {
    // 页面标题与面板切换按钮一并去除（成宇 2026-11-02「键盘页不需要快捷键和虚拟键盘按钮，
    // 去除」）：这一页本来就是整块虚拟键盘，正文与按钮重复表达同一件事；
    // 「快捷键（长按可编辑）」那块组合键面板与模板行也整块拆掉 —— 模板/自定义编辑
    // 仍在设置页「快捷键与外设」里（同一份 HotkeyStore，能力没丢，只是不在键盘页占位）。
    // 页首只留 topBar 高度让开玻璃顶栏（顶栏本身也已无文字）。
    // —— 界面稿边距分档（2026-11-09）：按窗口实测宽度取，不读 configuration ——
    // 本壳 configChanges=orientation|screenSize，旋转不重建，配置回写滞后一拍
    // （v223.29.9 结论，与 TouchpadPage / StatusPage 同口径）。
    val adaptive = rememberApAdaptiveLayout()
    val widthDp = adaptive.width.value
    val shortSide = minOf(widthDp, adaptive.height.value)
    val hPad = when {
        shortSide >= 600f -> 32.dp   // 04 Medium+：32dp（稿 01「16→24→32」）
        shortSide >= 520f -> 24.dp   // 03 Compact：24dp
        else -> 12.dp              // 手机：保留本页既有 12dp，不改（稿 01 只管内容页 16）
    }
    val limited = false  // issue#168 第 2 条追补：平板横屏不再限宽 680 居中，撑满整屏（原稿 04 口径废止）
    // 2026-11-12 反馈「键盘没有撑满屏幕」（真机 17 Pro Max 横屏实测：左侧挖孔安全边
    // 约 75dp 全数垫给了键区，横屏键区比屏宽少约 11%）：整页键盘没有一粒文字紧贴屏缘，
    // 键位自身的间隙与圆角足以让开居中挖孔，「刘海侧按键不被遮」的稿 02 口径废止 ——
    // 左右只保留 hPad 基础边距，不再叠加挖孔 insets（竖屏挖孔在顶/底，本来就不进 left/right）。
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            // widthIn 在 fillMaxSize **外层**：先把 max 夹到 680，再填满夹后的约束；
            // 反过来写 fillMaxSize().widthIn(max=680) 夹不住（min 已被 fillMaxSize 钉死）。
            modifier = (if (limited) Modifier.widthIn(max = 680.dp) else Modifier)
                .fillMaxSize()
                .padding(start = hPad, end = hPad),
        ) {
            Spacer(modifier = Modifier.height(topBar))
            ApFullKeyboard(bottomGap = bottomGap)
        }
    }
}
/** 8 套页签：名称与顺序对齐操控面 `kbNames`（`TouchpadActivity`） */
private val KB_TABS = listOf(
    R.string.ui_kb_tab_quick, R.string.ui_kb_tab_remote, R.string.ui_kb_tab_game,
    R.string.ui_kb_tab_num, R.string.ui_kb_tab_grid9, R.string.ui_kb_tab_arrow,
    R.string.ui_kb_tab_fn, R.string.ui_kb_tab_custom,
)

/** 按下扩散（成宇 2026-11-08「灯是是按下扩散」）：真键盘 Reactive，
 * 平时键不亮，按下的键心抛一圈光向外跑，扫过哪里亮哪里（含字符）。
 * 纯状态容器：UI 线程写，KeyBox.touchLight 按 SystemClock 读，随相位逐帧重绘。
 */
private object TouchFx {
    class Wave(val x: Float, val y: Float, val t0: Long, val hue: Float)
    val waves = ArrayDeque<Wave>()
    var seq = 0
    const val LIFE_MS = 800L
    const val SPEED_PX = 1500f
    const val BAND_PX = 170f
    fun add(x: Float, y: Float) {
        waves += Wave(x, y, android.os.SystemClock.uptimeMillis(), (seq++ * 57f) % 360f)
        while (waves.size > 6) waves.removeFirst()
    }
}

/**
 * RGB 灯效规格：档位（[KeyPref.RGB_*]）+ 动画相位（0..360°，关档时 phase 为 null）。
 *
 * 相位以 `State` 形式下发而不是算好的颜色 —— 消费侧**全部走绘制阶段**：
 *   · 键帽底色 → `Modifier.drawBehind` 读 .value（只重绘）；
 *   · 字符色   → Text 的 `Modifier.graphicsLayer { colorFilter }` 读 .value（只重绘字层）。
 * 组合期只读 phase **引用**与 mode，不订阅 .value —— 开灯时整副键盘**不逐帧重组**
 * （皮肤审查 #3 已修：此前字色与玻璃 tint 在组合期取相位，84 键每帧重组，
 * 与本文档原先的「只重绘不重组」承诺相违）。
 * 关档时 phase 为 null，所有灯效分支短路，零开销。
 */
/**
 * 灯效调色（用户 2026-11-08「灯效太黑了，能改成调节颜色」）：色相偏移 0..359° +
 * 亮度增益 40..160%。全局单例 —— **只允许在绘制阶段读**（rgbFaceAt / touchLightAt
 * 内部）：调色 chip 按下触发的是键帽重绘，不是 84 键重组，与皮肤审查 #3 同一条红线。
 */
internal object RgbTune {
    var hueShift by mutableIntStateOf(0)
    var bright by mutableIntStateOf(100)
}

internal class RgbSpec(val mode: Int, val phase: State<Float>?)

/** 键盘树内的灯效上下文；没有 Provider 的调用方拿到关档默认值（等于没加这个功能） */
private val LocalRgb = staticCompositionLocalOf { RgbSpec(KeyPref.RGB_OFF, null) }
    // b34 材质动效相位（0..360°，6s 一圈）：亚克力流光 / 水晶星芒 / 木质缎面共用一条节奏；
    // 只在选中这三档时于 KeyboardPage 起 transition，其余时候下发静态 0f（零开销）。
    // 显式标 State<Float>：默认值 mutableStateOf(0f) 会把类型推成 MutableState<Float>，
    // 而 provides 的 matPhase 是 State<Float>（animateFloat 返回的就是 State），
    // 严格同型对不上 → Build #1422 ARGUMENT_TYPE_MISMATCH。读侧只 .value，无需可变。
    private val LocalMatPhase = staticCompositionLocalOf<State<Float>> { mutableStateOf(0f) }

/** HSV → Color（Compose 没有内置 hsv 构造，走 android.graphics.Color.HSVToColor） */
private fun hsvColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v)))

/**
 * 呼吸 / 波浪档的键面颜色（公式只此一份，键帽与遥控图块共用 —— 皮肤审查 #5：
 * 遥控页此前完全不吃灯效，两处各写一份必然分叉）。
 *
 * @param phase 相位 0..360°；null / 关档 → null（调用方走普通底色）
 * @param x 横坐标像素（列状错相；键帽与图块都取 boundsInWindow 左缘）
 * @param vScale 机械侧壁压暗系数（0.42 = 同色压暗；常规键面传 1）
 */
private fun rgbFaceAt(mode: Int, phase: Float?, x: Float, vScale: Float = 1f): Color? {
    if (phase == null || mode == KeyPref.RGB_OFF) return null
    val breathe = mode != KeyPref.RGB_WAVE
    // 呼吸亮度包络：相位每走 30° 一个 4s 明灭（p=0 灭、p=15 亮）；波浪恒亮
    val env = if (breathe) (0.5 - 0.5 * Math.cos(Math.toRadians(phase * 12.0))).toFloat() else 1f
    // b33：呼吸暗谷曾 0.30（成宇 2026-11-08「灯效太黑了」→ 提到 0.45 起步）；
    // 呼吸档按横坐标错相成彩虹。h/v 再叠调色（RgbTune：色相偏移 + 亮度增益，
    // 调色 chip 写、这里在绘制阶段读 —— 只重绘不重组）。
    val h = phase + x * (if (breathe) 0.35f else 0.6f) + RgbTune.hueShift
    val v = (if (breathe) 0.45f + 0.50f * env else 0.95f) * (RgbTune.bright / 100f)
    return hsvColor(h, 0.97f, (v * vScale).coerceAtLeast(0f))
}

/**
 * 扩散档（RGB_TOUCH）在坐标 [c]（窗口坐标）处的波光颜色；没有光带扫过 →
 * [Color.Transparent]。键帽（本键中心）与遥控图块共用同一份 [TouchFx] 遍历。
 * 不读相位 —— 调用方（绘制/字层）要自己读 `phase.value` 拿逐帧刷新。
 */
private fun touchLightAt(c: Offset): Color {
    val now = android.os.SystemClock.uptimeMillis()
    var best = 0f
    var hue = 0f
    for (w in TouchFx.waves) {
        val age = now - w.t0
        if (age > TouchFx.LIFE_MS) continue
        val r = age / 1000f * TouchFx.SPEED_PX
        val dx = c.x - w.x
        val dy = c.y - w.y
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        val i =
            (1f - kotlin.math.abs(d - r) / TouchFx.BAND_PX) *
                (1f - age.toFloat() / TouchFx.LIFE_MS.toFloat())
        if (i > best) {
            best = i
            hue = w.hue
        }
    }
    if (best <= 0f) return Color.Transparent
    // 扩散波光同样吃调色（色相偏移 + 亮度增益，绘制期读 —— 与键面同口径）
    val vv = (0.06f + 0.89f * best.coerceAtMost(1f)) * (RgbTune.bright / 100f)
    return hsvColor(hue + RgbTune.hueShift, 0.97f, vv.coerceIn(0f, 1f))
}

/** sticky 修饰位的当前快照（HidKeys 全局状态 Compose 感知不到，本地镜像驱动高亮） */
private fun stickySnapshot(): Int =
    HidKeys.MOD_LABELS.fold(0) { acc, (_, bit) ->
        if (HidKeys.isSticky(bit)) acc or bit else acc
    }

/** sticky 镜像翻转一次（toggle 之后调用） */
private fun flipSticky(sticky: Int, bit: Int): Int =
    if (HidKeys.isSticky(bit)) sticky or bit else sticky and bit.inv()

/**
 * 整块虚拟键盘：页签行 + 当前页签键位。
 *
 * @param onClose 非空时右上角画「关闭」—— 这是**悬浮球 overlay 面板形态**要的出口
 *   （overlay 键盘没有底部导航栏可退，不给关闭按钮就收不起来）；主屏键盘页传 null，
 *   页面上一个按钮都不画（成宇 2026-11-02「键盘页不需要快捷键和虚拟键盘按钮，去除」）。
 * @param bottomGap 底部额外让出的高度：浮动底栏（或悬浮键盘面板下沿）盖在键盘上，
 *   最底行的按键必须仍可点。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApFullKeyboard(
    onClose: (() -> Unit)? = null,
    bottomGap: Dp = 0.dp,
    /** 悬浮面板自适应覆盖（issue#168 返工「横竖屏切换布局回归」）：overlay 里
     *  windowInfo/configuration 不可信，由 FloatingBallService 用 realScreenSize 传入
     *  整屏实测值；主屏 Activity 调用不传（null）走原自适应判定。 */
    landscapeOverride: Boolean? = null,
    widthDpOverride: Float? = null,
    heightDpOverride: Float? = null,
) {
    val ctx = LocalContext.current
    val host = LocalView.current
    val dens = KeyPref.density(ctx)
    // 键帽材质档（成宇 2026-11-03「键盘再加一组液态玻璃按键的皮肤」）：此前只有一个
    // `mechanical: Boolean`，只有「平面 / 机械」两种可能，液态玻璃这一档没有表达力 ——
    // 改成传档位值，三种材质各自成一支，键帽渲染处不再靠布尔猜。
    val keycap = KeyPref.keycapStyleIndex(ctx)
    // 按键特效档（成宇 2026-11-03「键盘的特效做几个」）：涟漪 / 高光 / 弹跳三档 + 关闭。
    // 与材质正交（材质决定键帽长什么样，特效决定按下去画什么），实现收在 KeyFx.kt 一处。
    val fx = KeyPref.effectIndex(ctx)
    // RGB 灯效档（成宇 2026-11-08「能让键盘有rgb灯的效果吗」）：与材质 / 特效正交的第三档外观。
    // 只在这里起一条 InfiniteTransition，相位 State 经 [LocalRgb] 下发给键帽；
    // 关档（默认）连 transition 都不建（if 分支不进），不跑任何动画。
    // 存成 state 而不是 val：页签行尾的灯效芯片就地循环档位，改完当场重组灯效分支。
    var rgbMode by remember { mutableIntStateOf(KeyPref.rgbIndex(ctx)) }
    // 调色：组合期一次性从持久层灌入（此后 RgbTune 只被绘制阶段读）；面板开关
    remember { RgbTune.hueShift = KeyPref.rgbHue(ctx); RgbTune.bright = KeyPref.rgbBright(ctx) }
    var tuneOpen by remember { mutableStateOf(false) }
    // 灯效直选弹窗（2026-11-12 图1/图4「结构混乱」修复 + 规划 v2 口径「快切改一键直选
    // 不再循环」）：页签行的灯效芯片只负责开这个弹窗，四档灯效在弹窗里**直选**、
    // 不再点一下循环一档 —— 原先「RGB 灯效 · 按下扩散」长芯片把页签行顶成三行，
    // 换成两字短芯片后页签行自然收拢（8 个页签 + 灯效，多数屏两行内装下）。
    var ledPickOpen by remember { mutableStateOf(false) }
    val rgbSpec = if (rgbMode == KeyPref.RGB_OFF) {
        RgbSpec(rgbMode, null)
    } else {
        // 波浪 6s 一圈能看出「跑」；呼吸相位放慢到 48s 一轮彩虹 ——
        // KeyBox.rgbFace 从同一条相位取亮度包络（每 30° = 一次 4s 明灭，共 12 次）
        // 与色相慢漂，相位连续不回绕、换色不跳（成宇 b24「循环闪不行」）。
        val duration = if (rgbMode == KeyPref.RGB_WAVE) 6_000 else 48_000
        val t = rememberInfiniteTransition(label = "rgbPhase")
        RgbSpec(
            rgbMode,
            t.animateFloat(
                0f,
                360f,
                animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing)),
                label = "rgbPhaseValue",
            ),
        )
    }
    // b34 材质动效相位：亚克力 / 水晶 / 木质三档要「活」的质感 —— 流光带、星芒游动、
    // 缎面反光都从这条 6s 循环相位取节奏。只在这三档才起 transition（其余材质零开销），
    // 与灯效那条互不干扰（灯效关着材质照样动）。
    val matPhase: State<Float> =
        if (keycap == KeyPref.KEYCAP_ACRYLIC || keycap == KeyPref.KEYCAP_CRYSTAL || keycap == KeyPref.KEYCAP_WOOD) {
            rememberInfiniteTransition(label = "matPhase").animateFloat(
                0f,
                360f,
                animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing)),
                label = "matPhaseValue",
            )
        } else {
            remember { mutableStateOf(0f) }
        }
    var tab by remember { mutableIntStateOf(0) }
    // 自定义行表要在编辑弹窗保存后刷新
    var customRows by remember { mutableStateOf(KeyPref.customRows(ctx)) }
    val adaptive = rememberApAdaptiveLayout(landscapeOverride, widthDpOverride, heightDpOverride)
    // §6.3（ROADMAP 2026-11-11）：Expanded（≥840dp，平板）键高不无限拉伸 —— 键位区按
    // 6 行 × 80dp 封顶居中，余出的整块高度给底部工具行（剪贴板 / 语音 / 灯效）。
    // Compact / Medium（手机竖横屏）行为完全不变：键位区照旧 weight 占满，不画工具行。
    // 走窗口实测宽度（与 ApKeyboardPage 同源判定），不读 configuration（旋转回写滞后一拍）。
    // issue#168 返工（2026-11-12「横竖屏切换布局回归」）：手机横屏宽 ≈900dp ≥840 也被
    // 当成平板 —— 436dp 高的横屏里键位区 heightIn(480dp) 封顶，6 行键每行只剩 ≈48dp，
    // F 键两行标签直接被裁掉。Expanded 收窄为「宽 ≥840 且短边 ≥600」：真平板
    // （两个维度都够大）才收，手机横屏（短边 <600）恢复 weight 占满的 Compact 行为。
    // 竖屏键位区始终 weight 占满剩余高度（成宇 2026-11-12 19:31「所有键盘区不要手势控制面」
    // 触控板撤出后，不再有 1/3 / 2/3 配比）。
    val expanded = adaptive.width >= 840.dp && minOf(adaptive.width.value, adaptive.height.value) >= 600f
    // 竖屏键盘布局（成宇 2026-11-12 19:31「所有键盘区不要手势控制面」）：issue#168
    // 2026-11-12 #4 的「上 1/3 触控板 + 2 栏快捷键 + 键盘」竖屏三段方案整块废止 ——
    // 键盘页（主屏 / 悬浮键盘全形态）一律不再画触控板手势面，只留页签行 + 快捷键条 +
    // 键位区；触控板手势面只保留在触控板页 / 悬浮触控板面板 / 远程输入页。
    val portraitQuick = !adaptive.landscape
    // 语音工具键的识别结果：先存这里再弹确认卡（用户看清说了什么再发，避免口误直上电脑）
    var voiceText by remember { mutableStateOf<String?>(null) }
    // 剪贴板待发内容：同语音一个道理（2026-11-13 UI 审查），先弹确认卡亮出内容
    // 再发 —— 剪贴板里常是用户没看过全文的一大段字，点了立即直发属不可逆误触。
    var clipConfirm by remember { mutableStateOf<String?>(null) }
    // 识别结果回投（2026-11-11 成宇「语音按键链路不能用」）：主屏与悬浮键盘统一走
    // VoiceCaptureActivity 透明宿主页 —— 语音识别必须有 Activity 宿主，悬浮键盘
    // （Service overlay）没有，旧实现在悬浮键盘里点了只弹「要在主屏使用」，链路断了。
    // 结果经伴生回调落回本面板的确认卡；DisposableEffect 挂 / 摘，
    // 防 Service 面板销毁重建后旧回调滞留（接住已被新面板取代的写入）。
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.allperiph.ui.VoiceCaptureActivity.onResult = { t ->
            voiceText = t?.takeIf { it.isNotBlank() }
            if (voiceText == null) {
                android.widget.Toast.makeText(
                    ctx, R.string.ui_kb_voice_none, android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        onDispose { com.allperiph.ui.VoiceCaptureActivity.onResult = null }
    }

    CompositionLocalProvider(LocalRgb provides rgbSpec, LocalMatPhase provides matPhase) {
        // 横屏底部避让已撤（2026-11-12 随工具行并入页签行）：原先横屏把 insets.bottom
        // 叠进底部垫边，是给贴底的工具行让手势条 —— 工具行不在底部了，让位一并撤回，
        // 键位区拿回整块高度；竖屏口径不变（bottomGap 已够）。
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(
                    start = 8.dp, end = 8.dp, top = 2.dp,
                    bottom = 8.dp + bottomGap,
                ),
        ) {
            // —— 关闭行：只在悬浮球面板形态画（主屏键盘页 onClose = null，一个按钮都不占位）——
            if (onClose != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ApComponents.NarrowButton(
                        text = ctx.getString(R.string.ui_kb_close),
                        onClick = { onClose.invoke() },
                    )
                }
            }

            // —— 第 1 行：页签（与操控面 kbTabs 同名同序）——
            // 2026-11-04 改走 [ApComponents.TabChip]：此前用 `Pill + apKeyFeel + apPress` 自己拼
            // 选中态，而 Kit 里的 TabChip 正是为「有选中态的芯片」造的（selected → 主色胶囊底 +
            // SemiBold 字重）。一并修掉两个问题：
            //  ① `Pill` 只有 `padding(10,4)`，**没有 44dp 命中下限** —— 页签是手指目标，
            //    在 400dp 宽的手机上偏小（TabChip 有 heightIn(min = 44.dp)）；
            //  ② 手拼那套与 TabChip 并存 = 又一份重复实现，改选中态样式会只改一边。
            // 震动 / 音效仍由本处负责：TabChip 内部只有 clickable（无 apKeyFeel 的按下震），
            // 所以 onClick 里给一次 [Feedback.tap]（震 + 声）—— 与 ActionButton / NarrowButton
            // 同口径。此前用的 [Feedback.release] 只出声不震（那是给「已有按下震」的键帽
            // 准备的），页签成了全页手感最弱的可点面（皮肤审查 #2）。
            //
            // issue#168（2026-11-11 手机竖屏清单）：页签行从「横向滚动」改 **FlowRow 自动换行** ——
            // 8 个页签 + 灯效芯片在 400dp 宽的竖屏里装不下一行，横滑要「先划再点」两步；
            // 换行后全部芯片一屏可见、直接点。宽屏一行装得下时 FlowRow 自然仍是单行，
            // 横屏观感不变。
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KB_TABS.forEachIndexed { i, res ->
                    ApComponents.TabChip(
                        text = ctx.getString(res),
                        selected = tab == i,
                        onClick = {
                            Feedback.tap(host)
                            tab = i
                        },
                        fx = fx,
                    )
                }
                // 工具分组（成宇 2026-11-12「进页签里不再单独用，作为页签的一个分组」）：
                // 剪贴板/语音/灯效从独立工具行并入页签行，作页签行尾部的**一组** ——
                // 组前加一段加宽间隔与页面页签区隔开，不再单独占一行（横屏底部整行
                // 只有三个小圆块的松散布局由此收掉）。灯效芯片仍在组尾：只开
                // [ledPickOpen] 弹窗直选（规划 v2 口径），「调色」在弹窗里给。
                // 2026-11-13 UI 审查：8 个页面页签与 3 个动作芯片同为 TabChip 混排，
                // 分不清哪段是「换页」哪段是「动作」—— 间隔里补一根细竖线，动作组
                // 一眼可辨（竖线只是装饰，不加任何点击语义）。
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(
                            MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.35f),
                            RoundedCornerShape(0.5.dp),
                        ),
                )
                Spacer(Modifier.width(6.dp))
                ApComponents.TabChip(
                    text = ctx.getString(R.string.ui_ball_fn_clipboard),
                    selected = false,
                    onClick = {
                        Feedback.tap(host)
                        // 预取 + 确认（不可逆直发改确认卡，见 clipConfirm 注）
                        fetchClipboardForPc(ctx)?.let { clipConfirm = it }
                    },
                    fx = fx,
                )
                ApComponents.TabChip(
                    text = ctx.getString(R.string.ui_kb_tool_voice),
                    selected = false,
                    onClick = {
                        Feedback.tap(host)
                        // 统一走透明宿主页拉系统识别（2026-11-11「语音按键链路不能用」）：
                        // 悬浮键盘没有 Activity 宿主，主屏同样可用 —— 识别器挑选、
                        // Google-only 提示都在 VoiceCaptureActivity 内（同 v223.29.33 口径），
                        // 结果经伴生回调回到本面板的确认卡。
                        com.allperiph.ui.VoiceCaptureActivity.start(ctx)
                    },
                    fx = fx,
                )
                // 灯效入口（成宇 2026-11-08「能让键盘有rgb灯的效果吗」；2026-11-12 按规划
                // v2 口径「快切改一键直选不再循环」改造）：芯片只开 [ledPickOpen] 弹窗，
                // 四档灯效在弹窗里直选。文案用现成的两字资源 ui_kb_tool_rgb，不再拼
                // 「ui_set_label_rgb · 档位名」长串 —— 长串正是 2026-11-12 图1 竖屏页签行
                // 折成三行的元凶。选中态 = 开灯；当前档位在弹窗里高亮。
                ApComponents.TabChip(
                    text = ctx.getString(R.string.ui_kb_tool_rgb),
                    selected = rgbMode != KeyPref.RGB_OFF,
                    onClick = {
                        Feedback.tap(host)
                        ledPickOpen = true
                    },
                    fx = fx,
                )
                // 「调色」芯片从页签行撤下（2026-11-12 图1 第三行孤行）：入口挪进
                // 灯效直选弹窗（灯效开着才给），页签行不再为它单独占一行。
            }

            // —— 调色面板：色相名直选 + 亮度三档（灯效开着且点了「调色」才展开；横滑防溢出）
            if (tuneOpen && rgbMode != KeyPref.RGB_OFF) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "原" to 0, "红" to 340, "橙" to 30, "黄" to 55,
                        "绿" to 140, "青" to 190, "蓝" to 240, "紫" to 285,
                    ).forEach { (label, hue) ->
                        ApComponents.TabChip(
                            text = label,
                            selected = RgbTune.hueShift == hue,
                            onClick = {
                                Feedback.tap(host)
                                RgbTune.hueShift = hue
                                KeyPref.setRgbHue(ctx, hue)
                            },
                            fx = fx,
                        )
                    }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    listOf("暗" to 60, "中" to 100, "亮" to 140).forEach { (label, b) ->
                        ApComponents.TabChip(
                            text = label,
                            selected = RgbTune.bright == b,
                            onClick = {
                                Feedback.tap(host)
                                RgbTune.bright = b
                                KeyPref.setRgbBright(ctx, b)
                            },
                            fx = fx,
                        )
                    }
                }
            }

            // —— 竖屏快捷键条（成宇 2026-11-12 19:31「所有键盘区不要手势控制面」）：
            // issue#168 2026-11-12 #4 三段方案里的触控板整块删掉，键位区拿回全部剩余
            // 高度；横屏不画（页签行 + 键位区吃满）。样式与自适应与触控板页同一套
            //（19:38「两端同步样式同步」，见 PortraitQuickStrip 注）。
            if (portraitQuick) {
                PortraitQuickStrip()
            }

            // —— 键位区：weight 占满剩余高度（触控板已从键盘区整块撤出），行内均分 ——
            // §6.3：Expanded 时键位区 heightIn 封顶（480dp ≈ 6 行 × 80dp），外层 Box 垂直居中，
            // 超出封顶的高度留在键位区下方，由下面的工具行占住 —— 键帽不再被拉成巨块。
            // issue#169 建议 3：pad 横屏时键帽不足整屏高，居中会让页签行与键位区隔出
            // 一大段空白（「模板栏离按键区太远」）。横屏改顶对齐 —— 键位区上沿贴住
            // 页签行，模板行与按键区视觉相邻；竖屏口径不变（仍居中）。
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = if (landscapeOverride == true || (landscapeOverride == null && adaptive.landscape)) {
                    // 顶对齐（pad 横屏「模板栏贴按键区」）。注意 contentAlignment
                    // 参数是完整 Alignment：Top/CenterVertically 是 Vertical（垂直
                    // 分量），喂进去类型对不上 —— 上一版用 Alignment.Top 正是
                    // CI 编译红的根因之一；顶对齐的水平中置语义 = TopCenter。
                    Alignment.TopCenter
                } else {
                    Alignment.Center
                },
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (expanded) Modifier.heightIn(max = 480.dp) else Modifier),
                ) {
                    when (tab) {
                        0 -> QuickLayout(dens, keycap, fx, landscapeOverride, widthDpOverride, heightDpOverride)
                        1 -> RemoteLayout()
                        2 -> GameLayout(dens, keycap, fx)
                        3 -> KeyGridLayout(KeyLayouts.NUMPAD, dens, keycap, fx)
                        4 -> KeyGridLayout(KeyLayouts.GRID9, dens, keycap, fx)
                        5 -> KeyGridLayout(KeyLayouts.ARROWS, dens, keycap, fx)
                        6 -> KeyGridLayout(KeyLayouts.FN, dens, keycap, fx)
                        else -> CustomLayout(customRows, dens, keycap, fx) {
                            customRows = KeyPref.customRows(ctx)
                        }
                    }
                }
            }
            // —— 工具行（剪贴板/语音/灯效）已撤（成宇 2026-11-12「进页签里不再单独用，
            // 作为页签的一个分组」）：三键并入上方页签行尾部分组，横屏底部不再单独占行
            // —— 原先整行只有三个居中小圆块、两侧大片留白的松散布局由此收掉。
        }
    }

    // —— 灯效直选弹窗（规划 v2「快切改一键直选不再循环」）：四档点谁亮谁，就地生效 ——
    // 点档位不关弹窗：用户常想连着试几档看观感，收尾点弹窗外任意处（onDismiss）；
    // 「调色」项只在灯效开着时出现，点了关弹窗、展开下方色相/亮度面板（tuneOpen 语义不变）。
    if (ledPickOpen) {
        ApComponents.DialogCard(onDismiss = { ledPickOpen = false }) {
            Text(
                text = ctx.getString(R.string.ui_set_label_rgb),
                fontSize = 16.sp,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KeyPref.rgbLabels(ctx).forEachIndexed { i, name ->
                    ApComponents.TabChip(
                        text = name,
                        selected = rgbMode == i,
                        onClick = {
                            Feedback.tap(host)
                            rgbMode = i
                            KeyPref.setRgb(ctx, i)
                        },
                        fx = fx,
                    )
                }
            }
            if (rgbMode != KeyPref.RGB_OFF) {
                ApComponents.TabChip(
                    text = ctx.getString(R.string.ui_kb_led_tune),
                    selected = tuneOpen,
                    onClick = {
                        Feedback.tap(host)
                        tuneOpen = !tuneOpen
                        ledPickOpen = false
                    },
                    fx = fx,
                )
            }
        }
    }

    // —— 语音识别结果确认卡：说了什么、发不发，用户看得见再决定 ——
    voiceText?.let { t ->
        ApComponents.DialogCard(onDismiss = { voiceText = null }) {
            Text(text = ctx.getString(R.string.ui_kb_voice_result_title), fontSize = 16.sp)
            // 成宇 2026-11-13 04:0x「不能修改，没有识别窗」：识别结果原来是死的 Text，
            // 离线小模型听错字很常见，用户只能取消重来。改用可编辑输入框，默认填上
            // 识别结果，发送前可就地增删；remember(t) 按结果重建 —— 取消后再识别出
            // 新结果，输入框内容跟着换，不残留上一轮的字。
            val editState = remember(t) {
                androidx.compose.foundation.text.input.TextFieldState(t)
            }
            top.yukonga.miuix.kmp.basic.TextField(
                state = editState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_common_cancel),
                    onClick = { voiceText = null },
                )
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_voice_send),
                    onClick = {
                        // 发送取编辑后的文本（用户改过什么发什么），不再是识别原样
                        val text = editState.text.toString()
                        val client = com.allperiph.wireless.ControlTarget.controlClient
                        val ok = client != null && client.ready &&
                            runCatching { client.sendClipboard(text) }.getOrDefault(false)
                        android.widget.Toast.makeText(
                            ctx,
                            if (ok) R.string.ui_kb_voice_sent else R.string.ui_ball_fn_clipboard_nolink,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        voiceText = null
                    },
                )
            }
        }
    }

    // —— 剪贴板发送确认卡：内容先亮出来再发（2026-11-13 UI 审查，样式与语音卡同构）——
    clipConfirm?.let { t ->
        ApComponents.DialogCard(onDismiss = { clipConfirm = null }) {
            Text(text = ctx.getString(R.string.ui_ball_fn_clipboard_send), fontSize = 16.sp)
            // 预览只亮 4 行出省略号 —— 剪贴板可能是一大段长文，全亮会撑爆卡片；
            // 发出去的仍是完整内容，不因预览截断。
            Text(
                text = t,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_common_cancel),
                    onClick = { clipConfirm = null },
                )
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_voice_send),
                    onClick = {
                        sendClipboardTextToPc(ctx, t)
                        clipConfirm = null
                    },
                )
            }
        }
    }
}

/**
 * 竖屏键盘页的快捷键条（issue#168 返工 2026-11-11「要触控板页常用快捷键栏样式、
 * 按钮限 2 行，不是加两个标题行」；2026-11-12 19:31「所有键盘区不要手势控制面」后
 * 成为竖屏键盘页唯一的非键盘区块）。
 *
 * 样式与自适应已与触控板页快捷键栏**同一套**（成宇 2026-11-12 19:38「触摸板页的
 * 快捷键自适应，和键盘页快捷键自适应，两端同步样式同步」）：打包走触控板页的
 * [packBySlots]（每 2 字占 1 位、一行 3 位、装不下折行），芯片走同一个 [HotkeyChip]
 * （TabChip + HOTKEY 皮配色 + 按键特效 + 点击直发）—— 此前键盘页自画的等宽
 * QuickChip（每行 4 枚、4 列等宽截长文案）整块删除，改一处两边同时变。
 *
 * 数据源：快捷键 = [HotkeyStore.load]（与触控板页同一份 HotkeyStore）；自动排序
 * 开启时按使用频次排（与 HotkeyBoard.render 同口径）；存档清空回落模板/出厂行
 * （只回显不落库）。上限 8 枚 —— 装不下的快捷键去触控板页用（卡内可滚动、可增删调序）。
 */
@Composable
private fun PortraitQuickStrip() {
    val ctx = LocalContext.current
    val hotkeys = remember {
        val loaded = com.allperiph.hid.HotkeyStore.load(ctx)
        val base = if (loaded.isNotEmpty()) loaded else {
            // 2026-11-12 用户反馈「竖屏中间空了一大块 / 快捷键没有跟快捷模版」：
            // 存档清空后中段只剩一行空态小字、整块留白。改为跟模板走 —— 套过
            // 模板用模板键位（与触控板页同一份存档），没套用出厂行；只回显不
            // 落库，触控板页的清空状态不受影响。
            val tpl = com.allperiph.hid.HotkeyTemplates.byKey(
                com.allperiph.hid.HotkeyStore.loadTemplate(ctx)
            )
            (tpl?.combos ?: HidKeys.DEFAULTS).toMutableList()
        }
        if (com.allperiph.hid.HotkeyStore.isAutoSort(ctx)) {
            val usage = com.allperiph.hid.HotkeyStore.loadUsage(ctx)
            base.sortedByDescending { usage[com.allperiph.hid.HotkeyStore.comboKey(it)] ?: 0 }
        } else {
            base
        }
    }
    // 每行 3 位（SLOTS_PER_ROW，与触控板页同口径）、上限 8 枚折 2–3 排；行尾补空位
    // 占位保持网格稳定（与 HotkeyGrid 的补空格同思路）。
    val rows = packBySlots(hotkeys.take(8))
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 错落入场（2026-11-12 第三批）：芯片排逐行亮起，动画只动 graphicsLayer 不占位跳动
        rows.forEachIndexed { ri, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .enterStagger(rememberEnterStagger(ri)),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { (index, slots) ->
                    HotkeyChip(
                        ctx = ctx,
                        combo = hotkeys[index],
                        modifier = Modifier.weight(slots.toFloat()),
                    )
                }
                repeat(SLOTS_PER_ROW - row.sumOf { it.second }) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 快捷键盘：sticky 修饰排 + `KeyPref.letterRows` 五行 —— 与操控面 `quickKeyboard()` 同款 */
@Composable
private fun QuickLayout(
    dens: KeyPref.Density,
    keycap: Int,
    fx: Int,
    /** 与 [ApFullKeyboard] 同源的自适应覆盖：悬浮键盘里 QuickLayout 自行读一次自适应，
     *  也必须用整屏实测值，否则「横屏画竖屏排」在这层复发（v223.29.61）。 */
    landscapeOverride: Boolean? = null,
    widthDpOverride: Float? = null,
    heightDpOverride: Float? = null,
) {
    val ctx = LocalContext.current
    val rows = KeyPref.letterRows(ctx)
    // b17 方案 C：横屏切真键盘（15u 行制），竖屏保持 letterRows 紧凑排
    // 横屏判定走窗口实测（v223.29.8 同源修法）：本壳 Activity 声明了 configChanges，
    // 旋转不重建，`LocalConfiguration.orientation` 回写滞后一拍 —— 已经横着拿键盘
    // 还停在竖屏排，FULL 真键盘就像「没了」。containerSize 与布局测量同源，永不滞后。
    val wide = rememberApAdaptiveLayout(landscapeOverride, widthDpOverride, heightDpOverride).landscape
    var sticky by remember { mutableIntStateOf(stickySnapshot()) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 修饰键排（Ctrl / Alt / Shift / Win sticky，与操控面同一份 MOD_LABELS）
        // b19「ctrl 那一排不要了，下面有」：横屏不画 sticky 修饰排（FULL 底排自带 Ctrl/Alt/Shift），竖屏保留
        // 错落入场（2026-11-12 第三批）：键排按序号依次落位（动画只动 graphicsLayer）
        if (!wide) Row(modifier = Modifier.weight(1f).fillMaxWidth().enterStagger(rememberEnterStagger(0))) {
            HidKeys.MOD_LABELS.forEach { (text, bit) ->
                ModKey(
                    label = text,
                    active = sticky and bit != 0,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    dens = dens,
                    keycap = keycap,
                    fx = fx,
                ) {
                    HidKeys.toggleSticky(bit)
                    sticky = flipSticky(sticky, bit)
                }
            }
        }
        // 数字 / 字母三行 / 功能行（Esc Tab Space Enter ⌫ Del）全随 KeyPref 行表走
        if (wide) {
            // b17 方案 C：横屏按现实真键盘排（15u 行制变宽）；weight(6f) 对齐竖屏 6 行比例
            Box(modifier = Modifier.weight(6f).fillMaxWidth()) {
                KeyGridLayout(com.allperiph.ui.KeyLayouts.FULL, dens, keycap, fx)
            }
        } else rows.forEachIndexed { ri, labels ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth().enterStagger(rememberEnterStagger(ri))) {
                labels.forEach { label ->
                    KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                        HidKeys.tapLabel(label)
                        sticky = 0 // tap 自动清 sticky，同步燄灭修饰键（与操控面 refreshMods 同口径）
                    }
                }
            }
        }
    }
}

/**
 * 遥控键盘：媒体 / 音量（Consumer 位图，与操控面 `remoteKeyboard` 同一条 HotkeyController 链路）
 * + 电源行。
 *
 * 键位与文案走 [KeyLayouts.REMOTE_ROWS] 共享表（审计批3 2026-11-12：原先两边各写一份，靠注释
 * 互相提醒对齐，历史上就分叉过——操控面一度找不到关机键）。本页保留自己的灯效图块渲染与
 * press/release 60ms 交互；关机 / 重启的确认弹窗与操控面 confirmPower 同语义（opcode 0x22，
 * 需被控端 root），主屏保留这两键是为了「对端卡死时还能远程救」。
 */
@Composable
private fun RemoteLayout() {
    val ctx = LocalContext.current
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var confirm by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val hk = com.allperiph.ui.HotkeyController
    Column(modifier = Modifier.fillMaxSize()) {
        // 错落入场（2026-11-12 第三批）：媒体/电源行依次落位（动画只动 graphicsLayer）
        KeyLayouts.REMOTE_ROWS.forEachIndexed { ri, defs ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).enterStagger(rememberEnterStagger(ri))) {
                defs.forEach { key ->
                    val label = ctx.getString(key.labelRes)
                    MediaTile(label, Modifier.weight(1f).fillMaxHeight()) {
                        if (key.powerAction >= 0) {
                            // 关机 / 重启：不直接发，先进确认框（见下方 DialogCard）
                            confirm = key.powerAction to label
                        } else {
                            hk.press(key.bit)
                            handler.postDelayed({ hk.release(key.bit) }, 60)
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (action, label) ->
        ApComponents.DialogCard(onDismiss = { confirm = null }) {
            Text(text = ctx.getString(R.string.ui_touchpad_dialog_device, label), fontSize = 16.sp)
            Text(
                text = ctx.getString(R.string.ui_touchpad_dialog_no_only_need, label),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_common_cancel),
                    onClick = { confirm = null },
                )
                ApComponents.NarrowButton(
                    text = label,
                    onClick = {
                        val c = com.allperiph.wireless.ControlTarget.controlClient
                        if (c == null) {
                            android.widget.Toast.makeText(
                                ctx, R.string.ui_touchpad_toast_device_connect_not,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            c.power(action)
                            android.widget.Toast.makeText(
                                ctx, ctx.getString(R.string.ui_touchpad_toast_send_done, label),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        confirm = null
                    },
                )
            }
        }
    }
}

/** 游戏键盘：4×6 高频键位（技能数字 / 左手技能区 / 方向+确认）—— 键位表走 [KeyLayouts.GAME_ROWS] 共享表（与操控面同源，审计批3 2026-11-12） */
@Composable
private fun GameLayout(dens: KeyPref.Density, keycap: Int, fx: Int) {
    val layout = KeyLayouts.GAME_ROWS
    var sticky by remember { mutableIntStateOf(stickySnapshot()) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 错落入场（2026-11-12 第三批）：游戏键排依次落位（动画只动 graphicsLayer）
        layout.forEachIndexed { ri, labels ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp).enterStagger(rememberEnterStagger(ri))) {
                labels.forEach { text ->
                    val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == text }?.second
                    if (bit != null) {
                        ModKey(
                            label = text,
                            active = sticky and bit != 0,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            dens = dens,
                            keycap = keycap,
                            fx = fx,
                        ) {
                            HidKeys.toggleSticky(bit)
                            sticky = flipSticky(sticky, bit)
                        }
                    } else {
                        KeyBox(text, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                            HidKeys.tapLabel(text)
                            sticky = 0
                        }
                    }
                }
            }
        }
    }
}

/** 扩展布局（数字 / 九宫格 / 方向 / F 区）：按 [KeyLayouts] 行列渲染，列宽按 weight 均分 */
@Composable
private fun KeyGridLayout(
    rows: List<List<KeyLayouts.Key>>,
    dens: KeyPref.Density,
    keycap: Int,
    fx: Int,
) {
    // b21「按了没长亮」：sticky 本地镜像驱动修饰键高亮（与 GameLayout 同口径：toggle 后 flip、tap 普通键清零）
    var sticky by remember { mutableIntStateOf(stickySnapshot()) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 错落入场（2026-11-12 第三批）：扩展布局键排依次落位（动画只动 graphicsLayer）
        rows.forEachIndexed { ri, row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp).enterStagger(rememberEnterStagger(ri))) {
                row.forEach { k ->
                    val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == k.label }?.second
                    KeyBox(
                        k.label,
                        Modifier.weight(k.weight).fillMaxHeight(),
                        dens, keycap, fx,
                        active = bit != null && sticky and bit != 0,
                    ) {
                        // b17：FULL 的 Shift/Ctrl/Alt 是修饰词 → 转 sticky（裸 usage 无意义）；
                        // NUMPAD/GRID9/ARROWS/FN 无修饰词 label，行为不变
                        if (bit != null) {
                            HidKeys.toggleSticky(bit)
                            sticky = flipSticky(sticky, bit)
                        } else {
                            HidKeys.tap(k.usage)
                            sticky = 0
                        }
                    }
                }
            }
        }
    }
}

/** 自定义布局：[KeyPref.customRows] 行表（与操控面「自定义」同一份数据）；未配置给引导文案 + 编辑入口 */
@Composable
private fun CustomLayout(
    rows: List<List<String>>?,
    dens: KeyPref.Density,
    keycap: Int,
    fx: Int,
    onEdited: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        if (rows.isNullOrEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // 原来复用的是触控板页那句「长按「自定义」页签编辑布局」—— 可这一页的页签
                // 只接了 clickable，全仓也没有长按处理，照着提示做永远没反应（审查 v5
                // 初稿把这里算成死路；复核后确认下方那枚「编辑」按钮是能用的，所以只是文案错）。
                Text(text = ctx.getString(R.string.ui_kb_custom_empty_hint), fontSize = 14.sp)
            }
        } else {
            // 错落入场（2026-11-12 第三批）：自定义键排依次落位（动画只动 graphicsLayer）
            rows.forEachIndexed { ri, labels ->
                Row(modifier = Modifier.weight(1f).fillMaxWidth().enterStagger(rememberEnterStagger(ri))) {
                    labels.forEach { label ->
                        KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
                            HidKeys.tapLabel(label)
                        }
                    }
                }
            }
        }
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_common_edit),
            onClick = {
                (ctx as? android.app.Activity)?.let { act ->
                    com.allperiph.ui.CustomKeyboardDialog.show(act) { onEdited() }
                }
            },
        )
    }
}

/**
 * 遥控 / 电源图块：整块可点（与操控面 `mediaTile` / `actionTile` 同一交互，纯文字键面）。
 *
 * 灯效（皮肤审查 #5「RGB 开着，遥控页一个灯不亮」）：开灯时图块与键帽同款亮灯 ——
 * 呼吸 / 波浪走 [rgbFaceAt] 同一条公式按横坐标错相；扩散档平时熄灭、点击时从图块
 * 自己抛一圈 [TouchFx] 涟漪。灯面与字色**都在绘制阶段取值**（drawBehind +
 * graphicsLayer colorFilter，审查 #3 同口径）：组合期只读 phase 引用与 mode，
 * 9 个图块开灯时不逐帧重组。
 */
@Composable
private fun MediaTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    // 媒体 / 电源图块也是「按键」，同样震一下、响一声
    val ctx = LocalContext.current
    val fx = KeyPref.effectIndex(ctx)
    val rgb = LocalRgb.current
    val rgbOn = rgb.phase != null && rgb.mode != KeyPref.RGB_OFF
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val rgbCap = Color(ctx.getColor(R.color.keycap_rgb_black))
    var rgbX by remember { mutableIntStateOf(0) }
    var rgbC by remember { mutableStateOf(Offset.Zero) }
    /** 当前图块灯色；**只允许在绘制阶段（drawBehind / graphicsLayer 块）调用** */
    fun tileLight(): Color? {
        if (!rgbOn) return null
        @Suppress("UNUSED_EXPRESSION")
        val tick = rgb.phase?.value ?: 0f // 订阅相位 → 触发重绘（扩散档的波光也靠它逐帧推）
        if (rgb.mode == KeyPref.RGB_TOUCH) {
            return touchLightAt(rgbC).takeIf { it.alpha > 0f }
        }
        return rgbFaceAt(rgb.mode, rgb.phase?.value ?: 0f, rgbX.toFloat())
    }
    // 字色层（审查 #3）：组合期只给静态 Modifier，灯色由 colorFilter 在绘制期按字形染色
    val tileLabelMod: Modifier =
        if (!rgbOn) {
            Modifier
        } else {
            Modifier.graphicsLayer {
                val l = tileLight()
                colorFilter = l?.let { ColorFilter.tint(it, BlendMode.SrcIn) }
            }
        }
    // 键面：开灯 → drawBehind 画「灯色外圈 + 黑键帽内胆 + 同色描边」（与键帽同观感）；
    // 关着 / 扩散档空闲 → 原 surfaceContainer。
    val faceMod: Modifier =
        if (rgbOn) {
            Modifier
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    rgbX = b.left.toInt()
                    rgbC = b.center
                }
                .drawBehind {
                    val l = tileLight()
                    if (l != null) {
                        val inset = 3.dp.toPx()
                        val tl = Offset(inset, inset)
                        val cap = Size(size.width - inset * 2, size.height - inset * 2)
                        drawRect(color = l)
                        drawRect(color = rgbCap, topLeft = tl, size = cap)
                        drawRect(color = l, topLeft = tl, size = cap, style = Stroke(2.dp.toPx()))
                    } else {
                        drawRect(color = surface)
                    }
                }
        } else {
            Modifier.background(surface)
        }
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .then(faceMod)
            .apKeyFeel()
            .apPress(
                fx,
                MiuixTheme.colorScheme.primary,
                {
                    // 扩散档：图块点击也抛涟漪（与键帽 LaunchedEffect(pressed) 同一全局波源，
                    // 图块没有按下态回调，落在点击时机，观感差一拍可接受）
                    if (rgbOn && rgb.mode == KeyPref.RGB_TOUCH) TouchFx.add(rgbC.x, rgbC.y)
                    onClick()
                },
                downFeel = true,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = tileLabelMod,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            // 成宇 2026-11-13 05:0x「深色模式下遥控布局按钮字体是黑色，看不清」：图块文字
            // 此前没显式给色，落到默认渲染成黑字 —— 深色 surface 上直接没影。对齐键帽
            // fg 的取法走 onSurface（亮=黑字/暗=白字，跟系统日夜自动切换）。
            color = MiuixTheme.colorScheme.onSurface,
            // 图块宽度按文字自适应（issue#168 2026-11-12 #5「长度按文字宽度自适应」）：
            // 不再由外层 weight 拉宽 —— 文字单行排，标签长就宽、短就窄。
            maxLines = 1,
        )
    }
}

/**
 * 修饰键帽（Ctrl / Alt / Shift / Win）。
 *
 * 2026-11-04：**改为 [KeyBox] 的一行委托**。此前本函数与 [KeyBox] 互为复制品 ——
 * 两份完全相同的三档渲染（玻璃 / 平面 / 机械）+ 相同的 `press` / `shape` / `h` / `interaction` /
 * `fxMod`，连两段审查注释都原样抄了两遍；真实差异只有 `bg` / `fg` / 玻璃档 `tint` 三个表达式。
 *
 * 合并后**调用点零改动**（签名与 `Modifier` 传递方式全部保持不变），渲染差异全部由
 * [KeyBox] 的 `active` / `tint` 表达：`active` 决定 bg（primary/surfaceContainer）与
 * fg（White/onSurface）—— 三档统一；`tint` **只喂玻璃档**（染色），平面/机械档仍用 `fg`，
 * 否则 active=true 时会出现 primary 文字压在 primary 背景上、字直接看不见。
 */
@Composable
private fun ModKey(
    label: String,
    active: Boolean,
    modifier: Modifier,
    dens: KeyPref.Density,
    keycap: Int,
    fx: Int,
    onClick: () -> Unit,
) {
    KeyBox(
        label = label,
        modifier = modifier,
        dens = dens,
        keycap = keycap,
        fx = fx,
        active = active,
        tint = if (active) MiuixTheme.colorScheme.primary else null,
        onClick = onClick,
    )
}

/**
 * 键帽：平面 = 描边圆角块；机械 = 底座当立体侧面、顶面按下时下沉
 * （与触控板页机械键帽同一档观感）。高度给下限 44dp * 密度倍率，
 * 行高够时由外层 `weight(1f)` 拉满。
 *
 * ⚠️ **2026-11-04 重构**：本函数此前与 [ModKey] 是逐字重复的两份（连注释都抄了两遍），
 * 现已合并为这一份 —— 修饰键只是按 `active` 调用它。**以后新增档位只改这里**，
 * 不要再在 ModKey 里写一遍。
 *
 * ⚠️ **参数顺序是硬约定，CI 踩过**（2026-11-04 android job 失败）：四个布局
 * （Quick/Game/KeyGrid/Custom）全部写的是 `KeyBox(a, b, c, d, e) { … }` 这种
 * **位置参数 + 尾随 lambda**，Kotlin 的尾随 lambda 只能绑到**最后一个形参**。
 * 第一版把签名写成 `…, fx, onClick, active, tint` —— `tint: Color?` 压了轴，
 * 于是那 4 处报成两联：`No value passed for parameter 'onClick'`（第 6 位空着）
 * 与 `actual type '() -> Unit' but 'Color?' was expected`（lambda 被判给了 tint）。
 * 所以：`onClick` **必须压轴**，`active` / `tint` 只能带默认值夹在中间；
 * [ModKey] 走命名参数转发，不受影响（它也是唯一另一个合法的调用姿势）。
 *
 * @param active 修饰键点亮态：`bg` 取 primary、`fg` 取白（三档统一）
 * @param tint 玻璃档染色（非空时给键帽染色）；平面 / 机械档不用它 —— 那两档靠 `fg`
 *   决定字色，写进 tint 会把 active 态的文字压成主色
 * @param onClick 必须是最后一个参数（尾随 lambda 绑位，见上方 ⚠️）
 */
@Composable
private fun KeyBox(
    label: String,
    modifier: Modifier,
    dens: KeyPref.Density,
    keycap: Int,
    fx: Int,
    active: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    // 按键反馈（成宇 2026-11-02「音效、喇叭回声、震动都没有了」）：
    // Compose 迁移时丢掉了 Feedback.tap，键帽只剩视觉下沉。所有 8 套布局的键位
    // 最终都收敛到 KeyBox / ModKey / MediaTile 这三个组件，在这里统一接回，
    // 档位仍由设置页的 ApFeel 控制（关 / 轻 / 标准 / 强，音效 关 / 点击 / 键盘 / 机械轴）。
    val host = LocalView.current
    // 震动在按下那一刻由 [apKeyFeel] 给过了，这里只出声（见 Feedback.release 的说明）
    // #6 长按连发（用户 2026-11-08「长按按键的连发功能所有键盘都没有吗」）：
    // 连发开火后抬手那次自然 onClick 吞掉 —— 否则长按结尾会多发一下（与操控面同语义）。
    var repeatFired by remember { mutableStateOf(false) }
    val press: () -> Unit = {
        if (repeatFired) { repeatFired = false } else { Feedback.release(host); onClick() }
    }
    val shape = RoundedCornerShape(dens.cornerDp.dp)
    // 审查条 2.2：基础高度 44dp × 倍率（0.92/1.0/1.12）→ 40.5/44/49.3dp，两档低于 48dp
    // 触摸基线。这里只抬「下限」不动倍率的语义：紧凑档靠字号与间隙变紧，不再压缩命中区；
    // 宽松档仍按 1.12 放大（49.3dp）。
    val h = (44f * dens.keyScale).coerceAtLeast(48f).dp
    val bg = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
    // —— RGB 灯效（成宇 2026-11-08）——
    // active（sticky 点亮）优先级最高：修饰键状态是功能反馈，不能被灯效盖掉。
    // 相位**只在绘制阶段**被读（faceMod/sideMod 的 drawBehind + labelMod 的
    // graphicsLayer colorFilter）—— 组合期只读 phase 引用与 mode、不订阅 .value，
    // 开灯时键帽只重绘、不逐帧重组（皮肤审查 #3 已修：此前字色与玻璃 tint
    // 在组合期取相位，84 键每帧重组）。
    val rgb = LocalRgb.current
    // b34 材质动效相位（draw 阶段读 .value → 只重绘不重组）
    val matPhase = LocalMatPhase.current
    val rgbOn = rgb.phase != null && rgb.mode != KeyPref.RGB_OFF && !active
    // 按**横坐标**错相（列状彩虹）：落位时记一次 x；三档都挂监听——b33 起呼吸档也错相
    // （修「rgb 没有渐变」：不再全键盘同色）。取 boundsInWindow（本仓 Compose 版本无 positionInRoot，
    // TouchpadPage 同款用法），左缘像素即可，窗口相对偏移不影响错相。
    var rgbX by remember { mutableIntStateOf(0) }
    // 按下扩散档还要键心（中心点）：TouchFx.add 的原点 + 涟漪距离的基准
    var rgbC by remember { mutableStateOf(Offset.Zero) }
    val km =
        if (rgbOn ||
            keycap == KeyPref.KEYCAP_ACRYLIC ||
            keycap == KeyPref.KEYCAP_CRYSTAL ||
            keycap == KeyPref.KEYCAP_WOOD
        ) {
            modifier.onGloballyPositioned {
                val b = it.boundsInWindow()
                rgbX = b.left.toInt()
                rgbC = b.center
            }
        } else {
            modifier
        }
    /** 当前键灯色（b26 按下扩散 + b27 修冻结 —— 成宇 2026-11-08）：
     *  RGB_TOUCH = 真键盘 Reactive：平时键面正常不发光，按下从键心抛亮带向外扫
     *  （SPEED_PX/BAND_PX/LIFE_MS），扫过处泛光 + 黑键帽 + 亮边、字符点亮；
     *  呼吸 / 波浪沿用 b25 真键盘渲染；公式本体在文件级 [touchLightAt] / [rgbFaceAt]，
     *  遥控图块（审查 #5）共用 —— 别再往这里加第二份公式。
     *  **只允许在绘制阶段调用**（drawBehind / graphicsLayer 块内），组合期调它会重新
     *  订阅相位、把审查 #3 修掉的逐帧重组又带回来。
     */
    fun touchLight(): Color = touchLightAt(rgbC)
    fun rgbFace(vScale: Float): Color {
        if (rgb.mode == KeyPref.RGB_TOUCH) {
            val l = touchLight()
            if (l.alpha <= 0f) return Color.Transparent
            return l.copy(
                red = l.red * vScale,
                green = l.green * vScale,
                blue = l.blue * vScale,
            )
        }
        return rgbFaceAt(rgb.mode, rgb.phase?.value ?: 0f, rgbX.toFloat(), vScale)
            ?: Color.Transparent
    }
    // 黑键帽走语义令牌（check_appearance 判据6：ui/ 下禁止字面色值）
    val rgbCap = Color(host.context.getColor(R.color.keycap_rgb_black))
    // 键面：相位在 drawBehind 里读，动画只触发重绘不重组；灯效关 → 原样 background。
    val faceMod: Modifier =
        if (rgbOn) {
            Modifier.drawBehind {
                if (rgb.mode == KeyPref.RGB_TOUCH) {
                    val tick = rgb.phase?.value ?: 0f // 订阅相位 → 逐帧重绘，涟漪推进
                    drawRect(color = bg)
                    val l = touchLight()
                    if (l.alpha > 0f) {
                        val inset = 3.dp.toPx()
                        val tl = Offset(inset, inset)
                        val cap = Size(size.width - inset * 2, size.height - inset * 2)
                        drawRect(color = l)
                        drawRect(color = rgbCap, topLeft = tl, size = cap)
                        drawRect(color = l, topLeft = tl, size = cap, style = Stroke(2.dp.toPx()))
                    }
                } else {
                    // b33 键内渐变：导光面底亮顶暗 + 黑键帽内胆 + 同色渐变描边
                    val light = rgbFace(1f)
                    val grad = Brush.verticalGradient(0f to light, 1f to light.copy(alpha = 0.45f))
                    val inset = 3.dp.toPx()
                    val tl = Offset(inset, inset)
                    val cap = Size(size.width - inset * 2, size.height - inset * 2)
                    drawRect(brush = grad)
                    drawRect(color = rgbCap, topLeft = tl, size = cap)
                    drawRect(brush = grad, topLeft = tl, size = cap, style = Stroke(2.dp.toPx()))
                }
            }
        } else if (
            keycap == KeyPref.KEYCAP_ACRYLIC ||
                keycap == KeyPref.KEYCAP_CRYSTAL ||
                keycap == KeyPref.KEYCAP_WOOD
        ) {
            // b34 材质动态质感：亚克力流光带 / 水晶双星芒 + 棱彩流边 / 木质缎面反光，
            // 全部从 LocalMatPhase 那条 6s 相位取节奏，xOff 错相让相邻键不齐刷刷；
            // 相位在 draw 阶段读 → 只重绘不重组（与灯效同一套路）。
            Modifier.drawBehind {
                val mp = matPhase.value
                val xo = rgbX.toFloat()
                val bw = size.width
                val bh = size.height
                when (keycap) {
                    KeyPref.KEYCAP_ACRYLIC -> {
                        drawRect(color = bg)
                        drawRect(color = Color.White, alpha = 0.07f)
                        val bandW = bh * 1.3f
                        val bx = (((mp + xo * 0.4f) % 360f) / 360f) * (bw + bandW * 2f) - bandW
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = 0.16f),
                                1f to Color.Transparent,
                                startX = bx,
                                endX = bx + bandW,
                            ),
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.10f),
                                0.45f to Color.Transparent,
                            ),
                        )
                        drawRect(color = Color.White, alpha = 0.20f, style = Stroke(1.dp.toPx()))
                    }
                    KeyPref.KEYCAP_CRYSTAL -> {
                        drawRect(color = hsvColor(232f, 0.05f, 0.28f))
                        drawLine(
                            Color.White.copy(alpha = 0.10f),
                            Offset(0f, bh * 0.30f),
                            Offset(bw, bh * 0.05f),
                            strokeWidth = 1f,
                        )
                        drawLine(
                            Color.White.copy(alpha = 0.10f),
                            Offset(0f, bh * 0.72f),
                            Offset(bw, bh * 0.95f),
                            strokeWidth = 1f,
                        )
                        drawRect(
                            color = hsvColor((mp * 0.6f + xo * 0.35f) % 360f, 0.85f, 1f),
                            alpha = 0.42f,
                            style = Stroke(1.2.dp.toPx()),
                        )
                        val r = minOf(bw, bh) * 0.24f
                        val a1 = Math.toRadians((mp + xo * 0.5f).toDouble())
                        val c1 = Offset(bw / 2f + (kotlin.math.cos(a1) * r).toFloat(), bh / 2f + (kotlin.math.sin(a1) * r).toFloat())
                        val a2 = Math.toRadians((mp + xo * 0.5f + 180.0))
                        val c2 = Offset(bw / 2f + (kotlin.math.cos(a2) * r).toFloat(), bh / 2f + (kotlin.math.sin(a2) * r).toFloat())
                        val gl = 0.30f + 0.45f * kotlin.math.abs(kotlin.math.sin(Math.toRadians(mp * 2.0))).toFloat()
                        val arm = bh * 0.14f
                        drawLine(Color.White.copy(alpha = gl), Offset(c1.x - arm, c1.y), Offset(c1.x + arm, c1.y), strokeWidth = 1.4f)
                        drawLine(Color.White.copy(alpha = gl), Offset(c1.x, c1.y - arm), Offset(c1.x, c1.y + arm), strokeWidth = 1.4f)
                        drawCircle(Color.White.copy(alpha = gl), radius = 1.6.dp.toPx(), center = c1)
                        drawLine(Color.White.copy(alpha = gl * 0.8f), Offset(c2.x - arm, c2.y), Offset(c2.x + arm, c2.y), strokeWidth = 1.2f)
                        drawLine(Color.White.copy(alpha = gl * 0.8f), Offset(c2.x, c2.y - arm), Offset(c2.x, c2.y + arm), strokeWidth = 1.2f)
                        drawCircle(hsvColor(mp, 0.9f, 1f), radius = 1.8.dp.toPx(), alpha = 0.55f, center = c2)
                    }
                    else -> {
                        drawRect(color = hsvColor(26f, 0.52f, 0.38f))
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to hsvColor(25f, 0.56f, 0.32f),
                                0.16f to hsvColor(27f, 0.50f, 0.44f),
                                0.33f to hsvColor(25f, 0.58f, 0.30f),
                                0.5f to hsvColor(28f, 0.48f, 0.46f),
                                0.66f to hsvColor(25f, 0.56f, 0.31f),
                                0.83f to hsvColor(27f, 0.52f, 0.43f),
                                1f to hsvColor(25f, 0.57f, 0.33f),
                            ),
                        )
                        val sw = bw * 0.7f
                        val sx = (((mp * 0.7f + xo * 0.3f) % 360f) / 360f) * (bw + sw * 2f) - sw
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = 0.11f),
                                1f to Color.Transparent,
                                startX = sx,
                                endX = sx + sw,
                            ),
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.75f to Color.Transparent,
                                1f to Color.White.copy(alpha = 0.07f),
                            ),
                        )
                        drawRect(color = hsvColor(25f, 0.6f, 0.26f), alpha = 0.9f, style = Stroke(1.dp.toPx()))
                    }
                }
            }
        } else {
            Modifier.background(bg)
        }
    // 字符透光（审查 #3 修复）：真键盘字符是双色注塑透光的——灯效下字符要跟灯色，
    // 但**组合期不再取相位**。做法：Text 用静态色 [fg]，灯色由 [labelMod] 的
    // graphicsLayer.colorFilter 在绘制期整层换色（SrcIn = 按字形 alpha 染色，底不变）——
    // 相位 State 只让字层重绘，键帽组合不重组（84 键不再每帧重组）。
    val fg =
        if (active) {
            Color.White
        } else {
            MiuixTheme.colorScheme.onSurface
        }
    /** 字色层：绘制期算灯色 → colorFilter 染字；没灯（关档 / 扩散档空闲）→ 不染走 [fg] 原色 */
    val labelMod: Modifier =
        if (!rgbOn) {
            Modifier
        } else {
            Modifier.graphicsLayer {
                @Suppress("UNUSED_EXPRESSION")
                val tick = rgb.phase?.value ?: 0f // 订阅相位 → 字层逐帧重绘（扩散档的波光推进靠它）
                val l = if (rgb.mode == KeyPref.RGB_TOUCH) touchLight() else rgbFace(1f)
                colorFilter =
                    if (l.alpha > 0f) {
                        // 玻璃档整面铺光、字恒白（无黑内胆衬托）；有黑内胆的档位字随灯色
                        ColorFilter.tint(
                            if (keycap == KeyPref.KEYCAP_GLASS) Color.White else l,
                            BlendMode.SrcIn,
                        )
                    } else {
                        null
                    }
            }
        }
    val accent = MiuixTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fxValues = rememberKeyFx(pressed, fx)
    val fxMod = Modifier.keyFx(fxValues, accent)
    // 按下扩散：按下的那一刻把键心交给 TouchFx（只在 RGB_TOUCH 档注册涟漪）
    LaunchedEffect(pressed) {
        if (pressed && rgbOn && rgb.mode == KeyPref.RGB_TOUCH) TouchFx.add(rgbC.x, rgbC.y)
    }
    // 副文字键帽（KeyLayouts FULL 的 F 键「F1\n静音」双行标签，2026-11-12 图4「文字
    // 缺失」修复）：主行用正常字号、副行缩到 0.6 倍。横屏键位区 6 行均分，行高只有
    // 30dp 出头，两行全尺寸字放不下、被键帽边界裁掉副行；缩副行后两行合计约 23dp，
    // 各档键帽都装得下。三档渲染（玻璃/普通/机械）共用 capText，不再各写一份。
    val labelLines = label.split('\n')
    val capText: @Composable () -> Unit = {
        if (labelLines.size == 1) {
            Text(text = label, modifier = labelMod, fontSize = dens.textSize.sp, color = fg)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = labelLines[0],
                    modifier = labelMod,
                    fontSize = dens.textSize.sp,
                    color = fg,
                    maxLines = 1,
                )
                Text(
                    text = labelLines[1],
                    modifier = labelMod,
                    fontSize = (dens.textSize * 0.6f).sp,
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
    // —— #6 长按连发：按住 400ms 起、每 55ms 一拍重发，松手即停；连发只送键不出声
    //（声/震在按下那一下已给）。修饰键走 ModKey、遥控走 MediaTile，均不连发。
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(400)
            while (true) {
                // issue#168 第 7 条：先清一次再发 —— tap 的 60ms 自动释放窗口（55ms 节奏
                // 下永不到期，每次都被下一拍 removeCallbacks）会让对端看不到新的按下边沿，
                // 长按只出第一个字符。releaseNow 后每拍都是一次真实的 按下 → 抬起。
                HidKeys.releaseNow()
                repeatFired = true
                onClick()
                kotlinx.coroutines.delay(55)
            }
        } else {
            // 全审查条 10：按压被 CANCEL（手势滑出键面）时 click 不回调，滞留的
            // repeatFired 会把下一次点按整个吞掉 —— 松开/取消一律复位。
            repeatFired = false
        }
    }
    if (keycap == KeyPref.KEYCAP_GLASS) {
        // 液态玻璃键帽：半透明 + 高光描边（成宇 2026-11-03「键盘再加一组液态玻璃按键的皮肤」）。
        // 玻璃档的灯面（审查 #3）：整面铺光、不画黑内胆（玻璃的字恒白衬在光面上），
        // 绘制期取值；空闲（扩散档没波）什么都不画，露出 surfaceContainer 底。
        val glassFaceMod: Modifier =
            if (rgbOn) {
                Modifier.drawBehind {
                    @Suppress("UNUSED_EXPRESSION")
                    val tick = rgb.phase?.value ?: 0f
                    val l = if (rgb.mode == KeyPref.RGB_TOUCH) touchLight() else rgbFace(1f)
                    if (l.alpha > 0f) drawRect(color = l)
                }
            } else {
                Modifier
            }
        ApComponents.GlassKeycap(
            // 液态玻璃键帽同样要按下即震（各材质一致，别让玻璃档掉队）
            modifier = km.padding(horizontal = dens.gapDp.dp).heightIn(min = h).apKeyFeel(),
            shape = shape,
            label = labelLines[0],
            // 副行单独传（玻璃档里由 GlassKeycap 缩小渲染，见 capText 注释）
            subLabel = labelLines.getOrNull(1),
            textSize = dens.textSize.sp,
            fg = fg,
            // 只喂 active 的染色（primary）；灯效走 faceMod/labelMod（绘制期），
            // 不再把 rgbFace 塞进 tint —— 审查 #1 的透明坑 + #3 的组合期取值都出在这条路
            tint = tint,
            interaction = interaction,
            onClick = press,
            fxMod = fxMod,
            faceMod = glassFaceMod,
            labelMod = labelMod,
        )
        return
    }
    if (keycap != KeyPref.KEYCAP_MECHANICAL) {
        Box(
            modifier = km
                .padding(horizontal = dens.gapDp.dp)
                .heightIn(min = h)
                .clip(shape)
                .then(faceMod)
                // ★ 同上：不把 interactionSource 传进来，[rememberKeyFx] 就收不到按压事件，
                // 特效永远不画。这是「键盘动效没有」的真正原因。
                .clickable(interactionSource = interaction, indication = null, onClick = press)
                .apKeyFeel()
                .then(fxMod),
            contentAlignment = Alignment.Center,
        ) { capText() }
        return
    }
    val side = lerp(bg, Color.Black, 0.35f)
    // 机械底座同色系压暗（灯效下 side 也跟着走色相，否则侧壁是一块死灰）
    val sideMod: Modifier =
        if (rgbOn) {
            Modifier.drawBehind {
                if (rgb.mode == KeyPref.RGB_TOUCH) {
                    val tick = rgb.phase?.value ?: 0f // 订阅相位 → 与灯面同步重绘
                    drawRect(color = side)
                    val l = touchLight()
                    if (l.alpha > 0f) {
                        drawRect(color = l.copy(alpha = 0.42f))
                    }
                } else {
                    drawRect(rgbFace(0.42f))
                }
            }
        } else {
            Modifier.background(side)
        }
    Box(km.padding(horizontal = dens.gapDp.dp).heightIn(min = h)) {
        Box(Modifier.matchParentSize().clip(shape).then(sideMod))
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = 3.dp)
                .offset(y = if (pressed) 2.dp else 0.dp)
                .clip(shape)
                .then(faceMod)
                .clickable(interactionSource = interaction, indication = null, onClick = press)
                .apKeyFeel()
                .then(fxMod),
            contentAlignment = Alignment.Center,
        ) { capText() }
    }
}

/**
 * 「剪贴板」工具键预取（§6.3 页签行动作组，2026-11-13 UI 审查重写）：
 * 原实现点了立即直发、无确认 —— 剪贴板里常是用户没看过全文的一大段字，
 * 误触即不可逆。现在只做**预检 + 取值**：无受控设备 / 空串时提示并返回 null，
 * 有内容交确认卡（[sendClipboardTextToPc] 负责真正发送）。与悬浮球剪贴板面板
 * 的「一键补发」同一份 ClipboardSync 口径 —— 取值、空串判定、程序性写入抑制
 * 都在 ClipboardSync 一处，这里不重复实现。
 */
internal fun fetchClipboardForPc(ctx: android.content.Context): String? {
    val client = com.allperiph.wireless.ControlTarget.controlClient
    if (client == null || !client.ready) {
        android.widget.Toast.makeText(
            ctx, R.string.ui_ball_fn_clipboard_nolink, android.widget.Toast.LENGTH_SHORT,
        ).show()
        return null
    }
    val cur = com.allperiph.ui.ClipboardSync.currentText().orEmpty()
    if (cur.isEmpty()) {
        android.widget.Toast.makeText(
            ctx, R.string.ui_ball_fn_clipboard_empty, android.widget.Toast.LENGTH_SHORT,
        ).show()
        return null
    }
    return cur
}

/** 确认卡「发到电脑」后的真正发送（0x20 通道）：发前再验一次链路，确认期间可能已掉线。 */
internal fun sendClipboardTextToPc(ctx: android.content.Context, text: String) {
    val client = com.allperiph.wireless.ControlTarget.controlClient
    val ok = client != null && client.ready &&
        runCatching { client.sendClipboard(text) }.getOrDefault(false)
    android.widget.Toast.makeText(
        ctx,
        if (ok) ctx.getString(R.string.ui_ball_fn_clipboard_copied, text.length)
        else ctx.getString(R.string.ui_ball_fn_clipboard_nolink),
        android.widget.Toast.LENGTH_SHORT,
    ).show()
}

/**
 * 「语音」工具键（§6.3 Expanded 工具行）的原拉起器（2026-11-11 移除）：
 * 主屏路径曾用 rememberLauncherForActivityResult 直拉识别、悬浮键盘路径只弹提示；
 * 成宇「语音按键链路不能用」后统一并入 [com.allperiph.ui.VoiceCaptureActivity] ——
 * 识别器挑选（v223.29.33 口径：先挑非 Google 识别活动）、结果抽取都在宿主页内，
 * 主屏与悬浮键盘共用一条链路，本文件不再保留第二套实现。
 */
