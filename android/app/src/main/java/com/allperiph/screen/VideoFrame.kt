package com.allperiph.screen

/**
 * 副屏视频帧载荷的解析（`docs/PROTOCOL.md` §3.1）。
 *
 * 全帧布局（`body` 已经由 [com.allperiph.core.ApxStreams] 剥掉了 16 字节 APX1 帧头
 * 与尾部 4 字节 CRC）：
 * ```
 * off  size  field
 *  0    8    ptsNs            u64   手机端时基
 *  8    2    width            u16
 * 10    2    height           u16
 * 12    2    frameRateX100   u16
 * 14    2    dirtyRectCount  u16
 * 16    2    codecId          u16   0=H264 1=HEVC 2=AV1 3=MJPEG 4=RAW_LZ4
 * 18    2    reserved         u16   补齐到 20 字节（5 个 32bit 字）
 * 20   ...  DirtyRect × N    8B each（u16 x/y/w/h）
 *      ...  bitstream
 * ```
 *
 * 注意：`headerExtWords` **不额外增加总长** —— 扩展头本身就含在 `payloadLen` 里
 * （这是协议踩过坑的地方，见 `docs/PROTOCOL.md` §3 的权威定义）。
 *
 * 本解析只取解码所需的信息；**脏矩形直接跳过**——增量编码时 PC 只发变化区域，
 * 但我们让 MediaCodec 解整帧位流即可（解码器自己会处理），不做局部贴图。
 */
object VideoFrame {

    /** 扩展头固定 20 字节 */
    const val EXT_HEADER_SIZE = 20

    /** DirtyRect 每条 8 字节 */
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
        /** 是否为解码器认得的编码（其余如 RAW_LZ4/MJPEG 本端暂不解码，如实降级） */
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
