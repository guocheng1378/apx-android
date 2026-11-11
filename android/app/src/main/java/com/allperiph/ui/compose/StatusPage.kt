package com.allperiph.ui.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.bt.BtDiag
import com.allperiph.bt.BtHidDevice
import com.allperiph.core.LinkSpeed
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.ui.AgentController
import com.allperiph.ui.EnvChecks
import com.allperiph.ui.Feedback
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApEdgeInsets
import com.allperiph.ui.miuix.rememberApInsets
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import com.allperiph.ui.miuix.ApTwoPaneScaffold
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import android.widget.Toast
import com.allperiph.controlled.ControlledService
import com.allperiph.wireless.ControlTarget
import com.allperiph.core.Uplink
import androidx.compose.ui.res.colorResource

/**
 * 状态页（**第一个完成 Compose + Miuix 迁移的屏**）。
 *
 * 对照旧实现 [com.allperiph.ui.StatusFragment] + `page_status.xml`，行为对齐如下：
 *  · 三个传输开关：读写 AgentController 的 `transport_*` 偏好，**并负责对应模块的启停与
 *    前台服务**（总闸已在 v223.29.3 去掉，开关就是唯一入口，见 AgentController.applyTransportSwitch）；
 *  · 音频方向：USB / Wi-Fi 两路的麦克风 / 音响开关（v223.29.3 从设置页整块上移，「把设置里的
 *    管麦克风和音响的也挪过来」）；
 *  · 模块行：`AgentController.labelRes(id)`（资源 id，本层 `ctx.getString` 解析）+
 *    `detailOf(module)` + 状态色，不再自己 findViewById 刷新；
 *  · 链路诊断：`AgentController.linkSpeed` 与 `EnvChecks.Env.summary`。
 *
 * 数据刷新走 1s 轮询（与旧 [com.allperiph.ui.MainActivity] 的 ticker 同频）：模块状态来自后台线程，
 * EventBus 只覆盖部分事件，轮询是最稳的一条。
 *
 * 2026-11-09 界面稿 03/04 + 差集 1（2026-11-09 下午）：页边距统一递进口径 ——
 * <520dp 手机 16（稿 01）；520–599dp 24（稿 03 Compact）；≥600dp 32（稿 01「16→24→32」）；
 * ≥720dp 且短边 ≥520 内容限宽 680 居中（稿 04）。上笔 a60f8413 的 message 声称统一为
 * 32/24/16 但 content 实际抄旧了 24/32 反档 —— 本笔真正修正（与 SettingsPage 同口径）。
 */
@Composable
fun StatusPage(
    ctx: Context,
    topBar: Dp = 0.dp,
    bottomGap: Dp = 0.dp,
) {
    val insets = rememberApInsets()
    val adaptive = rememberApAdaptiveLayout()
    val widthDp = adaptive.width.value
    val shortSide = minOf(widthDp, adaptive.height.value)
    // 界面稿 03/04 边距档位（2026-11-09）：按窗口实测宽度取，不读 configuration
    // （同 TouchpadPage 的 v223.29.9 结论：configChanges 滞后一拍，读配置会错一拍）。
    val hPad = when {
        shortSide >= 600f -> 32.dp   // 01/04：真平板 600+ → 32dp（与 SettingsPage 同口径）
        shortSide >= 520f -> 24.dp   // 03 Compact：520–599dp → 24dp（旧值 32 与稿子「16→24」矛盾，修正）
        else -> 16.dp                // 01 手机：<520dp → 16dp
    }
    // issue#168 第 2 条追补（2026-11-11）：传输协议配置页不再限宽 680 居中（平板横屏黑边），
    // 改为撑满整屏；shortSide/hPad 仍用于边距档位，不删除避免横屏边距退化。
    val limited = false
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            tick++
        }
    }

    val state = remember(tick) { snapshot(ctx) }

    // —— 段落抽块（平板适配 §6.2，2026-11-11）——
    // ①②③④=「开关与操作」（传输/音频方向/控制/模块），⑤⑥=「详情与诊断」（链路/帮助）。
    // 双栏（≥600dp）：左栏=操作块，右栏=详情块 + 所选模块详情卡；窄屏：两块顺序复用，
    // 与迁移前单栏完全一致 —— 两套观感共用同一份 Section 代码，不维护两份。
    // selModule 是模块在 state.modules 里的下标；快照每秒重建但 AgentController 注册序
    // 不变，下标选择不会漂移；越界（模块列表为空的瞬间）由 getOrNull 兜底。
    var selModule by remember { mutableIntStateOf(0) }
    val opsSections: @Composable () -> Unit = {

        ApComponents.Section(title = ctx.getString(R.string.ui_home_section_transport)) {
            Column {
                AgentController.TRANSPORTS.forEach { t ->
                    ApComponents.SwitchRow(
                        title = ctx.getString(AgentController.transportRes(t)),
                        summary = state.transportSummary[t],
                        checked = state.transportOn[t] == true,
                        onCheckedChange = { on ->
                            Feedback.haptic(ctx)
                            AgentController.applyTransportSwitch(ctx, t, on)
                            tick++
                        },
                    )
                }
            }
        }
        var usbMic by remember { mutableStateOf(com.allperiph.audio.AudioModule.isMicOn(ctx)) }
        var usbSpk by remember { mutableStateOf(com.allperiph.audio.AudioModule.isSpeakerOn(ctx)) }
        var wifiMic by remember { mutableStateOf(com.allperiph.audio.WirelessAudioModule.isMicOn(ctx)) }
        var wifiSpk by remember { mutableStateOf(com.allperiph.audio.WirelessAudioModule.isSpeakerOn(ctx)) }
        ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_audio_dir)) {
            Column {
                var micOpen by remember { mutableStateOf(false) }
                var spkOpen by remember { mutableStateOf(false) }
                ApComponents.FoldGroup(
                    title = ctx.getString(R.string.ui_set_audio_group_mic),
                    countText = ctx.getString(
                        R.string.ui_set_audio_group_count,
                        listOf(usbMic, wifiMic).count { it },
                    ),
                    open = micOpen,
                    onToggleOpen = { micOpen = !micOpen },
                ) {
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_usb_mic),
                        summary = ctx.getString(R.string.ui_set_sum_usb_mic),
                        checked = usbMic,
                        onCheckedChange = { on ->
                            usbMic = on
                            com.allperiph.ui.AudioDirections.setUsbMic(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_wifi_mic),
                        summary = ctx.getString(R.string.ui_set_sum_wifi_mic),
                        checked = wifiMic,
                        onCheckedChange = { on ->
                            wifiMic = on
                            com.allperiph.ui.AudioDirections.setWifiMic(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                }
                ApComponents.FoldGroup(
                    title = ctx.getString(R.string.ui_set_audio_group_spk),
                    countText = ctx.getString(
                        R.string.ui_set_audio_group_count,
                        listOf(usbSpk, wifiSpk).count { it },
                    ),
                    open = spkOpen,
                    onToggleOpen = { spkOpen = !spkOpen },
                ) {
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_usb_spk),
                        summary = ctx.getString(R.string.ui_set_sum_usb_spk),
                        checked = usbSpk,
                        onCheckedChange = { on ->
                            usbSpk = on
                            com.allperiph.ui.AudioDirections.setUsbSpeaker(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_wifi_spk),
                        summary = ctx.getString(R.string.ui_set_sum_wifi_spk),
                        checked = wifiSpk,
                        onCheckedChange = { on ->
                            wifiSpk = on
                            com.allperiph.ui.AudioDirections.setWifiSpeaker(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                }
            }
        }
        var pickerOpen by remember { mutableStateOf(false) }
        var btGuideOpen by remember { mutableStateOf(false) }
        var btDiagOpen by remember { mutableStateOf(false) }
        ApComponents.Section(title = ctx.getString(R.string.ui_home_section_control)) {
            Column {
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.ui_set_label_current_control),
                    value = state.controlLabel,
                )
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.ui_home_label_uplink),
                    value = state.uplinkLabel,
                )
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.ui_home_label_controlled),
                    value = state.controlledLabel,
                )
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_pick_device_button),
                    onClick = { pickerOpen = true },
                )
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_bt_pair_guide),
                    onClick = { btGuideOpen = true },
                )
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_bt_diag_entry),
                    onClick = { btDiagOpen = true },
                )
                if (btDiagOpen) {
                    ApComponents.DialogCard(onDismiss = { btDiagOpen = false }) {
                        BtDiagPanel(ctx = ctx, tick = tick)
                    }
                }
                if (btGuideOpen) {
                    ApComponents.DialogCard(onDismiss = { btGuideOpen = false }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = ctx.getString(R.string.ui_bt_pair_title),
                                fontSize = 15.sp,
                            )
                            Text(
                                text = ctx.getString(R.string.ui_bt_pair_body),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            ApComponents.ActionButton(
                                text = ctx.getString(R.string.ui_bt_pair_open_settings),
                                onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                        )
                                    }.onFailure {
                                        Toast.makeText(
                                            ctx,
                                            R.string.ui_bt_pair_open_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                    }
                }
                if (pickerOpen) {
                    ApComponents.DialogCard(onDismiss = { pickerOpen = false }) {
                        ApDevicePickerBody(onDone = { pickerOpen = false })
                    }
                }
                if (state.controlling) {
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.ui_set_btn_disconnect_control),
                        onClick = {
                            runCatching { ControlTarget.clear() }
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.ui_set_toast_disconnected),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
        }
        ApComponents.Section(title = ctx.getString(R.string.ui_home_section_modules)) {
            Column {
                state.modules.forEachIndexed { mi, row ->
                    // 尾部（状态胶囊 + 启停开关）两种行形一致，抽成局部 trailing 复用
                    val trailing: @Composable () -> Unit = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ApComponents.Pill(
                                text = ctx.getString(row.stateRes),
                                color = stateColor(row.state),
                            )
                            if (state.running) {
                                Switch(
                                    checked = row.enabled,
                                    onCheckedChange = { on ->
                                        Feedback.haptic(ctx)
                                        AgentController.setModuleEnabled(ctx, row.id, on)
                                        tick++
                                    },
                                )
                            }
                        }
                    }
                    if (adaptive.twoPane) {
                        // 平板双栏（§6.2）：整行可点选模块，详情/诊断落右栏；
                        // 选中态只在右栏体现，左栏不加高亮（避免行高随选中跳动）
                        ApComponents.ClickRow(
                            title = row.label,
                            summary = row.detail,
                            trailing = trailing,
                            onClick = { selModule = mi },
                        )
                    } else {
                        ApComponents.ItemRow(
                            title = row.label,
                            summary = row.detail,
                            showDivider = true,
                            trailing = trailing,
                        )
                    }
                }
            }
        }

    }
    val detailSections: @Composable () -> Unit = {

        if (adaptive.twoPane) {
            // 右栏详情卡（§6.2「左栏列表，右栏详情」）：展示左栏选中的模块行，
            // 标题用模块名、正文用其实时 detail（状态/链路口径与左栏同一份快照）。
            // 无新文案 —— 全部复用模块行已有的 label/detail/stateRes，窄屏不显示本卡。
            val sel = state.modules.getOrNull(selModule) ?: state.modules.firstOrNull()
            if (sel != null) {
                ApComponents.Section(title = sel.label) {
                    Column {
                        ApComponents.ItemRow(
                            title = sel.label,
                            summary = sel.detail,
                            trailing = {
                                ApComponents.Pill(
                                    text = ctx.getString(sel.stateRes),
                                    color = stateColor(sel.state),
                                )
                            },
                        )
                    }
                }
            }
        }

        ApComponents.Section(title = ctx.getString(R.string.card_link)) {
            Column {
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_home_env_recheck),
                    onClick = { AgentController.refreshEnv(ctx) {} },
                )
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.label_usb_speed),
                    value = state.linkLabel,
                )
                ApComponents.ItemRow(
                    title = ctx.getString(R.string.ui_home_label_env),
                    summary = state.envSummary,
                )
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.ui_home_env_root),
                    value = if (state.rooted) ctx.getString(R.string.ui_home_env_root_ok)
                    else ctx.getString(R.string.ui_home_env_root_no),
                )
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.ui_home_env_udc),
                    value = state.udc,
                )
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.ui_home_env_battery),
                    value = state.batteryLabel,
                )
                if (!state.batteryWhitelisted) {
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.ui_set_btn_battery_whitelist),
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                )
                            }
                        },
                    )
                }
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.ui_home_env_notify),
                    value = state.notifyLabel,
                )
                if (!state.notifyGranted) {
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.ui_set_btn_notify_settings),
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                                )
                            }.onFailure {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.fromParts("package", ctx.packageName, null),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
                Text(
                    text = if (state.rooted) ctx.getString(R.string.hint_usb_ok)
                    else ctx.getString(R.string.hint_usb2),
                    fontSize = 12.sp,
                    color = if (state.rooted) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else colorResource(R.color.module_state_pending),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
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
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_help_open_full),
                    onClick = { helpOpen = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
        if (helpOpen) ApHelpSheet(onDismiss = { helpOpen = false })
    }

    if (adaptive.twoPane) {
        // 平板/宽屏双栏（v223.29.73 统一骨架）：改走 ApTwoPaneScaffold —— 左栏（操作）
        // 固定 180dp 与设置页同口径、内容竖直居中，右栏（模块详情卡 + 链路诊断 + 帮助）
        // 吃掉剩余宽度；两栏各自滚动保留，外层统一垫 hPad 边距（与本页单栏口径同源）。
        // 此前是自拼 weight(1f) 左右各半，与设置页的骨架不一致。
        ApTwoPaneScaffold(
            left = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = hPad + insets.left,
                            end = 8.dp,
                            top = 8.dp + topBar,
                            bottom = bottomGap + insets.bottom,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { opsSections() }
            },
            right = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = 12.dp,
                            end = hPad + insets.right,
                            top = 8.dp + topBar,
                            bottom = bottomGap + insets.bottom,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { detailSections() }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        // 窄屏单栏：原排版原样（Box + 限宽 + 滚动 + 边距），内容改为两块顺序复用
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .then(if (limited) Modifier.widthIn(max = 680.dp) else Modifier.fillMaxWidth())
                .verticalScroll(rememberScrollState())
                .padding(
                    start = hPad + insets.left,
                    end = hPad + insets.right,
                    top = 8.dp + topBar,
                    bottom = bottomGap + insets.bottom,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            opsSections()
            detailSections()
        }
        } // Box：限宽居中（界面稿 04，≥720dp）
    }
}

private class ModuleRowVm(
    val id: String,
    val label: String,
    val detail: String,
    val state: ModuleState,
    val enabled: Boolean = true,
) {
    val stateRes: Int get() = when (state) {
        ModuleState.RUNNING -> R.string.ui_module_state_running
        ModuleState.STARTING -> R.string.ui_module_state_starting
        ModuleState.DEGRADED -> R.string.ui_module_state_degraded
        ModuleState.ERROR -> R.string.ui_module_state_error
        ModuleState.STOPPING -> R.string.ui_module_state_stopping
        ModuleState.STOPPED -> R.string.ui_module_state_stopped
        ModuleState.IDLE -> R.string.ui_module_state_idle
    }
}

private class StatusVm(
    val running: Boolean,
    val transportOn: Map<String, Boolean>,
    val transportSummary: Map<String, String>,
    val modules: List<ModuleRowVm>,
    val linkLabel: String,
    val envSummary: String,
    val rooted: Boolean,
    val controlLabel: String,
    val controlling: Boolean,
    val uplinkLabel: String,
    val controlledLabel: String,
    val udc: String,
    val batteryLabel: String,
    val notifyLabel: String,
    val batteryWhitelisted: Boolean,
    val notifyGranted: Boolean,
)

private fun snapshot(ctx: Context): StatusVm {
    val running = AgentController.running
    val env: EnvChecks.Env? = AgentController.env
    val transportOn = AgentController.TRANSPORTS.associateWith {
        AgentController.isTransportEnabled(ctx, it)
    }
    val transportSummary = AgentController.TRANSPORTS.associateWith { t ->
        val ids = AgentController.groupModules(t)
        val on = ids.count { AgentController.isEnabled(ctx, it) }
        ctx.getString(R.string.ui_home_transport_count, on, ids.size)
    }
    val modules = AgentController.ORDER.map { id ->
        val m = AgentController.module(id)
        ModuleRowVm(
            id = id,
            label = ctx.getString(AgentController.labelRes(id)),
            detail = if (m == null) ctx.getString(R.string.ui_home_module_idle_detail)
            else AgentController.detailOf(m),
            state = m?.state ?: ModuleState.IDLE,
            enabled = AgentController.isEnabled(ctx, id),
        )
    }
    val link: LinkSpeed = AgentController.linkSpeed
    val controlling = ControlTarget.isControlling()
    val uplinkLabel = if (!Uplink.observed) {
        ctx.getString(R.string.ui_home_uplink_pending)
    } else {
        when (Uplink.current) {
            Uplink.WIRELESS -> ctx.getString(R.string.ui_home_uplink_wireless)
            Uplink.USB -> ctx.getString(R.string.ui_home_uplink_usb)
            Uplink.BLUETOOTH -> ctx.getString(R.string.ui_home_uplink_bt)
            else -> ctx.getString(R.string.ui_home_uplink_none)
        }
    }
    return StatusVm(
        running = running,
        transportOn = transportOn,
        transportSummary = transportSummary,
        modules = modules,
        linkLabel = if (link == LinkSpeed.UNKNOWN) "--" else link.name,
        envSummary = env?.summary ?: ctx.getString(R.string.ui_home_env_not_checked),
        rooted = env?.rooted ?: false,
        controlLabel = if (controlling) ControlTarget.label
        else ctx.getString(R.string.ui_set_label_current_control_none),
        controlling = controlling,
        uplinkLabel = uplinkLabel,
        controlledLabel = if (ControlledService.running) ctx.getString(R.string.ui_home_controlled_on)
        else ctx.getString(R.string.ui_home_controlled_off),
        udc = env?.udc ?: ctx.getString(R.string.ui_home_env_udc_none),
        batteryLabel = if (env != null && !env.batteryOptimized) ctx.getString(R.string.ui_home_env_battery_ok)
        else ctx.getString(R.string.ui_home_env_battery_no),
        notifyLabel = if (env?.notificationGranted == true) ctx.getString(R.string.ui_home_env_notify_ok)
        else ctx.getString(R.string.ui_home_env_notify_no),
        batteryWhitelisted = env != null && !env.batteryOptimized,
        notifyGranted = env?.notificationGranted == true,
    )
}

@Composable
private fun stateColor(s: ModuleState): Color = colorResource(
    when (s) {
        ModuleState.RUNNING -> R.color.module_state_running
        ModuleState.STARTING, ModuleState.STOPPING -> R.color.module_state_pending
        ModuleState.DEGRADED -> R.color.module_state_pending
        ModuleState.ERROR -> R.color.module_state_error
        else -> R.color.module_state_idle
    },
)

private fun diagLines(ctx: Context, d: BtDiag): List<String> = buildList {
    add(ctx.getString(if (d.adapterOn) R.string.ui_bt_diag_adapter_on else R.string.ui_bt_diag_adapter_off))
    add(ctx.getString(if (d.connected) R.string.ui_bt_diag_connected_yes else R.string.ui_bt_diag_connected_no))
    add(ctx.getString(if (d.registered) R.string.ui_bt_diag_registered_waiting else R.string.ui_bt_diag_not_registered_yet))
    add(ctx.getString(R.string.ui_bt_diag_hosts))
    if (d.hosts.isEmpty()) {
        add(ctx.getString(R.string.ui_bt_diag_hosts_none))
    } else {
        d.hosts.forEach { add("· $it") }
    }
    add(ctx.getString(R.string.ui_bt_diag_last_error))
    add(d.lastError ?: ctx.getString(R.string.ui_bt_diag_no_error))
}

@Composable
private fun BtDiagPanel(ctx: Context, tick: Int) {
    val diag = remember(tick) {
        (AgentController.module(ModuleId.BTHID) as? BtHidDevice)?.diagnostics()
    }
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = ctx.getString(R.string.ui_bt_diag_title),
            fontSize = 15.sp,
        )
        val lines = diag?.let { diagLines(ctx, it) } ?: emptyList()
        lines.forEach { line ->
            Text(
                text = line,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_bt_diag_copy),
            onClick = {
                val text = lines.joinToString("\n")
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("bt_diag", text))
                Toast.makeText(ctx, R.string.ui_bt_diag_copied, Toast.LENGTH_SHORT).show()
            },
        )
    }
}
