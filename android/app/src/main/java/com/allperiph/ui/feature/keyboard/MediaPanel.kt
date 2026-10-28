package com.allperiph.ui.feature.keyboard

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.HotkeyController
import com.allperiph.wireless.ControlTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 遥控面板（Compose 重构线）—— 旧 `KeyboardPanels.remoteKeyboard()` 的 Compose 版。
 *
 * ## 两条不同的按键语义，这里都保住了
 *  · **媒体/电源**（上一曲、播放暂停、下一曲、静音、电源）：按一下 = `press` 后 60ms `release`。
 *    对端是按**位图边沿**计数的，必须真的产生一次按下→释放，只发 press 不算数。
 *  · **音量 ±**：按住**连发**（首下 400ms 后每 250ms 一次），松手兜底 `release`。
 *    调音量要「一直按到大为止」，单发的实现得点二十次。
 *
 * ## 关机 / 重启为什么要确认
 * 这两个不是 Consumer 控制码，而是真电源动作（控制帧 0x22）；被控端没有 root 时会退回
 * 软电源键并如实说明 —— 也就是说它**可能真的关掉那台机器**。所以必弹确认，
 * 文案沿用旧的 `ui_keyboard_power_*`，不重写。
 */
@Composable
fun MediaPanel(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cs = MiuixTheme.colorScheme
    var powerAsk by remember { mutableStateOf<Int?>(null) }   // 0 关机 / 1 重启

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 第一行：曲目
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            MediaTile(R.drawable.ic_apx_prev, stringResource(R.string.ui_keyboard_media_prev)) {
                tapMedia(HotkeyController.BIT_PREV_TRACK)
            }
            MediaTile(R.drawable.ic_apx_play, stringResource(R.string.ui_keyboard_media_play)) {
                tapMedia(HotkeyController.BIT_PLAY_PAUSE)
            }
            MediaTile(R.drawable.ic_apx_next, stringResource(R.string.ui_keyboard_media_next)) {
                tapMedia(HotkeyController.BIT_NEXT_TRACK)
            }
        }
        // 第二行：音量（音量键按住连发）+ 静音
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            MediaTile(
                icon = R.drawable.ic_apx_vol_down,
                label = stringResource(R.string.ui_keyboard_media_vol_down),
                repeat = HotkeyController.BIT_VOLUME_DOWN,
            )
            MediaTile(icon = R.drawable.ic_apx_mute, label = stringResource(R.string.ui_keyboard_media_mute)) {
                tapMedia(HotkeyController.BIT_MUTE)
            }
            MediaTile(
                icon = R.drawable.ic_apx_vol_up,
                label = stringResource(R.string.ui_keyboard_media_vol_up),
                repeat = HotkeyController.BIT_VOLUME_UP,
            )
        }
        // 第三行：电源（软）/ 关机 / 重启
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            MediaTile(icon = R.drawable.ic_apx_power, label = stringResource(R.string.ui_keyboard_media_power)) {
                tapMedia(HotkeyController.BIT_POWER)
            }
            ActionTile(stringResource(R.string.ui_keyboard_power_off), cs.error) { powerAsk = 0 }
            ActionTile(stringResource(R.string.ui_keyboard_power_reboot), cs.primary) { powerAsk = 1 }
        }
    }

    val ask = powerAsk
    if (ask != null) {
        val label = stringResource(
            if (ask == 0) R.string.ui_keyboard_power_off else R.string.ui_keyboard_power_reboot
        )
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_keyboard_power_title, label),
            onDismissRequest = { powerAsk = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.ui_keyboard_power_msg, label),
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(text = label, onClick = {
                        val client = ControlTarget.controlClient
                        if (client == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.ui_keyboard_no_device),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            client.power(ask)
                            Toast.makeText(
                                context,
                                context.getString(R.string.ui_keyboard_power_sent, label),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        powerAsk = null
                    })
                    TextButton(text = stringResource(R.string.ui_common_cancel), onClick = { powerAsk = null })
                }
            }
        }
    }
}

/** 单发媒体键：press → 60ms → release（对端按位图边沿计数，必须有下落沿） */
private fun tapMedia(bit: Int) {
    HotkeyController.press(bit)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        { HotkeyController.release(bit) }, 60L,
    )
}

/** 媒体图块：[repeat] 非空表示按住连发（音量键） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.MediaTile(
    icon: Int,
    label: String,
    repeat: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MiuixTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val pressModifier = if (repeat == null) {
        Modifier.clickable { onClick?.invoke() }
    } else {
        val scope = rememberCoroutineScope()
        Modifier.pointerInput(repeat) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                // 首下立即给一次（对端要看到按下→释放的边沿）
                scope.launch {
                    HotkeyController.press(repeat)
                    delay(60)
                    HotkeyController.release(repeat)
                }
                // 按住不放：每 190ms 再补一次，抬手立即停
                var done = false
                while (!done) {
                    done = withTimeoutOrNull(190L) { waitForUpOrCancellation() } != null
                    if (!done) {
                        scope.launch {
                            HotkeyController.press(repeat)
                            delay(60)
                            HotkeyController.release(repeat)
                        }
                    }
                }
                scope.launch { HotkeyController.release(repeat) }   // 兜底
                pressed = false
            }
        }
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) cs.primary.copy(alpha = 0.22f) else cs.surfaceVariant)
            .then(pressModifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(cs.primary),
                modifier = Modifier.size(28.dp),
            )
            Text(text = label, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** 动作图块：没有 Consumer 位，点了直接走确认流程（关机 / 重启） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionTile(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 14.sp, color = accent)
    }
}
