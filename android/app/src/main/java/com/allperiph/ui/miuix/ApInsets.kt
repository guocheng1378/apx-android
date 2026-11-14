package com.allperiph.ui.miuix

import android.view.View
import android.view.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一条边上的四向留白（dp）。用**框架** WindowInsets 算出，不引 androidx.core。
 *
 * 迁移口径（2026-10-28 三条补丁里唯一仍然成立的那条）：edge-to-edge 后用量身留白，
 * 而不是依赖系统自动垫边 —— targetSdk 35 起 `windowOptOutEdgeToEdgeEnforcement` 不再生效。
 */
data class ApEdgeInsets(
    val left: Dp = 0.dp,
    val top: Dp = 0.dp,
    val right: Dp = 0.dp,
    val bottom: Dp = 0.dp,
) {
    val horizontal: Dp get() = maxOf(left, right)
    val vertical: Dp get() = maxOf(top, bottom)
    operator fun plus(other: ApEdgeInsets) = ApEdgeInsets(
        maxOf(left, other.left),
        maxOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )
}

/** 三类 inset 的合并结果：系统栏 + 刘海/挖孔 + 输入法。 */
data class ApInsets(
    val bars: ApEdgeInsets = ApEdgeInsets(),
    val cutout: ApEdgeInsets = ApEdgeInsets(),
    val imeBottom: Dp = 0.dp,
    // 2026-11-07（成宇「优化回避屏幕大圆角和摄像头挖孔」）：大圆角是独立 insets
    // 类型（API 35+ roundedCorners），与挖孔分开读、内容安全区取并集。
    val rounded: ApEdgeInsets = ApEdgeInsets(),
) {
    /** 内容安全区：系统栏与刘海取较大者 */
    val top: Dp get() = maxOf(bars.top, cutout.top)
    val bottom: Dp get() = bars.bottom
    val left: Dp get() = maxOf(bars.left, cutout.left)
    val right: Dp get() = maxOf(bars.right, cutout.right)

    // —— 挖孔回避（2026-11-07 二轮，成宇「圆角和挖孔优化不是直接上部分不显示内容」）——
    // 语义：只算孔，**不算圆角**。圆角半径是屏幕四角的弧，系统 UI 自己会避让；
    // 把它垫进 top 会把顶部内容整块推走（真机表现即「上部分不显示内容」）。
    // 系统栏显示后，状态栏高度已天然盖住孔区，这里只作弹窗等窗口的兑底。
    val holeTop: Dp get() = cutout.top
    val holeBottom: Dp get() = cutout.bottom
    val holeLeft: Dp get() = cutout.left
    val holeRight: Dp get() = cutout.right

    /** 输入法抬起时的底部留白（键盘页用；无 IME 时等于系统栏） */
    val bottomWithIme: Dp get() = maxOf(bars.bottom, imeBottom)
}

/**
 * edge-to-edge（成宇 2026-11-07「按安卓 aosp 规范适配，而不是直接隐藏状态栏和
 * 手势小白条」）：系统栏显示且透明，内容经 [rememberApInsets] 自行垫边。
 * 2026-11-02「所有页屏蔽状态栏」的隐藏口径已按新需求反转；函数随之更名。
 *
 * 为什么做成 Activity 扩展而不是每页各写一遍：这套三件（setDecorFitsSystemWindows(false)
 * + insetsController + hide）在 Hub 页与各子页必须**同一口径**，抄第二遍迟早漏一处，
 * 而漏掉那一处只在真机上表现为「这一页还有系统时间」—— 编译器完全沉默。
 *
 * ⚠️ `window.insetsController` 在 DecorView 尚未创建时**不是返回 null 而是抛 NPE**
 * （Android 15/16 PhoneWindow.getInsetsController() 读未赋值的 mDecor，v223.20 首启
 * 闪退就是这么来的），所以必须放进 decorView.post 里跑。
 */
fun android.app.Activity.applyEdgeToEdge(hideSystemBars: Boolean = false) {
    window.setDecorFitsSystemWindows(false)
    window.decorView.post {
        val c = window.insetsController ?: return@post
        if (hideSystemBars) {
            // v223.29.90（成宇 2026-11-13 截图「顶部被状态栏覆盖」）：手柄页要的是
            // **隐藏**口径 —— 此前 post 里固定 show(systemBars())，而 post 排在
            // onCreate/onResume 之后才跑，会把同一次进入里先执行的 hide(statusBars())
            // 原地打回去：表现就是状态栏图标叠在 ✕/L2/R2 上。post 的动作必须与
            // 调用页的口径一致，TRANSIENT_BY_SWIPE 保留「下滑临时呼出」。
            c.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(android.view.WindowInsets.Type.statusBars())
        } else {
            // AOSP 规范（成宇 2026-11-07）：系统栏**显示**且透明，内容经 WindowInsets
            // 自行垫边；BEHAVIOR_DEFAULT = 系统默认（下滑临时呼出）。
            c.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_DEFAULT
            c.show(android.view.WindowInsets.Type.systemBars())
        }
    }
}

/**
 * v223.29.103（成宇 2026-11-14 截图「被控模式里上面状态栏没屏蔽，设置里的屏蔽按钮
 * 到底起作用没有」）：独立 Activity 宿主的横屏状态栏口径 —— 此前只有手柄页 / 主壳
 * 首帧（v223.29.90 批）接了 landscape_hide_status，操控面、输入面板等其余全屏宿主
 * 横屏时状态栏依旧常显，时间电量直接压在页面内容上。约定：独立 Activity 宿主在
 * onCreate / onResume / onWindowFocusChanged 三处调用；参数传「此刻是否横屏」（与
 * rememberApAdaptiveLayout 同口径：宽 > 高），竖屏不动作，横竖切换后由下一次回前台
 * /取焦按新朝向重压。只藏状态栏、不碰导航栏；TRANSIENT_BY_SWIPE 保留「下滑临时
 * 呼出」，insets 隐藏后归零，各页 rememberApInsets 垫边自动跟随。
 * 页内自管系统栏的全屏态（如操控面 setFullscreen 连导航栏一起收/放）应在调用前
 * 自行让位，避免两套口径打架。
 */
fun android.app.Activity.applyLandscapeBarPref(landscape: Boolean) {
    if (!landscape) return
    val ctrl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    // 扩展函数体内没有 Activity 子类的成员作用域，Context 常量必须带类名限定
    //（裸写 MODE_PRIVATE 在扩展函数里解析不到 —— 手柄页能裸写是因为写在子类里）。
    if (getSharedPreferences("apx", android.content.Context.MODE_PRIVATE)
            .getBoolean("landscape_hide_status", true)
    ) {
        ctrl.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    } else {
        ctrl.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }
}

/**
 * 读当前窗口的 framework insets 并转成 Compose 状态。
 *
 * 实现要点：
 *  · 监听器挂在 [LocalView] 上，**返回值原样透传** —— 不消费 insets，
 *    否则同一棵树里的子 View（如 ComposeView 之外的遗留控件）就收不到；
 *  · 只在 insets 真的变化时更新状态，避免每帧重组；
 *  · 依赖只到 `android.view.WindowInsets`（API 30+，本工程 minSdk 34，无需兼容分支）。
 */
@Composable
fun rememberApInsets(): ApInsets {
    val view = LocalView.current
    var state by remember { mutableStateOf(ApInsets()) }

    DisposableEffect(view) {
        val density = view.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        fun pxToDp(px: Int): Dp = (px / density).dp

        fun convert(insets: WindowInsets): ApInsets {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val cut = insets.getInsets(WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            // 大圆角：**SDK 里没有 `WindowInsets.Type.roundedCorners()`**（CI 实测
            // Unresolved reference —— 那条注释里的「API 35+ 可编译」是错的），改走
            // API 31+ 的 Display.getRoundedCorner 直接取四角半径，再按「该侧最大角半径」
            // 折成四边留白，语义与 holeX 的并集口径一致。
            fun cornerPx(pos: Int): Int {
                if (android.os.Build.VERSION.SDK_INT < 31) return 0
                val display = view.display ?: return 0
                return try {
                    display.getRoundedCorner(pos)?.radius ?: 0
                } catch (t: Throwable) {
                    0   // 个别设备/无窗口场景会抛 IAE：不垫，但不许崩
                }
            }
            // 位置常量取 Display.RoundedCornerPosition 的数值（TOP_LEFT/TOP_RIGHT/
            // BOTTOM_RIGHT/BOTTOM_LEFT = 1/2/3/4）——直接写类名在 Kotlin 侧解析不了
            // （CI 实测：Unresolved reference 'RoundedCornerPosition'）。
            val tl = cornerPx(1)   // Display.RoundedCornerPosition.TOP_LEFT
            val tr = cornerPx(2)   // Display.RoundedCornerPosition.TOP_RIGHT
            val br = cornerPx(3)   // Display.RoundedCornerPosition.BOTTOM_RIGHT
            val bl = cornerPx(4)   // Display.RoundedCornerPosition.BOTTOM_LEFT
            val rounded = android.graphics.Insets.of(
                maxOf(tl, bl), maxOf(tl, tr), maxOf(tr, br), maxOf(bl, br),
            )
            return ApInsets(
                bars = ApEdgeInsets(
                    pxToDp(bars.left), pxToDp(bars.top), pxToDp(bars.right), pxToDp(bars.bottom),
                ),
                cutout = ApEdgeInsets(
                    pxToDp(cut.left), pxToDp(cut.top), pxToDp(cut.right), pxToDp(cut.bottom),
                ),
                imeBottom = pxToDp(ime.bottom),
                rounded = ApEdgeInsets(
                    pxToDp(rounded.left), pxToDp(rounded.top),
                    pxToDp(rounded.right), pxToDp(rounded.bottom),
                ),
            )
        }

        // 兜底：listener 之外再直接读一次根窗口 insets。
        // 真机（v223.23）上 OnApplyWindowInsetsListener 从未被分发过 —— top 恒为 0，
        // 表现是主壳内容从 y=0 起、顶栏标题压着系统时间（状态栏 144px 完全没算进去）。
        // rootWindowInsets 是 DecorView 上那份权威值，attach/layout 后一定有。
        fun readRoot() {
            val wi = view.rootWindowInsets ?: return
            val next = convert(wi)
            if (next != state) state = next
        }

        val listener = View.OnApplyWindowInsetsListener { v, insets ->
            val next = convert(insets)
            if (next != state) state = next
            insets
        }
        view.setOnApplyWindowInsetsListener(listener)
        view.requestApplyInsets()
        readRoot()
        // attach 之前 rootWindowInsets 还是 null：排到一帧之后再读一次
        view.post { readRoot() }
        onDispose { view.setOnApplyWindowInsetsListener(null) }
    }
    return state
}
