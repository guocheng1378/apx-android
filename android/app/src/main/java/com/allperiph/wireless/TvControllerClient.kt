package com.allperiph.wireless

import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.util.Log
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
    /** 模式状态上报线程（0x31）；见 [startModeStatus] */
    private var modeThread: Thread? = null

    @Volatile var onReverseClipboard: ((String) -> Unit)? = null
    @Volatile var onRemoteInputRequest: ((String, String) -> Unit)? = null
    @Volatile var onRemoteInputText: ((String, Int) -> Unit)? = null
    @Volatile var onRemoteInputDone: (() -> Unit)? = null

    private val rxLock = Any()
    private var rxBuf = ByteArray(4096)
    private var rxLen = 0

    /** sendControl 断线丢帧日志节流（5s 一条）—— 前端与运行日志此前对丢帧完全无感 */
    @Volatile private var lastDropLogAt = 0L

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
        s.tcpNoDelay = true
        s.keepAlive = true   // 死链路探测：对端悄悄消失时让内核发探测，帮助 reader 尽早退出走重连
        s.connect(InetSocketAddress(host, port), 5000)
        sock = s; out = s.getOutputStream()
        val tok = token.toByteArray(Charsets.UTF_8)
        val hdr = ByteArray(4); hdr[0] = (tok.size and 0xFF).toByte(); hdr[1] = ((tok.size ushr 8) and 0xFF).toByte(); hdr[2] = 0; hdr[3] = 0
        out!!.write(hdr); if (tok.isNotEmpty()) out!!.write(tok); out!!.flush()
        ready = true; synchronized(rxLock) { rxLen = 0 }
        thread(name = "tvctrl-reader") { readerLoop(s) }
        startTx()
        startModeStatus()
        // 重连后自动重发光标浮层偏好：被控端状态是会话级的，重连即重置，
        // 不重发的话用户上次关掉的浮层又会冒出来
        lastOverlayCursorEnabled?.let { sendControl(byteArrayOf(0x32.toByte(), (if (it) 1 else 0).toByte())) }
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
                // v184：magic / 长度异常改为**逐字节重同步**（原 off = rxLen 会因一次错位
                // 丢掉后续全部合法帧）；并补上尾部 CRC32 校验。
                if (!ApxFrame.isMagic(rxBuf, off, rxLen)) { off++; continue }
                val payloadLen = ApxFrame.payloadLenAt(rxBuf, off)
                if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) { off++; continue }
                val total = ApxFrame.totalSize(payloadLen)
                if (rxLen - off < total) break
                if (!ApxFrame.verify(rxBuf, off, payloadLen)) { off += total; continue }
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
                val cb = onRemoteInputRequest
                if (cb != null) cb(host, hint) else handleRequestInputFallback(hint)
            }
            0x26 -> {
                // INPUT_TEXT: [0x26, flags, textLen, text...]
                if (body.size < 4) return
                val flags = body[1].toInt() and 0xFF
                val textLen = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
                if (body.size < 4 + textLen) return
                val text = String(body.copyOfRange(4, 4 + textLen), Charsets.UTF_8)
                val cb = onRemoteInputText
                if (cb != null) cb(text, flags) else injectRemoteTextFallback(text, flags)
            }
            0x27 -> { onRemoteInputDone?.invoke() }
        }
    }

    /**
     * 0x25 兜底（新）：对端输入框获焦 → 请求本机代输。
     * 此前 onRemoteInputRequest 全项目零赋值 —— 帧到了没人弹窗、静默丢弃：
     * 手机控手机 / 手机控 TV 时「对端获焦自动请本机代输」整个是死的
     * （PC 端 Ctrl9511Client 早有接线，手机端漏了这半边）。
     * 回调在 reader 线程，弹窗必须切主线程；Android 10+ 后台启动 Activity 受限，
     * 但收到此帧时用户正在操作控制端（前台），直接启动与被控侧 RemoteInputActivity.start 同口径。
     */
    private fun handleRequestInputFallback(hint: String) {
        val ctx = com.allperiph.App.context
        if (ctx == null) { Log.w("TvCtrl", "收到代输请求，但 App.context 未就绪"); return }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                com.allperiph.controlled.RemoteInputActivity.start(ctx, host, hint, fromClient = true)
            }.onFailure { Log.w("TvCtrl", "代输弹窗失败：${it.message}") }
        }
    }

    /**
     * 0x26 兜底（新）：对端替本机打的字落到本机获焦输入框。
     * 与 ControlledService.injectRemoteText 同口径：退格删一格 / 正文走无障碍
     * typeText→paste 兜底；CANCEL 留空（三端都未实现整段撤销，保留帧语义）。
     */
    private fun injectRemoteTextFallback(text: String, flags: Int) {
        val svc = com.allperiph.shared.accessibility.ApxAccessibilityService.instance
        if (svc == null) { Log.w("TvCtrl", "代输没落地：无障碍服务未就绪"); return }
        when {
            flags and ApxFrame.INPUT_FLAG_BACKSPACE != 0 -> svc.deleteChar()
            flags and ApxFrame.INPUT_FLAG_CANCEL != 0 -> Unit
            else -> if (text.isNotEmpty() && !svc.typeText(text) && !svc.paste(text)) {
                Log.w("TvCtrl", "代输注入失败（输入框没聚焦？）")
            }
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
                val target = out
                if (target == null) continue
                try { target.write(frame); target.flush() } catch (t: Throwable) {
                    // fix：写失败必须区分「旧连接残留写」与「当前连接真死」。
                    // 旧写法无条件 ready=false —— 自动重连刚把 ready 置回 true 后，
                    // 残留在队列里的旧帧写旧流抛错会把它再次覆盖成 false，而 reader
                    // 侧无异常、永不触发重连 → 链路钉死在「连着但 sendControl 全部
                    // 静默丢帧」：蓝点定格、滑动/点按无反应，直到下次被动断线。
                    if (out === target) {
                        Log.w("TvCtrl", "写出失败：${t.message} → 关闭连接触发自动重连")
                        ready = false
                        runCatching { sock?.close() }   // 借 readerLoop 退出走既有重连路径
                    } else {
                        Log.w("TvCtrl", "忽略旧连接残留写失败：${t.message}")
                    }
                }
            }
        }
    }

    fun sendControl(body: ByteArray): Boolean {
        if (!ready) {
            // 断线期输入帧全部静默丢弃是「功能像坏了但界面毫无提示」的元凶：
            // 这里节流记一条（运行日志可见），链路恢复时另记一条。
            val now = System.currentTimeMillis()
            if (now - lastDropLogAt >= 5000) {
                lastDropLogAt = now
                Log.w("TvCtrl", "连接未就绪，丢弃输入帧（5s 内仅记一次）")
            }
            return false
        }
        val frame = synchronized(writeLock) { seq = (seq + 1) and 0x7FFFFFFF; ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq) }
        while (txQueue.size > 512) txQueue.poll()
        return txQueue.offer(frame)
    }

    /** P0-1：dx/dy/wheel 各占一个有符号字节，超出 [-127,127] 会回绕成反向 —— 这里钳位兜底；被钳掉的余数由发送源端（GestureEngine 的 fracX/fracY 亚像素累加器）带入下一帧，不丢位移
     *  第 6 字节 pan（横向滚动，右为正）：v223.1 起无线出口也带上，此前只有 USB/蓝牙出口带。
     *  老 PC 端按 bodyLen>=5 读前 5 字节、忽略多出来的字节；老手机端同理 —— 双向兼容。 */
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) = sendControl(byteArrayOf(0x01.toByte(), buttons.toByte(), dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte(), pan.coerceIn(-127, 127).toByte()))
    fun touch(action: Int, buttons: Int, x: Int, y: Int) = sendControl(byteArrayOf(0x04.toByte(), action.toByte(), buttons.toByte(), (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(), (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(), 0, 0))
    /** v184：特殊键/组合键（0x28）：mod 位图 1=Ctrl 2=Shift 4=Alt，vk 用 Windows VK 码 */
    fun sendSpecialKey(mod: Int, vk: Int) = sendControl(byteArrayOf(0x28.toByte(), mod.toByte(), vk.toByte()))
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
        // 格式：[0x25, hintLen, hint...] — 与接收端 ControlServer onRequestInput 一致
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

    /**
     * 光标浮层开关（0x32，v223.10）：关/开**被控端**的光标浮层。
     * 手机控手机 / 手机控 TV 时，被控端会把本端光标画成全局浮层（蓝点）；
     * 有的场景嫌它碍事 —— 控制端给个开关，经控制面告诉被控端收起来。
     * 注意边界：浮层是**被控端全局**的（PC 控手机时同一颗点也走它），关掉对
     * 所有控制端生效，不是「只对我的会话」。
     */
    fun overlayToggle(show: Boolean) =
        sendControl(byteArrayOf(0x32.toByte(), (if (show) 1 else 0).toByte()))

    /**
     * 模式状态上报（0x31）：[flags] 的位布局见 [ModeStatus] / phone_mode.hpp。
     *
     * 面板侧那三个开关原来只有无线是真的，蓝牙与 USB 是 PC 无从判断的假开关 ——
     * 改成由**知道真相的这一端**（手机）把三条出口的可用性与当前在用哪条报上去。
     */
    fun sendModeStatus(flags: Int): Boolean =
        sendControl(byteArrayOf(0x31.toByte(), (flags and 0xFF).toByte()))

    /**
     * 连上先报一次，之后每 2s 一次。
     *
     * 为什么是轮询而不是「变化时推」：可用性来自三处互相独立的状态（局域网 / 蓝牙开关 /
     * USB gadget），逐个挂监听既不统一也容易漏；2s 的显示延迟对状态行完全够用，
     * 而且是**纯上报**、不参与输入时序。链路断了 sendControl 会如实返回 false（有节流日志）。
     */
    private fun startModeStatus() {
        if (modeThread?.isAlive == true) return
        modeThread = thread(name = "tvctrl-mode") {
            var lastGen = com.allperiph.core.Uplink.generation
            while (running.get()) {
                if (ready) {
                    val flags = runCatching { ModeStatus.currentFlags() }.getOrDefault(0)
                    sendModeStatus(flags)
                }
                // 连接模式刚切换过：不等那 2s，立刻再报一帧，让面板与手机同时改口。
                // 没有这一步，用户切完模式最长要盯 2s 的旧状态，看着就是「没切过去」。
                lastGen = com.allperiph.core.Uplink.generation
                var waited = 0
                while (running.get() && waited < 2000 &&
                    com.allperiph.core.Uplink.generation == lastGen
                ) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { return@thread }
                    waited += 100
                }
            }
        }
    }

    companion object {
        const val PORT = 9511

        /**
         * 控制端最近一次的光标浮层偏好（null = 用户没动过）。
         * 在 [openSocket] 成功后自动重发 —— 网络抖动自动重连后，被控端的浮层
         * 不会又冒出来；用户没动过开关就是 null，什么都不发（老行为）。
         */
        @Volatile
        var lastOverlayCursorEnabled: Boolean? = null
    }
}