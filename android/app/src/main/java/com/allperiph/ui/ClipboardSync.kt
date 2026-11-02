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

    /**
     * 引用计数：界面（[register]）与悬浮球服务（[serviceAttach]）可能同时在听。
     *
     * 旧实现只有一个布尔位，谁后调 [unregister] 谁就把监听整个摘掉 ——
     * 主界面 onPause 一摘，悬浮球服务这边的后台同步就跟着断（成宇 2026-11-02
     * 「悬浮窗要加剪贴板」的真正缺口：主界面退到后台后，手机上复制的文字不再发往对端，
     * 而这恰恰是用户在用悬浮球干活的时候）。
     */
    private var uiHolders = 0
    private var serviceHeld = false
    private var listening = false
    private var appContext: Context? = null

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

    /**
     * 手动把本机剪贴板发到当前受控设备（不依赖「变了才同步」）。
     *
     * 2026-11-02 审查条 1.6：旧触控板页的「发送剪贴板」入口在主壳里没有任何落点——
     * [ClipboardSync] 只做自动同步（监听变化），读剪贴板并直发的那段代码一直关在旧控制台
     * `TouchpadActivity.sendClipboard()` 里。这里把它提成可复用的出口，主壳首页与设置页
     * 都调它，文案沿用旧控制台那四条字符串（设备未连接 / 剪贴板为空 / 已发送 / 发送失败）。
     */
    enum class SendResult { NO_DEVICE, EMPTY, OK, FAIL }

    fun sendNow(ctx: Context): SendResult {
        val client = ControlTarget.controlClient
        if (client == null || !ControlTarget.isControlling()) return SendResult.NO_DEVICE
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = try {
            cm?.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (t: Throwable) {
            null
        }
        if (text.isNullOrEmpty()) return SendResult.EMPTY
        val ok = client.sendClipboard(text)
        Log.i(TAG, if (ok) "手动发送剪贴板（${text.length} 字符）" else "手动发送剪贴板失败")
        return if (ok) SendResult.OK else SendResult.FAIL
    }

    /** 在 Activity onStart 中调用 */
    fun register(ctx: Context) {
        uiHolders++
        ensureListening(ctx)
    }

    /** 在 Activity onStop 中调用 */
    fun unregister(ctx: Context) {
        if (uiHolders > 0) uiHolders--
        if (uiHolders <= 0 && !serviceHeld) stopListening(ctx)
    }

    /**
     * 悬浮球服务级别常驻监听（v223.29）。
     *
     * 主界面退到后台后按钮还在，用户照样会复制文字指望同步过去 —— 服务活着就一直听。
     * 与服务同生命周期：[FloatingBallService.onCreate] 挂、shutdown 摘。
     */
    fun serviceAttach(ctx: Context) {
        serviceHeld = true
        ensureListening(ctx)
    }

    /** 服务关闭时调用 */
    fun serviceDetach(ctx: Context) {
        serviceHeld = false
        if (uiHolders <= 0) stopListening(ctx)
    }

    private fun ensureListening(ctx: Context) {
        appContext = ctx.applicationContext
        if (listening) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.removePrimaryClipChangedListener(listener) }  // 保险
        cm.addPrimaryClipChangedListener(listener)
        listening = true
        Log.i(TAG, "剪贴板监听已注册（界面 $uiHolders · 服务 $serviceHeld）")
    }

    private fun stopListening(ctx: Context) {
        if (!listening) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { cm?.removePrimaryClipChangedListener(listener) }
        listening = false
        Log.i(TAG, "剪贴板监听已取消")
    }

    /** 当前剪贴板文字（悬浮球面板补发用，与内部监听同一取值口径） */
    fun currentText(): String? = currentClipboardText()

    private fun currentClipboardText(): String? {
        return try {
            val cm = appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (t: Throwable) { null }
    }
}