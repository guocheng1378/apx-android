package com.allperiph.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.allperiph.shared.media.MediaChannel
import com.allperiph.shared.media.Renderer
import com.allperiph.shared.net.ControlServer
import com.allperiph.shared.net.ControlEndpoint
import com.allperiph.shared.net.FileReceiver
import com.allperiph.shared.net.WirelessBeacon
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.util.Log
import com.allperiph.shared.inject.KeepAlive
import com.allperiph.shared.inject.ServiceController
import com.allperiph.shared.inject.TvInjector
import com.allperiph.tv.core.TvInjectorPlatform
import com.allperiph.shared.accessibility.ApxAccessibilityService
import com.allperiph.tv.media.TvSpeaker
import com.allperiph.tv.ui.TvInputDispatcher

class TvServerService : Service() {
    private var server: ControlServer? = null
    private var media: MediaChannel? = null
    private var beacon: WirelessBeacon? = null
    private val wdRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var fgFailed = false

    /** 控制面端点：把 shared ControlServer 的输入注入回调到本端 TvInjector + TvInputDispatcher */
    private val controlEndpoint = object : ControlEndpoint {
        override fun onPeerStateChanged(connected: Boolean, peerText: String) {
            TvInputDispatcher.peer(connected, peerText)
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
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) { val ch = NotificationChannel(CHANNEL_ID, "全能外设 TV 状态", NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch) }
        current = this
        TvInjector.init(applicationContext, TvInjectorPlatform)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { startForegroundCompat(); ensureControlPlane(); ensureMedia(); ensureFileReceiver(); KeepAlive.schedule(this, if (fgFailed) 30_000L else 60_000L); startWatchdog(); return START_STICKY }
    private fun startForegroundCompat() { val notif = buildNotification(server?.statusText() ?: "正在启动…"); if (Build.VERSION.SDK_INT >= 34) { try { startForeground(NOTIFY_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); fgFailed = false; return } catch (t: Throwable) { Log.w("startForeground(connectedDevice) 失败：${t.message}") } }; try { startForeground(NOTIFY_ID, notif); fgFailed = false } catch (t: Throwable) { fgFailed = true; Log.w("startForeground 失败：${t.message}") } }
    private fun ensureFileReceiver() { runCatching { FileReceiver.start(applicationContext) }.onFailure { Log.w("9512 未启动：${it.message}") } }
    private fun newServer(): ControlServer = ControlServer(endpoint = controlEndpoint).also { s ->
        s.onPeerChanged = { connected, ip -> Log.i("对端变化：connected=$connected ip=$ip"); if (connected && ip.isNotEmpty()) rememberPeer(ip); TvInjector.setConnected(connected); refreshNotification() }
        s.onOpenScreenRequest = { openScreenPage() }
        s.onAuthorizePeer = { ip -> isPeerAllowed(ip) }
        s.onRemoteInputRequest = { fromDevice, hint -> Log.i("远程输入请求: from=$fromDevice hint=$hint"); val i = Intent(ACTION_REMOTE_INPUT); i.putExtra(EXTRA_SOURCE, fromDevice); i.putExtra(EXTRA_HINT, hint); i.setPackage(packageName); sendBroadcast(i) }
        s.onRemoteInputText = { text, flags -> Log.i("远程输入文本回传: text=$text flags=$flags") }
        s.onRemoteInputDone = { Log.i("远程输入完成回传") }
    }
    private fun isPeerAllowed(ip: String): Boolean { if (ip.isBlank()) return true; if (!onlyTrustedEnabled(applicationContext)) return true; val ok = trustedPeers(applicationContext).contains(ip); if (!ok) Log.w("授权拒绝：$ip 不在允许名单"); return ok }
    private fun openScreenPage() { runCatching { val i = Intent(applicationContext, com.allperiph.tv.ui.TvScreenActivity::class.java); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) }.onFailure { Log.w("拉起副屏页失败：${it.message}") } }
    private fun ensureControlPlane() { if (server == null) server = newServer(); if (server!!.start()) ensureBeacon() else Log.e("TV 控制面启动失败") }
    private fun ensureBeacon() { if (beacon != null) return; val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"; beacon = WirelessBeacon("APX1TV", name, ControlServer.PORT, "", unicastHosts = { val out = ArrayList<String>(4); server?.currentPeerHost()?.let { out.add(it) }; out.addAll(knownPeers()); out }).also { it.start() } }
    private fun startWatchdog() { if (!wdRunning.compareAndSet(false, true)) return; Thread({ while (wdRunning.get()) { try { Thread.sleep(15000) } catch (_: InterruptedException) { break }; if (!wdRunning.get()) break; try { val listening = server?.isListening == true; if (!listening) { Log.w("看门狗：9511 不在监听 → 重建"); runCatching { server?.stop() }; server = newServer().also { it.start() }; ensureBeacon() } else if (beacon == null) ensureBeacon(); refreshNotification() } catch (t: Throwable) { Log.w("看门狗异常：${t.message}") } } }, "apx-tv-watchdog").start() }
    override fun onDestroy() { wdRunning.set(false); beacon?.stop(); beacon = null; media?.stop(); media = null; server?.stop(); server = null; current = null; super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); Log.w("任务被移除 → 重启"); restartService() }
    private fun restartService() { val ok = runCatching { val i = Intent(applicationContext, TvServerService::class.java); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i); true }.getOrElse { false }; if (!ok) KeepAlive.schedule(applicationContext, 1_000L) }
    override fun onBind(intent: Intent?): IBinder? = null
    fun status(): String = server?.statusText() ?: "未启动"
    fun ensureMedia() {
        if (media != null) return
        // 媒体通道收到的帧直接喂给 TV 端的渲染器 / 音箱（TV 不做上行）。
        media = MediaChannel(onFrame = { streamId, _, _, body ->
            when (streamId) {
                ApxFrame.STREAM_VIDEO -> Renderer.submit(body)
                ApxFrame.STREAM_AUDIO -> TvSpeaker.submit(body)
                else -> {}
            }
        }).also { if (!it.start()) Log.w("9512 未启动") }
    }
    fun mediaStatus(): String = media?.statusText() ?: "还没收到电脑画面"
    fun sendTargets(): List<String> { val out = ArrayList<String>(4); server?.currentPeerHost()?.let { out.add(it) }; out.addAll(knownPeers()); return out.distinct() }
    fun currentPeer(): String? = server?.currentPeerHost()
    /** 远程输入：将 TV 端用户输入的文本发回手机端 */
    fun sendInputText(text: String, flags: Int = 0x01) { server?.sendInputText(text, flags) }
    /** 远程输入：通知手机端输入完成 */
    fun sendInputDone() { server?.sendInputDone() }
    private fun injectStateText(): String = TvInjector.channelText()
    private fun refreshNotification() { runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFY_ID, buildNotification(server?.statusText() ?: "正在启动…")) } }
    private fun rememberPeer(ip: String) { val set = knownPeers().toMutableSet(); if (set.add(ip)) { getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PEERS, set.toList().takeLast(8).joinToString(",")).apply() } }
    private fun knownPeers(): List<String> = getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PEERS, "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    private fun buildNotification(text: String): Notification { val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this); builder.setContentTitle(getString(R.string.tv_notification_title)).setContentText(text + " · " + injectStateText() + (if (fgFailed) " · 后台运行受限" else "")).setSmallIcon(R.drawable.ic_launcher_tv).setOngoing(true); return builder.build() }
    companion object : ServiceController {
        private const val CHANNEL_ID = "apxtv_status"
        private const val NOTIFY_ID = 1
        const val PREF = "apx_tv"
        private const val KEY_PEERS = "peers"
        const val KEY_ENABLED = "enabled"
        @Volatile var current: TvServerService? = null; private set
        const val KEY_USER_STOPPED = "user_stopped"
        private const val KEY_TRUSTED = "trusted_peers"
        private const val KEY_ONLY_TRUSTED = "only_trusted"
        const val ACTION_REMOTE_INPUT = "com.allperiph.tv.ACTION_REMOTE_INPUT"
        const val EXTRA_SOURCE = "source_device"
        const val EXTRA_HINT = "hint"
        private fun prefsOf(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        private fun csv(c: Context, key: String): List<String> = prefsOf(c).getString(key, "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        fun knownPeerList(c: Context): List<String> = csv(c, KEY_PEERS).reversed()
        fun trustedPeers(c: Context): List<String> = csv(c, KEY_TRUSTED)
        fun setPeerTrusted(c: Context, ip: String, trusted: Boolean) { val set = trustedPeers(c).toMutableSet(); if (trusted) set.add(ip) else set.remove(ip); prefsOf(c).edit().putString(KEY_TRUSTED, set.joinToString(",")).apply() }
        fun onlyTrustedEnabled(c: Context): Boolean = prefsOf(c).getBoolean(KEY_ONLY_TRUSTED, false)
        fun setOnlyTrusted(c: Context, on: Boolean) { prefsOf(c).edit().putBoolean(KEY_ONLY_TRUSTED, on).apply() }
        fun isEnabled(c: Context): Boolean = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        override fun enabled(ctx: Context): Boolean = isEnabled(ctx)
        fun isUserStopped(c: Context): Boolean = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_USER_STOPPED, false)
        override fun isRunning(): Boolean = current != null
        override fun start(c: Context) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).putBoolean(KEY_USER_STOPPED, false).apply(); val i = Intent(c, TvServerService::class.java); runCatching { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }.onFailure { Log.w("拉起失败：${it.message}") } }
        fun startIfNeeded(c: Context) { if (isUserStopped(c)) return; start(c) }
        override fun stopAll(c: Context) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).putBoolean(KEY_USER_STOPPED, true).apply(); KeepAlive.cancel(c); runCatching { c.stopService(Intent(c, TvServerService::class.java)) }.onFailure { Log.w("停止失败：${it.message}") } }
    }
}