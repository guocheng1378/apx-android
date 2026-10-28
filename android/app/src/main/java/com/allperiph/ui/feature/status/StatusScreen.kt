package com.allperiph.ui.feature.status

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text

/**
 * 状态页（Compose 重构线）—— 旧 [com.allperiph.ui.StatusFragment] 的 Compose 版。
 *
 * ## 这一页显示什么（与旧页一一对应，没有删功能）
 *  · 总状态（运行中 / 降级 / 出错…）+ 环境摘要一行；
 *  · 链路诊断五行：Root 授权 / USB 连接速度 / USB 外设状态 / 后台运行 / 省电白名单；
 *  · 三个传输总开关（无线 / 蓝牙 / 有线）；
 *  · 各类下面的模块行（名称 + 状态 + 开关），双属模块的开关是「退出这一类」；
 *  · 音频方向子开关（音箱 / 麦克风）。
 *
 * ## 数据与动作
 * 全部走 [StatusProvider]（单例数据源），不依赖任何 Activity 回调 —— 换宿主不用改这页。
 */
@Composable
fun StatusScreen(
    context: Context,
    modifier: Modifier = Modifier,
    provider: StatusProvider = remember(context) { StatusProvider(context) },
) {
    DisposableEffect(provider) {
        provider.start()
        onDispose { provider.stop() }
    }
    val snap by provider.snapshot

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OverallCard(snap)
        if (!snap.anyTransportOn) {
            Text(text = stringResource(R.string.hint_idle), fontSize = 12.sp)
        }
        LinkCard(snap)
        TransportCards(snap, provider)
    }
}

/** 总状态卡：状态字 + 环境摘要 */
@Composable
private fun OverallCard(snap: StatusSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = overallLabel(snap.overall),
                fontSize = 20.sp,
                color = stateColor(snap.overall),
            )
            if (snap.envSummary.isNotEmpty()) {
                Text(text = snap.envSummary, fontSize = 12.sp)
            }
        }
    }
}

/** 链路诊断卡：五行「标签 · 状态点 · 值」 */
@Composable
private fun LinkCard(snap: StatusSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            SmallTitle(text = stringResource(R.string.card_link))
            HorizontalDivider()
            StatusRow(
                label = stringResource(R.string.label_root),
                value = if (snap.rooted == true) {
                    stringResource(R.string.value_root_ok)
                } else {
                    stringResource(R.string.value_root_missing)
                },
                color = if (snap.rooted == true) okColor() else errorColor(),
            )
            StatusRow(
                label = stringResource(R.string.label_usb_speed),
                value = if (snap.rawSpeed.isBlank()) {
                    snap.linkSpeed.label
                } else {
                    "${snap.rawSpeed}（${snap.linkSpeed.label}）"
                },
                color = if (snap.linkSpeed.isSuperSpeed) okColor() else warnColor(),
            )
            StatusRow(
                label = stringResource(R.string.label_usb_gadget),
                value = snap.udc ?: stringResource(R.string.common_unknown),
                color = if (!snap.udc.isNullOrBlank()) okColor() else idleColor(),
            )
            StatusRow(
                label = stringResource(R.string.label_service),
                value = if (snap.serviceRunning) {
                    stringResource(R.string.common_on)
                } else {
                    stringResource(R.string.common_off)
                },
                color = if (snap.serviceRunning) okColor() else idleColor(),
            )
            StatusRow(
                label = stringResource(R.string.label_battery),
                value = if (snap.batteryOptimized == false) {
                    stringResource(R.string.value_battery_ok)
                } else {
                    stringResource(R.string.value_battery_limited)
                },
                color = if (snap.batteryOptimized == false) okColor() else warnColor(),
            )
        }
    }
}

/** 三个传输分类：每类一张卡 —— 总开关 + 该类下的模块行 */
@Composable
private fun TransportCards(snap: StatusSnapshot, provider: StatusProvider) {
    for (kind in TransportKind.entries) {
        val t = snap.transports.firstOrNull { it.kind == kind } ?: continue
        val mods = snap.modules.filter { kind.key in it.groups }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(transportLabelRes(kind)),
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = t.enabled,
                        onCheckedChange = { provider.setTransport(kind, it) },
                    )
                }
                if (t.enabled && mods.isNotEmpty()) {
                    HorizontalDivider()
                    for (m in mods) {
                        ModuleRowView(m, kind, provider)
                    }
                }
            }
        }
    }
}

/** 模块行：名称 + 状态点 + 详情 + 开关（双属模块的开关是「退出这一类」） */
@Composable
private fun ModuleRowView(m: ModuleRow, kind: TransportKind, provider: StatusProvider) {
    val multi = m.groups.size > 1
    val checked = if (multi) m.exitEnabled[kind.key] == true else m.enabled
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(stateColor(m.state), shape = androidx.compose.foundation.shape.CircleShape),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(text = m.label, fontSize = 14.sp)
                Text(text = m.detail, fontSize = 11.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = { on ->
                    if (multi) {
                        provider.setModuleExitEnabled(m.id, kind.key, on)
                    } else {
                        provider.setModuleEnabled(m.id, on)
                    }
                },
            )
        }
        // 音频方向子开关：Wi-Fi 音频在无线类，USB 音频（UAC2）有线类
        if (m.id == ModuleId.WIFI_AUDIO) {
            AudioSubRows(provider, wireless = true)
        }
        if (m.id == ModuleId.AUDIO) {
            AudioSubRows(provider, wireless = false)
        }
    }
}

/** 音频方向子开关（音箱 / 麦克风） */
@Composable
private fun AudioSubRows(provider: StatusProvider, wireless: Boolean) {
    Column(modifier = Modifier.padding(start = 26.dp, end = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ui_main_label_speaker),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = provider.speakerOn(wireless),
                onCheckedChange = { provider.setSpeaker(it, wireless) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ui_main_label_mic),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = provider.micOn(wireless),
                onCheckedChange = { provider.setMic(it, wireless) },
            )
        }
    }
}

/** 一行「标签 · 状态点 · 值」 */
@Composable
private fun StatusRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = color,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// ==================== 颜色与文案 ====================

@Composable
private fun okColor(): Color = colorResource(R.color.state_ok)
@Composable
private fun warnColor(): Color = colorResource(R.color.state_warn)
@Composable
private fun errorColor(): Color = colorResource(R.color.state_error)
@Composable
private fun idleColor(): Color = colorResource(R.color.state_idle)

/** 模块状态 → 语义色（与旧 StatusFragment.stateColor 同一张表） */
@Composable
private fun stateColor(state: ModuleState): Color = when (state) {
    ModuleState.RUNNING -> okColor()
    ModuleState.DEGRADED, ModuleState.STARTING, ModuleState.STOPPING -> warnColor()
    ModuleState.ERROR -> errorColor()
    else -> idleColor()
}

/** 模块状态 → 文案（与旧 StatusFragment.stateLabel 同一张表） */
@Composable
private fun overallLabel(state: ModuleState): String = when (state) {
    ModuleState.RUNNING -> stringResource(R.string.common_on)
    ModuleState.DEGRADED -> stringResource(R.string.common_warn)
    ModuleState.STARTING -> stringResource(R.string.status_state_starting)
    ModuleState.ERROR -> stringResource(R.string.common_error)
    ModuleState.STOPPING -> stringResource(R.string.status_state_stopping)
    ModuleState.STOPPED -> stringResource(R.string.common_off)
    ModuleState.IDLE -> stringResource(R.string.status_state_idle)
}

/** 传输分类标题 */
private fun transportLabelRes(kind: TransportKind): Int = when (kind) {
    TransportKind.WIFI -> R.string.lay_transport_wifi
    TransportKind.BT -> R.string.lay_transport_bt
    TransportKind.USB -> R.string.lay_transport_usb_group
}
