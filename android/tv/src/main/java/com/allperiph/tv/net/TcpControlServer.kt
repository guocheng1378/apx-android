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

class TcpControlServer(private val port: Int = PORT, private val token: String = "") {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var peerText: String = ""
    @Volatile var ready: Boolean = false; private set
    @Volatile var onPeerChanged: ((Boolean, String) -> Unit)? = null
    @Volatile var onOpenScreenRequest: (() -> Unit)? = null
    @Volatile var onRemoteInputRequest: ((String, String) -> Unit)? = null
    @Volatile var onRemoteInputText: ((String, Int) -> Unit)? = null
    @Volatile var onRemoteInputDone: (() -> Unit)? = null
    @Volatile var onAuthorizePeer: ((String) -> Boolean)? = null
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
    // v1.7：pressedKeys 会被握手线程（activate 里清理）与注入线程（onKeyboard 差量更新）
    // 同时访问，裸 HashSet 竞态会漏释放（按键卡住）。与手机被控端用同一套写法。
    private val keysLock = Any()
    private val pressedKeys = HashSet<Int>()
    private var lastButtons = 0
    /** v184：读空闲超时累计次数（半开检测用） */
    private var idleTimeouts = 0

    // v184：注入动作的执行线程 —— actions 里的注入会调用 RootInput.run
    // （往 su 管道写命令，管道满/su 卡住时阻塞）与无障碍 API，放主线程执行有 ANR 风险。
    // 单线程队列保证按键/滑动的先后顺序不变。
    private val injectThread = android.os.HandlerThread("apxtv-inject").apply { start() }
    private val injectHandler = Handler(injectThread.looper)
    val isListening: Boolean get() = running.get() && server != null
    fun currentPeerHost(): String? = client?.inetAddress?.hostAddress
    fun start(): Boolean { if (running.get()) return true; return try { val ss = ServerSocket(); ss.reuseAddress = true; ss.bind(InetSocketAddress(port)); server = ss; running.set(true); acceptThread = Thread({ acceptLoop(ss) }, "apxtv-accept").apply { isDaemon = true; start() }; Log.i("控制面监听 *:$port（本机 ${localIpv4() ?: "未知"}）"); true } catch (t: Throwable) { Log.e("控制面监听 $port 失败：${t.message}"); runCatching { server?.close() }; server = null; false } }
    fun stop() { running.set(false); outQueue.clear(); runCatching { injectHandler.post { releaseAllInputs() } }; runCatching { injectThread.quitSafely() }; runCatching { client?.close() }; runCatching { server?.close() }; server = null; client = null; out = null; peerText = ""; ready = false; acceptThread = null; readerThread = null; writerThread = null }
    fun statusText(): String = if (ready) "已连接 $peerText" else "监听 $port · 等待手机连入"
    private fun acceptLoop(ss: ServerSocket) { while (running.get()) { val sock = try { ss.accept() } catch (t: Throwable) { if (!running.get()) break; try { Thread.sleep(200) } catch (_: InterruptedException) { break }; continue }; /* v184：接管移到鉴权之后（见 activate）—— 旧实现此处就踢掉在用连接，任何未授权连接都能造成中断 */ Thread({ handshake(sock) }, "apxtv-handshake").apply { isDaemon = true; start() } } }
    private fun handshake(sock: Socket) { try { sock.tcpNoDelay = true; sock.soTimeout = HANDSHAKE_TIMEOUT_MS; val ins = sock.getInputStream(); val len = readU32Le(ins); if (len < 0 || len > MAX_TOKEN) { Log.w("握手长度非法：$len"); runCatching { sock.close() }; return }; if (len > 0) { val buf = ByteArray(len); if (!readFully(ins, buf)) { runCatching { sock.close() }; return }; if (token.isNotEmpty() && String(buf, Charsets.UTF_8) != token) { Log.w("令牌不匹配"); runCatching { sock.close() }; return } }; sock.soTimeout = 0; activate(sock) } catch (t: Throwable) { Log.w("握手失败：${t.message}"); runCatching { sock.close() } } }
    private fun activate(sock: Socket) { val ip = sock.inetAddress?.hostAddress ?: ""; if (onAuthorizePeer?.invoke(ip) == false) { Log.w("拒绝未授权设备 $ip"); runCatching { sock.close() }; return }; val old = client; if (old != null && old !== sock) { Log.w("新连接接管，断开旧连接 $peerText"); runCatching { old.close() } }; client = sock; out = sock.getOutputStream(); peerText = peerTextOf(sock); synchronized(rxLock) { rxLen = 0 }; outQueue.clear(); synchronized(keysLock) { pressedKeys.clear() }; lastButtons = 0; ready = true; Log.i("手机已连入：$peerText"); mainHandler.post { TvInputDispatcher.peer(true, peerText) }; runCatching { sock.soTimeout = READ_TIMEOUT_MS }; readerThread = Thread({ readerLoop(sock) }, "apxtv-reader").apply { isDaemon = true; start() }; writerThread = Thread({ writerLoop(sock) }, "apxtv-writer").apply { isDaemon = true; start() } }
    private fun readerLoop(sock: Socket) { val ins = sock.getInputStream(); val buf = ByteArray(4096); idleTimeouts = 0; while (running.get() && !sock.isClosed && client === sock) { val n = try { ins.read(buf) } catch (t: Throwable) { if (t is java.net.SocketTimeoutException) { if (++idleTimeouts >= 8) { Log.w("读空闲超时 8 次，判定链路失效：$peerText"); break }; continue }; Log.w("读异常，断开：${t.message}"); break }; idleTimeouts = 0; if (n <= 0) break; feed(buf, n); val actions = pump(); if (actions.isNotEmpty()) injectHandler.post { for (a in actions) a() } }; Log.i("手机连接已断开：$peerText"); teardown(sock) }
    private fun writerLoop(sock: Socket) { val o = out ?: return; while (running.get() && !sock.isClosed) { val frame = try { outQueue.poll(WRITER_IDLE_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue; try { o.write(frame); o.flush() } catch (t: Throwable) { Log.w("控制帧写出失败：${t.message}"); break } }; runCatching { sock.close() }; teardown(sock) }
    private fun teardown(sock: Socket) { if (client === sock) { ready = false; client = null; out = null; peerText = ""; outQueue.clear(); mainHandler.post { TvInputDispatcher.peer(false, "") }; runCatching { injectHandler.post { releaseAllInputs() } } }; runCatching { sock.close() } }
    private fun feed(data: ByteArray, n: Int) { synchronized(rxLock) { if (rxLen + n > rxBuf.size) { var c = rxBuf.size * 2; while (c < rxLen + n) c *= 2; rxBuf = rxBuf.copyOf(c) }; System.arraycopy(data, 0, rxBuf, rxLen, n); rxLen += n } }
    private fun pump(): List<() -> Unit> { val actions = ArrayList<() -> Unit>(); synchronized(rxLock) { var off = 0; while (rxLen - off >= ApxFrame.HEADER_SIZE) { if (!ApxFrame.isMagic(rxBuf, off, rxLen)) { off++; continue }; val payloadLen = ApxFrame.payloadLenAt(rxBuf, off); if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) { off++; continue }; val total = ApxFrame.totalSize(payloadLen); if (rxLen - off < total) break; if (!ApxFrame.verify(rxBuf, off, payloadLen)) { off += total; continue }; if (ApxFrame.streamIdAt(rxBuf, off) == ApxFrame.STREAM_CONTROL && payloadLen >= 1) { val body = ApxFrame.bodyAt(rxBuf, off, payloadLen); if (body != null && body.isNotEmpty()) { when (body[0].toInt() and 0xFF) { 'p'.code -> actions.add { sendControl(PONG) }; 0x01 -> if (body.size >= 5) actions.add { onMouse(body) }; 0x02 -> if (body.size >= 3) actions.add { onConsumer(body) }; 0x03 -> if (body.size >= 3) actions.add { onKeyboard(body) }; 0x04 -> { Log.i("收到触摸帧 body=" + body.size + " cmd=0x" + Integer.toHexString(body[0].toInt() and 0xFF) + " action=" + (if (body.size > 1) (body[1].toInt() and 0xFF) else -1)); if (body.size >= 9) actions.add { onTouch(body) } }; 0x07 -> if (body.size >= 7) actions.add { onGamepad(body) }; 0x20 -> if (body.size >= 4) actions.add { onClipboard(body) }; 0x22 -> if (body.size >= 2) actions.add { onPowerAction(body) }; 0x21 -> Log.i("收到反向剪贴板帧"); 0x05 -> actions.add { onOpenScreen() }; 0x10 -> Log.i("收到模块开关（忽略）"); 0x25 -> if (body.size >= 2) actions.add { onRequestInput(body) }; 0x26 -> if (body.size >= 2) actions.add { onInputText(body) }; 0x27 -> actions.add { onInputDone() } } } }; off += total }; if (off > 0) { System.arraycopy(rxBuf, off, rxBuf, 0, rxLen - off); rxLen -= off } }; return actions }
    private fun onMouse(body: ByteArray) { val buttons = body[1].toInt() and 0xFF; val dx = body[2].toInt().toByte().toInt(); val dy = body[3].toInt().toByte().toInt(); val wheel = if (body.size >= 5) body[4].toInt().toByte().toInt() else 0; if ((buttons and 1) != (lastButtons and 1)) Log.i("TcpControlServer 左键 " + (if ((buttons and 1) != 0) "按下" else "抬起") + " buttons=0x" + Integer.toHexString(buttons)); TvInputDispatcher.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false); TvInjector.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false); if ((buttons and 1) != 0 && (lastButtons and 1) == 0) TvInjector.pressDown(); if ((buttons and 1) == 0 && (lastButtons and 1) == 1) TvInjector.pressUp(); if (wheel != 0) TvInjector.scroll(wheel); lastButtons = buttons }
    private fun onTouch(body: ByteArray) { val action = body[1].toInt() and 0xFF; val x = (body[3].toInt() and 0xFF) or ((body[4].toInt() and 0xFF) shl 8); val y = (body[5].toInt() and 0xFF) or ((body[6].toInt() and 0xFF) shl 8); val fx = x / 65535f; val fy = y / 65535f; TvInputDispatcher.cursorMove(fx, fy, absolute = true); TvInjector.cursorMove(fx, fy, absolute = true); when (action) { 0 -> TvInjector.touchDown(fx, fy); 1 -> { TvInputDispatcher.cursorClick(); TvInjector.touchUp(fx, fy) } } }
    private fun onConsumer(body: ByteArray) { val bitmap = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8); Log.i("Consumer 帧 bitmap=0x" + Integer.toHexString(bitmap)); TvInjector.consumer(bitmap); for (bit in 0 until 16) { if ((bitmap ushr bit) and 1 == 0) continue; val kc = CONSUMER_MAP[bit] ?: continue; TvInputDispatcher.key(kc, true); TvInputDispatcher.key(kc, false) } }
    private fun onPowerAction(body: ByteArray) { val action = body[1].toInt() and 0xFF; Log.i("电源动作请求：action=$action"); TvInjector.powerAction(action) }
    private fun onOpenScreen() { Log.i("收到开副屏请求"); onOpenScreenRequest?.invoke() }
    private fun onRequestInput(body: ByteArray) { if (body.size < 2) return; val hintLen = body[1].toInt() and 0xFF; if (body.size < 2 + hintLen) return; val hint = String(body.copyOfRange(2, 2 + hintLen), Charsets.UTF_8); Log.i("远程输入请求: hint=$hint"); mainHandler.post { onRemoteInputRequest?.invoke(peerText, hint) } }
    private fun onInputText(body: ByteArray) { if (body.size < 4) return; val flags = body[1].toInt() and 0xFF; val textLen = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8); if (body.size < 4 + textLen) return; val text = String(body.copyOfRange(4, 4 + textLen), Charsets.UTF_8); Log.i("远程输入文本: flags=0x${Integer.toHexString(flags)} text=$text"); mainHandler.post { onRemoteInputText?.invoke(text, flags) } }
    private fun onInputDone() { Log.i("远程输入完成"); mainHandler.post { onRemoteInputDone?.invoke() } }
    private val MOD_KEYCODE = intArrayOf(113, 59, 57, 117, 114, 60, 58, 118)
    private fun onKeyboard(body: ByteArray) { val mod = body[1].toInt() and 0xFF; val now = HashSet<Int>(); val end = if (body.size < 9) body.size else 9; for (i in 3 until end) { val usage = body[i].toInt() and 0xFF; if (usage != 0) now.add(usage) }; if (mod and 8 != 0 && now == HashSet(listOf(0x0F))) { synchronized(keysLock) { pressedKeys.clear() }; if (com.allperiph.tv.core.RootInput.available) com.allperiph.tv.core.RootInput.run("input keyevent 26") else com.allperiph.tv.core.RootInput.run("input keyevent 26"); return }; for (bit in 0 until 8) { if (mod and (1 shl bit) != 0) now.add(0xE0 + bit) }; val prev = synchronized(keysLock) { HashSet(pressedKeys) }; for (u in prev) { if (u !in now) hidUp(u) }; for (u in now) { if (u !in prev) hidDown(u) }; synchronized(keysLock) { pressedKeys.clear(); pressedKeys.addAll(now) } }
    /**
     * v1.7：**断链统一归位输入状态**（与手机被控端、PC 端同一套做法）。
     * 不做的后果：按住的键不会自己抬起；`pressedKeys` 与真实状态脱节会让重连后
     * 第一帧键盘差分辨成"本来就按着"，表现为**第一次按键不生效**。
     * 必须在注入线程上执行（调用方用 injectHandler.post）。
     */
    private fun releaseAllInputs() {
        val held = synchronized(keysLock) { HashSet(pressedKeys) }
        synchronized(keysLock) { pressedKeys.clear() }
        for (u in held) runCatching { hidUp(u) }
        if (lastButtons and 0x01 != 0) runCatching { com.allperiph.tv.core.TvInjector.pressUp() }
        lastButtons = 0
    }

    private fun hidDown(usage: Int) { if (usage in 0xE0..0xE7) { val kc = MOD_KEYCODE[usage - 0xE0]; TvInputDispatcher.key(kc, true); TvInjector.key(kc, true); return }; val (kc, ch) = HID_MAP[usage] ?: (0 to '\u0000'); if (kc != 0) { TvInputDispatcher.key(kc, true); TvInjector.key(kc, true) }; if (ch != '\u0000') { TvInputDispatcher.text(ch); TvInjector.text(ch) } }
    private fun hidUp(usage: Int) { if (usage in 0xE0..0xE7) { val kc = MOD_KEYCODE[usage - 0xE0]; TvInputDispatcher.key(kc, false); TvInjector.key(kc, false); return }; val (kc, _) = HID_MAP[usage] ?: (0 to '\u0000'); if (kc != 0) { TvInputDispatcher.key(kc, false); TvInjector.key(kc, false) } }
    private fun onClipboard(body: ByteArray) { val len = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8); if (len <= 0 || len > body.size - 3) return; TvInjector.clipboard(String(body.copyOfRange(3, 3 + len), Charsets.UTF_8)) }
    private fun onGamepad(body: ByteArray) { val buttons = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8); val x = body[3].toInt().toByte().toInt(); val y = body[4].toInt().toByte().toInt(); val rx = body[5].toInt().toByte().toInt(); val ry = body[6].toInt().toByte().toInt(); TvInputDispatcher.onGamepad(buttons, x, y, rx, ry); TvInjector.gamepad(buttons, x, y, rx, ry) }
    fun sendControl(body: ByteArray): Boolean { if (!ready) return false; val frame = synchronized(writeLock) { seq = (seq + 1) and 0x7FFFFFFF; ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq) }; if (outQueue.offer(frame)) return true; outQueue.poll(); return outQueue.offer(frame) }
    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL) { val textBytes = text.toByteArray(Charsets.UTF_8); val body = ByteArray(1 + 1 + 2 + textBytes.size); body[0] = ApxFrame.INPUT_TEXT.toByte(); body[1] = flags.toByte(); body[2] = (textBytes.size and 0xFF).toByte(); body[3] = ((textBytes.size ushr 8) and 0xFF).toByte(); textBytes.copyInto(body, 4); sendControl(body) }
    fun sendInputDone() { sendControl(byteArrayOf(ApxFrame.INPUT_DONE.toByte())) }
    private fun readU32Le(ins: InputStream): Int { val b = ByteArray(4); if (!readFully(ins, b)) return -1; var v = 0; for (i in 0 until 4) v = v or ((b[i].toInt() and 0xFF) shl (8 * i)); return v }
    private fun readFully(ins: InputStream, dst: ByteArray): Boolean { var got = 0; while (got < dst.size) { val n = try { ins.read(dst, got, dst.size - got) } catch (_: Throwable) { -1 }; if (n <= 0) return false; got += n }; return true }
    companion object { const val PORT = 9511; private const val MAX_TOKEN = 256; private const val HANDSHAKE_TIMEOUT_MS = 5_000; private const val QUEUE_CAP = 256; private const val WRITER_IDLE_MS = 200L; private const val READ_TIMEOUT_MS = 3_000; private val PONG = "pong".toByteArray(Charsets.UTF_8); private fun peerTextOf(sock: Socket): String = sock.inetAddress?.hostAddress?.let { "$it:${sock.port}" } ?: "?"; fun localIpv4(): String? = try { Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }.flatMap { ni -> Collections.list(ni.inetAddresses) }.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress } catch (_: Throwable) { null }; private val HID_MAP: Map<Int, Pair<Int, Char>> = buildMap { put(0x29, 4 to '\u0000'); put(0x4A, 3 to '\u0000'); put(0x2B, 61 to '\u0000'); for (i in 0 until 12) put(0x3A + i, 131 + i to '\u0000'); put(0x28, 66 to '\u0000'); put(0x2A, 67 to '\u0000'); put(0x2C, 62 to ' '); put(0x4C, 112 to '\u0000'); put(0x66, 26 to '\u0000'); put(0x65, 82 to '\u0000'); put(0x4F, 22 to '\u0000'); put(0x50, 21 to '\u0000'); put(0x51, 20 to '\u0000'); put(0x52, 19 to '\u0000'); for (c in 'a'..'z') put(0x04 + (c - 'a'), 0 to c); val digits = "1234567890"; for (i in digits.indices) put(0x1E + i, 0 to digits[i]) }; private val CONSUMER_MAP: Map<Int, Int> = mapOf(0 to 24, 1 to 25, 2 to 164, 3 to 26, 4 to 85, 5 to 88, 6 to 87) }
}