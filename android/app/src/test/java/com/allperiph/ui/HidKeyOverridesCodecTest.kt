package com.allperiph.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键映射覆盖层的**落盘往返**回归网（纯 JVM，不需要设备）：
 *
 *     ./gradlew :app:testReleaseUnitTest
 *
 * 为什么值得存在：2026-11-02 的仓库审查确认了一条「设置写了、行为没变」的静默失效——
 * 写入走 `keyCode=usage`（`"4=40;5=41"`），读回却按纯整数切分，`"4=40".toIntOrNull()` 是 null，
 * 被 `mapNotNull` 丢掉，于是每次 `App.onCreate` 的 `load()` 都得到空数组：改键当次有效，
 * 进程重启后全丢。本测试把「写进去的串能原样读回同一张表」钉死，同时锁住对两种历史写法
 * （交错整数 / 交错整数加逗号）的兼容，避免以后再改一次格式又静默丢用户数据。
 */
class HidKeyOverridesCodecTest {

    /** encode → decode 必须还原同一张表（顺序按 keyCode 升序，与落盘格式一致） */
    @Test
    fun roundTripKeepsEveryPair() {
        val map = mapOf(4 to 0x29, 5 to 0x2A, 66 to 0x28)   // 4=返回 → Esc 之类
        val raw = encodeKeyOverrides(map)
        assertEquals("4=41;5=42;66=40", raw)
        assertArrayEquals(intArrayOf(4, 41, 5, 42, 66, 40), decodeKeyOverrides(raw))
    }

    /** 落盘格式必须是审查里点名的那种带等号的串（格式一旦漂移，存量值就读不回来） */
    @Test
    fun encodedFormatIsKeyEqualsUsage() {
        assertEquals("4=40;5=41", encodeKeyOverrides(mapOf(5 to 41, 4 to 40)))
    }

    /** 空表：写空串、读空数组（不抛、不返回脏数据） */
    @Test
    fun emptyMapRoundTripsToEmpty() {
        assertEquals("", encodeKeyOverrides(emptyMap()))
        assertTrue(decodeKeyOverrides("").isEmpty())
        assertTrue(decodeKeyOverrides("   ").isEmpty())
    }

    /** 兼容历史写法一：交错整数、逗号或分号分隔 */
    @Test
    fun legacyInterleavedIntsStillParse() {
        assertArrayEquals(intArrayOf(4, 40, 5, 41), decodeKeyOverrides("4,40;5,41"))
    }

    /** 兼容历史写法二：纯逗号拼接的交错整数 */
    @Test
    fun legacyCommaJoinedIntsStillParse() {
        assertArrayEquals(intArrayOf(4, 40, 5, 41), decodeKeyOverrides("4,40,5,41"))
    }

    /** 坏值不能把 App 拖下水：认不出的 token 直接跳过 */
    @Test
    fun garbageTokensAreSkipped() {
        assertArrayEquals(intArrayOf(4, 40), decodeKeyOverrides("abc;;4=40;x=y;=7;8="))
    }

    /** usage<=0 视为清除，不该作为覆盖留在表里 */
    @Test
    fun zeroUsageIsDropped() {
        assertTrue(decodeKeyOverrides("4=0;5=41").contentEquals(intArrayOf(5, 41)))
    }

    /** 同一颗键写两次：后写的赢（与 set() 的覆盖语义一致） */
    @Test
    fun laterPairWinsForSameKey() {
        assertArrayEquals(intArrayOf(4, 41), decodeKeyOverrides("4=40;4=41"))
    }
}
