package com.allperiph.tv.net

import com.allperiph.tv.core.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TV 侧反向信标：周期向局域网广播「我是 TV / 控制端口 / 令牌」。
 * 载荷：`APX1TV <name> <port> <token>`（UTF-8），广播到 9501。
 *
 * **必须同时发受限广播与各网卡的子网定向广播**：Xiaomi MIUI / Android 15 会丢弃
 * `255.255.255.255` 受限广播（与手机端 [com.allperiph.controlled.WirelessBeacon] 同源问题），
 * 只发它时对面（手机 / PC）会发现不到本机，只能手填 IP 才有用。
 *
 * 手机端 [com.allperiph.wireless.TvControllerClient] 监听该信标即可零配置发现 TV。
 * 广播失败不影响 TCP 监听本身（用户仍可手工填 TV IP:9511 连入）。
 */
class WirelessBeacon(
    private val tvName: String,
    private val tcpPort: Int,
    private val token: String,
    /**
     * 额外的**单播**目标（每轮重新取值）。原因：不少廉价 AP / Mesh / 中继会丢弃
     * 「Wi‑Fi 客户端 → 有线」的广播（反方向却正常），此时只发广播的话，接在网线上的
     * PC 端会永远停在「正在发现」。这里对**已知对端**（当前连入方 + 记过的对端 IP）
     * 再单播一份，兜住这种情况。TV 自己不发起连接，所以这份名单来自「曾经连过本机的对端」。
     */
    private val unicastHosts: () -> List<String> = { emptyList() },
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
            while (running.get()) {
                // 每轮重算目标：网卡/切网随时可能变（网线插拔、Wi‑Fi 开关）
                for (addr in broadcastTargets()) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, addr, BEACON_PORT))
                    } catch (t: Throwable) {
                        // 单次失败（网卡切换/休眠）不影响 TCP 监听
                    }
                }
                // 单播兜底：广播被 AP 丢掉时，这条通常还能通
                for (h in unicastHosts()) {
                    val addr = try { InetAddress.getByName(h) } catch (_: Throwable) { continue }
                    try {
                        socket.send(DatagramPacket(payload, payload.size, addr, BEACON_PORT))
                    } catch (_: Throwable) {
                        // 同上：单次失败不致命
                    }
                }
                try {
                    Thread.sleep(periodMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (t: Throwable) {
            Log.w("TV信标广播不可用：${t.message}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** 受限广播 + 每个上联网卡的子网定向广播（MIUI 会丢前者，只发它等于没发） */
    private fun broadcastTargets(): List<InetAddress> {
        val out = ArrayList<InetAddress>(4)
        runCatching { out.add(InetAddress.getByName(BROADCAST_ADDR)) }
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (b.hostAddress != null) out.add(b)
                }
            }
        }
        return out.distinctBy { it.hostAddress }
    }

    companion object {
        const val BEACON_PORT = 9501

        private const val BROADCAST_ADDR = "255.255.255.255"
        private const val PERIOD_MS = 1500L
    }
}
