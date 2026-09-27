package com.allperiph.screen

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import com.allperiph.R
import com.allperiph.core.Log
import com.allperiph.core.MediaOut
import com.allperiph.wireless.ControlTarget
import kotlin.math.hypot

/**
 * 副屏全屏页：把 PC 推来的画面铺满手机屏幕。
 *
 * 用 `TextureView` 而不是 `SurfaceView`：SurfaceView 在沉浸式/过渡动画时容易出现
 * 黑块与层级问题（旧实现也因此改用 TextureView）。
 *
 * 页面本身不碰编解码 —— 它只负责把一个 `Surface` 交给 [ScreenRenderer]，
 * 真正的解码发生在 [ScreenModule] 收到的帧上。因此**退出页面不影响模块收流**，
 * 只是画面无处可画（此时帧会被丢弃并计数，[ScreenRenderer.stats] 会如实显示）。
 */
class ScreenActivity : Activity(), TextureView.SurfaceTextureListener {

    private lateinit var view: TextureView
    private lateinit var hint: TextView

    /**
     * 状态字必须**周期刷新**：首帧是在页面打开之后才到的，只在 onCreate 刷一次
     * 会让用户一直看到「等待画面」——而画面其实已经在跑了（真机踩过）。
     */
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshHint()
            ticker.postDelayed(this, HINT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_screen)
        view = findViewById(R.id.surfaceScreen)
        hint = findViewById(R.id.tvScreenHint)
        view.surfaceTextureListener = this
        installTouchBridge()
        refreshHint()
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
        // 切回副屏页：请 PC 立即出一帧 IDR（控制通道 0x06），否则要等编码端下一个
        // 关键帧，断连久了能黑好几秒。未选受控设备时发送失败，靠编码端周期 IDR 兜底。
        ControlTarget.controlClient?.sendControl(byteArrayOf(0x06))
        // fix(v184)：副屏期间持高性能 WiFi 锁 —— 省电模式掐 WiFi 是"老是断"的主因
        // （全仓库此前无任何 WifiLock/WakeLock）。锁挂在 Activity 生命周期上，
        // 离开副屏页即释放，不会常驻耗电。
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "apx-screen").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i("ScreenActivity", "副屏：WiFi 高性能锁已获取（防省电掐流）")
        }
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        wifiLock?.let {
            runCatching { it.release() }
            Log.i("ScreenActivity", "副屏：WiFi 高性能锁已释放")
        }
        wifiLock = null
        super.onPause()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        // 必须先 detach 再让 Surface 失效：否则解码器还持有已销毁的 Surface
        view.surfaceTextureListener = null
        ScreenRenderer.detachSurface()
        super.onDestroy()
    }

    // ————————————————————— 触摸桥：手机画面 → PC 鼠标 —————————————————————

    /** 双指手势进行中（滚动或待判定的右键点按） */
    private var twoFinger = false
    /** 双指滚动：第二根手指上一帧的 Y（累计滚动量用） */
    private var prevTwoY = 0f
    /** 双指滚动累计（像素）；每滚动滚一格就发一档滚轮 */
    private var scrollAccum = 0f
    /** 本次双指手势里已发生过滚动（抬起时就不再是"右键点按"） */
    private var scrolled = false

    // ———————— 触控板语义状态（v184）————————
    /** 长按拖拽已激活（左键已按下，MOVE 即拖动） */
    private var dragMode = false
    private var downX = 0f; private var downY = 0f
    private var downAt = 0L
    private var lastX = 0f; private var lastY = 0f
    private var lastXNorm = 0; private var lastYNorm = 0
    /** 长按检测回调 */
    private var dragArm: Runnable? = null
    /** 轻点判定阈值（位移） */
    private val tapSlopPx get() = 18 * resources.displayMetrics.density

    /** 副屏会话期间持有高性能 WiFi 锁：省电模式掐流是"老是断"的主因 */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // ———————— 点按触觉反馈 + 双击检测（v184）————————
    /** 待发的单击（延迟 250ms，等待可能的第二击组成双击） */
    private var pendingTap: Runnable? = null
    private var lastTapUpAt = 0L
    /** 上一次轻点的落点（**手机像素**坐标；判定双击必须同单位，归一化值会差 60 倍） */
    private var lastTapPxX = 0f; private var lastTapPxY = 0f
    private var downNormX = 0; private var downNormY = 0

    private fun buzz(ms: Long) {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26)
                vib.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib.vibrate(ms)
        }
    }

    /** 以 (nx, ny) 发一次左键点击（按下+抬起同坐标，PC 光标不漂移） */
    private fun sendClick(cli: com.allperiph.wireless.TvControllerClient?, nx: Int, ny: Int) {
        cli?.touch(MediaOut.TOUCH_DOWN, MediaOut.BTN_LEFT, nx, ny)
        cli?.touch(MediaOut.TOUCH_UP, MediaOut.BTN_LEFT, nx, ny)
    }

    /** 双指滚动：累计多少像素算滚一格 */
    private val scrollStepPx get() = (36 * resources.displayMetrics.density).toInt().coerceAtLeast(24)

    /**
     * 触摸坐标 → 归一化 0..65535。
     *
     * 按**整个视图**归一化：MediaCodec 渲染到 TextureView 是拉伸填充（无黑边），
     * 实测按画面比例做 fitCenter 反而引入系统性偏移——视图坐标与画面坐标
     * 一一对应，直接归一化即准。视频尺寸字段保留（ScreenRenderer.videoWidth），
     * 将来若渲染端改成保持比例的 letterbox，这里再启用 fitCenter。
     */
    private fun normalized(v: android.view.View, e: android.view.MotionEvent): Pair<Int, Int> {
        val w = v.width.coerceAtLeast(1)
        val h = v.height.coerceAtLeast(1)
        return Pair(
            ((e.x / w) * 65535f).toInt().coerceIn(0, 65535),
            ((e.y / h) * 65535f).toInt().coerceIn(0, 65535),
        )
    }

    /**
     * 触摸桥：把本页手势按归一化坐标经 9511 控制面（[com.allperiph.wireless.ControlTarget]）上行给受控端（控制帧 0x04），
     * PC 端 SendInput 注入成鼠标 —— 副屏从此不止能看，还能点。
     *
     * 走 9511 控制面而不是 9502 媒体通道：后者只在推流/音箱时建立，
     * 而控制通道在「无线」开着时始终在线（实测踩过：媒体通道未连入时触摸全丢）。
     *
     * 手势表（v184 起改为**触控板语义**；旧版"按下即拖"会让滑动变按住拖选，已改）：
     *   单指滑动       = 移动光标（不按键）
     *   单指点按       = 左键点击（位移小 + 时间短）
     *   长按后拖动     = 左键拖拽（≥400ms 且几乎没位移时激活，可拖窗口/文件）
     *   双指滑动       = 滚轮（自然方向：手指上滑内容上移）
     *   双指点按       = 右键（位移小于滚动阈值）
     *
     * 防卡键：拖拽激活后若 UP/CANCEL 因断链丢失，PC 左键会一直按着 ——
     * 因此每次新手势开始时先兜底补一个 TOUCH_UP（多余的一次 UP 在 PC 端无害）。
     * 发送失败如实丢弃：控制通道未连入时点按无效果，但不影响收流显示。
     */
    private fun installTouchBridge() {
        view.setOnTouchListener { v, e ->
            val (x, y) = normalized(v, e)
            val cli = ControlTarget.controlClient
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    twoFinger = false
                    scrolled = false
                    dragMode = false
                    downX = e.x; downY = e.y
                    downNormX = x; downNormY = y
                    downAt = android.os.SystemClock.uptimeMillis()
                    // 兜底：上一笔的 UP 若因断链丢失，这里补松左键（PC 端多余 UP 无害）
                    cli?.touch(MediaOut.TOUCH_UP, MediaOut.BTN_LEFT, x, y)
                    // 触控板语义：落指先把光标带到手指位置（纯移动，不按键）
                    cli?.touch(MediaOut.TOUCH_MOVE, MediaOut.BTN_LEFT, x, y)
                    // 长按检测：400ms 后仍几乎没位移 → 进入拖拽（发左键按下）
                    dragArm?.let { view.removeCallbacks(it) }
                    dragArm = Runnable {
                        val moved = hypot(
                            (lastX - downX).toDouble(),
                            (lastY - downY).toDouble()
                        ) < tapSlopPx
                        if (!twoFinger && !dragMode &&
                            android.os.SystemClock.uptimeMillis() - downAt >= 380 &&
                            moved && !scrolled
                        ) {
                            dragMode = true
                            buzz(15)
                            ControlTarget.controlClient?.touch(
                                MediaOut.TOUCH_DOWN, MediaOut.BTN_LEFT, lastXNorm, lastYNorm
                            )
                        }
                    }
                    view.postDelayed(dragArm, 400)
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                    if (e.pointerCount >= 2 && !twoFinger) {
                        twoFinger = true
                        scrolled = false
                        dragArm?.let { view.removeCallbacks(it) }
                        pendingTap?.let { view.removeCallbacks(it) }   // 手势升级：取消待发的单击
                        scrollAccum = 0f
                        prevTwoY = e.getY(e.pointerCount - 1)
                        // 若长按拖拽已激活，双指接管前先松左键
                        if (dragMode) {
                            cli?.touch(MediaOut.TOUCH_UP, MediaOut.BTN_LEFT, x, y)
                            dragMode = false
                        }
                    } else true
                android.view.MotionEvent.ACTION_MOVE -> {
                    lastX = e.x; lastY = e.y
                    lastXNorm = x; lastYNorm = y
                    if (twoFinger && e.pointerCount >= 2) {
                        val y2 = e.getY(e.pointerCount - 1)
                        scrollAccum += y2 - prevTwoY
                        prevTwoY = y2
                        // v184：滚动死区 —— 双指点按的手指轻颤幅度远小于 scrollStepPx，
                        // 但旧的累计方式让微小颤动也可能滚出档位、吞掉右键判定。
                        // 死区：累计量未越过半档之前一律视为"没在滚"，归零计。
                        if (kotlin.math.abs(scrollAccum) < scrollStepPx / 2f) {
                            scrollAccum = 0f
                            true
                        } else {
                        var sent = true
                        // 自然方向：手指上滑（acc 为负）= 内容上移 = 滚轮向前（+）
                        while (sent) {
                            if (scrollAccum <= -scrollStepPx) {
                                sent = cli?.mouse(0, 0, 0, 1) ?: false
                                scrollAccum += scrollStepPx
                            } else if (scrollAccum >= scrollStepPx) {
                                sent = cli?.mouse(0, 0, 0, -1) ?: false
                                scrollAccum -= scrollStepPx
                            } else break
                            scrolled = true
                        }
                        sent
                        }
                    } else {
                        // 单指移动：MOVE 本身就是纯光标移动（PC 端 MOVE 忽略按键位）；
                        // 拖拽模式下左键已按着，MOVE 同样生效
                        cli?.touch(MediaOut.TOUCH_MOVE, MediaOut.BTN_LEFT, x, y)
                        true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val cancelled = e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                    dragArm?.let { view.removeCallbacks(it) }
                    if (twoFinger) {
                        if (!scrolled && !cancelled) {
                            // 双指点按（几乎没位移）= 右键点击
                            cli?.touch(MediaOut.TOUCH_DOWN, MediaOut.BTN_RIGHT, x, y)
                            cli?.touch(MediaOut.TOUCH_UP, MediaOut.BTN_RIGHT, x, y)
                            buzz(12)
                        }
                        twoFinger = false
                        true
                    } else if (dragMode) {
                        // 拖拽中抬起 = 松开左键
                        dragMode = false
                        cli?.touch(if (cancelled) MediaOut.TOUCH_CANCEL else MediaOut.TOUCH_UP, MediaOut.BTN_LEFT, x, y)
                    } else if (!cancelled) {
                        // 轻点 = 左键点击（位移小 + 时间短）；快速滑动则只移动了光标，无需点击。
                        // v184：轻点**延迟 250ms 发出** —— 期间若来第二次轻点则合并为双击
                        //（四帧紧凑连发，Windows 必判双击；旧方案两次独立点击因绝对定位的
                        //  手指物理偏差超出双击容差，双击几乎点不出来）。
                        //  down/up 同用 DOWN 时的归一化坐标，光标不漂移。
                        val dur = android.os.SystemClock.uptimeMillis() - downAt
                        val moved = hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < tapSlopPx
                        if (moved && dur < 300) {
                            val now = android.os.SystemClock.uptimeMillis()
                            // 双击判定用**手机像素**落点（与 tapSlopPx 同单位），窗口 400ms（对齐 Windows 500ms）
                            val isDouble = now - lastTapUpAt < 400 &&
                                hypot((downX - lastTapPxX).toDouble(), (downY - lastTapPxY).toDouble()) < tapSlopPx * 2
                            if (isDouble) {
                                pendingTap?.let { view.removeCallbacks(it) }
                                pendingTap = null
                                lastTapUpAt = 0L
                                sendClick(cli, downNormX, downNormY)
                                sendClick(cli, downNormX, downNormY)
                                buzz(20)
                            } else {
                                pendingTap?.let { view.removeCallbacks(it) }
                                pendingTap = Runnable {
                                    sendClick(ControlTarget.controlClient, downNormX, downNormY)
                                    buzz(10)
                                    pendingTap = null
                                }
                                view.postDelayed(pendingTap, 250)
                                lastTapUpAt = now; lastTapPxX = downX; lastTapPxY = downY
                            }
                        }
                        true
                    } else true
                }
                else -> true
            }
            true
        }
    }

    // ————————————————————— TextureView.SurfaceTextureListener —————————————————————

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        ScreenRenderer.attachSurface(Surface(st))
        refreshHint()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // 分辨率变化由 ScreenRenderer 依帧内信息重建解码器，这里无需处理
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        ScreenRenderer.detachSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // 每帧回调，别在这里做任何重活
    }

    /** 状态提示：收流中但无画面时要能说清原因，而不是只给黑屏 */
    private fun refreshHint() {
        val rt = com.allperiph.ui.AgentController.runtime
        if (rt == null) {
            hint.text = getString(R.string.ui_screen_text_open_not_please_state)
            return
        }
        val m = com.allperiph.ui.AgentController.module(com.allperiph.core.ModuleId.SCREEN)
        val txt = m?.let { runCatching { it.statusText() }.getOrDefault("--") } ?: "--"
        hint.text = txt
        Log.i(TAG, "副屏页：$txt")
    }

    private companion object {
        const val TAG = "ScreenActivity"

        /** 状态字刷新间隔：够快能看到数字在涨，又不至于每秒刷很多次 */
        const val HINT_INTERVAL_MS = 1_000L
    }
}
