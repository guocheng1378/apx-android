package com.allperiph.wireless

import com.allperiph.core.ApxFrame
import com.allperiph.core.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 手机端「TV 控制」客户端：连入 TV 服务端，发送 APX1 控制帧。
 */
class TvControllerClient(
    private val host: String,
    private val port: Int = PORT,
    private val token: String = "",
) {
    @Volatile var ready: Boolean = false; private set
    private var sock: Socket? = null
    private var out: OutputStream? = null
    private val running = AtomicBoolean(false)
    private var seq = 0
    private val writeLock = Any()
    private val txQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var txThread: Thread? = null

    @Volatile var onReverseClipboard: ((String) -> Unit)? = null
    @Volatile var onRemoteInputRequest: ((String, String) -> Unit)? = null
    @Volatile var onRemoteInputText: ((String, Int) -> Unit)? = null
    @Volatile var onRemoteInputDone: (() -> Unit)? = null

    private val rxLock = Any()
    private var rxBuf = ByteArray(4096)
    private var rxLen = 0

    fun connect(): Boolean {
        if (running.get()) return ready
        running.set(true)
        val ok = openSocket()
        if (ok) thread(name = "tvctrl-ping") { pingLoop() } else running.set(false)
        return ok
    }

    /** 建立 socket + 握手 + 启动 reader（connect 与自动重连共用） */
    private fun openSocket(): Boolean = try {
        val s = Socket()
        s.tcpNoDelay = true; s.connect(InetSocketAddress(host, port), 5000)
        sock = s; out = s.getOutputStream()
        val tok = token.toByteArray(Charsets.UTF_8)
        val hdr = ByteArray(4); hdr[0] = (tok.size and 0xFF).toByte(); hdr[1] = ((tok.size ushr 8) and 0xFF).toByte(); hdr[2] = 0; hdr[3] = 0
        out!!.write(hdr); if (tok.isNotEmpty()) out!!.write(tok); out!!.flush()
        ready = true; synchronized(rxLock) { rxLen = 0 }
        thread(name = "tvctrl-reader") { readerLoop(s) }
        startTx()
        Log.i("TvCtrl", "已连 TV $host:$port")
        true
    } catch (t: Throwable) { Log.e("TvCtrl", "连 TV 失败：${t.message}"); runCatching { s_close() }; false }

    fun disconnect() { running.set(false); ready = false; runCatching { txQueue.clear() }; runCatching { txThread?.interrupt() }; txThread = null; s_close() }
    private fun s_close() = runCatching { sock?.close() }.also { sock = null; out = null }

    private fun readerLoop(s: Socket) {
        val ins = s.getInputStream(); val buf = ByteArray(4096)
        while (running.get() && !s.isClosed) {
            val n = try { ins.read(buf) } catch (_: Throwable) { -1 }
            if (n <= 0) break
            feed(buf, n)
            for (b in pumpFrames()) handleServerFrame(b)
        }
        ready = false
        // v184：被动断线（对端重启/网络抖动）自动重连 —— 此前断开后 ready=false 且
        // connect() 被 running 短路，副屏/触控板触摸全部静默丢失，只能手动重选设备。
        // 用户主动 disconnect() 会先置 running=false，这里自然不进入。
        if (running.get()) {
            Log.w("TvCtrl", "连接断开，启动自动重连 $host:$port")
            thread(name = "tvctrl-reconn") {
                while (running.get() && !ready) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                    if (!running.get() || ready) break
                    if (openSocket()) break
                }
            }
        }
    }

    private fun feed(data: ByteArray, n: Int) {
        synchronized(rxLock) {
            if (rxLen + n > rxBuf.size) { var c = rxBuf.size * 2; while (c < rxLen + n) c *= 2; rxBuf = rxBuf.copyOf(c) }
            System.arraycopy(data, 0, rxBuf, rxLen, n); rxLen += n
        }
    }

    private fun pumpFrames(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        synchronized(rxLock) {
            var off = 0
            while (rxLen - off >= ApxFrame.HEADER_SIZE) {
                if (!ApxFrame.isMagic(rxBuf, off, rxLen)) { off = rxLen; break }
                val payloadLen = ApxFrame.payloadLenAt(rxBuf, off)
                if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) { off = rxLen; break }
                val total = ApxFrame.totalSize(payloadLen)
                if (rxLen - off < total) break
                if (ApxFrame.streamIdAt(rxBuf, off) == ApxFrame.STREAM_CONTROL) {
                    val body = ApxFrame.bodyAt(rxBuf, off, payloadLen)
                    if (body != null) out.add(body)
                }
                off += total
            }
            if (off > 0) { System.arraycopy(rxBuf, off, rxBuf, 0, rxLen - off); rxLen -= off }
        }
        return out
    }

    private fun handleServerFrame(body: ByteArray) {
        if (body.isEmpty()) return
        when (body[0].toInt() and 0xFF) {
            'p'.code -> { }
            0x21 -> {
                val len = (body.getOrElse(1) { 0 }.toInt() and 0xFF) or ((body.getOrElse(2) { 0 }.toInt() and 0xFF) shl 8)
                if (body.size >= 3 + len && len > 0) { onReverseClipboard?.invoke(String(body.copyOfRange(3, 3 + len), Charsets.UTF_8)) }
            }
            0x25 -> {
                // REQUEST_INPUT: [0x25, hintLen, hint...]
                if (body.size < 2) return
                val hintLen = body[1].toInt() and 0xFF
                if (body.size < 2 + hintLen) return
                val hint = String(body.copyOfRange(2, 2 + hintLen), Charsets.UTF_8)
                onRemoteInputRequest?.invoke(host, hint)
            }
            0x26 -> {
                // INPUT_TEXT: [0x26, flags, textLen, text...]
                if (body.size < 4) return
                val flags = body[1].toInt() and 0xFF
                val textLen = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
                if (body.size < 4 + textLen) return
                val text = String(body.copyOfRange(4, 4 + textLen), Charsets.UTF_8)
                onRemoteInputText?.invoke(text, flags)
            }
            0x27 -> { onRemoteInputDone?.invoke() }
        }
    }

    private fun pingLoop() {
        while (running.get()) {
            sendControl("ping".toByteArray(Charsets.UTF_8))
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
    }

    private fun startTx() {
        if (txThread?.isAlive == true) return
        txThread = thread(name = "tvctrl-tx") {
            while (running.get()) {
                val frame = try { txQueue.take() } catch (_: InterruptedException) { break }
                try { out?.write(frame); out?.flush() } catch (t: Throwable) { Log.w("TvCtrl", "写出失败：${t.message}"); ready = false }
            }
        }
    }

    fun sendControl(body: ByteArray): Boolean {
        if (!ready) return false
        val frame = synchronized(writeLock) { seq = (seq + 1) and 0x7FFFFFFF; ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq) }
        while (txQueue.size > 512) txQueue.poll()
        return txQueue.offer(frame)
    }

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) = sendControl(byteArrayOf(0x01.toByte(), buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))
    fun touch(action: Int, buttons: Int, x: Int, y: Int) = sendControl(byteArrayOf(0x04.toByte(), action.toByte(), buttons.toByte(), (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(), (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(), 0, 0))
    fun keyboard(usage: Int, down: Boolean, mod: Int = 0) = if (down) sendControl(byteArrayOf(0x03.toByte(), mod.toByte(), 0, usage.toByte())) else sendControl(byteArrayOf(0x03.toByte(), 0, 0, 0))
    fun consumer(bitmap: Int) = sendControl(byteArrayOf(0x02.toByte(), (bitmap and 0xFF).toByte(), ((bitmap ushr 8) and 0xFF).toByte()))
    fun power(action: Int) = sendControl(byteArrayOf(0x22.toByte(), action.toByte()))
    fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) = sendControl(byteArrayOf(0x07.toByte(), (buttons and 0xFF).toByte(), ((buttons ushr 8) and 0xFF).toByte(), x.toByte(), y.toByte(), rx.toByte(), ry.toByte()))
    fun sendClipboard(text: String): Boolean {
        if (text.isEmpty()) return false
        val bytes = text.toByteArray(Charsets.UTF_8); if (bytes.size > 0xFFFF) return false
        val body = ByteArray(3 + bytes.size); body[0] = 0x20.toByte(); body[1] = (bytes.size and 0xFF).toByte(); body[2] = ((bytes.size ushr 8) and 0xFF).toByte(); bytes.copyInto(body, 3)
        return sendControl(body)
    }

    // 远程输入发送
    fun requestInput(hint: String) {
        val hintBytes = hint.toByteArray(Charsets.UTF_8)
        // 格式：[0x25, hintLen, hint...] — 与接收端 TcpControlServer onRequestInput 一致
        val body = ByteArray(1 + 1 + hintBytes.size)
        body[0] = ApxFrame.INPUT_REQUEST.toByte()
        body[1] = hintBytes.size.toByte()
        hintBytes.copyInto(body, 2)
        sendControl(body)
    }

    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val body = ByteArray(1 + 1 + 2 + textBytes.size)
        body[0] = ApxFrame.INPUT_TEXT.toByte(); body[1] = flags.toByte()
        body[2] = (textBytes.size and 0xFF).toByte(); body[3] = ((textBytes.size ushr 8) and 0xFF).toByte()
        textBytes.copyInto(body, 4)
        sendControl(body)
    }

    fun sendInputDone() { sendControl(byteArrayOf(ApxFrame.INPUT_DONE.toByte())) }

    companion object { const val PORT = 9511 }
}