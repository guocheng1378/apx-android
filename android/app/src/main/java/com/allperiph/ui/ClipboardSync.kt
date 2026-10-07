package com.allperiph.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import com.allperiph.shared.util.Log
import com.allperiph.wireless.ControlTarget

/**
 * 剪贴板同步：手机复制文字后自动发送到已连接的 PC/TV（通过 TCP 9511 控制面）。
 *
 * 用法：在 MainActivity 的 onStart 注册、onStop 取消注册。
 * 由 ControlTarget.controlClient（手机→TV/PC 的连接）发送 0x20 帧到对端。
 */
object ClipboardSync {
    private const val TAG = "ClipboardSync"

    /** 程序性写入抑制：收到对端剪贴板后 TvInjector 会写本机剪贴板，
     *  此时 listener 触发会回传形成死循环。抑制窗口 600ms。 */
    @Volatile private var lastProgrammaticWriteMs = 0L

    fun markProgrammaticWrite() { lastProgrammaticWriteMs = SystemClock.uptimeMillis() }

    private var registered = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        // 抑制程序性写入引起的回传
        if (SystemClock.uptimeMillis() - lastProgrammaticWriteMs < 600) return@OnPrimaryClipChangedListener
        val client = ControlTarget.controlClient ?: return@OnPrimaryClipChangedListener
        if (!client.ready) return@OnPrimaryClipChangedListener
        val text = currentClipboardText() ?: return@OnPrimaryClipChangedListener
        if (text.isNotEmpty()) {
            val ok = client.sendClipboard(text)
            if (ok) Log.i(TAG, "剪贴板已同步到 ${ControlTarget.host}（${text.length} 字符）")
            else Log.w(TAG, "剪贴板同步发送失败")
        }
    }

    /** 在 Activity onStart 中调用 */
    fun register(ctx: Context) {
        if (registered) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.removePrimaryClipChangedListener(listener) }  // 保险
        cm.addPrimaryClipChangedListener(listener)
        registered = true
        Log.i(TAG, "剪贴板监听已注册")
    }

    /** 在 Activity onStop 中调用 */
    fun unregister(ctx: Context) {
        if (!registered) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { cm?.removePrimaryClipChangedListener(listener) }
        registered = false
        Log.i(TAG, "剪贴板监听已取消")
    }

    private fun currentClipboardText(): String? {
        // 在主线程调用，需要在后台线程读取
        return try {
            val cm = androidx.core.content.ContextCompat.getSystemService(
                android.app.Activity(), ClipboardManager::class.java
            )
            cm?.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (t: Throwable) { null }
    }
}
