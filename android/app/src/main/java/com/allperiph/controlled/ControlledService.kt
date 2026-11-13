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
import com.allperiph.core.CameraPushRequestEvent
import com.allperiph.core.ScreenMirrorRequestEvent
import com.allperiph.core.ScreenOpenRequestEvent
import com.allperiph.shared.accessibility.ApxAccessibilityService
import com.allperiph.shared.event.EventBus
import com.allperiph.shared.net.ControlServer
import com.allperiph.shared.net.ControlEndpoint
import com.allperiph.shared.net.FileReceiver
import com.allperiph.shared.net.WirelessBeacon
import com.allperiph.shared.util.Log
import com.allperiph.shared.inject.KeepAlive
import com.allperiph.shared.inject.ServiceController
import com.allperiph.shared.inject.TvInjector
import com.allperiph.touchpad.TouchpadActivity

class ControlledService : Service() {
    private var server: ControlServer? = null
    private var beacon: WirelessBeacon? = null

    /** 控制面端点：把 shared ControlServer 的输入注入回调到本端 TvInjector + TvInputDispatcher */
    private val controlEndpoint = object : ControlEndpoint {
        override fun onPeerStateChanged(connected: Boolean, peerText: String) {
            TvInputDispatcher.peer(connected, peerText); TvInjector.setConnected(connected)
            // 输入法会话期换装（ImeSwitcher 类注释）：连上→切通道输入法，断开→还原。
            // 没被控机自用键盘也不受影响；没 root 自动跳过（维持手动切的老口径）。
            if (connected) {
                ImeSwitcher.activate(applicationContext)
                // 无障碍掉线即时修：对端刚连入时服务若已被 ROM 杀掉，就地 root 重绑（见
                // AccessibilityAutoEnable.ensureAlive）—— 不等 60s 保活闹钟，第一下点击/打字就可用
                if (!com.allperiph.shared.accessibility.AccessibilityAutoEnable.isActive()) {
                    com.allperiph.shared.accessibility.AccessibilityAutoEnable.ensureAlive(applicationContext)
                }
            } else ImeSwitcher.restore(applicationContext)
        }
        override fun cursorMove(x: Float, y: Float, absolute: Boolean) {
            TvInputDispatcher.cursorMove(x, y, absolute); TvInjector.cursorMove(x, y, absolute)
        }
        override fun cursorClick() { TvInputDispatcher.cursorClick() }
        override fun key(keyCode: Int, down: Boolean) {
            TvInputDispatcher.key(keyCode, down); TvInjector.key(keyCode, down)
        }
        override fun text(ch: Char) { TvInputDispatcher.text(ch); TvInjector.text(ch) }
        override fun pressDown() {
            // ④（双弹）：盖“远端刚按下”时间戳 —— 无障碍焦点事件在 800ms 窗口内
            // 会判定为远端点击造成的获焦，自动收起本机输入法（见 ApxAccessibilityService）。
            ApxAccessibilityService.lastRemotePressAtMs = android.os.SystemClock.uptimeMillis()
            TvInjector.pressDown()
        }
        override fun pressUp() {
            ApxAccessibilityService.lastRemotePressAtMs = android.os.SystemClock.uptimeMillis()
            TvInjector.pressUp()
        }
        override fun scroll(wheel: Int) = TvInjector.scroll(wheel)
        override fun touchDown(x: Float, y: Float) = TvInjector.touchDown(x, y)
        override fun touchUp(x: Float, y: Float) = TvInjector.touchUp(x, y)
        override fun consumer(bitmap: Int) = TvInjector.consumer(bitmap)
        override fun powerAction(action: Int) { TvInjector.powerAction(action) }
        override fun clipboard(text: String) {
            TvInjector.clipboard(text)
            // 记下来源（2026-11-14「粘贴后无限循环」）：这段内容是落本机剪贴板的，
            // 本机任何剪贴板监听都不能再当「用户复制了」回传 —— 防来回弹
            com.allperiph.ui.ClipboardSync.markRemoteWritten(text)
            // 收到对端 0x20：此前静默写入剪贴板，被控手机零反馈 —— 发送方无从知道
            // 到底到没到（用户报障「剪贴板发送后，被控手机没有提示」）。这里补一条
            // 带内容预览的 Toast，顺带充当「到达已确认」。
            // 回调跑在 apx-inject 线程（ControlServer 的 actions 队列），Toast 必须切主线程。
            if (text.isEmpty()) return
            val oneLine = text.replace('\n', ' ').replace('\r', ' ')
            val preview = oneLine.take(20)
            val more = if (oneLine.length > 20) "…" else ""
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    applicationContext,
                    "已收到剪贴板：$preview$more",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        override fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) {
            TvInputDispatcher.onGamepad(buttons, x, y, rx, ry); TvInjector.gamepad(buttons, x, y, rx, ry)
        }
        override fun lockScreen() { ApxAccessibilityService.instance?.lockScreen() }
    }
    /** v184：剪贴板监听是否已注册 —— onStartCommand 可能被反复调用，重复挂会多次回传 */
    private var clipListenerRegistered = false
    /** 内容去重（2026-11-14 真机「粘贴后无限循环」）：HyperOS 监听回调可能迟到超过 600ms
     *  抑制窗，纯时间窗防不住 A↔B 来回弹；与上次回传过的内容相同直接掐断 */
    private var lastEchoedClipText: String? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // v184：程序性写入抑制 —— 收到 PC 的 0x20 后 TvInjector.clipboard 会写剪贴板，
        // 这里若照常回传 0x21，PC 端 watcher 又写又回传，剪贴板内容来回乱跳。
        // 程序性写入后 600ms 内的变化静默吸收（真实用户复制不受影响）。
        if (android.os.SystemClock.uptimeMillis() - TvInjector.lastProgrammaticClipWriteMs < 600) return@OnPrimaryClipChangedListener
        val text = currentClipboardText()
        if (text.isNullOrEmpty() || text == lastEchoedClipText) return@OnPrimaryClipChangedListener
        lastEchoedClipText = text
        server?.sendReverseClipboard(text)
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
        TvInjector.init(this, PhoneInjectorPlatform)
        // 互传服务兜底启动（2026-11-14 用户「互传还是不能用」）：被控机的接收服务同样
        // 只由设置里的开关拉起 —— 开关没开时对端永远发现不了、连不上。被控服务在跑就
        // 兜底拉起（只补运行态、不动开关记录值），与发送页 v95 的兜底同口径。
        runCatching { if (!com.allperiph.localsend.LocalSendService.isRunning) com.allperiph.localsend.LocalSendService.start(this) }
        if (server == null) server = ControlServer(endpoint = controlEndpoint)
        Companion.server = server
        server?.onClipboardChange = { text -> server?.sendReverseClipboard(text) }
        // 0x05 开副屏请求：PC 面板开启副屏推流时下发 → 广播 ScreenOpenRequestEvent，
        // 由 AgentForegroundService.onPcOpenScreen 拉起副屏页（前台直接弹；
        // 后台被 Android 拦截时走通知「副屏」按钮兜底）。此前手机端不设该回调，
        // ControlServer 只打日志忽略 —— PC 推流后手机停在主界面毫无反应，
        // 帧全部进了 Renderer.droppedNoSurface。
        server?.onOpenScreenRequest = { EventBus.post(ScreenOpenRequestEvent()) }
        // 0x38 查找手机（2026-11-11，能力位 bit2）：PC 托盘点「查找手机」→ 本机响铃。
        // 实现落 FindPhoneRinger（STREAM_ALARM 最大音量 + 亮屏，30s 自动停）；
        // 回调已在主线程（ControlServer 侧 mainHandler.post），Ringer 内部再 post 一次也无害。
        server?.onFindPhone = { action -> com.allperiph.findphone.FindPhoneRinger.handle(action) }
        // 0x44/0x46 手机文件（2026-11-11，能力位 bit3）：电脑浏览本机目录并取走文件。
        // 实现落 PhoneFiles；回调本身在 actions 队列线程，PhoneFiles.handle 内部已甩专用线程。
        server?.onPhoneFileCmd = { cmd, body, reply, peer ->
            com.allperiph.files.PhoneFiles.handle(cmd, body, reply, peer)
        }
        // 0x48/0x4A/0x4B 相册投影（2026-11-12，能力位 bit4）：电脑分页看本机相册、
        // 监听新照片、按需取缩略图。实现落 PhoneAlbum；回调线程模型同 PhoneFiles，
        // handle 内部已甩专用线程，这里不做额外调度。
        server?.onAlbumCmd = { cmd, body, reply, peer ->
            com.allperiph.files.PhoneAlbum.handle(cmd, body, reply, peer)
        }
        // 0x41 通知回复（2026-11-14，能力位 bit1）：PC 气泡点回复 → 找回缓存 action 提交
        // RemoteInput。实现落 NotificationReply；handle 内部必回 0x43 终态（err 见回执常量）。
        // 回调线程模型同 PhoneFiles：actions 队列线程，RemoteInput 提交无主线程要求。
        server?.onNotifyReplyCmd = { cmd, body, reply ->
            com.allperiph.notify.NotificationReply.handle(cmd, body, reply)
        }
        // 0x33 能力位 bit1（通知回复）上报口径：通知使用权已开启才报 —— 回复能力依赖
        // 监听器的 action 缓存，使用权关着缓存必然是空的，报了也是空承诺。
        // sendCapsSync 在连接激活时调用，读偏好（SharedPreferences）线程安全。
        val service = this
        server?.notifyReplyCap = { com.allperiph.notify.NotificationSyncPref.isEnabled(service) }
        // 0x10 模块开关（v184）：PC 面板拨"无线"开关 → 挂起/恢复被控（监听保留，可随时恢复）
        server?.onModuleToggle = { id, on ->
            if (id == "wireless") {
                if (on) server?.resumeAccept() else server?.suspendAccept()
            } else if (id == "camera") {
                // M7-c 批4：面板 camera.toggle → 开/关推流页。开由 AgentForegroundService
                // 执行 startActivity（后台被拦时通知按钮兜底），关由推流页订阅同一事件
                // 自行 finish。回调已在主线程（ControlServer 侧 mainHandler.post），发事件安全。
                EventBus.post(CameraPushRequestEvent(on))
            } else if (id == "screenmirror") {
                // 投屏到电脑：开 → 拉起授权页；关 → 采集服务订阅同一事件自行停止。
                EventBus.post(ScreenMirrorRequestEvent(on))
            }
        }
        // v223.33（两台设备打架）：另一台设备要接管会话 —— 不再静默顶掉，
        // 拉起确认弹窗要用户点「确定」才切换（拉起失败/后台被拦 → 拒绝新设备，
        // ControlServer 侧还有 15s 未裁决默认拒绝的双保险，当前会话不会被误踢）。
        server?.onConcurrentPeer = { newPeer, curPeer, decide ->
            pendingTakeoverDecide = decide
            runCatching {
                val i = Intent(this, TakeoverDialogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(TakeoverDialogActivity.EXTRA_NEW_PEER, newPeer)
                    putExtra(TakeoverDialogActivity.EXTRA_CUR_PEER, curPeer)
                }
                startActivity(i)
            }.onFailure {
                Log.w(TAG, "接管确认弹窗启动失败（${it.message}），拒绝新设备 $newPeer")
                pendingTakeoverDecide = null
                runCatching { decide(false) }
            }
        }
        // 注册远程输入回调：收到 REQUEST_INPUT 时弹出 RemoteInputActivity
        server?.onRemoteInputRequest = { fromDevice, hint ->
            Log.i("ControlledService", "远程输入请求: from=$fromDevice, hint=$hint")
            RemoteInputActivity.start(this, fromDevice, hint)
        }
        // 「让对端帮我输入」的**发起端**：本机输入框获焦 → 请对端弹输入法。
        // 与 TV 端同一处断链：onFocusDetected 此前零注册，且 0x25 有收无发。
        com.allperiph.shared.accessibility.ApxAccessibilityService.onFocusDetected = { hint ->
            val s = server
            if (awaitingRemoteInput) {
                Log.i("ControlledService", "上一轮远程输入还没回传 —— 不重复请求")
            } else if (s != null && s.ready) {
                Log.i("ControlledService", "输入框获焦 → 请求对端输入: hint=$hint")
                awaitingRemoteInput = true
                s.sendRequestInput(hint)
            }
        }
        server?.onRemoteInputText = { text, flags -> injectRemoteText(text, flags) }
        server?.onRemoteInputDone = {
            awaitingRemoteInput = false
            injectedLen = 0   // ⑤：上一句对账定格（与 PC 端 g_injectedLen 同款语义）
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

    /** 已发出 0x25、还在等对端回传 —— 防「注入文本 → 输入框再获焦 → 又发一次请求」的回环 */
    @Volatile private var awaitingRemoteInput = false

    /**
     * ⑤（输入出两次）：本端**已注入**的码点数镜像 —— 与 PC 端 ControlServer 的
     * g_injectedLen 同款语义。增量帧累加；0x04 COMMIT 按镜像退格清场再整段写；
     * DONE/CANCEL 归零。回调全部经 ControlServer 的 mainHandler.post 串行投递到主线程，
     * 单线程读写无需锁。
     */
    @Volatile private var injectedLen = 0

    /**
     * 把对端敲的字落到本机当前聚焦的输入框（手机**请对端帮我输入**时的回传落地）。
     * 此前这里是空实现（注释写着"由 RemoteInputActivity 发送，这里不重复"）—— 那是
     * 手机**帮别人**输入的方向；本机**请别人**输入时收到的 0x26 必须注入，否则等于白喊。
     *
     * 语义按 [com.allperiph.shared.proto.ApxFrame.INPUT_FLAG_*]：增量=追加、退格=删一字、
     * 0x04=整段替换（镜像对账，否则增量+COMMIT 两路叠加 → 全文出现两遍）、
     * 0x08=作废归零。
     */
    private fun injectRemoteText(text: String, flags: Int) {
        awaitingRemoteInput = false          // 字回来了，本轮请求结束
        val svc = com.allperiph.shared.accessibility.ApxAccessibilityService.instance
        if (svc == null) { Log.w(TAG, "远程输入没落地：无障碍服务未就绪"); return }
        when {
            flags and com.allperiph.shared.proto.ApxFrame.INPUT_FLAG_BACKSPACE != 0 -> {
                if (svc.deleteChar()) injectedLen = (injectedLen - 1).coerceAtLeast(0)
            }
            flags and com.allperiph.shared.proto.ApxFrame.INPUT_FLAG_CANCEL != 0 -> injectedLen = 0
            flags and com.allperiph.shared.proto.ApxFrame.INPUT_FLAG_COMMIT != 0 -> {
                // 0x04 = 整段替换：先按镜像退格清掉「我们注入过的」，再写整段。
                // 旧实现把 COMMIT 当普通追加 —— 增量帧早已把同一段字送过来，
                // typeText 又是 cur+text 整段拼 → 全文两遍（用户报的「输入会出两次」）。
                while (injectedLen > 0 && svc.deleteChar()) injectedLen--
                injectedLen = 0
                if (text.isNotEmpty()) {
                    if (svc.typeText(text) || svc.paste(text)) {
                        injectedLen = text.codePointCount(0, text.length)
                    } else Log.w(TAG, "远程输入 COMMIT 没落地（输入框没聚焦？）")
                }
            }
            else -> {
                if (text.isNotEmpty()) {
                    // 增量：只把真正注入成功的部分记进镜像
                    if (svc.typeText(text) || svc.paste(text)) {
                        injectedLen += text.codePointCount(0, text.length)
                    } else Log.w(TAG, "远程输入注入失败（输入框没聚焦？）")
                }
            }
        }
    }

    override fun onDestroy() {
        running = false; Companion.server = null
        // 服务停了必还原输入法：远程会话中途被杀也不能把用户的键盘留在「无声通道」上
        ImeSwitcher.restore(applicationContext)
        // 待裁决的接入选择随服务销毁作废：先回调拒绝（释放挂起的握手线程），再清引用
        pendingTakeoverDecide?.let { runCatching { it(false) } }
        pendingTakeoverDecide = null
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        runCatching { cm?.removePrimaryClipChangedListener(clipListener) }
        clipListenerRegistered = false   // v184：服务重建时重新注册
        server?.stop(); server = null; beacon?.stop(); beacon = null
        // 焦点检测回调是静态的且闭包捕获了本 Service，停机不解绑会挂到下次启动
        com.allperiph.shared.accessibility.ApxAccessibilityService.onFocusDetected = null
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
    companion object : ServiceController {
        private const val TAG = "ControlledService"
        private const val CHANNEL_ID = "controlled_status"
        private const val NOTIFY_ID = 0x9A3
        private const val PREF = "apx_controlled"
        private const val KEY_ENABLED = "enabled"
        @Volatile var running: Boolean = false; private set
        @Volatile var server: ControlServer? = null; internal set
        /**
         * 待消费的多设备接入裁决（v223.33）：ControlServer 回调 → 拉起 TakeoverDialogActivity
         * → 用户点确定/取消后由该页消费清空。服务销毁时兜底按拒绝处理。
         */
        @Volatile var pendingTakeoverDecide: ((Boolean) -> Unit)? = null
        override fun enabled(c: Context): Boolean = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        override fun start(c: Context) {
            running = true; c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
            val i = Intent(c, ControlledService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }.onFailure { running = false; Log.e(TAG, "启动被控服务失败", it) }
        }
        fun stop(c: Context) {
            running = false; c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            runCatching { c.stopService(Intent(c, ControlledService::class.java)) }
            // v196：必须一并撤掉保活闹钟 —— KeepAlive.onAlarm 结尾会无条件再排下一次，
            // 只停服务不撤闹钟的话，用户关了被控模式，系统仍每分钟唤起一次本进程
            // （Application.onCreate 照跑），耗电且停不下来。TV 端 TvServerService.stopAll() 早有同样处理。
            runCatching { com.allperiph.shared.inject.KeepAlive.cancel(c) }
        }
        override fun isRunning(): Boolean = running
    }
}