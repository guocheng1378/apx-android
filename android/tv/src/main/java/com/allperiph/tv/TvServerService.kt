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
    private val controlEndpoint = object : ControlEndpoint {
        override fun onPeerStateChanged(connected: Boolean, peerText: String) {
            TvInputDispatcher.peer(connected, peerText)
        }
        override fun cursorMove(x: Float, y: Float, absolute: Boolean) {
            TvInputDispatcher.cursorMove(x, y, absolute); TvInjector.cursorMove(x, y, absolute)
        }
        override fun cursorClick() { TvInputDispatcher.cursorClick() }
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
        if (Build.VERSION.SDK_INT >= 26) { val ch = NotificationChannel(CHANNEL_ID, "\u5168\u80fd\u5916\u8bbe TV \u72b6\u6001", NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch) }
        current = this
        TvInjector.init(applicationContext, TvInjectorPlatform)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { startForegroundCompat(); ensureControlPlane(); ensureMedia(); ensureFileReceiver(); KeepAlive.schedule(this, if (fgFailed) 30_000L else 60_000L); startWatchdog(); return START_STICKY }
    private fun startForegroundCompat() { val notif = buildNotification(server?.statusText() ?: "\u6b63\u5728\u542f\u52a8\u2026"); if (Build.VERSION.SDK_INT >= 34) { try { startForeground(NOTIFY_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); fgFailed = false; return } catch (t: Throwable) { Log.w("startForeground(connectedDevice) \u5931\u8d25\uff1a${t.message}") } }; try { startForeground(NOTIFY_ID, notif); fgFailed = false } catch (t: Throwable) { fgFailed = true; Log.w("startForeground \u5931\u8d25\uff1a${t.message}") } }
    private fun ensureFileReceiver() { runCatching { FileReceiver.start(applicationContext); FileReceiver.onReceived = { c, f -> com.allperiph.tv.ui.TvFilePrompt.show(c, f) } }.onFailure { Log.w("9512 \u672a\u542f\u52a8\uff1a${it.message}") } }
    private fun newServer(): ControlServer = ControlServer(endpoint = controlEndpoint).also { s ->
        s.onPeerChanged = { connected, ip -> Log.i("\u5bf9\u7aef\u53d8\u5316\uff1acon=$connected ip=$ip"); if (connected && ip.isNotEmpty()) rememberPeer(ip); TvInjector.setConnected(connected); refreshNotification() }
        s.onOpenScreenRequest = { openScreenPage() }
        s.onAuthorizePeer = { ip -> isPeerAllowed(ip) }
        s.onRemoteInputRequest = { fromDevice, hint -> Log.i("\u8fdc\u7a0b\u8f93\u5165\u8bf7\u6c42: from=$fromDevice hint=$hint"); val i = Intent(ACTION_REMOTE_INPUT); i.putExtra(EXTRA_SOURCE, fromDevice); i.putExtra(EXTRA_HINT, hint); i.setPackage(packageName); sendBroadcast(i) }
        s.onRemoteInputText = { text, flags -> Log.i("\u8fdc\u7a0b\u8f93\u5165\u6587\u672c\u56de\u4f20: text=$text flags=$flags") }
        s.onRemoteInputDone = { Log.i("\u8fdc\u7a0b\u8f93\u5165\u5b8c\u6210\u56de\u4f20") }
    }
    private fun isPeerAllowed(ip: String): Boolean { if (ip.isBlank()) return true; if (!onlyTrustedEnabled(applicationContext)) return true; val ok = trustedPeers(applicationContext).contains(ip); if (!ok) Log.w("\u6388\u6743\u62d2\u7edd\uff1a$ip \u4e0d\u5728\u5141\u8bb8\u540d\u5355"); return ok }
    private fun openScreenPage() { runCatching { val i = Intent(applicationContext, com.allperiph.tv.ui.TvScreenActivity::class.java); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) }.onFailure { Log.w("\u62c9\u8d77\u526f\u5c4f\u9875\u5931\u8d25\uff1a${it.message}") } }
    private fun ensureControlPlane() { if (server == null) server = newServer(); if (server!!.start()) ensureBeacon() else Log.e("TV \u63a7\u5236\u9762\u542f\u52a8\u5931\u8d25") }
    private fun ensureBeacon() { if (beacon != null) return; val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"; beacon = WirelessBeacon(name, name, ControlServer.PORT, "", unicastHosts = { val out = ArrayList<String>(4); server?.currentPeerHost()?.let { out.add(it) }; out.addAll(knownPeers()); out }).also { it.start() } }
    private fun startWatchdog() { if (!wdRunning.compareAndSet(false, true)) return; Thread({ while (wdRunning.get()) { try { Thread.sleep(15000) } catch (_: InterruptedException) { break }; if (!wdRunning.get()) break; try { val listening = server?.isListening == true; if (!listening) { Log.w("\u770b\u95e8\u72d7\uff1a9511 \u4e0d\u5728\u76d1\u542c \u2192 \u91cd\u5efa"); runCatching { server?.stop() }; server = newServer().also { it.start() }; ensureBeacon() } else if (beacon == null) ensureBeacon(); refreshNotification() } catch (t: Throwable) { Log.w("\u770b\u95e8\u72d7\u5f02\u5e38\uff1a${t.message}") } } }, "apx-tv-watchdog").start() }
    override fun onDestroy() { wdRunning.set(false); beacon?.stop(); beacon = null; media?.stop(); media = null; server?.stop(); server = null; current = null; super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); Log.w("\u4efb\u52a1\u88ab\u79fb\u9664 \u2192 \u91cd\u542f"); restartService() }
    private fun restartService() { val ok = runCatching { val i = Intent(applicationContext, TvServerService::class.java); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i); true }.getOrElse { false }; if (!ok) KeepAlive.schedule(applicationContext, 1_000L) }
    override fun onBind(intent: Intent?): IBinder? = null
    fun status(): String = server?.statusText() ?: "\u672a\u542f\u52a8"
    fun ensureMedia() { if (media != null) return; media = MediaChannel(onFrame = { streamId, _, _, body -> when (streamId) { ApxFrame.STREAM_VIDEO -> Renderer.submit(body); ApxFrame.STREAM_AUDIO -> TvSpeaker.submit(body); else -> {} } }).also { if (!it.start()) Log.w("9512 \u672a\u542f\u52a8") } }
    fun mediaStatus(): String = media?.statusText() ?: "\u8fd8\u6ca1\u6536\u5230\u7535\u8111\u753b\u9762"
    fun sendTargets(): List<String> { val out = ArrayList<String>(4); server?.currentPeerHost()?.let { out.add(it) }; out.addAll(knownPeers()); return out.distinct() }
    fun currentPeer(): String? = server?.currentPeerHost()
    fun sendInputText(text: String, flags: Int = 0x01) { server?.sendInputText(text, flags) }
    fun sendInputDone() { server?.sendInputDone() }
    private fun injectStateText(): String = TvInjector.channelText()
    private fun refreshNotification() { runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFY_ID, buildNotification(server?.statusText() ?: "\u6b63\u5728\u542f\u52a8\u2026")) } }
    private fun rememberPeer(ip: String) { val set = knownPeers().toMutableSet(); if (set.add(ip)) { getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PEERS, set.toList().takeLast(8).joinToString(",")).apply() } }
    private fun knownPeers(): List<String> = getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PEERS, "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    private fun buildNotification(text: String): Notification { val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this); builder.setContentTitle(getString(R.string.tv_notification_title)).setContentText(text + " \u00b7 " + injectStateText() + (if (fgFailed) " \u00b7 \u540e\u53f0\u8fd0\u884c\u53d7\u9650" else "")).setSmallIcon(R.drawable.ic_launcher_tv).setOngoing(true); return builder.build() }
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
        override fun start(c: Context) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).putBoolean(KEY_USER_STOPPED, false).apply(); val i = Intent(c, TvServerService::class.java); runCatching { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }.onFailure { Log.w("\u62c9\u8d77\u5931\u8d25\uff1a${it.message}") } }
        fun startIfNeeded(c: Context) { if (isUserStopped(c)) return; start(c) }
        override fun stopAll(c: Context) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).putBoolean(KEY_USER_STOPPED, true).apply(); KeepAlive.cancel(c); runCatching { c.stopService(Intent(c, TvServerService::class.java)) }.onFailure { Log.w("\u505c\u6b62\u5931\u8d25\uff1a${it.message}") } }
    }
}