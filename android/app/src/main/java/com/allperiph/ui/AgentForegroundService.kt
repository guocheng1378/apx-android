package com.allperiph.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.allperiph.bt.BtHidDevice
import com.allperiph.core.AgentStateEvent
import com.allperiph.core.EventBus
import com.allperiph.core.GadgetStateEvent
import com.allperiph.core.LinkSpeed
import com.allperiph.core.Log
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.core.ScreenOpenRequestEvent
import com.allperiph.core.TcpCtrlBridge
import com.allperiph.R

/**
 * 【M6】常驻前台服务（架构 §1 Hardware Agent 的宿主）。
 *
 * 本服务是 **Manifest 中唯一声明**的前台服务，负责「编排 + 通知 + 进程兜底」；
 * 业务逻辑全在各自模块里。（原 `core/AgentService` 已按 main 裁决废弃：
 * UDC/Gadget 只能有一个所有者，重复的前台服务会互相抢占 USB 配置。）
 *
 * 要点：
 * - `foregroundServiceType=connectedDevice`（Manifest 与此处必须一致，Android 14 强校验）；
 * - 缺通知权限时 `startForeground` 会抛异常，此处吞掉并继续运行（代理逻辑不依赖通知）；
 * - 进程被杀时靠 shutdown hook 逆序停止模块，尽力恢复 USB 配置（架构 §6.2）。
 */
class AgentForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val disposables = ArrayList<EventBus.Disposable>()
    private var shutdownHook: Thread? = null

    @Volatile
    private var lastLink: LinkSpeed = LinkSpeed.UNKNOWN

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        NotificationChannels.ensure(this)
        AgentController.build(this)
        subscribeEvents()
        installShutdownHook()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AgentController.stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REPROBE -> {
                AgentController.refreshEnv(this) { updateNotification() }
                return START_STICKY
            }
        }
        startForegroundGuarded()
        AgentController.startEnabled(this)
        // 自检涉及 su（可能耗时数百毫秒），必须异步
        AgentController.refreshEnv(this) { updateNotification() }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        for (d in disposables) d.dispose()
        disposables.clear()
        AgentController.stopAll()
        shutdownHook?.let {
            runCatching { Runtime.getRuntime().removeShutdownHook(it) }
            shutdownHook = null
        }
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ————————————————————————— 通知 —————————————————————————

    private fun startForegroundGuarded() {
        val n = buildNotification()
        try {
            startForeground(
                NOTIFY_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (t: Throwable) {
            // 常见原因：未授予 POST_NOTIFICATIONS。代理逻辑仍可运行，不崩溃。
            Log.w(TAG, "startForeground 失败：${t.message}")
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!EnvChecks.notificationGranted(this)) return
        runCatching { nm.notify(NOTIFY_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "更新通知失败：${it.message}") }
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 副屏一键直达：PC 开副屏时若 app 在后台被系统拦了弹页，通知这个动作兜底
        val screenPi = PendingIntent.getActivity(
            this, 3,
            Intent(this, com.allperiph.screen.ScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 链路口径必须与真实的输入择路一致（优先级见 TouchpadModule.dispatch）：
        // USB HID → 蓝牙 HID → Wi‑Fi 控制。原文案只有「USB / 蓝牙」两档，于是无蓝牙
        // 适配器的 PC 走 Wi‑Fi 时会显示「蓝牙 HID」——那是一台压根没有蓝牙的机器。
        val rt = AgentController.runtime
        // 判据必须和 TouchpadModule.dispatch 完全一致：蓝牙看 isConnected 而非 state。
        // 模块「已注册（待 PC 配对）」时 state 也是 RUNNING，只看 state 会把一条
        // 根本没人连的蓝牙链路报成当前通道。
        val btConnected =
            (AgentController.module(ModuleId.BTHID) as? BtHidDevice)?.isConnected == true
        val linkText = when {
            rt != null && rt.hid.isReady() ->
                if (lastLink == LinkSpeed.UNKNOWN) "USB 链路" else "USB ${lastLink.label}"
            btConnected -> "蓝牙 HID"
            TcpCtrlBridge.ready() -> "Wi‑Fi 控制"
            else -> "未连接"
        }
        val text = getString(R.string.notify_text_running, linkText)
        return Notification.Builder(this, NotificationChannels.CHANNEL_STATUS)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_peripheral,
                    getString(R.string.notify_action_open),
                    openPi,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_peripheral,
                    getString(R.string.notify_action_screen),
                    screenPi,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_stat_peripheral,
                    getString(R.string.notify_action_stop),
                    stopPi,
                ).build()
            )
            .build()
    }

    /** PC 请求打开副屏（面板开了副屏推流）：能弹就直接弹，被拦则靠通知「副屏」动作兜底 */
    private fun onPcOpenScreen() {
        runCatching {
            startActivity(
                Intent(this, com.allperiph.screen.ScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.w(TAG, "后台打开副屏被系统拦截：${it.message}（通知「副屏」按钮可用）")
        }
        updateNotification()
    }

    private fun subscribeEvents() {
        disposables += EventBus.on<GadgetStateEvent>(mainHandler) {
            lastLink = it.linkSpeed
            updateNotification()
        }
        disposables += EventBus.on<AgentStateEvent>(mainHandler) {
            lastLink = it.linkSpeed
            updateNotification()
        }
        disposables += EventBus.on<ScreenOpenRequestEvent>(mainHandler) { onPcOpenScreen() }
        // v1.12：ScreenStatusEvent 订阅随副屏功能移除
    }

    private fun installShutdownHook() {
        val hook = Thread({
            Log.w(TAG, "shutdown hook：逆序停止模块，尽量恢复 USB 配置")
            AgentController.stopAll()
        }, "apx-ui-shutdown-hook")
        shutdownHook = hook
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
            .onFailure { Log.w(TAG, "addShutdownHook 失败：${it.message}") }
    }

    companion object {
        private const val TAG = "AgentForegroundService"
        private const val NOTIFY_ID = 0x9A2

        const val ACTION_START = "com.allperiph.action.START"
        const val ACTION_STOP = "com.allperiph.action.STOP"
        const val ACTION_REPROBE = "com.allperiph.action.REPROBE"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            // 乐观置 true：startForegroundService 是异步的，服务 onCreate 前若 refresh
            // 读到 running=false 会把 swMaster 回弹成关，进而发 ACTION_STOP 把刚启动的
            // 服务又停掉（启动竞态，真机复现：服务反复 onCreate/onDestroy、UI 一直显示
            // 「未启动」）。这里同步置 true，失败再回滚。
            running = true
            val i = Intent(context, AgentForegroundService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(i) }
                .onFailure {
                    running = false
                    Log.e(TAG, "启动前台服务失败", it)
                }
        }

        fun stop(context: Context) {
            // 同样乐观置 false，避免 stop 到 onDestroy 之间的 refresh 误判「仍在运行」。
            running = false
            context.startService(
                Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
