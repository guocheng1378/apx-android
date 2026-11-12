package com.allperiph.localsend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
// `var x by mutableStateOf(...)` 需要 getValue/setValue 两个扩展在作用域内，
// 缺了编译器报「cannot serve as a delegate」（fd842c1 的 CI 实锤：fully-qualified
// mutableStateOf 只解决了初始化表达式，委托转换仍要这两个 import）。
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URL
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LocalSend 互传接收端前台服务（MVP1）：
 *  - UDP 多播监听 224.0.0.167:53317 + 周期 announce（让桌面端看见本机）
 *  - 收到他人 announce → HTTP POST register 应答（对端加入本机列表）
 *  - 本地 HTTP server 服务 info/register/prepare/upload/cancel；文件落系统 Download/LocalSend/
 *  - HTTP 端口 53317 被占时回退 53318/53319，announce 携带实际监听端口
 *  - 通知用原生 Notification.Builder（app 模块无 androidx.core 依赖）
 */
class LocalSendService : Service() {

    companion object {
        const val ACTION_START = "com.allperiph.localsend.START"
        const val ACTION_STOP = "com.allperiph.localsend.STOP"
        private const val CHAN_ID = "localsend_rx"
        private const val NOTIFY_FGS = 0x105E
        private const val TAG = "ApxLocalSend"
        private const val ANNOUNCE_PERIOD_MS = 30_000L
        private const val REGISTER_DEDUP_MS = 30_000L
        /**
         * 接收端此刻是否真的在跑（进程内静态）。
         *
         * 设置页此前只看 `localsend_enabled` 这个**意愿位**，于是重启后开关还是「开」，
         * 服务却早就没了；这一位给它一个能对账的事实来源（审查 v5 §9.3 的补丁 0004）。
         * 与 `FloatingBallService.isRunning` 同一套写法：意愿位、实况位分开。
         *
         * issue#169 第 22 条：改成 Compose 快照状态（`mutableStateOf`）—— 此前是普通
         * `@Volatile`，设置页 summary 读它不触发重组，「发现通道未就绪」提示会滞留
         * （通道早就重建好了，文案还挂着）；跨线程写快照状态是安全的，语义不变。
         * 注意：Kotlin 不允许 `@Volatile` 修饰委托属性（编译器直接报错，
         * v223.29.88 前的 CI 曾栽在这），快照状态自身跨线程写安全，无需叠加。
         */
        var isRunning: Boolean by androidx.compose.runtime.mutableStateOf(false)
            private set

        /**
         * 发现通道（UDP 多播/广播）此刻是否真的可用。
         *
         * 必须和 [isRunning] 分开：服务在前台、HTTP 53317 在听、收文件也正常，但发现线程
         * 可能已经死了 —— v223.29.32 现场就是这台机器 40 分钟没往 PC 发过一条 announce，
         * 面板「附近设备」永远空，开关却显示「开」。
         *
         * issue#169 第 22 条排查结论（mac.log + phone.log 佐证）：
         *  · Mac 接收端正常（HTTP 53317 在听，能收 register/announce）；
         *  · 用户看到的「发现通道未就绪」多数不是 App 判定错误，而是**链路被路由器挡掉**：
         *    224.0.0.167 是 LocalSend 协议固定组播组地址（不是路由器 IP，用户无需疑惑），
         *    家用 AP 的 IGMP snooping 无 querier 时会静默丢弃组播；openclash 等 TUN/规则
         *    类工具也可能拦组播。App 侧已做多播组 + 受限广播 + 网段广播三路兜底（见
         *    [buildAnnounceTargets]），仍不通时请在路由器放行 224.0.0.0/4 或关掉 TUN；
         *  · 同时改成 Compose 快照状态，设置页 summary 随通道起落即时刷新，不再滞留
         *    （同理不加 `@Volatile`：委托属性不适用该注解，快照状态跨线程写安全）。
         */
        var discoveryUp: Boolean by androidx.compose.runtime.mutableStateOf(false)
            private set

        /** 本机 HTTP 监听端口（53317 被占时回退 53318/53319），register 自报用 */
        @Volatile
        var httpPort: Int = LocalSendProtocol.HTTP_PORT
            private set

        fun start(context: Context) = context.startForegroundService(Intent(context, LocalSendService::class.java).setAction(ACTION_START))
        fun stop(context: Context) = context.startService(Intent(context, LocalSendService::class.java).setAction(ACTION_STOP))
    }

    private class PendingFile(
        val fileId: String, val fileName: String, val size: Long, val sender: String,
        var uri: Uri? = null, var stream: OutputStream? = null, var written: Long = 0L,
    )
    private class Session(val id: String, val sender: String) {
        val files = ConcurrentHashMap<String, PendingFile>()
        var done = 0
    }

    private var multicast: MulticastSocket? = null
    /** announce 目标地址：多播组 + 受限广播 + 网段广播（建 socket 时按实际网卡算出来） */
    @Volatile private var announceTargets: List<InetAddress> = emptyList()
    private var discoveryThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var httpServer: LocalSendHttpServer? = null
    private var httpListenPort = LocalSendProtocol.HTTP_PORT
    private var running = false
    private val sessions = ConcurrentHashMap<String, Session>()
    private val registeredRecently = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val announceRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            sendAnnounce()
            mainHandler.postDelayed(this, ANNOUNCE_PERIOD_MS)
        }
    }

    private val alias: String get() = Build.MODEL ?: "Android"
    private val deviceType: String
        get() = if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); ensureChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { teardown(); stopSelf(); return START_NOT_STICKY }
            else -> if (!running) startAll()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { teardown(); super.onDestroy() }

    private fun startAll() {
        running = true
        isRunning = true
        val n = Notification.Builder(this, CHAN_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("LocalSend 互传")
            .setContentText("等待局域网设备发送文件…")
            .setOngoing(true).build()
        startForeground(NOTIFY_FGS, n)

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("apx-localsend").apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {}

        // HTTP server：53317 被占依次回退 53318/53319
        for (port in intArrayOf(LocalSendProtocol.HTTP_PORT, LocalSendProtocol.HTTP_PORT + 1, LocalSendProtocol.HTTP_PORT + 2)) {
            try {
                val s = LocalSendHttpServer(port, handler)
                s.start()
                httpServer = s; httpListenPort = port
                httpPort = port
                break
            } catch (_: Throwable) { httpServer = null }
        }

        startDiscovery()
    }

    private fun startDiscovery() {
        val alive = discoveryThread?.isAlive == true
        if (alive) return
        discoveryThread = Thread({ discoveryLoop() }, "LocalSend-Discovery").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 发现主循环。
     *
     * **外层 while 是自愈的关键**：旧实现把整个 socket 生命周期塞进一个 try，`receive()`
     * 抛一次就永久退出，异常还被 `catch (_: Throwable) {}` 吞掉 —— 表现就是「开关开着、
     * 53317 在听、收文件也正常，但附近设备永远是空的」。现在任何异常都记日志、关旧 socket、
     * 退避后重建并重新加入多播组（Wi-Fi 重连、网卡切换后自动恢复）。
     */
    private fun discoveryLoop() {
        var failures = 0
        while (running) {
            var ms: MulticastSocket? = null
            try {
                ms = openDiscoverySocket()
                multicast = ms
                discoveryUp = true
                failures = 0
                Log.i(TAG, "发现通道就绪 bind=${ms.localSocketAddress} group=${LocalSendProtocol.MULTICAST_GROUP} targets=${announceTargets.map { it.hostAddress }}")
                longArrayOf(100, 500, 2000).forEach { d -> mainHandler.postDelayed({ sendAnnounce() }, d) }
                mainHandler.removeCallbacks(announceRunnable)
                mainHandler.postDelayed(announceRunnable, ANNOUNCE_PERIOD_MS)
                val buf = ByteArray(64 * 1024)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    ms.receive(packet)
                    if (packet.length <= 0) continue
                    val ip = packet.address?.hostAddress ?: continue
                    // 单条报文解析失败不能带走整条通道（旧实现就是被这里带走的）
                    try {
                        onAnnounce(String(packet.data, 0, packet.length, Charsets.UTF_8), ip)
                    } catch (t: Throwable) {
                        Log.w(TAG, "处理 announce 失败（来自 $ip）", t)
                    }
                }
            } catch (t: Throwable) {
                if (!running) break
                failures++
                discoveryUp = false
                multicast = null
                Log.w(TAG, "发现通道中断（第 $failures 次），准备重建", t)
                mainHandler.removeCallbacks(announceRunnable)
                val backoff = minOf(30_000L, 2_000L * failures)
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            } finally {
                try { ms?.close() } catch (_: Throwable) {}
            }
        }
        discoveryUp = false
    }

    /**
     * 显式绑 IPv4 的发现 socket。
     *
     * 旧写法 `MulticastSocket(port)` 跟随平台默认地址族绑定（这台 Android 17 上落到
     * `[::]:53317`），随后 `joinGroup(..., null)` 又让内核自己挑网卡：多网络（Wi-Fi + 蜂窝）
     * 或默认网卡变化后，「加入组播的网卡」与「发 announce 的出口网卡」可能不是同一张，
     * announce 就再也发不出去。这里把三件事钉死：IPv4 通配绑定、指定网卡、TTL=1。
     */
    private fun openDiscoverySocket(): MulticastSocket {
        val ms = MulticastSocket(null)
        ms.reuseAddress = true
        ms.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), LocalSendProtocol.MULTICAST_PORT))
        ms.timeToLive = 1
        // 广播目标需要 SO_BROADCAST，否则 sendto(255.255.255.255) 直接 EACCES
        try { ms.broadcast = true } catch (t: Throwable) { Log.w(TAG, "开启广播发送失败", t) }
        val group = InetAddress.getByName(LocalSendProtocol.MULTICAST_GROUP)
        val nif = preferredMulticastInterface()
        if (nif != null) {
            try { ms.networkInterface = nif } catch (t: Throwable) { Log.w(TAG, "设置组播网卡失败", t) }
            ms.joinGroup(InetSocketAddress(group, LocalSendProtocol.MULTICAST_PORT), nif)
            Log.i(TAG, "加入多播组 ${LocalSendProtocol.MULTICAST_GROUP} on ${nif.name}")
        } else {
            ms.joinGroup(group)
            Log.w(TAG, "没有可用 IPv4 网卡，多播组按内核默认接口加入")
        }
        announceTargets = buildAnnounceTargets(nif, group)
        return ms
    }

    /** 优先带 IPv4 的 Wi-Fi 网卡；否则任一带 IPv4 的非回环物理网卡 */
    private fun preferredMulticastInterface(): NetworkInterface? {
        val candidates = ArrayList<NetworkInterface>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                if (!nif.inetAddresses.toList().any { it is Inet4Address }) continue
                candidates.add(nif)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "枚举网卡失败", t)
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.name.startsWith("wlan") }
            ?: candidates.firstOrNull { !it.isVirtual }
            ?: candidates.first()
    }

    /**
     * announce 目标：多播组 + 受限广播 255.255.255.255 + 网段广播。
     *
     * 多播是官方通道，但家用路由器/AP 常对 224.0.0.167 做 IGMP snooping 而不带 querier，
     * 无线↔有线方向会被静默丢掉；广播在同一个广播域里更稳，两边都发成本几乎为零。
     */
    private fun buildAnnounceTargets(nif: NetworkInterface?, group: InetAddress): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        out.add(group)
        try {
            val limited = InetAddress.getByName("255.255.255.255")
            if (out.none { it.hostAddress == limited.hostAddress }) out.add(limited)
        } catch (t: Throwable) {
            Log.w(TAG, "取受限广播地址失败", t)
        }
        try {
            nif?.interfaceAddresses?.forEach { ia ->
                val b = ia.broadcast
                if (b is Inet4Address && out.none { it.hostAddress == b.hostAddress }) out.add(b)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "取网段广播地址失败", t)
        }
        return out
    }

    private fun teardown() {
        running = false
        isRunning = false
        discoveryUp = false
        httpPort = LocalSendProtocol.HTTP_PORT
        mainHandler.removeCallbacksAndMessages(null)
        try { discoveryThread?.interrupt() } catch (_: Throwable) {}
        discoveryThread = null
        try { multicast?.leaveGroup(InetSocketAddress(LocalSendProtocol.MULTICAST_GROUP, LocalSendProtocol.MULTICAST_PORT), null) } catch (_: Throwable) {}
        try { multicast?.close() } catch (_: Throwable) {}
        multicast = null
        announceTargets = emptyList()
        try { httpServer?.stop() } catch (_: Throwable) {}
        httpServer = null
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
    }

    private fun sendAnnounce() {
        val ms = multicast ?: return
        val payload = try {
            LocalSendProtocol.announce(alias, deviceType, true, httpListenPort).toString().toByteArray(Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "构造 announce 失败", t)
            return
        }
        val targets = announceTargets.ifEmpty {
            listOfNotNull(runCatching { InetAddress.getByName(LocalSendProtocol.MULTICAST_GROUP) }.getOrNull())
        }
        var sent = 0
        for (addr in targets) {
            try {
                ms.send(DatagramPacket(payload, payload.size, addr, LocalSendProtocol.MULTICAST_PORT))
                sent++
            } catch (t: Throwable) {
                Log.w(TAG, "announce → ${addr.hostAddress} 失败", t)
            }
        }
        if (sent == 0) {
            // 一个目标都发不出去 = 发现通道实际已废：标记出来（设置页据此提示），
            // 并关掉 socket 让主循环走重建分支自愈。
            discoveryUp = false
            Log.w(TAG, "announce 全部目标发送失败，重建发现通道")
            try { ms.close() } catch (_: Throwable) {}
        }
    }

    private fun onAnnounce(text: String, ip: String) {
        val msg = try { JSONObject(text) } catch (_: Throwable) { return }
        if (msg.optString("fingerprint") == LocalSendProtocol.fingerprint) return
        if (msg.optString("protocol", "http") != "http") return
        val port = msg.optInt("port", 0); if (port !in 1..65535) return
        // 批H：每次 announce 都刷新发送端设备表（置于 dedup 前，lastSeen 才不过期）
        LocalSendClient.onAnnounce(msg, ip)
        val fp = msg.optString("fingerprint", ip)
        val now = System.currentTimeMillis()
        val last = registeredRecently[fp]
        if (last != null && now - last < REGISTER_DEDUP_MS) return
        registeredRecently[fp] = now
        postRegister(ip, port)
    }

    private fun postRegister(ip: String, port: Int) {
        Thread {
            try {
                val conn = URL("http://$ip:$port${LocalSendProtocol.PATH_REGISTER}").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000; conn.readTimeout = 3000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                // register body 必须带 port / protocol：对端（PC 接收端）靠这两个字段把本机
                // 记进「附近设备」，缺 port 会被静默丢弃 —— 多播被路由器挡掉时这是唯一的发现通路。
                val body = LocalSendProtocol.register(alias, deviceType, true, httpListenPort)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                Log.i(TAG, "register → $ip:$port HTTP $code")
            } catch (t: Throwable) {
                Log.w(TAG, "register → $ip:$port 失败", t)
            }
        }.apply { isDaemon = true; start() }
    }

    private val handler = object : LocalSendHttpServer.Handler {
        override fun deviceInfo(): JSONObject = LocalSendProtocol.deviceInfo(alias, deviceType, true)

        override fun prepareUpload(body: JSONObject): JSONObject {
            val info = body.optJSONObject("info")
            val sender = info?.optString("alias") ?: "未知设备"
            val session = Session(UUID.randomUUID().toString(), sender)
            val respFiles = JSONObject()
            val files = body.optJSONObject("files") ?: return JSONObject().put("sessionId", session.id)
            files.keys().forEach { key ->
                val f = files.optJSONObject(key) ?: return@forEach
                session.files[key] = PendingFile(
                    fileId = key,
                    fileName = sanitize(f.optString("fileName", "file_$key")),
                    size = f.optLong("size", -1L),
                    sender = sender,
                )
                respFiles.put(key, key)
            }
            sessions[session.id] = session
            return JSONObject().put("sessionId", session.id).put("files", respFiles)
        }

        override fun openUpload(sessionId: String, fileId: String): OutputStream? {
            val session = sessions[sessionId] ?: return null
            val pf = session.files[fileId] ?: return null
            synchronized(pf) {
                if (pf.stream != null) return pf.stream
                val created = try { createTarget(pf.fileName) } catch (_: Throwable) { return null }
                pf.uri = created.first; pf.stream = created.second
                return created.second
            }
        }

        override fun finishUpload(sessionId: String, fileId: String, ok: Boolean) {
            val session = sessions[sessionId] ?: return
            val pf = session.files[fileId] ?: return
            val sizeOk = pf.size < 0 || pf.written == pf.size
            if (ok && sizeOk) {
                markComplete(pf)
                val allDone = synchronized(session) {
                    session.done++
                    session.done >= session.files.size
                }
                if (allDone) { notifyDone(session); sessions.remove(sessionId) }
            } else {
                discard(pf)
                sessions.remove(sessionId)
                notifyFailed(pf)
            }
        }

        override fun cancel(sessionId: String?) {
            val session = sessionId?.let { sessions.remove(it) } ?: return
            session.files.values.forEach { discard(it) }
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_').trim().ifEmpty { "file" }
        return if (cleaned.length > 180) cleaned.takeLast(180) else cleaned
    }

    /** API29+ MediaStore Downloads/LocalSend（免权限）；低版本写公共 Download */
    private fun createTarget(fileName: String): Pair<Uri?, OutputStream> {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE,
                    URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LocalSend")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("insert failed")
            val os = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("open failed")
            return uri to os
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalSend")
        dir.mkdirs()
        var target = File(dir, fileName)
        if (target.exists()) {
            val base = fileName.substringBeforeLast('.', fileName)
            val ext = if ('.' in fileName) ".${fileName.substringAfterLast('.')}" else ""
            var i = 1
            while (target.exists()) target = File(dir, "${base} (${i++})${ext}")
        }
        return null to target.outputStream()
    }

    private fun markComplete(pf: PendingFile) {
        pf.uri?.let { uri ->
            try {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(uri, v, null, null)
            } catch (_: Throwable) {}
        }
    }
    private fun discard(pf: PendingFile) {
        try { pf.stream?.close() } catch (_: Throwable) {}
        pf.stream = null
        pf.uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Throwable) {} }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHAN_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHAN_ID, "LocalSend 互传", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
    private fun notifyDone(session: Session) {
        val names = session.files.values.joinToString("、") { it.fileName }
        notify("LocalSend 已接收 ${session.files.size} 个文件（来自 ${session.sender}）", names.take(120))
    }
    private fun notifyFailed(pf: PendingFile) {
        notify("LocalSend 接收失败", pf.fileName)
    }
    private fun notify(title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val pending = PendingIntent.getActivity(this, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, CHAN_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(pending).setAutoCancel(true).build()
            nm.notify((System.currentTimeMillis() and 0xFFFFL).toInt(), n)
        } catch (_: Throwable) {}
    }
}
