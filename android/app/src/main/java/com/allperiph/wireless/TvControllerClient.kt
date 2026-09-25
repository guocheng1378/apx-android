package com.allperiph.wireless

import com.allperiph.core.ApxFrame
import com.allperiph.core.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 手机端「TV 控制」客户端：连入 TV 服务端（[com.allperiph.tv.net.TcpControlServer]），
 * 发送与手机端同一套 APX1 控制帧（鼠标/触摸/键盘/多媒体）。
 *
 * 连链顺序（与 TV 服务端逐字节一致）：
 *   手机 → TV : u32 LE 长度 + UTF-8 令牌（空）
 *   手机 → TV : APX1 帧 body="ping"（1s 心跳，TV 回 pong）
 *   手机 → TV : APX1 控制帧（驱动 TV 界面）
 *
 * 与「手机被控」（[com.allperiph.controlled.ControlledService] 经 9511 服务端接收）方向相反、
 * 互不干扰：本类是手机作为控制端新开一条连向 TV / PC 的客户端连接，只写帧。
 */
class TvControllerClient(
    private val host: String,
    private val port: Int = PORT,
    private val token: String = "",
) {
    @Volatile
    var ready: Boolean = false
        private set

    private var sock: Socket? = null
    private var out: OutputStream? = null
    private val running = AtomicBoolean(false)
    private var seq = 0
    private val writeLock = Any()

    /** 反向剪贴板回调：被控手机剪贴板变化时回传文本（由 UI 注册，写入本机剪贴板） */
    @Volatile
    var onReverseClipboard: ((String) -> Unit)? = null

    private val rxLock = Any()
    private var rxBuf = ByteArray(4096)
    private var rxLen = 0

    /** @return 是否连上（连不上如实返回 false，UI 标红） */
    fun connect(): Boolean {
        if (running.get()) return ready
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)
            sock = s
            out = s.getOutputStream()
            // 握手：u32 LE 长度 + 令牌
            val tok = token.toByteArray(Charsets.UTF_8)
            val hdr = ByteArray(4)
            hdr[0] = (tok.size and 0xFF).toByte()
            hdr[1] = ((tok.size ushr 8) and 0xFF).toByte()
            hdr[2] = 0
            hdr[3] = 0
            out!!.write(hdr)
            if (tok.isNotEmpty()) out!!.write(tok)
            out!!.flush()
            running.set(true)
            ready = true
            synchronized(rxLock) { rxLen = 0 }
            thread(name = "tvctrl-reader") { readerLoop(s) }
            thread(name = "tvctrl-ping") { pingLoop() }
            Log.i("TvCtrl", "已连 TV $host:$port")
            true
        } catch (t: Throwable) {
            Log.e("TvCtrl", "连 TV 失败：${t.message}")
            runCatching { s_close() }
            false
        }
    }

    fun disconnect() {
        running.set(false)
        ready = false
        s_close()
    }

    private fun s_close() = runCatching { sock?.close() }.also { sock = null; out = null }

    private fun readerLoop(s: Socket) {
        val ins = s.getInputStream()
        val buf = ByteArray(4096)
        while (running.get() && !s.isClosed) {
            val n = try {
                ins.read(buf)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) break
            feed(buf, n)
            val bodies = pumpFrames()
            for (b in bodies) handleServerFrame(b)
        }
        ready = false
    }

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

    /** 从接收缓冲抽取 APX1 控制帧 body 列表（与 TV 端 pump 同解析） */
    private fun pumpFrames(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
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
                if (ApxFrame.streamIdAt(rxBuf, off) == ApxFrame.STREAM_CONTROL) {
                    val body = ApxFrame.bodyAt(rxBuf, off, payloadLen)
                    if (body != null) out.add(body)
                }
                off += total
            }
            if (off > 0) {
                System.arraycopy(rxBuf, off, rxBuf, 0, rxLen - off)
                rxLen -= off
            }
        }
        return out
    }

    /** 处理被控端回传的帧：pong 为心跳应答（忽略，避免与 TV 端形成 pong 互发循环）/ 0x21 反向剪贴板 */
    private fun handleServerFrame(body: ByteArray) {
        if (body.isEmpty()) return
        when (body[0].toInt() and 0xFF) {
            'p'.code -> { /* TV 对我方 ping 的应答，无需回发 */ }
            0x21 -> {
                val len = (body.getOrElse(1) { 0 }.toInt() and 0xFF) or
                    ((body.getOrElse(2) { 0 }.toInt() and 0xFF) shl 8)
                if (body.size >= 3 + len && len > 0) {
                    val text = String(body.copyOfRange(3, 3 + len), Charsets.UTF_8)
                    onReverseClipboard?.invoke(text)
                }
            }
        }
    }

    private fun pingLoop() {
        while (running.get()) {
            sendControl("ping".toByteArray(Charsets.UTF_8))
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** 发送任意控制面本体（含 cmd 字节）；供副屏关键帧请求等扩展帧使用 */
    fun sendControl(body: ByteArray): Boolean {
        if (!ready) return false
        val frame = synchronized(writeLock) {
            seq = (seq + 1) and 0x7FFFFFFF
            ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq)
        }
        return try {
            out?.write(frame)
            out?.flush()
            true
        } catch (t: Throwable) {
            Log.w("TvCtrl", "TV 控制帧写出失败：${t.message}")
            ready = false
            false
        }
    }

    // ————————————————————————————— 输入发送（供 UI 调用） —————————————————————————————

    /** 鼠标相对位移：buttons bit0=左键按下 */
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) =
        sendControl(byteArrayOf(0x01.toByte(), buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))

    /** 触摸绝对坐标（归一化 0..65535）；action: 0=down 1=up 2=move；buttons: 0/1=左 2=右 */
    fun touch(action: Int, buttons: Int, x: Int, y: Int) = sendControl(
        byteArrayOf(
            0x04.toByte(), action.toByte(), buttons.toByte(),
            (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(),
            (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
            0, 0,
        )
    )

    /** 键盘：HID usage(page 0x07)；down 发 usage，up 发空 */
    fun keyboard(usage: Int, down: Boolean) =
        if (down) sendControl(byteArrayOf(0x03.toByte(), 0, 0, usage.toByte()))
        else sendControl(byteArrayOf(0x03.toByte(), 0, 0, 0))

    /** 多媒体：16 位位图，bit 序见 TV 端 CONSUMER_MAP */
    fun consumer(bitmap: Int) =
        sendControl(byteArrayOf(0x02.toByte(), (bitmap and 0xFF).toByte(), ((bitmap ushr 8) and 0xFF).toByte()))

    /** 剪贴板文本：body=[0x20, len u16 LE, utf8…]，TV 端写入系统剪贴板并填入聚焦输入框 */
    fun sendClipboard(text: String): Boolean {
        if (text.isEmpty()) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 0xFFFF) return false
        val body = ByteArray(3 + bytes.size)
        body[0] = 0x20.toByte()
        body[1] = (bytes.size and 0xFF).toByte()
        body[2] = ((bytes.size ushr 8) and 0xFF).toByte()
        bytes.copyInto(body, 3)
        return sendControl(body)
    }

    companion object {
        /** TV 控制面端口（与 com.allperiph.tv.net.TcpControlServer.PORT 一致） */
        const val PORT = 9511
    }
}
