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
import com.allperiph.core.Log
import com.allperiph.touchpad.TouchpadActivity

/**
 * 被控模式前台服务：持有 [TvControlServer] + [TvFileReceiver] + 发现信标，使本机在退到后台后仍可被对方手机连入控制。
 * 同时注册剪贴板监听，实现反向剪贴板（本机复制 → 回传对方手机）。
 */
class ControlledService : Service() {

    private var server: TvControlServer? = null
    private var beacon: WirelessBeacon? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
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
        // ★★ 一进来就进前台：startForegroundService() 之后系统**只给 5 秒**。
        //   原先是"先建控制面 + 文件接收 + 信标，最后才 startForeground"，而这几步都要 bind
        //   套接字、建浮层，慢一点就 ForegroundServiceDidNotStartInTimeException
        //   → 服务被系统**直接杀掉** = "一退到后台就断连"。顺序必须反过来。
        startForegroundGuarded()

        TvInjector.init(this)
        if (server == null) server = TvControlServer()
        server?.onClipboardChange = { text -> server?.sendReverseClipboard(text) }
        if (server?.start() != true) Log.e(TAG, "被控控制面启动失败")
        TvFileReceiver.start(applicationContext)
        // 信标前缀保持 APX1TV —— 换前缀就得两端同时升级（旧 PC 端只认 APX1TV，换了它会
        // 发现不到本机，副屏/音箱/麦克风一起废）。改成在**名字**里带「手机被控」标记，
        // 对面手机据此显示「(手机)」且不切 TV 专属快捷键布局；PC/TV 端不看名字，无需改动。
        val devName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        beacon = WirelessBeacon(
            "APX1TV",
            com.allperiph.wireless.TvDiscovery.PHONE_NAME_MARK + devName,
            TvControlServer.PORT,
            "",
            // 单播兜底：对「正在控的对端」+「已发现的对端」各再发一份，
            // 广播被 AP 丢掉时仍有救（不依赖用户是否手动连过）
            unicastHosts = {
                val hosts = ArrayList<String>(8)
                com.allperiph.wireless.ControlTarget.host.takeIf { it.isNotBlank() }?.let { hosts.add(it) }
                com.allperiph.wireless.TvDiscovery.list().forEach { hosts.add(it.ip) }
                hosts.distinct()
            },
        ).also { it.start() }

        // 注册剪贴板监听（反向剪贴板）
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.addPrimaryClipChangedListener(clipListener)

        // 进程级自检：看门狗只能救"同进程内 9511 挂了"，进程被杀只能靠闹钟拉回来
        KeepAlive.schedule(this)

        // 主动上报一次当前剪贴板（被控 App 在前台时本机可读到自己的剪贴板）
        val init = currentClipboardText()
        if (!init.isNullOrEmpty()) server?.sendReverseClipboard(init)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { cm?.removePrimaryClipChangedListener(clipListener) }
        server?.stop()
        server = null
        beacon?.stop()
        beacon = null
        TvFileReceiver.stop()
        TvInjector.onDestroy()
        super.onDestroy()
    }

    /**
     * 用户从「最近任务」划掉 App：MIUI / HyperOS 会**连同进程一起杀**，被控端随即消失
     * （真机症状："一退出软件，对端就断开了"）。系统在杀进程前会回调这里，
     * 于是立刻把自己重新拉起来 —— 配合 START_STICKY，绝大多数情况能续上。
     * Android 12+ 可能以 BackgroundServiceStartNotAllowedException 拒绝，那也照记日志不崩。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "任务被移除 → 立即重启被控服务")
        // Android 12+ 在后台直接 startForegroundService 会被拒
        // （ForegroundServiceStartNotAllowedException）—— 原先只吞掉打日志，于是划掉任务就再也回不来。
        // 被拒时改交给闹钟：闹钟触发时应用会拿到一小段"允许启动前台服务"的窗口。
        val ok = runCatching {
            val i = Intent(applicationContext, ControlledService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            true
        }.getOrElse {
            Log.w(TAG, "直接从后台重启被拒：${it.message}")
            false
        }
        if (!ok) KeepAlive.schedule(applicationContext, 1_000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return try {
            cm?.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    private fun startForegroundGuarded() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFY_ID, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground 失败（权限？）：${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, TouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ready = server?.ready == true
        val sysReady = TvInjector.systemReady()
        // 缺通知权限 → startForeground 会失败 → 服务其实不是前台服务 → 退后台很快被系统回收。
        // 以前只在日志里写一行，用户完全看不到；现在直接写进通知正文。
        val noNotif = Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val text = (if (!ready) "等待手机连入 :${TvControlServer.PORT}"
        else if (sysReady) "已连接 · 系统注入已启用"
        else "已连接 · 未启用无障碍（只能看见光标，点不动）") +
                (if (noNotif) " · 未授予通知权限，后台易被回收" else "")
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("全能外设 · 被控模式")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_app)
            .setContentIntent(openPi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ControlledService"
        private const val CHANNEL_ID = "controlled_status"
        private const val NOTIFY_ID = 0x9A3

        /** 用户是否**主动开过**被控。持久化：闹钟自检可能在新进程里跑，读不到内存变量 */
        private const val PREF = "apx_controlled"
        private const val KEY_ENABLED = "enabled"

        @Volatile
        var running: Boolean = false
            private set

        /** 是否处于"用户已启用"状态（只有 [start]/[stop] 会改它） */
        fun enabled(c: Context): Boolean =
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun start(c: Context) {
            running = true
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true).apply()
            val i = Intent(c, ControlledService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            }.onFailure {
                running = false
                Log.e(TAG, "启动被控服务失败", it)
            }
        }

        fun stop(c: Context) {
            running = false
            // 用户主动关掉 → 清标记，闹钟自检不再把它拉回来（不替用户做决定）
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, false).apply()
            runCatching { c.stopService(Intent(c, ControlledService::class.java)) }
        }

        fun isRunning() = running
    }
}
