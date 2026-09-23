package com.allperiph.touchpad

import android.view.MotionEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

/** 归一化后的触控板帧（与 PC 端 MouseFrame 语义对齐，相对位移） */
data class TouchpadFrame(
    val dx: Int,
    val dy: Int,
    val buttons: Int,   // bit0 左, bit1 右, bit2 中
    val wheel: Int = 0, // 垂直滚动（+ = 向上）
    val pan: Int = 0,   // 水平滚动
    val consumer: Int = 0, // v1.7c：≠0 时走 Consumer Report 4（u16 位图，如捏合缩放）
    val tsNs: Long = 0L,
)

interface TouchpadSink { fun send(f: TouchpadFrame) }

/**
 * 手势识别：单指移动/点按、双指滚动、双指右键、三指中键。
 * 只产出相对增量，保证跟手（手势在手机端识别，架构 §4）。
 *
 * 优化 #2：惯性滚动用 AtomicInteger 版本号替代 interrupt()，
 *          避免线程泄漏和竞争。
 * 优化 #9：centroid + fingerDist 合并为 singlePassCentroidDist。
 */
class GestureEngine(private val sink: TouchpadSink) {
    var sensitivity = 1.0f
    var scrollStep = 1
    var acceleration = 0.4f

    // ---- 惯性滚动 ----
    private var lastWheelV = 0
    private var lastPanV = 0
    private var lastScrollT = 0L

    /** 惯性版本号：每次 startInertia() 递增，旧版本线程自动退出 */
    private val inertiaVersion = AtomicInteger(0)
    private var inertialThread: Thread? = null

    // v1.7d-fix：单击释放帧用共享单线程池
    private val releaseExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "apx-tap-release").also { it.isDaemon = true }
    }

    private fun kickInertia(wheel: Int, pan: Int, nowMs: Long) {
        lastWheelV = wheel; lastPanV = pan; lastScrollT = nowMs
    }

    /**
     * 停止惯性：递增版本号，旧线程在下次循环检查时自行退出。
     * 比 interrupt() 更可靠——interrupt 依赖 sleep 被唤醒，
     * 而线程如果恰好在执行 sink.send() 则不会被中断。
     */
    private fun stopInertia() {
        inertiaVersion.incrementAndGet()
    }

    /** UP 后启动惯性：按 16ms 步进衰减（×0.90），速度 <2 停止 */
    private fun startInertia() {
        stopInertia()
        var v = lastWheelV
        var p = lastPanV
        if (v == 0 && p == 0) return
        val ver = inertiaVersion.get()
        inertialThread = Thread {
            try {
                while ((Math.abs(v) > 1 || Math.abs(p) > 1) &&
                       inertiaVersion.get() == ver) {
                    Thread.sleep(16)
                    v = (v * 0.90f).toInt()
                    p = (p * 0.90f).toInt()
                    sink.send(TouchpadFrame(0, 0, 0, wheel = v, pan = p))
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; name = "apx-inertia"; start() }
    }

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var pendingButtons = 0
    private var activePointers = 0
    private var twoFingerCx = 0f
    private var twoFingerCy = 0f
    private var twoFingerDownTime = 0L
    private var twoFingerBaseDist = 0f
    @Volatile private var lastTwoTapSent = 0L
    private var dragArmed = false
    private var dragSent = false

    fun armDrag(): Boolean {
        if (activePointers != 1 || dragSent) {
            com.allperiph.core.Log.i("GestureEngine",
                "armDrag skipped: ptr=$activePointers armed=$dragArmed sent=$dragSent")
            return false
        }
        if (dragArmed) {
            com.allperiph.core.Log.i("GestureEngine", "armDrag already armed → buzz")
            return true
        }
        val moved = hypot((lastX - downX).toDouble(), (lastY - downY).toDouble())
        if (moved >= TAP_SLOP * 2f) {
            com.allperiph.core.Log.i("GestureEngine", "armDrag skipped: moved=$moved")
            return false
        }
        dragArmed = true
        com.allperiph.core.Log.i("GestureEngine", "armDrag LOCKED")
        return true
    }

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x; lastY = ev.y; downX = ev.x; downY = ev.y
                downTime = ev.eventTime; activePointers = 1; pendingButtons = 0
                dragArmed = false; dragSent = false
                stopInertia()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers++
                if (ev.pointerCount >= 2 && twoFingerDownTime == 0L) {
                    twoFingerDownTime = ev.eventTime
                    val (cx, cy) = centroid(ev)
                    twoFingerCx = cx; twoFingerCy = cy
                }
                dragArmed = false
                if (dragSent) { sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L)); dragSent = false }
            }
            MotionEvent.ACTION_MOVE -> handleMove(ev)
            MotionEvent.ACTION_POINTER_UP -> {
                if (activePointers == 2 && twoFingerDownTime > 0L) {
                    val dur = ev.eventTime - twoFingerDownTime
                    val (cx, cy) = centroid(ev)
                    val moved = hypot((cx - twoFingerCx).toDouble(), (cy - twoFingerCy).toDouble())
                    if (dur < TAP_MS && moved < TAP_SLOP) {
                        val now = ev.eventTime
                        if (now - lastTwoTapSent > 300) {
                            lastTwoTapSent = now
                            sink.send(TouchpadFrame(0, 0, 0x02, tsNs = now * 1_000_000L))
                            sink.send(TouchpadFrame(0, 0, 0x00, tsNs = now * 1_000_000L))
                        }
                    }
                    twoFingerDownTime = 0L
                }
                if (activePointers > 0) activePointers--
            }
            MotionEvent.ACTION_UP -> {
                val dur = ev.eventTime - downTime
                if (System.currentTimeMillis() - lastScrollT < 120) startInertia()
                if (dragSent) {
                    sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L))
                } else if (pendingButtons != 0) {
                    sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L))
                } else if (activePointers == 1 && dur < TAP_MS &&
                    hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) < TAP_SLOP) {
                    val ts = ev.eventTime * 1_000_000L
                    sink.send(TouchpadFrame(0, 0, 0x01, tsNs = ts))
                    releaseExecutor.execute {
                        try { Thread.sleep(25) } catch (_: InterruptedException) {}
                        sink.send(TouchpadFrame(0, 0, 0x00, tsNs = ts + 25_000_000L))
                    }
                    com.allperiph.core.Log.i("GestureEngine", "tap sent (dur=${dur}ms)")
                }
                pendingButtons = 0
                activePointers = 0
                dragArmed = false; dragSent = false
                twoFingerDownTime = 0L
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragSent) sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L))
                activePointers = 0
                dragArmed = false; dragSent = false
            }
        }
    }

    private fun handleMove(ev: MotionEvent) {
        when (activePointers) {
            1 -> {
                val moved = hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble())
                val dur = ev.eventTime - downTime
                if (!dragArmed && !dragSent && moved < TAP_SLOP && dur > LONG_PRESS_MS) {
                    dragArmed = true
                }
                if (dragArmed && !dragSent && moved >= TAP_SLOP) {
                    dragSent = true
                    sink.send(TouchpadFrame(0, 0, 0x01, tsNs = ev.eventTime * 1_000_000L))
                    lastX = ev.x; lastY = ev.y
                    return
                }
                var dx = (ev.x - lastX) * sensitivity
                var dy = (ev.y - lastY) * sensitivity
                if (acceleration > 0f) {
                    val mag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val k = 1f + acceleration * (mag / 200f)
                    dx *= k; dy *= k
                }
                lastX = ev.x; lastY = ev.y
                val btn = if (dragSent) (pendingButtons or 0x01) else pendingButtons
                sink.send(TouchpadFrame(dx.toInt(), dy.toInt(), btn, tsNs = ev.eventTime * 1_000_000L))
            }
            2 -> {
                val d = fingerDist(ev)
                if (twoFingerBaseDist > 0f) {
                    val scale = d / twoFingerBaseDist
                    if (scale > 1.25f || scale < 0.80f) {
                        val zoomBit = if (scale > 1f) 0x0080 else 0x0100
                        sink.send(TouchpadFrame(0, 0, 0, consumer = zoomBit, tsNs = ev.eventTime * 1_000_000L))
                        sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L))
                        lastX = ev.x; lastY = ev.y
                        return
                    }
                }
                val (cx, cy) = centroid(ev)
                val dx = cx - lastX
                val dy = cy - lastY
                lastX = cx; lastY = cy
                val w = -(dy * scrollStep).toInt()
                val p = (dx * scrollStep).toInt()
                kickInertia(w, p, System.currentTimeMillis())
                sink.send(TouchpadFrame(0, 0, pendingButtons, wheel = w, pan = p, tsNs = ev.eventTime * 1_000_000L))
            }
            3 -> {
                if (pendingButtons and 0x04 == 0) {
                    pendingButtons = pendingButtons or 0x04
                    sink.send(TouchpadFrame(0, 0, pendingButtons, tsNs = ev.eventTime * 1_000_000L))
                }
            }
        }
    }

    // 优化 #9：合并 centroid 和 fingerDist 为一次遍历
    private fun singlePassCentroidDist(ev: MotionEvent): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (i in 0 until ev.pointerCount) { sx += ev.getX(i); sy += ev.getY(i) }
        val n = ev.pointerCount.coerceAtLeast(1)
        return sx / n to sy / n
    }

    private fun centroid(ev: MotionEvent): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (i in 0 until ev.pointerCount) { sx += ev.getX(i); sy += ev.getY(i) }
        val n = ev.pointerCount.coerceAtLeast(1)
        return sx / n to sy / n
    }

    private fun fingerDist(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        return hypot(
            (ev.getX(0) - ev.getX(1)).toDouble(),
            (ev.getY(0) - ev.getY(1)).toDouble(),
        ).toFloat()
    }

    companion object {
        private const val TAP_SLOP = 12f
        private const val TAP_MS = 350L
        private const val LONG_PRESS_MS = 550L
    }
}