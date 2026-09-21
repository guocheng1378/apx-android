package com.allperiph.ui

import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.allperiph.hid.HidKeys
import kotlin.math.hypot

/**
 * 快捷键块的手势（v1.30）：轻点发送 / 长按按住 / **长按后拖动排序**。
 *
 * 手势分工（靠"有没有移动"区分，互不抢占）：
 * · 轻点            → [HidKeys.combo] 发一次（按下 60ms 后自动释放）
 * · 长按不动 350ms  → [HidKeys.hold] **按住不放**，抬起才释放
 * · 长按后拖动      → 转入**排序**：块跟手移动，松手落位（回调 [onReorder]）
 *   —— 一旦开始拖动就先放掉"按住"，避免边按住边排序
 *
 * 编辑入口不在这里：已挪到"常用快捷键"标题行的「编辑」按钮（弹出管理列表）。
 */
object ComboChip {

    /** 超过这个时长就进入"按住不放" */
    const val HOLD_MS = 350L

    /** 位移超过这个距离（dp）判定为"要拖动"而不是"按住" */
    private const val DRAG_SLOP_DP = 14f

    /**
     * @param onSent    发送成功回调（用于累计使用次数 / 自动排序）
     * @param onReorder 拖动落位回调：`from` 为原下标，`dropCenterX` 为松手时该块在屏幕上的中心 X
     */
    fun attach(
        v: View,
        c: HidKeys.Combo,
        onSent: (() -> Unit)? = null,
        onReorder: ((from: Int, dropCenterX: Float) -> Unit)? = null,
    ) {
        val slop = DRAG_SLOP_DP * v.resources.displayMetrics.density
        val from = (v.tag as? Int) ?: -1
        var holding = false
        var dragging = false
        var downX = 0f
        var downY = 0f

        val holdTask = Runnable {
            holding = true
            HidKeys.hold(c)
            onSent?.invoke()
            Feedback.haptic(v) // 进入按住的第二下震动，手感上"咔"一声
        }

        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    view.animate().cancel()
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    Feedback.haptic(view)
                    view.postDelayed(holdTask, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && onReorder != null && from >= 0 &&
                        hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > slop
                    ) {
                        // 手指动了 → 是排序意图：先放掉可能已成立的"按住"
                        if (holding) {
                            HidKeys.releaseHold()
                            holding = false
                        }
                        view.removeCallbacks(holdTask)
                        dragging = true
                    }
                    if (dragging) {
                        view.translationX = e.rawX - downX
                        view.translationY = e.rawY - downY
                        view.alpha = 0.88f
                        view.scaleX = 1.06f
                        view.scaleY = 1.06f
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(holdTask)
                    view.animate().cancel()
                    if (dragging) {
                        val loc = IntArray(2)
                        view.getLocationInWindow(loc)
                        val centerX = loc[0] + view.width / 2f
                        view.translationX = 0f
                        view.translationY = 0f
                        view.alpha = 1f
                        view.scaleX = 1f
                        view.scaleY = 1f
                        if (e.actionMasked == MotionEvent.ACTION_UP) onReorder?.invoke(from, centerX)
                    } else {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(OvershootInterpolator(2.2f)).start()
                        if (holding) {
                            HidKeys.releaseHold() // 长按：松手才释放
                            holding = false
                        } else {
                            HidKeys.combo(c)      // 轻点：发一次
                            onSent?.invoke()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }
}
