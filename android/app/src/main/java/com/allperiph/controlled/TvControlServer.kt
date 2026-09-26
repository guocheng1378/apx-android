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
                // 新连接来了：**让后来者接管**，而不是拒绝。
                // 旧客户端可能已是半死的僵尸（对端被强杀、Wi‑Fi 切换/网段变化，服务端收不到
                // FIN 就永远以为它还活着）。此时若拒绝新连接，PC 端表现为「TCP 连上但立刻被关」：
                // 面板显示「已连接」却瞬间掉线，点副屏报「媒体连接未建立」。
                Log.w("被控控制面", "新连接接管，顶掉旧连接 $peerText")
                val old = client
                runCatching { old?.close() }
                if (client === old) {   // 旧连接的收流线程可能刚好已清过，别重复清
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

        // 读超时：对端被强杀 / 网络黑洞时 read 既不返回也不抛错，收流线程会永远卡住，
        // ready 就永远是 true（后面每个新连接都会被当成「已有连接」）。给超时才能发现死连接。
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
                // 读超时（soTimeout>0）只表示「这一小段时间没数据」，**不是**断线，继续等。
                // 注意：曾在这里按「超过 N 秒无帧」判死，结果把一条对端心跳有间断的**活连接**
                // 掐掉了（PC 处于自动发现、广播又被 AP 挡着，掐掉后它无法重连 —— 直接变
                // 「连不上」）。僵尸连接已由 acceptLoop 的「新连接接管」解决，这里不再判死。
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
                    // ★★ 必须**现在**就把 body 拷出来：紧接着下面会 arraycopy 把缓冲区前移，
                    // 而动作是**延后**到主线程执行的 —— 以前把 (rxBuf, off) 直接塞进 lambda，
                    // 执行时 off 早已失效、内容也已移位：鼠标读到垃圾坐标、键盘读到垃圾 usage
                    // （HID_MAP 查不到 → 什么都不发生）。
                    // 真机症状正是「手机控手机完全不能用 / TV 所有按键都没反应」，而手机→电脑
                    // 一直正常 —— 因为 PC 端是即时解析，没有这道延迟。
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
                            0x21 -> Log.i("被控控制面", "收到反向剪贴板帧（本端忽略，由对方处理）")
                            0x05 -> Log.i("被控控制面", "收到开副屏请求（忽略）")
                            0x10 -> Log.i("被控控制面", "收到模块开关（忽略）")
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
        // body=[0x01, buttons, dx(i8), dy(i8), wheel]
        val buttons = body[1].toInt() and 0xFF
        val dx = body[2].toInt().toByte().toInt()   // i8 还原
        val dy = body[3].toInt().toByte().toInt()
        val wheel = if (body.size >= 5) body[4].toInt().toByte().toInt() else 0
        TvInputDispatcher.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        TvInjector.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        // 左键按下/松开 → 被控端"一笔"：不动=轻点或长按(≥500ms)，按住移动=拖拽（时长取实际值）。
        // 以前这里只合成单击 —— 长按菜单与拖拽全都出不来（真机反馈"长按不对、没有滑动"）。
        if ((buttons and 1) != 0 && (lastButtons and 1) == 0) TvInjector.pressDown()
        if ((buttons and 1) == 0 && (lastButtons and 1) == 1) TvInjector.pressUp()
        // 滚轮：一格 = 被控端滚一屏的 1/10（以前滚轮帧被整段忽略 → "没有滚动"）
        if (wheel != 0) TvInjector.scroll(wheel)
        lastButtons = buttons
    }

    private fun onTouch(body: ByteArray) {
        // body=[0x04, action, rsv, x u16 LE, y u16 LE]
        val action = body[1].toInt() and 0xFF
        val x = (body[3].toInt() and 0xFF) or ((body[4].toInt() and 0xFF) shl 8)
        val y = (body[5].toInt() and 0xFF) or ((body[6].toInt() and 0xFF) shl 8)
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

    private fun onConsumer(body: ByteArray) {
        // body=[0x02, bitmap u16 LE]
        val bitmap = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
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

    private fun onKeyboard(body: ByteArray) {
        // body=[0x03, mod, 0, k1..k6]；HID usage page 0x07
        val mod = body[1].toInt() and 0xFF
        val now = HashSet<Int>()
        val end = if (body.size < 9) body.size else 9
        for (i in 3 until end) {
            val usage = body[i].toInt() and 0xFF
            if (usage != 0) now.add(usage)
        }
        // 「锁屏」芯片发的是 Win+L —— 手机上没有桌面语义，当电源键处理（锁屏/亮屏）
        if (mod and 8 != 0 && now == HashSet(listOf(0x0F))) {
            pressedKeys.clear()
            if (RootInput.available) RootInput.run("input keyevent 26")
            else ApxAccessibilityService.instance?.lockScreen()
            return
        }
        // 修饰键：把 mod 的 8 个位折成 0xE0..0xE7 并入按下集合，复用下面的边沿逻辑。
        // 原先这里**整段没读 mod** —— 「Ctrl+C / Alt+Tab / Win+D」到本机只剩一个裸字母或
        // 完全没反应（真机症状：快捷键不能用）。对端松手时发 mod=0、keys=0，
        // 因此修饰键也会在这里被正确松开。
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
    private fun onClipboard(body: ByteArray) {
        // body=[0x20, len u16 LE, utf8…]
        val len = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        if (len <= 0 || len > body.size - 3) return
        val text = String(body.copyOfRange(3, 3 + len), Charsets.UTF_8)
        TvInjector.clipboard(text)
    }

    /** 手柄帧：body=[0x07, buttons u16 LE, x, y, rx, ry]；按钮位图 + 双摇杆 4 轴 */
    private fun onGamepad(body: ByteArray) {
        val buttons = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        val x = body[3].toInt().toByte().toInt()
        val y = body[4].toInt().toByte().toInt()
        val rx = body[5].toInt().toByte().toInt()
        val ry = body[6].toInt().toByte().toInt()
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

    /** 反向剪贴板：body=[0x21, len u16 LE, utf8…] → 回传对方手机 */
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
        /** 被控控制面端口（手机端 TvControllerClient 连这里） */
        const val PORT = 9511

        private const val MAX_TOKEN = 256
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val QUEUE_CAP = 256
        private const val WRITER_IDLE_MS = 200L

        /** 读超时（activate 后生效）：让收流线程定期醒来复核 running/接管状态，
         *  **不**据此判死 —— 判死会误杀心跳有间断的活连接（见 readerLoop 注释）。 */
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

        /**
         * HID usage(page 0x07) → Android。value: keyCode（0 表示仅文本）/ 字符（'\u0000' 表示无）。
         */
        private val HID_MAP: Map<Int, Pair<Int, Char>> = buildMap {
            // ★ 遥控器套（HotkeyTemplates "tv"）里的「返回 / 主页」：0x29(Esc)→Back、0x4A(Home)→Home。
            //   原先这张表里**没有**这两条 → 点「返回/主页」到本机被静默丢弃，
            //   而它们恰好是 TV 遥控套里最常用的两个键（TV 端那张表早就有了，手机端漏了）。
            put(0x29, 4 to '\u0000')           // Esc → Back（遥控器「返回」）
            put(0x4A, 3 to '\u0000')           // Home（遥控器「主页」）
            put(0x2B, 61 to '\u0000')          // Tab（终端/补全类模板常用）
            // F1..F12（0x3A..0x45 → KEYCODE_F1..F12）：多套模板把 F 区当快捷键
            for (i in 0 until 12) put(0x3A + i, 131 + i to '\u0000')
            put(0x28, 66 to '\u0000')          // Enter
            put(0x2A, 67 to '\u0000')          // Backspace
            put(0x2C, 62 to ' ')              // Space
            put(0x4C, 112 to '\u0000')         // Delete
            put(0x66, 26 to '\u0000')          // Keyboard Power → KEYCODE_POWER（电源键/锁屏）
            put(0x4F, 22 to '\u0000')          // Right
            put(0x50, 21 to '\u0000')          // Left
            put(0x51, 20 to '\u0000')          // Down
            put(0x52, 19 to '\u0000')          // Up
            for (c in 'a'..'z') put(0x04 + (c - 'a'), 0 to c)
            val digits = "1234567890"
            for (i in digits.indices) put(0x1E + i, 0 to digits[i])
        }

        /** Consumer 帧 16 位位图 → Android 媒体键（与手机端 CONSUMER_MAP 同序） */
        private val CONSUMER_MAP: Map<Int, Int> = mapOf(
            0 to 24,    // Volume Up
            1 to 25,    // Volume Down
            2 to 164,   // Mute
            3 to 26,    // Power（电源/锁屏；遥控键盘的「电源」图块发的就是这个位）
            4 to 85,    // Play/Pause
            5 to 88,    // Previous
            6 to 87,    // Next
        )
    }
}
