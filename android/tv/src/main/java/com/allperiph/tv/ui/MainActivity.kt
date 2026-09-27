package com.allperiph.tv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.allperiph.tv.TvServerService
import com.allperiph.tv.core.Log
import com.allperiph.tv.core.TvInjector
import com.allperiph.tv.net.TcpControlServer

class MainActivity : android.app.Activity() {
    private lateinit var root: FrameLayout
    private lateinit var overlay: RemoteInputOverlay
    private lateinit var statusTv: TextView
    private lateinit var injectTv: TextView
    private lateinit var enableSwitch: Switch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.bindColors(this)
        root = FrameLayout(this)
        root.setBackgroundColor(TvUi.Pal.bg)

        val sv = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24f), dp(16f), dp(24f), dp(16f)) }

        // ---- 标题 ----
        content.addView(mkText("APX TV", 24f, Color.WHITE, Typeface.DEFAULT_BOLD))
        content.addView(mkText("${TcpControlServer.localIpv4() ?: "无网络"} : ${TcpControlServer.PORT}", 14f, TvUi.Pal.textDim))
        content.addView(spacer(4f))

        // ---- 状态 ----
        statusTv = mkText("", 13f, TvUi.Pal.textDim)
        content.addView(statusTv)
        content.addView(spacer(8f))

        // ---- 被控开关 ----
        val switchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        switchRow.addView(mkText("被控开关", 16f, Color.WHITE))
        enableSwitch = Switch(this).apply { isChecked = TvServerService.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked) TvServerService.start(this@MainActivity)
                else TvServerService.stopAll(this@MainActivity)
                refreshUI()
            }
        }
        val switchParams = LinearLayout.LayoutParams(dp(50f), -2).apply { marginStart = dp(16f) }
        switchRow.addView(enableSwitch, switchParams)
        content.addView(switchRow)
        content.addView(spacer(8f))

        // ---- 注入通道 ----
        injectTv = mkText("", 14f, TvUi.Pal.text)
        content.addView(injectTv)
        content.addView(spacer(12f))

        // ---- 自检 ----
        val checkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        checkRow.addView(mkText("自检", 16f, Color.WHITE))
        checkRow.addView(mkButton("注入通道") {
            val info = TvInjector.channelText()
            android.widget.Toast.makeText(this, "注入通道: $info", android.widget.Toast.LENGTH_SHORT).show()
        })
        checkRow.addView(mkButton("服务状态") {
            val st = TvServerService.current?.status() ?: "未启动"
            android.widget.Toast.makeText(this, st, android.widget.Toast.LENGTH_SHORT).show()
        })
        content.addView(checkRow)
        content.addView(spacer(12f))

        // ---- 功能入口 ----
        val entryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        entryRow.addView(mkButton("文件传输") {
            val i = Intent(this, TvFileActivity::class.java)
            startActivity(i)
        })
        entryRow.addView(mkButton("副屏") {
            val i = Intent(this, TvScreenActivity::class.java)
            startActivity(i)
        })
        content.addView(entryRow)
        content.addView(spacer(12f))

        // ---- 已连接设备 ----
        content.addView(mkText("已连接设备", 14f, TvUi.Pal.textDim))
        content.addView(spacer(12f))

        // ---- 文件传输 ----
        content.addView(mkText("文件传输", 16f, Color.WHITE, Typeface.DEFAULT_BOLD))
        content.addView(spacer(4f))

        // 已收到的文件（只展示前 10 个）
        val received = TvFiles.listReceived(this).take(10)
        if (received.isNotEmpty()) {
            for (f in received) {
                content.addView(mkText("  ${f.name} (${TvFiles.sizeText(f.length())})", 12f, TvUi.Pal.textDim))
            }
        } else {
            content.addView(mkText("  暂无文件", 12f, TvUi.Pal.textDim))
        }

        sv.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

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

    private fun refreshUI() {
        val svc = TvServerService.current
        val status = svc?.status() ?: "服务未启动"
        statusTv.text = status
        injectTv.text = "注入通道: ${TvInjector.channelText()}"
        enableSwitch.isChecked = TvServerService.isEnabled(this)
    }

    // ---- 工具方法 ----
    private fun mkText(text: String, sp: Float, color: Int, typeface: Typeface? = null): TextView {
        return TextView(this).apply { this.text = text; setTextColor(color); TvUi.applyTextSize(this, sp); if (typeface != null) this.typeface = typeface }
    }
    /**
     * 文本按钮（v1.33）：补齐电视上的可用性三件套 ——
     * ① **可聚焦**：此前没有 `isFocusable`，DPAD 只能停在能用 focusBg 的少数卡片上，这个按钮永远到不了；
     * ② **触控下限**：原先纵向只有 4dp padding（≈ 十几像素），远距离同样够不着，现按 48dp 托底；
     * ③ **焦点态**：聚焦时填充 [TvUi.Pal.cardFocus] 并加白描边，隔几米也能看出落在哪一项。
     */
    private fun mkButton(label: String, onClick: () -> Unit): View {
        val tv = TextView(this).apply {
            text = label; setTextColor(TvUi.Pal.accent); TvUi.applyTextSize(this, 13f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            minimumHeight = TvUi.dp(this@MainActivity, 48f)
            minimumWidth = TvUi.dp(this@MainActivity, 64f)
            isFocusable = true
            contentDescription = label
            background = TvUi.focusBg(Color.TRANSPARENT, TvUi.Pal.cardFocus, dp(4f), dp(2f))
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) }
        return tv.apply { layoutParams = lp }
    }
    private fun spacer(h: Float): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(h)) }
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
