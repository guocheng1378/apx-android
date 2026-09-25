package com.allperiph.tv.net

import com.allperiph.tv.core.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 文件发送通道（TCP 端口 [PORT]）：连入对端（手机 / PC / 电视）的 9512 接收端。
 *
 * 与手机端 `com.allperiph.wireless.TvFileSender`、手机/TV 端 [TvFileReceiver] **逐字节同协议**：
 *   head = u32 LE 名称长度 + 名称(UTF-8) + u64 LE 文件大小，随后是文件原始字节（无握手、无令牌、无回执）。
 *
 * TV 端此前只有接收端（手机能把文件推过来），没有发送端 —— 「电视上的文件想给手机」只能绕路。
 * 这里补上发送侧，配合 [com.allperiph.tv.ui.TvFileActivity] 即可双向传。
 */
object TvFileSender {
    const val PORT = 9512

    /** 发本机文件（最常见路径） */
    fun send(host: String, file: File, onProgress: ((Int) -> Unit)? = null): Boolean {
        if (!file.isFile) return false
        return FileInputStream(file).use { ins ->
            send(host, file.name, file.length(), ins, onProgress)
        }
    }

    /**
     * 发任意输入流（给「从系统选择器挑的文件」用：拿到的只有 Uri，落临时文件没必要）。
     * @param size 已知大小时传真实字节数（用于进度）；未知传 -1（不做百分比，仍会完整发送）
     */
    fun send(
        host: String,
        name: String,
        size: Long,
        ins: InputStream,
        onProgress: ((Int) -> Unit)? = null,
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
            Log.i("文件已发出：$name（${sent}B）→ $host")
            return true
        } catch (t: Throwable) {
            Log.e("文件发送失败：${t.message}")
            return false
        } finally {
            runCatching { sock?.close() }
        }
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
