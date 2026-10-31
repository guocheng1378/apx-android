package com.allperiph.tv.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import com.allperiph.shared.accessibility.AccessibilityAutoEnable
import com.allperiph.shared.inject.TvInjector
import com.allperiph.shared.net.ControlServer
import com.allperiph.shared.util.Log
import com.allperiph.shared.util.LogExport
import com.allperiph.tv.R
import com.allperiph.tv.TvServerService
import com.allperiph.tv.core.TvInjectorPlatform

/**
 * APX TV 主界面。
 *
 * 2026-11-01 起界面层由 Views 改为 Compose（见 [TvHome]）；本类只保留**状态与接线**：
 *  · 2s 轮询服务状态、注入通道能力、本机地址与收到的文件 → 写进 [TvHomeState]；
 *  · [TvInputDispatcher.Listener] 的对端操作反馈（离散动作，不含光标）；
 *  · 远程输入广播 → [RemoteInputOverlay]（**仍是 View 浮层**，画在 ComposeView 之上，
 *    因为它是全屏无触摸穿透的临时层，先不动）；
 *  · 三件需要系统交互的动作：能力自查（Toast）、开启无障碍（root 尝试 + 跳设置）、
 *    停止/开始接受控制（AlertDialog 二次确认）。
 *
 * 文案与视觉约定不变：用词走 strings.xml，字号走 [TvUi.Type]，不出现端口号与实现词。
 */
class MainActivity : android.app.Activity() {

    private lateinit var root: FrameLayout
    private lateinit var overlay: RemoteInputOverlay
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUI()
            handler.postDelayed(this, 2000)
        }
    }

    /** 当前弹窗：Activity 销毁时必须 dismiss，否则 WindowLeaked */
    private var dialog: AlertDialog? = null

    /** 界面状态（Compose 侧单向读取，写入只发生在主线程） */
    private val uiState = mutableStateOf(TvHomeState())

    private val remoteInputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TvServerService.ACTION_REMOTE_INPUT) {
                val from = intent.getStringExtra(TvServerService.EXTRA_SOURCE) ?: ""
                val hint = intent.getStringExtra(TvServerService.EXTRA_HINT) ?: ""
                Log.i("收到远程输入请求: from=$from hint=$hint")
                runOnUiThread { overlay.show(from, hint) }
            }
        }
    }

    /**
     * 对端操作的实时反馈：只反馈"点按/按键/打字/手柄"这类离散动作。
     * 光标位置由 [TvOverlay] 直接画（高频），不走这里。
     */
    private val inputListener = object : TvInputDispatcher.Listener {
        override fun onCursorMove(x: Float, y: Float, absolute: Boolean) = Unit
        override fun onCursorClick() = noteInput("点按")
        override fun onKey(keyCode: Int, down: Boolean) { if (down) noteInput("按键") }
        override fun onText(ch: Char) = noteInput("打字")
        override fun onGamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) = noteInput("手柄")
        override fun onPeer(connected: Boolean, peer: String) {
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                uiState.value = uiState.value.copy(
                    live = if (connected && peer.isNotBlank()) "已连入：$peer" else "",
                )
            }
        }
    }

    private var lastInputNoteMs = 0L
    private val clearLive = Runnable { uiState.value = uiState.value.copy(live = "") }

    private fun noteInput(what: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastInputNoteMs < 400) return   // 打字/移动会连发，别每帧都刷界面
        lastInputNoteMs = now
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            uiState.value = uiState.value.copy(live = "对方正在$what")
            handler.removeCallbacks(clearLive)
            handler.postDelayed(clearLive, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.bindColors(this)
        TvInjector.init(this, TvInjectorPlatform)
        TvServerService.startIfNeeded(this)
        setContentView(buildContent())

        overlay = RemoteInputOverlay(
            activity = this,
            rootLayout = root,
            onTextChanged = { text, flags -> TvServerService.current?.sendInputText(text, flags) },
            onSend = { TvServerService.current?.sendInputDone() },
            onCancel = { TvServerService.current?.sendInputDone() },
        )

        val filter = IntentFilter(TvServerService.ACTION_REMOTE_INPUT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(remoteInputReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(remoteInputReceiver, filter)
        }

        // 有 root 时自动开启无障碍（root 探测在 TvInjector.init 里异步进行，稍等再试一次）
        handler.postDelayed({
            if (!TvInjector.systemReady()) AccessibilityAutoEnable.tryEnableViaRoot(applicationContext)
        }, 2500)

        refreshUI()
        loadAsync()
    }

    override fun onBackPressed() {
        // 浮层开着时按返回 = 取消远程输入（否则对端输入态悬挂）
        if (::overlay.isInitialized && overlay.isActive()) {
            overlay.cancelFromBack()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        TvInputDispatcher.listener = inputListener
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        TvInputDispatcher.listener = null
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        try { unregisterReceiver(remoteInputReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ————————————————————————————— 界面 —————————————————————————————

    private fun buildContent(): FrameLayout {
        root = FrameLayout(this).apply { setBackgroundColor(TvUi.Pal.bg) }
        val view = ComposeView(this).apply {
            setContent {
                // 电视端固定深色（客厅远距离观看，浅色底刺眼）：Miuix 组件需要主题
                // 才能取 onSurface 等语义色，这里是唯一入口，不跟随系统明暗
                MiuixTheme(colors = darkColorScheme()) {
                    TvHome(
                        ctx = this@MainActivity,
                        state = uiState.value,
                        onFiles = { startActivity(Intent(this@MainActivity, TvFileActivity::class.java)) },
                        onScreen = { startActivity(Intent(this@MainActivity, TvScreenActivity::class.java)) },
                        onSelfCheck = { showCapabilities() },
                        onAccessibility = { onAccessibilityAction() },
                        onExportLog = { onExportLog() },
                        onToggleService = { confirmToggleService() },
                    )
                }
            }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    /**
     * 地址与文件清单都要碰系统（遍历网卡 / 读目录），电视存储偏慢，
     * 放主线程会明显拖慢启动 —— 后台取，回来只更新状态。
     */
    private fun loadAsync() {
        Thread({
            val addr = ControlServer.localIpv4()
            val files = TvFiles.listReceived(this).take(10)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                uiState.value = uiState.value.copy(addr = addr, files = files)
            }
        }, "apx-tv-ui-io").apply { isDaemon = true; start() }
    }

    private fun refreshUI() {
        val svc = TvServerService.current
        val status = svc?.status() ?: getString(R.string.tv_service_stopped)
        uiState.value = uiState.value.copy(
            status = status,
            ability = getString(R.string.tv_ability_label) + "：" + TvInjector.channelText(),
            accepting = TvServerService.isEnabled(this),
        )
    }

    // ————————————————————————————— 三个系统动作 —————————————————————————————

    private fun showCapabilities() {
        val st = TvServerService.current?.status() ?: getString(R.string.tv_service_stopped)
        Toast.makeText(
            this,
            getString(R.string.tv_ability_label) + "：" + TvInjector.channelText() + "\n" + st,
            Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * 「开启无障碍」：无障碍服务在无 root / 无 adb 时只能由用户手动勾选。
     * 先试 root 直接开启；root 未生效则跳系统设置页引导。
     */
    private fun onAccessibilityAction() {
        if (TvInjector.systemReady()) {
            Toast.makeText(this, getString(R.string.tv_a11y_already), Toast.LENGTH_SHORT).show()
            return
        }
        if (AccessibilityAutoEnable.tryEnableViaRoot(applicationContext)) {
            Toast.makeText(this, getString(R.string.tv_a11y_root_tried), Toast.LENGTH_LONG).show()
        }
        handler.postDelayed({
            if (!TvInjector.systemReady()) {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.onFailure {
                    Toast.makeText(this, getString(R.string.tv_a11y_open_failed), Toast.LENGTH_LONG).show()
                }
            }
        }, 1500)
    }

    /**
     * 导出日志（issue #52 第 3 条：「安卓/TV、win、Mac 这 3 端一起优化日志导出体验」）。
     *
     * 电视端本来没有任何日志导出入口，用户报障只能拍屏。这里与手机端走**同一份**
     * [LogExport]（同名规则 `apx-logs-<类型>@<机型>-<时间>.txt`、同一落点口径），
     * 内容取应用自己的环形缓冲（[Log.snapshot]，不读系统 logcat —— 电视上多半没 root，
     * 也不该让用户为此开 root），再补一段设备信息，够定位「电视为什么连不上」这类问题。
     *
     * 落点在电视上不好找，所以提示里把**完整路径**报出来（引导语本身也说了去哪拿）。
     */
    private fun onExportLog() {
        Toast.makeText(this, getString(R.string.tv_log_collecting), Toast.LENGTH_SHORT).show()
        Thread({
            val text = Log.snapshot().joinToString("\n") { e ->
                "${LogExport.timestamp()} ${e.level} ${e.tag}: ${e.msg}"
            }
            val msg = try {
                if (text.isBlank()) {
                    getString(R.string.tv_log_empty)
                } else {
                    val saved = LogExport.saveToReceivedDir(
                        applicationContext,
                        LogExport.fileName("tvlog"),
                        // 文件头走字符串资源（与手机端同一份文案结构：时间 / 设备 / 系统版本）
                        getString(
                            R.string.tv_log_head, LogExport.timestamp(),
                            Build.MODEL, Build.VERSION.SDK_INT.toString(),
                        ) + text,
                    )
                    getString(R.string.tv_log_exported, saved)
                }
            } catch (e: Throwable) {
                getString(R.string.tv_log_failed, e.message ?: "")
            }
            runOnUiThread {
                if (!isFinishing) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }, "apx-tv-log-export").apply { isDaemon = true; start() }
    }

    /** 「停止接受控制」会立刻断开所有控制端，先确认再执行 */
    private fun confirmToggleService() {
        val enabling = !TvServerService.isEnabled(this)
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(if (enabling) R.string.tv_confirm_start_title else R.string.tv_confirm_stop_title))
            .setMessage(getString(if (enabling) R.string.tv_confirm_start_body else R.string.tv_confirm_stop_body))
            .setPositiveButton(getString(if (enabling) R.string.tv_confirm_start_ok else R.string.tv_confirm_stop_ok)) { _, _ ->
                if (enabling) TvServerService.start(this) else TvServerService.stopAll(this)
                refreshUI()
            }
            .setNegativeButton(R.string.tv_confirm_cancel, null)
            .show()
    }
}
