package com.allperiph.tv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.allperiph.tv.TvServerService
import com.allperiph.tv.core.Log
import com.allperiph.tv.net.TcpControlServer

class MainActivity : android.app.Activity() {
    private lateinit var root: FrameLayout
    private lateinit var overlay: RemoteInputOverlay
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
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(TvUi.dp(this@MainActivity, 24f), TvUi.dp(this@MainActivity, 16f), TvUi.dp(this@MainActivity, 24f), TvUi.dp(this@MainActivity, 16f)) }
        content.addView(TextView(this).apply { text = "APX TV"; setTextColor(Color.WHITE); TvUi.applyTextSize(this, 24f); typeface = Typeface.DEFAULT_BOLD })
        content.addView(TextView(this).apply { text = "本机 ${TcpControlServer.localIpv4() ?: "无网络"} : ${TcpControlServer.PORT}"; setTextColor(Color.parseColor("#99FFFFFF")); TvUi.applyTextSize(this, 14f) })
        content.addView(TextView(this).apply { text = TvServerService.current?.status() ?: "服务未启动"; setTextColor(Color.parseColor("#FF666666")); TvUi.applyTextSize(this, 13f); setPadding(0, TvUi.dp(this@MainActivity, 8f), 0, 0) })
        root.addView(content, FrameLayout.LayoutParams(-1, -2))
        setContentView(root)
        overlay = RemoteInputOverlay(
            activity = this,
            rootLayout = root,
            onTextChanged = { text, flags ->
                TvServerService.current?.sendInputText(text, flags)
            },
            onSend = {
                TvServerService.current?.sendInputDone()
            },
            onCancel = {
                TvServerService.current?.sendInputDone()
            }
        )
        val filter = IntentFilter(TvServerService.ACTION_REMOTE_INPUT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) registerReceiver(remoteInputReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(remoteInputReceiver, filter)
    }
    override fun onDestroy() { try { unregisterReceiver(remoteInputReceiver) } catch (_: Exception) {}; super.onDestroy() }
}