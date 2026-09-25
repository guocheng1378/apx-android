package com.allperiph.wireless

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网 TV 发现：监听 TV 侧 [WirelessBeacon] 广播的 `APX1TV <name> <port> <token>`（UDP 9501）。
 * 维护最近 8s 内活跃的设备清单，供触控板右上角的设备选择器展示「可控制的 TV」。
 *
 * 本机自身的 `WirelessBeacon` 只发广播（源端口随机），不占用 9501 接收端口，
 * 因此这里 bind 9501 收广播与自身发送互不冲突。
 */
object TvDiscovery {

    /** 8s 无信标即视为离线 */
    private const val EXPIRE_MS = 8000L
    private const val DISCOVERY_PORT = 9501

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val devices = ConcurrentHashMap<String, TvDevice>() // key = IP

    data class TvDevice(
        var name: String,
        val ip: String,
        var port: Int,
        var lastSeen: Long,
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "tv-discovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread = null
        devices.clear()
    }

    /** 当前在线的 TV 列表（按名称排序；自动剔除过期项） */
    fun list(): List<TvDevice> {
        val now = System.currentTimeMillis()
        val it = devices.values.iterator()
        while (it.hasNext()) if (now - it.next().lastSeen > EXPIRE_MS) it.remove()
        return devices.values.sortedBy { it.name }.toList()
    }

    private fun loop() {
        try {
            val s = DatagramSocket(DISCOVERY_PORT).apply { broadcast = true }
            socket = s
            val buf = ByteArray(256)
            while (running.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                val line = String(buf, 0, pkt.length, StandardCharsets.UTF_8)
                if (!line.startsWith("APX1TV ")) continue
                // APX1TV <name> <port> <token>
                val parts = line.split(' ')
                if (parts.size < 3) continue
                val ip = pkt.address?.hostAddress ?: continue
                val port = parts[2].toIntOrNull() ?: TvControllerClient.PORT
                val name = parts[1].ifBlank { ip }
                val now = System.currentTimeMillis()
                val existing = devices[ip]
                if (existing != null) {
                    existing.name = name
                    existing.port = port
                    existing.lastSeen = now
                } else {
                    devices[ip] = TvDevice(name, ip, port, now)
                }
            }
        } catch (_: Throwable) {
            // socket 关闭或权限问题：发现静默失效，不影响其它功能
        } finally {
            runCatching { socket?.close() }
        }
    }
}
