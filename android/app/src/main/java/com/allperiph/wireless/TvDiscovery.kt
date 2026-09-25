package com.allperiph.wireless

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.allperiph.core.Log

/**
 * 局域网发现：监听各端 [WirelessBeacon] 广播的 `<前缀> <name> <port> <token>`（UDP 9501）。
 * 前缀即类型：`APX1TV` 电视 / `APX1PC` PC / `APX1PH` 手机被控（三端共用 9511 控制面）。
 * 维护最近 8s 内活跃的设备清单，供触控板右上角的设备选择器展示「可控制的设备」。
 *
 * 本机自身的 `WirelessBeacon` 只发广播（源端口随机），不占用 9501 接收端口，
 * 因此这里 bind 9501 收广播与自身发送互不冲突（本机自己发的信标按来源 IP 过滤掉）。
 */
object TvDiscovery {

    /** 8s 无信标即视为离线 */
    private const val EXPIRE_MS = 8000L
    private const val DISCOVERY_PORT = 9501

    /**
     * 手机被控的名字标记（`PH|<机型>`）。
     *
     * 刻意**不改信标前缀**：PC 端（尤其已安装的旧版）只认 `APX1TV`，换前缀就得两端
     * 同时升级，否则对方直接发现不到本机（副屏/音箱/麦克风一起废）。所以手机仍发
     * `APX1TV`，只把"我是手机"写进名字里 —— 手机端读标记显示 `(手机)`，PC/TV 端不看名字，零影响。
     */
    const val PHONE_NAME_MARK = "PH|"

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val devices = ConcurrentHashMap<String, TvDevice>() // key = IP

    data class TvDevice(
        var name: String,
        val ip: String,
        var port: Int,
        var lastSeen: Long,
        /** 设备类型："tv"（APX1TV）/ "pc"（APX1PC）/ "phone"（APX1PH，手机被控）；
         *  只有 "tv" 才进 TV 专属快捷键布局 */
        var type: String = "tv",
    ) {
        /** 列表后缀：三类得一眼分清，否则「手机被控」看起来和电视没区别 */
        val typeLabel: String
            get() = when (type) {
                "pc" -> "(PC)"
                "phone" -> "(手机)"
                else -> "(TV)"
            }
    }

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

    /** 本机 IPv4 集合：本机开了「无线」也会广播，发现表里必须排除自己 */
    private fun localIpv4s(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }
            .toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun loop() {
        try {
            // 绑定 9501 可能瞬时失败（端口被占 / 权限）：失败就重试，避免一次失败就永久失效
            var s: DatagramSocket? = null
            while (running.get() && s == null) {
                s = try {
                    DatagramSocket(DISCOVERY_PORT).apply { broadcast = true }
                } catch (t: Throwable) {
                    Log.w("TvDiscovery", "绑定 $DISCOVERY_PORT 失败，1s 后重试：${t.message}")
                    Thread.sleep(1000)
                    null
                }
            }
            val sock = s ?: return
            socket = sock
            val buf = ByteArray(256)
            while (running.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val line = String(buf, 0, pkt.length, StandardCharsets.UTF_8)
                // 信标前缀即类型：APX1PC→pc、APX1PH→phone（保留，供将来切换）、APX1TV→tv
                var type = when {
                    line.startsWith("APX1PC ") -> "pc"
                    line.startsWith("APX1PH ") -> "phone"
                    line.startsWith("APX1TV ") -> "tv"
                    else -> continue
                }
                // <前缀> <name> <port> <token>
                val parts = line.split(' ')
                if (parts.size < 3) continue
                val ip = pkt.address?.hostAddress ?: continue
                // 自我过滤：本机开了「无线」也会广播，别把自机列进设备表
                if (ip in localIpv4s()) continue
                val port = parts[2].toIntOrNull() ?: TvControllerClient.PORT
                var name = parts[1].ifBlank { ip }
                // 手机被控靠**名字标记**识别（前缀仍是 APX1TV，PC 端无需跟着升级）：
                // 显示名去掉标记，类型标成 phone → 列表显示「机型 (手机)」且不切 TV 布局
                if (type == "tv" && name.startsWith(PHONE_NAME_MARK)) {
                    type = "phone"
                    name = name.removePrefix(PHONE_NAME_MARK)
                }
                val now = System.currentTimeMillis()
                val existing = devices[ip]
                if (existing != null) {
                    existing.name = name
                    existing.port = port
                    existing.type = type
                    existing.lastSeen = now
                } else {
                    devices[ip] = TvDevice(name, ip, port, now, type)
                }
            }
        } catch (_: Throwable) {
            // socket 关闭或权限问题：发现静默失效，不影响其它功能
        } finally {
            runCatching { socket?.close() }
            socket = null
            running.set(false)   // 复位，允许下次 start() 重新尝试（否则一次失败就永久失效）
        }
    }
}
