package com.allperiph.ui.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.Dp
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
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment

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
    /** 切到底栏某一页（0 状态 / 1 触控板 / 2 键盘 / 3 设置）。首页的「编辑快捷键」走它。 */
    onNavigate: (Int) -> Unit = {},
    running: Boolean,
    onToggleRunning: (Boolean) -> Unit,
    topBar: Dp = 0.dp,
    bottomGap: Dp = 0.dp,
) {
    // topBar/bottomGap：主壳顶栏（64dp 玻璃叠层）与浮动底栏的让位空档。
    // 由页面在滚动内容首尾自留，而不是主壳容器硬垫 —— 滚动时内容从栏下穿过。

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
                top = 8.dp + topBar,
                bottom = maxOf(bottomGap, 96.dp) + insets.bottom,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // —— 总开关 ——
        ApComponents.Section(title = ctx.getString(R.string.ui_home_section_state)) {
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
                        else ctx.getString(R.string.ui_home_btn_start),
                        fontSize = 16.sp,
                    )
                }
            }
        }

        // —— 快捷入口（审查条 1.6）——
        // 旧触控板页（`pageTouchpad`）有一排「发送文件 / 发送剪贴板 / 副屏 / 手柄 / 遥控器 /
        // 编辑快捷键」六个入口，迁 Compose 主壳时一个都没留在原位：发送文件退到设置两层、
        // 手柄只剩设置一处、遥控器只活在旧控制台的悬浮球路径、剪贴板手动发送连路径都没有。
        // 这里按新壳的信息架构放回「首页一排、一眼可达」的位置，六个各一枚：
        // 前三枚是动作（文件 / 剪贴板 / 副屏），后三枚是入口（手柄 / 遥控器 / 编辑快捷键）。
        // 不放进触控板页是刻意的：那一页的手势面要全屏（v223.23 真机反馈「可操作区域太小」），
        // 再叠一排按钮就等于把刚修好的问题还回去。
        ApComponents.Section(title = ctx.getString(R.string.ui_home_sec_quick)) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                val quick: List<Pair<Int, () -> Unit>> = listOf(
                    R.string.ui_home_quick_file to {
                        ctx.startActivity(
                            Intent(ctx, com.allperiph.localsend.SendActivity::class.java),
                        )
                    },
                    R.string.ui_home_quick_clip to {
                        val r = com.allperiph.ui.ClipboardSync.sendNow(ctx)
                        val msg = when (r) {
                            com.allperiph.ui.ClipboardSync.SendResult.NO_DEVICE ->
                                R.string.ui_main_toast_device_not
                            com.allperiph.ui.ClipboardSync.SendResult.EMPTY ->
                                R.string.ui_touchpad_toast_clipboard_empty
                            com.allperiph.ui.ClipboardSync.SendResult.OK ->
                                R.string.ui_touchpad_text_clipboard_send_done
                            com.allperiph.ui.ClipboardSync.SendResult.FAIL ->
                                R.string.ui_touchpad_text_send_failed
                        }
                        Toast.makeText(ctx, ctx.getString(msg), Toast.LENGTH_SHORT).show()
                    },
                    R.string.ui_home_quick_screen to {
                        ctx.startActivity(
                            Intent(ctx, com.allperiph.screen.ScreenActivity::class.java),
                        )
                    },
                    R.string.ui_ball_fn_gamepad to {
                        ctx.startActivity(Intent(ctx, com.allperiph.ui.GamepadActivity::class.java))
                    },
                    R.string.ui_remote_text_remote to {
                        ctx.startActivity(Intent(ctx, com.allperiph.ui.RemoteActivity::class.java))
                    },
                    R.string.ui_hotkey_title_edit to { onNavigate(2) },
                )
                quick.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { (label, action) ->
                            ApComponents.NarrowButton(
                                text = ctx.getString(label),
                                onClick = action,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 补空格：三列网格形状稳定
                        repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
                Text(
                    text = ctx.getString(R.string.ui_home_quick_hint),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp, start = 8.dp),
                )
            }
        }

        // —— 传输通道 ——
        ApComponents.Section(title = ctx.getString(R.string.ui_home_section_transport)) {
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
        ApComponents.Section(title = ctx.getString(R.string.ui_home_section_modules)) {
            Column {
                // 成宇 2026-11-02「第一页的开关，开了，为什么以前的子开关没了」：
                // 旧布局 item_module_row.xml 每行是「名称 + 副标题 + 状态点 + Switch」，
                // 迁 Compose 时只留下状态 Pill，每模块的启停开关整个丢了。
                // 按旧布局补回：开关直接读写 AgentController.isEnabled / setModuleEnabled，
                // 与「传输通道」段的开关同一份状态，不另造状态。
                state.modules.forEach { row ->
                    ApComponents.ItemRow(
                        title = row.label,
                        summary = row.detail,
                        showDivider = true,
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ApComponents.Pill(text = row.stateLabel, color = stateColor(row.state))
                                Switch(
                                    checked = row.enabled,
                                    onCheckedChange = { on ->
                                        Feedback.haptic(ctx)
                                        AgentController.setModuleEnabled(ctx, row.id, on)
                                        tick++
                                    },
                                )
                            }
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
                    title = ctx.getString(R.string.ui_home_label_env),
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

        // —— 帮助与说明（迁移到 Compose 时这块入口整个丢了：strings 里 ui_help_* 全套文案
        //    仍在，代码里一处引用都没有。主页在这里补回「三步上手」摘要 + 完整说明入口）——
        var helpOpen by remember { mutableStateOf(false) }
        ApComponents.Section(title = ctx.getString(R.string.ui_main_cat_help)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = ctx.getString(R.string.ui_help_title_howto), fontSize = 15.sp)
                Text(
                    text = ctx.getString(R.string.ui_help_howto),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = ctx.getString(R.string.ui_main_cat_help_hint),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(
                    onClick = {
                        Feedback.haptic(ctx)
                        helpOpen = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(text = ctx.getString(R.string.ui_help_open_full), fontSize = 15.sp)
                }
            }
        }
        if (helpOpen) ApHelpSheet(onDismiss = { helpOpen = false })
    }
}

// ————————————————————— 快照 —————————————————————

private class ModuleRowVm(
    val id: String,
    val label: String,
    val detail: String,
    val state: ModuleState,
    /** 该模块当前是否启用（可信源是 AgentController.isEnabled，这里只是快照） */
    val enabled: Boolean = true,
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
    // 审查条 1.8：此前按 runtime 是否存在过滤（`module(id) ?: return@mapNotNull null`），
    // 而 runtime 只在用户点「开始」/前台服务启动时才 build —— 冷启动进状态页整段「模块状态」
    // 是空卡片，也没法先关掉某个模块再启动。旧 StatusFragment 用静态列表渲染、不依赖 runtime，
    // 这里回到同一口径：清单以 ORDER 为准，模块实例只是可选的状态来源。
    val modules = AgentController.ORDER.map { id ->
        val m = AgentController.module(id)
        ModuleRowVm(
            id = id,
            label = AgentController.label(id),
            detail = if (m == null) "未启动（点上方开始）" else AgentController.detailOf(m),
            state = m?.state ?: ModuleState.IDLE,
            enabled = AgentController.isEnabled(ctx, id),
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
