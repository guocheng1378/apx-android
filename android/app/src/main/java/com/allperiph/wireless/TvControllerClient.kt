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

    /**
     * 发送队列 + 发送线程。
     * 为什么必须有：控制帧很多是**主线程**产生的（触控板手势走 `onTouchEvent` → 分发 → write），
     * 而 Android 禁止主线程做网络 I/O，会抛 `NetworkOnMainThreadException`（message 为 null）
     * ——异常被吞后 `ready` 被翻成 false，表现为「连上却完全控不了」。
     * 统一入队、由后台线程写出，顺带把 UI 从 socket 写阻塞里解放出来。
     */
    private val txQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var txThread: Thread? = null

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
            startTx()
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
        runCatching { txQueue.clear() }
        runCatching { txThread?.interrupt() }
        txThread = null
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

    /** 发送线程：串行写出队列里的帧（**绝不在主线程做 socket I/O**） */
    private fun startTx() {
        if (txThread?.isAlive == true) return
        txThread = thread(name = "tvctrl-tx") {
            while (running.get()) {
                val frame = try {
                    txQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    out?.write(frame)
                    out?.flush()
                } catch (t: Throwable) {
                    Log.w("TvCtrl", "TV 控制帧写出失败：${t.message}")
                    ready = false
                }
            }
        }
    }

    /** 发送任意控制面本体（含 cmd 字节）；只入队，实际写出在发送线程 */
    fun sendControl(body: ByteArray): Boolean {
        if (!ready) return false
        val frame = synchronized(writeLock) {
            seq = (seq + 1) and 0x7FFFFFFF
            ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq)
        }
        while (txQueue.size > 512) txQueue.poll()   // 防积压（相对位移帧可安全丢弃）
        return txQueue.offer(frame)
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

    /**
     * 键盘：HID usage(page 0x07)；down 发 usage，up 发空。
     *
     * [mod] 是 HID 修饰键位图（Ctrl=1 / Shift=2 / Alt=4 / Win=8；右修饰为高位）—— **必须与
     * usage 同帧发出**：受控端是按「上一帧 vs 这一帧的位差」按下/松开修饰键的（PC 端
     * `injectKeyboard` 就是这么实现的）。以前这里把 mod 写死成 0，于是「复制 / 粘贴 / 撤销 /
     * 切窗(Alt+Tab) / 桌面(Win+D)」到了对端只剩一个裸字母或干脆没反应 —— 真机症状即
     * 「快捷键不能用」。释放帧统一为 mod=0、keys=0，一次把修饰键与按键全松开。
     */
    fun keyboard(usage: Int, down: Boolean, mod: Int = 0) =
        if (down) sendControl(byteArrayOf(0x03.toByte(), mod.toByte(), 0, usage.toByte()))
        else sendControl(byteArrayOf(0x03.toByte(), 0, 0, 0))

    /** 多媒体：16 位位图，bit 序见 TV 端 CONSUMER_MAP */
    fun consumer(bitmap: Int) =
        sendControl(byteArrayOf(0x02.toByte(), (bitmap and 0xFF).toByte(), ((bitmap ushr 8) and 0xFF).toByte()))

    /**
     * 电源动作：`0`=关机 `1`=重启 `2`=待机（opcode 0x22）。
     *
     * 「待机」走软电源键；**关机 / 重启需要被控端有 root**（`reboot -p` / `reboot`），
     * 没有 root 时被控端会如实退回软电源键，不会假装关掉。
     */
    fun power(action: Int) = sendControl(byteArrayOf(0x22.toByte(), action.toByte()))

    /** 手柄：复用 USB HID 的 7 字节布局（buttons u16 LE + 左/右摇杆 4×i8 轴）；cmd=0x07 与 TV/PC 服务端一致 */
    fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) =
        sendControl(byteArrayOf(
            0x07.toByte(),
            (buttons and 0xFF).toByte(), ((buttons ushr 8) and 0xFF).toByte(),
            x.toByte(), y.toByte(), rx.toByte(), ry.toByte()
        ))

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
