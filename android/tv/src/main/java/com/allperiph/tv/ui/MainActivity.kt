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
import android.widget.Toast
import com.allperiph.tv.TvServerService
import com.allperiph.tv.core.ApxFrame
import com.allperiph.tv.core.Log
import com.allperiph.tv.net.TcpControlServer

class MainActivity : android.app.Activity() {
    private lateinit var root: FrameLayout
    private lateinit var overlay: RemoteInputOverlay
    private var currentHint: String = ""

    private val remoteInputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TvServerService.ACTION_REMOTE_INPUT) {
                val from = intent.getStringExtra(TvServerService.EXTRA_SOURCE) ?: ""
                val hint = intent.getStringExtra(TvServerService.EXTRA_HINT) ?: ""
                Log.i("MainActivity", "收到远程输入请求: from=$from, hint=$hint")
                currentHint = hint
                runOnUiThread { overlay.show(from, hint) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#FF1A1A2E"))

        // 主界面内容
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(TvUi.dp(this@MainActivity, 24f), TvUi.dp(this@MainActivity, 16f), TvUi.dp(this@MainActivity, 24f), TvUi.dp(this@MainActivity, 16f))
        }
        content.addView(TextView(this).apply {
            text = "APX TV"
            setTextColor(Color.WHITE)
            TvUi.applyTextSize(this, 24f)
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "本机 ${TcpControlServer.localIpv4() ?: "无网络"} : ${TcpControlServer.PORT}"
            setTextColor(Color.parseColor("#99FFFFFF"))
            TvUi.applyTextSize(this, 14f)
        })
        val statusTv = TextView(this).apply {
            text = TvServerService.current?.status() ?: "服务未启动"
            setTextColor(Color.parseColor("#FF666666"))
            TvUi.applyTextSize(this, 13f)
            setPadding(0, TvUi.dp(this@MainActivity, 8f), 0, 0)
        }
        content.addView(statusTv)
        root.addView(content, FrameLayout.LayoutParams(-1, -2))

        setContentView(root)

        // 远程输入覆盖层
        overlay = RemoteInputOverlay(this, root,
            onTextChanged = { text, flags ->
                // 通过 TcpControlServer 发送输入文本给对端
                // 注意：TV 端是被控端，这里的 onTextChanged 是 TV 端本地输入
                // 实际的远程输入应该由手机端发起，TV 端只是弹出输入界面
                Log.i("MainActivity", "远程输入: text=$text, flags=$flags")
            },
            onSend = { },
            onCancel = { }
        )

        // 注册广播接收器
        val filter = IntentFilter(TvServerService.ACTION_REMOTE_INPUT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteInputReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(remoteInputReceiver, filter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(remoteInputReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
