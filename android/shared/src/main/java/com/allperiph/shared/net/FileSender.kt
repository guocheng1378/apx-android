package com.allperiph.shared.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.allperiph.shared.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 文件发送通道（TCP 端口 [PORT]=9512）：连入对端设备（手机 / PC / TV）的
 * [FileReceiver] 同协议实现，先发 head(u32 名称长度 + 名称 + u64 文件大小)，
 * 随后流式写文件数据。落盘到对端应用私有下载目录，无需对端存储权限。
 *
 * 统一自手机端 `com.allperiph.wireless.TvFileSender`（Uri 入口）与 TV 端
 * `com.allperiph.tv.net.TvFileSender`（File / InputStream 入口）—— 三种入口
 * 都落到同一个核心发送实现，逐字节同协议。
 */
object FileSender {
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
        val name = queryName(cr, uri) ?: "file.bin"
        val ins = cr.openInputStream(uri) ?: return false
        return ins.use { send(host, name, size, it, onProgress) }
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
