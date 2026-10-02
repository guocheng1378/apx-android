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
import com.allperiph.shared.util.Log
import com.allperiph.tv.core.TvInjector
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
    private lateinit var toggleService: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() { refreshUI(); handler.postDelayed(this, 2000) }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.bindColors(this)
        // 注入通道初始化幂等；被控服务由用户开关控制（见 toggleService）
        TvInjector.init(this)
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
    }

    override fun onResume() { super.onResume(); handler.post(refreshRunnable) }
    override fun onPause() { handler.removeCallbacks(refreshRunnable); super.onPause() }
    override fun onDestroy() { try { unregisterReceiver(remoteInputReceiver) } catch (_: Exception) {}; super.onDestroy() }

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

        // ---- 本机地址 + 连法：只有真有个地址可报时才显示 ----
        val addr = ControlServer.localIpv4()
        if (addr != null) {
            col.addView(mkText(getString(R.string.tv_addr, addr), TvUi.Type.CAPTION, TvUi.Pal.textDim))
            col.addView(mkText(getString(R.string.tv_addr_hint), TvUi.Type.MICRO, TvUi.Pal.textWeak))
        }

        // ---- 这台电视现在能被怎么控制（注入通道能力，说人话） ----
        injectTv = TextView(this).apply {
            TvUi.applyTextSize(this, TvUi.Type.BODY)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TvUi.Pal.text)
            setPadding(0, gap / 2, 0, 0)
        }
        col.addView(injectTv)
        col.addView(spacer(gap.toFloat()))

        // ---- 四个动作 ----
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton(getString(R.string.tv_action_files)) { startActivity(Intent(this, TvFileActivity::class.java)) })
        actions.addView(actionButton(getString(R.string.tv_action_screen)) { startActivity(Intent(this, TvScreenActivity::class.java)) })
        actions.addView(actionButton(getString(R.string.tv_action_selfcheck)) { showCapabilities() })
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
        val received = TvFiles.listReceived(this).take(10)
        if (received.isNotEmpty()) {
            for (f in received) {
                col.addView(mkText("  ${f.name}（${TvFiles.sizeText(f.length())}）", TvUi.Type.MICRO, TvUi.Pal.textDim))
            }
        } else {
            col.addView(mkText(getString(R.string.tv_files_empty), TvUi.Type.MICRO, TvUi.Pal.textDim))
        }

        col.addView(spacer(gap.toFloat()))
        col.addView(mkText(getString(R.string.tv_ime_hint), TvUi.Type.MICRO, TvUi.Pal.textWeak))

        setContentView(root)
        refreshUI()
        return root
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

    /** 「停止接受控制」会立刻断开所有控制端，先确认再执行 */
    private fun confirmToggleService() {
        val enabling = !TvServerService.isEnabled(this)
        AlertDialog.Builder(this)
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
