package com.allperiph.screen

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import com.allperiph.R
import com.allperiph.core.Log
import com.allperiph.core.MediaOut
import com.allperiph.core.TcpCtrlBridge

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
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
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

    /** 双指期间置 true：后续 move/up 都按右键处理（松开才结束右键手势） */
    private var rightClick = false

    /**
     * 触摸桥：把本页手势按归一化坐标经 [TcpCtrlBridge.touch]（控制通道 0x04）上行给 PC，
     * PC 端 SendInput 注入成鼠标 —— 副屏从此不止能看，还能点。
     *
     * 走 9500 控制通道而不是 9502 媒体通道：后者只在推流/音箱时建立，
     * 而控制通道在「无线」开着时始终在线（实测踩过：媒体通道未连入时触摸全丢）。
     *
     * MVP 取主指针；**双指点按 = 右键**（保持到全部手指抬起）。发送失败如实丢弃：
     * 控制通道未连入时点按无效果，但不影响收流显示。
     */
    private fun installTouchBridge() {
        view.setOnTouchListener { v, e ->
            val w = v.width.coerceAtLeast(1)
            val h = v.height.coerceAtLeast(1)
            val x = ((e.x / w) * 65535f).toInt().coerceIn(0, 65535)
            val y = ((e.y / h) * 65535f).toInt().coerceIn(0, 65535)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    TcpCtrlBridge.touch(MediaOut.TOUCH_DOWN, MediaOut.BTN_LEFT, x, y)
                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                    if (e.pointerCount >= 2 && !rightClick) {
                        rightClick = true
                        TcpCtrlBridge.touch(MediaOut.TOUCH_DOWN, MediaOut.BTN_RIGHT, x, y)
                    } else true
                android.view.MotionEvent.ACTION_MOVE -> {
                    val btn = if (rightClick) MediaOut.BTN_RIGHT else MediaOut.BTN_LEFT
                    TcpCtrlBridge.touch(MediaOut.TOUCH_MOVE, btn, x, y)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val btn = if (rightClick) MediaOut.BTN_RIGHT else MediaOut.BTN_LEFT
                    rightClick = false
                    TcpCtrlBridge.touch(
                        if (e.actionMasked == android.view.MotionEvent.ACTION_CANCEL)
                            MediaOut.TOUCH_CANCEL else MediaOut.TOUCH_UP,
                        btn, x, y
                    )
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
            hint.text = "服务未启动：请在状态页打开无线开关"
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
