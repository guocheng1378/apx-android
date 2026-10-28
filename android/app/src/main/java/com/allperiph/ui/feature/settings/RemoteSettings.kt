package com.allperiph.ui.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.allperiph.R
import com.allperiph.touchpad.TouchpadActivity
import com.allperiph.ui.ConnectHistory
import com.allperiph.ui.ControlConnect
import com.allperiph.ui.FloatingBallService
import com.allperiph.ui.RemoteActivity
import com.allperiph.wireless.ControlTarget
import com.allperiph.wireless.TvDiscovery
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlinx.coroutines.delay

/**
 * 设置页 · **遥控**分类（Compose 重构线）。
 *
 * 三组：连接（选设备 / 断开）→ 工具（遥控面板 / 全屏控制台 / 日志 / 悬浮球）→ 最近设备。
 * 选设备与连入走 [TvDiscovery] + [ControlConnect]（与旧实现同一份连入逻辑），
 * 因此「换设备必须先断开旧实例」这类坑只有一处代码。
 */
@Composable
fun RemoteSettings(context: Context, modifier: Modifier = Modifier) {
    var revision by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    val controlling = ControlTarget.isControlling()
    val target = ControlTarget.label
    val recent = remember(revision) { ConnectHistory.getRecent(context, 3) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // —— 连接 ——
        SettingsGroup(R.string.lay_settings_group_connect) {
            ArrowPreference(
                title = stringResource(R.string.ui_main_label_device_connect),
                summary = if (controlling) (target ?: stringResource(R.string.common_unknown)) else "",
                onClick = { showPicker = true },
            )
            if (controlling) {
                ArrowPreference(
                    title = stringResource(R.string.ui_main_label_connect_disconnect),
                    summary = stringResource(R.string.ui_main_btn_to_local_local),
                    onClick = {
                        ControlTarget.clear()
                        revision++
                    },
                )
            }
        }

        // —— 工具 ——
        SettingsGroup(R.string.lay_settings_group_tools) {
            ArrowPreference(
                title = stringResource(R.string.ui_main_label_remote_power_volume),
                summary = stringResource(R.string.ui_main_hint_remote_keys),
                onClick = {
                    context.startActivity(
                        Intent(context, RemoteActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
            ArrowPreference(
                title = stringResource(R.string.ui_main_label_screen_touchpad_keyboard_fullscreen),
                summary = stringResource(R.string.ui_main_hint_console),
                onClick = {
                    context.startActivity(
                        Intent(context, TouchpadActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
            ArrowPreference(
                title = stringResource(R.string.ui_main_label_log_view),
                onClick = { showLog = true },
            )
            ArrowPreference(
                title = stringResource(R.string.ui_main_label_floating_ball),
                summary = stringResource(
                    if (FloatingBallService.isRunning) R.string.ui_main_floating_ball_on
                    else R.string.ui_main_floating_ball_off
                ),
                onClick = {
                    FloatingBallService.requestToggle(context)
                    revision++
                },
            )
        }

        // —— 最近设备 ——
        SettingsGroup(R.string.ui_main_label_recent_devices) {
            if (recent.isEmpty()) {
                InfoRow(stringResource(R.string.ui_main_text_no_recent), small = true)
            } else {
                for (item in recent) {
                    ArrowPreference(
                        title = item.name,
                        summary = "${item.host}:${item.port}",
                        onClick = {
                            ControlConnect.connect(context, item.host, item.port, item.name, "tv") {
                                revision++
                            }
                        },
                    )
                }
            }
        }
    }

    // —— 设备选择：本机 / 已发现 / 手动输入 ——
    if (showPicker) {
        var devices by remember { mutableStateOf(TvDiscovery.list()) }
        LaunchedEffect(Unit) {
            TvDiscovery.start()
            // 信标是周期广播的，弹窗打开瞬间往往还没收到 —— 打开期间每秒刷新
            while (true) {
                delay(1_000)
                devices = TvDiscovery.list()
            }
        }
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_main_dialog_device_pick),
            onDismissRequest = { showPicker = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                PickerRow(stringResource(R.string.ui_main_item_local)) {
                    ControlTarget.clear()
                    showPicker = false
                    revision++
                }
                for (d in devices) {
                    PickerRow("${d.name}  ${d.ip}  ${d.typeLabel}") {
                        ControlConnect.connect(context, d.ip, d.port, d.name, d.type) {
                            showPicker = false
                            revision++
                        }
                    }
                }
                if (devices.isEmpty()) {
                    InfoRow(stringResource(R.string.ui_main_item_device), small = true)
                }
                PickerRow(stringResource(R.string.ui_main_item_input_manual)) {
                    showPicker = false
                    showManual = true
                }
            }
        }
    }

    // —— 手动输入 IP ——
    if (showManual) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_main_item_input_manual),
            onDismissRequest = { showManual = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val cs = MiuixTheme.colorScheme
                BasicTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        color = cs.onSurface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cs.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.ui_main_label_device_connect),
                        onClick = {
                            val ip = manualIp.trim()
                            if (ip.isNotEmpty()) {
                                // 端口用协议默认（TV / PC 都跑同一个端口）
                                ControlConnect.connect(context, ip, com.allperiph.wireless.TvControllerClient.PORT, ip, "tv") {
                                    revision++
                                }
                            }
                            showManual = false
                        },
                    )
                    TextButton(text = stringResource(R.string.ui_common_close), onClick = { showManual = false })
                }
            }
        }
    }

    if (showLog) LogDialog(context = context, onDismiss = { showLog = false })
}

/** 设备选择里的一行 */
@Composable
private fun PickerRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
