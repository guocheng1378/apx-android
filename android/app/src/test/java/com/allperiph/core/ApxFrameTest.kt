package com.allperiph.core

import com.allperiph.shared.proto.ApxFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APX1 帧协议与分片重组的回归网（纯 JVM，不需要设备）：
 *
 *     ./gradlew :app:testReleaseUnitTest
 *
 * 为什么值得存在：同一份协议被三端手写了四遍（C++ / 手机端 / TV 端 / PC 控制面），
 * v1.7 之前修的多个缺陷都属于"一句话就能钉死"的那类 ——
 *   · 键盘帧键码起始偏移（发送端 b[3+i] vs 接收端 p+3，每帧少按最后一个键）
 *   · 接收侧从不校验 CRC（错位数据直接进解码器 → 花屏 / 乱点光标）
 *   · magic 失配时整体丢缓冲（一次错位 → 后面整段输入失灵）
 *   · 0x25 请求输入多塞 4 字节 sourceId（与另三处实现都不兼容）
 *
 * 下面用**黄金字节**把线上格式焊死：任何改动组帧 / 解析（CRC 多项式、字段偏移、
 * 字节序、长度语义）都会立刻变红，而不是等到真机上表现为"某方向偶尔不工作"。
 */
class ApxFrameTest {

    /** 黄金帧：streamId=3（控制面）、seq=0x11223344、body = 鼠标帧 `[01 00 3C 3C 00]`。 */
    private val golden = byteArrayOf(
        0x41, 0x50, 0x58, 0x31,                                  // magic 'APX1'
        0x03, 0x00, 0x00, 0x00,                                  // streamId = 3
        0x09, 0x00, 0x00, 0x00,                                  // payloadLen = body(5) + CRC(4)
        0x44, 0x33, 0x22, 0x11,                                  // seq = 0x11223344（小端）
        0x01, 0x00, 0x3C, 0x3C, 0x00,                            // body：buttons=0 dx=60 dy=60 wheel=0
        0xA6.toByte(), 0xBB.toByte(),                            // CRC32(body) = 0xA5C0BBA6（小端）
        0xC0.toByte(), 0xA5.toByte()
    )
    private val goldenBody = byteArrayOf(0x01, 0x00, 0x3C, 0x3C, 0x00)
    private val goldenPayloadLen = 9

    // ———————————————————————— 线上格式（跨语言契约）————————————————————————

    @Test
    fun `组帧结果与黄金字节逐字节一致`() {
        assertArrayEquals(
            "改动组帧（CRC 多项式 / 字段偏移 / 字节序）会让三端互不兼容",
            golden, ApxFrame.build(ApxFrame.STREAM_CONTROL, goldenBody, 0x11223344)
        )
    }

    @Test
    fun `解析黄金帧各字段正确`() {
        assertTrue("magic 必须识别", ApxFrame.isMagic(golden, 0, golden.size))
        assertEquals(3, ApxFrame.streamIdAt(golden, 0))
        assertEquals(0, ApxFrame.flagsAt(golden, 0))
        assertEquals(goldenPayloadLen, ApxFrame.payloadLenAt(golden, 0))
        assertEquals(0x11223344, ApxFrame.seqAt(golden, 0))
        assertEquals(goldenBody.size, ApxFrame.bodyLenOf(ApxFrame.payloadLenAt(golden, 0)))
        assertTrue("尾部 CRC 必须校验通过", ApxFrame.verify(golden, 0, goldenPayloadLen))
        assertArrayEquals(goldenBody, ApxFrame.bodyAt(golden, 0, goldenPayloadLen))
    }

    @Test
    fun `CRC32 与标准测试向量一致`() {
        // IEEE 802.3 / zlib 的标准校验值：改多项式或初值都会在这里露馅
        assertEquals(0xCBF43926.toInt(), ApxFrame.crc32("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0xA5C0BBA6.toInt(), ApxFrame.crc32(goldenBody))
        assertEquals("空输入应为 0", 0, ApxFrame.crc32(ByteArray(0)))
    }

    @Test
    fun `载荷被篡改时 CRC 校验必须失败`() {
        for (i in goldenBody.indices) {
            val bad = golden.copyOf()
            bad[ApxFrame.HEADER_SIZE + i] = (bad[ApxFrame.HEADER_SIZE + i].toInt() xor 0x01).toByte()
            assertFalse(
                "body[$i] 改动后仍通过校验 —— 说明 CRC 没有覆盖整段 body",
                ApxFrame.verify(bad, 0, goldenPayloadLen)
            )
        }
        val badCrc = golden.copyOf()
        badCrc[golden.size - 1] = (badCrc[golden.size - 1].toInt() xor 0x01).toByte()
        assertFalse("CRC 字段自身被改动也必须失败", ApxFrame.verify(badCrc, 0, goldenPayloadLen))
    }

    @Test
    fun `CRC 只覆盖 body_改帧头不影响校验结果`() {
        // 协议规定 CRC32 覆盖范围是「16 字节帧头之后的全部字节，不含尾部 4 字节」（PROTOCOL §3）
        val t = golden.copyOf()
        t[4] = 1                                        // streamId 改成 audio
        assertTrue("帧头不属于 CRC 覆盖范围", ApxFrame.verify(t, 0, goldenPayloadLen))
        assertEquals(1, ApxFrame.streamIdAt(t, 0))
    }

    @Test
    fun `截断与非法输入不崩溃且被判为非法`() {
        for (len in 0 until golden.size) {
            val part = golden.copyOf(len)
            if (len < golden.size) {
                assertFalse("截断到 $len 字节仍被认为 CRC 合法", ApxFrame.verify(part, 0, goldenPayloadLen))
            }
            // bodyAt 允许返回 null，但绝不能抛越界异常
            ApxFrame.bodyAt(part, 0, goldenPayloadLen)
            if (len >= 4) assertTrue("前 4 字节是 magic 时应被识别", ApxFrame.isMagic(part, 0, part.size))
        }
        assertFalse(ApxFrame.isMagic(ByteArray(0), 0, 0))
        assertFalse(ApxFrame.isMagic(byteArrayOf(0, 0, 0, 0), 0, 4))
    }

    @Test
    fun `magic 失配后逐字节重同步仍能找到合法帧`() {
        // 收帧纪律：失配只前进一字节，**禁止**整体丢缓冲（否则一次错位丢掉后面所有帧）
        val junk = byteArrayOf(0x00, 0x41, 0x50, 0x58, 0x99.toByte(), 0x7F)   // 含部分 magic 的噪声
        val stream = junk + golden
        var off = 0
        var found = -1
        while (stream.size - off >= ApxFrame.HEADER_SIZE) {
            if (!ApxFrame.isMagic(stream, off, stream.size)) { off++; continue }
            val payloadLen = ApxFrame.payloadLenAt(stream, off)
            if (payloadLen < ApxFrame.CRC_SIZE || payloadLen > ApxFrame.MAX_PAYLOAD) { off++; continue }
            val total = ApxFrame.totalSize(payloadLen)
            if (stream.size - off < total) break
            if (!ApxFrame.verify(stream, off, payloadLen)) { off += total; continue }
            found = off
            break
        }
        assertEquals("应跳过 ${junk.size} 字节噪声后对齐到黄金帧", junk.size, found)
    }

    @Test
    fun `载荷长度边界与上限判定`() {
        assertEquals("装不下尾部 CRC", -1, ApxFrame.bodyLenOf(3))
        assertEquals(0, ApxFrame.bodyLenOf(ApxFrame.CRC_SIZE))
        assertEquals(16, ApxFrame.HEADER_SIZE)
        assertEquals(4, ApxFrame.CRC_SIZE)
        assertEquals(4 * 1024 * 1024, ApxFrame.MAX_PAYLOAD)
        assertEquals(ApxFrame.HEADER_SIZE + 100, ApxFrame.totalSize(100))

        val f = ApxFrame.build(ApxFrame.STREAM_CONTROL, goldenBody, 1)
        putLe32(f, 8, ApxFrame.MAX_PAYLOAD + 1)
        assertTrue(
            "收帧泵必须拒绝超过 MAX_PAYLOAD 的帧",
            ApxFrame.payloadLenAt(f, 0) > ApxFrame.MAX_PAYLOAD
        )
    }

    // ———————————————————————— 远程输入组帧（0x25 / 0x26 / 0x27）————————————————————————

    @Test
    fun `请求输入帧布局与另三端一致`() {
        val body = ApxFrame.packRequestInput("hi", 0xDEADBEEF.toInt())
        assertEquals(0x25, body[0].toInt() and 0xFF)
        assertEquals("hintLen 必须在 [1]（PC 侧按 p[1] 读）", 2, body[1].toInt() and 0xFF)
        assertEquals("提示文字必须在 [2] 起（PC 侧按 p+2 读）", "hi", String(body, 2, 2, Charsets.UTF_8))
        assertEquals("总长 = 2 + hintLen", 4, body.size)
    }

    @Test
    fun `请求输入提示按 u8 上限截断`() {
        val body = ApxFrame.packRequestInput("x".repeat(300), 0)
        assertEquals(255, body[1].toInt() and 0xFF)
        assertEquals(2 + 255, body.size)
    }

    @Test
    fun `输入文本帧布局与长度语义`() {
        val txt = ApxFrame.packInputText(ApxFrame.INPUT_FLAG_INCREMENTAL, "你a")
        assertEquals(0x26, txt[0].toInt() and 0xFF)
        assertEquals(ApxFrame.INPUT_FLAG_INCREMENTAL, txt[1].toInt() and 0xFF)
        val len = (txt[2].toInt() and 0xFF) or ((txt[3].toInt() and 0xFF) shl 8)
        assertEquals("长度字段必须是 UTF-8 字节数（你=3 + a=1）", 4, len)
        assertEquals(4 + len, txt.size)
        assertEquals("你a", String(txt, 4, len, Charsets.UTF_8))
    }

    @Test
    fun `输入结束帧只有一个命令字节`() {
        val done = ApxFrame.packInputDone()
        assertEquals(1, done.size)
        assertEquals(0x27, done[0].toInt() and 0xFF)
    }

    private fun putLe32(dst: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) dst[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}

/**
 * 分片重组（视频帧超过 256KiB 会被切开，各分片 seq 相同、**只有末片**带 last_fragment）。
 * 这里的断言对应 PROTOCOL §3.4 的纪律：seq 跳变必须丢弃已攒部分 ——
 * "宁可这一帧花屏，也不能把两帧拼成一帧"。
 */
class FragmentJoinerTest {

    @Test
    fun `分片攒齐后返回完整载荷`() {
        val j = FragmentJoiner()
        assertNull("非末片不应返回", j.push(0, 7, byteArrayOf(1, 2)))
        assertNull("非末片不应返回", j.push(0, 7, byteArrayOf(3)))
        val whole = j.push(ApxFrame.FLAG_LAST_FRAGMENT, 7, byteArrayOf(4, 5))
        assertNotNull(whole)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), whole)
        // 收齐后必须复位，下一帧从零开始
        assertArrayEquals(byteArrayOf(9), j.push(ApxFrame.FLAG_LAST_FRAGMENT, 8, byteArrayOf(9)))
    }

    @Test
    fun `seq 跳变时丢弃已攒部分而不是拼成一帧`() {
        val j = FragmentJoiner()
        assertNull(j.push(0, 7, byteArrayOf(1, 2, 3)))          // 中间丢片前的残留
        val whole = j.push(ApxFrame.FLAG_LAST_FRAGMENT, 9, byteArrayOf(4))
        assertArrayEquals("只应包含新 seq 的内容", byteArrayOf(4), whole)
    }

    @Test
    fun `累计超上限时丢弃当前帧并复位`() {
        val j = FragmentJoiner(maxBytes = 8)
        assertNull(j.push(0, 1, byteArrayOf(1, 2, 3)))
        assertNull("超过上限应丢弃", j.push(0, 1, ByteArray(8)))
        assertArrayEquals("复位后应能重新开始", byteArrayOf(9),
            j.push(ApxFrame.FLAG_LAST_FRAGMENT, 1, byteArrayOf(9)))
    }
}
