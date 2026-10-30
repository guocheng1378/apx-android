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
 * 手势里程碑：**只在这些状态切换点**给一次震动反馈。
 * 滑动过程中的每一帧都不算里程碑 —— 逐帧震会变成持续嗡嗡声，也很费电。
 */
enum class GestureFeedback {
    DRAG_LOCK,      // 长按锁定拖拽（现在可以拖了）
    SCROLL_ENTER,   // 双指滑动进入滚动
    ZOOM,           // 捏合跨过缩放阈值
    RIGHT_TAP,      // 双指点按 → 右键
    RIGHT_DRAG,     // 双指长按 → 右键拖拽
    MIDDLE,         // 三指按下 → 中键
    THREE_SWIPE,    // 三指滑动 → 组合键（上/下/左/右，由外层转译）
}

/**
 * 手势识别：单指移动/点按、双指滚动、双指右键、三指中键。
 * 只产出相对增量，保证跟手（手势在手机端识别，架构 §4）。
 *
 * 优化 #2：惯性滚动用 AtomicInteger 版本号替代 interrupt()，
 *          避免线程泄漏和竞争。
 * 优化 #9：centroid + fingerDist 合并为 singlePassCentroidDist。
 */
class GestureEngine(private val sink: TouchpadSink) {
    /** 手势里程碑回调（震动 / 声音）。引擎层不碰 Android API，由外层注入实现。 */
    var onFeedback: ((GestureFeedback) -> Unit)? = null

    /** 三指滑动方向回调（0上 1下 2左 3右）：鼠标/无线链路 Windows 看不见手指，由外层转译组合键 */
    var onThreeSwipe: ((Int) -> Unit)? = null

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

    /** 停止惯性：递增版本号，旧线程在下次循环检查时自行退出。
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
    // P0-1/P1-4：单指移动的每轴亚像素累加器 —— dx.toInt() 截断会把 <1px 的移动全丢掉；
    // 余数（含整帧被钳 ±127 截下的超限部分）留在这里下一帧续发，位移既不丢也不回绕。
    private var fracX = 0f
    private var fracY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var pendingButtons = 0
    private var activePointers = 0
    private var twoFingerCx = 0f
    private var twoFingerCy = 0f
    private var twoFingerDownTime = 0L
    private var twoFingerBaseDist = 0f
    private var scrollEntered = false      // 本轮双指滑动是否已给过"进入滚动"反馈
    private var twoFingerMovedPx = 0f      // 双指本轮质心累计位移（滚动 vs 静止长按的判据，issue #52 第 4 条）
    private var scrollLocked = false       // 本轮双指已进入滚动：长按/右键点按不再生效
    private var scrollFracX = 0f           // 双指滚动 pan 的亚像素累加（慢滚不丢帧）
    private var scrollFracY = 0f           // 双指滚动 wheel 的亚像素累加
    @Volatile private var lastTwoTapSent = 0L
    private var dragArmed = false
    private var dragSent = false

    // ---- 修复面补全 ----
    // (a) 手势消费锁存：双指/三指点按等复合手势已发出后置 true，
    //     末指抬起时不再补发左键点按（否则右键后又跟一下左键）。
    private var gestureConsumed = false
    // (d) 三指起手基准：判「三指点按」用的位移/时长起点
    private var threeDownTime = 0L
    private var threeDownX = 0f
    private var threeDownY = 0f
    // (c) 最近一次真实移动的 eventTime：长按静止自动 arm 保守化的依据
    private var lastMoveT = 0L

    fun armDrag(): Boolean {
        if (activePointers != 1 || dragSent) {
            com.allperiph.shared.util.Log.i("GestureEngine",
                "armDrag skipped: ptr=$activePointers armed=$dragArmed sent=$dragSent")
            return false
        }
        if (dragArmed) {
            com.allperiph.shared.util.Log.i("GestureEngine", "armDrag already armed → buzz")
            return true
        }
        val moved = hypot((lastX - downX).toDouble(), (lastY - downY).toDouble())
        if (moved >= TAP_SLOP * 2f) {
            com.allperiph.shared.util.Log.i("GestureEngine", "armDrag skipped: moved=$moved")
            return false
        }
        dragArmed = true
        // ★ 锁定这一刻给一次反馈：用户手指没离开屏幕，看不到任何视觉变化，
        //   不震一下就完全不知道"已经可以拖了"。
        onFeedback?.invoke(GestureFeedback.DRAG_LOCK)
        com.allperiph.shared.util.Log.i("GestureEngine", "armDrag LOCKED")
        return true
    }

    fun armRightDrag() {
        if (activePointers < 2) return
        // 手指已经在移动（滚动中）就不算长按：550ms 定时器在 Activity 侧无条件挂，
        // 真正的判定必须看位移 —— 否则任何超过 550ms 的双指滚动都会滚到一半被注入右键
        // （issue #52 第 4 条「双指滚动经常识别为双指右键」的根因）。
        if (scrollLocked || twoFingerMovedPx >= TWO_FINGER_SCROLL_SLOP) return
        pendingButtons = pendingButtons or 0x02  // bit1 = right click
        onFeedback?.invoke(GestureFeedback.RIGHT_DRAG)
        com.allperiph.shared.util.Log.i("GestureEngine", "armRightDrag: buttons=$pendingButtons")
    }

    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x; lastY = ev.y; downX = ev.x; downY = ev.y
                downTime = ev.eventTime; activePointers = 1; pendingButtons = 0
                dragArmed = false; dragSent = false
                scrollEntered = false
                scrollLocked = false; twoFingerMovedPx = 0f; scrollFracX = 0f; scrollFracY = 0f
                twoFingerBaseDist = 0f      // 新的一次触摸：捏合基线重新量
                fracX = 0f; fracY = 0f      // 新的一笔：清掉上一笔残留的亚像素余数
                gestureConsumed = false     // (a) 新的一笔：清掉上一笔的消费锁存
                threeDownTime = 0L
                lastMoveT = ev.eventTime    // (c) 静止计时起点
                stopInertia()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers++
                if (ev.pointerCount >= 2 && twoFingerDownTime == 0L) {
                    twoFingerDownTime = ev.eventTime
                    val (cx, cy) = centroid(ev)
                    twoFingerCx = cx; twoFingerCy = cy
                    // ★ 捏合基线：这个字段此前**从来没有赋过值**，恒为 0，于是下面
                    //   `if (twoFingerBaseDist > 0f)` 永远进不去 —— 双指捏合缩放（Consumer
                    //   Report 的缩放位）等于完全失效，用户怎么捏都没反应。
                    twoFingerBaseDist = fingerDist(ev)
                }
                scrollEntered = false
                scrollLocked = false; twoFingerMovedPx = 0f; scrollFracX = 0f; scrollFracY = 0f
                dragArmed = false
                if (dragSent) { sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L)); dragSent = false }
                // (b) 指数变化重建基准：新指落下时把 lastX/lastY 重置为当前质心 ——
                //     否则下帧 dx = 新质心 - 旧单指坐标，起手瞬间甩出一帧大位移（突跳）。
                val (cx, cy) = centroid(ev)
                lastX = cx; lastY = cy
                fracX = 0f; fracY = 0f
                lastMoveT = ev.eventTime
                if (ev.pointerCount == 3) {
                    threeDownTime = ev.eventTime
                    threeDownX = cx; threeDownY = cy
                }
            }
            MotionEvent.ACTION_MOVE -> handleMove(ev)
            MotionEvent.ACTION_POINTER_UP -> {
                if (activePointers == 2 && twoFingerDownTime > 0L) {
                    val dur = ev.eventTime - twoFingerDownTime
                    val (cx, cy) = centroid(ev)
                    val moved = hypot((cx - twoFingerCx).toDouble(), (cy - twoFingerCy).toDouble())
                    if (!gestureConsumed && dur < TAP_MS && moved < TAP_SLOP && !scrollLocked && pendingButtons == 0) {
                        val now = ev.eventTime
                        if (now - lastTwoTapSent > 300) {
                            lastTwoTapSent = now
                            sink.send(TouchpadFrame(0, 0, 0x02, tsNs = now * 1_000_000L))
                            sink.send(TouchpadFrame(0, 0, 0x00, tsNs = now * 1_000_000L))
                            onFeedback?.invoke(GestureFeedback.RIGHT_TAP)
                            // (a) 双指点按已消费本笔手势：末指抬起不再补发左键
                            gestureConsumed = true
                        }
                    }
                    twoFingerDownTime = 0L
                }
                // (d) 三指点按：3→2 抬起沿，位移与时长都没超阈 → 补发中键按下+抬起；
                //     静止三指在 ACTION_MOVE 的 case 3 里不会发中键（那条只认有位移的）。
                if (activePointers == 3 && threeDownTime > 0L && pendingButtons and 0x04 == 0) {
                    val dur = ev.eventTime - threeDownTime
                    val (cx, cy) = centroid(ev)
                    val moved3 = hypot((cx - threeDownX).toDouble(), (cy - threeDownY).toDouble())
                    if (dur < TAP_MS && moved3 < TAP_SLOP) {
                        val now = ev.eventTime
                        sink.send(TouchpadFrame(0, 0, 0x04, tsNs = now * 1_000_000L))
                        sink.send(TouchpadFrame(0, 0, 0x00, tsNs = now * 1_000_000L))
                        onFeedback?.invoke(GestureFeedback.MIDDLE)
                        gestureConsumed = true
                        com.allperiph.shared.util.Log.i("GestureEngine", "3-finger tap → middle click")
                    }
                    threeDownTime = 0L
                }
                if (activePointers > 0) activePointers--
                // (b) 指数变化重建基准：抬掉一指后把 lastX/lastY 重置为**剩余手指**的
                //     质心（2→1 即剩余那一根的坐标），消除回单指瞬间的位移突跳。
                run {
                    var sx = 0f; var sy = 0f; var n = 0
                    for (i in 0 until ev.pointerCount) {
                        if (i == ev.actionIndex) continue
                        sx += ev.getX(i); sy += ev.getY(i); n++
                    }
                    if (n > 0) { lastX = sx / n; lastY = sy / n; fracX = 0f; fracY = 0f }
                }
                lastMoveT = ev.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val dur = ev.eventTime - downTime
                if (System.currentTimeMillis() - lastScrollT < 120) startInertia()
                if (dragSent) {
                    sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L))
                } else if (pendingButtons != 0) {
                    sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L))
                } else if (!gestureConsumed && activePointers == 1 && dur < TAP_MS &&
                    hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) < TAP_SLOP) {
                    val ts = ev.eventTime * 1_000_000L
                    sink.send(TouchpadFrame(0, 0, 0x01, tsNs = ts))
                    releaseExecutor.execute {
                        try { Thread.sleep(25) } catch (_: InterruptedException) {}
                        sink.send(TouchpadFrame(0, 0, 0x00, tsNs = ts + 25_000_000L))
                    }
                    com.allperiph.shared.util.Log.i("GestureEngine", "tap sent (dur=${dur}ms)")
                }
                pendingButtons = 0
                activePointers = 0
                dragArmed = false; dragSent = false
                twoFingerDownTime = 0L
                gestureConsumed = false
                threeDownTime = 0L
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragSent) sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L))
                activePointers = 0
                dragArmed = false; dragSent = false
                gestureConsumed = false
                threeDownTime = 0L
            }
        }
    }

    private fun handleMove(ev: MotionEvent) {
        when (activePointers) {
            1 -> {
                val moved = hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble())
                // (c) 跟踪最近一次真实移动：帧间位移 <0.5px 视为手抖噪声，不刷新计时
                if (hypot((ev.x - lastX).toDouble(), (ev.y - lastY).toDouble()) >= 0.5) lastMoveT = ev.eventTime
                // (c) 长按静止自动 arm 收紧：必须「位移已超过 TAP_SLOP（不可能再是点按）」
                //     且「随后静止满 550ms」才 arm。旧条件是 moved < TAP_SLOP 静止 550ms 就
                //     arm，落笔区域轻按不动也会误进拖拽；落笔点附近的长按仍走外部
                //     armDrag()（550ms 定时器）入口，不受影响。
                if (!dragArmed && !dragSent && moved >= TAP_SLOP && ev.eventTime - lastMoveT > LONG_PRESS_MS) {
                    dragArmed = true
                    // ★ arm 这一刻必须震：手指还在屏上，不震完全不知道已锁定拖拽；
                    //   外层 onGestureFeedback 有 250ms 同类型节流，不会连震两下。
                    onFeedback?.invoke(GestureFeedback.DRAG_LOCK)
                    com.allperiph.shared.util.Log.i("GestureEngine", "auto arm (moved=$moved, static > 550ms)")
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
                // P0-1/P1-4：亚像素累加 + 每帧钳位 —— 原来 dx.toInt() 直接截断，<1px 的
                // 移动全部丢弃且 lastX 无条件前进；且快甩一帧 >127 塞进单个有符号字节
                // 会回绕成反向位移。现在整数部分钳 ±127，余数（含超限部分）留到下一帧。
                fracX += dx; fracY += dy
                val idx = fracX.toInt().coerceIn(-127, 127)
                val idy = fracY.toInt().coerceIn(-127, 127)
                fracX -= idx; fracY -= idy
                val btn = if (dragSent) (pendingButtons or 0x01) else pendingButtons
                sink.send(TouchpadFrame(idx, idy, btn, tsNs = ev.eventTime * 1_000_000L))
            }
            2 -> {
                val d = fingerDist(ev)
                if (twoFingerBaseDist > 0f) {
                    val scale = d / twoFingerBaseDist
                    if (scale > 1.25f || scale < 0.80f) {
                        val zoomBit = if (scale > 1f) 0x0080 else 0x0100
                        sink.send(TouchpadFrame(0, 0, 0, consumer = zoomBit, tsNs = ev.eventTime * 1_000_000L))
                        sink.send(TouchpadFrame(0, 0, 0, tsNs = ev.eventTime * 1_000_000L))
                        // 缩放是离散的"咔哒"（每跨一次阈值发一帧），逐次震是对的
                        onFeedback?.invoke(GestureFeedback.ZOOM)
                        // 跨阈后重建基线：否则此后每帧都跨阈早退，双指滚动彻底失效
                        twoFingerBaseDist = d
                        scrollLocked = true          // 缩放也算已消费：长按右键不再生效
                        lastX = ev.x; lastY = ev.y
                        return
                    }
                }
                val (cx, cy) = centroid(ev)
                val dx = cx - lastX
                val dy = cy - lastY
                lastX = cx; lastY = cy
                // 双指累计位移：滚动 vs 静止长按的唯一判据（issue #52 第 4 条）
                twoFingerMovedPx += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                // wheel/pan 亚像素累加（与单指 fracX/fracY 同款）：高刷屏每帧质心增量常 <1px，
                // 直接 toInt() 截断会丢帧 —— 慢速滚动在引擎里「没有发生过」。
                scrollFracY += dy * scrollStep
                scrollFracX += dx * scrollStep
                if (twoFingerMovedPx < TWO_FINGER_SCROLL_SLOP) return
                scrollLocked = true
                // P0-1：wheel/pan 同样是单字节有符号字段，快滑也要钳 ±127 防回绕
                val w = (-scrollFracY.toInt()).coerceIn(-127, 127)
                val p = (scrollFracX.toInt()).coerceIn(-127, 127)
                scrollFracY += w; scrollFracX -= p
                // ★ 双指滑动进入滚动这一刻震一下：区分"移动光标"和"滚动页面"。
                //   之后每帧都不震 —— 那会变成持续嗡嗡声。抬起再落下是新的一轮，会再震。
                if (!scrollEntered) {
                    scrollEntered = true
                    onFeedback?.invoke(GestureFeedback.SCROLL_ENTER)
                }
                kickInertia(w, p, System.currentTimeMillis())
                sink.send(TouchpadFrame(0, 0, pendingButtons, wheel = w, pan = p, tsNs = ev.eventTime * 1_000_000L))
            }
            3 -> {
                // 三指阶梯判定（反馈#8：旧实现 moved≥12px 一律进中键，三指滑动全被吃掉）：
                //   ≥ SWIPE_MIN      → 三指滑动（单次，转译组合键；Windows 鼠标链路看不见手指）
                //   [TAP_SLOP, 60)   → 中键按住（原行为保留，中键拖拽仍可用）
                //   < TAP_SLOP       → 静止三指，留给「三指点按」由 3→2 抬起沿补发
                if (threeDownTime > 0L) {
                    val (cx, cy) = centroid(ev)
                    val dx3 = cx - threeDownX
                    val dy3 = cy - threeDownY
                    val dist = hypot(dx3.toDouble(), dy3.toDouble())
                    if (dist >= SWIPE_MIN) {
                        if (pendingButtons and 0x04 != 0) {
                            pendingButtons = pendingButtons and 0x04.inv()
                            sink.send(TouchpadFrame(0, 0, pendingButtons, tsNs = ev.eventTime * 1_000_000L))
                        }
                        val dir = if (Math.abs(dx3) >= Math.abs(dy3)) {
                            if (dx3 > 0f) 3 else 2
                        } else {
                            if (dy3 > 0f) 1 else 0
                        }
                        onThreeSwipe?.invoke(dir)
                        onFeedback?.invoke(GestureFeedback.THREE_SWIPE)
                        gestureConsumed = true
                        threeDownTime = 0L
                        com.allperiph.shared.util.Log.i("GestureEngine", "3-finger swipe dir=$dir")
                    } else if (dist >= TAP_SLOP && pendingButtons and 0x04 == 0 && !gestureConsumed) {
                        pendingButtons = pendingButtons or 0x04
                        sink.send(TouchpadFrame(0, 0, pendingButtons, tsNs = ev.eventTime * 1_000_000L))
                        onFeedback?.invoke(GestureFeedback.MIDDLE)
                        gestureConsumed = true
                    }
                }
            }
        }
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
        /** 双指滚动死区（px）：小于它视为手抖不发帧；超过它锁定滚动、禁用长按右键。
            取 8：必须 ≤ TAP_SLOP(12)，否则 8–12px 区间既不成滚动又落进右键点按窗；
            又明显高于手抖噪声。 */
        private const val TWO_FINGER_SCROLL_SLOP = 8f
        private const val TAP_SLOP = 12f
        /** 三指滑动起步阈值：与点按(12)/中键拖拽拉开档次，60px 主轴明确才触发 */
        private const val SWIPE_MIN = 60f
        private const val TAP_MS = 350L
        private const val LONG_PRESS_MS = 550L
    }
}