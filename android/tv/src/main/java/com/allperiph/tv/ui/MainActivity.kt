package com.allperiph.tv.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.allperiph.tv.R
import com.allperiph.tv.TvServerService
import com.allperiph.shared.accessibility.AccessibilityAutoEnable
import com.allperiph.shared.util.Log
import com.allperiph.shared.inject.TvInjector
import com.allperiph.tv.core.TvInjectorPlatform
import com.allperiph.shared.net.ControlServer

/**
 * APX TV 主界面（纯代码构建）。
 *
 * 这一页只服务一件事：**让用户知道这台电视现在能不能被手机 / 电脑控制、不能的话怎么办**。
 * 所以结构是「一句话说明产品 → 当前状态 → 本机地址与连法 → 四个动作 → 收到的文件」，
 * 不再有与「被手机控制」无关的内容。
 *
 * 文案与视觉约定（三端统一）：
 *  · 用词走 res/values/strings.xml；不出现端口号、服务名、注入通道等实现词；
 *  · 字号走 [TvUi.Type]、圆角走 [TvUi.Radius]，不在构造 View 时写魔法数字；
 *  · 状态句一律「现在什么情况 + 该怎么办」。
 */
class MainActivity : android.app.Activity() {
    private lateinit var root: FrameLayout
    private lateinit var overlay: RemoteInputOverlay
    private lateinit var statusTv: TextView
    private lateinit var injectTv: TextView
    /** 对端正在操作时的实时提示（由 TvInputDispatcher 驱动） */
    private lateinit var liveTv: TextView
    private lateinit var toggleService: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() { refreshUI(); handler.postDelayed(this, 2000) }
    }
    /** 当前弹窗：Activity 销毁时必须 dismiss，否则 WindowLeaked */
    private var dialog: android.app.AlertDialog? = null

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

    private val pad get() = TvUi.safeInset(this)
    private val gap get() = TvUi.dp(this, 10f)

    /**
     * 对端操作的实时反馈。
     *
     * 服务在每一个输入帧上都调 `TvInputDispatcher.xxx()`，但**全仓库没有任何一处给
     * [TvInputDispatcher.listener] 赋值** —— 那整条链路一直是空转的死代码。这里把它接上：
     * 光标位置由 TvOverlay 直接画出来（且是高频），所以只反馈"点按/按键/打字/手柄"这类
     * 离散动作，让用户知道手机确实在控制这台电视。
     *
     * ⚠️ 回调来自 apx-inject 后台线程（`ControlServer` 的派发线程），操作 View 必须先切回主线程。
     */
    private val inputListener = object : TvInputDispatcher.Listener {
        override fun onCursorMove(x: Float, y: Float, absolute: Boolean) {
            // 每秒几十次：交给光标浮层画，这里不刷新界面
        }
        override fun onCursorClick() { noteInput("点按") }
        override fun onKey(keyCode: Int, down: Boolean) { if (down) noteInput("按键") }
        override fun onText(ch: Char) { noteInput("打字") }
        override fun onGamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) { noteInput("手柄") }
        override fun onPeer(connected: Boolean, peer: String) {
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                liveTv.text = if (connected && peer.isNotBlank()) "已连入：$peer" else ""
            }
        }
    }

    private var lastInputNoteMs = 0L
    private val clearLive = Runnable { if (::liveTv.isInitialized) liveTv.text = "" }

    private fun noteInput(what: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastInputNoteMs < 400) return   // 打字/移动会连发，别每帧都刷界面
        lastInputNoteMs = now
        runOnUiThread {
            if (isFinishing || !::liveTv.isInitialized) return@runOnUiThread
            liveTv.text = "对方正在$what"
            handler.removeCallbacks(clearLive)
            handler.postDelayed(clearLive, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.bindColors(this)
        // 注入通道初始化幂等；被控服务由用户开关控制（见 toggleService）
        TvInjector.init(this, TvInjectorPlatform)
        // 电视视为常驻家电：打开过 App 一次即自动开始接受控制（点过「停止接受控制」则不强推，
        // 由 startIfNeeded 内部判断）。这也补上了此前从未被调用的 startIfNeeded —— 否则
        // 用户不点按钮就永远不会写入 enabled，开机自启的判定条件永远为 false。
        TvServerService.startIfNeeded(this)
        setContentView(buildUi())

        overlay = RemoteInputOverlay(
            activity = this,
            rootLayout = root,
            onTextChanged = { text, flags -> TvServerService.current?.sendInputText(text, flags) },
            onSend = { TvServerService.current?.sendInputDone() },
            onCancel = { TvServerService.current?.sendInputDone() }
        )

        val filter = IntentFilter(TvServerService.ACTION_REMOTE_INPUT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(remoteInputReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(remoteInputReceiver, filter)

        // 有 root 时自动开启无障碍（root 探测在 TvInjector.init 里异步进行，稍等再试一次）
        handler.postDelayed({
            if (!TvInjector.systemReady()) AccessibilityAutoEnable.tryEnableViaRoot(applicationContext)
        }, 2500)
    }

    override fun onBackPressed() {
        // 浮层开着时按返回 = 取消远程输入。以前没接管：电视上按返回会直接 finish 掉本页，
        // 浮层被连带销毁，onCancel（服务端的 sendInputDone）永远发不出去 —— 对端输入态悬挂。
        if (::overlay.isInitialized && overlay.isActive()) {
            overlay.cancelFromBack()
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // 只有界面在前台时才需要对端操作反馈（后台时服务照常工作，只是没人看）
        TvInputDispatcher.listener = inputListener
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        TvInputDispatcher.listener = null
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }
    override fun onDestroy() {
        // 这两件事以前都没做：① 未取消的 postDelayed 会在 Activity 销毁后照样执行 ——
        // 「开启无障碍」那条会在用户已经退出后**强行拉起系统设置页**；
        // ② 弹窗不 dismiss → Activity 销毁时报 WindowLeaked。
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        try { unregisterReceiver(remoteInputReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ————————————————————————————— 界面 —————————————————————————————

    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(TvUi.Pal.bg) }
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        sv.addView(col, ViewGroup.LayoutParams(-1, -2))
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))

        // ---- 标题 + 一句话说明：用户三秒内要知道"这是干嘛的" ----
        col.addView(mkText(getString(R.string.app_name), TvUi.Type.DISPLAY, TvUi.Pal.accent, Typeface.DEFAULT_BOLD))
        col.addView(mkText(getString(R.string.tv_tagline), TvUi.Type.CAPTION, TvUi.Pal.textDim))

        // ---- 当前状态（由 refreshUI 填） ----
        statusTv = mkText("", TvUi.Type.TITLE, TvUi.Pal.text)
        col.addView(statusTv)

        // ---- 本机地址 + 连法：只有真有个地址可报时才显示（后台取，见 loadAsync） ----
        val addrTv = mkText("", TvUi.Type.CAPTION, TvUi.Pal.textDim).apply { visibility = View.GONE }
        val addrHintTv = mkText("", TvUi.Type.MICRO, TvUi.Pal.textWeak).apply { visibility = View.GONE }
        col.addView(addrTv)
        col.addView(addrHintTv)

        // ---- 这台电视现在能被怎么控制（注入通道能力，说人话） ----
        injectTv = TextView(this).apply {
            TvUi.applyTextSize(this, TvUi.Type.BODY)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TvUi.Pal.text)
            setPadding(0, gap / 2, 0, 0)
        }
        col.addView(injectTv)
        liveTv = TextView(this).apply {
            setTextColor(TvUi.Pal.accent)
            TvUi.applyTextSize(this, TvUi.Type.CAPTION)
            setPadding(0, gap / 4, 0, 0)
        }
        col.addView(liveTv)
        col.addView(spacer(gap.toFloat()))

        // ---- 动作按钮 ----
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton(getString(R.string.tv_action_files)) { startActivity(Intent(this, TvFileActivity::class.java)) })
        actions.addView(actionButton(getString(R.string.tv_action_screen)) { startActivity(Intent(this, TvScreenActivity::class.java)) })
        actions.addView(actionButton(getString(R.string.tv_action_selfcheck)) { showCapabilities() })
        actions.addView(actionButton(getString(R.string.tv_action_a11y)) { onAccessibilityAction() })
        toggleService = actionButton(getString(R.string.tv_accept_on)) { confirmToggleService() }
        actions.addView(toggleService)
        col.addView(actions)
        col.addView(spacer(gap.toFloat()))

        // ---- 遥控器怎么用 ----
        col.addView(mkText(getString(R.string.tv_hint), TvUi.Type.MICRO, TvUi.Pal.textDim))
        col.addView(spacer(gap.toFloat()))

        // ---- 收到的文件（空态告诉用户怎么让它不空） ----
        col.addView(mkText(getString(R.string.tv_files_title), TvUi.Type.TITLE, TvUi.Pal.text, Typeface.DEFAULT_BOLD))
        col.addView(spacer(4f))
        // 内容由 loadAsync 后台填（读磁盘目录不能堵主线程）
        val filesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(filesBox)

        col.addView(spacer(gap.toFloat()))
        col.addView(mkText(getString(R.string.tv_ime_hint), TvUi.Type.MICRO, TvUi.Pal.textWeak))

        loadAsync(addrTv, addrHintTv, filesBox)
        refreshUI()
        // 由 onCreate 的 setContentView(buildUi()) 挂载 —— 这里不要再 setContentView 一次
        return root
    }

    /**
     * 地址与文件清单都要碰系统（遍历网卡 / 读目录），电视的存储在同类设备里偏慢，
     * 放在主线程会明显拖慢启动。这里后台取，回来填进占位 View。
     */
    private fun loadAsync(addrTv: TextView, addrHintTv: TextView, filesBox: LinearLayout) {
        Thread({
            val addr = ControlServer.localIpv4()
            val files = TvFiles.listReceived(this).take(10)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (addr != null) {
                    addrTv.text = getString(R.string.tv_addr, addr)
                    addrTv.visibility = View.VISIBLE
                    addrHintTv.text = getString(R.string.tv_addr_hint)
                    addrHintTv.visibility = View.VISIBLE
                }
                filesBox.removeAllViews()
                if (files.isEmpty()) {
                    filesBox.addView(mkText(getString(R.string.tv_files_empty), TvUi.Type.MICRO, TvUi.Pal.textDim))
                } else {
                    for (f in files) {
                        filesBox.addView(
                            mkText("  ${f.name}（${TvFiles.sizeText(f.length())}）", TvUi.Type.MICRO, TvUi.Pal.textDim)
                        )
                    }
                }
            }
        }, "apx-tv-ui-io").apply { isDaemon = true; start() }
    }

    private fun refreshUI() {
        val svc = TvServerService.current
        statusTv.text = svc?.status() ?: getString(R.string.tv_service_stopped)
        injectTv.text = getString(R.string.tv_ability_label) + "：" + TvInjector.channelText()
        toggleService.text = getString(if (TvServerService.isEnabled(this)) R.string.tv_accept_off else R.string.tv_accept_on)
    }

    // ---- 工具方法 ----
    private fun mkText(text: String, sp: Float, color: Int, typeface: Typeface? = null): TextView {
        return TextView(this).apply { this.text = text; setTextColor(color); TvUi.applyTextSize(this, sp); if (typeface != null) this.typeface = typeface }
    }

    private fun showCapabilities() {
        val st = TvServerService.current?.status() ?: getString(R.string.tv_service_stopped)
        Toast.makeText(
            this,
            getString(R.string.tv_ability_label) + "：" + TvInjector.channelText() + "\n" + st,
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 「开启无障碍」：无障碍服务在无 root / 无 adb 时只能由用户手动勾选。
     * 这里先尝试用 root 直接开启（[AccessibilityAutoEnable.tryEnableViaRoot]）；
     * root 未生效（无 root，或系统有延迟尚未连上）则跳转到系统无障碍设置页引导用户手动开。
     */
    private fun onAccessibilityAction() {
        if (TvInjector.systemReady()) {
            Toast.makeText(this, getString(R.string.tv_a11y_already), Toast.LENGTH_SHORT).show()
            return
        }
        if (AccessibilityAutoEnable.tryEnableViaRoot(applicationContext)) {
            Toast.makeText(this, getString(R.string.tv_a11y_root_tried), Toast.LENGTH_LONG).show()
        }
        // root 生效可能有延迟：稍后复核，仍未生效才打开系统设置页（避免 root 成功还硬跳设置）
        handler.postDelayed({
            if (!TvInjector.systemReady()) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { Toast.makeText(this, getString(R.string.tv_a11y_open_failed), Toast.LENGTH_LONG).show() }
            }
        }, 1500)
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

    /**
     * 卡片式功能按钮：
     * 圆角卡片两态背景 + 大点击区（48dp 托底）+ 可聚焦 + 无障碍描述。
     * 远距离一眼能看出焦点落在哪一项。
     */
    private fun actionButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(TvUi.Pal.text)
        TvUi.applyTextSize(this, TvUi.Type.ACTION)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        minimumHeight = TvUi.dp(this@MainActivity, 48f)
        minimumWidth = TvUi.dp(this@MainActivity, 64f)
        contentDescription = text
        background = TvUi.focusBg(TvUi.Pal.card, TvUi.Pal.cardFocus, TvUi.dp(this@MainActivity, TvUi.Radius.CARD), TvUi.dp(this@MainActivity, 3f))
        setPadding(gap * 3, gap * 2, gap * 3, gap * 2)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap * 2 }
        setOnClickListener { onClick() }
    }
    private fun spacer(h: Float): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, TvUi.dp(this@MainActivity, h)) }
}
