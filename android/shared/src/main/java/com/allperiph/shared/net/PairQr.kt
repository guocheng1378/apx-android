package com.allperiph.shared.net

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 配对二维码内容（2026-11-26，TV 配对批）。
 *
 * 被控端（TV / 手机）配对页亮出二维码，控制端（手机）扫码直连。统一格式：
 *
 *     allperf://<ip>:<port>?c=<6位配对码>
 *
 * - **凭据只放一次性配对码（c=），不放持久令牌（t=）**：二维码会显示在屏幕上，
 *   被同屋人拍到也无妨——码 10 分钟过期、连错 5 次作废；持久令牌写进画面等于
 *   把长效凭据贴墙上，绝对不干。
 * - 握手走既有 PairingCode + 0x36 持久令牌流（ControlServer.handshake 已实现），
 *   不新增凭据类型；对端落盘后与信标路径同权。
 * - 端口显式带上（虽然当前恒为 9511）：将来端口可配时不改格式。
 */
object PairQr {
    private const val SCHEME = "allperf://"

    /** 组二维码内容：`allperf://<ip>:<port>?c=<code>` */
    fun encode(ip: String, port: Int, code: String): String = "$SCHEME$ip:$port?c=$code"

    /**
     * 解析二维码内容：合法的 allperf:// 配对链接返回 (ip, port, code)，其余 null。
     * 宽松解析——端口缺省按 9511；code 非空才返回（空码握手必被拒，不如让调用方提示重扫）。
     */
    fun decode(text: String): Triple<String, Int, String>? {
        if (!text.startsWith(SCHEME)) return null
        val rest = text.removePrefix(SCHEME)
        val qIdx = rest.indexOf("?c=")
        if (qIdx <= 0) return null
        val hostPort = rest.take(qIdx)
        val code = rest.substring(qIdx + 3)
        val colon = hostPort.lastIndexOf(':')
        val ip: String
        val port: Int
        if (colon > 0) {
            ip = hostPort.take(colon)
            port = hostPort.substring(colon + 1).toIntOrNull() ?: ControlServer.PORT
        } else {
            ip = hostPort
            port = ControlServer.PORT
        }
        if (ip.isBlank() || code.isBlank()) return null
        return Triple(ip, port, code)
    }

    /**
     * 生成配对二维码位图（黑码白底，静区按 zxing 默认）。生成失败（内容异常等）
     * 返回 null，调用方降级为只展示 6 位码。
     */
    fun makeBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
        val bits = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val w = bits.width; val h = bits.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = if (bits[x, y]) Color.BLACK else Color.WHITE
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        bmp
    }.getOrNull()
}
