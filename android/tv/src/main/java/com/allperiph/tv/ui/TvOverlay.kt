package com.allperiph.tv.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
 */
object TvOverlay : CursorOverlay {
    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var size = 16
    private var shown = false

    override fun init(ctx: Context) {
        if (wm != null) return
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        size = (24 * dm.density).toInt().coerceAtLeast(8)
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TvUi.Pal.cardFocus)
                setStroke(
                    (2 * dm.density).toInt().coerceAtLeast(1),
                    0xFFFFFFFF.toInt(),
                )
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
            wm?.addView(dot, params)
            view = dot
            dot.visibility = View.GONE
        } catch (t: Throwable) {
            Log.w("浮层创建失败（需「显示在其他应用上层」权限）：${t.message}")
        }
    }

    /** 浮层是否真的建起来了（没拿到「显示在其他应用上层」时会失败） */
    override val isReady: Boolean
        get() = wm != null && view != null

    override fun move(x: Float, y: Float) {
        val v = view ?: return
        val p = params ?: return
        val screenWidth = wm?.defaultDisplay?.width ?: 0
        val screenHeight = wm?.defaultDisplay?.height ?: 0
        p.x = (x - size / 2).toInt().coerceIn(0, (screenWidth - size).coerceAtLeast(0))
        p.y = (y - size / 2).toInt().coerceIn(0, (screenHeight - size).coerceAtLeast(0))
        try {
            wm?.updateViewLayout(v, p)
        } catch (_: Throwable) {
        }
        if (!shown) {
            v.visibility = View.VISIBLE
            shown = true
        }
    }

    override fun hide() {
        view?.let { it.visibility = View.GONE }
        shown = false
    }

    override fun destroy() {
        view?.let { runCatching { wm?.removeView(it) } }
        view = null
        wm = null
        shown = false
    }
}