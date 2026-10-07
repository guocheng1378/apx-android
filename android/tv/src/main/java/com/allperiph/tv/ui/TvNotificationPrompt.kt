package com.allperiph.tv.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.allperiph.shared.util.Log

/**
 * 通知浮窗（TV 端）：收到手机通知时在电视上浮出一个卡片，
 * 显示通知标题、内容、来源 App。
 *
 * 复用 TvFilePrompt 的浮窗架构（显示在其他应用上层），
 * 自动5秒后消失。
 */
object TvNotificationPrompt {

    private var wm: WindowManager? = null
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private const val AUTO_DISMISS_MS = 5000L

    private val dismissRunnable = Runnable { dismiss() }

    /** 显示通知浮窗（主线程调用）。重复到来会先关掉上一个 */
    fun show(ctx: Context, appName: String, title: String, text: String) {
        val app = ctx.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        dismiss()
        val ui = buildCard(app, appName, title, text)
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = TvUi.dp(app, 48f) }
        try {
            manager.addView(ui, params)
            wm = manager
            view = ui
            // 自动消失
            handler.removeCallbacks(dismissRunnable)
            handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
        } catch (t: Throwable) {
            Log.w("通知浮窗创建失败：${t.message}")
        }
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        view?.let { runCatching { wm?.removeView(it) } }
        view = null
        wm = null
    }

    private fun buildCard(ctx: Context, appName: String, title: String, text: String): View {
        val gap = TvUi.dp(ctx, 12f)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap * 2, gap * 2, gap * 2, gap * 2)
            background = TvUi.solidBg(TvUi.Pal.card, TvUi.dp(ctx, TvUi.Radius.CARD))
            isFocusable = true
            setOnKeyListener { _, keyCode, ev ->
                if (ev?.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                    dismiss(); true
                } else false
            }
        }

        // 来源 App 标签
        card.addView(label(ctx, "📱 $appName", TvUi.Type.CAPTION, TvUi.Pal.textDim))

        // 通知标题
        card.addView(label(ctx, title, TvUi.Type.TITLE, TvUi.Pal.accent, bold = true).apply {
            setPadding(0, gap / 2, 0, 0)
        })

        // 通知内容
        if (text.isNotBlank()) {
            card.addView(label(ctx, text, TvUi.Type.BODY, TvUi.Pal.text).apply {
                maxLines = 3
                setPadding(0, gap / 4, 0, 0)
            })
        }

        return card
    }

    private fun label(ctx: Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            TvUi.applyTextSize(this, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
}
