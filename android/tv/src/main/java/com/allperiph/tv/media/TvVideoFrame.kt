package com.allperiph.tv.media

/**
 * 副屏视频帧载荷解析（与手机端 `com.allperiph.screen.VideoFrame` 同协议，见 `docs/PROTOCOL.md` §3.1）。
 *
 * `body` 已由媒体通道剥掉 16 字节 APX1 帧头与尾部 4 字节 CRC，剩下：
 * ```
 * off  size  field
 *  0    8    ptsNs            u64
 *  8    2    width            u16
 * 10    2    height           u16
 * 12    2    frameRateX100   u16
 * 14    2    dirtyRectCount  u16
 * 16    2    codecId          u16   0=H264 1=HEVC 2=AV1 3=MJPEG 4=RAW_LZ4
 * 18    2    reserved         u16
 * 20   ...  DirtyRect × N    8B each（u16 x/y/w/h）
 *      ...  bitstream
 * ```
 * 脏矩形照例跳过：交给解码器解整帧位流即可（增量编码时 PC 只发变化区域，解码器自行处理）。
 */
object TvVideoFrame {

    const val EXT_HEADER_SIZE = 20
    const val DIRTY_RECT_SIZE = 8

    const val CODEC_H264 = 0
    const val CODEC_HEVC = 1
    const val CODEC_AV1 = 2
    const val CODEC_MJPEG = 3
    const val CODEC_RAW_LZ4 = 4

    class Parsed(
        val width: Int,
        val height: Int,
        val codecId: Int,
        val bitstreamOffset: Int,
        val bitstreamLength: Int,
    ) {
        val decodable: Boolean
            get() = codecId == CODEC_H264 || codecId == CODEC_HEVC || codecId == CODEC_AV1
    }

    fun parse(body: ByteArray): Parsed? {
        if (body.size < EXT_HEADER_SIZE) return null
        val width = u16(body, 8)
        val height = u16(body, 10)
        val rectCount = u16(body, 14)
        val codecId = u16(body, 16)
        if (width <= 0 || height <= 0) return null
        val offset = EXT_HEADER_SIZE + rectCount * DIRTY_RECT_SIZE
        if (offset > body.size) return null
        return Parsed(width, height, codecId, offset, body.size - offset)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
