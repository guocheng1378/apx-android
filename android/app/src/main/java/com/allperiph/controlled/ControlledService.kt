package com.allperiph.controlled

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.allperiph.R
import com.allperiph.shared.net.ControlServer
import com.allperiph.shared.net.ControlEndpoint
import com.allperiph.shared.net.FileReceiver
import com.allperiph.shared.net.WirelessBeacon
import com.allperiph.shared.util.Log
import com.allperiph.controlled.KeepAlive
import com.allperiph.controlled.TvInjector
import com.allperiph.touchpad.TouchpadActivity

class ControlledService : Service() {
    private var server: ControlServer? = null
    private var beacon: WirelessBeacon? = null

    /** 控制面端点：把 shared ControlServer 的输入注入回调到本端 TvInjector + TvInputDispatcher */
    private val controlEndpoint = object : ControlEndpoint {
        override fun onPeerStateChanged(connected: Boolean, peerText: String) {
            TvInputDispatcher.peer(connected, peerText); TvInjector.setConnected(connected)
        }
        override fun cursorMove(x: Float, y: Float, absolute: Boolean) {
            TvInputDispatcher.cursorMove(x, y, absolute); TvInjector.cursorMove(x, y, absolute)
        }
        override fun cursorClick() = TvInputDispatcher.cursorClick()
        override fun key(keyCode: Int, down: Boolean) {
            TvInputDispatcher.key(keyCode, down); TvInjector.key(keyCode, down)
        }
        override fun text(ch: Char) {
            TvInputDispatcher.text(ch); TvInjector.text(ch)
        }
        override fun pressDown() = TvInjector.pressDown()
        override fun pressUp() = TvInjector.pressUp()
        override fun scroll(wheel: Int) = TvInjector.scroll(wheel)
        override fun touchDown(x: Float, y: Float) = TvInjector.touchDown(x, y)
        override fun touchUp(x: Float, y: Float) = TvInjector.touchUp(x, y)
        override fun consumer(bitmap: Int) = TvInjector.consumer(bitmap)
        override fun powerAction(action: Int) { TvInjector.powerAction(action) }
        override fun clipboard(text: String) = TvInjector.clipboard(text)
        override fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) {
            TvInputDispatcher.onGamepad(buttons, x, y, rx, ry); TvInjector.gamepad(buttons, x, y, rx, ry)
        }
        override fun lockScreen() { ApxAccessibilityService.instance?.lockScreen() }
    }
    /** v184：剪贴板监听是否已注册 —— onStartCommand 可能被反复调用，重复挂会多次回传 */
    private var clipListenerRegistered = false
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // v184：程序性写入抑制 —— 收到 PC 的 0x20 后 TvInjector.clipboard 会写剪贴板，
        // 这里若照常回传 0x21，PC 端 watcher 又写又回传，剪贴板内容来回乱跳。
        // 程序性写入后 600ms 内的变化静默吸收（真实用户复制不受影响）。
        if (android.os.SystemClock.uptimeMillis() - TvInjector.lastProgrammaticClipWriteMs < 600) return@OnPrimaryClipChangedListener
        val text = currentClipboardText()
        if (!text.isNullOrEmpty()) server?.sendReverseClipboard(text)
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "被控模式", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundGuarded()
        TvInjector.init(this)
        if (server == null) server = ControlServer(endpoint = controlEndpoint)
        Companion.server = server
        server?.onClipboardChange = { text -> server?.sendReverseClipboard(text) }
        // 0x10 模块开关（v184）：PC 面板拨"无线"开关 → 挂起/恢复被控（监听保留，可随时恢复）
        server?.onModuleToggle = { id, on ->
            if (id == "wireless") {
                if (on) server?.resumeAccept() else server?.suspendAccept()
            }
        }
        // 注册远程输入回调：收到 REQUEST_INPUT 时弹出 RemoteInputActivity
        server?.onRemoteInputRequest = { fromDevice, hint ->
            Log.i("ControlledService", "远程输入请求: from=$fromDevice, hint=$hint")
            RemoteInputActivity.start(this, fromDevice, hint)
        }
        server?.onRemoteInputText = { text, flags ->
            // 文本由 RemoteInputActivity 直接通过 server 发送，这里不重复
        }
        if (server?.start() != true) Log.e(TAG, "被控控制面启动失败")
        FileReceiver.start(applicationContext)
        val devName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        // v184：以下两处必须幂等 —— onStartCommand 会被反复调用（START_STICKY 重启、
        // onTaskRemoved 拉起、系统回收后重建）。旧实现每次都新建一个 beacon（旧实例的
        // 线程与端口从此没人管，逐次累积）并再 addPrimaryClipChangedListener 一次
        // （同一个 listener 挂多份 → 剪贴板变化被回传多次）。
        if (beacon == null) {
            beacon = WirelessBeacon(
                "APX1TV",
                com.allperiph.wireless.TvDiscovery.PHONE_NAME_MARK + devName,
                ControlServer.PORT, "",
                unicastHosts = {
                    val hosts = ArrayList<String>(8)
                    com.allperiph.wireless.ControlTarget.host.takeIf { it.isNotBlank() }?.let { hosts.add(it) }
                    com.allperiph.wireless.TvDiscovery.list().forEach { hosts.add(it.ip) }
                    hosts.distinct()
                },
            ).also { it.start() }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (!clipListenerRegistered) {
            runCatching { cm?.removePrimaryClipChangedListener(clipListener) }   // 保险：先摘再挂
            cm?.addPrimaryClipChangedListener(clipListener)
            clipListenerRegistered = true
        }
        KeepAlive.schedule(this)
        val init = currentClipboardText()
        if (!init.isNullOrEmpty()) server?.sendReverseClipboard(init)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; Companion.server = null
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { cm?.removePrimaryClipChangedListener(clipListener) }
        clipListenerRegistered = false   // v184：服务重建时重新注册
        server?.stop(); server = null; beacon?.stop(); beacon = null
        FileReceiver.stop(); TvInjector.onDestroy(); super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "任务被移除 → 重新拉起")
        val ok = runCatching {
            val i = Intent(applicationContext, ControlledService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i); true
        }.getOrElse { false }
        if (!ok) KeepAlive.schedule(applicationContext, 1_000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun currentClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return try { cm?.primaryClip?.getItemAt(0)?.text?.toString() } catch (t: Throwable) { null }
    }
    private fun startForegroundGuarded() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFY_ID, n)
        } catch (t: Throwable) { Log.w(TAG, "startForeground 失败：${t.message}") }
    }
    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(this, 1, Intent(this, TouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ready = server?.ready == true
        val sysReady = TvInjector.systemReady()
        val text = (if (!ready) "等待手机连入 :${ControlServer.PORT}" else if (sysReady) "已连接 · 系统注入已启用" else "已连接 · 未启用无障碍") +
            (if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) " · 未授予通知权限" else "")
        return Notification.Builder(this, CHANNEL_ID).setContentTitle("全能外设 · 被控模式").setContentText(text).setSmallIcon(R.drawable.ic_launcher_app).setContentIntent(openPi).setOngoing(true).build()
    }
    companion object {
        private const val TAG = "ControlledService"
        private const val CHANNEL_ID = "controlled_status"
        private const val NOTIFY_ID = 0x9A3
        private const val PREF = "apx_controlled"
        private const val KEY_ENABLED = "enabled"
        @Volatile var running: Boolean = false; private set
        @Volatile var server: ControlServer? = null; internal set
        fun enabled(c: Context): Boolean = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        fun start(c: Context) {
            running = true; c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
            val i = Intent(c, ControlledService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }.onFailure { running = false; Log.e(TAG, "启动被控服务失败", it) }
        }
        fun stop(c: Context) {
            running = false; c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            runCatching { c.stopService(Intent(c, ControlledService::class.java)) }
        }
        fun isRunning() = running
    }
}