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
import com.allperiph.tv.core.TvInjector
import com.allperiph.tv.media.TvMediaChannel
import com.allperiph.tv.net.TcpControlServer
import com.allperiph.tv.net.TvFileReceiver
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
        current = this

        // ★★ 被控能力必须由**服务自己**初始化，绝不能依赖 Activity。
        //   「开机自启（BootReceiver）」「被系统以 START_STICKY 重启」「任务被划掉后自启」
        //   这三条路径都**不会**创建任何 Activity，而 TvInjector.init() 原先只在
        //   MainActivity.onCreate 里调用 —— 于是那些情况下 ctx==null、浮层没建、root 通道没起，
        //   表现就是 **连得上但控制不了**（光标不显示、点击/按键没反应、音量与剪贴板失效）。
        //   这正是"一出界面就不好使"的关键一环。
        TvInjector.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ★★ 一进来就进前台：startForegroundService() 之后系统**只给 5 秒**。
        //   原先的顺序是「先建控制面 + 媒体通道，最后才 startForeground」，而这两步都要 bind
        //   套接字，在电视 / 盒子上偶尔明显偏慢 → ForegroundServiceDidNotStartInTimeException
        //   → 服务被系统**直接杀掉**，表现就是"一退到后台就断连"。顺序必须反过来。
        startForegroundCompat()

        ensureControlPlane()

        // 副屏 / 音箱（9502 媒体通道）：与控制面并列，独立启停 —— 关副屏不影响键鼠。
        // 端口被占（例如同机上另一个接收端在跑）时优雅降级、仅告警。
        ensureMedia()

        // 9512 文件接收也交给服务：原先只在 MainActivity.onCreate 起，
        // 服务单独重启（开机 / 划掉任务后）时手机发文件就收不到了。
        ensureFileReceiver()

        // 定时自检：看门狗只能救「同进程内 9511 挂了」，进程被杀只能靠闹钟拉回来。
        // 没进前台时缩短到 30 秒重试一次（闹钟窗口是重新进前台的合法路径）。
        KeepAlive.schedule(this, if (fgFailed) 30_000L else 60_000L)

        startWatchdog()
        return START_STICKY
    }

    /**
     * 进前台，带**逐级降级**：API 34 的 connectedDevice 类型有前置权限要求，
     * 拿不到时退回"无类型"，再失败就只记日志（服务仍继续跑，只是后台更容易被回收）。
     */
    private fun startForegroundCompat() {
        val notif = buildNotification(server?.statusText() ?: "服务启动中")
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(
                    NOTIFY_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                fgFailed = false
                return
            } catch (t: Throwable) {
                Log.w("startForeground(connectedDevice) 失败：${t.message} → 退回无类型重试")
            }
        }
        try {
            startForeground(NOTIFY_ID, notif)
            fgFailed = false
        } catch (t: Throwable) {
            // 从后台启动前台服务在 Android 12+ 会被拒（ForegroundServiceStartNotAllowedException）。
            // 闹钟触发的应用会拿到一小段「允许启动前台服务」的窗口 —— 所以安排 30 秒后重来一次，
            // 而不是就此退化成一个随时会被杀掉的普通后台服务。
            fgFailed = true
            Log.w("startForeground 失败（${t.message}）→ 稍后经闹钟窗口重试")
        }
    }

    /** 9512 文件接收（幂等）：让它在服务活着的时候始终在监听，与界面无关 */
    private fun ensureFileReceiver() {
        runCatching { TvFileReceiver.start(applicationContext) }
            .onFailure { Log.w("9512 文件接收未启动：${it.message}") }
    }

    // —————————————————————————— 可重建的控制面 / 信标（看门狗用） ——————————————————————————

    private fun newServer(): TcpControlServer = TcpControlServer().also { s ->
        s.onPeerChanged = { connected, ip ->
            Log.i("对端变化：connected=$connected ip=$ip（记下后可供文件发送选目标）")
            if (connected && ip.isNotEmpty()) rememberPeer(ip)
            // ★ 光标浮层的显隐由**服务**直接驱动，不经过 Activity 的 listener：
            //   界面不在前台时也必须能显示 / 收起光标（原先只有 MainActivity.onPeer 会调它，
            //   所以"服务在跑但界面退出过"的情况下连光标都看不到）。
            TvInjector.setConnected(connected)
            refreshNotification()
        }
        // 对端请求开副屏（0x05）→ 真的把副屏页拉起来（原先这帧被忽略，手机点了电视没反应）
        s.onOpenScreenRequest = { openScreenPage() }
        // 谁能控制本机：由服务注入策略（名单 + "只允许名单内"开关）
        s.onAuthorizePeer = { ip -> isPeerAllowed(ip) }
    }

    /**
     * 是否允许某 IP 控制本机。
     *
     * 默认（"只允许名单内"关闭）**放行所有设备** —— 保持旧行为，升级后不会突然连不上；
     * 用户把开关打开后，只有勾选过的设备能控制，其余在 [TcpControlServer.activate] 就被拒。
     */
    private fun isPeerAllowed(ip: String): Boolean {
        if (ip.isBlank()) return true
        if (!onlyTrustedEnabled(applicationContext)) return true
        val ok = trustedPeers(applicationContext).contains(ip)
        if (!ok) Log.w("授权拒绝：$ip 不在允许名单（共 ${trustedPeers(applicationContext).size} 个）")
        return ok
    }

    /** 从服务侧拉起副屏页（服务没有 Activity 栈，必须带 NEW_TASK） */
    private fun openScreenPage() {
        runCatching {
            val i = Intent(applicationContext, com.allperiph.tv.ui.TvScreenActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }.onFailure { Log.w("拉起副屏页失败：${it.message}") }
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
                    // ★ 用**自身状态**判断，而不是「自连 127.0.0.1:9511 成功与否」：
                    //   同一台机器上可能有**另一个应用**也占着 9511（例如手机端 APK 与 TV 端 APK
                    //   装在一起），自连会成功 → 看门狗一直以为一切正常，而我们的 bind 从未成功过，
                    //   于是端口永远不会被抢回来。真机症状："服务在跑、端口也有人监听，但连不上我们"。
                    val listening = server?.isListening == true
                    if (!listening) {
                        Log.w("看门狗：9511 控制面不在监听 → 就地重建（抢回端口）")
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
        restartService()
    }

    /**
     * 从后台把前台服务拉回来。
     *
     * Android 12+ 在后台直接 `startForegroundService()` 会被拒
     * （`ForegroundServiceStartNotAllowedException`），而**闹钟触发时应用会拿到一小段
     * 「允许启动前台服务」的窗口** —— 所以被拒时改交给闹钟，那条路才真正拉得回来。
     */
    private fun restartService() {
        val ok = runCatching {
            val i = Intent(applicationContext, TvServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            true
        }.getOrElse {
            Log.w("直接从后台重启被拒：${it.message}")
            false
        }
        if (!ok) KeepAlive.schedule(applicationContext, 1_000L)
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

    /**
     * 当前真正可用的注入通道。被控端最常见的困惑是"连上了但点不动"——
     * 因为无障碍没开、root 也没授权时，服务端只剩「光标可视化」这一层。
     * 直接写在通知里，用户不用翻日志就知道该去开什么。
     */
    private fun injectStateText(): String = TvInjector.channelText()

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
                text + " · " + injectStateText() +
                        (if (fgFailed) " · 未进入前台服务，后台易被回收" else "")
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

        /** 「用户主动停止过」的标记：有此标记时**不再**自动拉起（见 [startIfNeeded]） */
        const val KEY_USER_STOPPED = "user_stopped"

        /** 被允许控制本机的对端 IP 名单 */
        private const val KEY_TRUSTED = "trusted_peers"

        /** 「只允许名单内设备控制本机」开关 */
        private const val KEY_ONLY_TRUSTED = "only_trusted"

        private fun prefsOf(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        private fun csv(c: Context, key: String): List<String> =
            prefsOf(c).getString(key, "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

        /** 已知对端（曾连入过的 IP，最近优先）—— 供"谁能控制本机"列表展示 */
        fun knownPeerList(c: Context): List<String> = csv(c, KEY_PEERS).reversed()

        /** 被允许控制本机的 IP 名单 */
        fun trustedPeers(c: Context): List<String> = csv(c, KEY_TRUSTED)

        /** 勾选 / 取消勾选某设备（立即落盘，下一次连接即生效） */
        fun setPeerTrusted(c: Context, ip: String, trusted: Boolean) {
            val set = trustedPeers(c).toMutableSet()
            if (trusted) set.add(ip) else set.remove(ip)
            prefsOf(c).edit().putString(KEY_TRUSTED, set.joinToString(",")).apply()
            Log.i("允许名单${if (trusted) "加入" else "移除"}：$ip → 共 ${set.size} 个")
        }

        /** 是否"只允许名单内设备控制本机" */
        fun onlyTrustedEnabled(c: Context): Boolean = prefsOf(c).getBoolean(KEY_ONLY_TRUSTED, false)

        fun setOnlyTrusted(c: Context, on: Boolean) {
            prefsOf(c).edit().putBoolean(KEY_ONLY_TRUSTED, on).apply()
            Log.i("只允许名单内设备控制本机：${if (on) "开" else "关"}")
        }

        /** 是否处于"用户已启用被控"状态（[BootReceiver] 与保活自检据它决定要不要拉起） */
        fun isEnabled(c: Context): Boolean =
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /** 用户是否主动停止过被控 */
        fun isUserStopped(c: Context): Boolean =
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_USER_STOPPED, false)

        /** 开启被控（含首次打开 App）：清掉"已停止"标记、记住已启用、拉起服务 */
        fun start(c: Context) {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_USER_STOPPED, false)
                .apply()
            val i = Intent(c, TvServerService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            }.onFailure { Log.w("拉起被控服务失败：${it.message}") }
        }

        /**
         * App 启动时按需拉起：**用户手动停过就不再自动开**。
         * 否则"停止被控"会变成摆设 —— 下次打开 App 又被打开。
         */
        fun startIfNeeded(c: Context) {
            if (isUserStopped(c)) return
            start(c)
        }

        /**
         * 用户主动**停止被控**。
         *
         * 原先 TV 端只有"进 App 就自动开"，**没有任何关闭入口**（手机端有「无线」总开关），
         * 于是服务 / 发现信标 / 开机自启 / 保活闹钟会永久常开 —— 用户只能去系统设置里
         * "强行停止"，还得每次开机再来一遍。
         *
         * 这里把每个出口都收干净：标记已停止（[startIfNeeded] 不再自动开、[BootReceiver] 也不开）
         * → 清 enabled → 取消保活闹钟（否则它下一秒就把服务拉回来）→ 停止服务本体
         * （onDestroy 会停控制面 / 信标 / 媒体 / 9512）。
         */
        fun stopAll(c: Context) {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, false)
                .putBoolean(KEY_USER_STOPPED, true)
                .apply()
            KeepAlive.cancel(c)
            runCatching { c.stopService(Intent(c, TvServerService::class.java)) }
                .onFailure { Log.w("停止被控服务失败：${it.message}") }
        }
    }
}
