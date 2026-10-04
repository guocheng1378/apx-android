package com.allperiph.shared.media

import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Wi‑Fi **媒体通道**：与控制面（9511）并列的第二条连接，承载大流量流。
 *
 * v1.8：从手机端 `com.allperiph.wireless.TcpMediaChannel` 与 TV 端
 * `com.allperiph.tv.media.TvMediaChannel` 提取到共享模块。两端收流/协议部分逐字节相同，
 * 差异仅在「分发去哪里」与「是否做上行」—— 这两点由构造回调参数化，不再硬编码：
 * - [onFrame]：收到的帧往哪分发（手机端交给 `ApxStreams.dispatch`，TV 端直接喂 `Renderer` / `TvSpeaker`）。
 * - [onReady] / [onDisconnect]：连接建立 / 断开时的钩子（手机端借此挂 `MediaOut` 上下行出口）。
 * - [enableUpLink]：是否启用上行（手机端有麦克风/触摸上行 = true；TV 端只收不发 = false）。
 *
 * ## 为什么媒体与控制面分两条连接（而不是一条多路复用）
 * 控制面（9511）是**单对端语义**。把媒体（10Mbps 级视频 + 音频）
 * 混进同一条连接，会：① 让视频的拥塞/积压直接卡住输入延迟（输入是 60 次/秒的小帧）；
 * ② 迫使重构那条链路。因此媒体单独占一条连接与一个端口，
 * 两者可独立启停 —— 关副屏不影响键盘鼠标。
 *
 * ## 端口约定（PC 侧常量需与此一致）
 * ```
 * 9511  TCP  控制面（统一协议；手机可被控 / 控设备）
 * 9501  UDP  信标广播（APX1TV）
 * 9502  TCP  媒体（手机 / TV 做服务端，PC 连入）  —— 本类
 * ```
 *
 * ## 背压策略（重要）
 * 上行只有**一个** writer 线程（socket 写必须单写者才保序）。麦克风（~190KB/s、
 * 时延敏感）独占队列，丢帧计数分开统计，UI 如实展示，不静默。
 *
 * @param onFrame 收到一帧（已剥帧头/CRC）时分发；在锁外调用，允许较慢。
 * @param port 监听端口，默认 9502。
 * @param token 握手令牌；非空时校验，空则跳过（TV 端不校验）。
 * @param onReady 连接建立并通过握手鉴权后回调（手机端借此 `MediaOut.attach`）。
 * @param onDisconnect 连接断开时回调（手机端借此 `MediaOut.detach`）。
 * @param enableUpLink 是否启用上行（手机端 true，TV 端 false）。
 */
class MediaChannel(
    private val onFrame: (streamId: Int, flags: Int, seq: Int, body: ByteArray) -> Unit,
    private val port: Int = MEDIA_PORT,
    private val token: String = "",
    private val onReady: ((MediaChannel) -> Unit)? = null,
    private val onDisconnect: (() -> Unit)? = null,
    private val enableUpLink: Boolean = false,
) {

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var client: Socket? = null

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var peerText: String = ""

    @Volatile
    var ready: Boolean = false
        private set

    fun peer(): String = peerText

    /** 队列满导致的丢帧（保新弃旧） */
    val droppedFrames = AtomicLong(0)

    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    private val writeLock = Any()
    private var seq = 0

    /** 待发送帧（已含帧头与 CRC）。约 2 秒的音频缓冲 */
    private val outQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)

    private val rxLock = Any()
    private var rxBuf = ByteArray(256 * 1024)
    private var rxLen = 0

    // ————————————————————————————— 生命周期 —————————————————————————————

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss
            running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "apx-media-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Wi‑Fi 媒体通道监听 *:$port")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "媒体端口 $port 监听失败", t)
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
        onDisconnect?.invoke()
        acceptThread = null
        readerThread = null
        writerThread = null
        synchronized(rxLock) { rxLen = 0 }
    }

    fun statusText(): String = when {
        ready -> {
            val d = droppedFrames.get()
            val tail = buildString {
                if (d > 0) append(" · 丢帧 $d")
            }
            "已连接 $peerText$tail"
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
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            // 单对端：媒体流对时序敏感，多对端会互相抢带宽。
            // 接管**必须等到握手鉴权通过之后**（见 activate）—— 旧实现在这里就踢掉
            // 在用连接，于是任何连接（令牌错误、来路不明）都能顶掉正在工作的链路：
            // 既是一个被动的拒绝服务面，用户也会看到"副屏无故断开"。
            Thread({ handshake(sock) }, "apx-media-handshake").apply {
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
            // 与 9511 控制面/旧 9500 控制通道逐字节相同的握手：u32 LE 长度 + UTF-8 令牌
            val len = readU32Le(ins)
            if (len < 0 || len > MAX_TOKEN) {
                Log.w(TAG, "媒体握手长度非法：$len")
                runCatching { sock.close() }
                return
            }
            val buf = ByteArray(len)
            if (!readFully(ins, buf)) {
                runCatching { sock.close() }
                return
            }
            if (token.isNotEmpty() && String(buf, Charsets.UTF_8) != token) {
                Log.w(TAG, "媒体通道令牌不匹配，拒绝")
                runCatching { sock.close() }
                return
            }
            // 读不再永久阻塞。PC 只在触摸/推流时才发帧，静默超时是常态 →
            // 超时后 continue 继续等；真正半开（PC 消失）也会周期性醒来检查
            // sock.isClosed，不再无限挂死。
            sock.soTimeout = IDLE_READ_TIMEOUT_MS
            activate(sock)
        } catch (t: Throwable) {
            Log.w(TAG, "媒体握手失败：${t.message}")
            runCatching { sock.close() }
        }
    }

    private fun activate(sock: Socket) {
        // 握手鉴权已通过，**此时**才接管在用连接（踢旧连接让位给新连接）。
        // 旧连接若是 TCP 半开僵尸态（PC 已消失而本地写未失败），这一步能立刻让位，
        // 不会像更早的实现那样把 PC 的重连一直拒掉。
        val prev = client
        if (prev != null && prev !== sock) {
            Log.w(TAG, "媒体通道新连接接管，断开旧连接 $peerText")
            ready = false
            client = null
            out = null
            outQueue.clear()
            // 旧 reader/writer 会因 socket 关闭退出；teardown 的 client === sock 守卫
            // 保证它们不会误伤刚建立的新连接。
            runCatching { prev.close() }
        }
        client = sock
        out = sock.getOutputStream()
        peerText = "${sock.inetAddress?.hostAddress}:${sock.port}"
        synchronized(rxLock) { rxLen = 0 }
        outQueue.clear()
        droppedFrames.set(0)
        ready = true
        onReady?.invoke(this)
        Log.i(TAG, "PC 已连入媒体通道：$peerText")

        readerThread = Thread({ readerLoop(sock) }, "apx-media-reader").apply {
            isDaemon = true
            start()
        }
        if (enableUpLink) {
            writerThread = Thread({ writerLoop(sock) }, "apx-media-writer").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readerLoop(sock: Socket) {
        val ins = sock.getInputStream()
        // 收流缓冲开大一点：视频帧单分片可达 256KiB，小缓冲会把 recv 次数打上去
        val buf = ByteArray(64 * 1024)
        while (running.get() && !sock.isClosed && client === sock) {
            val n = try {
                ins.read(buf)
            } catch (t: java.net.SocketTimeoutException) {
                continue   // 空闲超时：PC 没发帧是常态，继续等
            } catch (t: Throwable) {
                -1
            }
            if (n <= 0) break
            feed(buf, n)
            // 关键：解析在锁内、**分发在锁外** —— 解码/播放回调可能较慢，
            // 不能握着 rxLock 做，否则整条连接的收流都被拖住。
            val frames = pump()
            for (f in frames) onFrame(f.streamId, f.flags, f.seq, f.body)
        }
        Log.i(TAG, "PC 媒体连接已断开：$peerText")
        teardown(sock)
    }

    private fun writerLoop(sock: Socket) {
        val o = out ?: return
        while (running.get() && !sock.isClosed) {
            // fix: 批量 flush，避免队列积压时每帧一次系统调用
            var frame = outQueue.poll()
            while (frame != null) {
                try {
                    o.write(frame)
                } catch (t: Throwable) {
                    Log.w(TAG, "媒体帧写出失败，视为链路断开：${t.message}")
                    runCatching { sock.close() }
                    teardown(sock)
                    return
                }
                frame = outQueue.poll()
            }
            // 队列排空后统一 flush
            try {
                o.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "媒体帧 flush 失败，视为链路断开：${t.message}")
                runCatching { sock.close() }
                teardown(sock)
                return
            }
            // 队列皆空：短暂等待，避免忙轮询
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                break
            }
        }
        runCatching { sock.close() }
        teardown(sock)
    }

    private fun teardown(sock: Socket) {
        if (client === sock) {
            ready = false
            onDisconnect?.invoke()
            // **这里不能 clear 流订阅者**。
            // 媒体连接断开 ≠ 模块停止（副屏 / 音箱开关可能还开着），
            // 旧实现在这里把所有订阅者一把抹掉，而重连后没有任何人会重新注册 ——
            // PC 端重发的音频/视频帧于是全部静默丢弃。用户症状：音箱用着用着没声，
            // 必须再点一次开关（点开关会走模块 start() 重新注册）。
            // 订阅者的生命周期由模块自己负责（start 注册 / stop 反注册）。
            client = null
            out = null
            peerText = ""
            outQueue.clear()
            synchronized(rxLock) { rxLen = 0 }
        }
        runCatching { sock.close() }
    }

    // ————————————————————————————— 收流解析 —————————————————————————————

    private fun feed(data: ByteArray, n: Int) {
        synchronized(rxLock) {
            // 给接收缓冲扩容加上限（8MB），避免恶意客户端 flood 垃圾数据
            // 导致 rxBuf 无限扩容 → OOM 崩溃。正常 APX 帧最大 ~4MB，8MB 足够容纳
            // 粘包场景下的多帧拼接。
            val need = rxLen + n
            if (need > MAX_RX_BUF) {
                throw IllegalStateException("媒体通道接收缓冲溢出（${need}B > ${MAX_RX_BUF}B）")
            }
            if (need > rxBuf.size) {
                var cap = rxBuf.size
                while (cap < need) cap *= 2
                if (cap > MAX_RX_BUF) cap = MAX_RX_BUF
                rxBuf = rxBuf.copyOf(cap)
            }
            System.arraycopy(data, 0, rxBuf, rxLen, n)
            rxLen += n
        }
    }

    /** 已解析出的帧（锁外分发） */
    private class Parsed(
        val streamId: Int,
        val flags: Int,
        val seq: Int,
        val body: ByteArray,
    )

    private fun pump(): List<Parsed> {
        var outList: MutableList<Parsed>? = null
        synchronized(rxLock) {
            var off = 0
            while (rxLen - off >= ApxFrame.HEADER_SIZE) {
                // magic / 长度异常改为**逐字节重同步**（原 off = rxLen 一次错位就会把
                // 后续所有合法帧一起丢掉）；并补上尾部 CRC32 校验 —— 坏帧直接喂解码器
                // 会花屏/爆音。PC 侧同款失配早已是逐字节前进，这里与它对齐。
                if (!ApxFrame.isMagic(rxBuf, off, rxLen)) { off++; continue }
                val payloadLen = ApxFrame.payloadLenAt(rxBuf, off)
                if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) { off++; continue }
                val total = ApxFrame.totalSize(payloadLen)
                if (rxLen - off < total) break
                if (!ApxFrame.verify(rxBuf, off, payloadLen)) { off += total; continue }
                val body = ApxFrame.bodyAt(rxBuf, off, payloadLen)
                if (body != null) {
                    (outList ?: ArrayList<Parsed>(4).also { outList = it }).add(
                        Parsed(
                            ApxFrame.streamIdAt(rxBuf, off),
                            ApxFrame.flagsAt(rxBuf, off),
                            ApxFrame.seqAt(rxBuf, off),
                            body,
                        )
                    )
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

    // ————————————————————————————— 发送（上行，仅 enableUpLink 时） —————————————————————————————

    /**
     * 上行发送：组帧 + 入队（不碰 socket，由 writerLoop 单写者线程写出）。
     * 未启用上行（[enableUpLink] = false）或未连入时返回 false。
     */
    fun send(streamId: Int, body: ByteArray, flags: Int): Boolean {
        if (!ready || !enableUpLink) return false

        val frame = synchronized(writeLock) {
            seq = (seq + 1) and 0x7FFFFFFF
            ApxFrame.build(streamId, body, seq, flags)
        }
        if (outQueue.offer(frame)) return true
        outQueue.poll()                 // 保新弃旧
        droppedFrames.incrementAndGet()
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
        private const val TAG = "MediaChannel"

        /** 媒体端口（PC 侧 `apxdesktop` 用同一常量连入） */
        const val MEDIA_PORT = 9502

        private const val MAX_TOKEN = 256
        private const val HANDSHAKE_TIMEOUT_MS = 5_000

        /** 读空闲超时：PC 只在触摸/推流时才发帧，静默是常态；超时仅用于周期醒来检查连接，不清连接 */
        private const val IDLE_READ_TIMEOUT_MS = 20_000
        private const val QUEUE_CAP = 256

        // 接收缓冲上限：APX 单帧最大 ~4MB，8MB 足够容纳粘包的多帧拼接，同时防止 OOM
        private const val MAX_RX_BUF = 8 * 1024 * 1024
    }
}
