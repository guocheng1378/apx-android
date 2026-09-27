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
import com.allperiph.tv.TvServerService
import com.allperiph.tv.core.Log
import com.allperiph.tv.core.TvInjector
import com.allperiph.tv.net.TcpControlServer

/**
 * APX TV 主界面（v1.34：**按 v120 的视觉重排**，纯代码构建）。
 *
 * v1.33 曾把主页改成「文字行堆叠」——功能齐全但缺少层次，用户反馈不如 v120 好看。
 * 本版恢复 v120 的排版骨架：大标题 + 状态区 + **卡片式功能按钮**（focusBg 两态、大点击区）
 * + 文件区，同时保留 v1.33 的全部能力：被控开关（含确认）、自检、注入通道轮询、
 * 远程输入浮层、文件接收列表。
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

    // ————————————————————————————— 界面（v120 视觉骨架） —————————————————————————————

    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(TvUi.Pal.bg) }
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        sv.addView(col, ViewGroup.LayoutParams(-1, -2))
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))

        // ---- 标题 + 状态区（v120：大标题、状态、IP、注入通道） ----
        col.addView(mkText("APX TV", 24f, TvUi.Pal.accent, Typeface.DEFAULT_BOLD))

        statusTv = mkText("", 17f, TvUi.Pal.text)
        col.addView(statusTv)

        col.addView(mkText("本机 ${TcpControlServer.localIpv4() ?: "无网络"} : ${TcpControlServer.PORT}", 13f, TvUi.Pal.textDim))

        injectTv = TextView(this).apply {
            TvUi.applyTextSize(this, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, gap / 2, 0, 0)
        }
        col.addView(injectTv)
        col.addView(spacer(gap.toFloat()))

        // ---- 功能按钮排（v120 的卡片式 actionButton） ----
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("文件传输") { startActivity(Intent(this, TvFileActivity::class.java)) })
        actions.addView(actionButton("副屏（看电脑画面）") { startActivity(Intent(this, TvScreenActivity::class.java)) })
        actions.addView(actionButton("控制能力（自检）") { showCapabilities() })
        toggleService = actionButton("停止被控") { confirmToggleService() }
        actions.addView(toggleService)
        col.addView(actions)
        col.addView(spacer(gap.toFloat()))

        // ---- 使用说明（v120 同款小字） ----
        col.addView(mkText(
            "方向键移动焦点，确认键触发；被手机控制时可直接用光标点。" +
                "本端只接受控制（被控），不主动控制别人。",
            12f, TvUi.Pal.textDim
        ))
        col.addView(spacer(gap.toFloat()))

        // ---- 文件传输（v183 保留功能） ----
        col.addView(mkText("已收到的文件", 16f, TvUi.Pal.text, Typeface.DEFAULT_BOLD))
        col.addView(spacer(4f))
        val received = TvFiles.listReceived(this).take(10)
        if (received.isNotEmpty()) {
            for (f in received) {
                col.addView(mkText("  ${f.name} (${TvFiles.sizeText(f.length())})", 12f, TvUi.Pal.textDim))
            }
        } else {
            col.addView(mkText("  暂无文件", 12f, TvUi.Pal.textDim))
        }

        col.addView(spacer(gap.toFloat()))
        col.addView(mkText("提示：中文打字最稳的通道是「输入法」，请在系统设置里启用「全能外设输入」。", 12f, TvUi.Pal.textDim))

        setContentView(root)
        refreshUI()
        return root
    }

    private fun refreshUI() {
        val svc = TvServerService.current
        statusTv.text = svc?.status() ?: "服务未启动"
        injectTv.text = "注入通道: ${TvInjector.channelText()}"
        toggleService.text = if (TvServerService.isEnabled(this)) "停止被控" else "开启被控"
    }

    // ---- 工具方法 ----
    private fun mkText(text: String, sp: Float, color: Int, typeface: Typeface? = null): TextView {
        return TextView(this).apply { this.text = text; setTextColor(color); TvUi.applyTextSize(this, sp); if (typeface != null) this.typeface = typeface }
    }

    private fun showCapabilities() {
        val st = TvServerService.current?.status() ?: "服务未启动"
        Toast.makeText(this, "注入通道: ${TvInjector.channelText()} | $st", Toast.LENGTH_LONG).show()
    }

    /** 「停止被控」会立刻断开所有控制端，先确认再执行 */
    private fun confirmToggleService() {
        val enabling = !TvServerService.isEnabled(this)
        AlertDialog.Builder(this)
            .setTitle(if (enabling) "开启被控" else "停止被控")
            .setMessage(
                if (enabling) "开启后，同一局域网内的手机 / 电脑可以连接并控制本机。"
                else "停止后，手机 / 电脑将无法连接控制本机。"
            )
            .setPositiveButton(if (enabling) "开启" else "停止") { _, _ ->
                if (enabling) TvServerService.start(this) else TvServerService.stopAll(this)
                refreshUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 卡片式功能按钮（v120 的 actionButton 视觉）：
     * 圆角卡片两态背景 + 大点击区（48dp 托底）+ 可聚焦 + 无障碍描述。
     * 远距离一眼能看出焦点落在哪一项。
     */
    private fun actionButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(TvUi.Pal.text)
        TvUi.applyTextSize(this, 16f)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        minimumHeight = TvUi.dp(this@MainActivity, 48f)
        minimumWidth = TvUi.dp(this@MainActivity, 64f)
        contentDescription = text
        background = TvUi.focusBg(TvUi.Pal.card, TvUi.Pal.cardFocus, TvUi.dp(this@MainActivity, 12f), TvUi.dp(this@MainActivity, 3f))
        setPadding(gap * 3, gap * 2, gap * 3, gap * 2)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap * 2 }
        setOnClickListener { onClick() }
    }
    private fun spacer(h: Float): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, TvUi.dp(this@MainActivity, h)) }
}
