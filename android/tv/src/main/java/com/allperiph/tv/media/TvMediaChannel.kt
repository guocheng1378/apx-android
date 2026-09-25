package com.allperiph.tv.media

import com.allperiph.tv.core.ApxFrame
import com.allperiph.tv.core.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TV 端 **Wi‑Fi 媒体通道**（TCP 9502，手机端 `TcpMediaChannel` 的同类实现）。
 *
 * 与控制面 9511 并列：控制面是单对端的小帧（输入），媒体是 10Mbps 级的大流量，
 * 分两条连接才不会让视频积压把输入延迟拖爆（关副屏也不影响键盘鼠标）。
 *
 * ## 承载的流（下行，PC → TV）
 * ```
 * streamId 0  video  副屏画面（H.264 / HEVC / AV1 码流） → [TvRenderer]
 * streamId 1  audio  音箱 PCM（48kHz / 16bit / 立体声）    → [TvSpeaker]
 * ```
 * TV 不做上行（没有麦克风/摄像头上行需求），因此本端只收不发。
 *
 * 握手与 9511 控制面**逐字节相同**：u32 LE 长度 + UTF-8 令牌；随后是 APX1 帧。
 */
class TvMediaChannel(private val port: Int = MEDIA_PORT) {

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var client: Socket? = null

    @Volatile
    private var peerText: String = ""

    @Volatile
    var ready: Boolean = false
        private set

    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var readerThread: Thread? = null

    private val rxLock = Any()
    private var rxBuf = ByteArray(256 * 1024)
    private var rxLen = 0

    fun peer(): String = peerText

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            server = ss
            running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "apxtv-media-accept").apply {
                isDaemon = true
                start()
            }
            Log.i("媒体通道监听 *:$port（副屏 / 音箱）")
            true
        } catch (t: Throwable) {
            Log.e("媒体端口 $port 监听失败：${t.message}")
            runCatching { server?.close() }
            server = null
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { client?.close() }
        runCatching { server?.close() }
        server = null
        client = null
        peerText = ""
        ready = false
        acceptThread = null
        readerThread = null
        synchronized(rxLock) { rxLen = 0 }
    }

    fun statusText(): String =
        if (ready) "媒体已连接 $peerText" else "媒体：等 PC 连入 $port"

    // ————————————————————————————— 接受 —————————————————————————————

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (_: Throwable) {
                if (!running.get()) break
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            // ★ 后来者接管（与控制面同一策略）：旧连接可能是僵尸（PC 强退/换网收不到 FIN），
            //   此时若拒绝新连接，TV 就再也收不到画面，只能重开 App。
            val old = client
            if (old != null && old !== sock) {
                Log.w("媒体新连接接管，断开旧的 $peerText")
                ready = false
                runCatching { old.close() }
            }
            Thread({ handshake(sock) }, "apxtv-media-handshake").apply {
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
            val len = readU32Le(ins)
            if (len < 0 || len > MAX_TOKEN) {
                runCatching { sock.close() }
                return
            }
            val buf = ByteArray(len)
            if (!readFully(ins, buf)) {
                runCatching { sock.close() }
                return
            }
            sock.soTimeout = READ_TIMEOUT_MS
            activate(sock)
        } catch (t: Throwable) {
            Log.w("媒体握手失败：${t.message}")
            runCatching { sock.close() }
        }
    }

    private fun activate(sock: Socket) {
        client = sock
        peerText = "${sock.inetAddress?.hostAddress}:${sock.port}"
        synchronized(rxLock) { rxLen = 0 }
        ready = true
        Log.i("PC 已连入媒体通道：$peerText")
        readerThread = Thread({ readerLoop(sock) }, "apxtv-media-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readerLoop(sock: Socket) {
        val ins = sock.getInputStream()
        val buf = ByteArray(64 * 1024)
        while (running.get() && !sock.isClosed && client === sock) {
            val n = try {
                ins.read(buf)
            } catch (t: Throwable) {
                if (t is java.net.SocketTimeoutException) continue   // 读超时 ≠ 断线
                -1
            }
            if (n <= 0) break
            feed(buf, n)
            // 解析在锁内、分发在锁外（解码/播放可能较慢，别握着锁做）
            for (f in pump()) dispatch(f)
        }
        Log.i("PC 媒体连接已断开：$peerText")
        teardown(sock)
    }

    private fun teardown(sock: Socket) {
        if (client === sock) {
            ready = false
            client = null
            peerText = ""
            synchronized(rxLock) { rxLen = 0 }
        }
        runCatching { sock.close() }
    }

    // ————————————————————————————— 收流解析 —————————————————————————————

    private fun feed(data: ByteArray, n: Int) {
        synchronized(rxLock) {
            if (rxLen + n > rxBuf.size) {
                var cap = rxBuf.size
                while (cap < rxLen + n) cap *= 2
                rxBuf = rxBuf.copyOf(cap)
            }
            System.arraycopy(data, 0, rxBuf, rxLen, n)
            rxLen += n
        }
    }

    private class Parsed(val streamId: Int, val body: ByteArray)

    private fun pump(): List<Parsed> {
        var outList: MutableList<Parsed>? = null
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
                val body = ApxFrame.bodyAt(rxBuf, off, payloadLen)
                if (body != null) {
                    val list = outList ?: ArrayList<Parsed>(4).also { outList = it }
                    list.add(Parsed(ApxFrame.streamIdAt(rxBuf, off), body))
                }
                off += total
            }
            if (off > 0) {
                System.arraycopy(rxBuf, off, rxBuf, 0, rxLen - off)
                rxLen -= off
            }
        }
        return outList ?: emptyList()
    }

    private fun dispatch(f: Parsed) {
        when (f.streamId) {
            STREAM_VIDEO -> TvRenderer.submit(f.body)
            STREAM_AUDIO -> TvSpeaker.submit(f.body)
            else -> {}    // 其余（控制/保留）本端不消费
        }
    }

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
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) return false
            got += n
        }
        return true
    }

    companion object {
        /** 媒体端口（PC 侧 apxdesktop 用同一常量连入） */
        const val MEDIA_PORT = 9502

        /** 与手机端 ApxFrame.STREAM_* 一致的流编号 */
        const val STREAM_VIDEO = 0
        const val STREAM_AUDIO = 1

        private const val MAX_TOKEN = 256
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 3_000
    }
}
