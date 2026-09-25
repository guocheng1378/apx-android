package com.allperiph.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.allperiph.tv.core.Log
import com.allperiph.tv.media.TvMediaChannel
import com.allperiph.tv.net.TcpControlServer
import com.allperiph.tv.net.WirelessBeacon

/**
 * 前台服务：持有 [TcpControlServer] 与**发现信标**，使 TV 服务端在 Activity 退到后台后仍可连。
 * 老版本（API 23）用 2 参 startForeground 即可；API 34 需显式带 connectedDevice 类型。
 *
 * 信标为什么必须在服务里（而不是 Activity）：
 * 以前 `WirelessBeacon` 是在 `MainActivity.onCreate` 里起的 —— 用户一按返回键 / 切走，
 * Activity 销毁，信标随之停发，手机和 PC 就再也**发现不到**本机（只能手填 IP）。
 * 现在信标跟着服务走：服务活着就持续广播，与界面无关。
 */
class TvServerService : Service() {

    private var server: TcpControlServer? = null
    private var media: TvMediaChannel? = null
    private var beacon: WirelessBeacon? = null

    /** 看门狗线程开关 */
    private val wdRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** startForeground 是否失败（缺通知权限时会发生 → 服务其实不是前台，极易被杀） */
    @Volatile
    private var fgFailed = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "APX TV 状态",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureControlPlane()

        // 副屏 / 音箱（9502 媒体通道）：与控制面并列，独立启停 —— 关副屏不影响键鼠。
        // 端口被占（例如同机上另一个接收端在跑）时优雅降级、仅告警。
        ensureMedia()

        val notif = buildNotification(server?.statusText() ?: "服务启动中")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFY_ID, notif)
            }
        } catch (t: Throwable) {
            fgFailed = true
            Log.w("startForeground 失败（权限？）：${t.message}")
        }
        current = this
        startWatchdog()
        return START_STICKY
    }

    // —————————————————————————— 可重建的控制面 / 信标（看门狗用） ——————————————————————————

    private fun newServer(): TcpControlServer = TcpControlServer().also { s ->
        s.onPeerChanged = { connected, ip ->
            Log.i("对端变化：connected=$connected ip=$ip（记下后可供文件发送选目标）")
            if (connected && ip.isNotEmpty()) rememberPeer(ip)
            refreshNotification()
        }
    }

    /** 幂等：保证 9511 控制面与发现信标都在跑（首次启动与看门狗自愈共用） */
    private fun ensureControlPlane() {
        if (server == null) server = newServer()
        if (server!!.start()) {
            ensureBeacon()
        } else {
            Log.e("TV 控制面启动失败")
        }
    }

    private fun ensureBeacon() {
        if (beacon != null) return
        // 名字用机型：对面设备列表里一眼认出是哪台电视/盒子（原来写死 "APX-TV"）。
        val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"
        beacon = WirelessBeacon(
            name,
            TcpControlServer.PORT,
            "",
            // 单播兜底：不少 AP / Mesh / 中继会丢「Wi‑Fi → 有线」的广播（反方向却正常），
            // 只发广播时 PC 端会永远停在「正在发现」。对「当前连入方 + 曾经连过的对端」
            // 再单播一份，走的是已验证能通的路径。
            unicastHosts = {
                val out = ArrayList<String>(4)
                server?.currentPeerHost()?.let { out.add(it) }
                out.addAll(knownPeers())
                out
            },
        ).also { it.start() }
    }

    /**
     * **看门狗**：电视 / 盒子对后台服务下手很狠（省电、内存回收），`START_STICKY` 不保证拉起。
     * 这里每 15 秒用「本机 TCP 自连」确认 9511 还在监听 —— 不在就**就地重建**控制面与信标，
     * 不必等系统想起我们。这是"用着用着突然失联"的最后防线。
     */
    private fun startWatchdog() {
        if (!wdRunning.compareAndSet(false, true)) return
        Thread({
            while (wdRunning.get()) {
                try {
                    Thread.sleep(15000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!wdRunning.get()) break
                try {
                    val listening = try {
                        java.net.Socket().use {
                            it.connect(java.net.InetSocketAddress("127.0.0.1", TcpControlServer.PORT), 400)
                            true
                        }
                    } catch (_: Throwable) {
                        false
                    }
                    if (!listening) {
                        Log.w("看门狗：9511 不在监听 → 就地重建控制面与信标")
                        runCatching { server?.stop() }
                        server = newServer().also { it.start() }
                        ensureBeacon()
                    } else if (beacon == null) {
                        ensureBeacon()
                    }
                    refreshNotification()
                } catch (t: Throwable) {
                    Log.w("看门狗异常：${t.message}")
                }
            }
        }, "apx-tv-watchdog").start()
    }

    override fun onDestroy() {
        wdRunning.set(false)
        beacon?.stop()
        beacon = null
        media?.stop()
        media = null
        server?.stop()
        server = null
        current = null
        super.onDestroy()
    }

    /**
     * 用户从「最近任务」清掉 App：不少电视 / 盒子会**连进程一起杀**，被控端随即失联
     * （真机症状："一退出软件，手机那边就断了"）。系统在杀进程前会回调这里，
     * 立刻把自己重新拉起来；系统拒绝后台启动时记日志不崩。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w("任务被移除 → 立即重启 TV 被控服务")
        runCatching {
            val i = Intent(applicationContext, TvServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }.onFailure { Log.w("重启 TV 服务失败：${it.message}") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 供界面读取实时状态（Activity 重建后也能立刻显示「已连接 xxx」） */
    fun status(): String = server?.statusText() ?: "未启动"

    /**
     * 副屏 / 音箱的媒体通道（9502）。与控制面分开：媒体是大流量，不能挤占输入链路。
     * 幂等 —— 界面每次进副屏页都可以调一次；端口被占时只告警，不阻断控制面。
     */
    fun ensureMedia() {
        if (media != null) return
        media = TvMediaChannel().also {
            if (!it.start()) Log.w("9502 媒体通道未启动（端口被占用？）：副屏与音箱不可用")
        }
    }

    /** 媒体通道状态（供副屏页显示「等 PC 连入 / 已连接」） */
    fun mediaStatus(): String = media?.statusText() ?: "媒体：未启动"

    /**
     * 文件发送目标候选：**当前连入方优先**，其次曾经连过的对端。
     * 电视上用遥控器输 IP 太痛苦，所以发送目标只从「连过的设备」里挑。
     */
    fun sendTargets(): List<String> {
        val out = ArrayList<String>(4)
        server?.currentPeerHost()?.let { out.add(it) }
        out.addAll(knownPeers())
        val distinct = out.distinct()
        Log.i("发送目标候选：当前=${server?.currentPeerHost() ?: "-"} 记住的=${knownPeers()} → ${distinct}")
        return distinct
    }

    /** 当前连入方 IP（没有则 null） */
    fun currentPeer(): String? = server?.currentPeerHost()

    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFY_ID, buildNotification(server?.statusText() ?: "服务启动中"))
        }
    }

    // —————————————————————————— 已知对端（信标单播兜底用） ——————————————————————————

    /** 记下曾经连入过的对端 IP：TV 自己不发起连接，只能靠这份历史做单播兜底 */
    private fun rememberPeer(ip: String) {
        val set = knownPeers().toMutableSet()
        if (!set.add(ip)) return
        // 只留最近 8 个，避免无上限增长
        val trimmed = set.toList().takeLast(8)
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_PEERS, trimmed.joinToString(","))
            .apply()
    }

    private fun knownPeers(): List<String> =
        getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_PEERS, "")
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder.setContentTitle(getString(R.string.tv_notification_title))
            .setContentText(
                text + if (fgFailed) " · 未授予通知权限，后台易被系统回收（到系统设置开启通知）" else ""
            )
            .setSmallIcon(R.drawable.ic_launcher_tv)
            .setOngoing(true)
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "apxtv_status"
        private const val NOTIFY_ID = 1

        /** 已知对端 IP 的落盘位置（[BootReceiver] 与信标共用） */
        const val PREF = "apx_tv"
        private const val KEY_PEERS = "peers"

        /** 「用户开过一次」的标记：开机自启（[BootReceiver]）据此决定是否拉起服务 */
        const val KEY_ENABLED = "enabled"

        /** 当前实例（界面读取状态用；服务未启动时为 null） */
        @Volatile
        var current: TvServerService? = null
            private set
    }
}
