package com.allperiph.ui.compose

import android.content.Context
import android.content.Intent
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import android.widget.Toast
import com.allperiph.controlled.ControlledService
import com.allperiph.core.Uplink
import com.allperiph.wireless.ControlTarget

/**
 * 状态页（**第一个完成 Compose + Miuix 迁移的屏**）。
 *
 * 对照旧实现 [com.allperiph.ui.StatusFragment] + `page_status.xml`，行为对齐如下：
 *  · 三个传输开关：读写 AgentController 的 `transport_*` 偏好，**并负责对应模块的启停与
 *    前台服务**（总闸已在 v223.29.3 去掉，开关就是唯一入口，见 AgentController.applyTransportSwitch）；
 *  · 音频方向：USB / Wi-Fi 两路的麦克风 / 音响开关（v223.29.3 从设置页整块上移，「把设置里的
 *    管麦克风和音响的也挪过来」）；
 *  · 模块行：`AgentController.label(id)` + `detailOf(module)` + 状态色，不再自己 findViewById 刷新；
 *  · 链路诊断：`AgentController.linkSpeed` 与 `EnvChecks.Env.summary`。
 *
 * 数据刷新走 1s 轮询（与旧 [com.allperiph.ui.MainActivity] 的 ticker 同频）：模块状态来自后台线程，
 * EventBus 只覆盖部分事件，轮询是最稳的一条。
 */
@Composable
fun StatusPage(
    ctx: Context,
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

    // tick 只用来触发重组；实际值每次都现读（模块状态与 running 都是 volatile 字段）。
    // 总闸去掉后 running 不再是入参，必须现读 —— 传输开关把服务拉起来后，
    // 模块行的子开关要自己在这一秒内跟上，而不是等用户重进页面。
    val state = remember(tick) { snapshot(ctx) }
    // 语义状态色明暗各一套（审查条 2.5）：判据与 ApMiuixTheme 同一入口，不各自读 configuration
    val dark = com.allperiph.ui.miuix.apIsDark()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp + insets.left,
                end = 16.dp + insets.right,
                top = 8.dp + topBar,
                // 同上：bottomGap 由主壳按横竖屏给定（竖屏 88dp / 横屏 24dp），
                // 不再用 maxOf(..., 96.dp) 那个会把横屏值顶掉的地板。
                bottom = bottomGap + insets.bottom,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // —— 传输通道 ——
        // ★ v223.29.9：此前的「运行状态」只读摘要块已整块移除（成宇 2026-11-05
        // 「状态页顶部运行状态去除」）。那一块只是把 `overallLabel / overallDetail`
        // 两行字摆出来，与下方三个传输开关表达的是同一件事 —— 开关的 `summary`
        // 已经写明每一路的实时状态，顶部再复述一遍纯属占地。
        // 沿革：更早版本这里是「启动 / 停止」总闸（成宇 2026-11-04 要求去掉，因为总闸
        // 关着时三个传输开关拨了不生效，是假开关）；去掉总闸后剩下的只读摘要在
        // v223.29.9 一并去掉。传输开关各自负责对应模块的启停与前台服务
        // （见 [AgentController.applyTransportSwitch]）。
        ApComponents.Section(title = ctx.getString(R.string.ui_home_section_transport)) {
            Column {
                AgentController.TRANSPORTS.forEach { t ->
                    ApComponents.SwitchRow(
                        title = AgentController.transportLabel(t),
                        summary = state.transportSummary[t],
                        checked = state.transportOn[t] == true,
                        onCheckedChange = { on ->
                            Feedback.haptic(ctx)
                            // 总闸去掉后，传输开关自己负责对应模块的启停与前台服务
                            // （与悬浮球「连接模式」菜单同一个入口，逻辑收在 AgentController 一处）
                            AgentController.applyTransportSwitch(ctx, t, on)
                            tick++
                        },
                    )
                }
            }
        }
        // —— 音频方向（v223.29.3 从设置页「工具与传输」整块上移）——
        // 成宇 2026-11-04「把设置里的管麦克风和音响的也挪过来」：四个音频方向开关与三个
        // 传输开关是同一层语义（都是「这一路要不要用」），分居设置页与状态页两处就是双源 ——
        // 与上一轮「环境诊断」上移同一判据（链路信息属于运行时，就该在状态页）。
        // 数据与热切换口径完全不变：仍走 com.allperiph.ui.AudioDirections
        // （模块在跑就热切换、没跑就只落盘，下次连接生效）。
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


        // —— 受控与出口（v223.28 从设置页「遥控与键盘」整体上移：成宇 2026-11-02
        //    「本应该在状态页的内容却在设置页」——链路状态属于运行时信息，就该在状态页。
        //    数据源：ControlTarget（当前控制谁）+ Uplink（唯一出口判据）+ ControlledService
        //    （本机被控）。选择器弹的是与悬浮球面板同一份 ApDevicePickerBody，连接逻辑
        //    仍只有 connectControlledDevice 一处）——
        var pickerOpen by remember { mutableStateOf(false) }
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
                if (pickerOpen) {
                    // DialogCard 内部已包一层 androidx.compose.ui.window.Dialog，
                    // 外面再套 Dialog = 「弹窗里再开一个窗口」（双层遮罩）。
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
                                ApComponents.Pill(text = row.stateLabel, color = stateColor(row.state, dark))
                                // 成宇 2026-11-03「状态页给个开关不能控制子开关，
                                // 总开关关了应该隐藏下面的子开关」：没在跑时这一行只留状态点。
                                // 这个「跑没跑」的判据在 v223.29.3 从主壳入参 [running] 换成
                                // 现读的 [StatusVm.running] —— 总闸去掉后没有任何地方再持有它，
                                // 页面必须自己现读（同一 1s tick 刷新）。
                                // 子开关的启用态仍记在 AgentController 里，运行态一起来就按原样回显。
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
                        },
                    )
                }
            }
        }

        // —— 链路诊断（v223.28 吸收设置页「环境诊断」整块：同一份 EnvChecks 数据
        //    此前两页各显示一半，属于双源。现在环境明细与重新检测都在这里，设置页删掉）——
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
                ApComponents.ValueRow(
                    title = ctx.getString(R.string.ui_home_label_env),
                    value = state.envSummary,
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
                // 只读文字是这次迁移丢掉的一步：旧主界面有 btnBattery（ACTION_IGNORE_BATTERY_
                // OPTIMIZATION），迁 Compose 后只剩「未忽略」四个字，用户知道有问题却没处点。
                // 这里给一枚按钮进「电池优化」列表页；不用带包名的直接请求，那需要多一条
                // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限（审查 v5 §9.3）。
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
                // 同一屏里无障碍那行本来就能跳（设置页 :651），通知权限却只有四个字，
                // 不一致比整段缺失更像故障（审查 v5 §9.3）。
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
                // 2026-11-04 改走 ApComponents.ActionButton：此前裸用 miuix.basic.Button，
                // **不带** ActionButton 内部那层 `Feedback.tap`（震动 + 音效），于是同一页上
                // 这个按钮只震不响、其余按钮又震又响（手感不一致，编译器不报、样式也不算错）。
                // 原 onClick 里的 Feedback.haptic(ctx) 一并删除 —— 留着会与 ActionButton
                // 内置的 Feedback.tap 叠成一次点击震两下。Button 的 import 同时移除，
                // scripts/check_ui_kit.py 的 BANNED 已把 Button 纳入拦裁范围。
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_help_open_full),
                    onClick = { helpOpen = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
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
    /** 现读的 Agent 运行态（总闸去掉后由传输开关驱动，见 [AgentController.applyTransportSwitch]） */
    val running: Boolean,
    val transportOn: Map<String, Boolean>,
    val transportSummary: Map<String, String>,
    val modules: List<ModuleRowVm>,
    val linkLabel: String,
    val envSummary: String,
    val rooted: Boolean,
    // v223.28 受控与出口 + 环境明细（吸收设置页「环境诊断」）
    val controlLabel: String,
    val controlling: Boolean,
    val uplinkLabel: String,
    val controlledLabel: String,
    val udc: String,
    val batteryLabel: String,
    val notifyLabel: String,
    /** 是否已加入电池优化白名单（true 才不需要提示去设置） */
    val batteryWhitelisted: Boolean,
    /** 通知权限是否已授予 */
    val notifyGranted: Boolean,
)

/**
 * 模块状态色（明暗**各一套**）。
 *
 * 2026-11-02 审查条 2.5：此前只有一套十六进制值，按 WCAG 公式在页面底上估算，
 * 浅色下 运行中 2.84:1 / 降级 2.41:1 / 异常 3.65:1、深色下 已停止 3.48:1，都不到 4.5:1。
 * 现在语义色以「卡片底色 + 同色 14% 胶囊底」为最坏情形取值，六个组合全部 ≥4.5:1：
 *
 *  | 状态 | 浅色（卡片 #FFFFFF） | 深色（卡片 #0F1011） |
 *  |---|---|---|
 *  | 运行中 | #00734D → 4.80 | #3DDC97 → 8.33 |
 *  | 启动中/停止中/降级 | #A34A08 → 4.83 | #FFB454 → 8.31 |
 *  | 异常 | #B4235A → 5.02 | #FF7AA8 → 6.37 |
 *  | 已停止/未启动 | #5B6470 → 4.95 | #A8B0BC → 6.90 |
 *
 * 括号里的比值是「文字色 vs 14% 同色胶囊底」的对比度（胶囊底压在卡片上，比纯卡片底更亮/更暗，
 * 所以按它算而不是按纯底算）。改色值时请连这张表一起改。
 */
private fun stateColor(s: ModuleState, dark: Boolean): Color = if (dark) {
    when (s) {
        ModuleState.RUNNING -> Color(0xFF3DDC97)
        ModuleState.STARTING, ModuleState.STOPPING -> Color(0xFFFFB454)
        ModuleState.DEGRADED -> Color(0xFFFFB454)
        ModuleState.ERROR -> Color(0xFFFF7AA8)
        else -> Color(0xFFA8B0BC)
    }
} else {
    when (s) {
        ModuleState.RUNNING -> Color(0xFF00734D)
        ModuleState.STARTING, ModuleState.STOPPING -> Color(0xFFA34A08)
        ModuleState.DEGRADED -> Color(0xFFA34A08)
        ModuleState.ERROR -> Color(0xFFB4235A)
        else -> Color(0xFF5B6470)
    }
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
    // v223.28：当前控制 / 出口 / 本机被控（快照现读，随 1s 轮询刷新）
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
        envSummary = env?.summary ?: "未检测",
        rooted = env?.rooted ?: false,
        controlLabel = if (controlling) ControlTarget.label
        else ctx.getString(R.string.ui_set_label_current_control_none),
        controlling = controlling,
        uplinkLabel = uplinkLabel,
        controlledLabel = if (ControlledService.running) ctx.getString(R.string.ui_home_controlled_on)
        else ctx.getString(R.string.ui_home_controlled_off),
        udc = env?.udc ?: ctx.getString(R.string.ui_home_env_udc_none),
        // EnvChecks.batteryOptimized == true 表示**没有**加白名单（它取的是
        // !isIgnoringBatteryOptimizations，摘要里也写「没加入省电白名单」），所以
        // 「已忽略（推荐）」要在它为 false 时显示。原来看反了：没加白名单时反而告诉
        // 用户「已忽略（推荐）」，等于把该处理的事说成已处理（审查 v5 §9.3）。
        // env 为 null（还没检测）时不给绿灯。
        batteryLabel = if (env != null && !env.batteryOptimized) ctx.getString(R.string.ui_home_env_battery_ok)
        else ctx.getString(R.string.ui_home_env_battery_no),
        notifyLabel = if (env?.notificationGranted == true) ctx.getString(R.string.ui_home_env_notify_ok)
        else ctx.getString(R.string.ui_home_env_notify_no),
        batteryWhitelisted = env != null && !env.batteryOptimized,
        notifyGranted = env?.notificationGranted == true,
    )
}