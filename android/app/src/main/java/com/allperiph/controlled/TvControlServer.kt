package com.allperiph.controlled

import android.os.Handler
import android.os.Looper
import com.allperiph.core.ApxFrame
import com.allperiph.core.Log
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
 * 被控模式控制面**服务端**：本机开 ServerSocket 监听，对方手机作为客户端连入并发控制帧。
 * 与 TV 模块 [com.allperiph.tv.net.TcpControlServer] 逐字节同协议（同款握手 + APX1 帧）。
 * 端口 [PORT]=9511，与手机端 [com.allperiph.wireless.TvControllerClient] 一致。
 *
 * 反向剪贴板：当本机（被控）剪贴板变化且已连入对方手机时，通过 0x21 帧把文本回传对方。
 */
class TvControlServer(
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

    /** 反向剪贴板：由 [ControlledService] 注入，当本机剪贴板变化时回调 */
    @Volatile
    var onClipboardChange: ((String) -> Unit)? = null

    /** 远程输入请求回调：对端设备请求本机输入 */
    @Volatile
    var onRemoteInputRequest: ((String, String) -> Unit)? = null  // (fromDevice, hint)

    /** 远程输入文本回调：对端发来输入文本 */
    @Volatile
    var onRemoteInputText: ((String, Int) -> Unit)? = null  // (text, flags)

    /** 远程输入完成回调 */
    @Volatile
    var onRemoteInputDone: (() -> Unit)? = null

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
            acceptThread = Thread({ acceptLoop(ss) }, "apxctl-accept").apply {
                isDaemon = true
                start()
            }
            Log.i("被控控制面", "监听 *:$port（本机 ${localIpv4() ?: "未知"}）")
            true
        } catch (t: Throwable) {
            Log.e("被控控制面", "监听 $port 失败：${t.message}")
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
            if (ready) {
                Log.w("被控控制面", "新连接接管，顶掉旧连接 $peerText")
                val old = client
                runCatching { old?.close() }
                if (client === old) {
                    ready = false
                    client = null
                    out = null
                    peerText = ""
                    outQueue.clear()
                }
            }
            Thread({ handshake(sock) }, "apxctl-handshake").apply {
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
                Log.w("被控控制面", "握手长度非法：$len")
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
                    Log.w("被控控制面", "令牌不匹配，拒绝 ${peerTextOf(sock)}")
                    runCatching { sock.close() }
                    return
                }
            }
            sock.soTimeout = 0
            activate(sock)
        } catch (t: Throwable) {
            Log.w("被控控制面", "握手失败：${t.message}")
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
        Log.i("被控控制面", "手机已连入：$peerText")
        mainHandler.post { TvInputDispatcher.peer(true, peerText) }
        TvInjector.setConnected(true)

        runCatching { sock.soTimeout = READ_TIMEOUT_MS }

        readerThread = Thread({ readerLoop(sock) }, "apxctl-reader").apply {
            isDaemon = true
            start()
        }
        writerThread = Thread({ writerLoop(sock) }, "apxctl-writer").apply {
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
                continue
            }
            if (n <= 0) break
            feed(buf, n)
            val actions = pump()
            if (actions.isNotEmpty()) {
                mainHandler.post { for (a in actions) a() }
            }
        }
        Log.i("被控控制面", "手机连接已断开：$peerText")
        teardown(sock)
    }

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
                Log.w("被控控制面", "控制帧写出失败，视为链路断开：${t.message}")
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
            TvInjector.setConnected(false)
            mainHandler.post { TvInputDispatcher.peer(false, "") }
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
                    val body = ApxFrame.bodyAt(rxBuf, off, payloadLen)
                    if (body != null && body.isNotEmpty()) {
                        when (body[0].toInt() and 0xFF) {
                            'p'.code -> actions.add { sendControl(PONG) }
                            0x01 -> if (body.size >= 5) actions.add { onMouse(body) }
                            0x02 -> if (body.size >= 3) actions.add { onConsumer(body) }
                            0x03 -> if (body.size >= 3) actions.add { onKeyboard(body) }
                            0x04 -> if (body.size >= 9) actions.add { onTouch(body) }
                            0x07 -> if (body.size >= 7) actions.add { onGamepad(body) }
                            0x20 -> if (body.size >= 4) actions.add { onClipboard(body) }
                            0x22 -> if (body.size >= 2) actions.add { onPowerAction(body) }
                            0x21 -> Log.i("被控控制面", "收到反向剪贴板帧（本端忽略，由对方处理）")
                            0x05 -> Log.i("被控控制面", "收到开副屏请求（忽略）")
                            0x10 -> Log.i("被控控制面", "收到模块开关（忽略）")
                            // ———— 远程输入帧（docs/REMOTE-INPUT.md）————
                            0x25 -> if (body.size >= 2) actions.add { onRequestInput(body) }
                            0x26 -> if (body.size >= 2) actions.add { onInputText(body) }
                            0x27 -> actions.add { onInputDone() }
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
        return actions
    }

    // ————————————————————————————— 控制帧语义 —————————————————————————————

    private fun onMouse(body: ByteArray) {
        val buttons = body[1].toInt() and 0xFF
        val dx = body[2].toInt().toByte().toInt()
        val dy = body[3].toInt().toByte().toInt()
        val wheel = if (body.size >= 5) body[4].toInt().toByte().toInt() else 0
        TvInputDispatcher.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        TvInjector.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        if ((buttons and 1) != 0 && (lastButtons and 1) == 0) TvInjector.pressDown()
        if ((buttons and 1) == 0 && (lastButtons and 1) == 1) TvInjector.pressUp()
        if (wheel != 0) TvInjector.scroll(wheel)
        lastButtons = buttons
    }

    private fun onTouch(body: ByteArray) {
        val action = body[1].toInt() and 0xFF
        val x = (body[3].toInt() and 0xFF) or ((body[4].toInt() and 0xFF) shl 8)
        val y = (body[5].toInt() and 0xFF) or ((body[6].toInt() and 0xFF) shl 8)
        val fx = x / 65535f
        val fy = y / 65535f
        TvInputDispatcher.cursorMove(fx, fy, absolute = true)
        TvInjector.cursorMove(fx, fy, absolute = true)
        when (action) {
            0 -> TvInjector.touchDown(fx, fy)
            1 -> {
                TvInputDispatcher.cursorClick()
                TvInjector.touchUp(fx, fy)
            }
        }
    }

    private fun onConsumer(body: ByteArray) {
        val bitmap = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        TvInjector.consumer(bitmap)
        for (bit in 0 until 16) {
            if ((bitmap ushr bit) and 1 == 0) continue
            val kc = CONSUMER_MAP[bit] ?: continue
            TvInputDispatcher.key(kc, true)
            TvInputDispatcher.key(kc, false)
        }
    }

    private fun onPowerAction(body: ByteArray) {
        val action = body[1].toInt() and 0xFF
        Log.i("被控控制面", "电源动作请求：action=$action")
        TvInjector.powerAction(action)
    }

    private val MOD_KEYCODE = intArrayOf(113, 59, 57, 117, 114, 60, 58, 118)

    private fun onKeyboard(body: ByteArray) {
        val mod = body[1].toInt() and 0xFF
        val now = HashSet<Int>()
        val end = if (body.size < 9) body.size else 9
        for (i in 3 until end) {
            val usage = body[i].toInt() and 0xFF
            if (usage != 0) now.add(usage)
        }
        if (mod and 8 != 0 && now == HashSet(listOf(0x0F))) {
            pressedKeys.clear()
            if (RootInput.available) RootInput.run("input keyevent 26")
            else ApxAccessibilityService.instance?.lockScreen()
            return
        }
        for (bit in 0 until 8) {
            if (mod and (1 shl bit) != 0) now.add(0xE0 + bit)
        }
        for (u in pressedKeys) {
            if (u !in now) hidUp(u)
        }
        for (u in now) {
            if (u !in pressedKeys) hidDown(u)
        }
        pressedKeys.clear()
        pressedKeys.addAll(now)
    }

    private fun hidDown(usage: Int) {
        if (usage in 0xE0..0xE7) {
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

    private fun onClipboard(body: ByteArray) {
        val len = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        if (len <= 0 || len > body.size - 3) return
        val text = String(body.copyOfRange(3, 3 + len), Charsets.UTF_8)
        TvInjector.clipboard(text)
    }

    private fun onGamepad(body: ByteArray) {
        val buttons = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        val x = body[3].toInt().toByte().toInt()
        val y = body[4].toInt().toByte().toInt()
        val rx = body[5].toInt().toByte().toInt()
        val ry = body[6].toInt().toByte().toInt()
        TvInputDispatcher.onGamepad(buttons, x, y, rx, ry)
        TvInjector.gamepad(buttons, x, y, rx, ry)
    }

    // ————————————————————————————— 远程输入帧处理 —————————————————————————————

    private fun onRequestInput(body: ByteArray) {
        if (body.size < 6) return
        val hintLen = body[5].toInt() and 0xFF
        if (body.size < 6 + hintLen) return
        val hint = String(body.copyOfRange(6, 6 + hintLen), Charsets.UTF_8)
        Log.i("被控控制面", "远程输入请求: hint=$hint")
        mainHandler.post {
            onRemoteInputRequest?.invoke(peerText, hint)
        }
    }

    private fun onInputText(body: ByteArray) {
        if (body.size < 4) return
        val flags = body[1].toInt() and 0xFF
        val textLen = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
        if (body.size < 4 + textLen) return
        val text = String(body.copyOfRange(4, 4 + textLen), Charsets.UTF_8)
        Log.i("被控控制面", "远程输入文本: flags=0x${Integer.toHexString(flags)}, text=$text")
        mainHandler.post {
            onRemoteInputText?.invoke(text, flags)
        }
    }

    private fun onInputDone() {
        Log.i("被控控制面", "远程输入完成")
        mainHandler.post {
            onRemoteInputDone?.invoke()
        }
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

    fun sendReverseClipboard(text: String): Boolean {
        if (text.isEmpty() || !ready) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 0xFFFF) return false
        val body = ByteArray(3 + bytes.size)
        body[0] = 0x21.toByte()
        body[1] = (bytes.size and 0xFF).toByte()
        body[2] = ((bytes.size ushr 8) and 0xFF).toByte()
        bytes.copyInto(body, 3)
        return sendControl(body)
    }

    // ————————————————————————————— 远程输入发送（供 RemoteInputActivity 调用）———————————————————

    /**
     * 发送实时输入文本到对端设备（B→A）。
     * @param text 输入的文本
     * @param flags INPUT_FLAG_* 位组合（默认 INCREMENTAL）
     */
    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val body = ByteArray(1 + 1 + 2 + textBytes.size)
        body[0] = ApxFrame.INPUT_TEXT.toByte()
        body[1] = flags.toByte()
        body[2] = (textBytes.size and 0xFF).toByte()
        body[3] = ((textBytes.size ushr 8) and 0xFF).toByte()
        textBytes.copyInto(body, 4)
        sendControl(body)
    }

    /** 发送输入完成到对端设备（B→A） */
    fun sendInputDone() {
        sendControl(byteArrayOf(ApxFrame.INPUT_DONE.toByte()))
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
        const val PORT = 9511

        private const val MAX_TOKEN = 256
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val QUEUE_CAP = 256
        private const val WRITER_IDLE_MS = 200L
        private const val READ_TIMEOUT_MS = 3_000
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

        private val HID_MAP: Map<Int, Pair<Int, Char>> = buildMap {
            put(0x29, 4 to '\u0000')           // Esc → Back
            put(0x4A, 3 to '\u0000')           // Home
            put(0x2B, 61 to '\u0000')          // Tab
            for (i in 0 until 12) put(0x3A + i, 131 + i to '\u0000')
            put(0x28, 66 to '\u0000')          // Enter
            put(0x2A, 67 to '\u0000')          // Backspace
            put(0x2C, 62 to ' ')              // Space
            put(0x4C, 112 to '\u0000')         // Delete
            put(0x66, 26 to '\u0000')          // Power
            put(0x65, 82 to '\u0000')          // Menu
            put(0x4F, 22 to '\u0000')          // Right
            put(0x50, 21 to '\u0000')          // Left
            put(0x51, 20 to '\u0000')          // Down
            put(0x52, 19 to '\u0000')          // Up
            for (c in 'a'..'z') put(0x04 + (c - 'a'), 0 to c)
            val digits = "1234567890"
            for (i in digits.indices) put(0x1E + i, 0 to digits[i])
        }

        private val CONSUMER_MAP: Map<Int, Int> = mapOf(
            0 to 24, 1 to 25, 2 to 164, 3 to 26, 4 to 85, 5 to 88, 6 to 87,
        )
    }
}