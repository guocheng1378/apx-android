package com.allperiph.shared.util

import android.util.Log as AndroidLog
import com.allperiph.shared.event.EventBus
import com.allperiph.shared.event.LogEvent

/**
 * 统一日志：写 logcat + 保留最近 N 条环形缓冲（供 UI 展示）+ 广播到事件总线。
 * 高频路径（传感器回调）请自行降频后再调用。
 */
object Log {

    enum class Level { V, D, I, W, E }

    data class Entry(
        val wallMs: Long,
        val elapsedMs: Long,
        val level: Level,
        val tag: String,
        val msg: String,
    )

    private const val RING_CAPACITY = 512
    private val ring = ArrayDeque<Entry>(RING_CAPACITY)
    private val lock = Any()

    @Volatile
    var minLevel: Level = Level.D

    /**
     * 单参重载默认 tag：与原 TV 端 `com.allperiph.tv.core.Log`（TAG="ApxTv"）一致，
     * 供 TV 模块沿用其单参 `Log.i(msg)` 调用风格；手机端一律走双参显式 tag。
     */
    private const val DEFAULT_TAG = "ApxTv"

    fun v(tag: String, msg: String) = println(Level.V, tag, msg)
    fun d(tag: String, msg: String) = println(Level.D, tag, msg)
    fun i(tag: String, msg: String) = println(Level.I, tag, msg)
    fun w(tag: String, msg: String) = println(Level.W, tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        println(Level.E, tag, if (tr == null) msg else "$msg\n${tr.stackTraceToString()}")
    }

    /** 单参重载（tag 取 [DEFAULT_TAG]），兼容 TV 端原单参日志 API */
    fun v(msg: String) = println(Level.V, DEFAULT_TAG, msg)
    fun d(msg: String) = println(Level.D, DEFAULT_TAG, msg)
    fun i(msg: String) = println(Level.I, DEFAULT_TAG, msg)
    fun w(msg: String) = println(Level.W, DEFAULT_TAG, msg)
    fun e(msg: String, tr: Throwable? = null) = e(DEFAULT_TAG, msg, tr)

    private fun println(level: Level, tag: String, msg: String) {
        val priority = when (level) {
            Level.V -> AndroidLog.VERBOSE
            Level.D -> AndroidLog.DEBUG
            Level.I -> AndroidLog.INFO
            Level.W -> AndroidLog.WARN
            Level.E -> AndroidLog.ERROR
        }
        AndroidLog.println(priority, tag, msg)

        if (level.ordinal < minLevel.ordinal) return
        val entry = Entry(
            wallMs = System.currentTimeMillis(),
            elapsedMs = android.os.SystemClock.elapsedRealtime(),
            level = level,
            tag = tag,
            msg = msg,
        )
        synchronized(lock) {
            while (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(entry)
        }
        runCatching { EventBus.post(LogEvent(entry)) }
    }

    /** 快照，供 UI 拉取历史日志 */
    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    fun clear() = synchronized(lock) { ring.clear() }
}
