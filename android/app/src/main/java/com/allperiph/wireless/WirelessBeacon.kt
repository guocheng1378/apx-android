package com.allperiph.wireless

import com.allperiph.core.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机侧反向信标：周期向局域网广播「我是谁 / 服务端口 / 令牌」。
 *
 * 载荷：`APX1PHONE <name> <port> <token>`（UTF-8），广播到 `255.255.255.255:9501`，
 * 与 `pc/host/src/wireless/beacon_listener.cpp` 的解析逐字节对应。
 *
 * 有了它，PC 端不必让用户手输手机 IP：`apxhost wireless-listen` 收到信标即自动连入。
 * 手机 IP 变化（DHCP）也不会导致失联 —— 这是纯手工填 IP 方案的主要痛点。
 *
 * 广播失败（无网络 / 路由器隔离广播）只记日志，不影响 TCP 监听本身：
 * 用户仍可在 PC 上用 `apxhost wireless <ip>:9500` 手工接入。
 */
class WirelessBeacon(
    private val phoneName: String,
    private val tcpPort: Int,
    private val token: String,
    private val periodMs: Long = PERIOD_MS,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "apx-beacon").apply {
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
        // 空格会破坏 PC 侧的定长字段解析（名字必须是单个 token），统一替换掉
        val safeName = phoneName.replace(' ', '_')
        val payload = "APX1PHONE $safeName $tcpPort $token".toByteArray(Charsets.UTF_8)
        try {
            socket = DatagramSocket().apply { broadcast = true }
            val target = InetAddress.getByName(BROADCAST_ADDR)
            val pkt = DatagramPacket(payload, payload.size, target, BEACON_PORT)
            while (running.get()) {
                try {
                    socket.send(pkt)
                } catch (t: Throwable) {
                    // 单次发送失败（网卡切换/休眠）不下线，下一周期继续
                }
                try {
                    Thread.sleep(periodMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "信标广播不可用：${t.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        private const val TAG = "WirelessBeacon"
        private const val BROADCAST_ADDR = "255.255.255.255"
        private const val PERIOD_MS = 1500L

        /** 与会话端口（9500）分开，避免与数据通道抢包 */
        const val BEACON_PORT = 9501
    }
}
