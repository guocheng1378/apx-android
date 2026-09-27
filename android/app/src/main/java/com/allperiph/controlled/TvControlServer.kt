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
 */
class TvControlServer(
    private val port: Int = PORT,
    private val token: String = "",
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var peerText: String = ""
    @Volatile var ready: Boolean = false; private set

    @Volatile var onClipboardChange: ((String) -> Unit)? = null

    /**
     * 0x10 模块开关（v184 新增协议）：PC 面板拨"无线"开关时通知手机挂起/恢复被控。
     * 语义是**挂起**而非停服务 —— 监听保持，仅拒绝/断开连接；这样 PC 端重新拨 ON
     * 后 3s 重连即可恢复，不会出现"关了就再也打不开"的单程门。
     */
    @Volatile var onModuleToggle: ((String, Boolean) -> Unit)? = null

    @Volatile private var suspended = false

    /** PC 请求挂起被控：断开当前连接并拒绝新连接（监听保留，可随时恢复） */
    fun suspendAccept() {
        suspended = true
        Log.i("被控控制面", "无线被控已挂起（PC 请求）")
        runCatching { client?.close() }
    }

    /** PC 请求恢复被控：重新接受连接 */
    fun resumeAccept() {
        if (suspended) Log.i("被控控制面", "无线被控已恢复（PC 请求）")
        suspended = false
    }
    @Volatile var onRemoteInputRequest: ((String, String) -> Unit)? = null
    @Volatile var onRemoteInputText: ((String, Int) -> Unit)? = null
    @Volatile var onRemoteInputDone: (() -> Unit)? = null

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
    // v184：pressedKeys 会被**握手线程**（activate 里 clear）与**主线程**
    // （onKeyboard 里遍历/改写）同时访问，裸 HashSet 竞态会导致漏释放（按键卡住）
    // 甚至遍历时抛 ConcurrentModificationException。所有访问纳入同一把锁。
    private val keysLock = Any()
    private val pressedKeys = HashSet<Int>()
    private var lastButtons = 0

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss; running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "apxctl-accept").apply { isDaemon = true; start() }
            Log.i("被控控制面", "监听 *:$port（本机 ${localIpv4() ?: "未知"}）")
            true
        } catch (t: Throwable) {
            Log.e("被控控制面", "监听 $port 失败：${t.message}")
            runCatching { server?.close() }; server = null; false
        }
    }

    fun stop() {
        running.set(false); outQueue.clear()
        runCatching { client?.close() }; runCatching { server?.close() }
        server = null; client = null; out = null; peerText = ""; ready = false
        acceptThread = null; readerThread = null; writerThread = null
    }

    fun statusText(): String = if (ready) "已连接 $peerText" else "监听 $port · 等待手机连入"

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try { ss.accept() } catch (t: Throwable) {
                if (!running.get()) break; try { Thread.sleep(200) } catch (_: InterruptedException) { break }; continue
            }
            if (ready) {
                Log.w("被控控制面", "新连接接管，顶掉旧连接 $peerText")
                val old = client; runCatching { old?.close() }
                if (client === old) { ready = false; client = null; out = null; peerText = ""; outQueue.clear() }
            }
            Thread({ handshake(sock) }, "apxctl-handshake").apply { isDaemon = true; start() }
        }
    }

    private fun handshake(sock: Socket) {
        try {
            sock.tcpNoDelay = true; sock.soTimeout = HANDSHAKE_TIMEOUT_MS
            val ins = sock.getInputStream()
            val len = readU32Le(ins)
            if (len < 0 || len > MAX_TOKEN) { Log.w("被控控制面", "握手长度非法：$len"); runCatching { sock.close() }; return }
            if (len > 0) {
                val buf = ByteArray(len)
                if (!readFully(ins, buf)) { runCatching { sock.close() }; return }
                if (token.isNotEmpty() && String(buf, Charsets.UTF_8) != token) { Log.w("被控控制面", "令牌不匹配，拒绝 ${peerTextOf(sock)}"); runCatching { sock.close() }; return }
            }
            sock.soTimeout = 0; activate(sock)
        } catch (t: Throwable) { Log.w("被控控制面", "握手失败：${t.message}"); runCatching { sock.close() } }
    }

    private fun activate(sock: Socket) {
        // v184：挂起期间**仍然接受连接**。旧实现在这里直接 close + return ——
        // PC 一旦断线重连就被拒，而"恢复"只能经这条连接送达（0x10 on），
        // 于是形成"PC 拨 OFF 之后手机再也连不上"的单程门
        // （真机症状：面板开关拨回去没反应，副屏也连不上）。
        // 挂起语义改为「连接保持、忽略输入类命令」，过滤见 pump()。
        if (suspended) Log.i("被控控制面", "被控处于挂起态：保持连接，不执行输入 ${peerTextOf(sock)}")
        // v184：接管时显式关掉旧连接 —— 旧实现只是覆盖 client，旧 socket 一直挂着
        // （fd 泄漏，且 PC 端那条死连接不会自愈）。旧 reader 会因 socket 关闭而退出，
        // readerLoop 的 client === sock 身份判断是第二道保险。
        val prev = client
        if (prev != null && prev !== sock) runCatching { prev.close() }
        client = sock; out = sock.getOutputStream(); peerText = peerTextOf(sock)
        synchronized(rxLock) { rxLen = 0 }; outQueue.clear()
        synchronized(keysLock) { pressedKeys.clear() }; lastButtons = 0; ready = true
        Log.i("被控控制面", "手机已连入：$peerText")
        mainHandler.post { TvInputDispatcher.peer(true, peerText) }
        TvInjector.setConnected(true)
        runCatching { sock.soTimeout = READ_TIMEOUT_MS }
        readerThread = Thread({ readerLoop(sock) }, "apxctl-reader").apply { isDaemon = true; start() }
        writerThread = Thread({ writerLoop(sock) }, "apxctl-writer").apply { isDaemon = true; start() }
    }

    private fun readerLoop(sock: Socket) {
        val ins = sock.getInputStream(); val buf = ByteArray(4096)
        // v184：循环条件加上 **client === sock** 身份判断 —— 旧连接被新连接接管后
        // 它自己的读线程必须退出。否则两条连接（握手窗口内并发到达时）会各起一个
        // reader，共用同一份 rxBuf/rxLen，后果是输入被重复注入（点一下变两下、
        // 按键连发）以及缓冲互相踩踏。
        // v184：读超时不能无限 continue —— 对端半开（WiFi 瞬断 / 被强杀，没有 FIN）时
        // 这里会永远"活着"，PC 发来的输入全进黑洞且不触发上层重连。
        // 连续 8 次超时（≈ 8×读超时）即判定链路已死；其它异常也直接退出
        // （旧实现 catch 后 continue，持续异常时会空转烧 CPU）。
        var idleTimeouts = 0
        while (running.get() && !sock.isClosed && client === sock) {
            val n = try {
                ins.read(buf)
            } catch (t: java.net.SocketTimeoutException) {
                if (++idleTimeouts >= 8) {
                    Log.w("被控控制面", "读空闲超时 8 次，判定链路已失效：$peerText")
                    break
                }
                continue
            } catch (t: Throwable) {
                Log.w("被控控制面", "读异常，断开连接：${t.message}")
                break
            }
            idleTimeouts = 0
            if (n <= 0) break; feed(buf, n)
            val actions = pump()
            if (actions.isNotEmpty()) { mainHandler.post { for (a in actions) a() } }
        }
        Log.i("被控控制面", "手机连接已断开：$peerText"); teardown(sock)
    }

    private fun writerLoop(sock: Socket) {
        val o = out ?: return
        while (running.get() && !sock.isClosed) {
            val frame = try { outQueue.poll(WRITER_IDLE_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            try { o.write(frame); o.flush() } catch (t: Throwable) { Log.w("被控控制面", "写出失败：${t.message}"); break }
        }
        runCatching { sock.close() }; teardown(sock)
    }

    private fun teardown(sock: Socket) {
        if (client === sock) {
            ready = false; client = null; out = null; peerText = ""; outQueue.clear()
            mainHandler.post { TvInputDispatcher.peer(false, "") }
            TvInjector.setConnected(false)
        }
        runCatching { sock.close() }
    }

    private fun feed(data: ByteArray, n: Int) {
        synchronized(rxLock) {
            if (rxLen + n > rxBuf.size) { var c = rxBuf.size * 2; while (c < rxLen + n) c *= 2; rxBuf = rxBuf.copyOf(c) }
            System.arraycopy(data, 0, rxBuf, rxLen, n); rxLen += n
        }
    }

    private fun pump(): List<() -> Unit> {
        val actions = ArrayList<() -> Unit>()
        synchronized(rxLock) {
            var off = 0
            while (rxLen - off >= ApxFrame.HEADER_SIZE) {
                // v184：magic / 长度异常改为**逐字节重同步** —— 原来的 off = rxLen 会因
                // 一次错位把后续全部合法帧一起丢掉；并补上尾部 CRC32 校验（对齐 PC 侧）。
                if (!ApxFrame.isMagic(rxBuf, off, rxLen)) { off++; continue }
                val payloadLen = ApxFrame.payloadLenAt(rxBuf, off)
                if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) { off++; continue }
                val total = ApxFrame.totalSize(payloadLen)
                if (rxLen - off < total) break
                if (!ApxFrame.verify(rxBuf, off, payloadLen)) { off += total; continue }
                if (ApxFrame.streamIdAt(rxBuf, off) == ApxFrame.STREAM_CONTROL && payloadLen >= 1) {
                    val body = ApxFrame.bodyAt(rxBuf, off, payloadLen)
                    if (body != null && body.isNotEmpty()) {
                        val cmd = body[0].toInt() and 0xFF
                        // v184：挂起期间只回 ping、只处理模块开关(0x10)，输入类命令一律丢弃。
                        // 连接保持（见 activate），因此 PC 随时能把挂起拨回来。
                        if (suspended && cmd != 'p'.code && cmd != 0x10) {
                            // 被控已挂起：忽略鼠标/键盘/触摸等输入
                        } else when (cmd) {
                            'p'.code -> actions.add { sendControl(PONG) }
                            0x01 -> if (body.size >= 5) actions.add { onMouse(body) }
                            0x02 -> if (body.size >= 3) actions.add { onConsumer(body) }
                            0x03 -> if (body.size >= 3) actions.add { onKeyboard(body) }
                            0x04 -> if (body.size >= 9) actions.add { onTouch(body) }
                            0x07 -> if (body.size >= 7) actions.add { onGamepad(body) }
                            0x20 -> if (body.size >= 4) actions.add { onClipboard(body) }
                            0x22 -> if (body.size >= 2) actions.add { onPowerAction(body) }
                            0x21 -> Log.i("被控控制面", "收到反向剪贴板帧（忽略）")
                            0x05 -> Log.i("被控控制面", "收到开副屏请求（忽略）")
                            0x10 -> if (body.size >= 3) actions.add { onModuleToggle(body) }
                            0x25 -> if (body.size >= 2) actions.add { onRequestInput(body) }
                            0x26 -> if (body.size >= 2) actions.add { onInputText(body) }
                            0x27 -> actions.add { onInputDone() }
                        }
                    }
                }
                off += total
            }
            if (off > 0) { System.arraycopy(rxBuf, off, rxBuf, 0, rxLen - off); rxLen -= off }
        }
        return actions
    }

    private fun onMouse(body: ByteArray) {
        val buttons = body[1].toInt() and 0xFF; val dx = body[2].toInt().toByte().toInt(); val dy = body[3].toInt().toByte().toInt()
        val wheel = if (body.size >= 5) body[4].toInt().toByte().toInt() else 0
        TvInputDispatcher.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        TvInjector.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        if ((buttons and 1) != 0 && (lastButtons and 1) == 0) TvInjector.pressDown()
        if ((buttons and 1) == 0 && (lastButtons and 1) == 1) TvInjector.pressUp()
        if (wheel != 0) TvInjector.scroll(wheel); lastButtons = buttons
    }

    private fun onTouch(body: ByteArray) {
        val action = body[1].toInt() and 0xFF
        val x = (body[3].toInt() and 0xFF) or ((body[4].toInt() and 0xFF) shl 8)
        val y = (body[5].toInt() and 0xFF) or ((body[6].toInt() and 0xFF) shl 8)
        val fx = x / 65535f; val fy = y / 65535f
        TvInputDispatcher.cursorMove(fx, fy, absolute = true)
        TvInjector.cursorMove(fx, fy, absolute = true)
        when (action) { 0 -> TvInjector.touchDown(fx, fy); 1 -> { TvInputDispatcher.cursorClick(); TvInjector.touchUp(fx, fy) } }
    }

    private fun onConsumer(body: ByteArray) {
        val bitmap = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        TvInjector.consumer(bitmap)
        for (bit in 0 until 16) { if ((bitmap ushr bit) and 1 == 0) continue; val kc = CONSUMER_MAP[bit] ?: continue
            TvInputDispatcher.key(kc, true); TvInputDispatcher.key(kc, false) }
    }

    /** 0x10 模块开关帧：[0x10, idLen, id..., on(0/1)]（v184） */
    private fun onModuleToggle(body: ByteArray) {
        val idLen = body[1].toInt() and 0xFF
        if (body.size < 2 + idLen + 1) return
        val id = String(body, 2, idLen, Charsets.UTF_8)
        val on = body[2 + idLen].toInt() != 0
        Log.i("被控控制面", "收到模块开关：$id -> $on")
        mainHandler.post { onModuleToggle?.invoke(id, on) }
    }

    private fun onPowerAction(body: ByteArray) {
        val action = body[1].toInt() and 0xFF
        Log.i("被控控制面", "电源动作请求：action=$action"); TvInjector.powerAction(action)
    }

    private val MOD_KEYCODE = intArrayOf(113, 59, 57, 117, 114, 60, 58, 118)

    private fun onKeyboard(body: ByteArray) {
        val mod = body[1].toInt() and 0xFF; val now = HashSet<Int>()
        val end = if (body.size < 9) body.size else 9
        for (i in 3 until end) { val usage = body[i].toInt() and 0xFF; if (usage != 0) now.add(usage) }
        if (mod and 8 != 0 && now == HashSet(listOf(0x0F))) {
            synchronized(keysLock) { pressedKeys.clear() }
            if (RootInput.available) RootInput.run("input keyevent 26") else ApxAccessibilityService.instance?.lockScreen()
            return
        }
        for (bit in 0 until 8) { if (mod and (1 shl bit) != 0) now.add(0xE0 + bit) }
        // v184：先取快照再比较、最后整体写回 —— 避免在锁内执行注入（hidUp/hidDown 会走
        // 系统调用），也避免遍历过程中集合被另一个线程改掉。
        val prev = synchronized(keysLock) { HashSet(pressedKeys) }
        for (u in prev) { if (u !in now) hidUp(u) }
        for (u in now) { if (u !in prev) hidDown(u) }
        synchronized(keysLock) { pressedKeys.clear(); pressedKeys.addAll(now) }
    }

    private fun hidDown(usage: Int) {
        if (usage in 0xE0..0xE7) { val kc = MOD_KEYCODE[usage - 0xE0]; TvInputDispatcher.key(kc, true); TvInjector.key(kc, true); return }
        val (kc, ch) = HID_MAP[usage] ?: (0 to '\u0000')
        if (kc != 0) { TvInputDispatcher.key(kc, true); TvInjector.key(kc, true) }
        if (ch != '\u0000') { TvInputDispatcher.text(ch); TvInjector.text(ch) }
    }

    private fun hidUp(usage: Int) {
        if (usage in 0xE0..0xE7) { val kc = MOD_KEYCODE[usage - 0xE0]; TvInputDispatcher.key(kc, false); TvInjector.key(kc, false); return }
        val (kc, _) = HID_MAP[usage] ?: (0 to '\u0000')
        if (kc != 0) { TvInputDispatcher.key(kc, false); TvInjector.key(kc, false) }
    }

    private fun onClipboard(body: ByteArray) {
        val len = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        if (len <= 0 || len > body.size - 3) return
        TvInjector.clipboard(String(body.copyOfRange(3, 3 + len), Charsets.UTF_8))
    }

    private fun onGamepad(body: ByteArray) {
        val buttons = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        TvInputDispatcher.onGamepad(buttons, body[3].toInt().toByte().toInt(), body[4].toInt().toByte().toInt(), body[5].toInt().toByte().toInt(), body[6].toInt().toByte().toInt())
        TvInjector.gamepad(buttons, body[3].toInt().toByte().toInt(), body[4].toInt().toByte().toInt(), body[5].toInt().toByte().toInt(), body[6].toInt().toByte().toInt())
    }

    /**
     * 远程输入请求帧格式：[0x25, hintLen, hint...]
     * 与 TvControllerClient.requestInput() 发送格式一致。
     */
    private fun onRequestInput(body: ByteArray) {
        if (body.size < 2) return
        val hintLen = body[1].toInt() and 0xFF
        if (body.size < 2 + hintLen) return
        val hint = String(body.copyOfRange(2, 2 + hintLen), Charsets.UTF_8)
        Log.i("被控控制面", "远程输入请求: hint=$hint")
        mainHandler.post { onRemoteInputRequest?.invoke(peerText, hint) }
    }

    private fun onInputText(body: ByteArray) {
        if (body.size < 4) return
        val flags = body[1].toInt() and 0xFF
        val textLen = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
        if (body.size < 4 + textLen) return
        val text = String(body.copyOfRange(4, 4 + textLen), Charsets.UTF_8)
        Log.i("被控控制面", "远程输入文本: flags=0x${Integer.toHexString(flags)}, text=$text")
        mainHandler.post { onRemoteInputText?.invoke(text, flags) }
    }

    private fun onInputDone() {
        Log.i("被控控制面", "远程输入完成")
        mainHandler.post { onRemoteInputDone?.invoke() }
    }

    fun sendControl(body: ByteArray): Boolean {
        if (!ready) return false
        val frame = synchronized(writeLock) { seq = (seq + 1) and 0x7FFFFFFF; ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq) }
        if (outQueue.offer(frame)) return true; outQueue.poll(); return outQueue.offer(frame)
    }

    fun sendReverseClipboard(text: String): Boolean {
        if (text.isEmpty() || !ready) return false
        val bytes = text.toByteArray(Charsets.UTF_8); if (bytes.size > 0xFFFF) return false
        val body = ByteArray(3 + bytes.size); body[0] = 0x21.toByte()
        body[1] = (bytes.size and 0xFF).toByte(); body[2] = ((bytes.size ushr 8) and 0xFF).toByte(); bytes.copyInto(body, 3)
        return sendControl(body)
    }

    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val body = ByteArray(1 + 1 + 2 + textBytes.size)
        body[0] = ApxFrame.INPUT_TEXT.toByte(); body[1] = flags.toByte()
        body[2] = (textBytes.size and 0xFF).toByte(); body[3] = ((textBytes.size ushr 8) and 0xFF).toByte()
        textBytes.copyInto(body, 4); sendControl(body)
    }

    fun sendInputDone() { sendControl(byteArrayOf(ApxFrame.INPUT_DONE.toByte())) }

    /** v184：特殊键/组合键（0x28）——手机编辑快捷键排 → PC/TV 注入。mod：1=Ctrl 2=Shift 4=Alt */
    fun sendSpecialKey(mod: Int, vk: Int) { sendControl(byteArrayOf(0x28.toByte(), mod.toByte(), vk.toByte())) }

    private fun readU32Le(ins: InputStream): Int {
        val b = ByteArray(4); if (!readFully(ins, b)) return -1
        var v = 0; for (i in 0 until 4) v = v or ((b[i].toInt() and 0xFF) shl (8 * i)); return v
    }

    private fun readFully(ins: InputStream, dst: ByteArray): Boolean {
        var got = 0
        while (got < dst.size) { val n = try { ins.read(dst, got, dst.size - got) } catch (_: Throwable) { -1 }; if (n <= 0) return false; got += n }
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
        private fun peerTextOf(sock: Socket): String = sock.inetAddress?.hostAddress?.let { "$it:${sock.port}" } ?: "?"
        fun localIpv4(): String? = try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> Collections.list(ni.inetAddresses) }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress
        } catch (_: Throwable) { null }
        private val HID_MAP: Map<Int, Pair<Int, Char>> = buildMap {
            put(0x29, 4 to '\u0000'); put(0x4A, 3 to '\u0000'); put(0x2B, 61 to '\u0000')
            for (i in 0 until 12) put(0x3A + i, 131 + i to '\u0000')
            put(0x28, 66 to '\u0000'); put(0x2A, 67 to '\u0000'); put(0x2C, 62 to ' ')
            put(0x4C, 112 to '\u0000'); put(0x66, 26 to '\u0000'); put(0x65, 82 to '\u0000')
            put(0x4F, 22 to '\u0000'); put(0x50, 21 to '\u0000'); put(0x51, 20 to '\u0000'); put(0x52, 19 to '\u0000')
            for (c in 'a'..'z') put(0x04 + (c - 'a'), 0 to c)
            val digits = "1234567890"
            for (i in digits.indices) put(0x1E + i, 0 to digits[i])
        }
        private val CONSUMER_MAP: Map<Int, Int> = mapOf(0 to 24, 1 to 25, 2 to 164, 3 to 26, 4 to 85, 5 to 88, 6 to 87)
    }
}