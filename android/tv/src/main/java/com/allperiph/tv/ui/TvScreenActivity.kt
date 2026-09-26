package com.allperiph.tv.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.allperiph.tv.TvServerService
import com.allperiph.tv.media.TvRenderer
import com.allperiph.tv.media.TvSpeaker

/**
 * TV 端「副屏」：把电脑桌面**投到电视上**（PC → TV，走 9502 媒体通道）。
 *
 * ## 画面自适应（电视的重点）
 * 电脑画面与电视的宽高比几乎不可能刚好一致（16:9 的电视看 16:10 / 21:9 的桌面、或反过来），
 * 直接拉伸会变形。这里按**等比 fit-center** 摆放 Surface：取视频宽高比与可用区域求交，
 * 居中并留黑边（letterbox），且在**过扫描安全边距**之内 —— 四周不会被电视裁掉。
 * 分辨率变化时（PC 换分辨率/旋转）由 [TvRenderer.onFormatChanged] 触发重算。
 *
 * ## 音频
 * 进页面即启动 [TvSpeaker]（电脑的声音一起到电视/音响），退页面停掉。
 */
class TvScreenActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    /** 屏上返回按钮（用手机控制时屏幕上必须看得见出口） */
    private lateinit var backButton: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    /** 每秒刷新一次状态行。**显式声明类型**：写成 `val tick = Runnable { postDelayed(tick) }`
     *  会让 Kotlin 类型推断陷入递归，必须给类型并借 `this` 重排自己。 */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        surfaceView.holder.addCallback(this)
        TvRenderer.onFormatChanged = { _, _ -> mainHandler.post { fitSurface() } }
    }

    override fun onResume() {
        super.onResume()
        // 用户从首页进来时，服务端可能已经起好媒体通道了；没起的话这里兜一下
        TvServerService.current?.ensureMedia()
        mainHandler.post(tick)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tick)
        TvRenderer.onFormatChanged = null
        TvSpeaker.stop()
        super.onDestroy()
    }

    // ————————————————————————————— 界面 —————————————————————————————

    private fun buildUi(): FrameLayout {
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
        }
        root.addView(surfaceView)

        val overlay = FrameLayout(this).apply {
            val pad = TvUi.safeInset(this@TvScreenActivity)
            setPadding(pad, pad / 2, pad, pad / 2)
            isClickable = false
            isFocusable = false
        }
        statusView = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            TvUi.applyTextSize(this, 13f)
            text = "等待电脑画面…"
        }
        overlay.addView(statusView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        // ★ 屏上返回按钮：原先只能靠遥控器的返回键退出副屏页 ——
        //   用手机控制时屏幕上**看不到出口**（文件页有「← 返回」，这里没有，不一致）。
        backButton = TextView(this).apply {
            text = "← 返回"
            setTextColor(android.graphics.Color.WHITE)
            TvUi.applyTextSize(this, 14f)
            isFocusable = true
            isClickable = true
            background = TvUi.focusBg(
                TvUi.CARD, TvUi.CARD_FOCUS,
                TvUi.dp(this@TvScreenActivity, 10f), TvUi.dp(this@TvScreenActivity, 3f),
            )
            val p = TvUi.dp(this@TvScreenActivity, 12f)
            setPadding(p * 2, p, p * 2, p)
            setOnClickListener { finish() }
        }
        overlay.addView(backButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    /** 等比 fit-center：居中留黑边，并留在过扫描安全边距内 */
    private fun fitSurface() {
        val vw = TvRenderer.videoWidth
        val vh = TvRenderer.videoHeight
        val availW = root.width
        val availH = root.height
        if (vw <= 0 || vh <= 0 || availW <= 0 || availH <= 0) {
            surfaceView.layoutParams = FrameLayout.LayoutParams(availW, availH, Gravity.CENTER)
            return
        }
        // 安全边距：电视会把四周 3~5% 裁掉，画面不能顶到边
        val inset = TvUi.safeInset(this)
        val boxW = (availW - inset * 2).coerceAtLeast(1)
        val boxH = (availH - inset * 2).coerceAtLeast(1)
        val scale = minOf(boxW.toFloat() / vw, boxH.toFloat() / vh)
        val w = (vw * scale).toInt().coerceAtLeast(1)
        val h = (vh * scale).toInt().coerceAtLeast(1)
        surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        surfaceView.requestLayout()
    }

    private fun refreshStatus() {
        val media = TvServerService.current?.mediaStatus() ?: "媒体通道未启动"
        statusView.text = "$media · ${TvRenderer.stats()} · ${TvSpeaker.stats()}"
    }

    // ————————————————————————————— Surface —————————————————————————————

    override fun surfaceCreated(holder: SurfaceHolder) {
        TvRenderer.attachSurface(holder.surface)
        TvSpeaker.start()
        mainHandler.post { fitSurface() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mainHandler.post { fitSurface() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        TvRenderer.detachSurface()
        TvSpeaker.stop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 遥控器返回键退出副屏，回到首页（不要把整台电视的返回吃掉）
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            TvRenderer.detachSurface()
            TvSpeaker.stop()
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
