package com.allperiph.wireless

import android.content.Context
import android.net.Uri
import com.allperiph.core.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 文件发送通道（TCP 端口 [PORT]）：连入对端设备（手机 / PC / TV）的 [com.allperiph.tv.net.TvFileReceiver] 同协议实现，
 * 先发 head(u32 名称长度 + 名称 + u64 文件大小)，随后流式写文件数据。
 * 落盘到 TV 应用私有下载目录，无需被控端存储权限。
 */
object TvFileSender {
    const val PORT = 9512

    /**
     * @param host TV 端 IP
     * @param uri  本机待发文件（由文件选择器返回）
     * @param onProgress 0..100 进度回调（主线程外，调用方自行切线程）
     * @return 是否成功发完
     */
    fun send(context: Context, host: String, uri: Uri, onProgress: ((Int) -> Unit)? = null): Boolean {
        var sock: Socket? = null
        try {
            val cr = context.contentResolver
            val size = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            val name = queryName(cr, uri) ?: "file.bin"
            val ins: InputStream = cr.openInputStream(uri) ?: return false

            sock = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, PORT), 8000)
            }
            val out = sock.getOutputStream()

            // head：u32 名称长度 + 名称 + u64 文件大小
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.write(intToBytes(nameBytes.size))
            out.write(nameBytes)
            out.write(longToBytes(size))

            val buf = ByteArray(32 * 1024)
            var sent: Long = 0
            var n: Int
            while (ins.read(buf).also { n = it } > 0) {
                out.write(buf, 0, n)
                sent += n
                if (size > 0) onProgress?.invoke(((sent * 100 / size).toInt()).coerceIn(0, 100))
            }
            out.flush()
            ins.close()
            onProgress?.invoke(100)
            Log.i("TvFileSender", "文件已发：$name ($sent B) → $host")
            return true
        } catch (t: Throwable) {
            Log.e("TvFileSender", "发送失败：${t.message}")
            return false
        } finally {
            runCatching { sock?.close() }
        }
    }

    private fun queryName(cr: android.content.ContentResolver, uri: Uri): String? {
        var n: String? = null
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) n = c.getString(0)
                }
        }
        return n
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
