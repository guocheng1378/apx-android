package com.allperiph.ui.compose

import android.content.Context
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalConfiguration
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
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.ui.miuix.MiuixTokens
import com.allperiph.ui.miuix.ApGlassTopBar
import com.allperiph.ui.miuix.apHazeSource
import com.allperiph.ui.miuix.rememberApHazeState
import com.allperiph.ui.miuix.rememberApInsets
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import com.allperiph.touchpad.TouchpadModule
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.DisposableEffect
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.icon.extended.GridView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog

/**
 * 迁移期的**并行宿主**（阶段 2/3 的落点）。
 *
 * 已迁移至此的屏：状态页、触控板页。主壳（横竖屏策略、四指切页、键盘页、剪贴板同步、
 * 三指手势遮蔽、设备选择）仍在 [com.allperiph.ui.MainActivity]，等阶段 6 一次性接过来
 * 并切换默认入口 —— 本轮不切 launcher，保证「迁移动码编不过」时整机仍有可用入口。
 *
 * 与旧界面的行为差异：
 *  · 只有两个页签（状态 / 触控板），没有键盘页与设置页；键盘页入口仍走旧主界面；
 *  · 四指左右滑切页未实现（旧逻辑依赖 `Activity.dispatchTouchEvent` + ViewFlipper，
 *    迁移版改为状态驱动的底栏切换，手势切页随主壳一起做）；
 *  · 触控板页不再挂 `PhysKeyBridge`（物理蓝牙键盘转发），该桥接在旧主壳里。
 */
class ApShellActivity : ComponentActivity() {

    /** 四指切页的中间状态（dispatchTouchEvent 在 Compose 之外，只能用字段传） */
    private var pickOnStart = false
    private var fourStartX = -1f
    private var fourSwiped = false

    /** 当前页签的"单一真相"：Compose 侧写、dispatchTouchEvent 读/写 */
    @Volatile
    private var pageState = 0

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
        pickOnStart = intent?.getBooleanExtra(EXTRA_PICK_DEVICE, false) == true
        intent?.removeExtra(EXTRA_PICK_DEVICE)
        setContent {
            ApMiuixTheme { Shell() }
        }
    }

    @Composable
    private fun Shell() {
        val insets = rememberApInsets()
        // 反馈 #52（新一轮）第 4 条：横屏底部留白按 96dp 统一，不再让底栏压住页面内容；
        // 竖屏 88dp（底栏自身高度 + 一点呼吸）
        // 上游 v223.13 对旧 Views 版本就是按 96dp 修的，这里保持同一口径
        val adaptive = rememberApAdaptiveLayout()
        adaptiveState = adaptive
        val landscape = adaptive.landscape
        val bottomGap = if (landscape) 96.dp else 88.dp
        // 「横屏覆盖状态栏」（成宇 2026-11-01）：横屏下内容画到状态栏区域之下，
        // 不预留顶部空档；竖屏仍然垫状态栏，否则第一张卡片会被时间/电量压住。
        // 需要垫的只剩挖孔：横屏时刘海在左右，所以左右 inset 照旧生效。
        val topPad = if (landscape) 0.dp else insets.top
        val sidePad = if (adaptive.padCutout) maxOf(insets.left, insets.right) else 0.dp
        // 特效：顶栏渐进模糊（Android 13+ 有 RuntimeShader 才开，老机器退化为纯色底）
        val hazeState = rememberApHazeState()
        var page by remember { mutableIntStateOf(pageState) }
        // 四指切页在 dispatchTouchEvent 里改 pageState，这里双向同步
        androidx.compose.runtime.LaunchedEffect(page) { pageState = page }
        androidx.compose.runtime.LaunchedEffect(pageState) {
            if (page != pageState) page = pageState
        }
        var pickerOpen by remember { mutableStateOf(pickOnStart) }
        androidx.compose.runtime.LaunchedEffect(pickOnStart) { if (pickOnStart) pickerOpen = true }
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
        androidx.compose.runtime.LaunchedEffect(orientationLandscape) {
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
                    .padding(top = if (insets.top > 0.dp) insets.top else 0.dp),
            ) {
                // 顶栏是玻璃叠层、底栏是浮动导航条，两者都盖在内容之上：
                // 这里给内容留出 64dp（顶栏高）+ 96dp（底栏 + 反馈 #52 第 4 条要求的横屏留白）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .apHazeSource(hazeState)
                        .padding(top = 64.dp, bottom = insets.bottom + bottomGap),
                ) {
                    when (page) {
                        0 -> StatusPage(
                            ctx = this@ApShellActivity,
                            running = running,
                            onToggleRunning = { want ->
                                running = want
                                toggleAgent(want)
                            },
                        )
                        1 -> TouchpadPage(
                            ctx = this@ApShellActivity,
                            onGesture = { ev -> touchpadModule()?.feed(ev) },
                            onLongPressArmDrag = { touchpadModule()?.armDrag() },
                            onLongPressRightDrag = { touchpadModule()?.armRightDrag() },
                        )
                        2 -> ApKeyboardPage()
                        else -> SettingsPage(
                            ctx = this@ApShellActivity,
                            onRecreate = { recreate() },
                        )
                    }
                }
            }

            // 顶栏：玻璃渐进模糊 + 当前页标题（内容从栏下滚过）
            ApGlassTopBar(
                title = when (page) {
                    0 -> "状态"
                    1 -> "触控板"
                    2 -> "键盘"
                    else -> "设置"
                },
                hazeState = hazeState,
                onDeviceClick = { pickerOpen = true },
                modifier = Modifier.align(Alignment.TopCenter).padding(start = sidePad, end = sidePad),
            )

            // 受控设备选择器（从旧 MainActivity.showDevicePicker 迁来）：列表来自
            // TvDiscovery 的广播发现，选中即连入 9511；「本机 / PC」走 ControlTarget.clear()
            if (pickerOpen) {
                ApDevicePicker(onDismiss = { pickerOpen = false })
            }

            // 底栏：0.9.4 里 FloatingNavigationBar 自带离底高度与导航栏 inset，
            // 外层只负责贴底（组件内部 Column 会丢弃 BoxScope.align）
            // 液态玻璃底栏：之前给的是一块不透明的 surfaceContainer，等于纯色条。
            // 这里在容器上加 hazeEffect（同一份 hazeState，和顶栏共用），底色降到 0.5 透明度，
            // 内容从底栏后面滚过时能看到模糊透出来的颜色。
            val navSurface = MiuixTheme.colorScheme.surfaceContainer
            // 成宇 2026-11-02「低栏要的是液态悬浮玻璃，现在底下还有东西」的成因：
            // 模糊层贴在**外层 Box** 上，而 Box 是按组件包围盒测量的 —— 里面还含
            // FloatingNavigationBar 自带的离底间距与导航栏 inset，于是胶囊下面又多出
            // 一条半透明模糊带。改成先把外层裁成胶囊（percent=50 → 纯药丸），再上模糊，
            // 玻璃就只跟着胶囊走，栏下不再有任何东西。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(percent = 50))
                    .then(
                        if (hazeState != null) {
                            Modifier.hazeEffect(state = hazeState) {
                                blurRadius = 20.dp
                                backgroundColor = navSurface.copy(alpha = 0.5f)
                                noiseFactor = 0.02f
                                tints = listOf(dev.chrisbanes.haze.HazeTint(navSurface.copy(alpha = 0.16f)))
                            }
                        } else {
                            // 无 RuntimeShader 的机器：同样只画药丸，半透明（0.96 太实，看不出悬浮）
                            Modifier.background(navSurface.copy(alpha = 0.72f))
                        }
                    ),
            ) {
                FloatingNavigationBar(color = Color.Transparent) {
                    FloatingNavigationBarItem(
                        selected = page == 0,
                        onClick = { page = 0 },
                        icon = MiuixIcons.Home,
                        label = "状态",
                        enabled = true,
                    )
                    FloatingNavigationBarItem(
                        selected = page == 1,
                        onClick = { page = 1 },
                        icon = MiuixIcons.GridView,
                        label = "触控板",
                        enabled = true,
                    )
                    FloatingNavigationBarItem(
                        selected = page == 2,
                        onClick = { page = 2 },
                        icon = MiuixIcons.ListView,
                        label = "键盘",
                        enabled = true,
                    )
                    // 图标：miuix-icons 0.9.4 的 extended 共 **156** 个图标（从 maven sources.jar 全量列名，
                    // 不是靠文档猜）。底栏用到的 Home / ListView / GridView / Settings 都在名单内；
                    // 触控板用 GridView、键盘用 ListView，两者区分开。该库没有 Touchpad/Keyboard/
                    // Wifi/Bluetooth/Usb/Terminal 这些设备语义名，所以没硬凑。
                    // 所以设置页用 Settings；触控板与键盘暂时都还是 ListView（猜名字会编译红）
                    FloatingNavigationBarItem(
                        selected = page == 3,
                        onClick = { page = 3 },
                        icon = MiuixIcons.Settings,
                        label = "设置",
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
        // 剪贴板同步（旧主壳里由 MainActivity 挂载，迁到新宿主后同一个入口）
        com.allperiph.ui.ClipboardSync.register(this)
    }

    override fun onPause() {
        com.allperiph.ui.ClipboardSync.unregister(this)
        super.onPause()
    }

    private fun applyImmersive() {
        // 横屏覆盖状态栏的前提（framework API，minSdk 34 无需兼容分支）：
        // ① 装饰视图不再替我们让位 —— 让位由 ApInsets 按边算，横屏顶部按 0 处理；
        // ② 状态栏颜色透明；③ 允许布局延伸到挖孔短边（横屏时刘海在左右侧）。
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
        window.decorView.post {
            val c = window.insetsController ?: return@post
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.show(WindowInsets.Type.statusBars())
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
 * 用 Compose 的 `Dialog` 承载 Miuix `Card` —— Miuix 0.9.4 的 `WindowDialog` 是 preference 侧
 * 的窗口级弹窗，这里要的是应用内弹窗，用系统 Dialog + Miuix 卡片更稳。
 */
@Composable
private fun ApDevicePicker(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var devices by remember { mutableStateOf(emptyList<com.allperiph.wireless.TvDiscovery.TvDevice>()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.allperiph.wireless.TvDiscovery.start()
        devices = com.allperiph.wireless.TvDiscovery.list()
    }
    Dialog(onDismissRequest = onDismiss) {
        ApComponents.DialogCard(onDismiss = onDismiss) {
            Text(
                text = "选择受控设备",
                fontSize = 17.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ApComponents.ActionButton(
                text = "本机 / PC（默认）",
                onClick = {
                    com.allperiph.wireless.ControlTarget.clear()
                    onDismiss()
                },
            )
            devices.forEach { d ->
                ApComponents.ActionButton(
                    text = "${d.name} ${d.typeLabel}  ${d.ip}",
                    onClick = {
                        com.allperiph.wireless.ControlTarget.host = d.ip
                        com.allperiph.wireless.ControlTarget.label = d.name
                        com.allperiph.wireless.ControlTarget.type = d.type
                        onDismiss()
                    },
                )
            }
            if (devices.isEmpty()) {
                Text(
                    text = "同一 Wi-Fi 下没发现设备（电视/PC 端要开着 9511 服务）",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
