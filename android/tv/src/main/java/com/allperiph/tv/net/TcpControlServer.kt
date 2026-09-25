package com.allperiph.tv.net

import android.os.Handler
import android.os.Looper
import com.allperiph.tv.core.ApxFrame
import com.allperiph.tv.core.Log
import com.allperiph.tv.core.TvInjector
import com.allperiph.tv.ui.TvInputDispatcher
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * APX TV 控制面**服务端**：TV 开 ServerSocket 监听，手机作为客户端连入并发控制帧。
 * 协议与手机端统一控制面（[com.allperiph.wireless.TvControllerClient] 客户端 /
 * [com.allperiph.controlled.ControlledService] 服务端，端口 9511）逐字节一致（同款握手 + APX1 帧）：
 * ```
 * 手机 → TV : u32 LE 长度 + UTF-8 令牌（无回执）
 * 手机 → TV : APX1 帧 body="ping"（1s 心跳）
 * TV   → 手机: APX1 帧 body="pong"（回显）
 * 手机 → TV : APX1 控制帧（鼠标/键盘/触摸/多媒体）—— 解析后驱动 TV 界面
 * ```
 * 与手机端差异：手机端作为服务端时只处理 ping/0x05/0x10；TV 端要**完整消化**
 * 0x01 鼠标 / 0x02 多媒体 / 0x03 键盘 / 0x04 触摸，并把事件转给 [TvInputDispatcher]。
 */
class TcpControlServer(
    private val port: Int = PORT,
    private val token: String = "",
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

    /**
     * 链路状态变化回调（**主线程**调用）：`connected` + 对端 IP。
     * 前台服务用它刷新通知，并把对端 IP 记下来供信标做单播兜底
     * （不少 AP / Mesh 会丢「Wi‑Fi → 有线」的广播，只发广播时 PC 端永远「正在发现」）。
     */
    @Volatile
    var onPeerChanged: ((Boolean, String) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val writeLock = Any()
    private var seq = 0

    private val outQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)

    private val rxLock = Any()
    private var rxBuf = ByteArray(4096)
    private var rxLen = 0

    /** 键盘按下态（HID usage 集合），用于构造 key-up 边沿 */
    private val pressedKeys = HashSet<Int>()

    /** 鼠标左键上一帧状态，用于检测按下边沿 */
    private var lastButtons = 0

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss
            running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "apxtv-accept").apply {
                isDaemon = true
                start()
            }
            Log.i("控制面监听 *:$port（本机 ${localIpv4() ?: "未知"}）")
            true
        } catch (t: Throwable) {
            Log.e("监听 $port 失败", t)
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
        acceptThread = null
        readerThread = null
        writerThread = null
    }

    fun statusText(): String = if (ready) "已连接 $peerText" else "监听 $port · 等待手机连入"

    /** 当前连入的对端 IP（无连接为 null）。信标用它做单播兜底，见 [WirelessBeacon]。 */
    fun currentPeerHost(): String? = client?.inetAddress?.hostAddress

    // ————————————————————————————— 接受 —————————————————————————————

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
            // ★ 「后来者接管」，**不要拒绝新连接**：旧连接可能是僵尸 —— 对端被强杀 / 换网时收不到
            //   FIN，收流线程会一直阻塞在 read 上（TCP 黑洞），ready 永远是 true。
            //   此时若把新连接 close 掉，手机端就是「TCP 连上、却一行都进不来」，只能重启 TV 应用。
            //   这里关掉旧 socket 让它的读写线程立刻醒来退出；收尾由旧收流线程自己完成
            //   （见 readerLoop / teardown 的 `client === sock` 判定），不会互相踩。
            val old = client
            if (old != null && old !== sock) {
                Log.w("新连接接管，断开旧连接 $peerText")
                ready = false
                runCatching { old.close() }
            }
            Thread({ handshake(sock) }, "apxtv-handshake").apply {
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
                Log.w("握手长度非法：$len")
                runCatching { sock.close() }
                return
            }
            if (len > 0) {
                val buf = ByteArray(len)
                if (!readFully(ins, buf)) {
                    runCatching { sock.close() }
                    return
                }
                if (token.isNotEmpty() && String(buf, Charsets.UTF_8) != token) {
                    Log.w("令牌不匹配，拒绝 ${peerTextOf(sock)}")
                    runCatching { sock.close() }
                    return
                }
            }
            sock.soTimeout = READ_TIMEOUT_MS
            activate(sock)
        } catch (t: Throwable) {
            Log.w("握手失败：${t.message}")
            runCatching { sock.close() }
        }
    }

    private fun activate(sock: Socket) {
        client = sock
        out = sock.getOutputStream()
        peerText = peerTextOf(sock)
        synchronized(rxLock) { rxLen = 0 }
        outQueue.clear()
        pressedKeys.clear()
        lastButtons = 0
        ready = true
        Log.i("手机已连入：$peerText")
        mainHandler.post {
            TvInputDispatcher.peer(true, peerText)
            onPeerChanged?.invoke(true, sock.inetAddress?.hostAddress ?: "")
        }

        readerThread = Thread({ readerLoop(sock) }, "apxtv-reader").apply {
            isDaemon = true
            start()
        }
        writerThread = Thread({ writerLoop(sock) }, "apxtv-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun readerLoop(sock: Socket) {
        val ins = sock.getInputStream()
        val buf = ByteArray(4096)
        // `client === sock`：被新连接接管后本线程立即退出（不再继续解析旧连接的数据）
        while (running.get() && !sock.isClosed && client === sock) {
            val n = try {
                ins.read(buf)
            } catch (t: Throwable) {
                // **读超时 ≠ 断线**：对方可能只是暂时没说话（心跳有间断也是正常的）。
                // 早期版本据此判死，会把活连接误杀；现在只把超时当成「再来一轮」，
                // 真正的收尾交给 IO 异常、对端关闭、或新连接接管。
                if (t is java.net.SocketTimeoutException) continue
                -1
            }
            if (n <= 0) break
            feed(buf, n)
            val actions = pump()
            if (actions.isNotEmpty()) {
                mainHandler.post { for (a in actions) a() }
            }
        }
        Log.i("手机连接已断开：$peerText")
        teardown(sock)
    }

    private fun writerLoop(sock: Socket) {
        val o = out ?: return
        while (running.get() && !sock.isClosed && client === sock) {
            val frame = try {
                outQueue.poll(WRITER_IDLE_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            try {
                o.write(frame)
                o.flush()
            } catch (t: Throwable) {
                Log.w("控制帧写出失败，视为链路断开：${t.message}")
                break
            }
        }
        runCatching { sock.close() }
        teardown(sock)
    }

    private fun teardown(sock: Socket) {
        if (client === sock) {
            ready = false
            client = null
            out = null
            peerText = ""
            outQueue.clear()
            mainHandler.post {
                TvInputDispatcher.peer(false, "")
                onPeerChanged?.invoke(false, "")
            }
        }
        runCatching { sock.close() }
    }

    // ————————————————————————————— 收流解析 —————————————————————————————

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

    /** @return 待在主线程执行的输入动作列表（保持帧内顺序） */
    private fun pump(): List<() -> Unit> {
        val actions = ArrayList<() -> Unit>()
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
                if (ApxFrame.streamIdAt(rxBuf, off) == ApxFrame.STREAM_CONTROL && payloadLen >= 1) {
                    val b0 = rxBuf[off + ApxFrame.HEADER_SIZE].toInt() and 0xFF
                    when (b0) {
                        'p'.code -> actions.add { sendControl(PONG) }
                        0x01 -> if (payloadLen >= 5) actions.add { onMouse(rxBuf, off) }
                        0x02 -> if (payloadLen >= 3) actions.add { onConsumer(rxBuf, off) }
                        0x03 -> if (payloadLen >= 3) actions.add { onKeyboard(rxBuf, off, payloadLen) }
                        0x04 -> if (payloadLen >= 9) actions.add { onTouch(rxBuf, off) }
                        0x07 -> if (payloadLen >= 7) actions.add { onGamepad(rxBuf, off) }
                        0x20 -> if (payloadLen >= 4) actions.add { onClipboard(rxBuf, off, payloadLen) }
                        0x05 -> Log.i("收到开副屏请求（TV 端忽略）")
                        0x10 -> Log.i("收到模块开关（TV 端忽略）")
                    }
                }
                off += total
            }
            if (off > 0) {
                System.arraycopy(rxBuf, off, rxBuf, 0, rxLen - off)
                rxLen -= off
            }
        }
        return actions
    }

    // ————————————————————————————— 控制帧语义 —————————————————————————————

    private fun onMouse(buf: ByteArray, off: Int) {
        val base = off + ApxFrame.HEADER_SIZE
        val buttons = buf[base + 1].toInt() and 0xFF
        val dx = buf[base + 2].toInt().toByte().toInt()   // i8 还原
        val dy = buf[base + 3].toInt().toByte().toInt()
        TvInputDispatcher.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        TvInjector.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        if ((buttons and 1) != 0 && (lastButtons and 1) == 0) {
            TvInputDispatcher.cursorClick()
            TvInjector.click()
        }
        lastButtons = buttons
    }

    private fun onTouch(buf: ByteArray, off: Int) {
        val base = off + ApxFrame.HEADER_SIZE
        val action = buf[base + 1].toInt() and 0xFF
        val x = (buf[base + 3].toInt() and 0xFF) or ((buf[base + 4].toInt() and 0xFF) shl 8)
        val y = (buf[base + 5].toInt() and 0xFF) or ((buf[base + 6].toInt() and 0xFF) shl 8)
        val fx = x / 65535f
        val fy = y / 65535f
        TvInputDispatcher.cursorMove(fx, fy, absolute = true)
        TvInjector.cursorMove(fx, fy, absolute = true)
        when (action) {
            0 -> TvInjector.touchDown(fx, fy)          // down
            1 -> {                                      // up
                TvInputDispatcher.cursorClick()
                TvInjector.touchUp(fx, fy)
            }
        }
    }

    private fun onConsumer(buf: ByteArray, off: Int) {
        val base = off + ApxFrame.HEADER_SIZE
        val bitmap = (buf[base + 1].toInt() and 0xFF) or ((buf[base + 2].toInt() and 0xFF) shl 8)
        TvInjector.consumer(bitmap)   // 音量/静音/媒体
        for (bit in 0 until 16) {
            if ((bitmap ushr bit) and 1 == 0) continue
            val kc = CONSUMER_MAP[bit] ?: continue
            TvInputDispatcher.key(kc, true)
            TvInputDispatcher.key(kc, false)
        }
    }

    /** HID 修饰位 → Android keyCode（bit0..3 = 左 Ctrl/Shift/Alt/Win，bit4..7 = 右；与 PC 端 injectKeyboard 位序一致） */
    private val MOD_KEYCODE = intArrayOf(113, 59, 57, 117, 114, 60, 58, 118)

    private fun onKeyboard(buf: ByteArray, off: Int, payloadLen: Int) {
        // body=[0x03, mod, 0, k1..k6]；HID usage page 0x07
        val base = off + ApxFrame.HEADER_SIZE
        val mod = buf[base + 1].toInt() and 0xFF
        val now = HashSet<Int>()
        val end = if (payloadLen < 9) payloadLen else 9
        for (i in 3 until end) {
            val usage = buf[base + i].toInt() and 0xFF
            if (usage != 0) now.add(usage)
        }
        // 修饰键：把 mod 的 8 个位折成 0xE0..0xE7 并入按下集合，复用下面的边沿逻辑。
        // 原先这里**整段没读 mod** —— 「Ctrl+C / Alt+Tab / Win+D」到 TV 只剩一个裸字母或
        // 完全没反应（真机症状：快捷键不能用）。对端松手时发 mod=0、keys=0，
        // 因此修饰键也会在这里被正确松开。
        for (bit in 0 until 8) {
            if (mod and (1 shl bit) != 0) now.add(0xE0 + bit)
        }
        // key-up：之前按下、本次没了
        for (u in pressedKeys) {
            if (u !in now) hidUp(u)
        }
        // key-down：本次有、之前没有
        for (u in now) {
            if (u !in pressedKeys) hidDown(u)
        }
        pressedKeys.clear()
        pressedKeys.addAll(now)
    }

    private fun hidDown(usage: Int) {
        if (usage in 0xE0..0xE7) {            // 修饰键：走原生按键，不能按字符注入
            val kc = MOD_KEYCODE[usage - 0xE0]
            TvInputDispatcher.key(kc, true)
            TvInjector.key(kc, true)
            return
        }
        val (kc, ch) = HID_MAP[usage] ?: (0 to '\u0000')
        if (kc != 0) {
            TvInputDispatcher.key(kc, true)
            TvInjector.key(kc, true)
        }
        if (ch != '\u0000') {
            TvInputDispatcher.text(ch)
            TvInjector.text(ch)
        }
    }

    private fun hidUp(usage: Int) {
        if (usage in 0xE0..0xE7) {
            val kc = MOD_KEYCODE[usage - 0xE0]
            TvInputDispatcher.key(kc, false)
            TvInjector.key(kc, false)
            return
        }
        val (kc, _) = HID_MAP[usage] ?: (0 to '\u0000')
        if (kc != 0) {
            TvInputDispatcher.key(kc, false)
            TvInjector.key(kc, false)
        }
    }

    /** 剪贴板文本帧：body=[0x20, len u16 LE, utf8…] */
    private fun onClipboard(buf: ByteArray, off: Int, payloadLen: Int) {
        val base = off + ApxFrame.HEADER_SIZE + 1   // 跳过 opcode
        val len = (buf[base].toInt() and 0xFF) or ((buf[base + 1].toInt() and 0xFF) shl 8)
        if (payloadLen - 2 < len || len <= 0) return
        val text = String(buf.copyOfRange(base + 2, base + 2 + len), Charsets.UTF_8)
        TvInjector.clipboard(text)
    }

    /** 手柄帧：body=[0x07, buttons u16 LE, x, y, rx, ry]；按钮位图 + 双摇杆 4 轴 */
    private fun onGamepad(buf: ByteArray, off: Int) {
        val base = off + ApxFrame.HEADER_SIZE
        val buttons = (buf[base + 1].toInt() and 0xFF) or ((buf[base + 2].toInt() and 0xFF) shl 8)
        val x = buf[base + 3].toInt().toByte().toInt()
        val y = buf[base + 4].toInt().toByte().toInt()
        val rx = buf[base + 5].toInt().toByte().toInt()
        val ry = buf[base + 6].toInt().toByte().toInt()
        TvInputDispatcher.onGamepad(buttons, x, y, rx, ry)
        TvInjector.gamepad(buttons, x, y, rx, ry)
    }

    // ————————————————————————————— 发送 —————————————————————————————

    fun sendControl(body: ByteArray): Boolean {
        if (!ready) return false
        val frame = synchronized(writeLock) {
            seq = (seq + 1) and 0x7FFFFFFF
            ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq)
        }
        if (outQueue.offer(frame)) return true
        outQueue.poll()
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
        /** TV 控制面端口（手机端 TvControllerClient 连这里） */
        const val PORT = 9511

        private const val MAX_TOKEN = 256
        private const val HANDSHAKE_TIMEOUT_MS = 5_000

        /**
         * 收流读超时：让收流线程定期醒来复核 running / 接管状态。
         * **不据此判死** —— 判死会误杀心跳有间断的活连接（见 readerLoop 注释）；
         * 真正的收尾交给 IO 异常、对端关闭或新连接接管。
         */
        private const val READ_TIMEOUT_MS = 3_000
        private const val QUEUE_CAP = 256
        private const val WRITER_IDLE_MS = 200L
        private val PONG = "pong".toByteArray(Charsets.UTF_8)

        private fun peerTextOf(sock: Socket): String =
            sock.inetAddress?.hostAddress?.let { "$it:${sock.port}" } ?: "?"

        fun localIpv4(): String? = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> Collections.list(ni.inetAddresses) }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress
        } catch (t: Throwable) {
            null
        }

        /**
         * HID usage(page 0x07) → Android。value: keyCode（0 表示仅文本）/ 字符（'\u0000' 表示无）。
         * 覆盖方向键、回车、删除、空格与字母数字；其余键忽略。
         */
        private val HID_MAP: Map<Int, Pair<Int, Char>> = buildMap {
            put(0x28, 66 to '\u0000')          // Enter
            put(0x29, 4 to '\u0000')           // Esc → Back（遥控器「返回」）
            put(0x2A, 67 to '\u0000')          // Backspace
            put(0x2C, 62 to ' ')              // Space
            put(0x4A, 3 to '\u0000')           // Home（遥控器「主页」；受系统注入限制可能不生效）
            put(0x4C, 112 to '\u0000')         // Delete
            put(0x4F, 22 to '\u0000')          // Right
            put(0x50, 21 to '\u0000')          // Left
            put(0x51, 20 to '\u0000')          // Down
            put(0x52, 19 to '\u0000')          // Up
            // 字母 a..z (0x04..0x1D)
            for (c in 'a'..'z') put(0x04 + (c - 'a'), 0 to c)
            // 数字 1..9,0 (0x1E..0x27)
            val digits = "1234567890"
            for (i in digits.indices) put(0x1E + i, 0 to digits[i])
        }

        /** Consumer 帧 16 位位图 → Android 媒体键。
         * 位序与手机端 HotkeyController / TvControllerActivity 的 consumer 位图严格一致：
         *  bit0 音量+ bit1 音量- bit2 静音 bit3 电源(本端无对应键，忽略)
         *  bit4 播放/暂停 bit5 上一首 bit6 下一首 */
        private val CONSUMER_MAP: Map<Int, Int> = mapOf(
            0 to 24,    // Volume Up
            1 to 25,    // Volume Down
            2 to 164,   // Mute
            4 to 85,    // Play/Pause
            5 to 88,    // Previous
            6 to 87,    // Next
        )
    }
}
