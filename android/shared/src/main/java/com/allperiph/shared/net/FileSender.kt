package com.allperiph.shared.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.allperiph.shared.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 文件发送通道（TCP 端口 [PORT]=9512）：连入对端设备（手机 / PC / TV）的
 * [FileReceiver] 同协议实现，先发 head(u32 名称长度 + 名称 + u64 文件大小)，
 * 随后流式写文件数据。落盘到对端应用私有下载目录，无需对端存储权限。
 *
 * R2 通道加密（2026-11-17，REQ §4.3，9512 子批）：本端为 Initiator。加密连接的
 * 送上来的第一个 u32 是 0（模式标记，占 legacy 名称长度位），随后 [u32 len1][msg1]；
 * 等 [u32 len2][msg2] split 后，head 与文件数据都按段 [u32 ctLen][AEAD 密文] 发出
 * （首段明文 = head(nameLen+name+size)，后续段明文 ≤ [FileReceiver.SEG_PLAIN]/段）。
 *
 * 门控（不探测对端）：`context` 非空 && [NoiseSession.linkToken] 非空（9511 链路的
 * 被控端令牌）&& 本端密钥对可用 && 对端静态公钥已有 TOFU 记录（9511 握手成功时落盘，
 * 见 NoiseSession.savePeerPub）。任一不满足 → 纯明文（旧版对端 / TV 兼容路径）。
 * R2 握手失败（对端明文 / token 不符）→ 关闭后**回退一次明文重连**（fail-safe，
 * 与 PC 端 file_sender 同口径）；此时尚未读过数据流，ins 可直接复用。
 *
 * 统一自手机端 `com.allperiph.wireless.TvFileSender`（Uri 入口）与 TV 端
 * `com.allperiph.tv.net.TvFileSender`（File / InputStream 入口）—— 三种入口
 * 都落到同一个核心发送实现，逐字节同协议。
 */
object FileSender {
    const val PORT = 9512

    /** Noise 握手消息长度上限（IK msg1/msg2 实际 ≤ 96B，放宽到 1KB 防畸形帧） */
    private const val HS_MAX = 1024

    /** 发本机文件（最常见路径） */
    fun send(host: String, file: File, onProgress: ((Int) -> Unit)? = null, context: Context? = null): Boolean {
        if (!file.isFile) return false
        return FileInputStream(file).use { ins ->
            send(host, file.name, file.length(), ins, onProgress, context)
        }
    }

    /**
     * 发任意输入流（给「从系统选择器挑的文件」用：拿到的只有 Uri，落临时文件没必要）。
     * @param size 已知大小时传真实字节数（用于进度）；未知传 -1（不做百分比，仍会完整发送）
     * @param context 应用上下文（R2 加密门控用：读对端 TOFU 公钥）；null = 一律明文。
     *                默认 null 不破坏既有调用点；app 模块调用点逐步传 this。
     */
    fun send(
        host: String,
        name: String,
        size: Long,
        ins: InputStream,
        onProgress: ((Int) -> Unit)? = null,
        context: Context? = null,
    ): Boolean {
        // R2 门控：token + 本端密钥对 + 对端公钥（TOFU）齐备才尝试加密，否则直接明文
        val r2 = prepareR2(context, name)
        if (r2 != null && sendR2(host, name, size, ins, onProgress, r2)) return true
        if (r2 != null) {
            // 握手阶段失败（对端是旧版/明文）：ins 尚未被读过，回退一次明文重连
            Log.w("FileSender", "9512 R2 握手失败，回退明文重连")
        }
        return sendLegacy(host, name, size, ins, onProgress)
    }

    /** R2 门控资料：token / 本端私钥 / 对端公钥（TOFU）。任一缺失返回 null（走明文）。 */
    private fun prepareR2(context: Context?, name: String): Triple<ByteArray, ByteArray, ByteArray>? {
        if (context == null) return null
        val tok = NoiseSession.linkToken ?: return null
        if (tok.isBlank()) return null
        val kp = NoiseSession.ensureKeyPair(context) ?: return null
        val peerPub = NoiseSession.loadPeerPub(context) ?: return null
        Log.i("FileSender", "9512 R2 加密发送：$name（对端公钥 ${NoiseSession.hex(peerPub).take(16)}…）")
        return Triple(tok.toByteArray(Charsets.UTF_8), kp.first, peerPub)
    }

    /**
     * R2 加密发送：握手（Initiator）成功后按段发 head + 文件数据。
     * 握手失败返回 false（调用方回退明文）；握手成功后段解密由对端负责，
     * 本端只保证按序写出（AEAD nonce 顺序 = 发送顺序）。
     */
    private fun sendR2(
        host: String,
        name: String,
        size: Long,
        ins: InputStream,
        onProgress: ((Int) -> Unit)?,
        r2: Triple<ByteArray, ByteArray, ByteArray>,
    ): Boolean {
        var sock: Socket? = null
        try {
            sock = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, PORT), 8000)
            }
            val raw = sock.getOutputStream()
            // 模式标记 0 + msg1（[u32 len][msg] 封装，与 PC 端 file_sender 对拍）
            val sess = NoiseSession(NoiseSession.PROLOGUE_9512)
            val msg1 = ByteArray(NoiseSession.MAX_PLAIN + 16)
            if (!sess.beginInitiator(r2.first, r2.third, r2.second)) return false
            val n1 = sess.writeHandshakeMsg(msg1)
            if (n1 <= 0) return false
            writeU32(raw, 0)
            writeU32(raw, n1)
            raw.write(msg1, 0, n1)
            raw.flush()
            // 等 msg2（对端 Responder 生成后原路返回）；读不出 = 对端不懂 R2，握手失败
            val m2in = DataInputStreamCompat(sock.getInputStream())
            val n2 = m2in.readInt()
            if (n2 <= 0 || n2 > HS_MAX) return false
            val msg2 = ByteArray(n2)
            if (!readFully(m2in, msg2)) return false
            if (sess.readHandshakeMsg(msg2, 0, msg2.size) != 0) return false
            if (!sess.split()) return false
            Log.i("FileSender", "9512 R2 握手完成，开始加密发送")

            // 传输态：head 为首段明文，文件数据按 SEG_PLAIN 切段
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val head = ByteArray(4 + nameBytes.size + 8)
            putU32(head, 0, nameBytes.size)
            System.arraycopy(nameBytes, 0, head, 4, nameBytes.size)
            putU64(head, 4 + nameBytes.size, size.coerceAtLeast(0))
            val out = DataOutputStreamCompat(raw, sess)
            out.writeSegment(head)

            val buf = ByteArray(FileReceiver.SEG_PLAIN)
            var sent = 0L
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.writeSegment(buf, n)
                sent += n
                if (size > 0) onProgress?.invoke(((sent * 100 / size).toInt()).coerceIn(0, 100))
            }
            out.flush()
            onProgress?.invoke(100)
            Log.i("FileSender", "文件已发（加密）：$name（${sent}B）→ $host")
            return true
        } catch (t: Throwable) {
            Log.e("FileSender", "R2 发送失败：${t.message}")
            return false
        } finally {
            runCatching { sock?.close() }
        }
    }

    /** legacy 明文发送（与 R2 前的原始实现逐字节一致，兼容路径） */
    private fun sendLegacy(
        host: String,
        name: String,
        size: Long,
        ins: InputStream,
        onProgress: ((Int) -> Unit)?,
    ): Boolean {
        var sock: Socket? = null
        try {
            sock = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, PORT), 8000)
            }
            val out = sock.getOutputStream()
            // head：u32 名称长度 + 名称 + u64 大小
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.write(intToBytes(nameBytes.size))
            out.write(nameBytes)
            out.write(longToBytes(size.coerceAtLeast(0)))

            val buf = ByteArray(32 * 1024)
            var sent = 0L
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                sent += n
                if (size > 0) onProgress?.invoke(((sent * 100 / size).toInt()).coerceIn(0, 100))
            }
            out.flush()
            onProgress?.invoke(100)
            Log.i("FileSender", "文件已发：$name（${sent}B）→ $host")
            return true
        } catch (t: Throwable) {
            Log.e("FileSender", "发送失败：${t.message}")
            return false
        } finally {
            runCatching { sock?.close() }
        }
    }

    /**
     * 发系统选择器返回的 Uri（手机端常用）。
     * @param host 对端 IP
     * @param uri  本机待发文件（由文件选择器返回）
     * @param onProgress 0..100 进度回调（主线程外，调用方自行切线程）
     * @return 是否成功发完
     */
    fun send(context: Context, host: String, uri: Uri, onProgress: ((Int) -> Unit)? = null): Boolean {
        val cr = context.contentResolver
        val size = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        // 九追（issue#168 2026-11-11）：DISPLAY_NAME 查不到不再一律落 file.bin —— 部分
        // 文件管理器（HyperOS 实测，README.txt 发出后对端叫 file.bin）不回这个列。
        // 退而求其次从 uri 里还原：file:// 的路径段、content:// 末段带「.」的当文件名
        // （documentsui 的 primary:Download/README.txt 取冒号后一段）；都没有才落 file.bin。
        val name = queryName(cr, uri)
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.takeIf { it.contains('.') }
            ?: "file.bin"
        val ins = cr.openInputStream(uri) ?: return false
        return ins.use { send(host, name, size, it, onProgress, context) }
    }

    private fun queryName(cr: android.content.ContentResolver, uri: Uri): String? {
        var n: String? = null
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) n = c.getString(0)
                }
        }
        return n
    }

    /** R2 段写出：[u32 ctLen][AEAD 密文]，段明文 ≤ SEG_PLAIN（与 PC 端同口径） */
    private class DataOutputStreamCompat(private val raw: OutputStream, private val sess: NoiseSession) {
        private val seg = ByteArray(FileReceiver.SEG_PLAIN)

        fun writeSegment(plain: ByteArray, len: Int = plain.size) {
            var off = 0
            while (off < len) {
                val n = minOf(FileReceiver.SEG_PLAIN, len - off)
                System.arraycopy(plain, off, seg, 0, n)
                writeChunk(seg, n)
                off += n
            }
        }

        private fun writeChunk(plain: ByteArray, len: Int) {
            val ct = plain.copyOf(len)
            val ctLen = sess.encrypt(ct, len)
            if (ctLen <= 0) throw java.io.IOException("R2 段加密失败")
            writeU32(raw, ctLen)
            raw.write(ct, 0, ctLen)
        }

        fun flush() {
            raw.flush()
        }
    }

    /** 带 u32 读的 InputStream 薄封装（msg2 读取用） */
    private class DataInputStreamCompat(private val raw: InputStream) : InputStream() {
        fun readInt(): Int {
            val b = ByteArray(4)
            if (!readFully(this, b)) return -1
            var v = 0
            for (i in 0 until 4) v = v or ((b[i].toInt() and 0xFF) shl (8 * i))
            return v
        }

        override fun read(): Int = raw.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raw.read(b, off, len)
    }

    private fun readFully(ins: InputStream, dst: ByteArray): Boolean {
        var got = 0
        while (got < dst.size) {
            val n = ins.read(dst, got, dst.size - got)
            if (n <= 0) return false
            got += n
        }
        return true
    }

    private fun writeU32(out: OutputStream, v: Int) {
        out.write(byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        ))
    }

    private fun putU32(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putU64(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun longToBytes(v: Long): ByteArray = ByteArray(8) { i ->
        ((v ushr (8 * i)) and 0xFF).toByte()
    }
}
