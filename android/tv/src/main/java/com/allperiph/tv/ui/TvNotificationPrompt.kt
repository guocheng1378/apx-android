package com.allperiph.tv.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.shared.util.Log
import top.yukonga.miuix.kmp.basic.Text

/**
 * 通知浮窗（TV 端）：收到手机通知时在电视上浮出一个卡片，
 * 显示来源 App、通知标题与内容，自动 5 秒消失。
 *
 * 2026-11-01 迁 Compose：卡片内容改由 [TvOverlayHost] 承载的 ComposeView 渲染
 * （浮层窗口没有 Activity 的 View 树，必须补三件套 lifecycle owner，否则组合建立时崩溃），
 * WindowManager 的窗口类型/flags/位置策略保持原样 —— 仍是**纯展示、不可聚焦、
 * 不吞窗口外触摸**，不会抢遥控器焦点。
 */
object TvNotificationPrompt {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var host: TvOverlayHost? = null
    private val handler = Handler(Looper.getMainLooper())
    private const val AUTO_DISMISS_MS = 5000L

    private val dismissRunnable = Runnable { dismiss() }

    /** 显示通知浮窗（主线程调用）。重复到来会先关掉上一个 */
    fun show(ctx: Context, appName: String, title: String, text: String) {
        val app = ctx.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        dismiss()
        val overlayHost = TvOverlayHost(app)
        val ui = overlayHost.create {
            NotificationCard(appName = appName, title = title, text = text)
        }
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        // 纯展示卡片：不可聚焦、不吞窗口外触摸，避免 5 秒内抢走遥控器方向键/返回键。
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = TvUi.dp(app, 48f) }
        try {
            manager.addView(ui, params)
            wm = manager
            view = ui
            host = overlayHost
            handler.removeCallbacks(dismissRunnable)
            handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
        } catch (t: Throwable) {
            overlayHost.destroy()
            Log.w("通知浮窗创建失败：${t.message}")
        }
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        view?.let { runCatching { wm?.removeView(it) } }
        host?.destroy()
        view = null
        wm = null
        host = null
    }

    /** 卡片本体：三行文字，圆角卡片背景，全部走 TvUi 调色板 */
    @androidx.compose.runtime.Composable
    private fun NotificationCard(appName: String, title: String, text: String) {
        Column(
            modifier = Modifier
                .background(Color(TvUi.Pal.card), RoundedCornerShape(TvUi.Radius.CARD.dp))
                .padding(24.dp),
        ) {
            Text(
                text = "📱 " + appName.take(40),
                fontSize = TvUi.Type.CAPTION.sp,
                color = Color(TvUi.Pal.textDim),
            )
            Text(
                text = title.take(80),
                fontSize = TvUi.Type.TITLE.sp,
                color = Color(TvUi.Pal.accent),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (text.isNotBlank()) {
                Text(
                    text = text.take(160),
                    fontSize = TvUi.Type.BODY.sp,
                    color = Color(TvUi.Pal.text),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
