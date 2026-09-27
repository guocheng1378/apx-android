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
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#FF1A1A2E"))

        val sv = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24f), dp(16f), dp(24f), dp(16f)) }

        // ---- 标题 ----
        content.addView(mkText("APX TV", 24f, Color.WHITE, Typeface.DEFAULT_BOLD))
        content.addView(mkText("${TcpControlServer.localIpv4() ?: "无网络"} : ${TcpControlServer.PORT}", 14f, Color.parseColor("#99FFFFFF")))
        content.addView(spacer(4f))

        // ---- 状态 ----
        statusTv = mkText("", 13f, Color.parseColor("#FF666666"))
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
        injectTv = mkText("", 14f, Color.parseColor("#BBFFFFFF"))
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
        content.addView(mkText("已连接设备", 14f, Color.parseColor("#99FFFFFF")))
        content.addView(spacer(12f))

        // ---- 文件传输 ----
        content.addView(mkText("文件传输", 16f, Color.WHITE, Typeface.DEFAULT_BOLD))
        content.addView(spacer(4f))

        // 已收到的文件（只展示前 10 个）
        val received = TvFiles.listReceived(this).take(10)
        if (received.isNotEmpty()) {
            for (f in received) {
                content.addView(mkText("  ${f.name} (${TvFiles.sizeText(f.length())})", 12f, Color.parseColor("#88FFFFFF")))
            }
        } else {
            content.addView(mkText("  暂无文件", 12f, Color.parseColor("#66666666")))
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
    private fun mkButton(label: String, onClick: () -> Unit): View {
        val tv = TextView(this).apply {
            text = label; setTextColor(Color.parseColor("#FF4FC3F7")); TvUi.applyTextSize(this, 13f)
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            background = GradientDrawable().apply { setStroke(1, Color.parseColor("#FF4FC3F7")); cornerRadius = dp(4f).toFloat() }
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) }
        return tv.apply { layoutParams = lp }
    }
    private fun spacer(h: Float): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(h)) }
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
