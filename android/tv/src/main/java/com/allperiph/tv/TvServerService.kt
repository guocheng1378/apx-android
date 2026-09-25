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
    private var beacon: WirelessBeacon? = null

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
        if (server == null) {
            val s = TcpControlServer()
            s.onPeerChanged = { connected, ip ->
                Log.i("对端变化：connected=$connected ip=$ip（记下后可供文件发送选目标）")
                if (connected && ip.isNotEmpty()) rememberPeer(ip)
                refreshNotification()
            }
            server = s
        }
        if (server!!.start()) {
            if (beacon == null) {
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
        } else {
            Log.e("TV 控制面启动失败")
        }

        val notif = buildNotification(server?.statusText() ?: "服务启动中")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFY_ID, notif)
            }
        } catch (t: Throwable) {
            Log.w("startForeground 失败（权限？）：${t.message}")
        }
        current = this
        return START_STICKY
    }

    override fun onDestroy() {
        beacon?.stop()
        beacon = null
        server?.stop()
        server = null
        current = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 供界面读取实时状态（Activity 重建后也能立刻显示「已连接 xxx」） */
    fun status(): String = server?.statusText() ?: "未启动"

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
            .setContentText(text)
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
