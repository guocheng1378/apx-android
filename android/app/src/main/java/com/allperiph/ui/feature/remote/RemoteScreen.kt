package com.allperiph.ui.feature.remote

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.ui.HotkeyController
import com.allperiph.wireless.ControlTarget
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 遥控页（Compose 重构线）—— 旧 `RemoteActivity`（573 行程序化 View）的 Compose 版。
 *
 * ## 版面顺序照旧（这是盲按顺序，不是审美）
 * 1. 目标提示：正在控制谁 / 没连上时按键会走本机 USB HID（等于自带键盘）——必须让人看见；
 * 2. **电源行在最上**：遥控器上最需要盲按到的一行，离拇指最近；
 * 3. 拨盘：方向 + 确定；
 * 4. 功能 / 音量 / 媒体三行，按使用频率排。
 *
 * ## 三套发键语义，一个都不能混
 *  · 方向 / 返回 / 主页 / 菜单 / 电源（软）：`HidKeys.tap(usage)`，走键盘报文；
 *  · 音量 / 静音 / 曲目：`HotkeyController.press` + 60ms `release`，走 Consumer 控制位图，
 *    对端按**边沿**计数；
 *  · 关机 / 重启：控制帧 0x22 的真电源动作，必须二次确认（可能真的关机）。
 */
@Composable
fun RemoteScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cs = MiuixTheme.colorScheme
    var powerAsk by remember { mutableStateOf<Int?>(null) }
    // 控制目标会在别处（状态页连/断）变化；@Volatile 字段不会触发重组，这里 1s 轮询一次
    var controlling by remember { mutableStateOf(ControlTarget.isControlling()) }
    var targetLabel by remember { mutableStateOf(ControlTarget.label) }
    LaunchedEffect(Unit) {
        while (true) {
            controlling = ControlTarget.isControlling()
            targetLabel = ControlTarget.label
            delay(1_000)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (controlling) {
                stringResource(R.string.ui_main_text_controlling, targetLabel)
            } else {
                stringResource(R.string.shell_remote_target_none)
            },
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // 电源行
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            RoundKey(
                label = stringResource(R.string.ui_touchpad_label_power),
                modifier = Modifier.weight(1f),
            ) { HidKeys.tap(U_POWER) }
            RoundKey(
                label = stringResource(R.string.ui_touchpad_label_poweroff),
                modifier = Modifier.weight(1f),
                accent = cs.error,
            ) { powerAsk = 0 }
            RoundKey(
                label = stringResource(R.string.ui_touchpad_label_reboot),
                modifier = Modifier.weight(1f),
                accent = cs.error,
            ) { powerAsk = 1 }
        }

        // 拨盘：方向 + 确定
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DialPad(
                onUp = { HidKeys.tap(U_UP) },
                onDown = { HidKeys.tap(U_DOWN) },
                onLeft = { HidKeys.tap(U_LEFT) },
                onRight = { HidKeys.tap(U_RIGHT) },
                onOk = { HidKeys.tap(U_ENTER) },
            )
        }

        SmallTitle(text = stringResource(R.string.ui_remote_item_home))
        KeyRow(
            listOf(
                stringResource(R.string.ui_common_back) to { HidKeys.tap(U_ESC) },
                stringResource(R.string.ui_remote_item_home) to { HidKeys.tap(U_HOME) },
                stringResource(R.string.ui_remote_item_menu) to { HidKeys.tap(U_MENU) },
            )
        )
        SmallTitle(text = stringResource(R.string.ui_touchpad_label_volume))
        KeyRow(
            listOf(
                stringResource(R.string.ui_touchpad_label_volume) to { media(HotkeyController.BIT_VOLUME_DOWN) },
                stringResource(R.string.ui_touchpad_media_mute) to { media(HotkeyController.BIT_MUTE) },
                stringResource(R.string.ui_remote_item_volume) to { media(HotkeyController.BIT_VOLUME_UP) },
            )
        )
        SmallTitle(text = stringResource(R.string.ui_remote_item_play))
        KeyRow(
            listOf(
                stringResource(R.string.ui_touchpad_media_prev) to { media(HotkeyController.BIT_PREV_TRACK) },
                stringResource(R.string.ui_remote_item_play) to { media(HotkeyController.BIT_PLAY_PAUSE) },
                stringResource(R.string.ui_touchpad_media_next) to { media(HotkeyController.BIT_NEXT_TRACK) },
            )
        )
    }

    val ask = powerAsk
    if (ask != null) {
        val label = stringResource(
            if (ask == 0) R.string.ui_touchpad_label_poweroff else R.string.ui_touchpad_label_reboot
        )
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_remote_dialog_device, label),
            onDismissRequest = { powerAsk = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = stringResource(R.string.ui_remote_dialog_no_only_need, label), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(text = label, onClick = {
                        val client = ControlTarget.controlClient
                        if (client == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.ui_remote_toast_device_connect_not),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            client.power(ask)
                            Toast.makeText(
                                context,
                                context.getString(R.string.ui_remote_toast_send_done, label),
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

/** 方向拨盘：十字排布 + 中间确定；圆的，拇指按住的落点比方块宽容 */
@Composable
private fun DialPad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DialKey("↑", onUp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialKey("←", onLeft)
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(CircleShape)
                        .background(cs.primary)
                        .clickable(onClick = onOk)
                        .padding(horizontal = 26.dp, vertical = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = stringResource(R.string.shell_remote_ok), fontSize = 13.sp, color = cs.onPrimary)
                }
                DialKey("→", onRight)
            }
            DialKey("↓", onDown)
        }
    }
}

@Composable
private fun DialKey(glyph: String, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(CircleShape)
            .background(cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = 16.sp)
    }
}

@Composable
private fun KeyRow(items: List<Pair<String, () -> Unit>>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        for ((label, action) in items) {
            RoundKey(label = label, modifier = Modifier.weight(1f), onClick = action)
        }
    }
}

@Composable
private fun RoundKey(
    label: String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent?.copy(alpha = 0.14f) ?: cs.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 14.sp, color = accent ?: cs.onSurface)
    }
}

/** Consumer 多媒体键：按下 + 60ms 释放（对端按边沿识别） */
private fun media(bit: Int) {
    HotkeyController.press(bit)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        { HotkeyController.release(bit) }, 60L,
    )
}

private const val U_ESC = 0x29
private const val U_HOME = 0x4A
private const val U_MENU = 0x65
private const val U_POWER = 0x66
private const val U_UP = 0x52
private const val U_DOWN = 0x51
private const val U_LEFT = 0x50
private const val U_RIGHT = 0x4F
private const val U_ENTER = 0x28
