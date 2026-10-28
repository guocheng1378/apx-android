package com.allperiph.ui.feature.touchpad

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Compose 手势面 → 既有手势引擎的**桥**。
 *
 * ## 为什么不是重写手势识别
 * 手势识别逻辑在 `touchpad/GestureEngine.kt`（单指移动/点按、双指滚动、双指右键、三指中键、
 * 长按拖拽、惯性滚动），它已经是纯逻辑、只吃 `MotionEvent`、产出相对增量。重写一遍既没必要，
 * 也会把「跟手」「惯性」这些调过的参数丢掉。所以在 Compose 里把指针事件**还原成 MotionEvent**
 * 喂给同一份引擎 —— 新的只有界面，手势行为与旧页一致。
 *
 * ## 还原规则（与 View 侧的事件流对齐）
 *  · 第一个指头按下 → ACTION_DOWN；后续指头按下 → ACTION_POINTER_DOWN（带索引）；
 *  · 抬起同理用 ACTION_UP / ACTION_POINTER_UP；
 *  · 其余帧 ACTION_MOVE；
 *  · 所有指针数变化都集中在同一帧时按「先抬后按」处理（引擎只关心数量与质心）。
 */
fun Modifier.touchpadGestures(
    onGesture: (MotionEvent) -> Unit,
    onPointerDown: (List<Offset>) -> Unit = {},
    onPointerMove: (List<Offset>) -> Unit = {},
    onPointerUp: () -> Unit = {},
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var downTime = 0L
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val all = event.changes
            val pressed = all.filter { it.pressed }
            val previousPressed = all.filter { it.previousPressed }
            if (pressed.isEmpty() && previousPressed.isEmpty()) continue

            val now = SystemClock.uptimeMillis()
            val newlyDown = all.filter { it.pressed && !it.previousPressed }
            val newlyUp = all.filter { !it.pressed && it.previousPressed }
            if (downTime == 0L && newlyDown.isNotEmpty()) downTime = now

            // 当前所有还按着的指针（含这一帧刚按下的）
            val pointers = pressed
            val n = pointers.size
            if (n == 0) {
                // 最后一个指头抬起：用上一次的位置发 ACTION_UP
                val upIndex = newlyUp.firstOrNull()?.let { c -> previousPressed.indexOf(c) } ?: 0
                val coords = previousPressed.map { it.position }
                sendMotionEvent(
                    onGesture, downTime, now,
                    MotionEvent.ACTION_UP, upIndex, coords,
                )
                downTime = 0L
                onPointerUp()
                continue
            }

            val action = when {
                newlyDown.isNotEmpty() && previousPressed.isEmpty() && n == 1 -> MotionEvent.ACTION_DOWN
                newlyDown.isNotEmpty() && previousPressed.isEmpty() -> MotionEvent.ACTION_DOWN
                newlyDown.isNotEmpty() ->
                    MotionEvent.ACTION_POINTER_DOWN or (newlyDown.first().let { pointers.indexOf(it) } shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                newlyUp.isNotEmpty() && n >= 1 ->
                    MotionEvent.ACTION_POINTER_UP or (newlyUp.first().let { c -> previousPressed.indexOf(c).coerceAtLeast(0) } shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                else -> MotionEvent.ACTION_MOVE
            }

            sendMotionEvent(
                onGesture, downTime, now, action, 0,
                pointers.map { it.position },
            )

            if (newlyDown.isNotEmpty()) onPointerDown(pointers.map { it.position })
            else onPointerMove(pointers.map { it.position })

            // 吃掉事件：手势面独占，避免外层滚动容器抢
            all.forEach { it.consume() }
        }
    }
}

/**
 * 用一帧的指针位置拼一个 MotionEvent 交给引擎。
 *
 * 指针 id 用 0..n-1（引擎只按顺序取质心，不关心系统 id）；[actionIndex] 只对
 * ACTION_POINTER_DOWN / UP 有意义。
 */
private fun sendMotionEvent(
    onGesture: (MotionEvent) -> Unit,
    downTime: Long,
    eventTime: Long,
    action: Int,
    actionIndex: Int,
    positions: List<Offset>,
) {
    val n = positions.size
    if (n == 0) return
    val props = Array(n) { i ->
        MotionEvent.PointerProperties().apply {
            id = i
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
    }
    val coords = Array(n) { i ->
        MotionEvent.PointerCoords().apply {
            x = positions[i].x
            y = positions[i].y
            pressure = 1f
            size = 1f
        }
    }
    val safeAction = if (actionIndex in 0 until n) action else action and MotionEvent.ACTION_POINTER_INDEX_MASK.inv()
    val ev = MotionEvent.obtain(
        downTime, eventTime, safeAction, n, props, coords,
        0, 0, 1f, 1f, 0, 0, 0, 0,
    )
    try {
        onGesture(ev)
    } finally {
        ev.recycle()
    }
}
