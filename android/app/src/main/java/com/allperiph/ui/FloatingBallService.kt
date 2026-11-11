package com.allperiph.ui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.allperiph.R
import com.allperiph.shared.util.Log
import com.allperiph.touchpad.TouchpadModule
import com.allperiph.ui.compose.ApDevicePanel
import com.allperiph.ui.compose.ApFullKeyboard
import com.allperiph.ui.compose.ApTouchpadSurface
import com.allperiph.ui.compose.ApShellActivity
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvDiscovery
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 悬浮球（反馈#7）：独立系统 overlay 触控板窗口。
 *
 * 为什么需要：连接/HID 已能后台保活（AgentForegroundService + KeepAlive），但**输入面**
 * 一直挂在前台 Activity —— HyperOS 小窗失焦时系统不给它派发输入事件（freeform 未聚焦
 * 窗口不收 input），实测小窗后台无法输入、必须展开小窗，Pad 上很影响显示。
 * overlay 窗口与前台/焦点无关，其他 App 照常可点，球/面板照常收触摸。
 *
 * 形态：
 *  - 小球（48dp）：单击展开/收起，拖动移位，长按关闭服务；
 *  - 面板（底部 300dp 半透卡）：整块即触控板，touch 直接喂 [TouchpadModule.feed]
 *    —— 与触控板页完全同一条手势链路（GestureEngine 的 bounds 由首个事件自适应，
 *    不需要配置 surface）；面板外屏幕区域不受影响（FLAG_NOT_TOUCH_MODAL）。
 *
 * 权限：复用已有 SYSTEM_ALERT_WINDOW（副屏同款）；未授权时先跳系统授权页。
 * 入口：设置页「工具」小节 → 悬浮球。
 */
class FloatingBallService : Service() {

    companion object {
        private const val TAG = "FloatingBall"
        private const val NOTIFICATION_ID = 700
        private const val PREFS = "apx_floating_ball"
        private const val KEY_ON = "on"
        const val ACTION_TOGGLE = "com.allperiph.ui.floating_ball.toggle"
        const val ACTION_STOP = "com.allperiph.ui.floating_ball.stop"

        /** 三指手势遮蔽的持有者标识（与主页 / 独立操控面区分开） */
        private const val HOLDER_BALL = "ball"

        /** 停靠态的透明度（建议 2）：够看得见、又不挡下面的内容 */
        private const val DOCK_ALPHA = 0.42f

        /**
         * 球直径（dp）。窗口尺寸直接用这个值给死，不走 WRAP_CONTENT ——
         * 球底 `bg_floating_ball` 是 layer-list，而 shape 基元**没有** intrinsic size，
         * `WRAP_CONTENT` 会一路塌到子 View（当年 22dp、现 26dp 的图标），实测球窗口只有 66px
         * （22dp，@3x），比设计小一半，顺带把扇形的圆心也带偏。
         */
        private const val BALL_DP = 48

        /**
         * 停靠态长条尺寸（dp）：轻拨到屏幕左右边后，球收成一根竖条。
         *
         * 为什么不是继续用圆球：贴边时圆球仍占 48dp 见方，压在应用内容上很显眼；
         * 竖条窄（10dp）却够长（64dp），既能看清是侧边手柄，也不挡内容 —— 用户
         * 2026-11-02「悬浮窗贴边应该变成长条贴边」就是这个观感。
         */
        private const val DOCK_W_DP = 10
        private const val DOCK_H_DP = 64

        /**
         * 隐藏态「三分之一圆弧」几何（issue#168 第 12 条三追）：圆心放在**屏幕外**
         * r/2 处，屏内只能看到正对屏幕的 120°（整圆的三分之一），弧的凸面朝屏幕内、
         * 开口朝屏幕外 —— 比发丝线好点按得多。
         *
         * 四追（2026-11-11 15:50「弧度调低一点，圆弧高度为悬浮球直径一致」）：120° 弧段
         * 的可见高 ≈ 1.732r，加描边与 6dp 窗口余量后要正好等于一颗球的直径（48dp），
         * 反解 r = (48 − 5 − 6) / 1.732 ≈ 21 —— 比旧值（26）更扁更矮，弧占的高度与球一致。
         */
        private const val ARC_RADIUS_DP = 21
        private const val ARC_STROKE_DP = 5

        /**
         * 隐藏态不透明度：圆弧本体只有一根细描边，太透会彻底看不见（用户反馈
         * 「线条和屏幕背景对比度太低了」）。除了调高不透明度，颜色还会按壁纸明暗
         * 自适应（见 [peekArcColor]），浅色壁纸用深弧、深色壁纸用浅弧。
         */
        private const val DOCK_HIDDEN_ALPHA = 0.92f

        /** 长按关闭的判定时长（ms）。旧值是 400，比系统长按阈值还短，拖动时极易误触 */
        private const val LONG_PRESS_MS = 800L

        /** 扇形里快捷项之间的最小间隙（dp）：球贴边时全靠它撑开相邻两项（六追：10→6 收紧间距） */
        private const val FAN_GAP_DP = 6

        /**
         * 圈层首圈容量与递增步长（issue#168 六追：「1 圈最多 5 个，每层递增 3 个」）——
         * 内圈 5、外圈 8、再外 11……（旧版 +1 递增的 5/6/7 太散，用户实测后改口径）。
         */
        private const val FAN_RING_FIRST = 5
        private const val FAN_RING_STEP = 3

        /** 照片卡「待发数量」角标的 view tag：渲染时按 tag 找回（发版后字段名稳定，不参与混淆） */
        private const val BADGE_TAG = "photoCardBadge"

        /**
         * 服务**实际存活**（onCreate 起到了 onDestroy 之间）。设置行据此显示 ——
         * 它是结果，不是意愿。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 用户**意愿**（持久化开关位）。与 [isRunning] 分开是这次真机反馈的根因：
         *
         * 旧实现只有 [isRunning]（进程内静态），而它是**进程级**的 —— 进程一被系统回收
         * 就归 false，可设置页那个开关读的正是它，于是出现「设置里显示关着、球还在屏幕上」
         * （服务在另一个进程活得好好的）。反之 [onCreate] 里先 `isRunning = true` 再
         * `addBall()`，addBall 抛异常（悬浮窗权限被撤 / 主题解析失败）时开关照样显示「开」，
         * 就是「开了没有球」。
         *
         * 修法：开关位落 SharedPreferences（跨进程 / 跨重启可读），显示与切换都看它；
         * addBall 真的成功了才算「开」。
         */
        fun isOn(c: Context): Boolean =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

        private fun setOn(c: Context, on: Boolean) =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ON, on).apply()

        /** 是否真的挂着球（给设置页的「实际状态」用；与意愿位交叉比对） */
        @Volatile
        var ballAttachedGlobal: Boolean = false
            private set

        /** 当前活着的服务实例（同进程直调收浮层用；本服务没有 android:process 隔离） */
        @Volatile
        private var live: FloatingBallService? = null

        /**
         * 就地在球的位置展开扇形常用功能菜单（v223.29）。
         *
         * 主壳的悬浮低栏长按调它 —— 用户要的是「常用功能可以通过长按悬浮低栏呼唤出来」。
         * 刻意复用球自己的扇形：菜单项清单（[BallConfig]）、图标、连接模式的选中态、
         * 分发逻辑（[onFanItem]）都只有一份，低栏不再另造一套「常用功能」面板。
         *
         * @return false 表示球现在不在屏上（用户关过悬浮球 / 服务没起来），调用方据此提示
         */
        fun openQuickMenu(c: Context): Boolean {
            val svc = live ?: return false
            val v = svc.ball ?: return false
            if (!svc.ballAttached) return false
            svc.collapse()          // 先收面板：菜单与面板互斥（同 toggleMenu 口径）
            svc.toggleMenu(v)       // 已有菜单则收起 —— 长按是开关语义，连按两次收起
            return true
        }

        /**
         * 设置页换球面图标时**即时生效**（issue#168 第 9 条）：直接找到活着的球换图。
         * 停靠条 / 隐藏圆弧里图标本来就隐藏（applyDockShape 已 GONE），换图无副作用，
         * 恢复成球时 applyBallShape 只管可见性，图还是这里设的这张。
         * 服务没跑（live == null）时静默返回 —— 下次 addBall 会按落盘的选择挂新图。
         */
        fun applyIcon(c: Context) {
            val v = live?.ball ?: return
            setBallIcon(v.findViewById(R.id.ivBallIcon) ?: return, c)
        }

        /**
         * 按落盘选择给球面挂图（issue#168 第 9 条）：选中相册皮肤（[BallIcon.CUSTOM]）
         * 且副本可解码时挂位图 —— 副本损坏 / 未选相册则回落内置图标。
         * addBall 与设置页即时换图共用这一条路。
         *
         * 二轮修正（issue#168 2026-11-11「选择图片皮肤不生效，必须是纯色图片」）：
         * 根因是布局 XML 里的 `android:tint="@color/ball_text"` —— 它在视图 inflate 时
         * 落成 imageTintList，[ImageView.setImageBitmap] **不会清掉它**，任何照片都被
         * 整体压成一个单色色块，只有照片本身恰好是纯色时才「看不出不对」。修法：
         * 照片挂上就摘 tint，并铺满球面（26dp 的图标尺寸装不下照片观感，改 44dp +
         * 圆形裁剪 + CENTER_CROP，与菜单里照片卡同款）；回落内置图标时把这几样
         * 逐一还原（tint / 尺寸 / 缩放），否则换回图标会顶着照片的残留状态显示。
         * 任意 1:1（乃至任意比例）照片都走中心裁方副本（[BallIcon.setPhoto]），本就支持。
         */
        private fun setBallIcon(iv: ImageView, c: Context) {
            val bmp = if (BallIcon.picked(c) == BallIcon.CUSTOM) BallIcon.bitmap(c) else null
            val density = iv.resources.displayMetrics.density
            if (bmp != null) {
                iv.setImageBitmap(bmp)
                iv.imageTintList = null
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                // 照片贴圆形裁剪：与照片卡同一套 outline 手法（framework 裁剪，非自绘）
                iv.clipToOutline = true
                iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                iv.layoutParams = iv.layoutParams.apply {
                    width = (44 * density + 0.5f).toInt()
                    height = (44 * density + 0.5f).toInt()
                }
            } else {
                iv.setImageResource(BallIcon.drawable(c))
                // 恢复 XML 默认观感：tint（XML 里 android:tint 的运行时等价物）、
                // 26dp 图标尺寸、按中心摆放 —— 照片路径改过这三样
                iv.imageTintList = ColorStateList.valueOf(c.resources.getColor(R.color.ball_text))
                iv.scaleType = ImageView.ScaleType.CENTER
                iv.clipToOutline = false
                iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                iv.layoutParams = iv.layoutParams.apply {
                    width = (26 * density + 0.5f).toInt()
                    height = (26 * density + 0.5f).toInt()
                }
            }
        }

        /**
         * 收起一切展开层：扇形菜单、浮窗面板（触控板 / 键盘 / 手柄 / 设备），
         * 并把停靠长条恢复成正常圆球。主壳退出（返回确认 / 任务划掉）时调用 ——
         * 成宇 2026-11-02「退出应用后悬浮窗自动挂起」：主界面一走，展开过的菜单 /
         * 面板还钉在桌面应用上面，停靠态又是一根 10dp 的半透明细条，看着就是「挂起了」。
         * 退出主壳后桌面上只应留一颗正常、可见、可点的球。
         *
         * @return true 表示确实收起 / 恢复了东西（主壳据此决定返回键要不要吃掉）
         */
        fun collapseOverlays(): Boolean {
            val s = live ?: return false
            var acted = false
            if (s.menu != null) {
                s.closeMenu()
                acted = true
            }
            if (s.panel != null) {
                s.collapse()   // 内部会 addBall() 还球
                acted = true
            }
            val b = s.ball
            if (s.docked && b != null) {
                s.undock(b)
                acted = true
            }
            return acted
        }

        /** 自愈冷却：进程被反复拉起时不要把球切成一条闪烁的动画 */
        private const val KEY_LAST_AUTO = "last_auto_start"

        /**
         * 进程起点自愈（v223.29）—— 修成宇 2026-11-02「悬浮窗不会自动拉起」。
         *
         * 悬浮球是前台服务，可它此前**只有用户手动开关**这一条拉起路径：进程被系统回收、
         * 手机重启、用户从最近任务划掉之后，球就再也不回来，得手动进设置页再打开一次。
         * 用户意愿位 [isOn] 一直存着 true，却没有任何人在进程起点读它。
         *
         * 这里补上这个入口，由 [com.allperiph.App.onCreate] 调：
         *   · 意愿位为 false（用户自己关过悬浮球）→ 什么都不做，绝不强行拉起；
         *   · 没有悬浮窗权限 → 也不做（进程起点弹系统授权页很唐突，留给用户开界面时再要）；
         *   · 60 秒内已经试过 → 跳过，避免进程反复被拉起时球一闪一闪。
         *
         * @param force 跳过「60 秒冷却」与「已经活着」两道判断，给
         *   [com.allperiph.ui.FloatingBallKeepAlive]（自检闹钟 / 开机广播）用 ——
         *   那些时机本身就是「确认服务不在」之后才发生的，冷却反而会把该做的拉起挡掉。
         * @return true 表示这次真的发起了拉起
         */
        fun ensureRunningIfWanted(c: Context, force: Boolean = false): Boolean {
            if (!isOn(c)) return false
            if (isRunning && live != null) return false
            if (!overlayAllowed(c)) {
                Log.i(TAG, "自愈跳过：没有悬浮窗权限（Settings 与 AppOps 都判不允许）")
                return false
            }
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - p.getLong(KEY_LAST_AUTO, 0L) < 60_000L) return false
            p.edit().putLong(KEY_LAST_AUTO, now).apply()
            runCatching { c.startForegroundService(Intent(c, FloatingBallService::class.java)) }
                .onFailure {
                    Log.w(TAG, "自愈拉起失败：${it.message}")
                    return false
                }
            Log.i(TAG, "按用户意愿位自动拉起悬浮球")
            return true
        }

        /**
         * 切后台自愈的**进程内补球**（issue#168 建议 11 的 v223.29.50 补课）。
         *
         * v223.29.48 只走了 [ensureRunningIfWanted] 一条路：服务活着时它直接返回
         * false（什么都不做），服务不在时又卡在 Android 12+ 的后台前台服务启动限制上
         * ——`onStop` 时应用已进后台，`startForegroundService` 会被系统直接拒绝
         * （ForegroundServiceStartNotAllowedException，被 runCatching 吞掉只剩日志），
         * 真机表现就是「切了后台球不出来」。真机上「服务不在」几乎只发生在用户手动关
         * （意愿位 off，本来就不该拉），所以**最常见的缺口其实是服务活着、球没挂**。
         *
         * 这条路不经过服务启动：活着就原地补挂（[addBall]），面板 / 菜单展开期间球是
         * 刻意收起的（collapse 时还球），不越俎代庖。服务真不在时仍退回
         * [ensureRunningIfWanted]（老系统可用），被拒则等主壳 `onResume` 回前台兜底
         * ——前台启动无限制，回来必补上。
         */
        fun restoreBallIfNeeded(c: Context) {
            if (!isOn(c)) return
            val svc = live ?: return
            if (svc.panel != null || svc.menu != null) return
            if (svc.ballAttached) return
            runCatching { svc.addBall() }
                .onFailure { Log.w(TAG, "切后台补球失败：${it.message}") }
        }

        /**
         * 转屏重排的进程内直调入口（2026-11-11 成宇「悬浮球收缩后，旋转屏幕会乱跑」）。
         *
         * ApShellActivity 声明了 configChanges，转屏回调它**必收到**；由它在
         * onConfigurationChanged 里调这里，把球重排从「app 级回调碰运气」改成
         * 「主界面在场就一定触发」。服务不在（球没开）时静默跳过；面板 / 菜单
         * 展开期间球是收起的，[reflowForScreenChange] 内部按 ballAttached 自行空跑。
         */
        fun reflowNow() {
            runCatching { live?.reflowForScreenChange() }
                .onFailure { Log.w(TAG, "转屏重排直调失败：${it.message}") }
        }

        /**
         * 悬浮窗权限判读（v223.29.1 第 5 条修复的核心）。
         *
         * **不要单独用 [Settings.canDrawOverlays] 当判据。** 真机实测（2026-11-02，
         * Android 15 / HyperOS，targetSdk 35）：app-ops 后台「skip」窗口期间它会假阴性 ——
         * 同一时刻 `cmd appops get` 明确是 `SYSTEM_ALERT_WINDOW: allow`，且紧接着
         * 又有 `time=...` 的正常放行记录。单独采信它，自愈就在那个瞬间放弃拉起，
         * 表现就是成宇反馈的「悬浮窗不会自动拉起」。
         *
         * 所以两路取或：Settings 为真直接放行；否则用 AppOpsManager 复核
         * `android:system_alert_window`，只有**两路都否**才认定真撤权。
         * 真撤权时调用方会如实记录，下次真机验证能一眼区分「假阴性」与「真撤权」。
         */
        fun overlayAllowed(c: Context): Boolean {
            if (Settings.canDrawOverlays(c)) return true
            val mode = runCatching {
                val ops = c.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
                ops?.unsafeCheckOpNoThrow(
                    "android:system_alert_window",
                    android.os.Process.myUid(),
                    c.packageName,
                )
            }.getOrNull()
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        }

        /**
         * 设置页入口：无悬浮窗权限 → 跳系统授权页（HyperOS：设置→隐私保护→显示悬浮窗）；
         * 有权限 → 开/关切换（服务未活 = 开，已活 = 停）。
         */
        fun requestToggle(c: Context) {
            if (!overlayAllowed(c)) {
                runCatching {
                    c.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${c.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                Log.i(TAG, "悬浮窗未授权，已跳系统授权页")
                return
            }
            // 用持久化开关位判「现在是开还是关」，不用进程内静态 —— 见 isOn 注释。
            val intent = Intent(c, FloatingBallService::class.java).apply {
                action = if (isOn(c)) ACTION_STOP else ACTION_TOGGLE
            }
            c.startService(intent)
        }
    }

    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    /**
     * 服务侧的换肤上下文（见 [ThemePref.wrapService]）。
     *
     * 菜单／面板／连接模式按钮必须用**它**去 inflate 与取色：`Service` 自身的
     * `getTheme()` 是 framework 的 `Theme.DeviceDefault.Light.DarkActionBar`，
     * 解析不到 `?attr/apPrimary` 一族的属性 —— v223.13 起「单击悬浮球」
     * 就闪退正是栽在这里（InflateException：Failed to resolve attribute）。
     */
    private val themed by lazy { ThemePref.wrapService(this) }

    private var ball: View? = null
    private var ballAttached = false
    private var panel: View? = null

    /** [panel] 的 Compose 生命周期宿主（键盘 / 手柄 / 设备面板用；触控板 XML 面板为 null） */
    private var panelHost: OverlayComposeHost? = null

    /** [panel] 的窗口参数（拖拽调高时要拿它改 height 再 updateViewLayout，见 [resizePanel]） */
    private var panelLp: WindowManager.LayoutParams? = null

    /** [panel] 对应的 [PanelSize] 键（null = 这块面板不可调高）；松手落盘时用它 */
    private var panelSizeKey: String? = null

    /** 扇形菜单浮层（v223.15）：单击悬浮球展开的那一圈快捷项 */
    private var menu: View? = null

    /** 展开中的快捷项（id → 那一格 View）：连接模式切换后按它就地刷新选中态 */
    private val fanItems = mutableListOf<Pair<String, View>>()

    /** 已停靠在侧边（半透明、点击恢复）—— 轻拨手势的落点状态 */
    private var docked = false

    /** 停靠在哪一侧（左 / 右）：恢复圆球与往屏内挪都要按这一侧算 */
    private var dockLeft = false

    /**
     * 停靠判定余量（issue#168 第 12 条三追）：吸附不再按「球心 48dp 内」，改为
     * **球边贴到屏幕边缘**才触发 —— 余量只留 2dp 容手抖，没挨着边缘不收。
     */
    private val dockSnapPx get() = dp(2)

    /** 旋转重算用：上次落位时的屏幕尺寸（等比换算 x/y 到新屏） */
    private var lastScreenW = 0
    private var lastScreenH = 0

    /** 贴边滑入动画；触摸按下即取消，位置以手指为准 */
    private var dockAnim: android.animation.ValueAnimator? = null

    /** 停靠条是否已收成圆弧（隐藏态）：单击直接恢复圆球；往屏内拖手势已取消（第 12 条三追） */
    private var dockHidden = false

    /**
     * 停靠自动隐藏的排程器。球窗口的全部变更本就发生在主线程（onTouch /
     * 动画回调都在主线程），主线程 Handler 足够，不需要另起线程。
     */
    private val dockHideHandler = Handler(Looper.getMainLooper())

    /** 待执行的隐藏任务；取消与重排都经它，避免残留回调在错误状态里把球收没 */
    private var dockHidePending: Runnable? = null

    // ————————————— 服务层悬浮栏整合（issue#168 2026-11-11「先把大事做了」） —————————————
    //
    // 栏整层拆除了。原独立的常驻长条（view_dock_bar）与球是两个 overlay，同屏只互相
    // 打架（用户反复报「两个悬浮窗」）；栏的四个入口（触控板 / 键盘面板就地展开、
    // 副屏 / 遥控器拉起完整页）全部由球的扇形菜单承接——触控板 / 键盘 / 副屏本就是
    // 菜单项，遥控器本次补挂（BallConfig.REMOTE + 补挂迁移 V6）。
    // 从此桌面与任意应用里只悬浮**一个**控件：拖动即挪位（球），单击展开菜单（原栏
    // 的入口都在里面），长按关闭。主壳前后台通知 API（notifyShellForeground）随栏
    // 一并移除，调用点见 ApShellActivity / TouchpadActivity。


    /**
     * 屏幕配置变化（旋转 / 折叠 / 分屏）→ 重新落位。
     *
     * 旧版没有任何旋转监听：lp.x/y 是绝对像素，竖屏贴右边存的 x 在横屏更宽的屏上
     * 直接落在屏幕中间，贴边态也丢（成宇 2026-11-05 报的「贴边后横屏跑中间」）。
     */
    private val reflowCb = object : android.content.ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = reflowForScreenChange()
        override fun onLowMemory() {}
    }

    /**
     * 服务自身的转屏回调（2026-11-11 成宇「悬浮球收缩后，旋转屏幕会乱跑」）。
     *
     * 原来只依赖 [reflowCb] 这条 app 级注册通道：它挂在应用上下文的回调清单里，
     * 部分 ROM / targetSdk 组合下转屏只给**自己声明了 configChanges 的 Activity**
     * 派发（ApShellActivity 正是这种），服务这条通道收不到 —— 球的绝对像素位置
     * 停在旧方向，横竖互换后看起来就是「乱跑」。Service 本身就是 ComponentCallbacks2，
     * 框架对每个组件都有直接派发，补这条保险；与 [reflowCb] 重复触发也无妨，
     * [reflowForScreenChange] 幂等（尺寸没变就是空跑）。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        reflowForScreenChange()
    }

    private fun reflowForScreenChange() {
        val v = ball ?: return
        if (!ballAttached) return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = realDisplayMetrics()
        val (left, top) = parentOffset(v)
        dockAnim?.cancel()
        dockAnim = null
        closeMenu()
        if (lastScreenW > 0 && lastScreenH > 0 &&
            (lastScreenW != dm.widthPixels || lastScreenH != dm.heightPixels)
        ) {
            // 比例换算保住相对位置：贴边的球换算后仍在边缘附近，再由 applyDockShape 精确吸边
            lp.x = (lp.x.toFloat() / lastScreenW * dm.widthPixels).toInt()
                .coerceIn(0, (dm.widthPixels - left - lp.width).coerceAtLeast(0))
            lp.y = (lp.y.toFloat() / lastScreenH * dm.heightPixels).toInt()
                .coerceIn(0, (dm.heightPixels - top - lp.height).coerceAtLeast(0))
            publishBallPosition(v)
        }
        lastScreenW = dm.widthPixels
        lastScreenH = dm.heightPixels
        // 隐藏态（圆弧）重排后要保持隐藏形态，不能借机变回停靠条 ——
        // 「旋转偷偷把收起的条放出来」看着就像球自己跑了（issue#168 建议 12）
        if (docked) {
            if (dockHidden) hideDockShape(v) else applyDockShape(v, dockLeft)
        }
    }

    /** 拖动阈值：超过它才算「拖」，滤掉手指按上去时的抖动 */
    private val touchSlop get() = dp(6)

    // 球拖动/点击判定
    private var downRawX = 0f
    private var downRawY = 0f

    /** 按下瞬间的窗口坐标（拖动全程以它为基准，避免与世界坐标混算） */
    private var downWindowX = 0
    private var downWindowY = 0
    private var downAt = 0L
    private var moved = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 真实**整屏**尺寸（宽, 高），px。
     *
     * 为什么不用 `resources.displayMetrics`：App 自己被挂成小窗/分屏时（Android 11+ 自由窗口），
     * 应用上下文的 displayMetrics 会缩成**窗口**大小 —— 球的落位/拖动夹边/贴边吸附、面板按屏高
     * 的百分比，全都跟着缩：球被关进小窗那块区域出不来、面板只剩窗口那么高。悬浮窗（overlay）
     * 不属于任何 App 窗口，坐标系必须是整块屏。
     *
     * API 30+ 走 `WindowManager.maximumWindowMetrics`（整屏 bounds，不受窗口形态影响，旋转
     * 后宽高互换）；更老版本退回 displayMetrics —— 那时多窗口也不改写应用 metrics，行为正确。
     * 注意：这是唯一权威来源，落位/夹边/重排/面板比例一律走这里，别再混用 resources 的值。
     */
    private fun realScreenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = resources.displayMetrics  // API 29- 兜底：此时应用 metrics 未被窗口形态改写
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * 真实整屏尺寸的 DisplayMetrics 形态（宽高=整屏，density 沿用当前上下文）。
     * 给既有 `dm.widthPixels` 用法的调用点做**最小替换**：只换来源，不动各处算式。
     */
    private fun realDisplayMetrics(): android.util.DisplayMetrics {
        val out = android.util.DisplayMetrics()
        val (w, h) = realScreenSize()
        out.widthPixels = w
        out.heightPixels = h
        out.density = resources.displayMetrics.density
        out.densityDpi = resources.displayMetrics.densityDpi
        return out
    }

    // ————————————————————————— 生命周期 —————————————————————————

    override fun onCreate() {
        super.onCreate()
        // 菜单／面板的 ?attr 强调色由 [themed]（ThemePref.wrapService）负责，
        // 不能直接叠在 Service 自身主题上 —— 那是 framework 默认主题。
        Log.i(TAG, "onCreate")
        live = this
        // v223.29：剪贴板同步改为「服务活着就一直听」—— 旧实现只在主界面 onResume 注册，
        // 主界面一退到后台，手机上复制的文字就不再发往对端（用户「悬浮窗要加剪贴板」的缺口）。
        runCatching { com.allperiph.ui.ClipboardSync.serviceAttach(this) }
            .onFailure { Log.w(TAG, "剪贴板后台监听挂载失败：${it.message}") }
        // v223.25 起菜单多了 键盘 / 手柄：老用户存过勾选清单时补挂一次（见 BallConfig.ensureNewItems）
        BallConfig.ensureNewItems(this)
        // v223.29 自愈闭环：进程被回收 / 手机重启之后按「用户意愿位」把球放回来。
        // 排下一次自检 —— 这次 onCreate 本身可能就是被上一次闹钟唤起来的。
        runCatching { FloatingBallKeepAlive.schedule(this) }
            .onFailure { Log.w(TAG, "自检闹钟排程失败：${it.message}") }
        NotificationChannels.ensure(this)
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, NotificationChannels.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_launcher_app)
                .setContentTitle(getString(R.string.ui_main_label_floating_ball))
                .setContentText(getString(R.string.ui_main_floating_ball_note))
                .setOngoing(true)
                .setContentIntent(
                    // 点通知 = 打开主界面（旧版发 ACTION_STOP，点一下球直接关了；
                    // 关闭路径：长按球 / 扇形菜单退出 / 设置页开关，不该藏在通知点击里）
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, ApShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        // 意愿先落盘（服务真起来了），「是不是真的挂上球」由 addBall 的成功与否决定 ——
        // 旧版无条件 isRunning = true，addBall 失败时开关与画面必然对不上。
        setOn(this, true)
        if (addBall()) {
            ballAttachedGlobal = true
        } else {
            Log.w(TAG, "悬浮球挂载失败：开关位保持 true，设置页会显示「已开启但未显示」")
        }
        // 服务层悬浮栏已随整合拆除（见类顶注释）：桌面只悬浮球一个控件。
        val (sw0, sh0) = realScreenSize()
        lastScreenW = sw0
        lastScreenH = sh0
        // v223.29.11：旋转后重新落位（旧版无旋转监听，贴边的球转横屏会漂到屏幕中间）
        runCatching { registerComponentCallbacks(reflowCb) }
            .onFailure { Log.w(TAG, "rotation callback register failed: ${it.message}") }
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        runCatching { unregisterComponentCallbacks(reflowCb) }
        dockAnim?.cancel()
        live = null
        shutdown()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        setOn(this, false)
        // 用户主动关掉（长按球 / 设置页开关 / 菜单「退出」都汇到这里）→ 连自检闹钟一起撤，
        // 否则闹钟下一秒按意愿位又把球拉回来，表现为「关不掉」。
        runCatching { FloatingBallKeepAlive.cancel(this) }
        // 停靠隐藏的排程同样要撤：任务里引用的窗口即将全部拆除，留着只会空跑
        cancelDockHide()
        dockHidden = false
        ballAttachedGlobal = false
        runCatching { com.allperiph.ui.ClipboardSync.serviceDetach(this) }
        // 服务被杀/关闭时面板可能还开着：遮蔽必须一起还，否则系统三指截屏一直被关着
        com.allperiph.touchpad.GestureShield.release(this, HOLDER_BALL)
        detach(ball)
        detach(panel)
        detach(menu)
        panelHost?.destroy()
        panelHost = null
        ball = null
        panel = null
        menu = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun detach(v: View?) {
        v?.let { runCatching { wm.removeView(it) } }
    }

    // ————————————————————————— 小球 —————————————————————————

    /** @return 是否真的把球挂上了（失败时调用方不能对外宣称「已开启」） */
    private fun addBall(): Boolean {
        if (ballAttached) return true
        // issue#44：悬浮球此前用 Service 自带（亮色）上下文 inflate，深色下取色永远是亮的
        val v = ball ?: LayoutInflater.from(themed).inflate(R.layout.view_floating_ball, null).also { ball = it }
        // issue#168 第 9 条：球面图标按设置页的选择来（布局里 src 是默认触控板，
        // 这里每次挂球都重设一次 —— 服务进程被回收重启后也能回到用户选的图标）。
        setBallIcon(v.findViewById<ImageView>(R.id.ivBallIcon) ?: return false, themed)
        runCatching { wm.addView(v, ballLayoutParams()) }.onFailure {
            Log.w(TAG, "addView 失败（悬浮窗权限被撤？）：${it.message}")
            ball = null
            return false
        }
        ballAttached = true
        ballAttachedGlobal = true
        setupBallTouch(v)
        return true
    }

    private fun ballLayoutParams() = WindowManager.LayoutParams(
        // ⚠️ 必须给死尺寸，别用 WRAP_CONTENT：球底是 layer-list，shape 没有 intrinsic
        //    size，wrap_content 会塌到子 View（那个 22dp 图标，v223.29.51 起改 26dp）→ 实测球只有 66px。
        dp(BALL_DP),
        dp(BALL_DP),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val (sw, sh) = realScreenSize()
        x = sw - dp(64)
        y = sh / 3
    }

    private fun setupBallTouch(v: View) {
        v.setOnTouchListener { view, ev ->
            val lp = view.layoutParams as? WindowManager.LayoutParams
            if (lp == null) {
                return@setOnTouchListener false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // ⚠️ 记录「按下瞬间手指相对窗口左上角」而不是窗口的世界坐标：
                    //    overlay 窗口的 x/y 不带状态栏偏移，rawX/rawY 却带 ——
                    //    直接拿 rawY - y 当偏移，球会一路往屏幕外飘。
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downWindowX = lp.x
                    downWindowY = lp.y
                    downAt = System.currentTimeMillis()
                    moved = false
                    // v223.29.11：抓球即停掉贴边滑入 + 按压脉冲（落下轻微缩小）
                    dockAnim?.cancel()
                    // issue#168 建议 12：任何一次触摸都算「有操作」——先撤掉待执行的
                    // 隐藏任务；若此刻已是圆弧，接下来这把是点击恢复（拖回屏内不再恢复）。
                    cancelDockHide()
                    view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    // 先判位移、后动球：阈值内不更新位置，球的视觉与「是拖是点」的判定
                    // 才不会各说各话 —— 旧版动 1px 就挪球、却要 12px 才算拖动，
                    // 于是「按住想挪一下」常常落进长按分支，把服务直接关掉。
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        moveBall(view, downWindowX + dx, downWindowY + dy)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - downAt
                    // 抬手把按压脉冲的缩放还原
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    when {
                        // 拖动结束：落位 + 判定是否轻拨到了侧边（建议 2）
                        moved -> settleAfterDrag(view)
                        // 停靠态单击 = 恢复悬浮球正常显示（建议 2）。隐藏态（圆弧）
                        // 单击同样直落这里 —— 不停在中间态，一找回就是完整圆球，
                        // 之后单击再展开菜单（issue#168 建议 12）。
                        docked -> undock(view)
                        // 长按关闭：只在「真的没动过」时算数，拖动不背这个锅
                        held >= LONG_PRESS_MS -> shutdown()
                        else -> toggleMenu(view)           // 单击展开/收起扇形菜单
                    }
                }
            }
            true
        }
    }

    /**
     * 父框原点相对屏幕的偏移（screen = lp + offset）。
     *
     * overlay 窗口的 lp.x / lp.y 是相对**父框**的，而父框顶部被状态栏顶下去（真机 144px）。
     * 只在屏幕坐标里算位置的地方（拖动夹边、扇形圆心、包围盒）都得拿它换算，
     * 否则表现就是「球比手指低一截」「扇形比球高一截」。
     */
    private fun parentOffset(v: View): Pair<Int, Int> {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return 0 to 0
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return (loc[0] - lp.x) to (loc[1] - lp.y)
    }

    /** 把球挪到窗口坐标 (x, y) 并夹进屏内（拖动期间的每一帧都走这里） */
    private fun moveBall(v: View, x: Float, y: Float) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = realDisplayMetrics()
        val (left, top) = parentOffset(v)
        val maxX = (dm.widthPixels - left - v.width).coerceAtLeast(0)
        // ⚠️ 上限要减父框偏移：lp.y 从父框顶部算，屏幕底部还剩多少得先扣掉状态栏那段，
        //    否则拖到底会被系统自己裁一次，落点比手指高，看着像「一拖就跳／跑掉」。
        val maxY = (dm.heightPixels - top - v.height).coerceAtLeast(0)
        lp.x = x.toInt().coerceIn(0, maxX)
        lp.y = y.toInt().coerceIn(0, maxY)
        runCatching { wm.updateViewLayout(v, lp) }
    }

    // ————————————————————————— 侧边停靠（建议 2） —————————————————————————

    /**
     * 拖动结束后判定「轻拨」：球心贴近左/右边缘就吸附过去并半透明，减少遮挡；
     * 落在中间就保持普通显示（顺带把 y 夹回屏内，免得出屏幕找不回来）。
     */
    private fun settleAfterDrag(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = realDisplayMetrics()
        val (left, top) = parentOffset(v)
        val cx = left + lp.x + v.width / 2f
        // 抓球前的停靠态要留底：拖动期间 docked 一直保持 true，没有它分不清
        // 「正常球拨到边」（首次停靠）与「停靠条又拨回边」（二次隐藏）
        val wasDocked = docked
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - top - v.height).coerceAtLeast(0))

        // 第 12 条三追：比较的是**球边**离屏幕边缘的距离（cx 是球心），不再是球心距
        val nearLeft = v.width > 0 && cx - v.width / 2f < dockSnapPx
        val nearRight = v.width > 0 && dm.widthPixels - (cx + v.width / 2f) < dockSnapPx
        if (nearLeft || nearRight) {
            docked = true
            dockLeft = nearLeft
            // 贴边 → 先滑向边缘再收条（v223.29.11 动效；旧版瞬间跳变）
            val edgeX = if (nearLeft) -left else dm.widthPixels - left - v.width
            val fromX = lp.x
            dockAnim?.cancel()
            dockAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 160
                addUpdateListener { a ->
                    lp.x = (fromX + (edgeX - fromX) * a.animatedFraction).toInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var canceled = false
                    override fun onAnimationCancel(a: android.animation.Animator) { canceled = true }
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        // 按下取消 / 中途改停靠侧都不再收
                        if (canceled || !docked || dockLeft != nearLeft) return
                        // issue#168 三追（2026-11-11 06:10）：拨到边缘**直接**收成圆弧，
                        // 取消「半透明竖条 + 5 秒排程」中间态 —— 用户要的是贴边即圆弧
                        //（参考截图见该条）。首次停靠与从隐藏态再拨回边缘走同一条路，
                        // 观感一致；竖条形态只剩拖离边缘归位时的兜底（见下方 wasDocked）。
                        hideDockShape(v)
                    }
                })
                start()
            }
        } else if (wasDocked) {
            // 第 12 条三追：「往屏幕内拖恢复」手势取消 —— 与系统全面屏返回手势冲突。
            // 停靠态（含隐藏态）被拖离边缘时按**原形态**归位：隐藏态仍收回圆弧、
            // 停靠条仍回停靠态；找回圆球只认单击（[undock]）。
            if (dockHidden) hideDockShape(v) else applyDockShape(v, dockLeft)
        } else {
            docked = false
            // 普通圆球拖离边缘落回中间：保持圆球不动
            dockHidden = false
            applyBallShape(v)
            v.alpha = 1f
        }
        publishBallPosition(v)
        // 拖动/停靠后菜单与球的相对位置失效，直接收掉，下次点球再按新位置摆
        closeMenu()
    }

    /**
     * 圆球 → 贴边竖条：换尺寸、换背景、藏图标，并让条紧贴选中的那一侧。
     *
     * 圆心（cx / cy）从当前窗口换算，所以拖动落点在哪里，条就在那里的高度贴边，
     * 不会突然弹回屏幕中段。
     */
    private fun applyDockShape(v: View, leftSide: Boolean) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = realDisplayMetrics()
        val (left, top) = parentOffset(v)
        val cy = top + lp.y + v.height / 2f
        val w = dp(DOCK_W_DP)
        val h = dp(DOCK_H_DP)
        lp.width = w
        lp.height = h
        lp.x = if (leftSide) 0 else (dm.widthPixels - left - w).coerceAtLeast(0)
        // ⚠️ cy 是**屏幕**坐标，lp.y 是**父框**坐标（父框顶被状态栏顶下去）——
        // 直接把 cy - h/2 写进 lp.y 会把条整体压低一个状态栏高度（真机 48dp），
        // 用户看到的「线条下移一段距离、和球原来的高度不一致」就是这一行（v223.29.49 修）。
        lp.y = (cy - top - h / 2f).toInt().coerceIn(0, (dm.heightPixels - top - h).coerceAtLeast(0))
        v.setBackgroundResource(R.drawable.bg_ball_dock)
        v.findViewById<ImageView>(R.id.ivBallIcon)?.visibility = View.GONE
        runCatching { wm.updateViewLayout(v, lp) }
            .onFailure { Log.w(TAG, "停靠长条应用失败：${it.message}") }
    }

    /** 贴边竖条 → 圆球：尺寸/背景/图标全恢复，并以当前条心为中心放回屏内 */
    private fun applyBallShape(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = realDisplayMetrics()
        val (left, top) = parentOffset(v)
        val cx = left + lp.x + v.width / 2f
        val cy = top + lp.y + v.height / 2f
        val s = dp(BALL_DP)
        lp.width = s
        lp.height = s
        // ⚠️ cx/cy 是屏幕坐标，lp.x/lp.y 是父框坐标（原点被状态栏/左 inset 顶下去）——
        // 必须先减掉 parentOffset 再落 lp，否则恢复成球时整体向右下漂一个 inset
        // （用户反馈「点开线条后球向左下移、高度位置都变了」的根因之一，v223.29.49 修）。
        lp.x = (cx - left - s / 2f).toInt().coerceIn(0, (dm.widthPixels - left - s).coerceAtLeast(0))
        lp.y = (cy - top - s / 2f).toInt().coerceIn(0, (dm.heightPixels - top - s).coerceAtLeast(0))
        v.setBackgroundResource(R.drawable.bg_floating_ball)
        v.findViewById<ImageView>(R.id.ivBallIcon)?.visibility = View.VISIBLE
        runCatching { wm.updateViewLayout(v, lp) }
            .onFailure { Log.w(TAG, "球形态恢复失败：${it.message}") }
    }

    /** 停靠态点击 → 恢复正常显示：竖条变回圆球、不透明，并往屏内挪出一个球身 */
    private fun undock(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = realDisplayMetrics()
        val wasLeft = dockLeft
        docked = false
        dockHidden = false
        // 恢复成球后不再有「停靠」可隐藏，先把待执行的任务撤掉，
        // 免得它到点把一颗好好的圆球收成条。
        cancelDockHide()
        v.alpha = 1f
        applyBallShape(v)
        // applyBallShape 已按条心把球夹回屏内；这里再往里推一个球身，
        // 免得球心仍落在吸附阈值内，下次一拖就又变条。
        lp.x = if (wasLeft) min(dp(BALL_DP), (dm.widthPixels - dp(BALL_DP)).coerceAtLeast(0))
               else (dm.widthPixels - dp(BALL_DP) * 2).coerceAtLeast(0)
        // ⚠️ 上面改了 lp.x 必须落回 WindowManager：applyBallShape 内部那次 update 是在
        // 这行**之前**执行的，漏写的话球会停在恢复那一刻的位置（贴边半嵌），
        // 直到下一次拖动才被 moveBall 带进屏内（v223.29.49 修）。
        publishBallPosition(v)
        // v223.29.11 动效：变回圆球时从压缩态回弹
        v.scaleX = 0.6f
        v.scaleY = 0.6f
        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
    }

    /**
     * 把球当前的窗口坐标落回 WindowManager。
     *
     * 坐标的唯一真相是 [WindowManager.LayoutParams] 的 x / y —— 旧版把 `View.x` 当
     * 真身（overlay 里它恒为 0），于是拖动「不跟手」、抬手也「不动」，还得靠额外的
     * 回写去补，补不齐就飘。现在只在 lp 上改，这里只是统一收口 + 失败日志。
     */
    private fun publishBallPosition(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching { wm.updateViewLayout(v, lp) }
            .onFailure { Log.w(TAG, "悬浮球站位回写失败：${it.message}") }
    }

    // ————————————————————— 停靠隐藏态维护（issue#168 三追） —————————————————————

    /**
     * 撤掉待执行的隐藏任务。
     *
     * v223.29.65 起贴边即圆弧（issue#168 三追 2026-11-11 06:10），「5 秒无操作
     * 自动收弧」的排程已随竖条中间态一起取消；本函数保留为空跑安全网 ——
     * 触摸（ACTION_DOWN）、恢复圆球、形态切换前照常先撤，万一将来再引入
     * 排程也不会漏撤。
     */
    private fun cancelDockHide() {
        dockHidePending?.let { dockHideHandler.removeCallbacks(it) }
        dockHidePending = null
    }

    /**
     * 停靠 → 三分之一圆弧（issue#168 第 12 条三追；2026-11-11 06:10 再追）。
     *
     * 用户反馈发丝线太难点按、对比度太低，改为：弧心在**屏幕外** r/2 处，屏内只露出
     * 正对屏幕的 120°（整圆的三分之一），凸面朝屏幕内、开口朝屏幕外，点按热区比发丝
     * 线大得多。再追（同日 06:10）：贴边停靠不再经竖条中间态，拨到边缘**直接**落成
     * 本形态；弧的最近可见点离屏幕边缘留 **2px** 缝，不再贴死（用户参考图口径）。
     * 弧心（原条心）高度不动：按当前中心换算新窗口 y，收拢一瞬不会上下跳。
     * 找回只认**单击**（直落 `docked -> undock`）；「往屏内拖」已按第 12 条三追取消。
     */
    private fun hideDockShape(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = realDisplayMetrics()
        val (left, top) = parentOffset(v)
        val cy = top + lp.y + v.height / 2f
        val r = dp(ARC_RADIUS_DP)
        val stroke = dp(ARC_STROKE_DP)
        // 弓形可见宽度 = r/2（圆心在屏外 r/2），加描边与 2dp 边距余量
        val w = r / 2 + stroke + dp(4)
        // 可见弧高 = 2·r·sin(60°) ≈ 1.73r，加描边余量
        val h = (2 * r * 0.866f).toInt() + stroke + dp(6)
        // issue#168 三追：窗口整体往屏内让 2px，弧两端（可见描边离屏缘最近的点）
        // 与屏幕边缘之间留出这道缝；右侧镜像同口径。
        val gap = dp(2)
        lp.width = w
        lp.height = h
        lp.x = if (dockLeft) gap else ((dm.widthPixels - left - w) - gap).coerceAtLeast(0)
        // 同 applyDockShape：cy 是屏幕坐标，lp.y 是父框坐标，先减 top（v223.29.49 修）
        lp.y = (cy - top - h / 2f).toInt().coerceIn(0, (dm.heightPixels - top - h).coerceAtLeast(0))
        dockHidden = true
        v.background = ArcPeekDrawable(dockLeft)
        v.alpha = DOCK_HIDDEN_ALPHA
        // issue#168 四追（2026-11-11 15:50「贴边成圆弧时不要显示皮肤」）：v223.29.65 起
        // 拨边**直接**落圆弧、不再经竖条中间态，applyDockShape 里藏图标的那一步被跳过 ——
        // 球面照片/图标会原样叠在圆弧上。这里与 applyDockShape 同口径补藏；
        // 恢复圆球走 applyBallShape 的 VISIBLE，不受影响。
        v.findViewById<ImageView>(R.id.ivBallIcon)?.visibility = View.GONE
        runCatching { wm.updateViewLayout(v, lp) }
            .onFailure { Log.w(TAG, "停靠条收成圆弧失败：${it.message}") }
    }

    /**
     * 隐藏态圆弧的颜色（issue#168 第 12 条三追「自适应调整颜色提高对比度」）：
     * 采样当前壁纸的平均亮度 —— 浅色壁纸用深弧、深色壁纸用浅弧，保证两种桌面都看得清。
     * 采样只做一次（收弧是高频动作，不能每帧解码壁纸），取不到壁纸按浅色处理用深弧。
     */
    private var peekArcColorCache = 0

    private fun peekArcColor(): Int {
        if (peekArcColorCache != 0) return peekArcColorCache
        val sample: Float = runCatching {
            // 亮度采样走 WallpaperColors（API 27+，无需特殊权限）：旧写法用 getWallpaper() 取
            // Drawable 逐像素采样，但该 API（连同 getDrawable()）在新 SDK（API 37 实测）已从
            // android.jar 移除，且 Android 14+ 直读壁纸位图本就受限。主/次/第三三档颜色加权
            // 平均精度虽不如逐像素，但本处只区分「壁纸整体偏亮 / 偏暗」，足够。
            val colors = android.app.WallpaperManager.getInstance(this)
                .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM) ?: return@runCatching 1f
            val parts = listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
            var lum = 0f
            var n = 0
            for (c in parts) {
                // Rec. 601 亮度：足够区分「壁纸整体偏亮 / 偏暗」；Color 分量是 0..1 浮点
                lum += 0.299f * c.red() + 0.587f * c.green() + 0.114f * c.blue()
                n++
            }
            if (n == 0) 1f else lum / n
        }.getOrDefault(1f)
        // 亮度高（浅色壁纸）用深弧，亮度低（深色壁纸）用浅弧；带一点透明度融入桌面
        peekArcColorCache = if (sample > 0.55f) 0xDD202024.toInt() else 0xDDF2F2F2.toInt()
        return peekArcColorCache
    }

    /**
     * 隐藏态的三分之一圆弧绘制（见 [hideDockShape]）。
     *
     * 圆心放在屏幕外 r/2 处，画布上只露出 120° 弧段：左缘弧圆心在 x = -r/2、
     * 圆心角 0° 两侧各 60°；右缘镜像（圆心角 180° 两侧各 60°）。
     */
    private inner class ArcPeekDrawable(private val leftSide: Boolean) : android.graphics.drawable.Drawable() {
        private val stroke = dp(ARC_STROKE_DP).toFloat()
        private val r = dp(ARC_RADIUS_DP).toFloat()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeWidth = stroke
            color = peekArcColor()
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val cy = b.exactCenterY()
            val cx0 = if (leftSide) -r / 2f else b.width() + r / 2f
            val rect = android.graphics.RectF(cx0 - r, cy - r, cx0 + r, cy + r)
            val start = if (leftSide) -60f else 120f
            canvas.drawArc(rect, start, 120f, false, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            paint.colorFilter = cf
        }

        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    // ————————————————————— 扇形快捷菜单（v223.15，MIUI / HyperOS 快捷球观感） —————————————————————

    /**
     * 单击悬浮球：展开／收起扇形快捷菜单。
     *
     * 为什么是扇形，而不是旧版那一列卡片：
     * - HyperOS 快捷球的交互就是「球 → 周围一圈快捷项」，用户要的是这个观感；
     * - 悬浮球住在 overlay 层，任何矩形卡片都会压住球旁边的应用内容；扇形只在球周围
     *   占一圈，遮挡与视觉重量都最小；
     * - 圈里有哪些项由**用户勾选**决定（[BallConfig]），不再是写死的四颗按钮。
     *
     * ⚠️ 菜单窗口的尺寸 = 那一圈的包围盒，不是整屏：窗口内被子 View 之外的区域由根容器
     * 接住并收起菜单（点空白处即关），窗口外的触摸照常落给下面的应用（FLAG_NOT_TOUCH_MODAL），
     * 所以展开菜单时别处还能滑。
     */
    private fun toggleMenu(v: View) {
        if (menu != null) {
            closeMenu()
            return
        }
        collapse()   // 菜单与触控板面板互斥：面板铺满底部，菜单要占球旁边的位置
        // 兜底：即便主题／资源出问题，也只让菜单展开失败，不能把整个进程带走
        val fan = runCatching { buildFan(v) }.getOrElse {
            Log.w(TAG, "扇形菜单构建失败：${it.message}")
            return
        }
        if (!attachFan(fan)) return
        menu = fan.root
    }

    /** 扇形菜单的「一屏」：包围盒尺寸的根容器 + 它的窗口参数 */
    private class Fan(val root: View, val lp: WindowManager.LayoutParams)

    /**
     * 按 [BallConfig.enabled] 生成扇形：球心为圆心、朝**屏幕内侧**铺开。
     *
     * 六追（issue#168 2026-11-11，用户实测后重新定口径）：
     * 1. **圈层容量递增 3**：内圈 5、外圈 8、再外 11……（旧版 +1 的 5/6/7 排得太稀疏）；
     * 2. **统一固定间距**：相邻两项的弦长恒为「图标 + 间隙」，角距由几何反解
     *    （2R·sin(step/2) = chord），不再按「张角从宽到窄」搜索 —— 未排满的圈也按同一
     *    角度步进聚拢排布，不摊满整圈（用户原话「未排满时不分散布局」）；
     * 3. **逆时针排列**：屏幕坐标 y 轴向下，角度增大视觉上是顺时针，所以逆时针 =
     *    角度递减，第一项落在参考方向逆时针一侧（用户原话「逆时针排列」）；
     * 4. **缩短与球距离、收紧行间距**：半径与间隙整档下调（见 [BallConfig.arcRadiusDp] /
     *    [FAN_GAP_DP]）。
     * 兜底逻辑不变：极端贴边时逐项钳进屏内（含可绘制区上沿），宁可邻项略叠，
     * 不摆到看不见的地方（HyperOS 会裁掉窗口负坐标，见五追口径）。
     *
     * 窗口取**所有快捷项外扩一圈的矩形**，而不是固定的正方形包围盒：球贴边时正方形
     * 有一角在屏外，被裁的那块虽是空白，却会让「点空白收起菜单」的手感莫名其妙。
     */
    /**
     * 单圈摆位（圈层版 [buildFan] 每圈调用一次）：全部圈层共用**朝屏心**的参考方向，
     * 逆时针、固定角距（见上方类注释）；[topMin] 仍是可绘制区 y 上沿，兜底钳位沿用
     * 五追口径。返回值第二位带出参考角 —— 旧版每圈各搜各的圆心角会相背，现在圈层
     * 朝向天然一致，原样传回。
     */
    private fun placeArcRing(
        m: Int,
        baseR: Float,
        cx: Float,
        cy: Float,
        refDirDeg: Float,
        itemPx: Float,
        gapPx: Float,
        screenW: Int,
        screenH: Int,
        topMin: Int,
    ): Pair<List<Pair<Float, Float>>, Float> {
        val half = itemPx / 2f
        // 可行区的屏幕坐标上下沿：下沿就是屏幕底，上沿是可绘制区顶（父框原点之下）
        val yTop = topMin + half
        if (m <= 1) {
            val a = Math.toRadians(refDirDeg.toDouble())
            return listOf(
                (cx + baseR * cos(a).toFloat()).coerceIn(half, screenW - half) to
                    (cy + baseR * sin(a).toFloat()).coerceIn(yTop, screenH - half),
            ) to refDirDeg
        }
        // 六追新口径（issue#168 2026-11-11）：固定间距、逆时针、未排满不分散 ——
        // 相邻两项的**弦长**恒为「图标 + 间隙」，由 2R·sin(step/2) = chord 几何反解出
        // 角距 step；整段弧以朝屏心方向为正中对称展开，第一项落在参考方向**逆时针**
        // 一侧（屏幕坐标 y 轴向下，角度增大是视觉顺时针，所以逆时针 = 角度递减）。
        val chord = itemPx + gapPx
        val step = Math.toDegrees(
            2.0 * asin(min(chord / (2.0 * baseR), 1.0)),
        ).toFloat()
        val spanDeg = step * (m - 1)
        val startDeg = refDirDeg + spanDeg / 2f
        val pts = ArrayList<Pair<Float, Float>>(m)
        for (i in 0 until m) {
            // 逆时针排列：角度随序号递减
            val a = Math.toRadians((startDeg - step * i).toDouble())
            // 极端贴边时逐项钳进屏内（含可绘制区上沿），宁可邻项略叠，不摆到看不见的地方
            pts.add(
                (cx + baseR * cos(a).toFloat()).coerceIn(half, screenW - half) to
                    (cy + baseR * sin(a).toFloat()).coerceIn(yTop, screenH - half),
            )
        }
        // 参考方向原样传回：所有圈层共用朝屏心角，圈层朝向天然一致
        return pts to refDirDeg
    }

    private fun buildFan(ballView: View): Fan {
        // 照片卡默认排第一位（ROADMAP §5.2 ①）——issue#168 二轮起可在设置页关闭
        //（[BallLatestPhoto.photoCardEnabled]，「相册按钮没有加到设置页悬浮球菜单项」）；
        // 关掉后菜单从勾选清单第一项排起，勾选清单本身不受影响
        val items =
            (if (BallLatestPhoto.photoCardEnabled(this)) listOf(BallConfig.PHOTO_CARD) else emptyList()) +
                BallConfig.enabled(this)
        val dm = realDisplayMetrics()
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        // 菜单尺寸档（2026-11-07）：项直径与扇形半径一起按用户选的三档缩放，
        // 见 BallConfig.itemDp / arcRadiusDp（设置页「悬浮栏 → 菜单大小」）。
        // itemPx/gapPx 需为 Float：placeArcRing 的半径/间隙参数都是浮点（CI 编译错实修）
        val itemPx = dp(BallConfig.itemDp(this)).toFloat()
        val gapPx = dp(FAN_GAP_DP).toFloat()
        val half = itemPx / 2f
        val designR = dp(BallConfig.arcRadiusDp(this)).toFloat()

        // ⚠️ 圆心必须用「屏幕坐标」，不能直接用 lp.x / lp.y，也不能用 View.x / .y：
        //    前者相对父框（父框顶部被状态栏顶下去 144px），后者在 overlay 里恒为 0。
        //    上一版拿 lp.y 当圆心，扇形整体比球高一个状态栏，看着就是「图标不在一个地方」。
        val ballLp = ballView.layoutParams as? WindowManager.LayoutParams
        val loc = IntArray(2)
        ballView.getLocationOnScreen(loc)
        // 父框原点与 lp 原点的差值：屏幕坐标 = lp + 这个偏移
        val parentLeft = loc[0] - (ballLp?.x ?: 0)
        val parentTop = loc[1] - (ballLp?.y ?: 0)
        val cx = loc[0] + ballView.width / 2f
        val cy = loc[1] + ballView.height / 2f
        val n = items.size
        // 朝屏幕中心（球停在角落时自然变成对角方向）
        val dirDeg = Math.toDegrees(
            atan2((screenH / 2f - cy).toDouble(), (screenW / 2f - cx).toDouble()),
        ).toFloat()

        val centres = ArrayList<Pair<Float, Float>>(n)
        if (n <= 1) {
            val a = Math.toRadians(dirDeg.toDouble())
            centres.add(
                (cx + designR * cos(a).toFloat()).coerceIn(half, screenW - half) to
                    (cy + designR * sin(a).toFloat()).coerceIn(parentTop + half, screenH - half),
            )
        } else {
            // 圈层分配（issue#168 六追 2026-11-11：「单圈层样式改为每层递增 3 个」）：
            // 内圈 5、外圈 8、再外 11…… 从内往外依次装满；≤5 项时只有内圈，
            // 未排满的圈按固定角距聚拢（见 placeArcRing），不摊满整圈
            val ringCounts = ArrayList<Int>()
            var rem = n
            var cap = FAN_RING_FIRST
            while (rem > 0) {
                val m = minOf(rem, cap)
                ringCounts.add(m)
                rem -= m
                cap += FAN_RING_STEP
            }
            // 五追：外圈以**内圈选中的圆心角**为参考方向（内圈放不下则仍是朝屏心角）——
            // 圈与圈朝向一致，不再各搜各的导致两段弧相背；上界传可绘制区顶（状态栏之下）
            var refDir = dirDeg
            ringCounts.forEachIndexed { ringIdx, m ->
                // 外圈半径逐圈外移一圈「图标 + 间隙」，保证相邻圈不叠
                val ringR = designR + ringIdx * (itemPx + gapPx)
                val (pts, chosen) = placeArcRing(
                    m, ringR, cx, cy, refDir, itemPx, gapPx, screenW, screenH, parentTop,
                )
                centres.addAll(pts)
                refDir = chosen
            }
        }

        // 窗口 = 所有快捷项外扩一圈的矩形，紧贴扇形，不做无谓的遮挡
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for ((x, y) in centres) {
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        val pad = half + gapPx
        minX -= pad
        minY -= pad
        maxX += pad
        maxY += pad
        // 布局与像素参数走整数 px（itemPx 浮点版仅供 placeArcRing 半径计算）
        val itemIpx = itemPx.toInt()
        val boxW = (maxX - minX).toInt().coerceAtLeast(itemIpx)
        val boxH = (maxY - minY).toInt().coerceAtLeast(itemIpx)
        val wx = minX.toInt()
        val wy = minY.toInt()

        val shownAt = SystemClock.uptimeMillis()
        val root = FrameLayout(themed).apply {
            // 空白处点一下 = 收起菜单（快捷项各吃各的，不会传上来）。
            // ⚠️ 只认「菜单出现之后」才按下的手势：展开菜单的那一次抬手会落到刚 add 的
            //    窗口上，不筛 downTime 的话菜单一闪就自己关掉（真机实测）。
            setOnTouchListener { _, ev ->
                if (ev.downTime < shownAt) {
                    true                                   // 吃掉展开手势的残留，别当点击
                } else {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) closeMenu()
                    true
                }
            }
        }
        fanItems.clear()
        centres.forEachIndexed { i, (x, y) ->
            val cell = buildFanItem(items[i])
            val lpItem = FrameLayout.LayoutParams(itemIpx, itemIpx).apply {
                leftMargin = (x - wx - half).toInt()
                topMargin = (y - wy - half).toInt()
            }
            root.addView(cell, lpItem)
            fanItems.add(items[i] to cell)
        }

        val lp = WindowManager.LayoutParams(
            boxW,
            boxH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 包围盒是按屏幕坐标算的，写回窗口时要把父框偏移减掉（状态栏那 144px）
            x = wx - parentLeft
            y = wy - parentTop
        }
        return Fan(root, lp)
    }

    /** 一颗快捷项：52dp 圆 + 居中单色图标（HyperOS 那边也是只给图标，不给文字） */
    private fun buildFanItem(id: String): View {
        if (id == BallConfig.PHOTO_CARD) return buildPhotoCardCell()
        val cell = FrameLayout(themed).apply {
            isClickable = true
            isFocusable = true
            contentDescription = getString(BallConfig.labelRes(id))
            setOnClickListener { onFanItem(id) }
        }
        cell.addView(
            ImageView(themed).apply {
                setImageResource(BallConfig.iconRes(id))
                // 图标是单色遮罩，颜色在这儿定（见 applyFanState）
                setImageTintList(ColorStateList.valueOf(themed.resources.getColor(R.color.ball_text)))
            },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
        )
        applyFanState(cell, id)
        return cell
    }

    /**
     * 最新照片卡片（ROADMAP §5.2 ①，2026-11-12；issue#168 二轮改「点选 → 再点发」）：
     * 扇形菜单顶部的照片格，默认排第一位（可在设置页关闭，[BallLatestPhoto.photoCardEnabled]）。
     *
     * 交互（二轮反馈「每次点击按钮后触发多选图片，然后再点击图标发送到电脑，发送之后
     * 按钮重置需要重新选择新图片」）：
     *  · 待发为空：点按 / 拖出 → 拉系统**多选**（[PhotoPickActivity]，可挑多张）；
     *  · 有待发：卡面显示首张缩略图 + 数量角标，再点卡 = 全部发出（[BallLatestPhoto.send]），
     *    发完自动重置，下次点按重新选图；
     *  · 没权限先走授权页（[PhotoPermActivity]），没连上如实提示。
     *
     * 发送链路与触控板页发文件同一条（9512，无新协议）。
     */
    private fun buildPhotoCardCell(): View {
        val cell = FrameLayout(themed).apply {
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.ui_ball_photo_card_desc)
            // 底座与其它快捷项同款半透明圆；照片铺满时被下面的圆形裁剪兜住
            setBackgroundResource(R.drawable.bg_ball_item)
        }
        val iv = ImageView(themed).apply {
            // 照片贴圆形：outline 椭圆 + clipToOutline（framework 裁剪能力，
            // 不是画形状 —— 形状本身仍只有 UI Kit / drawable 一处）
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        // 数量角标（待发多张时叠在卡面上，一眼看出「这是待发状态、不是最新照片」）
        val badge = TextView(themed).apply {
            tag = BADGE_TAG
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            // 半透明黑底圆角小胶囊：与快捷项底座不撞形
            setBackgroundResource(R.drawable.bg_ball_badge)
            visibility = View.GONE
        }
        cell.addView(iv, FrameLayout.LayoutParams(dp(BallConfig.itemDp(this)), dp(BallConfig.itemDp(this)), Gravity.CENTER))
        cell.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // 查一次 + 异步解码：query 是毫秒级的索引扫描（主线程可承受），
        // loadThumbnail 的解码在后台线程，回来时菜单多半还开着，就地换上真图
        if (BallLatestPhoto.pendingCount() > 0) {
            // 待发状态：首张缩略图在后台解，解回来就地换
            BallLatestPhoto.loadPendingThumb(themed, dp(BallConfig.itemDp(this))) {
                if (iv.isAttachedToWindow) renderPhotoCard(iv)
            }
        } else {
            val latest = BallLatestPhoto.query(themed)
            if (latest != null) {
                BallLatestPhoto.loadThumb(themed, latest, dp(BallConfig.itemDp(this))) {
                    if (iv.isAttachedToWindow) renderPhotoCard(iv)
                }
            }
        }
        renderPhotoCard(iv)
        var downX = 0f
        var downY = 0f
        var dragged = false
        cell.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 拖出即发：不需要精确的「拖到某区域再放手」，拖起来就算
                    if (!dragged &&
                        (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop)
                    ) {
                        dragged = true
                        closeMenu()
                        BallLatestPhoto.send(this, cell)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        closeMenu()
                        BallLatestPhoto.send(this, cell)
                    }
                    true
                }
                else -> true
            }
        }
        return cell
    }

    /**
     * 照片卡的图面：有待发显示待发首张缩略图 + 数量角标（多选待发态）；
     * 没有待发显示最新照片缩略图；都没有（未授权 / 无照片 / 还没解出来）
     * 回落文件占位图标（观感与其它快捷项一致，不闪一块空白）。
     * 照片铺满格子（圆形裁剪、不上 tint），占位图标走单色 tint 居中。
     */
    private fun renderPhotoCard(iv: ImageView) {
        val pendingN = BallLatestPhoto.pendingCount()
        // 数量角标：挂在照片卡（iv 的父容器）上，按待发数量显隐
        (iv.parent as? ViewGroup)?.let { cell ->
            cell.findViewWithTag<TextView>(BADGE_TAG)?.apply {
                text = pendingN.toString()
                visibility = if (pendingN > 0) View.VISIBLE else View.GONE
            }
        }
        val bmp = if (pendingN > 0) BallLatestPhoto.pendingThumbOrNull() else BallLatestPhoto.thumbOrNull()
        if (bmp != null) {
            iv.setImageBitmap(bmp)
            iv.imageTintList = null
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            iv.setImageResource(R.drawable.ic_apx_files)
            iv.imageTintList = ColorStateList.valueOf(themed.resources.getColor(R.color.ball_text))
            iv.scaleType = ImageView.ScaleType.CENTER
        }
    }

    /** 快捷项外观：连接模式类按开关态走主色实底，其余走和球同款半透明白／黑 */
    private fun applyFanState(cell: View, id: String) {
        // 照片卡不是开关也不是模式：缩略图不能上单色 tint，跳过这套状态渲染
        if (id == BallConfig.PHOTO_CARD) return
        val kind = BallConfig.transportOf(id)
        val on = (kind != null && AgentController.isTransportEnabled(this, kind)) ||
            (BallConfig.isToggle(id) && fanToggleOn(id))
        cell.setBackgroundResource(if (on) R.drawable.bg_ball_item_on else R.drawable.bg_ball_item)
        val tint = if (on) ApPalette.onPrimary(themed) else themed.resources.getColor(R.color.ball_text)
        (cell as? FrameLayout)?.getChildAt(0)?.let { (it as? ImageView)?.setImageTintList(ColorStateList.valueOf(tint)) }
    }

    /** 连接模式切完就地刷新那几格，不重建整个扇形（重建会闪一下，且丢按下态） */
    private fun renderFanStates() {
        for ((id, cell) in fanItems) applyFanState(cell, id)
    }

    private fun attachFan(fan: Fan): Boolean {
        val added = runCatching {
            wm.addView(fan.root, fan.lp)
            true
        }.getOrElse {
            Log.w(TAG, "扇形菜单贴窗失败：${it.message}")
            false
        }
        // ⚠️ 置顶必须与 addView 分开兜底：若它失败让整个 attachFan 返回 false，
        //    菜单窗口已经 add 进去了却没人记着 —— 之后既收不掉也再开不了（变成孤儿窗口）。
        if (added) raiseBall()
        // v223.29.11 动效：扇形逐项浮现（30ms 一档），替代整圈瞬间出现
        fanItems.forEachIndexed { i, (_, cell) ->
            cell.alpha = 0f
            cell.scaleX = 0.7f
            cell.scaleY = 0.7f
            cell.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(i * 30L).setDuration(160)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                .start()
        }
        return added
    }

    /**
     * 把球窗口抬到最上层。
     *
     * 菜单窗口是「以球心为中心的矩形」，新 add 的窗口又在最上层 —— 不抬球的话，
     * 展开期间球被自己的菜单盖住，既点不到也拖不动（用户报的「移动还会退出」里
     * 就有这一层：以为在拖球，实际点在了菜单上）。
     * 球与外面那圈项在视觉上并不重叠，抬它只是把触摸权还回来。
     *
     * 必须用 removeViewImmediate：普通 removeView 是异步的，窗口记录要等
     * ViewRootImpl 回收完才清，紧接着 addView 同一 View 会撞
     * 「View has already been added to the window manager」，而那时球已经被摘掉了 ——
     * 表现就是球直接消失。
     */
    private fun raiseBall() {
        val v = ball ?: return
        if (!ballAttached) return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching {
            wm.removeViewImmediate(v)
            wm.addView(v, lp)
        }.onFailure { Log.w(TAG, "悬浮球置顶失败：${it.message}") }
    }

    private fun closeMenu() {
        detach(menu)
        menu = null
        fanItems.clear()
    }

    /** 快捷项分发：触控板 / 键盘 / 手柄 / 设备 = 浮窗面板（就地完成）；文件与被控台 = 完整页面（startActivity）；退出与连接模式 = 服务内处理 */
    private fun onFanItem(id: String) {
        when (id) {
            BallConfig.PHOTO_CARD -> {
                // 照片卡平时走自己的触摸监听（点选 → 再点发）；分发到这里的只可能是兜底
                //（如未来哪处改为 setOnClickListener）—— 行为与点按一致（多选 / 发送见 BallLatestPhoto.send）
                closeMenu()
                BallLatestPhoto.send(this, ball ?: return)
            }
            BallConfig.TOUCHPAD -> {
                closeMenu()
                expand()
            }
            BallConfig.KEYBOARD -> {
                closeMenu()
                expandKeyboard()
            }
            BallConfig.GAMEPAD -> {
                closeMenu()
                expandGamepad()
            }
            BallConfig.DEVICES -> {
                closeMenu()
                // 「扫描 / 切换设备」同样浮窗化（成宇 2026-11-02「应该在悬浮窗完成」）：
                // 同一份选择器内容（ApDevicePanel）装进 overlay 卡片，不再拉起主 Activity。
                expandDevices()
            }
            BallConfig.FILES -> {
                closeMenu()
                runCatching {
                    startActivity(
                        Intent(this, FilePanelActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            BallConfig.CLIPBOARD -> {
                closeMenu()
                // 就地浮窗完成（成宇 2026-11-02「悬浮窗要加剪贴板」）：不拉起主界面，
                // 面板里直接看当前剪贴板内容 + 手动补发一次。
                expandClipboard()
            }
            BallConfig.SCREEN -> {
                closeMenu()
                // 副屏要 TextureView + 全屏推流，只能拉起完整页（悬浮面板装不下）；
                // 与「文件」同口径，从球上一跳直达，不必先进主界面再翻设置页。
                runCatching {
                    startActivity(
                        Intent(this, com.allperiph.screen.ScreenActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            BallConfig.REMOTE -> {
                closeMenu()
                // 遥控器（悬浮栏整合补挂，2026-11-11）：原服务层悬浮栏四入口之一，
                // 栏拆除后由菜单承接 —— 与副屏同口径拉起完整页。
                runCatching {
                    startActivity(
                        Intent(this, com.allperiph.ui.RemoteActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            BallConfig.CAMERA -> {
                closeMenu()
                // 手机画面回传本机：要 CameraX 预览 + 逐帧编码，只能拉起完整页
                //（与「副屏」同口径）。此前这项只能由 PC 面板开关或通知栏动作拉起，
                // 应用内没有入口 —— 手机侧「找不到摄像头」就是缺这一跳。
                runCatching {
                    startActivity(
                        Intent(this, com.allperiph.camera.CameraPushActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            BallConfig.CONSOLE -> {
                closeMenu()
                runCatching {
                    startActivity(
                        Intent(this, com.allperiph.touchpad.TouchpadActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            BallConfig.QUIT -> {
                closeMenu()
                quitApp()
            }
            BallConfig.KB_FORWARD -> onFanToggle(id)
            else -> BallConfig.transportOf(id)?.let { onMenuTransport(it) }
        }
    }

    /** 双开关项的当前态：与设置页同一读点（各自的 prefs 出口），两处状态不会打架 */
    private fun fanToggleOn(id: String): Boolean = when (id) {
        BallConfig.KB_FORWARD -> GlobalKeyForwarder.isEnabled(GlobalKeyForwarder.prefs(this))
        else -> false
    }

    /**
     * 系统无障碍里本应用的转发服务是否已启用。
     *
     * 开关落了盘但无障碍服务没挂 = 按键根本不会送进 onKeyEvent —— 球上若显示「已开启」
     * 就是假的（issue #52「界面状态必须与实际一致」的同款坑）。按类名 contains 判定，
     * 不写死完整组件名，包名变化时也不至于静默误判。
     */
    private fun a11yForwardOn(): Boolean {
        val s = runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull()
        return s?.contains("ApxAccessibilityService") == true
    }

    /**
     * 双开关项（成宇 2026-11-07「悬浮球菜单加上物理键盘全局转发的切换开关，方便不用
     * 的时候关闭」）。语义与连接模式项一致：就地翻转、Toast 报态、**不收菜单**。
     * 写点与设置页完全同一条 prefs；开不起来时（无障碍没启用）**如实回退并提示**，
     * 绝不让球上显示假的「已开启」。
     * （root 直读项已随功能整体移除 —— issue#168 2026-11-11 用户报开关闪退且实测无效。）
     */
    private fun onFanToggle(id: String) {
        val label = getString(BallConfig.labelRes(id))
        val next = !fanToggleOn(id)
        when (id) {
            BallConfig.KB_FORWARD -> {
                GlobalKeyForwarder.setEnabled(GlobalKeyForwarder.prefs(this), next)
                if (next && !a11yForwardOn()) {
                    GlobalKeyForwarder.setEnabled(GlobalKeyForwarder.prefs(this), false)
                    Toast.makeText(this, getString(R.string.ui_ball_toggle_kb_a11y), Toast.LENGTH_SHORT).show()
                    renderFanStates()
                    return
                }
            }
        }
        Toast.makeText(
            this,
            getString(if (next) R.string.ui_ball_menu_mode_on else R.string.ui_ball_menu_mode_off, label),
            Toast.LENGTH_SHORT,
        ).show()
        renderFanStates()
    }

    /**
     * 切换连接模式。与状态页 `StatusFragment.onTransportToggle` 同一套语义：
     * 多出口模块只切「该出口」、不动模块启停；单出口模块跟着传输开关启停；
     * 打开任一路就先拉起前台服务保活，全关了就停掉。
     */
    private fun onMenuTransport(kind: String) {
        // 「切开关 + 热启停该组模块 + 管前台服务」全在 AgentController.applyTransportSwitch 里
        // （v223.29.3：状态页的总闸去掉后，三个传输开关与这里必须共用同一个写点，
        //  否则两条路径的启停语义会各自长歪）。
        val next = !AgentController.isTransportEnabled(this, kind)
        AgentController.applyTransportSwitch(this, kind, next)
        // 2026-11-04 资源化：原 `AgentController.transportLabel(kind)` 直接返回写死的中文
        // （数据层硬编码，与状态页标题同一个双源）—— AgentController 现改存资源 id
        // （transportRes），本层用 Service 上下文解析。
        val label = getString(AgentController.transportRes(kind))
        Toast.makeText(
            this,
            getString(if (next) R.string.ui_ball_menu_mode_on else R.string.ui_ball_menu_mode_off, label),
            Toast.LENGTH_SHORT,
        ).show()
        renderFanStates()
    }

    /**
     * 退出：把所有外设服务停掉，最后收悬浮球自己。
     *
     * 顺序不能反 —— 悬浮球是前台服务，先关它进程就可能被回收，后面的清理就跑不完。
     * 与主界面返回键的退出确认同义（那里是 finish + 服务自停，这里是后台直接清）。
     */
    private fun quitApp() {
        runCatching {
            ControlTarget.clear()
            TvDiscovery.stop()
        }
        AgentController.stopAll()
        stopService(Intent(this, AgentForegroundService::class.java))
        Toast.makeText(this, getString(R.string.ui_ball_menu_quit_done), Toast.LENGTH_SHORT).show()
        shutdown()
    }

    // ————————————————————————— 面板调高（拖拽把手 / 偏好） —————————————————————————

    /**
     * 拖动中：按「手指位移」改窗口高度。
     *
     * 语义取「往上拖 = 变高」：把手在面板**顶边**，手指向上就是顶边向上，窗口跟着长。
     * 所以 `height = 原高 - dy`（dy 向下为正）。夹在 20%~90% 屏高之间 ——
     * 下限保证面板还装得下内容，上限保证还能点到原应用和系统手势区。
     */
    private fun resizePanel(lp: WindowManager.LayoutParams, dyPx: Int) {
        val v = panel ?: return
        val hpx = realScreenSize().second
        val min = hpx * PanelSize.MIN_PCT / 100
        val max = hpx * PanelSize.MAX_PCT / 100
        val next = (lp.height - dyPx).coerceIn(min, max)
        if (next == lp.height) return
        lp.height = next
        runCatching { wm.updateViewLayout(v, lp) }
            .onFailure { Log.w(TAG, "面板改高失败：${it.message}") }
    }

    /**
     * 松手：把当前高度换成屏幕百分比写进偏好，下次展开就按这个比例来。
     *
     * 存百分比而不是 px —— 横竖屏、分屏、换机后 px 全是错的（见 [PanelSize] 的说明）。
     * 只写**比例**，不重建面板：用户拖完手上这块就是他要的大小，重建反而会闪一下。
     */
    private fun resizePanelCommit(lp: WindowManager.LayoutParams) {
        val key = panelSizeKey ?: return
        val hpx = realScreenSize().second
        if (hpx <= 0) return
        // Math.round(Float) 返回 Long，setPercent 要 Int —— 显式取整，别依赖平台推断
        val pct = Math.round(lp.height * 100f / hpx).toInt()
        PanelSize.setPercent(this, key, pct)
        Log.i(TAG, "面板高度已保存：$key = ${pct}%（${lp.height}px / ${hpx}px）")
    }

    /**
     * 面板顶部的拖拽把手（v223.29.9）。
     *
     * 为什么要有它：设置页的滑块能改「下次展开」的高度，但用户真正在用时是在
     * 面板上凭手感调的 —— 让他为了试一个数值来回跳两次界面，等于没有这个功能。
     * 把手给的是「就在这儿，往上拉一点」的直接操作。
     *
     * 视觉：48×10dp 的半透明胶囊垫底 + 里面 30×4dp 的细条，整条 18dp 高都可拖
     * （手指目标不能只有 4dp）。垫底那层是为了压在任意应用画面上都有对比。
     */
    @Composable
    private fun PanelResizeHandle(
        onResize: (Int) -> Unit,
        onCommit: () -> Unit,
    ) {
        var dragDy by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragDy = 0f },
                        onDragEnd = { dragDy = 0f; onCommit() },
                        onDragCancel = { dragDy = 0f },
                    ) { change, drag ->
                        change.consume()
                        // 累计位移按整像素交给窗口：逐帧取 round 会丢小数，慢慢拖就不动
                        dragDy += drag.y
                        val whole = dragDy.toInt()
                        if (whole != 0) {
                            onResize(whole)
                            dragDy -= whole
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // 两层：外面一枚半透明胶囊当「底」，里面一根细条当「梗」。
            // 只画细条不行 —— 面板是 TRANSLUCENT overlay，底下可能是任意应用画面，
            // 细条压在浅色照片上就看不见了；垫一层 surfaceContainer 保证任何背景都有对比。
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f)),
                )
            }
        }
    }

    // ————————————————————————— 展开面板（底部触控板） —————————————————————————

    /**
     * 悬浮触控板面板。
     *
     * ★ v223.29：从「XML 面板 + 裸 setOnTouchListener{ feed(ev) }」换成 Compose
     *   [ApTouchpadSurface] —— 与主壳触控板页**同一份**手势实现。
     *
     * 用户原话「悬浮窗触摸板手势和触摸板页手势不一样」，根因就是这里只写了半条链路：
     * 主壳页除了 feed() 还会做「长按 550ms 锁拖拽 / 双指长按 550ms 右键拖拽」，
     * 且带 slop 门禁（位移一超 touchSlop 就把长按作废，免得双指滚动被判成右键）。
     * 面板少了这两条腿 → 长按没反应、双指滚动偶尔误触发右键；也没有光标 / 尾迹，
     * 更没有任何震动反馈（[GestureEngine] 的里程碑震动走的是
     * [com.allperiph.touchpad.TouchpadModule.onGestureFeedback]，面板这条链路
     * 此前根本没把模块的反馈接出来）。
     *
     * 现在三条腿都在共用面里：事件原样 feed、长按 550ms 排程 + slop 门禁、光标与尾迹。
     * 震动走 TouchpadModule 自己的里程碑节流（模块活着才有），无需界面额外接线。
     */
    private fun expand() {
        if (panel != null) return
        ensureTouchpadReady()
        val (wpx, hpx) = realScreenSize()
        // 竖屏 300dp 量级的手感；横屏屏更矮，按 34% 高并夹在 220~360dp 之间。
        // ★ v223.29.9：用户调过（设置页滑块 / 面板把手）就用他的比例，且**不再夹 dp** ——
        // 220~360dp 这个窗口本身就是「写死」的一部分，用户拖到 90% 却被夹回 360dp 会看着像坏了。
        // 未调过（0f）仍走旧口径，老用户升级上来观感不变。
        val custom = PanelSize.fraction(this, PanelSize.TOUCHPAD)
        val height = if (custom > 0f) {
            (hpx * custom).toInt()
        } else {
            val frac = if (wpx > hpx) 0.34f else 0.30f
            (hpx * frac).toInt().coerceIn(dp(220), dp(360))
        }
        closeMenu()   // 菜单与面板互斥
        val ok = expandComposePanel(height, resizable = PanelSize.TOUCHPAD, paintBg = true) {
            // 底色由 expandComposePanel 的外框统一铺（paintBg = true）——
            // 这里再画一层 surfaceContainer 会和把手那条带子在接缝处叠出两种色差。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ui_main_label_floating_ball),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = stringResource(R.string.ui_main_floating_ball_hint),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    ApComponents.NarrowButton(
                        text = stringResource(R.string.ui_main_floating_ball_collapse),
                        onClick = { collapse() },
                    )
                }
                ApTouchpadSurface(
                    onGesture = { ev ->
                        // 与触控板页同一条链路：feed → GestureEngine → 三出口（USB / 蓝牙 / 无线）
                        (AgentController.module(com.allperiph.core.ModuleId.TOUCHPAD) as? TouchpadModule)
                            ?.feed(ev)
                    },
                    onLongPressArmDrag = {
                        (AgentController.module(com.allperiph.core.ModuleId.TOUCHPAD) as? TouchpadModule)
                            ?.armDrag()
                    },
                    onLongPressRightDrag = {
                        (AgentController.module(com.allperiph.core.ModuleId.TOUCHPAD) as? TouchpadModule)
                            ?.armRightDrag()
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
        // 悬浮球的触控板同样是三指滑动面：展开期间一并遮蔽系统三指截屏，
        // 收起就还（这个面板压在别的应用上，不还的话用户在桌面想三指截屏会按不动）。
        // expandComposePanel 负责挂窗口与收球；这里只补遮蔽这一件事 ——
        // 面板没挂上就不能遮蔽，否则三指手势被永久关着。
        if (ok) com.allperiph.touchpad.GestureShield.acquireWithHint(this, HOLDER_BALL)
    }

    // ————————————————————————— Compose 悬浮面板（键盘 / 手柄 / 设备） —————————————————————————

    /**
     * 在 overlay 窗口里挂一块 Compose 面板。
     *
     * Activity 之外 ComposeView 默认没有 LifecycleOwner / SavedStateRegistryOwner，
     * 起不了 Recomposer —— [OverlayComposeHost] 手动补这三件套并驱动生命周期：
     * create → 挂窗口 → start/resume，收起时 destroy（重组协程随 lifecycle 释放）。
     *
     * 面板互斥复用 [panel] 槽位（触控板 XML 面板同款）：展开时收球，收起时还球。
     * 输入面板全部 FLAG_NOT_FOCUSABLE：键位/摇杆只吃触摸，不碰 IME。
     */
    /**
     * @param resizable 非空时面板**可以调高**：顶部画一条把手，上下拖即改窗口高度，
     *   松手把当时比例写进 [PanelSize]（键见 [PanelSize.TOUCHPAD] / [PanelSize.KEYBOARD]）。
     *   传 null 的面板（手柄全屏、设备选择、剪贴板）保持原样 —— 那几块是
     *   MATCH_PARENT / WRAP_CONTENT，前者的高度不是「大小」问题，后者的高度由内容决定，
     *   硬塞一根把手只会让「拖了没反应」。
     * @param paintBg 把手所在的整块外框是否铺 `surfaceContainer` 底。触控板面板本来
     *   整块就是这张卡，铺上才不会在顶部多出一条 18dp 的透明缝；键盘面板本来就是
     *   「键帽浮在透明上」，铺底会把整块键盘变成一堵实心墙 —— 所以它传 false，
     *   把手靠自己的胶囊垫底维持可见。
     */
    private fun expandComposePanel(
        height: Int,
        keepScreenOn: Boolean = false,
        resizable: String? = null,
        paintBg: Boolean = false,
        content: @Composable () -> Unit,
    ): Boolean {
        if (panel != null) return false
        val host = OverlayComposeHost().also { it.create() }
        val cv = ComposeView(themed)
        cv.setViewTreeLifecycleOwner(host)
        cv.setViewTreeSavedStateRegistryOwner(host)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        cv.setContent {
            ApMiuixTheme {
                // ⚠️ 只有可调高的面板才包 Column + fillMaxSize。其余面板（设备选择 / 剪贴板
                // 走 WRAP_CONTENT、手柄走 MATCH_PARENT）必须保持「内容即根」——
                // 给 WRAP_CONTENT 的窗口塞一个 fillMaxSize 的根会把窗口撑到满屏，
                // 那几块面板当场变成整块黑底盖住原应用。
                if (resizable != null) {
                    // ⚠️ `weight(1f)` 只属于 ColumnScope：PanelResizeHandle 是 receiver 为
                    // Unit 的 @Composable，写不进它的链式调用；把内容盒留在 Column lambda
                    // 里、把手先单独占一行，scope 问题自然不存在（把手本身不需要 weight）。
                    Column(
                        modifier = if (paintBg) {
                            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surfaceContainer)
                        } else {
                            Modifier.fillMaxSize()
                        },
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            PanelResizeHandle(
                                onResize = { dyPx -> resizePanel(lp, dyPx) },
                                onCommit = { resizePanelCommit(lp) },
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
                    }
                } else {
                    // 非可调面板保持原样：连 Box 都不加。WRAP_CONTENT 的窗口塞一层
                    // wrap-content 盒子会多一个测量层，尺寸看着一样但没必要动它。
                    content()
                }
            }
        }
        closeMenu()   // 菜单与面板互斥
        val attached = runCatching { wm.addView(cv, lp) }.onFailure {
            Log.w(TAG, "Compose 面板挂载失败：${it.message}")
            host.destroy()
        }.isSuccess
        if (!attached) return false
        host.start()
        host.resume()
        panel = cv
        panelHost = host
        panelLp = lp
        panelSizeKey = resizable
        // 展开时收起球（与触控板面板同口径，避免叠在面板上）
        ball?.let { if (ballAttached) { detach(it); ballAttached = false } }
        return true
    }

    /**
     * 悬浮键盘：主屏键盘页同款 8 页签全键盘（[ApFullKeyboard] 弹窗形态，onClose 非空
     * 自带「全键盘」标题 + 关闭键），键位直喂 HID —— 服务上下文就能发，不需要 Activity。
     * 横屏高度只有竖屏一半：横屏占 66% 高、竖屏 52%，底部对齐，上半屏留给原应用。
     */
    private fun expandKeyboard() {
        val (wpx, hpx) = realScreenSize()
        // ★ v223.29.9：同 expand()，用户调过就听用户的（见 [PanelSize]）
        val custom = PanelSize.fraction(this, PanelSize.KEYBOARD)
        val height = if (custom > 0f) {
            (hpx * custom).toInt()
        } else {
            val fraction = if (wpx > hpx) 0.66f else 0.52f
            (hpx * fraction).toInt()
        }
        // issue#168（2026-11-11 悬浮球清单）：悬浮键盘**铺底**。此前按「键帽浮在透明上」
        // 不铺 paintBg，悬浮键盘直接叠在原应用上，键帽标签与底下的内容混在一起看不清
        //（用户原话「加一个背景，避免上方文字对比度过低看不清」）。铺底让整块键盘
        // 有 surfaceContainer 实底，对比度回归正常；把手仍在顶部（resizable 面板同款）。
        expandComposePanel(height, resizable = PanelSize.KEYBOARD, paintBg = true) {
            // ★ issue#168 返工（2026-11-12「横竖屏切换布局回归」）：overlay 的 ComposeView
            //  挂在 WindowManager 上，LocalWindowInfo.containerSize / LocalConfiguration
            //  给的是小窗/服务竖屏配置，横屏时 ApFullKeyboard 拿到 portraitPhone=true，
            //  画出竖屏布局、键帽文字显示不全。这里用 realScreenSize 实测整屏宽高
            //  （dp 换算走服务 density）覆盖自适应判定 —— 键盘内部所有派生量
            //  （横竖排 / Expanded / portraitPhone）都按整屏口径重算。
            val den = resources.displayMetrics.density
            ApFullKeyboard(
                onClose = { collapse() },
                bottomGap = 0.dp,
                landscapeOverride = wpx > hpx,
                widthDpOverride = wpx / den,
                heightDpOverride = hpx / den,
            )
        }
    }

    /**
     * 确保触控板模块就绪（v223.29.1）。
     *
     * 真机 2026-11-02 第二轮反馈「悬浮窗触摸板手势和触摸板页手势不一样，也没有触摸页的
     * 特效震动」，根因**不在手势识别本身**（两处都已共用 [ApTouchpadSurface]，
     * 长按 550ms / 双指长按 / slop 门禁 / 光标尾迹都是同一份），而在**模块没在跑**：
     *
     * 触控板模块只在两条路径上进入 RUNNING ——
     *   · 状态页「开启外设」（[AgentController.startEnabled]），
     *   · [AgentForegroundService] 起来时（它 onCreate/onStartCommand 会 startEnabled）。
     * 而主界面被划掉、进程被系统回收之后，悬浮球是由自检闹钟 / 开机广播**单独**拉起的
     * （v223.29 补的自愈正是这个场景）—— 那时进程里只有球和它的 overlay，
     * 模块注册表可能压根没建、模块更是 IDLE。`TouchpadModule.feed()` 在
     * `engineRef == null` 时直接把事件丢掉（只打一行限频日志），于是
     * 「怎么划都不动」；震动同理 —— 里程碑反馈走
     * `GestureEngine.onFeedback → TouchpadModule.onGestureFeedback`，
     * 模块不跑这条链路就不存在，观感上就是「没有触摸页的特效震动」。
     *
     * 这里在展开触控板面板前补齐三件事：
     *   1. 进程里没有注册表就 [AgentController.build]（幂等）；
     *   2. 用户**关掉过**该模块就什么都不做（与悬浮球「只认意愿位」同一口径，
     *      绝不替用户打开他明确关掉的外设）；
     *   3. 模块存在但不在跑 → 拉起它（走 io 线程，面板先出来、模块随后就位）。
     */
    private fun ensureTouchpadReady() {
        runCatching {
            AgentController.build(this)
            val id = com.allperiph.core.ModuleId.TOUCHPAD
            if (!AgentController.isEnabled(this, id)) return@runCatching
            val m = AgentController.module(id) ?: return@runCatching
            if (m.state.isActive) return@runCatching
            Log.i(TAG, "悬浮触控板面板：模块不在跑（state=${m.state}）→ 按用户开关位拉起")
            AgentController.setModuleEnabled(this, id, true)
        }.onFailure { Log.w(TAG, "触控板模块就绪检查失败：${it.message}") }
    }

    /**
     * 悬浮手柄：[GamepadPanel]（GamepadActivity 同款双摇杆），全屏 overlay + KEEP_SCREEN_ON。
     *
     * v223.29.6：外面套 [LandscapePanel] —— overlay 没有 `requestedOrientation`，
     * 竖屏打开时此前就是「手柄挤在半屏」（成宇 2026-11-04「悬浮板的手柄，没有强制横屏」）。
     * 容器在竖屏时把内容按横屏尺寸布局并旋转 90°，与主屏 [GamepadActivity] 的横屏观感一致。
     */
    private fun expandGamepad() {
        expandComposePanel(WindowManager.LayoutParams.MATCH_PARENT, keepScreenOn = true) {
            LandscapePanel { GamepadPanel(onExit = { collapse() }) }
        }
    }

    /** 悬浮设备选择：[ApDevicePanel] 与主屏选择器同一份内容（TvDiscovery 广播列表），选中即连。 */
    private fun expandDevices() {
        // issue#168（2026-11-11 悬浮球清单）：横屏改左右双栏 —— 左=被控模式开关 + 关闭，
        // 右=最近连接 + 扫描列表（超高可滚 + 右侧滚动条）。横竖按**屏幕实宽实高**判，
        // overlay 窗口没有 Configuration 可依赖。
        val (wpx, hpx) = realScreenSize()
        // 右列限高：overlay 是 WRAP_CONTENT 窗口，面板拿不到可靠窗高，按屏幕实高折算
        // 传入（约 0.55 屏，给左栏操作区留足空间）；竖屏单列不限高（传 0）。
        val density = resources.displayMetrics.density
        val listMaxDp = if (wpx > hpx) ((hpx / density) * 0.55f).toInt() else 0
        expandComposePanel(WindowManager.LayoutParams.WRAP_CONTENT) {
            ApDevicePanel(
                onClose = { collapse() },
                landscape = wpx > hpx,
                listMaxHeightDp = listMaxDp,
                // issue#168 返工（2026-11-12「设备页文字与关闭按钮居中」）
                centered = true,
            )
        }
    }

    /**
     * 悬浮剪贴板面板（v223.29）。
     *
     * 剪贴板同步此前**只在主界面注册**（[com.allperiph.ui.ClipboardSync] 由
     * ApShellActivity.onResume 挂、onPause 摘）—— 主界面一退到后台，手机上复制的文字
     * 就不再发往对端了，可这时候用户往往正用悬浮球在别的应用里干活。用户要的
     * 「悬浮窗要加剪贴板」正是这个缺口。
     *
     * 这里给两件事：
     *   · 面板里显示当前剪贴板内容 + 一键补发（走 [ControlTarget.controlClient] 的 0x20 帧）；
     *   · 顺便把后台监听补齐 —— 服务活着就一直听（见 [ensureClipboardTap]）。
     */
    private fun expandClipboard() {
        // 取值口径与后台监听同一处（ClipboardSync）：此前这里内联读一遍、ClipboardSync
        // 里又读一遍，两处的「程序性写入抑制 / 空串判定」迟早会跑偏。
        val text = com.allperiph.ui.ClipboardSync.currentText().orEmpty()
        expandComposePanel(WindowManager.LayoutParams.WRAP_CONTENT) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                ApComponents.Card {
                    Column(
                        // issue#168 返工（2026-11-12「悬浮球剪贴板/设备页文字与关闭按钮居中」）：
                        // 面板窄，标题/提示/内容左对齐挤在左半边不好看，整列水平居中。
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.ui_ball_fn_clipboard_title),
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Text(
                            text = stringResource(R.string.ui_ball_fn_clipboard_hint),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text(
                            text = if (text.isEmpty()) stringResource(R.string.ui_ball_fn_clipboard_empty)
                            else stringResource(
                                R.string.ui_ball_fn_clipboard_now,
                                text.take(120) + if (text.length > 120) "…" else "",
                            ),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        ApComponents.ActionButton(
                            text = stringResource(R.string.ui_ball_fn_clipboard_send),
                            onClick = {
                                val client = ControlTarget.controlClient
                                if (client == null || !client.ready) {
                                    Toast.makeText(
                                        this@FloatingBallService,
                                        getString(R.string.ui_ball_fn_clipboard_nolink),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@ActionButton
                                }
                                // 手指落下到按下按钮之间用户可能又复制了别的：再取一次当前的
                                val cur = com.allperiph.ui.ClipboardSync.currentText().orEmpty()
                                if (cur.isEmpty()) {
                                    Toast.makeText(
                                        this@FloatingBallService,
                                        getString(R.string.ui_ball_fn_clipboard_empty),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@ActionButton
                                }
                                val ok = runCatching { client.sendClipboard(cur) }.getOrDefault(false)
                                Toast.makeText(
                                    this@FloatingBallService,
                                    if (ok) getString(R.string.ui_ball_fn_clipboard_copied, cur.length)
                                    else getString(R.string.ui_ball_fn_clipboard_nolink),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (ok) collapse()
                            },
                        )
                        ApComponents.NarrowButton(
                            text = stringResource(R.string.ui_help_close),
                            onClick = { collapse() },
                        )
                    }
                }
            }
        }
    }

    private fun collapse() {
        detach(panel)
        panel = null
        panelHost?.destroy()
        panelHost = null
        panelLp = null
        panelSizeKey = null
        // 收起就走延迟还原（1.2s 内又展开就不用白跑两次 su）
        com.allperiph.touchpad.GestureShield.releaseSoon(this, HOLDER_BALL)
        addBall()
    }
}

/**
 * overlay 窗口里的 Compose 宿主：ComposeView 起体外 Recomposer 需要
 * LifecycleOwner + SavedStateRegistryOwner（顺带 ViewModelStoreOwner），
 * Service 不天然带这三件套 —— 这里手动驱动：create 之后挂窗口，挂上即 start/resume，
 * 收起时 destroy（Recomposer 协程随 lifecycle 释放，不泄漏）。
 */
private class OverlayComposeHost :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore get() = store

    /** 挂窗口前调用：SavedStateRegistry 必须在 ON_CREATE 之前完成 restore。 */
    fun create() {
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

    fun resume() = registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}