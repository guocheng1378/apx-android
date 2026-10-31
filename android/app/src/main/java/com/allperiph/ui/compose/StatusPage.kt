package com.allperiph.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.core.LinkSpeed
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.ui.AgentController
import com.allperiph.ui.EnvChecks
import com.allperiph.ui.Feedback
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApEdgeInsets
import com.allperiph.ui.miuix.rememberApInsets
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 状态页（**第一个完成 Compose + Miuix 迁移的屏**）。
 *
 * 对照旧实现 [com.allperiph.ui.StatusFragment] + `page_status.xml`，行为对齐如下：
 *  · 大号启动/停止开关：与旧 `swAgent`/`btnStart` 同一动作（AgentController.startEnabled / stopAll）；
 *  · 三个传输开关：读写 AgentController 的 `transport_*` 偏好，改动即时热启停模块；
 *  · 模块行：`AgentController.label(id)` + `detailOf(module)` + 状态色，不再自己 findViewById 刷新；
 *  · 链路诊断：`AgentController.linkSpeed` 与 `EnvChecks.Env.summary`。
 *
 * 数据刷新走 1s 轮询（与旧 [com.allperiph.ui.MainActivity] 的 ticker 同频）：模块状态来自后台线程，
 * EventBus 只覆盖部分事件，轮询是最稳的一条。
 */
@Composable
fun StatusPage(
    ctx: Context,
    running: Boolean,
    onToggleRunning: (Boolean) -> Unit,
) {
    val insets = rememberApInsets()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            tick++
        }
    }

    // tick 只用来触发重组；实际值每次都现读（模块状态是 volatile 字段）
    val state = remember(tick, running) { snapshot(ctx) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp + insets.left,
                end = 16.dp + insets.right,
                top = 8.dp,
                bottom = 96.dp + insets.bottom,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // —— 总开关 ——
        ApComponents.Section(title = "运行状态") {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (running) state.overallLabel else ctx.getString(R.string.hint_idle),
                    fontSize = 22.sp,
                )
                Text(
                    text = state.overallDetail,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = {
                        Feedback.haptic(ctx)
                        onToggleRunning(!running)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Text(
                        text = if (running) ctx.getString(R.string.notify_action_stop)
                        else "启动",
                        fontSize = 16.sp,
                    )
                }
            }
        }

        // —— 传输通道 ——
        ApComponents.Section(title = "传输通道") {
            Column {
                AgentController.TRANSPORTS.forEach { t ->
                    ApComponents.SwitchRow(
                        title = AgentController.transportLabel(t),
                        summary = state.transportSummary[t],
                        checked = state.transportOn[t] == true,
                        onCheckedChange = { on ->
                            Feedback.haptic(ctx)
                            AgentController.setTransportEnabled(ctx, t, on)
                        },
                    )
                }
            }
        }

        // —— 模块状态 ——
        ApComponents.Section(title = "模块") {
            Column {
                state.modules.forEach { row ->
                    ApComponents.ItemRow(
                        title = row.label,
                        summary = row.detail,
                        showDivider = true,
                        trailing = {
                            ApComponents.Pill(text = row.stateLabel, color = stateColor(row.state))
                        },
                    )
                }
            }
        }

        // —— 链路诊断 ——
        ApComponents.Section(title = ctx.getString(R.string.card_link)) {
            Column {
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.label_usb_speed),
                    value = state.linkLabel,
                )
                ApComponents.ValueRow(
                    title = "环境自检",
                    value = state.envSummary,
                )
                Text(
                    text = if (state.rooted) ctx.getString(R.string.hint_usb_ok)
                    else ctx.getString(R.string.hint_usb2),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

// ————————————————————— 快照 —————————————————————

private class ModuleRowVm(
    val label: String,
    val detail: String,
    val state: ModuleState,
) {
    val stateLabel: String get() = when (state) {
        ModuleState.RUNNING -> "运行中"
        ModuleState.STARTING -> "启动中"
        ModuleState.DEGRADED -> "降级"
        ModuleState.ERROR -> "异常"
        ModuleState.STOPPING -> "停止中"
        ModuleState.STOPPED -> "已停止"
        ModuleState.IDLE -> "未启动"
    }
}

private class StatusVm(
    val overallLabel: String,
    val overallDetail: String,
    val transportOn: Map<String, Boolean>,
    val transportSummary: Map<String, String>,
    val modules: List<ModuleRowVm>,
    val linkLabel: String,
    val envSummary: String,
    val rooted: Boolean,
)

private fun stateColor(s: ModuleState): Color = when (s) {
    ModuleState.RUNNING -> Color(0xFF00A870)
    ModuleState.STARTING, ModuleState.STOPPING -> Color(0xFFFF7A00)
    ModuleState.DEGRADED -> Color(0xFFFF7A00)
    ModuleState.ERROR -> Color(0xFFE0457B)
    else -> Color(0xFF5B6470)
}

/** 现读一份界面快照（主线程，全部是内存/偏好读取，不碰 IO） */
private fun snapshot(ctx: Context): StatusVm {
    val running = AgentController.running
    val env: EnvChecks.Env? = AgentController.env
    val transportOn = AgentController.TRANSPORTS.associateWith {
        AgentController.isTransportEnabled(ctx, it)
    }
    val transportSummary = AgentController.TRANSPORTS.associateWith { t ->
        val ids = AgentController.groupModules(t)
        val on = ids.count { AgentController.isEnabled(ctx, it) }
        "$on/${ids.size} 个模块启用"
    }
    val modules = AgentController.ORDER.mapNotNull { id ->
        val m = AgentController.module(id) ?: return@mapNotNull null
        ModuleRowVm(
            label = AgentController.label(id),
            detail = AgentController.detailOf(m),
            state = m.state,
        )
    }
    val link: LinkSpeed = AgentController.linkSpeed
    return StatusVm(
        overallLabel = if (running) "外设已启动" else "未启动",
        overallDetail = if (running) {
            "${modules.count { it.state.isActive }}/${modules.size} 个模块在用"
        } else {
            "点下方按钮开始把手机当外设"
        },
        transportOn = transportOn,
        transportSummary = transportSummary,
        modules = modules,
        linkLabel = if (link == LinkSpeed.UNKNOWN) "--" else link.name,
        envSummary = env?.summary ?: "未检测",
        rooted = env?.rooted ?: false,
    )
}
