package com.allperiph.wireless

import com.allperiph.core.ApxFrame
import com.allperiph.core.EventBus
import com.allperiph.core.Log
import com.allperiph.core.ScreenOpenRequestEvent
import com.allperiph.core.TcpCtrlBridge
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi‑Fi 控制通道：**手机做服务端**（PC 主动连入），承载触控板 / 键盘 / 多媒体输入。
 *
 * 为什么手机做服务端：PC 端发起的是**出站**连接，Windows 防火墙默认放行，
 * 不需要用户去开入站规则；手机侧只需监听一个端口。
 *
 * 建链顺序（与 `pc/host/src/wireless/wireless_link.cpp` 逐字节一致）：
 * ```
 * PC  → 手机 : u32 LE 长度 + UTF-8 令牌      （握手，无回执字节）
 * 手机 → PC  : APX1 帧（streamId=3）           （控制面，收发双向）
 * PC  → 手机 : APX1 帧 body="ping"（1s 心跳）
 * 手机 → PC  : APX1 帧 body="pong"（回显，PC 据此算 RTT）
 * ```
 * 令牌校验失败时**直接关连接、不回执** —— 回执字节会与紧随其后的帧混淆，
 * 反而把对端解析带偏。对端 `recv` 返回 0 即知被拒，语义一样清楚。
 *
 * ## 发送必须走队列（真机教训）
 * 手势帧的生产者是 **UI 线程**（`onTouchEvent` → GestureEngine → dispatch）。
 * 在 UI 线程上直接 `socket.write` 会抛 `NetworkOnMainThreadException`，
 * 被 catch 吞掉后表现为「链路显示在线、但光标纹丝不动」——
 * 只有 60ms 后由子线程发出的「按键释放帧」能漏过去。
 * 因此所有出站帧一律**入队**，由专门的 writer 线程写出（单写者，天然保序）。
 */
class TcpControlChannel(
    private val port: Int = PORT,
    private val token: String = "",
) : TcpCtrlBridge.Sink {

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var client: Socket? = null

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var peerText: String = ""

    @Volatile
    override var ready: Boolean = false
        private set

    /** 已建立连接的 PC，未连接时为空串（UI 展示用） */
    fun peer(): String = peerText

    /** 队列满时被丢弃的帧数（手势高峰；丢弃是**保新弃旧**，保证跟手） */
    @Volatile
    var droppedFrames: Long = 0
        private set

    /**
     * PC → 手机 的模块开关命令回调（0x10）：参数 (模块索引, 开)。
     * 在 reader 线程回调；实现方自行切线程（AgentController 内部已有 io 线程）。
     * 模块索引两端约定，与 AgentController.ORDER 一致（0..7）。
     */
    @Volatile
    var moduleCommandListener: ((Int, Boolean) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    private val writeLock = Any()
    private var seq = 0

    /** 待发送帧（已含帧头与 CRC）。控制面流量很小，256 帧足够吸收任何突发 */
    private val outQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)

    private val rxLock = Any()
    private var rxBuf = ByteArray(4096)
    private var rxLen = 0

    // ————————————————————————————— 生命周期 —————————————————————————————

    /** 开始监听。失败（端口被占/无网络权限）返回 false，由调用方如实标 ERROR。 */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss
            running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "apx-tcp-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Wi‑Fi 控制通道监听 *:$port（本机 ${localIpv4() ?: "未知"}）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "监听 $port 失败", t)
            runCatching { server?.close() }
            server = null
            false
        }
    }

    fun stop() {
        running.set(false)
        outQueue.clear()
        runCatching { client?.close() }
        runCatching { server?.close() }
        server = null
        client = null
        out = null
        peerText = ""
        ready = false
        TcpCtrlBridge.detach()
        acceptThread = null
        readerThread = null
        writerThread = null
    }

    fun statusText(): String = when {
        ready -> {
            val drop = if (droppedFrames > 0) " · 丢帧 $droppedFrames" else ""
            "已连接 $peerText$drop"
        }
        else -> "监听 $port · 等 PC 连入"
    }

    // ————————————————————————————— 接受连接 —————————————————————————————

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (t: Throwable) {
                if (!running.get()) break
                try {
                    Thread.sleep(200)   // 避免异常导致的忙循环
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            // 单对端语义：已有连接时拒绝新连接，防止两个 PC 争抢同一输入出口
            if (ready) {
                Log.w(TAG, "已有连接 $peerText，拒绝新连接")
                runCatching { sock.close() }
                continue
            }
            Thread({ handshake(sock) }, "apx-tcp-handshake").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handshake(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = HANDSHAKE_TIMEOUT_MS
            val ins = sock.getInputStream()

            // u32 LE 长度 + 令牌字节；令牌为空也读这 4 字节（与 PC 端行为对称）
            val len = readU32Le(ins)
            if (len < 0 || len > MAX_TOKEN) {
                Log.w(TAG, "握手长度非法：$len")
                runCatching { sock.close() }
                return
            }
            val buf = ByteArray(len)
            if (!readFully(ins, buf)) {
                runCatching { sock.close() }
                return
            }
            if (token.isNotEmpty() && String(buf, Charsets.UTF_8) != token) {
                Log.w(TAG, "令牌不匹配，拒绝 ${peerTextOf(sock)}")
                runCatching { sock.close() }
                return
            }

            sock.soTimeout = 0
            activate(sock)
        } catch (t: Throwable) {
            Log.w(TAG, "握手失败：${t.message}")
            runCatching { sock.close() }
        }
    }

    private fun activate(sock: Socket) {
        client = sock
        out = sock.getOutputStream()
        peerText = peerTextOf(sock)
        synchronized(rxLock) { rxLen = 0 }
        outQueue.clear()
        droppedFrames = 0
        ready = true
        TcpCtrlBridge.attach(this)
        Log.i(TAG, "PC 已连入：$peerText")

        readerThread = Thread({ readerLoop(sock) }, "apx-tcp-reader").apply {
            isDaemon = true
            start()
        }
        writerThread = Thread({ writerLoop(sock) }, "apx-tcp-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun readerLoop(sock: Socket) {
        val ins = sock.getInputStream()
        val buf = ByteArray(4096)
        while (running.get() && !sock.isClosed) {
            val n = try {
                ins.read(buf)
            } catch (t: Throwable) {
                -1
            }
            if (n <= 0) break
            feed(buf, n)
            val ev = pump()
            if (ev and 1 != 0) sendControl(PONG)
            if (ev and 2 != 0) EventBus.post(ScreenOpenRequestEvent())
        }
        Log.i(TAG, "PC 连接已断开：$peerText")
        teardown(sock)
    }

    /** 单写者：把队列里的帧按序写出；写失败即判链路失效 */
    private fun writerLoop(sock: Socket) {
        val o = out ?: return
        while (running.get() && !sock.isClosed) {
            val frame = try {
                outQueue.poll(WRITER_IDLE_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            try {
                o.write(frame)
                o.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "控制帧写出失败，视为链路断开：${t.message}")
                break
            }
        }
        runCatching { sock.close() }
        teardown(sock)
    }

    private fun teardown(sock: Socket) {
        if (client === sock) {
            ready = false
            TcpCtrlBridge.detach()
            client = null
            out = null
            peerText = ""
            outQueue.clear()
        }
        runCatching { sock.close() }
    }

    // ————————————————————————————— 收流解析 —————————————————————————————
    // 控制面收的是 PC 的心跳 ping；按 APX1 帧长剥离，遇到非帧头字节即整体丢弃
    // 重新对齐（链路是可靠有序的，不需要重传/乱序处理）。

    private fun feed(data: ByteArray, n: Int) {
        synchronized(rxLock) {
            if (rxLen + n > rxBuf.size) {
                var cap = rxBuf.size * 2
                while (cap < rxLen + n) cap *= 2
                rxBuf = rxBuf.copyOf(cap)
            }
            System.arraycopy(data, 0, rxBuf, rxLen, n)
            rxLen += n
        }
    }

    /** @return 位图：bit0=收到 ping（需回 pong），bit1=收到打开副屏请求（0x05） */
    private fun pump(): Int {
        var flags = 0
        synchronized(rxLock) {
            var off = 0
            while (rxLen - off >= ApxFrame.HEADER_SIZE) {
                if (!ApxFrame.isMagic(rxBuf, off, rxLen)) {
                    off = rxLen
                    break
                }
                val payloadLen = ApxFrame.payloadLenAt(rxBuf, off)
                if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) {
                    off = rxLen
                    break
                }
                val total = ApxFrame.totalSize(payloadLen)
                if (rxLen - off < total) break
                if (ApxFrame.streamIdAt(rxBuf, off) == ApxFrame.STREAM_CONTROL &&
                    payloadLen >= 1
                ) {
                    when (rxBuf[off + ApxFrame.HEADER_SIZE]) {
                        'p'.code.toByte() -> flags = flags or 1
                        // 0x05 = PC 请求打开副屏（面板开副屏推流时下发）
                        0x05.toByte() -> flags = flags or 2
                        // 0x10 = PC 模块开关命令：body=[0x10, 模块索引, on]
                        0x10.toByte() -> if (payloadLen >= 3) {
                            val idx = rxBuf[off + ApxFrame.HEADER_SIZE + 1].toInt() and 0xFF
                            val on = rxBuf[off + ApxFrame.HEADER_SIZE + 2].toInt() != 0
                            moduleCommandListener?.invoke(idx, on)
                        }
                    }
                }
                off += total
            }
            if (off > 0) {
                System.arraycopy(rxBuf, off, rxBuf, 0, rxLen - off)
                rxLen -= off
            }
        }
        return flags
    }

    // ————————————————————————————— 发送 —————————————————————————————

    /**
     * 只做「组帧 + 入队」，**绝不在这里碰 socket**：调用方可能是 UI 线程。
     * @return 是否成功入队（true ≠ 已送达，但链路可用时很快会写出）
     */
    override fun sendControl(body: ByteArray): Boolean {
        if (!ready) return false
        val frame = synchronized(writeLock) {
            seq = (seq + 1) and 0x7FFFFFFF
            ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq)
        }
        if (outQueue.offer(frame)) return true
        // 队列满：丢最旧、保最新（手势跟手优先于历史帧的完整性）
        outQueue.poll()
        droppedFrames++
        return outQueue.offer(frame)
    }

    // ————————————————————————————— 工具 —————————————————————————————

    private fun readU32Le(ins: InputStream): Int {
        val b = ByteArray(4)
        if (!readFully(ins, b)) return -1
        var v = 0
        for (i in 0 until 4) v = v or ((b[i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun readFully(ins: InputStream, dst: ByteArray): Boolean {
        var got = 0
        while (got < dst.size) {
            val n = try {
                ins.read(dst, got, dst.size - got)
            } catch (t: Throwable) {
                -1
            }
            if (n <= 0) return false
            got += n
        }
        return true
    }

    companion object {
        private const val TAG = "TcpControlChannel"

        /** 手机侧监听端口（PC 面板与 `apxhost wireless` 默认连这里） */
        const val PORT = 9500

        /** 握手令牌上限（防对端发超大长度把内存撑爆） */
        private const val MAX_TOKEN = 256

        private const val HANDSHAKE_TIMEOUT_MS = 5_000

        /** 出站队列容量（约 4 秒的手势帧，正常负载远用不到） */
        private const val QUEUE_CAP = 256

        /** writer 空闲轮询间隔：兼顾 stop() 的响应速度与空转开销 */
        private const val WRITER_IDLE_MS = 200L

        /** 心跳回显：PC 端以载荷首字节 'p' 识别为 pong */
        private val PONG = "pong".toByteArray(Charsets.UTF_8)

        private fun peerTextOf(sock: Socket): String =
            sock.inetAddress?.hostAddress?.let { "$it:${sock.port}" } ?: "?"

        /** 本机第一个非回环 IPv4（展示用；取不到返回 null） */
        fun localIpv4(): String? = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> Collections.list(ni.inetAddresses) }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress
        } catch (t: Throwable) {
            null
        }
    }
}
