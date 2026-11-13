package com.allperiph.localsend

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP/1.1 server，仅覆盖 LocalSend v2 所需端点（每连接一线程，Connection: close）。
 * 关键实现约束：header/JSON body 都从原始 InputStream 逐字节读，**不引入 BufferedReader**——
 * 字符缓冲会预读吞掉 upload body 开头字节，导致流位置错位、文件损坏。
 */
class LocalSendHttpServer(
    private val port: Int,
    private val handler: Handler,
) {
    interface Handler {
        /** GET info 与 register 的响应体（设备信息） */
        fun deviceInfo(): JSONObject

        /**
         * POST register：把请求方登记进本机发现表（2026-11-13 补）。
         *
         * 此前只回 deviceInfo、不入表 —— 单播 register 这条「多播被路由器挡掉时的唯一
         * 发现通路」只对 PC 端生效（PC 自己会入表），手机↔手机之间收 register 的一端
         * 永远不认识对方，UDP 一被滤附近设备就全空。body 为 RegisterDto（info + port +
         * protocol），peerIp 取自连接的远端地址，比 body 更可信。
         */
        fun onRegister(body: JSONObject, peerIp: String)

        /** POST prepare-upload：接受清单，返回 {sessionId, files:{id:token}} */
        fun prepareUpload(body: JSONObject): JSONObject

        /** POST|PUT upload：打开目标文件输出流；未知 session/file 返回 null → 404 */
        fun openUpload(sessionId: String, fileId: String): OutputStream?

        /** 传输结束（ok=写完并关闭）或出错时回调 */
        fun finishUpload(sessionId: String, fileId: String, ok: Boolean)

        /** POST cancel */
        fun cancel(sessionId: String?)
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(port)
        serverSocket = ss
        Thread({
            while (running.get()) {
                val socket = try { ss.accept() } catch (_: Throwable) { break }
                Thread { handle(socket) }.apply { isDaemon = true; start() }
            }
        }, "LocalSend-HTTP-Accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 120_000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()

            val requestLine = readLineRaw(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val query = if ('?' in parts[1]) parts[1].substringAfter('?') else ""

            var contentLength = 0L
            while (true) {
                val line = readLineRaw(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(idx + 1).trim().toLongOrNull() ?: 0L
                }
            }

            when {
                method == "GET" && path == LocalSendProtocol.PATH_INFO ->
                    respondJson(out, 200, handler.deviceInfo())

                method == "POST" && path == LocalSendProtocol.PATH_REGISTER -> {
                    val body = readBodyBytes(input, contentLength)
                    val json = try { JSONObject(body) } catch (_: Throwable) { JSONObject() }
                    // 对端 IP 取连接远端地址：NAT/代理在本场景不存在，直连即可信
                    handler.onRegister(json, socket.inetAddress?.hostAddress ?: "")
                    respondJson(out, 200, handler.deviceInfo())
                }

                method == "POST" && path == LocalSendProtocol.PATH_PREPARE -> {
                    val body = JSONObject(readBodyBytes(input, contentLength))
                    respondJson(out, 200, handler.prepareUpload(body))
                }

                (method == "POST" || method == "PUT") && path == LocalSendProtocol.PATH_UPLOAD -> {
                    val sessionId = queryParam(query, "sessionId") ?: ""
                    val fileId = queryParam(query, "fileId") ?: ""
                    val target = handler.openUpload(sessionId, fileId)
                    if (target == null) {
                        handler.finishUpload(sessionId, fileId, false)
                        respondText(out, 404, "unknown session/file")
                    } else {
                        var ok = true
                        try {
                            copyExactly(input, target, contentLength)
                            target.close()
                        } catch (_: Throwable) {
                            ok = false
                            try { target.close() } catch (_: Throwable) {}
                        }
                        handler.finishUpload(sessionId, fileId, ok)
                        if (ok) respondText(out, 204, "") else respondText(out, 500, "write failed")
                    }
                }

                method == "POST" && path == LocalSendProtocol.PATH_CANCEL -> {
                    skipBody(input, contentLength)
                    handler.cancel(queryParam(query, "sessionId"))
                    respondText(out, 204, "")
                }

                else -> {
                    skipBody(input, contentLength)
                    respondText(out, 404, "not found")
                }
            }
        } catch (_: Throwable) {
            // 连接级异常：对端超时/中断属常态
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    /** 从原始流读一行（LF 结尾，容忍 CR）；上限 8KB 防滥用 */
    private fun readLineRaw(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.ISO_8859_1)
            if (b == '\n'.code) break
            if (buf.size() > 8192) throw IllegalStateException("header too long")
            buf.write(b)
        }
        var bytes = buf.toByteArray()
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes = bytes.copyOf(bytes.size - 1)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun readBodyBytes(input: InputStream, length: Long): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length.toInt().coerceAtMost(4 shl 20))
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun skipBody(input: InputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    /** 按 Content-Length 精确搬字节到文件流 */
    private fun copyExactly(input: InputStream, target: OutputStream, length: Long) {
        if (length <= 0) throw IllegalStateException("upload without content-length")
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("truncated body")
            target.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&').firstOrNull { "${key}=" in it }?.substringAfter("${key}=")

    private fun respondJson(out: OutputStream, code: Int, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "No Content"
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
    }

    private fun respondText(out: OutputStream, code: Int, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; 500 -> "Server Error"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
    }

    private class ByteArrayOutputStream(cap: Int) : java.io.ByteArrayOutputStream(cap)
}
