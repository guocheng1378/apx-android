package com.allperiph.controlled

import com.allperiph.core.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 被控侧反向信标：周期向局域网广播「我是可控制的手机 / 控制端口 / 令牌」。
 * 载荷：`APX1TV <name> <port> <token>`（UTF-8），广播到 `255.255.255.255:9501`。
 * 对方手机端 [com.allperiph.wireless.TvControllerClient] / [TvDiscovery] 监听该信标即可零配置发现本机。
 * 与 TV 模块 [com.allperiph.tv.net.WirelessBeacon] 同实现。
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
        thread = Thread({ loop() }, "apxctl-beacon").apply {
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
            Log.w("被控信标", "广播不可用：${t.message}")
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
