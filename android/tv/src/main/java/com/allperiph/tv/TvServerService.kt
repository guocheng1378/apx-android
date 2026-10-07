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
    /** @Volatile：主线程（onStartCommand/onDestroy）与看门狗线程交叉读写 */
    @Volatile private var server: ControlServer? = null
    private var media: MediaChannel? = null
    private var beacon: WirelessBeacon? = null
    private val wdRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private var watchdog: Thread? = null
    @Volatile private var fgFailed = false
    /** 已知对端缓存：信标每 1.5s 取值一次，不能每次都读磁盘 SharedPreferences */
    @Volatile private var peersCache: List<String>? = null
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
        if (Build.VERSION.SDK_INT >= 26) { val ch = NotificationChannel(CHANNEL_ID, "全能外设 TV 状态", NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch) }
        current = this
        TvInjector.init(applicationContext, TvInjectorPlatform)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { startForegroundCompat(); ensureControlPlane(); ensureMedia(); ensureFileReceiver(); KeepAlive.schedule(this, if (fgFailed) 30_000L else 60_000L); startWatchdog(); return START_STICKY }
    private fun startForegroundCompat() { val notif = buildNotification(server?.statusText() ?: "正在启动…"); if (Build.VERSION.SDK_INT >= 34) { try { startForeground(NOTIFY_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); fgFailed = false; return } catch (t: Throwable) { Log.w("startForeground(connectedDevice) 失败：${t.message}") } }; try { startForeground(NOTIFY_ID, notif); fgFailed = false } catch (t: Throwable) { fgFailed = true; Log.w("startForeground 失败：${t.message}") } }
    private fun ensureFileReceiver() { runCatching { FileReceiver.start(applicationContext); FileReceiver.onReceived = { c, f -> com.allperiph.tv.ui.TvFilePrompt.show(c, f) } }.onFailure { Log.w("9512 未启动：${it.message}") } }
    private fun newServer(): ControlServer = ControlServer(endpoint = controlEndpoint).also { s ->
        s.onPeerChanged = { connected, ip -> Log.i("对端变化：con=$connected ip=$ip"); if (connected && ip.isNotEmpty()) rememberPeer(ip); TvInjector.setConnected(connected); refreshNotification() }
        s.onOpenScreenRequest = { openScreenPage() }
        s.onAuthorizePeer = { ip -> isPeerAllowed(ip) }
        s.onRemoteInputRequest = { fromDevice, hint -> Log.i("远程输入请求: from=$fromDevice hint=$hint"); val i = Intent(ACTION_REMOTE_INPUT); i.putExtra(EXTRA_SOURCE, fromDevice); i.putExtra(EXTRA_HINT, hint); i.setPackage(packageName); sendBroadcast(i) }
        s.onRemoteInputText = { text, flags -> injectRemoteText(text, flags) }
        s.onRemoteInputDone = { Log.i("远程输入完成回传"); awaitingRemoteInput = false }
        // ★ 0x10 模块开关：TV 端此前**从未注册**这个回调，PC 面板拨「无线」开关时 TV 只打
        //   一行日志就结束 —— suspendAccept()/resumeAccept() 永不调用。PC 关掉无线后 TV
        //   仍在接受并注入输入，PC 侧却以为已挂起（手机端 ControlledService 早就注册了）。
        s.onModuleToggle = { id, on ->
            Log.i("模块开关：$id -> $on")
            if (id == "wireless") { if (on) s.resumeAccept() else s.suspendAccept() }
            refreshNotification()
        }
        // 「让对端帮我输入」的**发起端**：本机（电视）输入框获焦 → 请对端弹输入法。
        // 这条线以前完全没接（ApxAccessibilityService.onFocusDetected 全仓库零注册点），
        // 无障碍服务检测到的焦点事件被丢掉了，所以手机输入法再也不会被自动唤起。
        com.allperiph.shared.accessibility.ApxAccessibilityService.onFocusDetected = { hint ->
            val peer = s.currentPeerHost()
            if (awaitingRemoteInput) {
                // 上轮请求还没回传就别再喊 —— 否则每注入一次文本，对端输入面板又弹一次
                Log.i("上一轮远程输入还没回传 —— 不重复请求")
            } else if (peer.isNullOrEmpty()) {
                Log.i("输入框获焦，但当前没有对端 —— 不请求远程输入")
            } else {
                Log.i("输入框获焦 → 请求 $peer 输入: hint=$hint")
                awaitingRemoteInput = true
                s.sendRequestInput(hint)
            }
        }
    }

    /** 已发出 0x25、还在等对端回传。防「注入文本 → 输入框再获焦 → 又发一次请求」的回环 */
    @Volatile private var awaitingRemoteInput = false

    /**
     * 把对端敲的字落到本机当前聚焦的输入框。
     * 此前这里只有一行 Log：对端弹了输入法、用户也打了字，但字**根本没进输入框** ——
     * 「请对端帮我输入」等于白喊（PC 端同样的活由 injectSystemText 承担）。
     *
     * 语义按 [ApxFrame.INPUT_FLAG_*]：增量=追加、退格=删一字。COMMIT(0x04) 是"整段再粘一遍"，
     * 与增量并存就是 340856f 修的「文字重复两次」，发送端已不再发它，这里也不按追加处理。
     */
    private fun injectRemoteText(text: String, flags: Int) {
        awaitingRemoteInput = false          // 字回来了，本轮请求结束
        val svc = ApxAccessibilityService.instance
        if (svc == null) { Log.w("远程输入没落地：无障碍服务未就绪"); return }
        when {
            flags and ApxFrame.INPUT_FLAG_BACKSPACE != 0 -> svc.deleteChar()
            flags and ApxFrame.INPUT_FLAG_CANCEL != 0 -> Unit   // 发送端改用退格逐字撤销
            else -> if (text.isNotEmpty() && !svc.typeText(text) && !svc.paste(text)) {
                Log.w("远程输入注入失败（输入框没聚焦？）")
            }
        }
    }

    private fun isPeerAllowed(ip: String): Boolean { if (ip.isBlank()) return true; if (!onlyTrustedEnabled(applicationContext)) return true; val ok = trustedPeers(applicationContext).contains(ip); if (!ok) Log.w("授权拒绝：$ip 不在允许名单"); return ok }
    private fun openScreenPage() { runCatching { val i = Intent(applicationContext, com.allperiph.tv.ui.TvScreenActivity::class.java); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i) }.onFailure { Log.w("拉起副屏页失败：${it.message}") } }
    private fun ensureControlPlane() { if (server == null) server = newServer(); if (server!!.start()) ensureBeacon() else Log.e("TV 控制面启动失败") }
    /**
     * ⚠️ [WirelessBeacon] 第一个参数是**设备类型前缀**（对端按它判定"这是电视/PC/手机被控"），
     * 第二个才是显示名。之前这里误把设备名 `Build.MODEL` 当前缀传了两次，载荷变成
     * `"<机型> <机型> 9511 "` —— 手机端 `TvDiscovery` 与 PC 端都只认 `APX1TV ` / `APX1PC ` /
     * `APX1PH ` 开头，其余一律 `continue` 丢弃，于是**这台电视永远搜不到**（控制面还在监听，
     * 手动填 IP 能连，但自动发现对它是死的）。前缀必须是常量，不能跟设备名混用。
     */
    private fun ensureBeacon() {
        if (beacon != null) return
        val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"
        beacon = WirelessBeacon(
            BEACON_PREFIX_TV,
            name,
            ControlServer.PORT,
            "",
            unicastHosts = {
                val out = ArrayList<String>(4)
                server?.currentPeerHost()?.let { out.add(it) }
                out.addAll(knownPeers())
                out
            },
        ).also { it.start() }
    }
    private fun startWatchdog() {
        if (!wdRunning.compareAndSet(false, true)) return
        val t = Thread({
            while (wdRunning.get()) {
                try { Thread.sleep(WATCHDOG_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (!wdRunning.get()) break
                try {
                    val listening = server?.isListening == true
                    if (!listening) {
                        Log.w("看门狗：9511 不在监听 → 重建")
                        runCatching { server?.stop() }
                        // 服务可能已在 sleep 期间被销毁：此时再建一个 ControlServer 就
                        // 再也无人 stop（ServerSocket:9511 + apx-inject 线程永久泄漏）
                        if (!wdRunning.get()) break
                        server = newServer().also { it.start() }
                        ensureBeacon()
                    } else if (beacon == null) ensureBeacon()
                    refreshNotification()
                } catch (t2: Throwable) { Log.w("看门狗异常：${t2.message}") }
            }
        }, "apx-tv-watchdog")
        // 守护线程 + 保留句柄：否则服务销毁后线程最长滞留一整个周期，并强引用整个 Service
        t.isDaemon = true
        watchdog = t
        t.start()
    }

    override fun onDestroy() {
        wdRunning.set(false)
        watchdog?.let { t ->
            if (t.isAlive && t !== Thread.currentThread()) {
                t.interrupt()
                runCatching { t.join(1000) }
            }
        }
        watchdog = null
        beacon?.stop(); beacon = null
        media?.stop(); media = null
        server?.stop(); server = null
        // 以前漏了这四项 → 服务停止后 9512 文件口仍在监听、光标浮层永远挂在 WindowManager 上、
        // 音箱的 AudioTrack 与播放线程不释放、收文件回调不解绑（对比手机端 ControlledService 是调了的）
        runCatching { FileReceiver.stop() }
        FileReceiver.onReceived = null
        // 焦点检测回调是**静态**的且闭包捕获了本 Service，不清会一直挂到下次服务启动
        com.allperiph.shared.accessibility.ApxAccessibilityService.onFocusDetected = null
        // 收文件浮窗若还开着，服务停了它就会一直挂在电视上抢遥控器
        com.allperiph.tv.ui.TvFilePrompt.dismiss()
        // 通知队列目前没有消费端（转发需新增协议帧），服务停了必须清空：
        // 否则本机所有 App 的通知文本会一直留在内存里
        com.allperiph.tv.notification.NotificationQueue.clear()
        TvInjector.onDestroy()
        TvSpeaker.stop()
        peersCache = null
        current = null
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); Log.w("任务被移除 → 重启"); restartService() }
    private fun restartService() { val ok = runCatching { val i = Intent(applicationContext, TvServerService::class.java); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i); true }.getOrElse { false }; if (!ok) KeepAlive.schedule(applicationContext, 1_000L) }
    override fun onBind(intent: Intent?): IBinder? = null
    fun status(): String = server?.statusText() ?: "未启动"
    /** 媒体口是 9502（副屏画面 / 电脑声音）；9512 是文件口 —— 以前这里日志写错，排障时会误导 */
    /**
     * ★ 以前 start() 失败时 media 仍被赋成非 null 实例：之后每次 ensureMedia()
     *   （KeepAlive 每 60s 一次）都在第一行 return，9502 **再也不会重试** —— 开机自启时
     *   Wi-Fi 可能尚未就绪，一次失败就导致副屏永久黑屏、电脑声音永久不出，只能重启进程。
     *   现在失败不落 media，下次调用自然重试。
     */
    fun ensureMedia() {
        if (media != null) return
        val ch = MediaChannel(onFrame = { streamId, _, _, body -> when (streamId) { ApxFrame.STREAM_VIDEO -> Renderer.submit(body); ApxFrame.STREAM_AUDIO -> TvSpeaker.submit(body); else -> {} } })
        if (!ch.start()) { Log.w("9502 未启动（等网络就绪后自动重试）"); return }
        media = ch
    }
    fun mediaStatus(): String = media?.statusText() ?: "还没收到电脑画面"
    fun sendTargets(): List<String> { val out = ArrayList<String>(4); server?.currentPeerHost()?.let { out.add(it) }; out.addAll(knownPeers()); return out.distinct() }
    fun currentPeer(): String? = server?.currentPeerHost()
    fun sendInputText(text: String, flags: Int = 0x01) { server?.sendInputText(text, flags) }
    fun sendInputDone() { server?.sendInputDone() }
    private fun injectStateText(): String = TvInjector.channelText()
    private fun refreshNotification() { runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFY_ID, buildNotification(server?.statusText() ?: "正在启动…")) } }
    private fun rememberPeer(ip: String) {
        val set = knownPeers().toMutableSet()
        if (!set.add(ip)) return
        val list = set.toList().takeLast(MAX_REMEMBERED_PEERS)
        peersCache = list
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PEERS, list.joinToString(",")).apply()
    }

    /**
     * 已知对端。信标每 1.5s 取一次（[WirelessBeacon.PERIOD_MS]）—— 不能每次都读磁盘：
     * 那是后台网络线程上的周期性 SharedPreferences IO，还会和 `apply()` 抢同一把锁。
     */
    private fun knownPeers(): List<String> {
        peersCache?.let { return it }
        val list = getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_PEERS, "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        peersCache = list
        return list
    }
    private fun buildNotification(text: String): Notification { val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this); builder.setContentTitle(getString(R.string.tv_notification_title)).setContentText(text + " · " + injectStateText() + (if (fgFailed) " · 后台运行受限" else "")).setSmallIcon(R.drawable.ic_launcher_tv).setOngoing(true); return builder.build() }
    companion object : ServiceController {
        private const val CHANNEL_ID = "apxtv_status"
        private const val NOTIFY_ID = 1
        /** 信标设备类型前缀（对端按它识别电视）；取值须与手机端 `TvDiscovery` 的分支一致 */
        private const val BEACON_PREFIX_TV = "APX1TV"
        /** 看门狗巡检间隔：控制面掉监听后靠它重建 */
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        /** 最多记住几个对端（信标单播兜底用） */
        private const val MAX_REMEMBERED_PEERS = 8
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