package com.allperiph.tv.net

import com.allperiph.tv.core.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TV 侧反向信标：周期向局域网广播「我是 TV / 控制端口 / 令牌」。
 * 载荷：`APX1TV <name> <port> <token>`（UTF-8），广播到 `255.255.255.255:9501`。
 * 手机端 [com.allperiph.wireless.TvControllerClient] 监听该信标即可零配置发现 TV。
 * 广播失败不影响 TCP 监听本身（用户仍可手工填 TV IP:9511 连入）。
 */
class WirelessBeacon(
    private val tvName: String,
    private val tcpPort: Int,
    private val token: String,
    private val periodMs: Long = PERIOD_MS,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "apxtv-beacon").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var socket: DatagramSocket? = null
        val safeName = tvName.replace(' ', '_')
        val payload = "APX1TV $safeName $tcpPort $token".toByteArray(Charsets.UTF_8)
        try {
            socket = DatagramSocket().apply { broadcast = true }
            val target = InetAddress.getByName(BROADCAST_ADDR)
            val pkt = DatagramPacket(payload, payload.size, target, BEACON_PORT)
            while (running.get()) {
                try {
                    socket.send(pkt)
                } catch (t: Throwable) {
                    // 单次失败（网卡切换/休眠）不影响 TCP 监听
                }
                try {
                    Thread.sleep(periodMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w("信标广播不可用：${t.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        const val BEACON_PORT = 9501

        private const val BROADCAST_ADDR = "255.255.255.255"
        private const val PERIOD_MS = 1500L
    }
}
