package com.allperiph.tv.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.allperiph.shared.inject.CursorOverlay
import com.allperiph.shared.util.Log

/**
 * 全局光标浮层：被控端把手机光标画在最上层，提示当前落点。
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）；未授予时静默失败，仅丢失可视化。
 * 本对象实现共享 [CursorOverlay]，供 [com.allperiph.shared.inject.TvInjector] 经
 * [com.allperiph.shared.inject.InjectorPlatform.overlay] 注入。
 *
 * ## 线程纪律（别删）
 * 调用方是 **apx-inject 后台线程**（`ControlServer` 在 `HandlerThread("apx-inject")` 上派发输入帧）。
 * 而 `WindowManager.updateViewLayout` / `View.setVisibility` 必须在创建视图的线程（主线程）执行，
 * 否则抛 `CalledFromWrongThreadException`。旧实现在后台线程直接调，还用空 `catch (_: Throwable) {}`
 * 把异常吞掉 —— 表现为**手机动鼠标，电视上的光标一动不动**。这里统一切回主线程。
 */
object TvOverlay : CursorOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var size = 16
    private var shown = false
    private var screenW = 0
    private var screenH = 0

    override fun init(ctx: Context) {
        if (wm != null && view != null) return
        // ⚠️ 上一次 addView 失败（没拿到「显示在其他应用上层」）时必须把状态清回 null，
        // 否则 `if (wm != null) return` 会让浮层**永久失效**：用户后来授权了也再建不起来，
        // 只能杀进程。`TvInjector.channelText()` 会一直报「悬浮窗不可用」。
        if (view == null) {
            wm = null
            params = null
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        // 4K 电视上必须按分辨率放大，否则光标点小到看不见（TvUi.dp 内含 1080p 基准缩放）
        size = TvUi.dp(ctx, 24f).coerceAtLeast(8)
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TvUi.Pal.cardFocus)
                setStroke(TvUi.dp(ctx, 2f).coerceAtLeast(1), 0xFFFFFFFF.toInt())
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }
        try {
            manager.addView(dot, params)
            wm = manager
            view = dot
            dot.visibility = View.GONE
            measure(manager, ctx)
        } catch (t: Throwable) {
            Log.w("浮层创建失败（需「显示在其他应用上层」权限）：${t.message}")
            wm = null
            params = null
        }
    }

    /** 浮层是否真的建起来了（没拿到「显示在其他应用上层」时会失败） */
    override val isReady: Boolean
        get() = wm != null && view != null

    override fun move(x: Float, y: Float) {
        if (Looper.myLooper() == Looper.getMainLooper()) doMove(x, y) else mainHandler.post { doMove(x, y) }
    }

    private fun doMove(x: Float, y: Float) {
        val v = view ?: return
        val p = params ?: return
        p.x = (x - size / 2).toInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
        p.y = (y - size / 2).toInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
        try {
            wm?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            // 别再静默吞掉：浮层失效是"光标不动"的唯一线索
            Log.w("浮层移动失败：${t.message}")
        }
        if (!shown) {
            v.visibility = View.VISIBLE
            shown = true
        }
    }

    override fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) doHide() else mainHandler.post { doHide() }
    }

    private fun doHide() {
        view?.let { it.visibility = View.GONE }
        shown = false
    }

    override fun destroy() {
        if (Looper.myLooper() == Looper.getMainLooper()) doDestroy() else mainHandler.post { doDestroy() }
    }

    private fun doDestroy() {
        view?.let { runCatching { wm?.removeView(it) } }
        view = null
        wm = null
        params = null
        shown = false
    }

    /** 缓存屏幕尺寸：move() 每帧都调，不能每次都查（且 defaultDisplay 已废弃） */
    private fun measure(manager: WindowManager, ctx: Context) {
        screenW = ctx.resources.displayMetrics.widthPixels
        screenH = ctx.resources.displayMetrics.heightPixels
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val b = manager.currentWindowMetrics.bounds
                if (b.width() > 0) screenW = b.width()
                if (b.height() > 0) screenH = b.height()
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                val m = android.util.DisplayMetrics()
                manager.defaultDisplay?.getMetrics(m)
                if (m.widthPixels > 0) screenW = m.widthPixels
                if (m.heightPixels > 0) screenH = m.heightPixels
            }
        }
    }
}
