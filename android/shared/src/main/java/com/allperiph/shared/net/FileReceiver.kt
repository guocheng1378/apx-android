package com.allperiph.shared.net

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.allperiph.shared.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * 文件接收通道（TCP 端口 [PORT]=9512）：对端（手机 / PC / TV）连入，
 * 先发 head(u32 名称长度 + 名称 + u64 文件大小)，随后流式写入文件。
 * 落盘到公共下载目录 Download/AllPeriph/<name>（用户可直接在文件管理器中找到）。
 *
 * R2 通道加密（2026-11-17，REQ §4.3，9512 子批）：连接后的首个 u32 是**模式标记** ——
 *  0 → R2 加密连接：随后 [u32 len1][Noise msg1]（本端 Responder），回 [u32 len2][msg2]
 *      后进入传输态；之后的流为段式封装 [u32 ctLen][AEAD 密文]，首段明文 =
 *      head(nameLen+name+size)，后续段 = 文件数据（明文 ≤ 32768B/段，两端同口径）。
 *  >0 → legacy 明文连接：该 u32 即名称长度，后续字节与加密前逐字节一致，兼容旧版对端。
 *
 * 加密门控：本端 [NoiseSession.linkToken] 非空（9511 链路的被控端令牌，9512 复用同一
 * token 派生 PSK）且 [NoiseSession.ensureKeyPair] 可用；任一不满足即整条连接走明文
 * （TV 端 / 未配对对端兼容路径，与 9511 同规则）。
 *
 * 统一自手机被控端 `com.allperiph.controlled.TvFileReceiver` 与 TV 端
 * `com.allperiph.tv.net.TvFileReceiver`（两端逐字节同实现，对端设备通用）。
 */
object FileReceiver {
    const val PORT = 9512

    /** R2 传输段明文上限（发送端按此切块；两端一致，见 FileSender.SEG_PLAIN） */
    const val SEG_PLAIN = 32 * 1024

    /** Noise 握手消息长度上限（IK msg1/msg2 实际 ≤ 96B，放宽到 1KB 防畸形帧打爆内存） */
    private const val HS_MAX = 1024

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var ctx: Context? = null

    /**
     * 文件接收完成回调（**主线程**）。各端在服务启动时设置：
     *  - TV 端 → 弹「打开 / 删除」交互浮窗（见 `TvFilePrompt`）；
     *  - 手机端不设置 → 退回一句 Toast（保持原有行为）。
     * 回调里抛异常不会影响接收（内部已 try-catch）。
     */
    @Volatile
    var onReceived: ((Context, File) -> Unit)? = null

    fun start(c: Context) {
        if (running.get()) return
        ctx = c.applicationContext
        try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            server = ss
            running.set(true)
            thread = thread(name = "apxctl-file") { loop(ss) }
            Log.i("文件接收", "文件接收监听 *:$PORT")
        } catch (t: Throwable) {
            Log.e("文件接收", "文件接收监听失败：${t.message}")
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun loop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (_: Throwable) {
                break
            }
            thread(name = "apxctl-file-client") { handle(sock) }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.getInputStream().use { raw ->
                val buffered = BufferedInputStream(raw)
                // 首 u32 模式标记（9512 子批）：0 = R2 加密，>0 = legacy 名称长度
                val first = readU32(buffered)
                var ins: InputStream = buffered
                var nameLen = first
                if (first == 0) {
                    val sess = handshake(buffered, sock.getOutputStream(), sock.inetAddress?.hostAddress)
                    if (sess == null) {
                        // 握手失败（token 不符 / 畸形帧）：直接断 —— 加密连接不留半开明文，
                        // 发送端自己有明文回退（重连一次 legacy）
                        Log.w("文件接收", "R2 握手失败，断开连接")
                        return
                    }
                    ins = NoiseInStream(buffered, sess)
                    nameLen = readU32(ins)   // 首段明文里才是真正的 head
                }
                if (nameLen <= 0 || nameLen > 4096) return
                val nameBuf = ByteArray(nameLen)
                if (!readFully(ins, nameBuf)) return
                val name = String(nameBuf, Charsets.UTF_8)
                    .replace('/', '_').replace('\\', '_').replace("..", "")
                val size = readU64(ins)
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(base, "AllPeriph").apply { mkdirs() }
                val out = File(dir, name)
                Log.i("文件接收", "接收文件 $name (${size}B) → $out")
                var left = size
                val buf = ByteArray(32 * 1024)
                FileOutputStream(out).use { fos ->
                    while (left > 0) {
                        val toRead = min(buf.size.toLong(), left).toInt()
                        val n = ins.read(buf, 0, toRead)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        left -= n
                    }
                }
                Log.i("文件接收", "文件已存：$out")
                val c = ctx
                if (c != null) {
                    Handler(Looper.getMainLooper()).post {
                        val cb = onReceived
                        if (cb != null) {
                            runCatching { cb(c, out) }
                        } else {
                            runCatching {
                                Toast.makeText(c, "已接收文件：$name", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("文件接收", "文件接收异常：${t.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    /**
     * R2 握手（9512 Responder 侧，2026-11-17）：消费 [u32 len1][msg1]，产出 msg2
     * 按 [u32 len2][msg2] 回写。token 取 [NoiseSession.linkToken]（9511 链路的被控端
     * 令牌，两端同源）。任何一步失败返回 null（调用方断链）。
     */
    private fun handshake(raw: InputStream, out: java.io.OutputStream, peerHost: String?): NoiseSession? {
        val tok = NoiseSession.linkToken ?: return null
        val c = ctx ?: return null
        val kp = NoiseSession.ensureKeyPair(c) ?: return null
        return try {
            val len1 = readU32(raw)
            if (len1 <= 0 || len1 > HS_MAX) return null
            val msg1 = ByteArray(len1)
            if (!readFully(raw, msg1)) return null
            val s = NoiseSession(NoiseSession.PROLOGUE_9512)
            if (!s.beginResponder(tok.toByteArray(Charsets.UTF_8), kp.first)) return null
            if (s.readHandshakeMsg(msg1, 0, msg1.size) != 0) return null
            // §4.4 白名单：msg1 消费后即摘对端公钥，回 msg2 **之前**裁决 —— 拒绝则不回
            //（对端等不到 msg2 自会放弃），落盘统一在裁决放行里做。裁决可能阻塞（弹窗），
            // 本函数跑在每连接独立线程（apxctl-file-client），可安全等待。
            val pub = s.remoteStaticPubPending()
            if (pub != null && NoiseSession.pubAuthorizer?.invoke(pub, peerHost) == false) {
                Log.w("文件接收", "9512 R2：对端公钥未获信任，拒绝 ${NoiseSession.hex(pub)} from $peerHost")
                return null
            }
            val msg2 = ByteArray(NoiseSession.MAX_PLAIN + 16)
            val n2 = s.writeHandshakeMsg(msg2)
            if (n2 <= 0) return null
            // len2 + msg2 回写（与发送端约定：握手消息都走 [u32 len][msg] 封装）
            out.write(u32Bytes(n2))
            out.write(msg2, 0, n2)
            out.flush()
            if (!s.split()) return null
            Log.i("文件接收", "9512 R2 通道加密已启用")
            s
        } catch (t: Throwable) {
            Log.w("文件接收", "9512 R2 握手异常：${t.message}")
            null
        }
    }

    /**
     * R2 传输段解密流：把 [u32 ctLen][ct] 段流视图成普通 InputStream（段间不粘连 ——
     * 每段独立 AEAD，段内明文原样吐出）。head 与文件数据都从这走，解析逻辑与
     * legacy 单份复用（handle 内只换流不换逻辑）。解密失败（标签不过）直接抛
     * IOException 语义的异常终止接收 —— 半个文件比坏文件强。
     */
    private class NoiseInStream(private val raw: InputStream, private val sess: NoiseSession) : InputStream() {
        private var pending: ByteArray = ByteArray(0)
        private var off = 0

        /** 取下一段：读 [u32 ctLen][ct] → 原位解密成明文。流结束抛 EOF。 */
        private fun fill() {
            if (off < pending.size) return
            val len = readU32(raw)
            if (len <= 16 || len > NoiseSession.MAX_PLAIN + 16) throw java.io.IOException("R2 段长非法：$len")
            val ct = ByteArray(len)
            if (!readFully(raw, ct)) throw java.io.IOException("R2 段不完整（流中断）")
            val plainLen = sess.decrypt(ct, len)
            if (plainLen <= 0) throw java.io.IOException("R2 段解密失败（标签不过）")
            pending = ct.copyOf(plainLen)   // decrypt 原位：前 plainLen 字节即明文
            off = 0
        }

        override fun read(): Int {
            fill()
            return pending[off++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, o: Int, len: Int): Int {
            if (len == 0) return 0
            fill()
            val n = min(len, pending.size - off)
            System.arraycopy(pending, off, b, o, n)
            off += n
            return n
        }
    }

    private fun u32Bytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun readU32(ins: InputStream): Int {
        val b = ByteArray(4)
        if (!readFully(ins, b)) return -1
        var v = 0
        for (i in 0 until 4) v = v or ((b[i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun readU64(ins: InputStream): Long {
        val b = ByteArray(8)
        if (!readFully(ins, b)) return -1L
        var v = 0L
        for (i in 0 until 8) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun readFully(ins: InputStream, dst: ByteArray): Boolean {
        var got = 0
        while (got < dst.size) {
            val n = try {
                ins.read(dst, got, dst.size - got)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) return false
            got += n
        }
        return true
    }
}
