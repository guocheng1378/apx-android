package com.allperiph.ui.compose

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.core.ModuleId
import com.allperiph.ui.AgentController
import com.allperiph.ui.AgentForegroundService
import com.allperiph.ui.ThemePref
import com.allperiph.ui.ApSkin
import com.allperiph.ui.OrientLock
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.ui.miuix.MiuixTokens
import com.allperiph.ui.miuix.applyEdgeToEdge
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.allperiph.R
import com.allperiph.ui.miuix.apHazeSource
import com.allperiph.ui.miuix.rememberApHazeState
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import com.allperiph.touchpad.TouchpadModule
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 新主壳（唯一 launcher 入口：状态 / 触控板 / 键盘 / 设置四页）。
 *
 * 旧主壳 `com.allperiph.ui.MainActivity` 已删（迁移完成后清理），它承担的职责都在本类落地：
 * 横竖屏策略（`sensor` + [rememberApAdaptiveLayout] 两栏、重力判读自动切页）、
 * 剪贴板同步（onResume/onPause 挂 [com.allperiph.ui.ClipboardSync]）、
 * 横屏触控板页的三指手势遮蔽持有、受控设备选择器（[ApDevicePicker]）。
 */
class ApShellActivity : ComponentActivity() {

    /** 四指切页的中间状态（dispatchTouchEvent 在 Compose 之外，只能用字段传） */
    private var pickOnStart = false
    private var fourStartX = -1f
    private var fourSwiped = false

    /** 当前页签的"单一真相"：Compose 侧写、dispatchTouchEvent 读/写。
     *  必须是快照状态（成宇 2026-11-10「横屏的四指切页好像不能用」）：此前是普通
     *  `@Volatile` 字段，四指滑在 dispatchTouchEvent 里只改它、不同时写 `page`，
     *  而 `LaunchedEffect(pageState)` 的 key 是组合期读到的值 —— 改普通字段不会
     *  触发重组，同步 effect 永远不再跑，界面纹丝不动。改成 snapshot state 后
     *  任何一处写它都会让读它的组合失效，四指切页即时生效。 */
    private var pageState by androidx.compose.runtime.mutableStateOf(0)

    /** 退出确认弹窗开关（v223.28 补回旧 MainActivity 迁移时整块丢掉的那份） */
    private val showExitAsk = androidx.compose.runtime.mutableStateOf(false)

    /** 横竖屏标志（Compose 侧写入，dispatchTouchEvent 读） */
    @Volatile
    private var adaptiveState: com.allperiph.ui.miuix.ApAdaptiveLayout? = null

    companion object {
        /** 「只来选设备」模式：遥控器页带这个 extra 启动时立刻弹设备选择器 */
        const val EXTRA_PICK_DEVICE = "com.allperiph.extra.PICK_DEVICE"

        /** 页签存档键：Activity 万一被重建（进程回收后返回、系统改配置等），
         *  用它把用户当时停的那一页还原，而不是一律归零到状态页 ——
         *  归零的表现就是成宇 2026-11-02 反馈的「横屏切换后会先跳状态页再跳回」。 */
        private const val STATE_PAGE = "com.allperiph.state.PAGE"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePref.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不再调 ApPalette.applyRuntimeTheme：Compose 侧的强调色已由 ApMiuixTheme 的
        // colorScheme 给全，这个调用是「给 View 层 ?attr 叠运行时强调色」的遗产，
        // 宿主页已经没有任何 View 需要它；保持 attachBaseContext(ThemePref.wrap)，
        // 因为 ApMiuixTheme 的明暗取值来自 configuration.uiMode —— 正是 wrap 注入的用户选择。
        ApAccentSync.apply(this)
        applyImmersive()
        // 全局界面缩放（2026-11-07）：冷启动在此读回（幂等）。悬浮球等非主壳宿主
        // 目前按 100% 渲染，等有反馈再扩到那侧（见 UiScalePref 的 KDoc）。
        com.allperiph.ui.UiScalePref.load(this)
        // 方向锁定（审查条 1.1）：按偏好钉住/放开本 Activity 的朝向。必须在 setContent 之前，
        // 否则首帧会按系统朝向排版一次再被改回来（真机上表现为「闪一下才转」）。
        OrientLock.apply(this)
        pickOnStart = intent?.getBooleanExtra(EXTRA_PICK_DEVICE, false) == true
        intent?.removeExtra(EXTRA_PICK_DEVICE)
        // 同上：能在 onSaveInstanceState 保住时优先用存档值（比 intent extra 更贴近
        // 「用户刚才看的是哪一页」；extra 只在冷启动带参时才有）。
        savedInstanceState?.getInt(STATE_PAGE, -1)?.takeIf { it >= 0 }?.let { pageState = it }
        setContent {
            // 2026-11-07（成宇「做成可以无极自定义 dpi 缩放」）：整体密度乘全局缩放。
            // 这一层只管主壳四页；弹窗是独立组合，在 ApComponents.DialogCard 里
            // 自己再供一次 —— 两处都读 UiScalePref.current，拖滑条即刻全端生效。
            val __base = androidx.compose.ui.platform.LocalDensity.current
            // 直接跟手，不再加缓动（成宇 2026-11-10「界面缩放不丝滑」）：150ms tween
            // 是档位跳变时代（离散档之间一步一跳）加的平滑；滑条改连续后它只剩副作用
            // —— 拖动中每次目标值变化都重启 tween，界面永远落后拇指一小截地「追」，
            // 松手后还多渲染一段尾巴，正是不丝滑的来源。UiScalePref 只有滑条一个
            // 写入方（连续、每次步进极小），直接读它即是逐帧实况预览，天然平滑。
            val __scale = com.allperiph.ui.UiScalePref.current.floatValue
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(__base.density * __scale, __base.fontScale),
            ) {
                ApMiuixTheme { Shell() }
            }
        }
    }

    /**
     * 页签存档（v223.29.1 第 3 条「横屏切换后会先跳状态页再跳回」的机制保证）。
     *
     * 修法有两层，缺一层都还有可能漏：
     *   · 声明层：[AndroidManifest] 给本 Activity 列了 orientation、screenSize 等
     *     configChanges，旋转不重建 Activity，页签自然不丢 —— 这是主路径；
     *   · 兜底层：万一还是被重建（进程被回收后返回、系统改语言或密度、分屏等），
     *     把页签存进 Bundle 还原。此前 [pageState] 只是普通字段，重建即归零，
     *     表现就是「先跳一下状态页，再跳回原来那页」。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, pageState)
    }

    @Composable
    private fun Shell() {
        // 反馈 #52（新一轮）第 4 条：横屏底部留白按 96dp 统一，不再让底栏压住页面内容；
        // 竖屏 88dp（底栏自身高度 + 一点呼吸）
        // 上游 v223.13 对旧 Views 版本就是按 96dp 修的，这里保持同一口径
        val adaptive = rememberApAdaptiveLayout()
        adaptiveState = adaptive
        val landscape = adaptive.landscape
        // 成宇 2026-11-02「横屏后悬浮低栏可以悬浮在右上角」：低栏挪到右上角之后，
        // 给它预留的空档也得跟着从底部搬到顶部 —— 否则页面顶部内容被低栏压住、
        // 底部却还空着 96dp（那 96dp 本来就是为底部居中的低栏留的）。
        // 竖屏维持原样：底栏在底部居中，88dp 是「底栏自身高度 + 一点呼吸」。
        // 方案4（成宇 2026-11-07「低栏缩回应是透明悬浮，不该强占界面面积」）：
        // 让位量不再固定，在下方 barCollapsed 声明处按收/展状态动画 —— 收起成球时
        // 竖屏 88dp / 横屏 96dp 收到 8dp，内容回涨与球缩同一套进入曲线（界面稿禁弹簧）；展开恢复原值。
        // 2026-11-07 二轮（成宇「按安卓 aosp 规范适配，不是直接隐藏状态栏和小白条」
        // 「圆角和挖孔优化不是直接上部分不显示内容」）：系统栏改为显示
        // （applyEdgeToEdge），顶部按**含状态栏**的 top 垫 —— 竖屏内容从状态栏下
        // 开始（状态栏高度已天然盖住孔区）；横屏状态栏 inset 落在侧边、top≈0 不变；
        // 圆角不再参与垫边（四角由系统 UI 避让，垫半径会把顶部内容整块推走）。
        val topPad = com.allperiph.ui.miuix.rememberApInsets().top
        // 特效：顶栏渐进模糊（Android 13+ 有 RuntimeShader 才开，老机器退化为纯色底）
        val hazeState = rememberApHazeState()
        // 底栏 textureBlur 的采样源：内容层注册进 LayerBackdrop。
        // 底色取壳自己画的背景（md_background），不用 md_surface —— 本壳是 background
        // （浅色 #F5F6F6 / 深色 #010102），采样层若铺 surface 会让胶囊底部比真实背景
        // 更亮一层，玻璃看起来发白。
        val shellBg = MiuixTheme.colorScheme.background
        val navBackdrop = rememberLayerBackdrop {
            drawRect(shellBg)
            drawContent()
        }
        var page by remember { mutableIntStateOf(pageState) }
        // 四指切页在 dispatchTouchEvent 里改 pageState，这里双向同步
        androidx.compose.runtime.LaunchedEffect(page) {
            pageState = page
            // 页面切了就换该模块的皮肤（成宇 2026-11-03「看看为什么不能映射触摸页和键盘页」）：
            // 皮肤是按模块选的，强调色却只有一份 Miuix 配色 —— 在切页这一处收敛，
            // 比让每个页面各自 sync 更不容易漏。skinAccent 是 Compose 状态，写它会重组。
            com.allperiph.ui.compose.ApAccentSync.apply(
                this@ApShellActivity,
                when (page) {
                    1 -> com.allperiph.ui.ApSkin.TOUCHPAD
                    2 -> com.allperiph.ui.ApSkin.KEYBOARD
                    else -> com.allperiph.ui.ApSkin.TOUCHPAD
                },
            )
        }
        androidx.compose.runtime.LaunchedEffect(pageState) {
            if (page != pageState) page = pageState
        }
        var pickerOpen by remember { mutableStateOf(pickOnStart) }
        // 低栏长按弹出的「快捷入口」开关（内容见文件末尾 QuickActionsDialog）。
        // ⚠️ 必须在 Box 之前声明：弹窗判定在 Box 内容 lambda 内（前向引用不成立）。
        var quickActionsOpen by remember { mutableStateOf(false) }
        // 悬浮栏收/展状态（成宇 2026-11-07 第 4 条「上滑收起成球、下滑或点球展开」）。
        // 根 Box 的单指观察手势写它；低栏内容按它在胶囊/小球之间切换。
        var barCollapsed by remember { mutableStateOf(false) }
        // —— 低栏拖动移位已迁往服务层悬浮栏（issue#168 2026-11-10 22:46「悬浮栏拖动卡顿
        // 不跟手，改为和悬浮球一个实现方案」）——
        // 壳内低栏是 Activity 窗口里的 Compose 节点，拖动靠每帧重组 offset，真机上就是
        // 「卡顿不跟手」；与球同一套实现的移位只能住 WindowManager overlay（见
        // FloatingBallService 的悬浮栏：同一套按住跟手 / 贴边吸附 / 单击恢复，位置落盘）。
        // 壳内低栏保留：收/展 + 进操控页自动收起（导航语义不变）+ 长按快捷入口；
        // 主壳在前台期间服务层悬浮栏会自行收起，两套栏不会同屏。
        // 进入触屏/键盘页自动收起低栏（成宇 2026-11-10「悬浮低栏进入触屏页或键盘页要自动
        // 隐藏」）：这两页是全屏操控面，胶囊压着最常用的操作区 —— 切进来就自动收成球，
        // 回到状态/设置页再自动展开。手动收展仍然可用：本 effect 只在页码变化时落一次，
        // 用户点球展开后不会被立刻按回去，直到再次切页。
        androidx.compose.runtime.LaunchedEffect(page) {
            barCollapsed = page == 1 || page == 2
        }
        // 方案4：让位量跟收/展联动（动效走 ApMotion 进入曲线 200ms，与 barFx 同步，球缩与
        // 内容回涨一次动画到位）。收起后只剩一枚 48dp 玻璃球浮在内容上，页面让位
        // 收到 8dp —— 竖屏多出 80dp、横屏多出 88dp 可用高度。页面侧 bottomGap 只管
        // 低栏让位，手势条避让走 insets.bottom（StatusPage:94），收到 8dp 安全。
        // ⚠️ 键盘页横屏 40dp 特判不动：球与胶囊同角位、下沿高度相近，仍需避让修饰键排。
        val bottomGap by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (barCollapsed) 8.dp else if (landscape) 24.dp else 88.dp,
            // 界面稿 08 质感层：禁弹簧（原 LowBouncy 会回弹），进入曲线 200ms，
            // 设置页「动效」关掉时 durationMs = 0ms（直接落位）。
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis =
                    com.allperiph.ui.miuix.ApMotion.durationMs(this@ApShellActivity, 200),
                easing = com.allperiph.ui.miuix.ApMotion.EnterEasing,
            ),
            label = "bottomGap",
        )
        val topGap by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (landscape) (if (barCollapsed) 8.dp else 96.dp) else 0.dp,
            // 同 bottomGap：进入曲线 200ms、无回弹（界面稿 08）。
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis =
                    com.allperiph.ui.miuix.ApMotion.durationMs(this@ApShellActivity, 200),
                easing = com.allperiph.ui.miuix.ApMotion.EnterEasing,
            ),
            label = "topGap",
        )
        androidx.compose.runtime.LaunchedEffect(pickOnStart) { if (pickOnStart) pickerOpen = true }
        // 返回键：**全壳只有这一处策略**（审查条 2.6 + v5 §9.2-C 的修正）。
        //
        // 前一版把「先收悬浮层、否则弹退出确认」注册在 onCreate 的 onBackPressedDispatcher 上，
        // 而这里的 Compose BackHandler 是组合期后注册的 —— OnBackPressedDispatcher 后注册先响应，
        // 于是只要不在状态页，返回键只做了「回状态页」，扇形菜单与触控板/键盘浮窗留在屏幕上；
        // 只有恰好停在状态页时才会走到收层逻辑。现在按优先级在一处走完：
        //   悬浮层 → 设备选择器 → 回状态页 → 退出确认。
        // 设置页内部的分类归零仍由 SettingsPage 自己的 BackHandler 处理（它持有 cat 状态）。
        BackHandler(enabled = true) {
            if (com.allperiph.ui.FloatingBallService.collapseOverlays()) return@BackHandler
            if (pickerOpen) {
                pickerOpen = false
                return@BackHandler
            }
            if (page != 0) {
                page = 0
                pageState = 0
                return@BackHandler
            }
            showExitAsk.value = true
        }
        // 横屏进触控板页时持有三指遮蔽，离开即释放（旧 MainActivity 的持有者标识是 "main"）
        androidx.compose.runtime.LaunchedEffect(landscape, page) {
            if (landscape && page == 1) {
                com.allperiph.touchpad.GestureShield.acquireWithHint(
                    this@ApShellActivity, "main",
                )
            } else {
                com.allperiph.touchpad.GestureShield.releaseSoon(this@ApShellActivity, "main")
            }
        }
        // 重力方向判读：旧版「横持 / 竖持自动切页**不受系统方向锁限制**」靠的就是自己判重力，
        // 而不是读 configuration（读 configuration 时开方向锁就永远不切）。这里补上：
        // ACCELEROMETER 的 x/y 绝对值谁大就是横/竖，翻转要过 1.5 m/s² 余量（约 45°±5° 滞回），
        // 传感器缺失时退回 configuration（`gravityLandscape == null`）。
        var gravityLandscape by remember { mutableStateOf<Boolean?>(null) }
        androidx.compose.runtime.DisposableEffect(Unit) {
            val sm = getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            val acc = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            val listener = if (acc == null) null else object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    val ax = kotlin.math.abs(e.values[0])
                    val ay = kotlin.math.abs(e.values[1])
                    if (ax + ay < 5f) return          // 手机平放/晃得太厉害时不判方向
                    gravityLandscape = when (gravityLandscape) {
                        null -> ax > ay
                        true -> !(ay > ax + 1.5f)
                        false -> ax > ay + 1.5f
                    }
                }

                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
            }
            if (sm != null && listener != null) {
                sm.registerListener(listener, acc, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            }
            onDispose { if (sm != null && listener != null) sm.unregisterListener(listener) }
        }
        val orientationLandscape = gravityLandscape ?: landscape

        // ★ 2026-11-04（成宇「横竖屏切换好像被你强制了」）：自动切页**默认关闭**。
        //
        // 这一段是旧 Views 版的行为，迁 Compose 时被特意补回来（b6dbdca）。问题是它读的是
        // 加速度计、**不看系统方向锁** —— 用户明明在系统里开着「竖屏锁定」，躺着坐着把手机
        // 转一下，页面照样从触控板跳到键盘页。用户的原话就是「横竖屏切换好像被你强制了」。
        //
        // 现在的口径：
        //   · 版式朝向交回系统（OrientLock 关锁时是 UNSPECIFIED，见那个对象的 KDoc）；
        //   · 自动切页改成一个**显式开关**（设置页「快捷键与外设 → 通用 → 横持自动切到键盘页」），
        //     默认关 —— 「我不动，你就别动」；
        //   · 打开后仍按原来的滞回逻辑工作（给需要边走边用手势面的用户）。
        //
        // 旧实现的注释保留在下面这段里，行为差异只在这里，别处不要各写一套。
        //
        // 横持 / 竖持自动切页（旧 Views 版的既有行为，迁 Compose 时整段丢了）：
        // 横持 → 键盘页，竖持 → 触控板页；**停在状态页（0）与设置页（3）时不抢方向**，
        // 免得用户正打字/正改设置被翻走。只做这两页之间的往返，其它页不动。
        // 节流 1.5s：旋转过程中传感器会连报，不节流会来回抖。
        // 方向锁定：开启时重力判读不再抢页（见 OrientLock 的 KDoc：锁 = 什么都别动）。
        // **每次组合都现读偏好**，不要缓存成字段：设置页是在同一个 Activity 里拨开关的，
        // 缓存只在 onResume 更新的话，本会话里重力判读还按旧值跑 —— 用户开了「锁定」、
        // 回到触控板页一转手机，页面照样被换成键盘页，得退到后台再回来才生效
        // （审查 v5 §9.2-A）。切页会重组 Shell，所以现读的值能跟上开关。
        val locked = OrientLock.isOn(this@ApShellActivity)
        // 横持自动切页的显式开关（默认关；见上面这段的说明）。
        // 与 locked 同口径：**每次组合现读偏好**，不要 remember 缓存 ——
        // 设置页是在同一个 Activity 里拨这个开关的，缓存了就要退后台再回来才生效。
        val autoSwap = com.allperiph.ui.OrientLock.autoSwapEnabled(this@ApShellActivity)
        androidx.compose.runtime.LaunchedEffect(orientationLandscape, locked, autoSwap) {
            if (locked || !autoSwap) return@LaunchedEffect
            val target = when {
                orientationLandscape && (page == 0 || page == 1) -> 2
                !orientationLandscape && page == 2 -> 1
                else -> -1
            }
            if (target >= 0 && target != page) {
                kotlinx.coroutines.delay(1500)
                page = target
                pageState = target
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 背景层（成宇 2026-11-03「设置里的背景图也要挪到主题外观分组」+
                // 「背景图可以动态的吗」）：**主壳此前没有背景层** —— 根 Box 画的是纯
                // Miuix 主题底色，设置页选的相册图 / 预设底色 / 动态开关全部只落到
                // ApBackdrop 的偏好里，四个页一律看不见，用户看到的就是「设了没反应」。
                // rememberApBackdrop 返回 drawBehind，画在子内容之下、**不新增层级**；
                // 口径与 Views 侧 Backdrop 一致：自定义图 > 预设底色 > fallback（主题底色）。
                .then(rememberApBackdrop(this@ApShellActivity, shellBg))
                // 单指上下滑收/展悬浮栏（成宇 2026-11-07 第 4 条）：上滑→收成球、下滑→展开。
                // · Initial pass 只观察不消费：页面滚动、按钮点击、低栏长按全部照常；
                // · 96dp 竖向位移且竖 ≥ 2×横 且全程单指才触发 —— 点按/斜滑/双指滚动不会误收；
                // · 触控板页（page==1）豁免：那页单指滑动是移光标，收栏会跟光标乱跳；
                //   键盘页不豁免 —— 打字是离散点按，够不到 96dp 的竖向阈值。
                .pointerInput(page) {
                    if (page != 1) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val sx = down.position.x
                            val sy = down.position.y
                            var fired = false
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                if (ev.changes.isEmpty()) break
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                    ?: ev.changes.first()
                                if (!c.pressed) break
                                if (fired) continue
                                val dy = c.position.y - sy
                                val dx = c.position.x - sx
                                if (ev.changes.count { it.pressed } == 1 &&
                                    kotlin.math.abs(dy) > 96f * density &&
                                    kotlin.math.abs(dy) > 2f * kotlin.math.abs(dx)
                                ) {
                                    fired = true
                                    barCollapsed = dy < 0
                                }
                            }
                        }
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // topPad 此前定义了却没接到这里：真机（v223.22）横屏顶部因此多垫了一条状态栏高的空档。
                    // 这里回到 topPad 的意图：横屏为 0（内容画到状态栏之下，顶栏是玻璃叠层），
                    // 竖屏才垫状态栏，否则第一张卡片会被时间/电量压住。
                    .padding(top = topPad),
            ) {
                // 顶栏是玻璃叠层、底栏是浮动导航条，两者都盖在内容之上：
                // 这里给内容留出 64dp（顶栏高）+ 96dp（底栏 + 反馈 #52 第 4 条要求的横屏留白）
                // 顶栏玻璃与浮动底栏都盖在内容之上：64dp 顶栏与 bottomGap 底栏空档改由
                // 各页面在内容首尾自留（滚动内容从栏下穿过，可视/可操作面积不再被裁成
                // 中间一条 —— v223.23 真机反馈「底栏和顶栏把可操作区域压的很小」，
                // 旧实现容器级 padding 让滚动内容也被压缩，设置页底部更是双倍留白）。
                // 触控板页手势面连 64dp 都不留，直接全屏。
                // 页切换动效（2026-11-07）：此前 `when(page)` 是硬切 —— 四页之间跳变
                // 没有任何过渡，观感上像换了个应用。这里只给**新页**做一次 12dp 上浮 + 淡入
                // （不做交叉淡出：两页同时在场会让触控板/键盘页的手势订阅短暂翻倍）。
                // 时长走 ApMotion.durationMs，设置页「动效」关掉时是 0ms（直接落位）；
                // 200ms（界面稿：时长只用 150/180/200，原 190 归档）。
                val pageIn = remember { Animatable(1f) }
                androidx.compose.runtime.LaunchedEffect(page) {
                    pageIn.snapTo(0f)
                    pageIn.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = com.allperiph.ui.miuix.ApMotion.durationMs(
                                this@ApShellActivity, 200,
                            ),
                            easing = com.allperiph.ui.miuix.ApMotion.EnterEasing,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .layerBackdrop(navBackdrop)
                        .apHazeSource(hazeState)
                        .graphicsLayer {
                            val v = pageIn.value
                            alpha = 0.55f + 0.45f * v
                            translationY = (1f - v) * 12f * density
                        },
                ) {
                    when (page) {
                        0 -> StatusPage(
                            ctx = this@ApShellActivity,
                            // v223.29.3：总闸与快捷入口都搬走了 —— 状态页不再需要
                            // running / onToggleRunning / onNavigate 三个入参，
                            // 运行态由页面自己现读 AgentController.running。
                            topBar = topGap,
                            bottomGap = bottomGap,
                        )
                        1 -> TouchpadPage(
                            ctx = this@ApShellActivity,
                            onGesture = { ev -> touchpadModule()?.feed(ev) },
                            onLongPressArmDrag = { touchpadModule()?.armDrag() },
                            onLongPressRightDrag = { touchpadModule()?.armRightDrag() },
                            topBar = topGap,
                            bottomGap = bottomGap,
                        )
                        // 成宇 2026-11-03「键盘页可以再往上点」：横屏 96dp 的顶部让位是给
                        // **右上角**那枚低栏留的，但低栏只占右半屏，键盘页整块跟着下移 96dp
                        // 白白丢掉一截高度。这里横屏只留 40dp（页签行仍在低栏左侧，不会被压住）。
                        //
                        // ⚠️ 2026-11-04 成宇「横屏时低栏挡了两个按键不能用」的修正：
                        // 之前这里是 24dp，低栏胶囊的**下沿落在 58dp**（8dp 顶部间隙 + 48dp 胶囊
                        // + 描边），而修饰键排（Ctrl/Alt/Shift/Win）的顶部只到 54dp ——
                        // 真机 uiautomator 实测低栏胶囊 [1780,30]-[2464,174] 与 Shift
                        // [1319,162]-[1911,319] 重叠 12px、与 Win [1941,162]-[2533,319] 重叠更多，
                        // 用户看到的就是「Shift / Win 被低栏压着，点不动」。
                        // 40dp 让修饰键排的顶部落到 40 + 30（页签行）= 70dp > 低栏下沿 56dp，
                        // 留出 14dp 呼吸；40 - 24 = 16dp 的代价摊到 6 行键位，每行只少 2.7dp，
                        // 仍在 48dp 命中下限之上（横屏 400dp 高时每行约 48dp）。
                        2 -> ApKeyboardPage(
                            topBar = if (landscape) 24.dp else topGap,
                            bottomGap = bottomGap,
                        )
                        else -> SettingsPage(
                            ctx = this@ApShellActivity,
                            onRecreate = { recreate() },
                            topBar = topGap,
                            bottomGap = bottomGap,
                        )
                    }
                }
            }

            // 顶栏整块去除（成宇 2026-11-02「顶栏不要留东西了，没有状态栏没有连接设备的地方」）：
            // 标题上一版已去掉，剩下的只是一条 64dp 玻璃底 —— 它还占着屏且没有承载任何
            // 信息（设备选择已移到设置页「当前控制」下方，不再靠点顶栏进）。保留 apHazeSource
            // 的采样层即可，玻璃效果由底栏继续使用。

            // 低栏长按唤出的快捷入口（成宇 2026-11-04）：从状态页整块搬来这里 ——
            // 那排按钮在状态页占着一张卡片，实际更需要的是「随手能叫出来」，
            // 而低栏长按本来就是「常用功能」的口子。
            if (quickActionsOpen) {
                QuickActionsDialog(
                    ctx = this@ApShellActivity,
                    onDismiss = { quickActionsOpen = false },
                    onOpenTouchpad = {
                        quickActionsOpen = false
                        page = 1
                        pageState = 1
                    },
                )
            }

            // 受控设备选择器（从旧 MainActivity.showDevicePicker 迁来）：列表来自
            // TvDiscovery 的广播发现，选中即连入 9511；「本机 / PC」走 ControlTarget.clear()
            if (pickerOpen) {
                ApDevicePicker(onDismiss = { pickerOpen = false })
            }

            // 退出确认（v223.28）：三个动作都明确 —— 「后台继续」不打断正在跑的外设，
            // 「停止并退出」走与状态页关闭开关同一条 toggleAgent(false)。
            if (showExitAsk.value) {
                ApComponents.DialogCard(onDismiss = { showExitAsk.value = false }) {
                    Text(text = stringResource(R.string.ui_exit_title), fontSize = 17.sp)
                    Text(
                        text = stringResource(R.string.ui_exit_msg),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    ApComponents.ActionButton(
                        text = stringResource(R.string.ui_exit_keep),
                        onClick = {
                            showExitAsk.value = false
                            finish()
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    ApComponents.ActionButton(
                        text = stringResource(R.string.ui_exit_stop),
                        onClick = {
                            showExitAsk.value = false
                            toggleAgent(false)
                            finish()
                        },
                    )
                    ApComponents.ActionButton(
                        text = stringResource(R.string.ui_exit_cancel),
                        onClick = { showExitAsk.value = false },
                    )
                }
            }

            // 底栏 = 悬浮玻璃胶囊（用户 2026-11-02 的原话是「悬浮低栏要和当初组件预览里
            // 一样的」；那个预览页已随 2026-11-04 归档删除，**做法全部保留**在这段与
            // LayerBackdrop / Highlight 三件套里）：FloatingNavigationBar 胶囊 +
            // Miuix textureBlur 采样内容层（LayerBackdrop）+ Highlight 玻璃描边；
            // 无运行时着色器的老机器退化为纯色胶囊。
            // 0.9.4 契约：FloatingNavigationBar 的 modifier 作用于内层胶囊 Row，根 Column 固定
            // fillMaxWidth 且不接定位 —— 垂直定位由外层 Box align(BottomCenter) 负责，
            // 离底高度组件自管（26dp + 导航栏 inset），不要往胶囊 modifier 里塞 padding。
            val navSurface = MiuixTheme.colorScheme.surfaceContainer
            val navGlass = isRuntimeShaderSupported() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val navDark = remember(shellBg) {
                0.2126f * shellBg.red + 0.7152f * shellBg.green +
                    0.0722f * shellBg.blue < 0.5f
            }
            // 长按悬浮低栏 → 唤出悬浮球的扇形常用功能（成宇 2026-11-02
            // 「常用功能可以通过长按悬浮低栏呼唤出来」）。
            // 复用悬浮球自己的扇形菜单：菜单项清单、图标、开关态、分发逻辑都只有一份，
            // 低栏不再另造一套「常用功能」面板。球不在屏幕上（用户关过悬浮球 / B 服务没起来）
            // 则给一句明确解释，而不是长按毫无反应。
            // 横屏时低栏挪到右上角（成宇 2026-11-02「横屏后悬浮低栏可以悬浮在右上角」）：
            // 横屏本来横向就宽，底栏居中会压住键盘页/触控板页底部那条最常用的区域；
            // 挪到右上角后既避开拇指区，也避开横屏时右侧的快捷键列。
            // 竖屏保持底部居中不动（拇指可及性是竖屏的第一需求）。
            val barAlign = if (landscape) Alignment.TopEnd else Alignment.BottomCenter
            // ⚠️ 光给 Box 传 align(TopEnd) 是**不够**的：FloatingNavigationBar 的根
            // Column 固定 `fillMaxWidth()`，它在父 Box 里已经占满整行宽度，水平方向
            // 怎么对齐都不会动。真正决定胶囊位置的是组件自己的 `horizontalAlignment`
            // （见上游 NavigationBar.kt：`.align(horizontalAlignment)` + 只有 End 那一侧
            // 才加 36dp 内侧留白）。所以横屏必须把 End 一起传下去，否则「挪到右上角」
            // 只会变成「贴到最上面、仍然水平居中」。
            val barHorizontal = if (landscape) Alignment.End else Alignment.CenterHorizontally
            // ⚠️ 这层 Box 必须自己限宽：FloatingNavigationBar 的根 Column 是 fillMaxWidth，
            // 在不受限的 Box 里会把宽度撑满整行 —— 于是这枚带长按手势的 Box 在横屏时变成
            // 「整条顶部横带」，把左上角设置页分类列的点击全吃了（成宇 2026-11-03
            // 「横屏时设置页分组不能切换了」，真机 input tap 复现：页签能点、分组点不动）。
            // 限到 440dp 后触摸判定跟胶囊走，横屏左列、竖屏底栏两侧的内容恢复可点。
            // 低栏收/展动效（2026-11-07）：收起成球 / 展开成胶囊此前是一帧硬切 ——
            // 这是屏幕上唯一的常驻控件，跳变最扎眼。给一次缩放淡入
            // （ApMotion 进入曲线，界面稿 08 禁弹簧 —— springSpec 已改为无回弹 tween）。
            val barFx = remember { Animatable(1f) }
            androidx.compose.runtime.LaunchedEffect(barCollapsed) {
                barFx.snapTo(0.84f)
                barFx.animateTo(
                    targetValue = 1f,
                    animationSpec = com.allperiph.ui.miuix.ApMotion.springSpec(
                        this@ApShellActivity,
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .align(barAlign)
                    // 横屏顶栏贴到屏幕上沿时，8dp 会落进大圆角/挖孔的弧内（真机横屏截图里
                    // 胶囊左上角被屏幕弧切掉一截）。这里叠上挖孔让位量 topPad。
                    .then(if (landscape) Modifier.padding(top = 8.dp + topPad) else Modifier)
                    .widthIn(max = 440.dp)
                    .graphicsLayer {
                        val v = barFx.value
                        alpha = 0.72f + 0.28f * v
                        scaleX = v
                        scaleY = v
                    }
                    .pointerInput(landscape) {
                        // 低栏手势（2026-11-11 收敛）：只剩「按住不动 650ms → 长按弹快捷入口」。
                        // 拖动移位已迁往服务层悬浮栏（本文件上方注释）：壳内 Compose 节点拖动
                        // 靠每帧重组 offset，真机卡顿不跟手 —— 跟手版本只能住 WindowManager
                        // overlay（与球同一套实现）。移动超 slop 直接放行，不拦事件。
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val start = down.position
                            val startMs = System.currentTimeMillis()
                            var longPressFired = false
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                // ⚠️ 长按只在**单指**下成立（成宇 2026-11-04「挡了两个按键
                                // 不能用」）：这根 pointerInput 挂在低栏外层 Box 上，且
                                // `requireUnconsumed = false` —— 横屏低栏的命中区正好贴着修饰键
                                // 排，键盘按键的按下它**也会收到**（不消费，键帽照常能按）。
                                // 两根手指同时按 Shift + Win 时这里会误判，第二根手指落下即放弃。
                                if (ev.changes.count { it.pressed } > 1) break
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                    ?: ev.changes.firstOrNull()
                                if (c == null) continue
                                if (!c.pressed) break
                                if ((c.position - start).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    // 按住后移动：不是长按（拖动语义归服务层悬浮栏），整个手势放弃
                                    break
                                }
                                if (System.currentTimeMillis() - startMs >= 650L) {
                                    // 长按成立：把剩余序列在 Initial pass 里标记消费，
                                    // 页签的 clickable 在 Main pass 看到已消费就不会再当点击
                                    longPressFired = true
                                    while (true) {
                                        val e2 = awaitPointerEvent(PointerEventPass.Initial)
                                        e2.changes.forEach { it.consume() }
                                        if (e2.changes.none { it.pressed }) break
                                    }
                                    break
                                }
                            }
                            if (longPressFired) {
                                // Shell() 里没有 LocalContext 的 `ctx`：这个函数有自己的
                                // this@ApShellActivity（Activity 本身就是 Context），
                                // 直接用它，别再引入一个可能为 null 的局部变量。
                                val act = this@ApShellActivity
                                // 成宇 2026-11-04「悬浮低栏长按没震动」：长按虽然成立了，手感上
                                // 却没有任何反馈 —— 补一下震动（与页签的按键反馈同一条 Feedback
                                // 链路，尊重设置里的震动档位，档位是「关」时不震）。
                                com.allperiph.ui.Feedback.haptic(act)
                                // 成宇 2026-11-04「弹出应该是状态页的快捷入口的弹窗，再把状态页的
                                // 移除」：长按不再借用悬浮球的扇形菜单 —— 那套与球自身的 12 项配置
                                // 耦合、球没开时还会变成「长按没反应」；改用本壳自己的快捷入口弹窗，
                                // 内容就是原先状态页那一段六个入口，任何情况下都能弹出。
                                quickActionsOpen = true
                            }
                        }
                    },
            ) {
                if (barCollapsed) {
                    // 收起态：一枚 48dp 小球顶在原角位（成宇 2026-11-07 第 4 条），点按展开。
                    // 竖屏补 26dp 底距 = 低栏组件自管的离底高度；横屏补 20dp 侧距与低栏
                    // End 侧内留白对位（外层 Box 的横屏 top 8dp 已由父级给出，这里不重复）。
                    Box(
                        modifier = Modifier
                            .then(
                                if (landscape) Modifier.padding(end = 20.dp)
                                else Modifier.padding(bottom = 26.dp),
                            )
                            .size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            // 收起球的可访问性描述（48dp 可点元素必须有描述，UI 规范硬规则）
                            .semantics {
                                contentDescription = getString(R.string.ui_dock_ball_desc)
                            }
                            // 方案4·玻璃化：实心 surfaceContainer → 半透明渐变玻璃 +
                            // 1dp 高光描边（与展开胶囊同一套质感，shader-free 老机器同款）——
                            // 收起后球浮在内容上、内容可透出，不再是压着界面的实心圆。
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(
                                        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f),
                                        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.46f),
                                    ),
                                ),
                            )
                            .border(
                                width = 1.dp,
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = if (navDark) 0.16f else 0.52f),
                                        Color.White.copy(alpha = if (navDark) 0.05f else 0.14f),
                                    ),
                                ),
                                shape = androidx.compose.foundation.shape.CircleShape,
                            )
                            .clickable { barCollapsed = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "≡", fontSize = 17.sp, color = MiuixTheme.colorScheme.onSurface)
                    }
                } else {
                // （else 分支内容沿用原缩进：收/展只切低栏 Box 的内容，不动布局结构）
                FloatingNavigationBar(
                    horizontalAlignment = barHorizontal,
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        // 低栏页签的「按下即震 + 长按确认」（成宇 2026-11-04「长按按键没震动」）：
                        // 挂在 FloatingNavigationBar 根上，四个页签一起生效 —— 与键帽同一套手势，
                        // 一次只震一下（页签自身没有 Feedback.tap，不会重复）。
                        // ⚠️ 与上面那根 pointerInput 的分工：那根管「长按低栏弹快捷入口」并会
                        // 在成立后 consume，也负责长按那一下震动；这根只给「按下即震」，
                        // 长按确认关掉（longPressFeel = false），免得按住低栏震两下。
                        .apKeyFeel(longPressFeel = false)
                        .then(
                            if (navGlass) {
                                Modifier.textureBlur(
                                    backdrop = navBackdrop,
                                    shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
                                    blurRadius = 25f,
                                    colors = BlurDefaults.blurColors(
                                        blendColors = listOf(
                                            BlendColorEntry(color = navSurface.copy(alpha = 0.4f)),
                                        ),
                                    ),
                                    highlight = if (navDark) Highlight.GlassStrokeMiddleDark
                                    else Highlight.GlassStrokeMiddleLight,
                                )
                            } else {
                                Modifier
                            }
                        ),
                    color = if (navGlass) Color.Transparent else navSurface,
                ) {
                    FloatingNavigationBarItem(
                        selected = page == 0,
                        onClick = { page = 0 },
                        icon = MiuixIcons.Home,
                        label = stringResource(R.string.ui_nav_status),
                        enabled = true,
                    )
                    FloatingNavigationBarItem(
                        selected = page == 1,
                        onClick = { page = 1 },
                        icon = ImageVector.vectorResource(R.drawable.ic_apx_touchpad),
                        label = stringResource(R.string.ui_nav_touchpad),
                        enabled = true,
                    )
                    FloatingNavigationBarItem(
                        selected = page == 2,
                        onClick = { page = 2 },
                        icon = ImageVector.vectorResource(R.drawable.ic_apx_keyboard),
                        label = stringResource(R.string.ui_nav_keyboard),
                        enabled = true,
                    )
                    // 图标（成宇 2026-11-02「悬浮低栏的图标不对」）：触控板配格子、键盘配列表，
                    // 一眼看不出是什么 —— 这两页改用自家矢量 ic_apx_touchpad / ic_apx_keyboard
                    // 经 vectorResource 转 ImageVector（单色描边，随选中态着色）；状态 / 设置
                    // 语义中性，保留 miuix-icons 的 Home / Settings（extended 156 个里没有
                    // Touchpad/Keyboard 这类设备语义名，自家矢量反而是现成的）。
                    FloatingNavigationBarItem(
                        selected = page == 3,
                        onClick = { page = 3 },
                        icon = MiuixIcons.Settings,
                        label = stringResource(R.string.ui_nav_settings),
                        enabled = true,
                    )
                }
                }
            }
        }
    }

    /**
     * 四指左右滑切页（横屏换页方式，避开触控板自身一到三指手势）。
     *
     * 走 [dispatchTouchEvent] 而不是 Compose 的 pointerInput：键盘页是 AndroidView 包着的
     * `KeyboardPanels`，按键会吃掉事件序列，只有分发第一站能稳定看到四指。
     * 与旧 `MainActivity` 同一套阈值（56dp）与循环顺序。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (adaptiveState?.landscape == true) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    fourStartX = -1f
                    fourSwiped = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                    if (ev.pointerCount >= 4 && fourStartX < 0f) {
                        fourStartX = ev.x
                        fourSwiped = false
                    }
                android.view.MotionEvent.ACTION_MOVE ->
                    if (fourStartX >= 0f && !fourSwiped && ev.pointerCount >= 4) {
                        val dx = ev.x - fourStartX
                        if (kotlin.math.abs(dx) > 56f * resources.displayMetrics.density) {
                            fourSwiped = true
                            val order = intArrayOf(1, 2, 3)   // 触控板 → 键盘 → 设置
                            val cur = order.indexOf(pageState).coerceAtLeast(0)
                            pageState = if (dx < 0) {
                                order[(cur + 1) % order.size]
                            } else {
                                order[(cur + order.size - 1) % order.size]
                            }
                        }
                    }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun touchpadModule(): TouchpadModule? =
        AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule

    /** 与旧 `btnStart` 同一组副作用：模块编排 + 前台服务 */
    private fun toggleAgent(want: Boolean) {
        if (want) {
            AgentController.build(this)
            AgentController.startEnabled(this)
            if (AgentController.TRANSPORTS.any { AgentController.isTransportEnabled(this, it) }) {
                AgentForegroundService.start(this)
            }
        } else {
            AgentController.stopAll()
            AgentForegroundService.stop(this)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 悬浮球转屏重排直调（2026-11-11 成宇「悬浮球收缩后，旋转屏幕会乱跑」）：
        // 本壳声明了 configChanges，转屏回调这里必收到；app 级注册的服务回调在部分
        // ROM 上不保证派发，改由主界面在场时保底推一次。内部幂等：尺寸没变是空跑。
        com.allperiph.ui.FloatingBallService.reflowNow()
    }

    override fun onResume() {
        super.onResume()
        // 方向锁定：设置页改完返回时偏好已变，这里按偏好重新钉/放（幂等）。
        // 重力判读的门禁在 Shell 组合期现读偏好，不经过这里。
        OrientLock.apply(this)
        // 剪贴板同步（旧主壳里由 MainActivity 挂载，迁到新宿主后同一个入口）
        com.allperiph.ui.ClipboardSync.register(this)
        // issue#168 建议 11 的回前台兜底（v223.29.50）：onStop 已进后台时
        // startForegroundService 可能被系统拒，服务不在的球就在回前台这一刻补拉
        // （前台启动无限制）。内部幂等：服务活着 / 意愿位关着都是 no-op。
        runCatching { com.allperiph.ui.FloatingBallService.ensureRunningIfWanted(this, force = true) }
        // 服务层悬浮栏（issue#168 2026-11-10 22:46）：主壳在前台期间收栏 ——
        // 壳内已有同职能的低栏（导航 + 快捷入口），两套栏同屏只会互相打架。
        runCatching { com.allperiph.ui.FloatingBallService.notifyShellForeground(true) }
    }

    override fun onPause() {
        com.allperiph.ui.ClipboardSync.unregister(this)
        // 离开主壳即还栏：服务层悬浮栏是「桌面 / 其它应用里」的操控面入口（见 onResume）。
        runCatching { com.allperiph.ui.FloatingBallService.notifyShellForeground(false) }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // issue#168 建议 11：返回桌面 / 切到其它 app 时自动把悬浮球放回屏上。
        // 两级自愈（v223.29.50 补课）：服务活着走进程内补球（不碰服务启动）——
        // Android 12+ 起后台 startForegroundService 会被系统拒绝，v223.29.48 在真机
        // 上正是死在这里（runCatching 吞掉，只剩「切了后台球不出来」）；
        // 服务不在才退回服务级拉起（老系统可用，新系统被拒则等 onResume 回前台兜底）。
        runCatching { com.allperiph.ui.FloatingBallService.restoreBallIfNeeded(this) }
        runCatching { com.allperiph.ui.FloatingBallService.ensureRunningIfWanted(this, force = true) }
    }

    override fun onDestroy() {
        // 退出主壳（返回确认 / 最近任务划掉）时把悬浮球的展开层一起收掉：
        // 只认 isFinishing —— 旋转/重建时不该动用户的悬浮面板。
        if (isFinishing) com.allperiph.ui.FloatingBallService.collapseOverlays()
        super.onDestroy()
    }

    /**
     * 全屏沉浸：状态栏 / 导航栏隐藏 + 两件主壳独有的窗口设置。
     *
     * ⚠️ 2026-11-04 收敛：核心三件（`setDecorFitsSystemWindows(false)` →
     * `decorView.post { behavior + hide }`）此前在这里**又抄了一份** ——
     * 连「insetsController 在 DecorView 未创建时抛 NPE，必须 post」那段闪退修复
     * （v223.20 首启崩溃）的注释都各写了一遍，而 [com.allperiph.ui.miuix.hideSystemBars]
     * 已被 8 个子页（FilePanel / Send / Gamepad / Remote / Screen / RemoteInput /
     * Touchpad / 等）共用，它自己的 KDoc 就警告过「抄第二遍迟早漏一处」。
     * 现在主壳只留自己那两件，三件套走公共扩展 —— 改全屏口径只动 ApInsets 一处。
     * 顺序变化（先 statusBarColor/cutout 再 setDecorFits）不影响行为：三者都是
     * onCreate 同步段里的窗口属性设置，hide 仍在 post 里执行（NPE 防护原样保留）。
     */
    private fun applyImmersive() {
        // 状态栏颜色透明；允许布局延伸到挖孔短边（横屏时刘海在左右侧）。
        // 成宇 2026-11-02「所有页屏蔽状态栏」：**隐藏**状态栏与导航栏，四个页
        // （状态 / 触控板 / 键盘 / 设置）都不再露系统时间与电量。此前这里主动
        // c.show(statusBars())，竖屏能看见状态栏，叠上玻璃顶栏就是两层顶部占位。
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyEdgeToEdge()
    }
}

/** ApSkin → MiuixTokens 的一次性同步（皮肤切换后要重新调用） */
internal object ApAccentSync {
    /**
     * 把某一模块的皮肤同步给 Miuix 配色。
     *
     * ★ 必须**按页调用**（见下面 setContent 里的 LaunchedEffect(page)）：
     * 此前这里写死 `ApSkin.TOUCHPAD`，且全项目只在 `onCreate` 里调一次 ——
     * 于是「键盘」与「触控板」两套皮肤选了也没有任何界面变化，用户的原话是
     * 「看看为什么不能映射触摸页和键盘页」。根因不是没存下来（偏好一直都在写），
     * 而是**只有触控板那一套在启动时被读出来一次**，其余两套谁都没读。
     *
     * （`ApAccent.syncSkin` 曾是这里的逐字副本、全仓零调用，已于 2026-11-04 删除；
     * 皮肤同步只有本函数一个入口，别再抄。）
     */
    fun apply(ctx: Context, scope: String = ApSkin.TOUCHPAD) {
        val picked = ApSkin.picked(ctx, scope).isNotBlank()
        MiuixTokens.skinAccent = if (picked) {
            androidx.compose.ui.graphics.Color(ApSkin.current(ctx, scope).accent)
        } else {
            null
        }
    }
}


/**
 * 受控设备选择器（`showDevicePicker` 的 Compose 版）。
 *
 * 数据源是 [com.allperiph.wireless.TvDiscovery] 的广播列表（电视/PC/手机三类都在这张表里），
 * 选中即 `ControlTarget` 连入；「本机 / PC」一行等于 `ControlTarget.clear()`。
 * 视觉由 [ApComponents.DialogCard] 承载（它内部已经是 `androidx.compose.ui.window.Dialog`
 * + Miuix `Card`，Miuix 0.9.4 的 `WindowDialog` 是 preference 侧窗口级弹窗，这里不要）。
 * v223.28 去掉此前多套的一层外 `Dialog` —— 两层窗口会叠两层遮罩。
 */
@Composable
private fun ApDevicePicker(onDismiss: () -> Unit) {
    ApComponents.DialogCard(onDismiss = onDismiss) {
        ApDevicePickerBody(onDone = onDismiss)
    }
}

/**
 * 真正连入受控设备（v223.27）。
 *
 * ★ 此前 Compose 版选择器只写了 `ControlTarget.host/label/type` 三个字段，
 *   **从未建立 9511 连接** —— `controlClient` 一直是 null，于是：
 *     · [com.allperiph.touchpad.TouchpadModule] 的 `wirelessReady` 恒为 false，
 *       出口落到 USB／蓝牙／NONE，选了设备手势照样发不出去（成宇报的「触摸板控制不了，
 *       界面不动」）；
 *     · 连「手机被控」也同样不可用 —— 同一个根因，不是 phone 类型缺分支。
 *   建连这一步原先只在已下线的旧界面 `TouchpadActivity.connectTv()` 里做，Compose
 *   迁移时漏掉了。这里按同一口径补回。
 *
 * 顺序仍是「先连、后换」：新连接成功后才断开旧实例 —— 旧实例若不断开，它的
 * readerLoop 会每 2s 无限重连旧 IP，留下 socket 与 tx/ping/reconn 三条线程。
 */
internal fun connectControlledDevice(
    ctx: Context,
    d: com.allperiph.wireless.TvDiscovery.TvDevice,
    onResult: (Boolean, String) -> Unit,
) {
    val ui = android.os.Handler(android.os.Looper.getMainLooper())
    Thread({
        val c = com.allperiph.wireless.TvControllerClient(d.ip, d.port)
        val ok = c.connect()
        ui.post {
            if (ok) {
                // 成宇 2026-11-04「历史链接设备一点就可链接」：连接成功才写历史，
                // 失败的不进列表（否则「一点就连」里会混进连不上的死记录）。
                runCatching {
                    com.allperiph.ui.ConnectHistory.add(ctx, d.name, d.ip, d.port, d.type)
                }
                com.allperiph.wireless.ControlTarget.controlClient
                    ?.takeIf { it !== c }?.disconnect()
                com.allperiph.wireless.ControlTarget.controlClient = c
                com.allperiph.wireless.ControlTarget.host = d.ip
                com.allperiph.wireless.ControlTarget.label = d.name
                com.allperiph.wireless.ControlTarget.type = d.type
                // 出口换了：作废「已观测出口」，否则徽章 / 通知栏 / 0x31 上报在用户碰屏幕
                // 之前仍显示上一个出口（issue#52 第 8 条同款）。
                // ⚠️ 这里**不**顺手打开「无线」传输类：那条开关会一并拉起 WirelessModule
                // → ControlledService（本机被控 + 对外广播信标），是反方向的另一个功能，
                // 静默替用户打开是越权。缺哪一项由状态页的开关与提示如实表达。
                com.allperiph.core.Uplink.reset()
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(R.string.ui_main_toast_done, d.name),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } else {
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(R.string.ui_touchpad_toast_connect_failed, d.ip),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            onResult(ok, d.name)
        }
    }, "apx-connect").start()
}

/**
 * 设备选择器的内容体（v223.25 从 ApDevicePicker 抽出）：主屏弹窗与悬浮球 overlay
 * 面板共用同一份列表与连接逻辑 —— 选中即 [connectControlledDevice] 连入 9511，
 * 「本机 / PC」= clear()。
 *
 * 设备选择器主体（本机 / 被控开关 / 最近连接 / 扫描列表）。三处共用：状态页、
 * 低栏快捷弹窗、悬浮球设备面板。
 *
 * @param landscape 横屏双栏（issue#168 2026-11-11 悬浮球清单）：左=本机 + 被控模式开关
 *   （[footer] 里的关闭按钮也落在左栏底），右=最近连接 + 扫描列表（超高可滚 + 右侧
 *   滚动条）。主屏调用不传参，保持原来的单列竖排。
 * @param listMaxHeightDp 双栏右列的限高（dp）：悬浮球 overlay 是 WRAP_CONTENT 窗口，
 *   没有可靠的窗口高度可依，由调用方按屏幕实高折算传入；≤0 不限高。
 * @param footer 附加在左栏底部的内容（悬浮面板的「关闭」按钮）；竖排模式忽略。
 */
@Composable
internal fun ApDevicePickerBody(
    onDone: () -> Unit,
    landscape: Boolean = false,
    listMaxHeightDp: Int = 0,
    footer: (@Composable () -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var devices by remember { mutableStateOf(emptyList<com.allperiph.wireless.TvDiscovery.TvDevice>()) }
    var connecting by remember { mutableStateOf("") }
    // 连接失败的就地重试（v223.29.37 HIG 落地批次一）：失败不再只有一句转瞬即逝的
    // 提示 —— 选择器里保留失败卡片，附「重试」按钮，用户不用重新翻列表找刚才那台。
    var lastFailed by remember {
        mutableStateOf<com.allperiph.wireless.TvDiscovery.TvDevice?>(null)
    }
    // 历史连接 + 外设模式状态（成宇 2026-11-04「历史链接设备一点就可链接，加入外设模式」）
    var recent by remember {
        mutableStateOf(emptyList<com.allperiph.ui.ConnectHistory.HistoryItem>())
    }
    var controlledOn by remember { mutableStateOf(com.allperiph.controlled.ControlledService.running) }
    // ★ v223.27：这里原来是 start() 之后立刻 list() **只取一次** —— UDP 广播发现要
    //   1~2s 才有第一台设备进来（TvDiscovery 的 devices 表还要等 loop 收到信标才填），
    //   所以弹窗几乎必然显示空表。用户报「找不到设备 / 没有连接设备的地方」有一半在这里。
    //   改成每秒刷新：弹窗开着就能看到设备陆续出现。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.allperiph.wireless.TvDiscovery.start()
        while (true) {
            devices = com.allperiph.wireless.TvDiscovery.list()
            recent = com.allperiph.ui.ConnectHistory.getRecent(ctx, 5)
            controlledOn = com.allperiph.controlled.ControlledService.running
            kotlinx.coroutines.delay(1000L)
        }
    }
    // v223.29.58（issue#168 2026-11-11 悬浮球清单「选择设备页横屏双栏」）：拆左右两栏。
    // 左栏 = 标题 + 本机 + 被控开关（悬浮面板的关闭按钮经 [footer] 也落左栏底）；
    // 右栏 = 最近连接 + 连接中/失败提示 + 扫描列表，超高可滚并挂右侧滚动条；竖屏
    // 保持原有单列竖排，内容与顺序不变。两栏内容各内收 6dp，避免贴边文字被卡片
    // 圆角裁掉（同批「子页面文字内收」）。
    val listScroll = rememberScrollState()
    val leftPane: @Composable () -> Unit = {
        Text(
            text = ctx.getString(R.string.ui_pick_device_title),
            fontSize = 17.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_pick_device_local),
            onClick = {
                com.allperiph.wireless.ControlTarget.clear()
                onDone()
            },
        )
        // —— 外设模式（本机被控）——
        // 此前这个开关只在设置页「快捷键与外设」与触控板页出现，选设备这条动线上没有，
        // 用户要「这台被对方控」时得先离开选择器。这里给一行就地切换（服务启停走
        // ControlledService 自己的开关位，与那些入口是同一条链路）。
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_pick_device_controlled) + "：" +
                ctx.getString(
                    if (controlledOn) R.string.ui_home_controlled_on else R.string.ui_home_controlled_off,
                ),
            onClick = {
                // 震动已由 ApComponents.ActionButton 统一给（2026-11-04 一整套手感），这里不再叠
                if (controlledOn) {
                    com.allperiph.controlled.ControlledService.stop(ctx)
                } else {
                    com.allperiph.controlled.ControlledService.start(ctx)
                }
                controlledOn = com.allperiph.controlled.ControlledService.running
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(
                        if (controlledOn) R.string.ui_home_controlled_on else R.string.ui_home_controlled_off,
                    ),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
        // 悬浮面板的「关闭」按钮落左栏底（横屏时与被控开关同列）。
        footer?.invoke()
    }
    val rightPane: @Composable () -> Unit = {
        // —— 最近连接：点一下就连（不必等广播重新发现）——
        if (recent.isNotEmpty()) {
            Text(
                text = ctx.getString(R.string.ui_pick_device_recent),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
            recent.forEach { h ->
                ApComponents.ActionButton(
                    text = "${h.name}  ${h.host}",
                    onClick = {
                        connecting = h.host
                        val dev = com.allperiph.wireless.TvDiscovery.TvDevice(
                            name = h.name,
                            ip = h.host,
                            port = h.port,
                            lastSeen = h.lastConnected,
                            type = h.type,
                        )
                        connectControlledDevice(ctx, dev) { ok, _ ->
                            connecting = ""
                            lastFailed = if (ok) null else dev
                            if (ok) onDone()
                        }
                    },
                )
            }
        }
        if (connecting.isNotEmpty()) {
            Text(
                text = ctx.getString(R.string.ui_pick_device_connecting, connecting),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        // 连接失败：内联提示 + 就地重试（提示与真实原因对齐，不弹打断式弹窗）
        lastFailed?.let { failed ->
            Text(
                text = ctx.getString(R.string.ui_pick_device_connect_failed, failed.ip),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            ApComponents.ActionButton(
                text = ctx.getString(R.string.ui_pick_device_retry) + " · " + failed.name,
                onClick = {
                    connecting = failed.ip
                    lastFailed = null
                    connectControlledDevice(ctx, failed) { ok, _ ->
                        connecting = ""
                        lastFailed = if (ok) null else failed
                        if (ok) onDone()
                    }
                },
            )
        }
        devices.forEach { d ->
            ApComponents.ActionButton(
                text = "${d.name} ${d.typeLabel}  ${d.ip}",
                onClick = {
                    connecting = d.ip
                    connectControlledDevice(ctx, d) { ok, _ ->
                        connecting = ""
                        lastFailed = if (ok) null else d
                        if (ok) onDone()
                    }
                },
            )
        }
        if (devices.isEmpty()) {
            Text(
                text = ctx.getString(R.string.ui_pick_device_scanning),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
    if (!landscape) {
        // 竖屏：沿用原单列竖排，内容与顺序不变。
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            leftPane()
            rightPane()
        }
    } else {
        // 横屏：左右双栏等宽；右列超高（listMaxHeightDp>0）时滚动，右缘贴滚动条。
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                leftPane()
            }
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .let { if (listMaxHeightDp > 0) it.heightIn(max = listMaxHeightDp.dp) else it }
                        .verticalScroll(listScroll),
                ) {
                    rightPane()
                }
                // 滚动条贴右缘；内容不超高时组件自己不画（VScrollbar 内部判 maxValue）。
                ApComponents.VScrollbar(
                    scrollState = listScroll,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp),
                    alignEnd = true,
                )
            }
        }
    }
}

/**
 * 悬浮球的「设备」面板（成宇 2026-11-02「应该在悬浮窗完成」）：同一份选择器内容
 * 装进 overlay 卡片，不再拉起主 Activity。卡片与按钮照走 ApComponents（UI Kit 口径）。
 * v223.29.58：透传横屏双栏参数（issue#168 2026-11-11 悬浮球清单），「关闭」按钮
 * 经 [ApDevicePickerBody.footer] 落到左栏底，不再独自占整行。
 */
@Composable
internal fun ApDevicePanel(
    onClose: () -> Unit,
    landscape: Boolean = false,
    listMaxHeightDp: Int = 0,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        ApComponents.Card {
            ApDevicePickerBody(
                onDone = onClose,
                landscape = landscape,
                listMaxHeightDp = listMaxHeightDp,
                footer = {
                    ApComponents.NarrowButton(
                        text = stringResource(R.string.ui_help_close),
                        onClick = onClose,
                    )
                },
            )
        }
    }
}

/**
 * 低栏长按唤出的「快捷入口」弹窗（v223.29.3）。
 *
 * 内容与状态页原先那张「快捷入口」卡**一模一样**（成宇 2026-11-04「弹出应该是状态页的
 * 快捷入口的弹窗，再把状态页的移除」）：发送文件 / 发送剪贴板 / 屏幕 / 手柄 / 遥控器 /
 * 编辑快捷键。搬过来的理由：那排按钮在状态页占着一张卡片，实际更需要的是「随手能叫出来」，
 * 而低栏长按本来就是「常用功能」的口子，放在这里才叫随手可达。
 *
 * v223.29.6（成宇 2026-11-04「低栏长按后快捷键管理删除」）：最后一项「编辑快捷键」改为
 * **直接打开管理列表**（`HotkeyBoard.managerDialog`：上移 / 下移 / 改 / 删 / 新建 /
 * 恢复默认全在里面），不再只是把页面切到触控板页让用户自己去找长按入口。
 * 皮肤取色与触控板页同一口径（`MiuixTheme` 的 onSurface / surface），否则同一个列表
 * 从两个入口进来长得不一样。非 Activity 上下文（理论上不会发生）才退回切页。
 */
@Composable
private fun QuickActionsDialog(
    ctx: Context,
    onDismiss: () -> Unit,
    onOpenTouchpad: () -> Unit,
) {
    // 管理弹窗的皮肤：与触控板页同一个取色口径（主题色），否则从低栏进来的列表
    // 会和从触控板页进来的长得不一样。
    val chipTextArgb = MiuixTheme.colorScheme.onSurface.toArgb()
    val chipBgArgb = MiuixTheme.colorScheme.surface.toArgb()
    val subTextArgb = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
    // managerDialog() 用的是独立 AlertDialog：改完就地把它自己的 render() 跑一遍，
    // 触控板页那边的 chip 网格以 editVersion 轮询重建会跟上（见 HotkeyGrid 的 items）。
    ApComponents.DialogCard(onDismiss = onDismiss) {
        Text(text = ctx.getString(R.string.ui_home_sec_quick), fontSize = 17.sp)
        Text(
            text = ctx.getString(R.string.ui_home_quick_hint),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_home_quick_file),
            onClick = {
                onDismiss()
                runCatching {
                    ctx.startActivity(Intent(ctx, com.allperiph.localsend.SendActivity::class.java))
                }
            },
        )
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_home_quick_clip),
            onClick = {
                onDismiss()
                val r = com.allperiph.ui.ClipboardSync.sendNow(ctx)
                val msg = when (r) {
                    com.allperiph.ui.ClipboardSync.SendResult.NO_DEVICE ->
                        R.string.ui_main_toast_device_not
                    com.allperiph.ui.ClipboardSync.SendResult.EMPTY ->
                        R.string.ui_touchpad_toast_clipboard_empty
                    com.allperiph.ui.ClipboardSync.SendResult.OK ->
                        R.string.ui_touchpad_text_clipboard_send_done
                    com.allperiph.ui.ClipboardSync.SendResult.FAIL ->
                        R.string.ui_touchpad_text_send_failed
                }
                android.widget.Toast.makeText(ctx, ctx.getString(msg), android.widget.Toast.LENGTH_SHORT).show()
            },
        )
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_home_quick_screen),
            onClick = {
                onDismiss()
                runCatching {
                    ctx.startActivity(Intent(ctx, com.allperiph.screen.ScreenActivity::class.java))
                }
            },
        )
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_home_quick_camera),
            onClick = {
                onDismiss()
                runCatching {
                    ctx.startActivity(
                        Intent(ctx, com.allperiph.camera.CameraPushActivity::class.java),
                    )
                }
            },
        )
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_ball_fn_gamepad),
            onClick = {
                onDismiss()
                runCatching {
                    ctx.startActivity(Intent(ctx, com.allperiph.ui.GamepadActivity::class.java))
                }
            },
        )
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_remote_text_remote),
            onClick = {
                onDismiss()
                runCatching {
                    ctx.startActivity(Intent(ctx, com.allperiph.ui.RemoteActivity::class.java))
                }
            },
        )
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_hotkey_title),
            onClick = {
                // 震动由 ApComponents.ActionButton 统一给（2026-11-04 一整套手感），不再叠
                onDismiss()
                // 成宇 2026-11-04「低栏长按后快捷键管理删除」：这一项此前只把页面切到触控板页，
                // 管理列表还得再长按空白处才出得来。改成**直接**打开管理列表 ——
                // 上移 / 下移 / 改 / 删 / 新建 / 恢复默认 都在那一个弹窗里。
                val act = ctx as? android.app.Activity
                if (act == null) {
                    onOpenTouchpad()
                } else {
                    // 形状参数走 Style.HOME_*（2026-11-04 收敛）：此前这里硬编码 12/12/4，
                    // 与触控板页 HotkeyGrid.kbStyle **各写一份同样的值** —— 改圆角或每行
                    // 个数得记着改两处，漏一处两个入口打开的列表就长得不一样。
                    // 颜色仍由本处传（低栏进来的取色口径与触控板页一致，都是 MiuixTheme 主题色）。
                    val style: () -> com.allperiph.ui.HotkeyBoard.Style = {
                        com.allperiph.ui.HotkeyBoard.Style(
                            chipText = chipTextArgb,
                            chipBg = chipBgArgb,
                            chipRadiusDp = com.allperiph.ui.HotkeyBoard.Style.HOME_CHIP_RADIUS_DP,
                            padVDp = com.allperiph.ui.HotkeyBoard.Style.HOME_PAD_V_DP,
                            perRow = com.allperiph.ui.HotkeyBoard.Style.HOME_PER_ROW,
                            accent = chipTextArgb,
                            textPrimary = chipTextArgb,
                            textSecondary = subTextArgb,
                        )
                    }
                    com.allperiph.ui.HotkeyBoard(
                        act,
                        android.widget.LinearLayout(act),
                        style,
                        {},
                    ).managerDialog()
                }
            },
        )
    }
}
