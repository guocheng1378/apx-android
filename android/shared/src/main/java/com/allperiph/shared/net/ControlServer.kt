package com.allperiph.shared.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.util.Log
import com.allperiph.shared.input.RootInput
import com.allperiph.shared.input.UinputKeyboard
import com.allperiph.shared.input.UinputMouse
import com.allperiph.shared.inject.TvInjector
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
import kotlin.math.abs

/**
 * 控制面**服务端**：本机开 ServerSocket 监听，对端（手机 / PC / TV）作为客户端连入并发控制帧。
 *
 * 统一自手机被控端 `TvControlServer` 与 TV 端 `TcpControlServer` —— 两端逐字节同协议
 * （同款握手 + APX1 帧）。端口 [PORT]=9511，与对端控制客户端一致。
 *
 * 端点相关的输入注入（手机/TV 各自的 `TvInjector` / `TvInputDispatcher` / 无障碍服务）
 * 实现不同，通过 [ControlEndpoint] 接口由构造函数注入，避免 shared 模块反向依赖两端实现。
 */
class ControlServer(
    private val port: Int = PORT,
    private val token: String = "",
    private val endpoint: ControlEndpoint,
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var peerText: String = ""
    @Volatile var ready: Boolean = false; private set

    @Volatile var onClipboardChange: ((String) -> Unit)? = null

    /**
     * 0x10 模块开关（v184 新增协议）：PC 面板拨"无线"开关时通知本机挂起/恢复控制面。
     * 语义是**挂起**而非停服务 —— 监听保持，断开当前连接、挂起期只收不执行输入
     * （ping/0x10 除外）；这样 PC 端重新拨 ON 后 3s 重连即可恢复，
     * 不会出现"关了就再也打不开"的单程门。
     */
    @Volatile var onModuleToggle: ((String, Boolean) -> Unit)? = null

    /** 对端连入/断开的服务级回调（记忆对端 IP、刷新通知等）；与 [ControlEndpoint.onPeerStateChanged] 区别在于它是上层侧效应 */
    @Volatile var onPeerChanged: ((Boolean, String) -> Unit)? = null

    /** root 通道保活节流：借 ping 重拉 su shell，两次尝试至少间隔 30s（真无 root 时也不白转） */
    private var lastRootKeepaliveMs: Long = 0L

    /**
     * 借心跳保活 root 注入通道（2026-11-14 被控机实锤：su shell 被 HyperOS 杀掉后
     * 无人再拉，「授权显示允许、通道整段没起」）。ping 每秒一跳、tryStart 幂等
     * （在跑/在启动直接返回），这里按 30s 节流 —— 真无 root 的机器也不至于每秒白起进程。
     * 异步拉起：本秒通道还没回来，下一秒连接内就绪；打字路径另有用时重拉（TvInjector）双保险。
     */
    private fun keepRootChannelWarm() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRootKeepaliveMs < 30_000L) return
        lastRootKeepaliveMs = now
        RootInput.tryStart()
    }

    /**
     * 对端能力位图（0x33 能力协商，2026-11-11）：连接建立后对端报上来的支持能力，
     * 见 [com.allperiph.shared.proto.ApxFrame] 的 CAP_* 常量。0 = 未收到（旧版对端没有该机制，
     * 此时上层只能按"不支持新能力"处理，即能力帧族一律不发）。
     * 新会话建立（[doActivate]）时清零重收；只有 ready 且 peerCaps 含对应位才允许发对应帧。
     */
    @Volatile var peerCaps: Long = 0L
        private set

    /** 0x05 开副屏请求回调（TV 用；手机端不设即忽略） */
    @Volatile var onOpenScreenRequest: (() -> Unit)? = null

    /**
     * 0x38 查找手机回调（2026-11-11，能力位 bit2）：PC 托盘点「查找手机」→ 控制面命令
     * → 上层实现响铃。回调参数 action：1=响铃 0=停，已在 ControlServer 主线程（mainHandler
     * post 过）。本模块取不到应用上下文，实际响铃在 app 模块
     * `com.allperiph.findphone.FindPhoneRinger`（由 ControlledService 注入）；
     * TV 端不设该回调即忽略 —— 查找手机只对手机有意义。
     */
    @Volatile var onFindPhone: ((Int) -> Unit)? = null

    /**
     * 0x44/0x46 手机文件回调（2026-11-11，能力位 bit3）：电脑浏览本机目录（0x44）并取走文件
     * （0x46）。回调参数：(cmd, body, reply, peerHost) —— body 为整段帧载荷（含 cmd 字节）；
     * reply = 本链路的 sendControl（应答 0x45/0x47 原路返回）；peerHost = 当前对端 IP
     * （0x46 经 9512 推送文件时的目标）。实现落 app 模块
     * `com.allperiph.files.PhoneFiles`（由 ControlledService 注入）；TV 端不设即忽略
     * （浏览手机文件只在 PC 控手机 / 手机控手机两条链路上有界面入口）。
     */
    @Volatile var onPhoneFileCmd: ((cmd: Int, body: ByteArray, reply: (ByteArray) -> Boolean, peerHost: String?) -> Unit)? = null

    /**
     * 0x48/0x4A/0x4B 相册投影回调（2026-11-12，能力位 bit4）：电脑浏览本机相册
     * （0x48 分页）、开关新照片监听（0x4A）、取缩略图（0x4B，本体经 9512）。
     * 参数口径与 [onPhoneFileCmd] 一致（cmd/body/reply/peerHost）。实现落 app 模块
     * `com.allperiph.files.PhoneAlbum`（由 ControlledService 注入）；TV 端不设即忽略
     * （相册只在「电脑控手机 / 手机控手机」两条链路上有界面入口）。
     */
    @Volatile var onAlbumCmd: ((cmd: Int, body: ByteArray, reply: (ByteArray) -> Boolean, peerHost: String?) -> Unit)? = null

    @Volatile var onRemoteInputRequest: ((String, String) -> Unit)? = null
    @Volatile var onRemoteInputText: ((String, Int) -> Unit)? = null
    @Volatile var onRemoteInputDone: (() -> Unit)? = null

    /** 握手后鉴权回调：返回 false 拒绝该 IP（TV 用；手机端不设即只靠令牌鉴权） */
    @Volatile var onAuthorizePeer: ((String) -> Boolean)? = null

    /**
     * 多控制端并发接入裁决（v223.33，手机端接）：已有设备在线时**另一台**设备握手通过
     * 要接管，不再静默顶掉旧连接 —— 旧实现直接踢，于是两台设备都自动重连就互相接管
     * 来回踢，注入乱跳 = 用户报的「两台设备打架」。改为回调上层弹窗，**用户点确定才切换**；
     * 15s 未裁决或弹窗没拉起来，兜底拒绝新连接（保住当前会话）。
     * 不设回调的端（TV）维持旧行为：直接接管。
     * 参数：(新设备 peerText, 当前设备 peerText, 裁决回调 true=切到新设备)
     */
    @Volatile var onConcurrentPeer: ((String, String, (Boolean) -> Unit) -> Unit)? = null

    @Volatile private var suspended = false

    /**
     * PC 请求挂起控制面：断开当前连接（监听保留，可随时恢复）。
     * 挂起期新连接**仍握手保持**（见 [activate] 的 suspended 分支），只在 pump 里忽略
     * 输入帧 —— 0x10 恢复开关必须靠这条活着的连接送达，全拒就是单程门。
     */
    fun suspendAccept() {
        suspended = true
        Log.i("控制面", "控制面已挂起（PC 请求）")
        runCatching { client?.close() }
    }

    /** PC 请求恢复控制面：重新接受连接 */
    fun resumeAccept() {
        if (suspended) Log.i("控制面", "控制面已恢复（PC 请求）")
        suspended = false
    }

    val isListening: Boolean get() = running.get() && server != null
    fun currentPeerHost(): String? = client?.inetAddress?.hostAddress

    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeLock = Any()
    /** 会话接管锁：并发握手在鉴权通过后串行收口，防止 client / out 被交叉写成两条连接 */
    private val activateLock = Any()
    private var seq = 0
    private val outQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)
    private val rxLock = Any()
    private var rxBuf = ByteArray(4096)
    private var rxLen = 0
    private val keysLock = Any()
    private val pressedKeys = HashSet<Int>()
    private var lastButtons = 0

    // ---- P0-3：右键（bit1）按下沿状态 —— 此前 onMouse 只认 bit0，0x02/0x04 被整个丢弃，
    //      右键菜单/双指点按完全不可用。只在 injectHandler 单线程上读写，无需加锁。----
    private var rightDownAt = 0L
    private var rightMovedPx = 0
    private var rightUpPending: Runnable? = null
    // D-1 诊断：静默丢帧计数（magic/payloadLen/CRC 三类，每 50 次记一条）
    private var frameDrops = 0

    // ---- v223.33 多设备接入裁决状态（均在 activateLock 内读写）----
    /** 待用户裁决的新连接（一次只裁一个，后续并发直接拒） */
    private var pendingSock: Socket? = null
    /** 被拒过的设备 IP：再次接入静默拒绝，不重复弹窗（防两台重连风暴刷弹窗）；无人在线时清空 */
    private val rejectedIps = HashSet<String>()

    private val injectThread = android.os.HandlerThread("apx-inject").apply { start() }
    private val injectHandler = Handler(injectThread.looper)

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss; running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "apxctl-accept").apply { isDaemon = true; start() }
            Log.i("控制面", "监听 *:$port（本机 ${localIpv4() ?: "未知"}）")
            true
        } catch (t: Throwable) {
            Log.e("控制面", "监听 $port 失败：${t.message}")
            runCatching { server?.close() }; server = null; false
        }
    }

    fun stop() {
        running.set(false); outQueue.clear()
        runCatching { injectHandler.post { releaseAllInputs() } }
        runCatching { injectThread.quitSafely() }
        runCatching { client?.close() }; runCatching { server?.close() }
        server = null; client = null; out = null; peerText = ""; ready = false
        acceptThread = null; readerThread = null; writerThread = null
        synchronized(activateLock) { pendingSock = null; rejectedIps.clear() }
    }

    fun statusText(): String = if (ready) "已连接 $peerText" else "监听 $port · 等待对端连入"

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try { ss.accept() } catch (t: Throwable) {
                if (!running.get()) break; try { Thread.sleep(200) } catch (_: InterruptedException) { break }; continue
            }
            // v-fix（link-hardening）：**先握手鉴权、后接管**。旧实现在 accept 时见 ready
            // 就顶掉旧连接、再异步握手 —— 于是任何一次 TCP 探连（哪怕令牌不符被拒）
            // 都能踢掉正在进行的会话，是纯 DoS，令牌配了也白配。现在未通过鉴权的连接
            // 对现有会话零影响，接管统一收口在 activate()（握手三关全过后才执行）。
            Thread({ handshake(sock) }, "apxctl-handshake").apply { isDaemon = true; start() }
        }
    }

    private fun handshake(sock: Socket) {
        try {
            sock.tcpNoDelay = true; sock.soTimeout = HANDSHAKE_TIMEOUT_MS
            val ins = sock.getInputStream()
            val len = readU32Le(ins)
            if (len < 0 || len > MAX_TOKEN) { Log.w("控制面", "握手长度非法：$len"); runCatching { sock.close() }; return }
            if (len > 0) {
                val buf = ByteArray(len)
                if (!readFully(ins, buf)) { runCatching { sock.close() }; return }
                if (token.isNotEmpty() && String(buf, Charsets.UTF_8) != token) { Log.w("控制面", "令牌不匹配，拒绝 ${peerTextOf(sock)}"); runCatching { sock.close() }; return }
            } else if (token.isNotEmpty()) {
                // v-fix（link-hardening）：服务端配了令牌，对端却发空令牌（len=0）——
                // 旧实现整段 if 被跳过、直接放行，等于「配了令牌也拦不住不带令牌的连接」
                // （攻击者只需发 4 字节 0）。空令牌必须显式拒绝，否则鉴权形同虚设。
                Log.w("控制面", "对端未携带令牌，拒绝 ${peerTextOf(sock)}"); runCatching { sock.close() }; return
            }
            sock.soTimeout = 0; activate(sock)
        } catch (t: Throwable) { Log.w("控制面", "握手失败：${t.message}"); runCatching { sock.close() } }
    }

    /**
     * 握手与鉴权**全部通过**后才到这里接管会话。
     *
     * v-fix（link-hardening）：接管（顶掉旧连接）从 acceptLoop 挪进来 —— 旧实现先踢
     * 旧连接再握手，失败的探连也能打断正在工作的会话。现在握手 / 令牌 / onAuthorizePeer
     * 三关全过才动旧连接；[activateLock] 让并发握手串行收口，防止 client 与 out 被
     * 交叉写成「读写两条连接」。
     *
     * v223.33（多设备打架）：已有会话且接入方 IP 不同 → **不再静默接管**，挂起待裁决
     * 回调 [onConcurrentPeer]（上层弹窗，用户点确定才切换）；同 IP 重连（原设备断线
     * 重连的僵连接）与未接裁决回调的端（TV）仍走直接接管。
     */
    private fun activate(sock: Socket) {
        synchronized(activateLock) {
            if (suspended) Log.i("控制面", "控制面处于挂起态：保持连接，不执行输入 ${peerTextOf(sock)}")
            val ip = sock.inetAddress?.hostAddress ?: ""
            if (onAuthorizePeer?.invoke(ip) == false) { Log.w("控制面", "拒绝未授权设备 $ip"); runCatching { sock.close() }; return }
            val prev = client
            val prevOpen = prev != null && prev !== sock && !prev.isClosed
            if (prevOpen && ip != prev?.inetAddress?.hostAddress) {
                // —— 另一台设备接入：弹窗点确定才行，不打架 ——
                if (pendingSock != null) {
                    Log.w("控制面", "已有连接等待用户裁决，新连接直接拒绝 ${peerTextOf(sock)}")
                    runCatching { sock.close() }; return
                }
                if (rejectedIps.contains(ip)) {
                    Log.i("控制面", "已被拒绝过的设备再次接入，静默拒绝（不再弹窗）$ip")
                    runCatching { sock.close() }; return
                }
                val cb = onConcurrentPeer
                if (cb != null) {
                    pendingSock = sock
                    val cur = peerText
                    Log.i("控制面", "两台设备并发接入：${peerTextOf(sock)} 请求接管当前 $cur —— 等用户裁决")
                    mainHandler.post {
                        cb(peerTextOf(sock), cur) { ok -> decideTakeover(sock, ok) }
                        // 兜底：弹窗没拉起来 / 用户没操作 —— 15s 后默认拒绝，保住当前会话
                        mainHandler.postDelayed({ decideTakeover(sock, false) }, TAKEOVER_DECIDE_MS)
                    }
                    return
                }
                // 上层没接裁决回调（TV 端）：维持旧行为，直接接管
                Log.w("控制面", "新连接接管，顶掉旧连接 $peerText")
            } else if (prevOpen) {
                Log.w("控制面", "新连接接管，顶掉旧连接 $peerText")
            }
            if (prevOpen) runCatching { prev!!.close() }
            doActivate(sock)
        }
    }

    /** 用户在弹窗上做出选择（或超时兜底）。pendingSock 一次性消费，重复调用无副作用。 */
    private fun decideTakeover(sock: Socket, ok: Boolean) {
        synchronized(activateLock) {
            if (pendingSock !== sock) return   // 已被裁决过（用户点击与 15s 兜底的竞争）
            pendingSock = null
            if (!ok) {
                sock.inetAddress?.hostAddress?.let { rejectedIps.add(it) }
                runCatching { sock.close() }
                Log.i("控制面", "新设备未获确认，已拒绝 ${peerTextOf(sock)}")
                return
            }
            if (!running.get() || sock.isClosed) { runCatching { sock.close() }; return }
            val prev = client
            if (prev != null && prev !== sock && !prev.isClosed) {
                // 用户已选新设备：旧设备记入拒绝名单，防止它自动重连再弹一轮（打架循环）
                prev.inetAddress?.hostAddress?.let { rejectedIps.add(it) }
                Log.w("控制面", "用户确认切换：顶掉旧连接 $peerText")
                runCatching { prev.close() }
            }
            doActivate(sock)
        }
    }

    /** 实际接管（调用方必须已持有 [activateLock]）：换连接、重置状态、起读写线程 */
    private fun doActivate(sock: Socket) {
        client = sock; out = sock.getOutputStream(); peerText = peerTextOf(sock)
        synchronized(rxLock) { rxLen = 0 }; outQueue.clear()
        synchronized(keysLock) { pressedKeys.clear(); lastButtons = 0 }; ready = true
        // 能力协商（0x33，2026-11-11）：新会话立即清空上一会话的对端能力，并把自己的能力
        // 报给对端 —— 接收侧存下 [peerCaps]；不发的话对端永远学到 0，能力帧族全被门掉。
        peerCaps = 0L
        sendCapsSync()
        Log.i("控制面", "对端已连入：$peerText")
        mainHandler.post { endpoint.onPeerStateChanged(true, peerText); onPeerChanged?.invoke(true, peerText) }
        runCatching { sock.soTimeout = READ_TIMEOUT_MS }
        readerThread = Thread({ readerLoop(sock) }, "apxctl-reader").apply { isDaemon = true; start() }
        writerThread = Thread({ writerLoop(sock) }, "apxctl-writer").apply { isDaemon = true; start() }
    }

    private fun readerLoop(sock: Socket) {
        val ins = sock.getInputStream(); val buf = ByteArray(4096)
        var idleTimeouts = 0
        while (running.get() && !sock.isClosed && client === sock) {
            val n = try {
                ins.read(buf)
            } catch (t: java.net.SocketTimeoutException) {
                if (++idleTimeouts >= 8) {
                    Log.w("控制面", "读空闲超时 8 次，判定链路已失效：$peerText")
                    break
                }
                continue
            } catch (t: Throwable) {
                Log.w("控制面", "读异常，断开连接：${t.message}")
                break
            }
            idleTimeouts = 0
            if (n <= 0) break; feed(buf, n)
            val actions = pump()
            if (actions.isNotEmpty()) { injectHandler.post { for (a in actions) a() } }
        }
        Log.i("控制面", "对端连接已断开：$peerText"); teardown(sock)
    }

    private fun writerLoop(sock: Socket) {
        val o = out ?: return
        while (running.get() && !sock.isClosed) {
            val frame = try { outQueue.poll(WRITER_IDLE_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            try { o.write(frame); o.flush() } catch (t: Throwable) { Log.w("控制面", "写出失败：${t.message}"); break }
        }
        runCatching { sock.close() }; teardown(sock)
    }

    private fun teardown(sock: Socket) {
        if (client === sock) {
            ready = false; client = null; out = null; peerText = ""; outQueue.clear()
            // 无人在线：重置接入裁决记忆 —— 下次两台再连时重新给用户选一次
            synchronized(activateLock) { rejectedIps.clear() }
            mainHandler.post { endpoint.onPeerStateChanged(false, ""); onPeerChanged?.invoke(false, "") }
            runCatching { injectHandler.post { releaseAllInputs() } }
        }
        runCatching { sock.close() }
    }

    private fun feed(data: ByteArray, n: Int) {
        synchronized(rxLock) {
            val need = rxLen + n
            if (need > MAX_RX_BUF) {
                throw IllegalStateException("接收缓冲溢出（${need}B > ${MAX_RX_BUF}B）")
            }
            if (need > rxBuf.size) {
                var c = rxBuf.size * 2
                while (c < need) c *= 2
                if (c > MAX_RX_BUF) c = MAX_RX_BUF
                rxBuf = rxBuf.copyOf(c)
            }
            System.arraycopy(data, 0, rxBuf, rxLen, n); rxLen += n
        }
    }

    private fun pump(): List<() -> Unit> {
        val actions = ArrayList<() -> Unit>()
        synchronized(rxLock) {
            var off = 0
            while (rxLen - off >= ApxFrame.HEADER_SIZE) {
                if (!ApxFrame.isMagic(rxBuf, off, rxLen)) {
                    if (frameDrops++ % 50 == 0) Log.w("控制面", "诊断:丢帧#${frameDrops} magic不符 off=$off rxLen=$rxLen")
                    off++; continue
                }
                val payloadLen = ApxFrame.payloadLenAt(rxBuf, off)
                if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) {
                    if (frameDrops++ % 50 == 0) Log.w("控制面", "诊断:丢帧#${frameDrops} payloadLen=$payloadLen")
                    off++; continue
                }
                val total = ApxFrame.totalSize(payloadLen)
                if (rxLen - off < total) break
                if (!ApxFrame.verify(rxBuf, off, payloadLen)) {
                    if (frameDrops++ % 50 == 0) Log.w("控制面", "诊断:丢帧#${frameDrops} CRC不符 off=$off len=$payloadLen")
                    off += total; continue
                }
                if (ApxFrame.streamIdAt(rxBuf, off) == ApxFrame.STREAM_CONTROL && payloadLen >= 1) {
                    val body = ApxFrame.bodyAt(rxBuf, off, payloadLen)
                    if (body != null && body.isNotEmpty()) {
                        val cmd = body[0].toInt() and 0xFF
                        // v184：挂起期间只回 ping、只处理模块开关(0x10)，输入类命令一律丢弃。
                        // 连接保持（见 activate），因此 PC 随时能把挂起拨回来。
                        // 0x33 能力协商是元数据不是输入：挂起期照收（连接保活时对端可能刚升级）。
                        if (suspended && cmd != 'p'.code && cmd != 0x10 && cmd != 0x33) {
                            // 控制面已挂起：忽略鼠标/键盘/触摸等输入
                        } else when (cmd) {
                            'p'.code -> {
                                Log.i("控制面", "诊断:收到ping→回pong")
                                actions.add {
                                    if (!sendControl(PONG)) Log.w("控制面", "诊断:pong发送失败 ready=$ready")
                                }
                                keepRootChannelWarm()
                            }
                            0x01 -> if (body.size >= 5) actions.add { onMouse(body) }
                            0x02 -> if (body.size >= 3) actions.add { onConsumer(body) }
                            0x03 -> if (body.size >= 3) actions.add { onKeyboard(body) }
                            0x04 -> if (body.size >= 9) actions.add { onTouch(body) }
                            0x07 -> if (body.size >= 7) actions.add { onGamepad(body) }
                            CLIPBOARD -> if (body.size >= 4) actions.add { onClipboard(body) }
                            0x22 -> if (body.size >= 2) actions.add { onPowerAction(body) }
                            0x21 -> Log.i("控制面", "收到反向剪贴板帧（忽略）")
                            0x05 -> actions.add { onOpenScreen() }
                            0x10 -> if (body.size >= 3) actions.add { onModuleToggle(body) }
                            0x25 -> if (body.size >= 2) actions.add { onRequestInput(body) }
                            0x26 -> if (body.size >= 4) actions.add { onInputText(body) }
                            0x27 -> actions.add { onInputDone() }
                            // 0x32 光标浮层开关（v223.10）：控制端关/开被控端的浮层光标。
                            // 直接落 TvInjector（同模块，无循环）—— 被控端不需要知道这个开关，
                            // 浮层画不画由注入器统一门控（三个 move 落点都走 paintOverlay）。
                            0x32 -> if (body.size >= 2) actions.add { TvInjector.setOverlayEnabled(body[1].toInt() and 0xFF != 0) }
                            // 0x33 能力协商（2026-11-11）：对端连接后报能力位图（u64 小端 8 字节）。
                            // 只是元数据落 volatile，直接内联处理不入注入队列。
                            0x33 -> if (body.size >= 9) onCapsSync(body)
                            // 0x38 查找手机（2026-11-11，能力位 bit2）：PC 托盘点「查找手机」→
                            // 本机响铃（STREAM_ALARM 最大音量 + 亮屏，30s 自动停；action=0 停）。
                            // 实现落 app 模块 FindPhoneRinger（经 onFindPhone 回调注入；
                            // TV 不设回调即忽略）。
                            0x38 -> if (body.size >= 2) actions.add { onFindPhoneFrame(body) }
                            // 0x44/0x46 手机文件（2026-11-11，能力位 bit3）：电脑浏览本机目录并取走文件。
                            // 实现落 app 模块 PhoneFiles（经 onPhoneFileCmd 回调注入，与 0x38 的
                            // FindPhoneRinger 同一套注入口径）；TV 端不注入 → 静默忽略（类底有日志）。
                            // reply 闭包 = 当前链路的 sendControl（应答 0x45/0x47 走原路返回）。
                            0x44 -> if (body.size >= 3) actions.add { onPhoneFileCmd?.invoke(0x44, body, ::sendControl, currentPeerHost()) }
                            0x46 -> if (body.size >= 3) actions.add { onPhoneFileCmd?.invoke(0x46, body, ::sendControl, currentPeerHost()) }
                            // 0x48/0x4A/0x4B 相册投影（2026-11-12，能力位 bit4）：电脑浏览本机相册。
                            // 实现落 app 模块 PhoneAlbum（经 onAlbumCmd 回调注入，与 onPhoneFileCmd
                            // 同一套注入口径）；TV 端不注入 → 静默忽略（类底有日志）。
                            0x48 -> if (body.size >= 5) actions.add { onAlbumCmd?.invoke(0x48, body, ::sendControl, currentPeerHost()) }
                            0x4A -> if (body.size >= 2) actions.add { onAlbumCmd?.invoke(0x4A, body, ::sendControl, currentPeerHost()) }
                            0x4B -> if (body.size >= 5) actions.add { onAlbumCmd?.invoke(0x4B, body, ::sendControl, currentPeerHost()) }
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
        val buttons = body[1].toInt() and 0xFF
        val dx = body[2].toInt().toByte().toInt()
        val dy = body[3].toInt().toByte().toInt()
        val wheel = if (body.size >= 5) body[4].toInt().toByte().toInt() else 0
        endpoint.cursorMove(dx.toFloat(), dy.toFloat(), absolute = false)
        val prev = synchronized(keysLock) { lastButtons }
        val needDown = (buttons and 1) != 0 && (prev and 1) == 0
        val needUp = (buttons and 1) == 0 && (prev and 1) == 1
        // P0-3：右键 bit1 此前完全没被识别（onMouse 只认 bit0），0x02/0x04 整个丢弃 ——
        // 双指点按/右键菜单全部失效。现在 bit1 按下沿→光标处按下，抬起沿按时长/位移
        // 判「长按（右键）或拖拽」；bit0 左键沿用 pressDown/pressUp。
        val needRightDown = (buttons and 2) != 0 && (prev and 2) == 0
        val needRightUp = (buttons and 2) == 0 && (prev and 2) != 0
        // 中键 bit2：三指点按/中键拖拽（GestureEngine 发 0x04 帧对）此前整个被忽略 ——
        // 「各个按钮是否有用」审查（2026-11-13）实锤的断链之一。现在接 uinput 中键：
        // 与左右键同一条内核鼠标通道；uinput 未就绪时维持提示（无障碍/触摸无中键语义，
        // 不做 root input 模拟——事件流对不齐会双击）。
        val needMiddleDown = (buttons and 4) != 0 && (prev and 4) == 0
        val needMiddleUp = (buttons and 4) == 0 && (prev and 4) != 0
        synchronized(keysLock) { lastButtons = buttons }
        if (needDown) endpoint.pressDown()
        if (needUp) endpoint.pressUp()
        if (needMiddleDown) {
            if (UinputMouse.ready) UinputMouse.button(2, true)
            else Log.w("控制面", "收到中键按下（bit2）：虚拟鼠标未就绪，中键暂无法注入")
        }
        if (needMiddleUp) UinputMouse.button(2, false)
        if (needRightDown) {
            // 快速连点右键：先收掉上一次还没落地的推迟抬起，避免触摸流卡在 DOWN
            rightUpPending?.let { p ->
                injectHandler.removeCallbacks(p); rightUpPending = null; endpoint.pressUp()
            }
            rightDownAt = SystemClock.uptimeMillis()
            rightMovedPx = 0
            // 光标处按下：抬起沿再判 长按（右键菜单）/ 拖拽；有 INJECT_EVENTS 时是一条真实触摸流
            endpoint.pressDown()
        }
        if (buttons and 2 != 0) rightMovedPx += abs(dx) + abs(dy)
        if (needRightUp) {
            val held = SystemClock.uptimeMillis() - rightDownAt
            if (held >= RIGHT_HOLD_MS || rightMovedPx >= TAP_DRIFT_PX) {
                // 按住够久或拖出了位移：立即抬起 → finishStroke 判长按 / 拖拽
                rightUpPending?.let { injectHandler.removeCallbacks(it); rightUpPending = null }
                endpoint.pressUp()
            } else {
                // 快速右键点按：把抬起推迟到按下后 550ms（≥ finishStroke 的 500ms 长按阈值，
                // 也是真实触摸流里对端能识别为长按的按住时长），对端在光标处弹右键菜单
                val r = Runnable { rightUpPending = null; endpoint.pressUp() }
                rightUpPending = r
                injectHandler.postDelayed(r, (rightDownAt + RIGHT_HOLD_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
            }
        }
        if (wheel != 0) endpoint.scroll(wheel)
    }

    private fun onTouch(body: ByteArray) {
        val action = body[1].toInt() and 0xFF
        val x = (body[3].toInt() and 0xFF) or ((body[4].toInt() and 0xFF) shl 8)
        val y = (body[5].toInt() and 0xFF) or ((body[6].toInt() and 0xFF) shl 8)
        val fx = x / 65535f; val fy = y / 65535f
        endpoint.cursorMove(fx, fy, absolute = true)
        when (action) { 0 -> endpoint.touchDown(fx, fy); 1 -> { endpoint.cursorClick(); endpoint.touchUp(fx, fy) } }
    }

    private fun onConsumer(body: ByteArray) {
        val bitmap = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        // ★ 下面这圈 CONSUMER_MAP 是**重复注入**，已删除：
        //   endpoint.consumer() 已经把 bit0~bit6 全处理了（音量走 AudioManager、
        //   bit3 电源、bit4/5/6 走 sendMediaKey，见 TvInjector.consumer），这里再按位
        //   补一次按键，等于每个动作都做两遍 —— 点一次「播放/暂停」来回切两次、
        //   看起来毫无反应，按「电源」连发两次，有 root 时音量一次跳两档。
        //   （两个被控端 TV / 手机的 endpoint.consumer 都是 TvInjector.consumer，删掉不丢功能。）
        endpoint.consumer(bitmap)
    }

    private fun onModuleToggle(body: ByteArray) {
        val idLen = body[1].toInt() and 0xFF
        if (body.size < 2 + idLen + 1) return
        val id = String(body, 2, idLen, Charsets.UTF_8)
        val on = body[2 + idLen].toInt() != 0
        Log.i("控制面", "收到模块开关：$id -> $on")
        mainHandler.post { onModuleToggle?.invoke(id, on) }
    }

    private fun onPowerAction(body: ByteArray) {
        val action = body[1].toInt() and 0xFF
        Log.i("控制面", "电源动作请求：action=$action"); endpoint.powerAction(action)
    }

    /**
     * 收 0x38 查找手机（2026-11-11，能力位 bit2）：载荷 `[0x38][action]`，action 1=响铃
     * 0=停。响铃实现由上层注入（[onFindPhone] → app 模块 FindPhoneRinger），未注入
     * （TV 端）时只留日志 —— 查找手机只对手机有意义。
     */
    private fun onFindPhoneFrame(body: ByteArray) {
        val action = body[1].toInt() and 0xFF
        Log.i("控制面", "查找手机：action=$action")
        mainHandler.post { onFindPhone?.invoke(action) }
    }

    private fun onOpenScreen() { Log.i("控制面", "收到开副屏请求"); onOpenScreenRequest?.invoke() }

    /**
     * 收 0x33 能力协商（2026-11-11）：解析对端能力位图（u64 小端，8 字节，见
     * [com.allperiph.shared.proto.ApxFrame] 的 CAP_* 常量）存进 [peerCaps]。
     * 调用点在 pump() 内（rxLock 持有），只做 volatile 写与日志，无锁需求。
     */
    private fun onCapsSync(body: ByteArray) {
        var caps = 0L
        for (i in 0 until 8) caps = caps or ((body[1 + i].toLong() and 0xFF) shl (8 * i))
        peerCaps = caps
        Log.i("控制面", "对端能力：0x${java.lang.Long.toHexString(caps)}")
    }

    /**
     * 发 0x33 能力协商（2026-11-11）：把本端能力位图报给对端。连接建立后由 [doActivate]
     * 立即调用一次。命令号按字面量写（0x33），与 ApxFrame.CMD_CAPS_SYNC 及
     * `scripts/check_protocol.py` 的跨端字面量对拍一致 —— 改这里必须三处同步。
     */
    private fun sendCapsSync() {
        val own = com.allperiph.shared.proto.ApxFrame.CAP_NOTIFY_SYNC or
            com.allperiph.shared.proto.ApxFrame.CAP_NOTIFY_REPLY or
            com.allperiph.shared.proto.ApxFrame.CAP_FIND_PHONE or
            com.allperiph.shared.proto.ApxFrame.CAP_PHONE_FILES or
            com.allperiph.shared.proto.ApxFrame.CAP_ALBUM
        val body = ByteArray(9)
        body[0] = 0x33.toByte()
        for (i in 0 until 8) body[1 + i] = ((own ushr (8 * i)) and 0xFF).toByte()
        if (!sendControl(body)) Log.w("控制面", "能力协商帧发送失败（连接未就绪）")
    }

    private val MOD_KEYCODE = intArrayOf(113, 59, 57, 117, 114, 60, 58, 118)

    /** 本帧此刻是否有修饰键按住（onKeyboard 按 mod 位图更新；组合键路由的判据） */
    @Volatile
    private var anyModifierHeld = false

    /** 已改走 uinput 真键盘的字符键（usage→字符）：抬起时按原路抬起，不走错通道 */
    private val routedChars = HashMap<Int, Char>()

    private fun onKeyboard(body: ByteArray) {
        val mod = body[1].toInt() and 0xFF; val now = HashSet<Int>()
        val end = if (body.size < 9) body.size else 9
        for (i in 3 until end) { val usage = body[i].toInt() and 0xFF; if (usage != 0) now.add(usage) }
        if (mod and 8 != 0 && now == HashSet(listOf(0x0F))) {
            synchronized(keysLock) { pressedKeys.clear() }
            if (RootInput.available) RootInput.run("input keyevent 26") else endpoint.lockScreen()
            return
        }
        for (bit in 0 until 8) { if (mod and (1 shl bit) != 0) now.add(0xE0 + bit) }
        // 修饰键状态先于按键 diff 更新：hidDown 里字符是否改走 uinput 真键盘（组合键）
        // 要看「本帧此刻有没有修饰键按住」，提前一帧/滞后一帧都会让组合键散架
        anyModifierHeld = now.any { it in 0xE0..0xE7 }
        val prev = synchronized(keysLock) { HashSet(pressedKeys) }
        for (u in prev) { if (u !in now) hidUp(u) }
        for (u in now) { if (u !in prev) hidDown(u) }
        synchronized(keysLock) { pressedKeys.clear(); pressedKeys.addAll(now) }
    }

    private fun releaseAllInputs() {
        val held = synchronized(keysLock) { HashSet(pressedKeys) }
        val hadLeftBtn = synchronized(keysLock) { (lastButtons and 0x01) != 0 }
        // P0-3：右键序列（含还没落地的推迟抬起）在断开/挂起时也必须收尾，否则触摸流卡在 DOWN
        val hadRightBtn = synchronized(keysLock) { (lastButtons and 0x02) != 0 }
        val hadMiddleBtn = synchronized(keysLock) { (lastButtons and 0x04) != 0 }
        synchronized(keysLock) { pressedKeys.clear(); lastButtons = 0 }
        val pendingRight = rightUpPending
        if (pendingRight != null) { injectHandler.removeCallbacks(pendingRight); rightUpPending = null }
        for (u in held) runCatching { hidUp(u) }
        if (hadLeftBtn || hadRightBtn || pendingRight != null) runCatching { endpoint.pressUp() }
        if (hadMiddleBtn) UinputMouse.button(2, false)   // 中键按住断连：抬起，不残留卡键
        UinputKeyboard.releaseAll()   // 真键盘按住的修饰键/字符兜底抬起（断连时对端未必按序发抬起帧）
    }

    private fun hidDown(usage: Int) {
        if (usage in 0xE0..0xE7) { val kc = MOD_KEYCODE[usage - 0xE0]; endpoint.key(kc, true); return }
        val (kc, ch) = HID_MAP[usage] ?: (0 to '\u0000')
        // 组合键路由（2026-11-13）：修饰键按住时字符改走 uinput 真键盘 —— 内核把
        // Ctrl/Shift 状态随事件流一起送进应用，Ctrl+C / Shift+A 才有真实语义；输入法/
        // 无障碍文本通道只能发裸字符，组合键等于白按。无修饰键时不改道：输入法文本
        // 通道中英文最稳（uinput 真键盘经系统按键分发，中文组合输入反而过不去）。
        if (anyModifierHeld && ch != '\u0000' && UinputKeyboard.ready) {
            if (UinputKeyboard.sendChar(ch, down = true)) {
                synchronized(keysLock) { routedChars[usage] = ch }
                return
            }
        }
        if (kc != 0) endpoint.key(kc, true)
        if (ch != '\u0000') endpoint.text(ch)
    }

    private fun hidUp(usage: Int) {
        if (usage in 0xE0..0xE7) { val kc = MOD_KEYCODE[usage - 0xE0]; endpoint.key(kc, false); return }
        // 按下时改走真键盘的字符，抬起必须原路返回（此刻修饰键可能已松开，不能再判一次）
        val routed = synchronized(keysLock) { routedChars.remove(usage) }
        if (routed != null) { if (UinputKeyboard.ready) UinputKeyboard.sendChar(routed, down = false); return }
        val (kc, _) = HID_MAP[usage] ?: (0 to '\u0000')
        if (kc != 0) endpoint.key(kc, false)
    }

    private fun onClipboard(body: ByteArray) {
        val len = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        if (len <= 0 || len > body.size - 3) return
        endpoint.clipboard(String(body.copyOfRange(3, 3 + len), Charsets.UTF_8))
    }

    private fun onGamepad(body: ByteArray) {
        val buttons = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
        val x = body[3].toInt().toByte().toInt()
        val y = body[4].toInt().toByte().toInt()
        val rx = body[5].toInt().toByte().toInt()
        val ry = body[6].toInt().toByte().toInt()
        endpoint.gamepad(buttons, x, y, rx, ry)
    }

    private fun onRequestInput(body: ByteArray) {
        if (body.size < 2) return
        val hintLen = body[1].toInt() and 0xFF
        if (body.size < 2 + hintLen) return
        val hint = String(body.copyOfRange(2, 2 + hintLen), Charsets.UTF_8)
        Log.i("控制面", "远程输入请求: hint=$hint")
        mainHandler.post { onRemoteInputRequest?.invoke(peerText, hint) }
    }

    private fun onInputText(body: ByteArray) {
        if (body.size < 4) return
        val flags = body[1].toInt() and 0xFF
        val textLen = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
        if (body.size < 4 + textLen) return
        val text = String(body.copyOfRange(4, 4 + textLen), Charsets.UTF_8)
        Log.i("控制面", "远程输入文本: flags=0x${Integer.toHexString(flags)}, text=$text")
        mainHandler.post { onRemoteInputText?.invoke(text, flags) }
    }

    private fun onInputDone() {
        Log.i("控制面", "远程输入完成")
        mainHandler.post { onRemoteInputDone?.invoke() }
    }

    fun sendControl(body: ByteArray): Boolean {
        if (!ready) return false
        val frame = synchronized(writeLock) { seq = (seq + 1) and 0x7FFFFFFF; ApxFrame.build(ApxFrame.STREAM_CONTROL, body, seq) }
        return outQueue.offer(frame)
    }

    fun sendReverseClipboard(text: String): Boolean {
        if (text.isEmpty() || !ready) return false
        val bytes = text.toByteArray(Charsets.UTF_8); if (bytes.size > 0xFFFF) return false
        val body = ByteArray(3 + bytes.size); body[0] = 0x21.toByte()
        body[1] = (bytes.size and 0xFF).toByte(); body[2] = ((bytes.size ushr 8) and 0xFF).toByte(); bytes.copyInto(body, 3)
        return sendControl(body)
    }

    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL): Boolean {
        // v-fix：组帧去重 —— 手写 u16 长度段与 ApxFrame.packInputText 完全同构，
        // 收发两处各自维护一份就是隐患；统一走 packer（对齐线上布局与全部解析方）。
        // 返回真实发送结果：远程输入页据此失败入队重推 —— 此前丢弃返回值恒报成功，
        // 写失败静默，对端就永久少这段字（「和电脑上照不齐」根因之一）。
        return sendControl(ApxFrame.packInputText(flags, text))
    }

    fun sendInputDone() { sendControl(ApxFrame.packInputDone()) }

    /**
     * 发 REQUEST_INPUT（0x25）：请求对端弹输入法帮本机输入，即「让对端帮我输入」。
     *
     * ⚠️ 这个方法此前**根本不存在**：0x25 只有收（`onRequestInput`）没有发，
     * `ApxAccessibilityService` 检测到输入框获焦后 `onFocusDetected` 又没有任何注册点，
     * 于是「本机输入框获焦 → 对端自动弹输入法」从 A 端就断了（PC 端是在
     * `Ctrl9511Client::sendRequestInput` 自己组帧，所以只有 PC→手机这一个方向能用）。
     *
     * 组帧走 [ApxFrame.packRequestInput]（已对齐线上布局）；sourceId 未上线，传 0。
     */
    fun sendRequestInput(hint: String): Boolean {
        if (!ready) return false
        return sendControl(ApxFrame.packRequestInput(hint, 0))
    }

    /**
     * 发 0x40 通知推送（2026-11-11，能力位 bit0）：把本机一条通知推给对端 —— PC 端
     * dispatch 收到后转托盘气泡（`TrayIcon::notify`）。调用方是通知使用权监听
     * （app 模块 `ApxNotificationListener`，经 NotificationSyncBus 的 controlled 通道进来）。
     *
     * 能力门控：对端必须报过 [ApxFrame.CAP_NOTIFY_SYNC]（0x33 能力协商）——
     * 旧版对端没有该机制时安静返回 false，帧族一律不发（发了也进黑洞，还会刷日志）。
     */
    fun sendNotificationPush(key: String, title: String, text: String, app: String): Boolean {
        if (!ready) return false
        if (peerCaps and ApxFrame.CAP_NOTIFY_SYNC == 0L) return false
        return sendControl(ApxFrame.packNotifyPush(key, title, text, app) ?: return false)
    }

    /**
     * 发 0x42 通知消除（与 [sendNotificationPush] 配对）：用户在本机划掉/点开通知时回发，
     * PC 端气泡虽无法撤回，但状态机知道该通知已不在了（为后续「PC 端点通知回跳」铺路）。
     * 能力门控同上。
     */
    fun sendNotificationDismiss(key: String): Boolean {
        if (!ready) return false
        if (peerCaps and ApxFrame.CAP_NOTIFY_SYNC == 0L) return false
        return sendControl(ApxFrame.packNotifyDismiss(key) ?: return false)
    }

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
        private const val MAX_RX_BUF = 8 * 1024 * 1024
        /** v223.33：多设备接入弹窗的裁决超时 —— 15s 未点确定默认拒绝新连接，保住当前会话 */
        private const val TAKEOVER_DECIDE_MS = 15_000L
        /** P0-3：右键按住阈值 —— 抬起沿据此判「长按（右键菜单）」，需 ≥ TvInjector.finishStroke 的 500ms */
        private const val RIGHT_HOLD_MS = 550L
        /** P0-3：右键按住期间位移超过该像素数 → 判拖拽，立即抬起走 swipe */
        private const val TAP_DRIFT_PX = 12
        /** 剪贴板同步命令号（手机复制→PC粘贴），协议帧 body[0] */
        private const val CLIPBOARD = 0x20
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
            // 符号键（2026-11-14 实锤修复）：键盘页符号排在被控手机上此前整排无声 ——
            // 这批 usage 不在表里，hidDown 查出 (0, '\u0000') 直接忽略。补齐 US 布局
            // 下档符号（与 HidKeys.usageOf 的键面口径一致，修一处查同模式同步 TvControlServer）。
            put(0x2D, 0 to '-'); put(0x2E, 0 to '='); put(0x2F, 0 to '['); put(0x30, 0 to ']')
            put(0x31, 0 to '\\'); put(0x33, 0 to ';'); put(0x34, 0 to '\''); put(0x35, 0 to '`')
            put(0x36, 0 to ','); put(0x37, 0 to '.'); put(0x38, 0 to '/')
        }
    }
}

/**
 * 控制面端点：由各端（手机被控 / TV）实现，封装该端特有的输入注入路径
 */
interface ControlEndpoint {
    fun onPeerStateChanged(connected: Boolean, peerText: String)
    fun cursorMove(x: Float, y: Float, absolute: Boolean)
    fun cursorClick()
    fun key(keyCode: Int, down: Boolean)
    fun text(ch: Char)
    fun pressDown()
    fun pressUp()
    fun scroll(wheel: Int)
    fun touchDown(x: Float, y: Float)
    fun touchUp(x: Float, y: Float)
    fun consumer(bitmap: Int)
    fun powerAction(action: Int)
    fun clipboard(text: String)
    fun gamepad(buttons: Int, x: Int, y: Int, rx: Int, ry: Int)
    fun lockScreen()
}
