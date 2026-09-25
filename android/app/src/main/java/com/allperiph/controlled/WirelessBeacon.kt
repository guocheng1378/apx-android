package com.allperiph.controlled

import com.allperiph.core.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 被控侧反向信标：周期向局域网广播「我是可控制的手机 / 控制端口 / 令牌」。
 * 载荷：`APX1TV <name> <port> <token>`（UTF-8），广播到 9501。
 *
 * **必须同时发受限广播与各网卡的子网定向广播**：Xiaomi MIUI / Android 15 会丢弃
 * `255.255.255.255` 受限广播，只发它时 PC 端面板会永远停在「正在发现」，
 * 进而连不上 9502 媒体口（副屏 / 音箱 / 麦克风全废）。PC 侧 ctrl9511.cpp 早已
 * 用同样办法绕开，这里补齐同一处理。
 *
 * 对方手机端 [com.allperiph.wireless.TvControllerClient] / [TvDiscovery] 监听该信标即可零配置发现本机。
 * 与 TV 模块 [com.allperiph.tv.net.WirelessBeacon] 同实现。
 */
class WirelessBeacon(
    /** 信标前缀 = 设备类型：APX1TV 电视 / APX1PC PC / APX1PH 手机被控 */
    private val prefix: String,
    private val tvName: String,
    private val tcpPort: Int,
    private val token: String,
    /**
     * 额外**单播**目标（每轮重新取值）。原因：不少廉价 AP / 中继 / Mesh 会丢弃
     * 「Wi‑Fi 客户端 → 有线」的广播（有线 → Wi‑Fi 却正常），此时只发广播对面永远
     * 发现不到本机 —— 表现为 PC 面板一直「正在发现」，副屏/音箱/麦克风全废。
     * 这里对已知对端（如正在控的 PC）再单播一份，兜住这种情况。
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
        val payload = "$prefix $safeName $tcpPort $token".toByteArray(Charsets.UTF_8)
        try {
            socket = DatagramSocket().apply { broadcast = true }
            var loggedFail = false
            var first = true
            while (running.get()) {
                // 每轮重算目标：网卡/切网随时可能变（Wi‑Fi 开关、热点的时）
                val bcasts = broadcastTargets()
                val uni = unicastHosts()
                if (first) {
                    // 首轮把目标打出来：广播目标里没有子网广播地址 = 对面永远收不到
                    first = false
                    Log.i("被控信标", "广播目标=${bcasts.map { it.hostAddress }} 单播目标=$uni")
                }
                for (addr in bcasts) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, addr, BEACON_PORT))
                    } catch (t: Throwable) {
                        // 单次失败（网卡切换/休眠）不影响 TCP 监听；首次失败要显形
                        if (!loggedFail) {
                            loggedFail = true
                            Log.w("被控信标", "广播发送失败 ${addr.hostAddress}：${t.message}")
                        }
                    }
                }
                // 单播兜底：对已知对端再发一份（广播被 AP 丢弃时这条通常还能通）
                for (h in uni) {
                    val addr = try { InetAddress.getByName(h) } catch (_: Throwable) { continue }
                    try {
                        socket.send(DatagramPacket(payload, payload.size, addr, BEACON_PORT))
                    } catch (t: Throwable) {
                        // 同上：单次失败不致命
                        if (!loggedFail) {
                            loggedFail = true
                            Log.w("被控信标", "单播发送失败 $h：${t.message}")
                        }
                    }
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
