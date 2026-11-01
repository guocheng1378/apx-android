package com.allperiph.controlled

import com.allperiph.shared.ui.ApKit
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.allperiph.shared.inject.CursorOverlay
import com.allperiph.shared.util.Log

/**
 * 全局光标浮层：被控端把对方手机光标画在最上层，提示当前落点。
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）；未授予时静默失败，仅丢失可视化。
 * 与 TV 模块 [com.allperiph.tv.ui.TvOverlay] 同实现；本对象实现共享 [CursorOverlay]，
 * 供 [com.allperiph.shared.inject.TvInjector] 经 [com.allperiph.shared.inject.InjectorPlatform.overlay] 注入。
 *
 * ## 线程纪律（别删）
 * 调用方是 **apx-inject 后台线程**（`TvControlServer` 在 `HandlerThread("apx-inject")` 上派发输入帧）。
 * 而 `WindowManager.updateViewLayout` / `View.setVisibility` 必须在创建视图的线程（主线程）执行，
 * 否则抛 `CalledFromWrongThreadException`。旧实现在后台线程直接调，还用空 `catch (_: Throwable) {}`
 * 把异常吞掉 —— 表现为**对端动鼠标，手机上的光标一动不动**。这里统一切回主线程。
 */
object TvOverlay : CursorOverlay {
    /** 修复面补全：主线程分发器 —— 与 TV 版 [com.allperiph.tv.ui.TvOverlay] 同结构 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 浮层是否真的建起来了（没拿到「显示在其他应用上层」时会失败） */
    override val isReady: Boolean
        get() = wm != null && view != null

    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var size = 16
    private var shown = false

    override fun init(ctx: Context) {
        if (wm != null) return
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        size = (10 * dm.density).toInt().coerceAtLeast(8)
        val dot = View(ctx).apply {
            background = ApKit.oval(
                fill = 0xFF1F6FEB.toInt(),
                strokeColor = 0xFFFFFFFF.toInt(),
                strokePx = (2 * dm.density).toInt().coerceAtLeast(1),
            )
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
            wm?.addView(dot, params)
            view = dot
            dot.visibility = View.GONE
        } catch (t: Throwable) {
            Log.w("被控浮层", "创建失败（需「显示在其他应用上层」权限）：${t.message}")
        }
    }

    override fun move(x: Float, y: Float) {
        if (Looper.myLooper() == Looper.getMainLooper()) doMove(x, y) else mainHandler.post { doMove(x, y) }
    }

    private fun doMove(x: Float, y: Float) {
        val v = view ?: return
        val p = params ?: return
        p.x = (x - size / 2).toInt().coerceAtLeast(0)
        p.y = (y - size / 2).toInt().coerceAtLeast(0)
        try {
            wm?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            // 修复面补全：别再静默吞掉 —— 浮层失效是"光标不动"的唯一线索
            Log.w("被控浮层", "移动失败：${t.message}")
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
        shown = false
    }
}