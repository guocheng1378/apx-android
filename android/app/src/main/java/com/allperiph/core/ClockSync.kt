package com.allperiph.core

/**
 * 时钟与同步（PROTOCOL §1）。
 * 手机端时基统一 [nowNs]（单调、含深睡）；与 PC 的偏移用**最小 RTT** 估计 + 指数平滑。
 */
object ClockSync {

    private const val SMOOTHING = 0.2

    @Volatile
    private var offsetNs: Long = 0L

    @Volatile
    private var minRttNs: Long = Long.MAX_VALUE

    @Volatile
    private var lastSyncNs: Long = 0L

    @Volatile
    private var resyncNeeded: Boolean = true

    /** 手机端时基（含深睡的单调时钟） */
    fun nowNs(): Long = android.os.SystemClock.elapsedRealtimeNanos()

    /** 记录一次 ping/pong 往返：取 RTT 最小样本，偏移做指数平滑 */
    fun onRttSample(rttNs: Long, pcClockNs: Long, phoneClockNs: Long) {
        if (rttNs <= 0) return
        if (rttNs < minRttNs) minRttNs = rttNs
        // 单程延迟按 RTT/2 估计：pc ≈ phone + offset
        val sample = pcClockNs - phoneClockNs - rttNs / 2
        offsetNs = if (lastSyncNs == 0L) sample else (offsetNs + (sample - offsetNs) * SMOOTHING).toLong()
        lastSyncNs = nowNs()
        resyncNeeded = false
    }

    /** 链路中断后必须重新同步（协议 §2.3 flags bit0） */
    fun markResyncNeeded() {
        resyncNeeded = true
    }

    fun isResyncNeeded(): Boolean = resyncNeeded

    fun reset() {
        offsetNs = 0L
        minRttNs = Long.MAX_VALUE
        lastSyncNs = 0L
        resyncNeeded = true
    }

    fun offsetNs(): Long = offsetNs
    fun minRttNs(): Long = if (minRttNs == Long.MAX_VALUE) -1 else minRttNs

    /** 手机时基 → PC 时基 */
    fun toPcNs(phoneNs: Long): Long = phoneNs + offsetNs

    /** 构造 §2.3 的 flags：bit0=需重新同步 */
    fun imuFlags(sampleLost: Boolean): Int =
        (if (resyncNeeded) ImuFlag.NEED_RESYNC else 0) or
            (if (sampleLost) ImuFlag.SAMPLE_LOST else 0)
}
