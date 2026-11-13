package com.allperiph.localsend

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LocalSend 发送端能力层（MVP2 · issue#45）：
 *  - 设备表：LocalSendService 的 announce 监听回填（依赖互传开关打开），60s 活跃窗口
 *  - 发送三步：POST register → POST prepare-upload（带 sha256）→ POST upload 逐文件流式推
 *  - 端点/字段与官方 dto_v2.rs 对齐；sha256 提前算好（对端接收器可能校验）
 */
object LocalSendClient {

    data class Discovered(
        val alias: String, val deviceType: String, val ip: String, val port: Int,
        val fingerprint: String, var lastSeen: Long,
    )
    /** 待发送项：名称/大小/流工厂（调用方决定 File 或 content Uri 来源） */
    data class SendItem(val name: String, val size: Long, val open: () -> InputStream)
    data class Result(val ok: Boolean, val message: String)

    private val devices = ConcurrentHashMap<String, Discovered>()

    /** Service 收 announce 后回填；download=false（对端不收）不入表 */
    fun onAnnounce(msg: JSONObject, ip: String) {
        val fp = msg.optString("fingerprint").ifEmpty { ip }
        val port = msg.optInt("port", 0)
        if (port !in 1..65535) return
        if (!msg.optBoolean("download", true)) return
        devices[fp] = Discovered(
            alias = msg.optString("alias", ip),
            deviceType = msg.optString("deviceType", "desktop"),
            ip = ip, port = port, fingerprint = fp,
            lastSeen = System.currentTimeMillis(),
        )
    }

    /** 60s 内见过的设备，最近可见优先 */
    fun activeDevices(): List<Discovered> {
        val cutoff = System.currentTimeMillis() - 60_000
        return devices.values.filter { it.lastSeen >= cutoff }.sortedByDescending { it.lastSeen }
    }

    /** 三步发送；成功 ok=true。selfInfo = 本机设备信息（含 fingerprint/version） */
    fun send(target: Discovered, selfInfo: JSONObject, items: List<SendItem>): Result {
        if (items.isEmpty()) return Result(false, "没有要发送的文件")
        val base = "http://${target.ip}:${target.port}"

        // 1) register：确认对端可达
        val reg = httpJson("POST", base + LocalSendProtocol.PATH_REGISTER, selfInfo)
            ?: return Result(false, "连接对端失败")
        val peerAlias = reg.optString("alias", target.alias)

        // 2) prepare-upload：先流式算 sha256（对端可能校验）
        val filesJson = JSONObject()
        val order = mutableListOf<String>()
        val shaByFile = HashMap<String, String>()
        for (item in items) {
            val id = UUID.randomUUID().toString()
            order.add(id)
            shaByFile[id] = try { sha256Of(item.open()) } catch (e: Throwable) { return Result(false, "读取失败：${item.name}") }
            filesJson.put(id, JSONObject()
                .put("fileName", item.name)
                .put("size", item.size)
                .put("sha256", shaByFile[id])
                .put("fileType", guessType(item.name)))
        }
        // prepare-upload 的 info 字段是**平铺设备信息**（dto_v2 PrepareUploadRequestDto）；
        // register 出站已改官方包裹结构（RegisterDto = info + port + protocol）——
        // selfInfo 现在带着 info 壳进来，这里拆壳取平铺，别把壳再包一层。
        val flatSelfInfo = selfInfo.optJSONObject("info") ?: selfInfo
        val prepBody = JSONObject().put("info", flatSelfInfo).put("files", filesJson)
        val prep = httpJson("POST", base + LocalSendProtocol.PATH_PREPARE, prepBody)
            ?: return Result(false, "prepare 失败")
        val sessionId = prep.optString("sessionId")
        if (sessionId.isEmpty()) return Result(false, "对端未返回 sessionId")
        val tokens = prep.optJSONObject("files") ?: return Result(false, "对端未返回文件 token")

        // 3) 逐文件 upload（POST + Content-Length 流式）
        items.forEachIndexed { i, item ->
            val id = order[i]
            val token = tokens.optString(id)
            if (token.isEmpty()) return Result(false, "对端拒绝了文件：${item.name}")
            val url = "$base${LocalSendProtocol.PATH_UPLOAD}?sessionId=${sessionId}&fileId=${token}"
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000; conn.readTimeout = 120_000
                conn.setFixedLengthStreamingMode(item.size)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                item.open().use { input ->
                    conn.outputStream.use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    }
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) return Result(false, "上传失败（HTTP $code）：${item.name}")
            } catch (e: Throwable) {
                return Result(false, "上传中断：${e.message ?: item.name}")
            }
        }
        return Result(true, "已向 $peerAlias 发送 ${items.size} 个文件")
    }

    /** 流式 sha256（调用方提供新流） */
    private fun sha256Of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        input.use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun guessType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> "image"
        "mp4", "mkv", "avi", "mov", "webm", "flv", "3gp" -> "video"
        "mp3", "flac", "wav", "aac", "ogg", "m4a", "opus" -> "audio"
        "txt", "md", "log", "json", "xml", "csv", "html", "pdf" -> "text"
        else -> "application"
    }

    /** 极简 JSON POST；非 2xx 或异常返回 null */
    private fun httpJson(method: String, urlStr: String, body: JSONObject): JSONObject? = try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.doOutput = true
        conn.connectTimeout = 3000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        if (code in 200..299 && !text.isNullOrBlank()) JSONObject(text) else JSONObject()
    } catch (_: Throwable) { null }
}
