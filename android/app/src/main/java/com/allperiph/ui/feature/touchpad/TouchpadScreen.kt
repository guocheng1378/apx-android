package com.allperiph.ui.feature.touchpad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.touchpad.TouchpadModule
import com.allperiph.ui.AgentController
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 触控板页（Compose 重构线）—— 旧 `TouchpadFragment` + `activity_main.xml` 里的
 * pageTouchpad 那一块的 Compose 版。
 *
 * ## 新的只有界面，手势识别一点没重写
 * 指针事件经 [touchpadGestures] 还原成 MotionEvent，喂给既有的
 * `TouchpadModule.feed()`（内部是 `GestureEngine`：单指移动、双指滚动、双指右键、
 * 三指中键、长按拖拽、惯性滚动）。眼下的光标/涟漪是新画的，跟手参数仍走引擎。
 *
 * ## 光标为什么这样画
 * 旧版 `TouchCursorView` 是「液态光斑」：按下放大、拖动时拖一层淡淡的光晕。
 * Compose 版用同一套观感但更省：一个随指针走的实心圆 + 按下时的外圈光晕，
 * 位置直接取当前指针，不做插值——插值会让「跟手」变成「追手」，触控板最忌这个。
 */
@Composable
fun TouchpadScreen(modifier: Modifier = Modifier) {
    val cs = MiuixTheme.colorScheme
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val module = remember { AgentController.module(ModuleId.TOUCHPAD) as? TouchpadModule }
    val state = remember { mutableStateOf(module?.state ?: ModuleState.IDLE) }

    LaunchedEffect(Unit) {
        while (true) {
            state.value = module?.state ?: ModuleState.IDLE
            kotlinx.coroutines.delay(500)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // —— 顶部状态条：当前模块状态 + 一句提示 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(stateColor(state.value), CircleShape),
            )
            Text(text = stateLabel(state.value), fontSize = 13.sp)
            Text(text = stringResource(R.string.lay_status_text_touchpad_cursor_swipe), fontSize = 12.sp)
        }

        // —— 手势面 ——
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .touchpadGestures(
                        onGesture = { ev -> module?.feed(ev) },
                        onPointerDown = { pts ->
                            cursor = pts.firstOrNull()
                            pressed = true
                        },
                        onPointerMove = { pts ->
                            cursor = pts.firstOrNull()
                        },
                        onPointerUp = {
                            pressed = false
                            cursor = null
                        },
                    ),
            ) {
                val c = cursor
                if (c != null) {
                    // 光晕：按下时给一圈半透明的外圈
                    if (pressed) {
                        drawCircle(
                            color = cs.primary.copy(alpha = 0.18f),
                            radius = 46.dp.toPx(),
                            center = c,
                        )
                    }
                    drawCircle(color = cs.primary, radius = 14.dp.toPx(), center = c)
                    drawCircle(
                        color = cs.primary.copy(alpha = 0.55f),
                        radius = 22.dp.toPx(),
                        center = c,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
private fun stateColor(state: ModuleState): Color = when (state) {
    ModuleState.RUNNING -> colorResource(R.color.state_ok)
    ModuleState.DEGRADED -> colorResource(R.color.state_warn)
    ModuleState.ERROR -> colorResource(R.color.state_error)
    else -> colorResource(R.color.state_idle)
}

@Composable
private fun stateLabel(state: ModuleState): String = when (state) {
    ModuleState.RUNNING -> stringResource(R.string.common_on)
    ModuleState.DEGRADED -> stringResource(R.string.common_warn)
    ModuleState.ERROR -> stringResource(R.string.common_error)
    ModuleState.STARTING -> stringResource(R.string.status_state_starting)
    ModuleState.STOPPING -> stringResource(R.string.status_state_stopping)
    ModuleState.STOPPED, ModuleState.IDLE -> stringResource(R.string.status_state_idle)
}
