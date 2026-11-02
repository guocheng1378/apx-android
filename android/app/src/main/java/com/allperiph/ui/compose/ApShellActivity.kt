package com.allperiph.ui.compose

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

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

    /** 当前页签的"单一真相"：Compose 侧写、dispatchTouchEvent 读/写 */
    @Volatile
    private var pageState = 0

    /** 退出确认弹窗开关（v223.28 补回旧 MainActivity 迁移时整块丢掉的那份） */
    private val showExitAsk = androidx.compose.runtime.mutableStateOf(false)

    /** 横竖屏标志（Compose 侧写入，dispatchTouchEvent 读） */
    @Volatile
    private var adaptiveState: com.allperiph.ui.miuix.ApAdaptiveLayout? = null

    companion object {
        /** 「只来选设备」模式：遥控器页带这个 extra 启动时立刻弹设备选择器 */
        const val EXTRA_PICK_DEVICE = "com.allperiph.extra.PICK_DEVICE"
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
        // 方向锁定（审查条 1.1）：按偏好钉住/放开本 Activity 的朝向。必须在 setContent 之前，
        // 否则首帧会按系统朝向排版一次再被改回来（真机上表现为「闪一下才转」）。
        OrientLock.apply(this)
        pickOnStart = intent?.getBooleanExtra(EXTRA_PICK_DEVICE, false) == true
        intent?.removeExtra(EXTRA_PICK_DEVICE)
        setContent {
            ApMiuixTheme { Shell() }
        }
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
        val bottomGap = if (landscape) 24.dp else 88.dp
        val topGap = if (landscape) 96.dp else 0.dp
        // 状态栏整块屏蔽（成宇 2026-11-02「所有页屏蔽状态栏」）：applyImmersive 里
        // statusBars 也一并 hide 了，所以这里**不用再按边算顶部留白** —— 内容直接画到
        // 屏幕最上沿。此前竖屏垫 insets.top（且 v223.23 前那个值恒为 0），横竖屏各说各话，
        // 顶栏标题才会压在系统时间那一行上。
        // 顶栏整块去除后侧向留白也由各页自己按 insets.left/right 处理，这里不再介入。
        val topPad = 0.dp
        // 特效：顶栏渐进模糊（Android 13+ 有 RuntimeShader 才开，老机器退化为纯色底）
        val hazeState = rememberApHazeState()
        // 底栏 textureBlur 的采样源：内容层注册进 LayerBackdrop（组件总览同款做法）。
        // 底色取壳自己画的背景（md_background），不用 md_surface —— 组件总览整页是
        // surface 打底，本壳是 background（浅色 #F5F6F6 / 深色 #010102），采样层若铺
        // surface 会让胶囊底部比真实背景更亮一层，玻璃看起来发白。
        val shellBg = MiuixTheme.colorScheme.background
        val navBackdrop = rememberLayerBackdrop {
            drawRect(shellBg)
            drawContent()
        }
        var page by remember { mutableIntStateOf(pageState) }
        // 四指切页在 dispatchTouchEvent 里改 pageState，这里双向同步
        androidx.compose.runtime.LaunchedEffect(page) { pageState = page }
        androidx.compose.runtime.LaunchedEffect(pageState) {
            if (page != pageState) page = pageState
        }
        var pickerOpen by remember { mutableStateOf(pickOnStart) }
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
        androidx.compose.runtime.LaunchedEffect(orientationLandscape, locked) {
            if (locked) return@LaunchedEffect
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

        var running by remember { mutableStateOf(AgentController.running) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .layerBackdrop(navBackdrop)
                        .apHazeSource(hazeState),
                ) {
                    when (page) {
                        0 -> StatusPage(
                            ctx = this@ApShellActivity,
                            // 首页快捷入口里的「编辑快捷键」要能跳到键盘页（编辑弹窗在那边）
                            onNavigate = { target ->
                                page = target
                                pageState = target
                            },
                            running = running,
                            onToggleRunning = { want ->
                                running = want
                                toggleAgent(want)
                            },
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
                        2 -> ApKeyboardPage(topBar = topGap, bottomGap = bottomGap)
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

            // 底栏 = 组件总览（MiuixShowcaseActivity）同款悬浮玻璃（用户要求「悬浮低栏要和
            // 组件预览里一样的」）：FloatingNavigationBar 胶囊 + Miuix textureBlur 采样内容层
            // （LayerBackdrop）+ Highlight 玻璃描边；无运行时着色器的老机器退化为纯色胶囊。
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
            Box(
                modifier = Modifier
                    .align(barAlign)
                    .then(if (landscape) Modifier.padding(top = 8.dp) else Modifier)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                // Shell() 里没有 LocalContext 的 `ctx`：这个函数有自己的
                                // this@ApShellActivity（Activity 本身就是 Context），
                                // 直接用它，别再引入一个可能为 null 的局部变量。
                                val act = this@ApShellActivity
                                if (!com.allperiph.ui.FloatingBallService.openQuickMenu(act)) {
                                    android.widget.Toast.makeText(
                                        act,
                                        act.getString(R.string.ui_shell_ball_off),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    },
            ) {
                FloatingNavigationBar(
                    horizontalAlignment = barHorizontal,
                    modifier = Modifier
                        .widthIn(max = 440.dp)
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

    override fun onResume() {
        super.onResume()
        // 方向锁定：设置页改完返回时偏好已变，这里按偏好重新钉/放（幂等）。
        // 重力判读的门禁在 Shell 组合期现读偏好，不经过这里。
        OrientLock.apply(this)
        // 剪贴板同步（旧主壳里由 MainActivity 挂载，迁到新宿主后同一个入口）
        com.allperiph.ui.ClipboardSync.register(this)
    }

    override fun onPause() {
        com.allperiph.ui.ClipboardSync.unregister(this)
        super.onPause()
    }

    override fun onDestroy() {
        // 退出主壳（返回确认 / 最近任务划掉）时把悬浮球的展开层一起收掉：
        // 只认 isFinishing —— 旋转/重建时不该动用户的悬浮面板。
        if (isFinishing) com.allperiph.ui.FloatingBallService.collapseOverlays()
        super.onDestroy()
    }

    private fun applyImmersive() {
        // 全屏口径（framework API，minSdk 34 无需兼容分支）：
        // ① 装饰视图不再替我们让位 —— 让位由各页自己按 ApInsets 算；
        // ② 状态栏颜色透明；③ 允许布局延伸到挖孔短边（横屏时刘海在左右侧）。
        // 成宇 2026-11-02「所有页屏蔽状态栏」：**隐藏**状态栏与导航栏，四个页
        // （状态 / 触控板 / 键盘 / 设置）都不再露系统时间与电量。此前这里主动
        // c.show(statusBars())，竖屏能看见状态栏，叠上玻璃顶栏就是两层顶部占位。
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // 闪退修复（v223.20 首启崩溃）：DecorView 还没创建时 `window.insetsController`
        // **不是返回 null，而是直接抛 NPE** —— Android 15/16 的 PhoneWindow.getInsetsController()
        // 读的是尚未赋值的 mDecor 字段，`?: return` 挡不住，Launcher 一启就 FATAL。
        // 改为 decorView.post：它在 DecorView attach 到 Window 之后才跑，那时 controller 已就位；
        // 未 attach 时先排队，既不会漏执行，也不会再抛。
        // 状态栏与导航栏都隐藏；下拉一次仍可临时唤出（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE），
        // 但不主动显示。
        window.decorView.post {
            val c = window.insetsController ?: return@post
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsets.Type.statusBars())
            c.hide(WindowInsets.Type.navigationBars())
        }
    }
}

/** ApSkin → MiuixTokens 的一次性同步（皮肤切换后要重新调用） */
internal object ApAccentSync {
    fun apply(ctx: Context) {
        val picked = ApSkin.picked(ctx, ApSkin.TOUCHPAD).isNotBlank()
        MiuixTokens.skinAccent = if (picked) {
            androidx.compose.ui.graphics.Color(ApSkin.current(ctx, ApSkin.TOUCHPAD).accent)
        } else {
            null
        }
    }
}


/**
 * 受控设备选择器（`showDevicePicker` 的 Compose 版）。
 *
 * 数据源是 [com.allperiph.wireless.TvDiscovery] 的广播列表（电视/PC/手机三类都在这张表里），
 * 选中即 `ControlTarget` 连入；`本机 / PC` 一行等于 `ControlTarget.clear()`。
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
 */
@Composable
internal fun ApDevicePickerBody(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var devices by remember { mutableStateOf(emptyList<com.allperiph.wireless.TvDiscovery.TvDevice>()) }
    var connecting by remember { mutableStateOf("") }
    // ★ v223.27：这里原来是 start() 之后立刻 list() **只取一次** —— UDP 广播发现要
    //   1~2s 才有第一台设备进来（TvDiscovery 的 devices 表还要等 loop 收到信标才填），
    //   所以弹窗几乎必然显示空表。用户报「找不到设备 / 没有连接设备的地方」有一半在这里。
    //   改成每秒刷新：弹窗开着就能看到设备陆续出现。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.allperiph.wireless.TvDiscovery.start()
        while (true) {
            devices = com.allperiph.wireless.TvDiscovery.list()
            kotlinx.coroutines.delay(1000L)
        }
    }
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
    if (connecting.isNotEmpty()) {
        Text(
            text = ctx.getString(R.string.ui_pick_device_connecting, connecting),
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    devices.forEach { d ->
        ApComponents.ActionButton(
            text = "${d.name} ${d.typeLabel}  ${d.ip}",
            onClick = {
                connecting = d.ip
                connectControlledDevice(ctx, d) { ok, name ->
                    connecting = ""
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

/**
 * 悬浮球的「设备」面板（成宇 2026-11-02「应该在悬浮窗完成」）：同一份选择器内容
 * 装进 overlay 卡片，不再拉起主 Activity。卡片与按钮照走 ApComponents（UI Kit 口径）。
 */
@Composable
internal fun ApDevicePanel(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        ApComponents.Card {
            Column {
                ApDevicePickerBody(onDone = onClose)
                ApComponents.NarrowButton(
                    text = stringResource(R.string.ui_help_close),
                    onClick = onClose,
                )
            }
        }
    }
}
