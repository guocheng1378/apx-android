package com.allperiph.tv.net

import android.content.Context
import com.allperiph.tv.core.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * 文件接收通道（TCP 端口 [PORT]）：手机端 [com.allperiph.wireless.TvFileSender] 连入，
 * 先发 head(u32 名称长度 + 名称 + u64 文件大小)，随后流式写入文件。
 * 落盘到应用私有外部文件目录 <外部文件>/APX/<name>（无需任何存储权限）。
 */
object TvFileReceiver {
    const val PORT = 9512

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var ctx: Context? = null

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
            thread = thread(name = "apxtv-file") { loop(ss) }
            Log.i("文件接收监听 *:$PORT")
        } catch (t: Throwable) {
            Log.e("文件接收监听失败：${t.message}")
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
            thread(name = "apxtv-file-client") { handle(sock) }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.getInputStream().use { raw ->
                val ins = BufferedInputStream(raw)
                val nameLen = readU32(ins)
                if (nameLen <= 0 || nameLen > 4096) return
                val nameBuf = ByteArray(nameLen)
                if (!readFully(ins, nameBuf)) return
                val name = String(nameBuf, Charsets.UTF_8)
                    .replace('/', '_').replace('\\', '_').replace("..", "")
                val size = readU64(ins)
                val base = ctx?.getExternalFilesDir(null) ?: return
                val dir = File(base, "APX").apply { mkdirs() }
                val out = File(dir, name)
                Log.i("接收文件 $name (${size}B) → $out")
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
                Log.i("文件已存：$out")
            }
        } catch (t: Throwable) {
            Log.e("文件接收异常：${t.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun readU32(ins: java.io.InputStream): Int {
        val b = ByteArray(4)
        if (!readFully(ins, b)) return -1
        var v = 0
        for (i in 0 until 4) v = v or ((b[i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun readU64(ins: java.io.InputStream): Long {
        val b = ByteArray(8)
        if (!readFully(ins, b)) return -1L
        var v = 0L
        for (i in 0 until 8) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun readFully(ins: java.io.InputStream, dst: ByteArray): Boolean {
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
