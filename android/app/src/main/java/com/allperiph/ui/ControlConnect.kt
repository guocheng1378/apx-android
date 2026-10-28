package com.allperiph.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvControllerClient

/**
 * 连入受控设备（TV / PC）的**唯一实现**。
 *
 * ## 为什么要单独拎出来
 * 这段逻辑原来只长在 `MainActivity.connectTargetInternal()` 里：连、失败回滚、记连接历史、
 * 反向剪贴板回调，全在 Activity 内部。重构后设置页（Compose）也要能连设备，若照抄一份，
 * 两处迟早会不一致（尤其「必须先断开旧实例」这条：旧实例的 readerLoop 会每 2 秒重连**旧 IP**，
 * 直接覆盖会永久多留一套线程 + socket）。
 *
 * 所以把它抽成这里，旧 Activity 与新设置页都走它 —— 行为只有一份。
 *
 * ## 线程
 * 连接是阻塞的（`connect()` 最长 5s），在后台线程做；回主线程回调，回调里可以直接动 UI。
 */
object ControlConnect {

    /**
     * 连入 [ip]:[port]。
     *
     * @param type 受控端类型（"tv" / "pc"），只用于显示与历史记录
     * @param onReverseClipboard 反向剪贴板回调（受控端 → 本机），已在主线程
     * @param onDone 连入结果（主线程）；成功时 [ControlTarget] 与连接历史都已写好
     */
    fun connect(
        context: Context,
        ip: String,
        port: Int,
        name: String,
        type: String = "tv",
        onReverseClipboard: (String) -> Unit = {},
        onDone: (Boolean) -> Unit = {},
    ) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val c = TvControllerClient(ip, port)
            val ok = c.connect()
            main.post {
                if (ok) {
                    // ⚠️ 必须先断开旧连接：旧实例仍是 running，它的 readerLoop 会每 2s 无限
                    // 重连**旧 IP**，并一直留着 socket + tx/ping 三条线程
                    ControlTarget.controlClient?.takeIf { it !== c }?.disconnect()
                    ControlTarget.controlClient = c
                    ControlTarget.host = ip
                    ControlTarget.label = name
                    ControlTarget.type = type
                    ConnectHistory.add(app, name, ip, port)
                    c.onReverseClipboard = { text -> main.post { onReverseClipboard(text) } }
                } else {
                    ControlTarget.clear()
                }
                onDone(ok)
            }
        }, "apx-target").start()
    }
}
