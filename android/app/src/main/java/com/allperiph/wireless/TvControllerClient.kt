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
    /**
     * R2 通道加密（2026-11-17，能力位 bit8）：对端（被控手机）静态公钥的 64 位 hex，
     * 来自信标第 5 段（[com.allperiph.shared.net.WirelessBeacon]）。空 = 本连接不具备
     * 加密条件（TV / 旧版被控端 / 手动连接），链路恒明文。见 docs/REQ-无线安全配对.md §4.1。
     */
    private val peerPubKeyHex: String = "",
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

    /**
     * 对端能力位图（0x33 能力协商，2026-11-11）：对端连接后报上来的支持能力，
     * 见 [com.allperiph.shared.proto.ApxFrame] 的 CAP_* 常量。0 = 未收到（旧版对端没有该
     * 机制，此时能力帧族一律不发）。新会话建立（[openSocket]）时清零重收。
     */
    @Volatile var peerCaps: Long = 0L
        private set

    private val rxLock = Any()
    private var rxBuf = ByteArray(4096)
    private var rxLen = 0

    /** sendControl 断线丢帧日志节流（5s 一条）—— 前端与运行日志此前对丢帧完全无感 */
    @Volatile private var lastDropLogAt = 0L

    // ---- R2 通道加密（0x35 / CAP_ENCRYPT bit8，2026-11-17）：本端是 Initiator ----
    // 锁序恒为 writeLock → noiseLock（会话操作不持 noiseLock 拿 writeLock），无环。
    private val noiseLock = Any()
    /** 本端静态密钥对（openSocket 时 ensureKeyPair 取/生成；null = 无法加密） */
    private var ownKp: Pair<ByteArray, ByteArray>? = null
    /** 传输态 Noise 会话（split 后）；null = 尚无 */
    private var noiseSess: NoiseSession? = null
    /** msg1 已发、等 msg2：出向业务帧攒 [pendingBuf]，握手完成（或放弃）后补发 */
    @Volatile private var noiseWait = false
    /** 加密开关：0x35 完成后置 true，此后出入帧 payload 均为 AEAD 密文（帧头明文） */
    @Volatile private var noiseActive = false
    /** WAIT 期间攒下的待发帧（writeLock 内读写） */
    private val pendingBuf = ArrayDeque<ByteArray>()
    /** R2 诊断：解密/加密失败丢帧计数（每 50 次记一条，不断链） */
    private var noiseDrops = 0

    /** 本连接能否发起加密（能力上报与 0x35 发起共用同一判据） */
    private fun noiseCapable(): Boolean =
        token.isNotEmpty() && peerPubKeyHex.length == 64 && ownKp != null

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
        // v-fix（link-hardening）：读超时 —— 此前 soTimeout=0，对端半开（静默消失 / NAT
        // 表过期，没有 FIN/RST）时 reader 永久阻塞、ready 恒为 true，sendControl 表面成功
        // 实际全喰死连接，只能等内核 keepalive（分钟级）或写失败才发现。服务端 ControlServer
        // 早就是 3s×8 口径，客户端现在对齐；正常链路每 1s 有 ping/pong，不会误伤。
        s.soTimeout = READ_TIMEOUT_MS
        sock = s; out = s.getOutputStream()
        val tok = token.toByteArray(Charsets.UTF_8)
        val hdr = ByteArray(4); hdr[0] = (tok.size and 0xFF).toByte(); hdr[1] = ((tok.size ushr 8) and 0xFF).toByte(); hdr[2] = 0; hdr[3] = 0
        out!!.write(hdr); if (tok.isNotEmpty()) out!!.write(tok); out!!.flush()
        ready = true; synchronized(rxLock) { rxLen = 0 }
        thread(name = "tvctrl-reader") { readerLoop(s) }
        startTx()
        startModeStatus()
        // R2 加密：新会话从明文开始（对端 0x33 报 bit8 后本端发起，见 initiateNoise）。
        // 顺带取/生成本端静态密钥对（进程级缓存，首连落盘一次）
        ownKp = com.allperiph.App.context?.let { NoiseSession.ensureKeyPair(it) }
        synchronized(writeLock) {
            noiseWait = false; noiseActive = false; pendingBuf.clear()
            synchronized(noiseLock) { noiseSess = null }
        }
        // 能力协商（0x33，2026-11-11）：新会话清空上一会话的对端能力，并把本端能力报给对端。
        // 接收侧存 [peerCaps]；不发的话对端学到的永远是 0，能力帧族全被门掉。
        peerCaps = 0L
        sendCapsSync()
        // v223.29.12 热修：会话建立**即刻**上报一帧 0x31，不等 2s 轮询线程。
        // PC 端握手时会把 modeReported_ 清零（「新会话从未上报开始」）；若模式线程恰在
        // disconnect/快速重连的 isAlive 早退竞态里死掉（startModeStatus 里
        // `if (modeThread?.isAlive == true) return` 早退而旧线程随后退出），面板就会
        // 永久停在「手机未上报」。即刻一帧把这扇窗关掉；轮询线程存活时照常补报。
        sendModeStatus(runCatching { ModeStatus.currentFlags() }.getOrDefault(0))
        // 重连后自动重发光标浮层偏好：被控端状态是会话级的，重连即重置，
        // 不重发的话用户上次关掉的浮层又会冒出来
        lastOverlayCursorEnabled?.let { sendControl(byteArrayOf(0x32.toByte(), (if (it) 1 else 0).toByte())) }
        Log.i("TvCtrl", "已连 TV $host:$port")
        true
    } catch (t: Throwable) { Log.e("TvCtrl", "连 TV 失败：${t.message}"); runCatching { s_close() }; false }

    fun disconnect() {
        running.set(false); ready = false
        // R2 加密：密钥随会话走，断开即弃（重连走 openSocket 重新握手）
        synchronized(writeLock) {
            noiseWait = false; noiseActive = false; pendingBuf.clear()
            synchronized(noiseLock) { noiseSess = null }
        }
        runCatching { txQueue.clear() }; runCatching { txThread?.interrupt() }; txThread = null; s_close()
    }
    private fun s_close() = runCatching { sock?.close() }.also { sock = null; out = null }

    private fun readerLoop(s: Socket) {
        val ins = s.getInputStream(); val buf = ByteArray(4096)
        var idleTimeouts = 0
        while (running.get() && !s.isClosed) {
            val n = try {
                ins.read(buf)
            } catch (_: java.net.SocketTimeoutException) {
                // v-fix（link-hardening）：半开检测 —— 正常链路 ping/pong 每 1s 有字节，
                // 3s 收不到任何数据才计一轮；连续 8 轮（≈24s）判死，走下方自动重连。
                // 镜像服务端 ControlServer.readerLoop 的 3s×8 口径，两端同一把尺。
                if (++idleTimeouts >= READ_TIMEOUT_MAX_MISSES) {
                    Log.w("TvCtrl", "读空闲超时 ${READ_TIMEOUT_MAX_MISSES} 次，判定链路已失效：$host:$port")
                    break
                }
                continue
            } catch (_: Throwable) { -1 }
            idleTimeouts = 0
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
                val payloadLen0 = ApxFrame.payloadLenAt(rxBuf, off)
                if (payloadLen0 < 0 || payloadLen0 > ApxFrame.MAX_PAYLOAD) { off++; continue }
                var payloadLen = payloadLen0
                var total = ApxFrame.totalSize(payloadLen)
                if (rxLen - off < total) break
                // R2 加密（2026-11-17）：active 后 payload 整段是 AEAD 密文（明文 = body + CRC 尾，
                // 帧 16 字节头保持明文）。先解密、重写 payloadLen，再走常规 CRC 校验 ——
                // CRC 尾在密文里，解密前的 verify 恒不成立。失败按丢帧计数，不断链。
                if (noiseActive) {
                    if (payloadLen <= NoiseSession.KEY_LEN) {   // 密文至少要装下 16 字节 Poly 标签
                        if (noiseDrops++ % 50 == 0) Log.w("TvCtrl", "R2丢帧#${noiseDrops}: 密文过短 len=$payloadLen")
                        off += total; continue
                    }
                    val cipher = rxBuf.copyOfRange(off + ApxFrame.HEADER_SIZE, off + ApxFrame.HEADER_SIZE + payloadLen)
                    val plainLen = synchronized(noiseLock) { noiseSess?.decrypt(cipher, payloadLen) ?: 0 }
                    if (plainLen <= 0) {
                        if (noiseDrops++ % 50 == 0) Log.w("TvCtrl", "R2丢帧#${noiseDrops}: 解密失败 len=$payloadLen")
                        off += total; continue
                    }
                    System.arraycopy(cipher, 0, rxBuf, off + ApxFrame.HEADER_SIZE, plainLen)
                    putU32Le(rxBuf, off + 8, plainLen)
                    payloadLen = plainLen
                    total = ApxFrame.totalSize(plainLen)
                }
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
            // 0x28 特殊键回传（2026-11-14 审查补齐）：对端替本机代输时，编辑键排的特殊键
            // 由被控侧经本帧回传 —— PC 控制端早有接收，手机/TV 控制端一直静默丢弃。
            // 本机侧注入走与代输文本同两条通道（输入法/无障碍）；方向键/Tab 需要焦点
            // 节点级的光标操作，暂不支持、如实打日志不装死。
            0x28 -> {
                if (body.size >= 3) specialKeyFallback(body[1].toInt() and 0xFF, body[2].toInt() and 0xFF)
            }
            // 0x33 能力协商（2026-11-11）：对端报能力位图（u64 小端 8 字节），存进 [peerCaps]。
            0x33 -> {
                if (body.size >= 9) {
                    var caps = 0L
                    for (i in 0 until 8) caps = caps or ((body[1 + i].toLong() and 0xFF) shl (8 * i))
                    peerCaps = caps
                    Log.i("TvCtrl", "对端能力：0x${java.lang.Long.toHexString(caps)}")
                    // R2 通道加密（2026-11-17）：对端报 bit8 → 具备条件就发起 0x35 握手
                    if (caps and ApxFrame.CAP_ENCRYPT != 0L) initiateNoise()
                }
            }
            // 0x35 R2 加密握手（2026-11-17）：对端（Responder）回 msg2，本端 split 后进加密态
            0x35 -> if (body.size >= 1) onNoiseHandshakeMsg2(body)
            // 0x38 查找手机（2026-11-11，能力位 bit2）：手机控 PC / TV 的链路上，PC 托盘
            // 「查找手机」反向让本机响铃。直接落 FindPhoneRinger（app 模块内可见，无需回调
            // 接线；与 0x25 的 callback+fallback 是历史口径不同，这里没有 UI 交互就不需要）。
            // handle 内部切主线程，reader 线程直调安全。
            0x38 -> {
                if (body.size >= 2) com.allperiph.findphone.FindPhoneRinger.handle(body[1].toInt() and 0xFF)
            }
            // 0x41 通知回复（2026-11-14，能力位 bit1）：手机控 PC / TV 的链路上，PC 服务端
            // 气泡点回复反向发来 0x41。落 app 模块 NotificationReply（与 0x44 的 PhoneFiles
            // 同口径直调，无 UI 回调要接）；handle 内部必回 0x43 终态（err 语义见回执常量）。
            0x41 -> {
                if (body.size >= 5) com.allperiph.notify.NotificationReply.handle(0x41, body, ::sendControl)
            }
            // 0x44 浏览本机目录（2026-11-11，能力位 bit3）：手机控 PC / TV 的链路上，对端
            // 也可以浏览本机文件（三端对等）。落 app 模块 PhoneFiles（与 0x38 同口径直调，
            // 无 UI 回调要接）；reply 走本链路 sendControl，对端 IP = 本客户端连的服务端 host。
            0x44 -> {
                if (body.size >= 3) com.allperiph.files.PhoneFiles.handle(0x44, body, ::sendControl, host)
            }
            // 0x46 取走文件：同上，PhoneFiles 内部经 9512 把文件推给对端（host）。
            0x46 -> {
                if (body.size >= 3) com.allperiph.files.PhoneFiles.handle(0x46, body, ::sendControl, host)
            }
            // 0x48/0x4A/0x4B 相册投影（2026-11-12，能力位 bit4）：手机控 PC / TV 的链路上，
            // 对端也可以浏览本机相册（三端对等）。落 app 模块 PhoneAlbum（与 0x44 同口径直调，
            // 无 UI 回调要接）；reply 走本链路 sendControl，对端 IP = 本客户端连的服务端 host。
            0x48 -> {
                if (body.size >= 5) com.allperiph.files.PhoneAlbum.handle(0x48, body, ::sendControl, host)
            }
            0x4A -> {
                if (body.size >= 2) com.allperiph.files.PhoneAlbum.handle(0x4A, body, ::sendControl, host)
            }
            0x4B -> {
                if (body.size >= 5) com.allperiph.files.PhoneAlbum.handle(0x4B, body, ::sendControl, host)
            }
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

    /**
     * 0x28 特殊键落地（与 [injectRemoteTextFallback] 同通道）：退格走输入法删除、
     * 无输入连接再退无障碍删一字；回车走输入法提交；Esc 退无障碍全局返回。
     * 其余（方向键/Tab/翻页）需要焦点节点的光标语义，当前通道做不到，打日志。
     */
    private fun specialKeyFallback(mod: Int, vk: Int) {
        val svc = com.allperiph.shared.accessibility.ApxAccessibilityService.instance
        val ok = when (vk) {
            0x08 -> com.allperiph.shared.ime.ApxImeService.delete() || (svc?.deleteChar() == true)
            0x2E -> svc?.deleteChar() == true   // Delete（删光标后一字）：无障碍近似按删前一字处理
            0x0D -> com.allperiph.shared.ime.ApxImeService.key(android.view.KeyEvent.KEYCODE_ENTER, true)
            0x1B -> { svc?.back(); true }
            else -> false
        }
        if (!ok) Log.w("TvCtrl", "代输特殊键未落地（输入框没聚焦？）：vk=0x${vk.toString(16)} mod=$mod")
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
        // R2 三态（2026-11-17，与 PC 端 Ctrl9511Client::sendCmd 同口径）：
        // ① WAIT（msg1 已发等 msg2）→ 业务帧攒 pendingBuf，握手完成后按密文补发；
        // ② active → payload 整段（body + CRC 尾）AEAD 加密，帧头明文，payloadLen 重写为密文长；
        // ③ 明文 → 直发。状态判定、攒帧、加密收口在 writeLock（0x35 处理侧同锁翻转，无交叉窗口）。
        var buffered = false
        var encrypted = false
        val frame: ByteArray? = synchronized(writeLock) {
            seq = (seq + 1) and 0x7FFFFFFF
            val f = ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq)
            when {
                noiseWait -> {
                    // 握手窗口有限：攒帧积压过深直接丢最老（对端升级假死时也不至于无限占内存）
                    if (pendingBuf.size >= PENDING_CAP) pendingBuf.removeFirst()
                    pendingBuf.addLast(f)
                    buffered = true
                    null
                }
                noiseActive -> {
                    encrypted = true
                    val plainLen = f.size - ApxFrame.HEADER_SIZE
                    if (plainLen > NoiseSession.MAX_PLAIN) {   // 超 Noise 单条明文上限：丢帧（同 PC 端口径）
                        if (noiseDrops++ % 50 == 0) Log.w("TvCtrl", "R2丢帧#${noiseDrops}: 明文超限 $plainLen")
                        null
                    } else encryptFrame(f)
                }
                else -> f
            }
        }
        if (buffered) return true   // 已入待发队列，握手完成后补发
        if (frame == null) return false   // 加密失败（会话被重置等）
        while (txQueue.size > 512) txQueue.poll()
        // 入队失败 + 本帧已加密：发送 nonce 已前进而对端收不到这条密文，两端 AEAD
        // 计数器错位、后续帧全解不开 —— 只能断链走自动重连（写线程停摆本身也断定了）
        if (!txQueue.offer(frame)) {
            if (encrypted) {
                Log.w("TvCtrl", "R2 加密帧入队失败（发送队列满），断链重连")
                runCatching { sock?.close() }
            }
            return false
        }
        return true
    }

    /** P0-1：dx/dy/wheel 各占一个有符号字节，超出 [-127,127] 会回绕成反向 —— 这里钳位兜底；被钳掉的余数由发送源端（GestureEngine 的 fracX/fracY 亚像素累加器）带入下一帧，不丢位移
     *  第 6 字节 pan（横向滚动，右为正）：v223.1 起无线出口也带上，此前只有 USB/蓝牙出口带。
     *  老 PC 端按 bodyLen>=5 读前 5 字节、忽略多出来的字节；老手机端同理 —— 双向兼容。 */
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) = sendControl(byteArrayOf(0x01.toByte(), buttons.toByte(), dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte(), pan.coerceIn(-127, 127).toByte()))
    fun touch(action: Int, buttons: Int, x: Int, y: Int) = sendControl(byteArrayOf(0x04.toByte(), action.toByte(), buttons.toByte(), (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(), (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(), 0, 0))
    /**
     * 绝对指针帧（0x06，2026-11-13「指哪打哪」第二模式）：触控板页小地图点按/拖动走这条。
     * 载荷与 0x04 副屏触摸同布局（action 0按下/1抬起/2移动 + buttons + x/y 归一化
     * 0..65535 + 保留 2 字节），但 PC 端映射**整个虚拟桌面**（多屏也能点到），
     * Android 被控端映射整屏，语义见 ApxFrame.CAP_ABSOLUTE_POINTER 注释。
     *
     * 能力门控：对端必须报过 0x33 且含 CAP_ABSOLUTE_POINTER（bit5）—— 旧版被控端对
     * 未知帧号安静忽略，但发也是白发，这里提前挡一道；对端没报过 0x33（peerCaps==0，
     * 老版本）照发兜底，同 sendFindPhone 口径。
     */
    fun absoluteTouch(action: Int, buttons: Int, x: Int, y: Int): Boolean {
        if (!ready) return false
        if (peerCaps != 0L && peerCaps and ApxFrame.CAP_ABSOLUTE_POINTER == 0L) return false
        return sendControl(byteArrayOf(0x06.toByte(), action.toByte(), buttons.toByte(), (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(), (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(), 0, 0))
    }
    /** v184：特殊键/组合键（0x28）：mod 位图 1=Ctrl 2=Shift 4=Alt，vk 用 Windows VK 码 */
    fun sendSpecialKey(mod: Int, vk: Int) = sendControl(byteArrayOf(0x28.toByte(), mod.toByte(), vk.toByte()))
    fun keyboard(usage: Int, down: Boolean, mod: Int = 0) = if (down) sendControl(byteArrayOf(0x03.toByte(), mod.toByte(), 0, usage.toByte())) else sendControl(byteArrayOf(0x03.toByte(), 0, 0, 0))
    fun consumer(bitmap: Int) = sendControl(byteArrayOf(0x02.toByte(), ((bitmap and 0xFF).toByte()), ((bitmap ushr 8).toByte())) )
    fun power(action: Int) = sendControl(byteArrayOf(0x22.toByte(), action.toByte()))
    fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int) = sendControl(byteArrayOf(0x07.toByte(), (buttons and 0xFF).toByte(), ((buttons ushr 8) and 0xFF).toByte(), x.toByte(), y.toByte(), rx.toByte(), ry.toByte()))
    /**
     * 手柄截图（0x4C，issue#170 #7）：让受控 PC 抓自己屏幕存 PNG。门控同 absoluteTouch
     * —— 对端报过 0x33 且缺 CAP_GAMEPAD_SHOT 位（PC 端还没升级）时跳过返回 false，
     * 手机侧好提示「升级电脑端」；对端没报过 0x33（老版本）照发兜底，未知帧号安静忽略。
     */
    fun gamepadShot(): Boolean {
        if (!ready) return false
        if (peerCaps != 0L && peerCaps and ApxFrame.CAP_GAMEPAD_SHOT == 0L) return false
        return sendControl(byteArrayOf(ApxFrame.CMD_GAMEPAD_SHOT.toByte()))
    }

    /**
     * 副屏远程开关（0x4D，2026-11-14 成宇「能在 App 副屏页加一个按钮一键启用 PC 的
     * 副屏开关吗」）：副屏页右下角入口 → 让受控 PC 插/拔虚拟显示器（热键 screen.toggle
     * 同款动作，含配置落盘）。门控同 gamepadShot —— 对端报过 0x33 且缺
     * CAP_SCREEN_TOGGLE 位（PC 端还没升级）时跳过返回 false，手机侧好提示「升级电脑端」；
     * 对端没报过 0x33（老版本）照发兜底，未知帧号安静忽略。
     */
    fun screenToggle(on: Boolean): Boolean {
        if (!ready) return false
        if (peerCaps != 0L && peerCaps and ApxFrame.CAP_SCREEN_TOGGLE == 0L) return false
        return sendControl(ApxFrame.packScreenToggle(on))
    }
    fun sendClipboard(text: String): Boolean {
        if (text.isEmpty()) return false
        val bytes = text.toByteArray(Charsets.UTF_8); if (bytes.size > 0xFFFF) return false
        val body = ByteArray(3 + bytes.size); body[0] = 0x20.toByte(); body[1] = (bytes.size and 0xFF).toByte(); body[2] = ((bytes.size ushr 8) and 0xFF).toByte(); bytes.copyInto(body, 3)
        return sendControl(body)
    }

    // 远程输入发送
    fun requestInput(hint: String) {
        // v-fix：改走 ApxFrame.packRequestInput —— 旧手写实现 `body[1] = hintBytes.size.toByte()`
        // 不截断，hint 超 255 字节时对端按 u8 hintLen 解析会整体错位（弹出乱码提示）；
        // packer 已按 u8 截断且注明「对齐四处解析」，顺带消掉一处重复组帧实现。
        sendControl(ApxFrame.packRequestInput(hint, 0))
    }

    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL): Boolean {
        // v-fix：同 requestInput —— 统一走 ApxFrame.packInputText，消除手写副本。
        // 返回真实结果（断线/队列拒收如实 false）—— RemoteInputActivity 据此入队重推。
        return sendControl(ApxFrame.packInputText(flags, text))
    }

    fun sendInputDone() { sendControl(ApxFrame.packInputDone()) }

    /**
     * 发 0x40 通知推送（2026-11-11，能力位 bit0）：本机通知推给**受控端**（PC 显示托盘
     * 气泡；TV 没有通知中心，但协议上是同一帧，TV 端不接即安静忽略）。调用方是
     * `ApxNotificationListener`（经 NotificationSyncBus 的 controller 通道进来）。
     *
     * 能力门控：对端必须报过 [ApxFrame.CAP_NOTIFY_SYNC]（0x33 能力协商）——
     * 旧版对端安静返回 false，帧族一律不发（前向兼容）。
     */
    fun sendNotificationPush(key: String, title: String, text: String, app: String): Boolean {
        if (!ready) return false
        if (peerCaps and ApxFrame.CAP_NOTIFY_SYNC == 0L) return false
        return sendControl(ApxFrame.packNotifyPush(key, title, text, app) ?: return false)
    }

    /**
     * 发 0x42 通知消除（与 [sendNotificationPush] 配对）：本机通知被划掉/点开时回发。
     * 能力门控同上。
     */
    fun sendNotificationDismiss(key: String): Boolean {
        if (!ready) return false
        if (peerCaps and ApxFrame.CAP_NOTIFY_SYNC == 0L) return false
        return sendControl(ApxFrame.packNotifyDismiss(key) ?: return false)
    }

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
     * 发 0x35 R2 加密握手（2026-11-17）：本端是 Initiator。触发条件（缺一静默返回）：
     * 对端 0x33 报过 bit8、本端有静态密钥对、对端公钥来自信标、配对令牌非空 ——
     * 与 PC 端 `Ctrl9511Client::initiateNoise` 同口径。锁序：会话操作收口 noiseLock、
     * 发送/WAIT 置位收口 writeLock（不持 noiseLock 拿 writeLock），与 sendControl 无环。
     */
    private fun initiateNoise() {
        if (!noiseCapable()) return
        val peerPub = runCatching { hexToBytes(peerPubKeyHex) }.getOrNull()
        if (peerPub == null || peerPub.size != NoiseSession.KEY_LEN) return
        val kp = ownKp ?: return
        val tok = token.toByteArray(Charsets.UTF_8)
        if (tok.isEmpty()) return
        val out = ByteArray(NoiseSession.MAX_PLAIN + 16)
        var msgLen = 0
        synchronized(noiseLock) {
            if (noiseActive || noiseWait || noiseSess != null) return   // 已在握手中/加密态
            val s = NoiseSession()
            if (!s.beginInitiator(tok, peerPub, kp.first)) { Log.w("TvCtrl", "R2 握手：Initiator 初始化失败"); return }
            msgLen = s.writeHandshakeMsg(out)
            if (msgLen <= 0) { Log.w("TvCtrl", "R2 握手：msg1 生成失败"); return }
            noiseSess = s
        }
        // msg1 明文帧入队 + 打开 WAIT（均在 writeLock 内，与 sendControl 的三态判定互斥）：
        // 先置 WAIT 再入队，窗口内的业务帧（若有）会以明文先于 msg1 到达对端，照常处理
        synchronized(writeLock) {
            if (noiseActive) return
            noiseWait = true
            seq = (seq + 1) and 0x7FFFFFFF
            txQueue.offer(ApxFrame.build(ApxFrame.STREAM_CONTROL, out.copyOf(msgLen), seq))
        }
        Log.i("TvCtrl", "R2 加密握手已发起（msg1 已发，等对端 msg2）")
    }

    /**
     * 收 0x35 msg2（2026-11-17）：split 出传输态密钥 → WAIT 期间攒下的帧按密文补发 →
     * 置 active。msg2 校验/split 失败（对端令牌不符等）弃会话，攒下的帧按明文补发、
     * 链路维持明文 —— 与 PC 端 readHandshakeMsg 失败分支同口径。
     */
    private fun onNoiseHandshakeMsg2(body: ByteArray) {
        var failed = false
        synchronized(noiseLock) {
            val s = noiseSess
            if (s == null || noiseActive) return   // 没发起过 / 已在加密态：忽略
            failed = s.readHandshakeMsg(body, 0, body.size) != 0
            if (failed) noiseSess = null else if (!s.split()) { failed = true; noiseSess = null }
        }
        synchronized(writeLock) {
            if (failed) {
                Log.w("TvCtrl", "R2 握手：msg2 校验/split 失败（对端令牌不符？），链路维持明文")
                noiseWait = false
                while (pendingBuf.isNotEmpty()) txQueue.offer(pendingBuf.removeFirst())   // 明文补发
                return
            }
            // 补发 WAIT 期间攒下的帧（会话已 split，按密文加密）；noiseWait 先关再发，
            // 补发顺序仍按入队先后（FIFO），对端解密 nonce 顺序一致
            noiseWait = false
            while (pendingBuf.isNotEmpty()) {
                val f = pendingBuf.removeFirst()
                val enc = encryptFrame(f)
                if (enc != null) txQueue.offer(enc) else noiseDrops++
            }
            noiseActive = true
        }
        Log.i("TvCtrl", "R2 通道加密已启用（Noise IK+PSK）")
    }

    /**
     * R2 加密一帧（调用方须持 writeLock）：payload 整段（body + CRC 尾）原位 AEAD，
     * 帧 16 字节头保持明文、payloadLen 重写为密文长。会话访问在 noiseLock 内收口
     * （锁序 writeLock → noiseLock，与全类一致）。失败返回 null（会话缺失/明文超限）。
     */
    private fun encryptFrame(plain: ByteArray): ByteArray? {
        val plainLen = plain.size - ApxFrame.HEADER_SIZE
        if (plainLen <= 0 || plainLen > NoiseSession.MAX_PLAIN) return null
        val cipher = plain.copyOfRange(ApxFrame.HEADER_SIZE, plain.size)
        val cipherLen = synchronized(noiseLock) { noiseSess?.encrypt(cipher, plainLen) ?: 0 }
        if (cipherLen <= plainLen) return null
        val enc = ByteArray(ApxFrame.HEADER_SIZE + cipherLen)
        System.arraycopy(plain, 0, enc, 0, ApxFrame.HEADER_SIZE)
        System.arraycopy(cipher, 0, enc, ApxFrame.HEADER_SIZE, cipherLen)
        putU32Le(enc, 8, cipherLen)
        return enc
    }

    /** 向 buf[off) 写 u32 小端（ApxFrame.putU32 为 private，帧头 payloadLen 重写在这边做） */
    private fun putU32Le(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte(); buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte(); buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /** 64 位 hex → 32 字节（信标第 5 段的对端公钥）；非法字符返回 null */
    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * 发 0x33 能力协商（2026-11-11）：把本端能力位图报给对端，连接建立后由 [openSocket]
     * 立即调用一次。命令号按字面量写（0x33），与 ApxFrame.CMD_CAPS_SYNC 及
     * `scripts/check_protocol.py` 的跨端字面量对拍一致 —— 改这里必须三处同步。
     */
    private fun sendCapsSync() {
        // bit1 通知回复：本端通知使用权已开启才报（app 模块直接读 NotificationSyncPref，
        // 2026-11-14 REQ-通知回复.md）。回复的 action 缓存在监听器里，使用权关着必然是空的；
        // App.context 未就绪时按未开启处理（不报 bit1，PC 门控不给回放入口）。
        val appCtx = com.allperiph.App.context
        val replyCap = if (appCtx != null && com.allperiph.notify.NotificationSyncPref.isEnabled(appCtx)) {
            ApxFrame.CAP_NOTIFY_REPLY
        } else 0L
        sendCapsBase(replyCap)
    }

    /** 组 0x33 并发出（[sendCapsSync] 的公共尾部；参数即本端要报的通知回复能力位） */
    private fun sendCapsBase(replyCap: Long) {
        val own = com.allperiph.shared.proto.ApxFrame.CAP_NOTIFY_SYNC or
            replyCap or
            com.allperiph.shared.proto.ApxFrame.CAP_FIND_PHONE or
            com.allperiph.shared.proto.ApxFrame.CAP_PHONE_FILES or
            com.allperiph.shared.proto.ApxFrame.CAP_ALBUM or
            // bit5 绝对指针（0x06）：本端被控时收它落整屏 onTouch，支持即报
            com.allperiph.shared.proto.ApxFrame.CAP_ABSOLUTE_POINTER or
            // bit8 R2 通道加密（2026-11-17）：密钥对 + 对端公钥 + 令牌三样齐才报 ——
            // 对端（ControlServer）据此等待本端发 0x35 msg1
            (if (noiseCapable()) com.allperiph.shared.proto.ApxFrame.CAP_ENCRYPT else 0L)
        val body = ByteArray(9)
        body[0] = 0x33.toByte()
        for (i in 0 until 8) body[1 + i] = ((own ushr (8 * i)) and 0xFF).toByte()
        sendControl(body)
    }

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
         * 读空闲超时（v-fix link-hardening）：3s 无任何入向字节计一轮，
         * 连续 [READ_TIMEOUT_MAX_MISSES] 轮判链路失效 —— 与服务端 ControlServer
         * 的 READ_TIMEOUT_MS=3s × 8 轮同一把尺。正常链路 ping/pong 每 1s 一轮，
         * 不会误伤；半开链路由此从「内核 keepalive 分钟级」提到 ≈24s。
         */
        private const val READ_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MAX_MISSES = 8

        /** R2 握手窗口攒帧上限（2026-11-17）：握手通常毫秒级完成，超限丢最老帧防内存积压 */
        private const val PENDING_CAP = 1024

        /**
         * 控制端最近一次的光标浮层偏好（null = 用户没动过）。
         * 在 [openSocket] 成功后自动重发 —— 网络抖动自动重连后，被控端的浮层
         * 不会又冒出来；用户没动过开关就是 null，什么都不发（老行为）。
         */
        @Volatile
        var lastOverlayCursorEnabled: Boolean? = null
    }
}
