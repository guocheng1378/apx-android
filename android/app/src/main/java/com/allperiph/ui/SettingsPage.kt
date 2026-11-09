package com.allperiph.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.allperiph.R
import com.allperiph.core.ModuleId
import com.allperiph.core.ModuleState
import com.allperiph.core.Uplink
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApEdgeInsets
import com.allperiph.ui.miuix.rememberApInsets
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text

/**
 * 设置页（**第二个完成 Compose + Miuix 迁移的主屏**）。
 *
 * 对照旧实现 [com.allperiph.ui.SettingsActivity] + `page_settings.xml`，行为对齐如下：
 *  · 三档「主题与外观」：日间 / 夜间 / 自动，走 `ApPalette.themeMode`（与主壳 `ApShellActivity
 *    updateAppearance` 同一偏好）；
 *  · 字体大小：`ApFontSize`（v223.29.3 从主壳上移至此，此前设置里只有「主题与外观」没有字体）；
 *  · 模块开关（成宇 2026-11-03「模块开关挪到设置页」）：`AgentController.isEnabled/setEnabled`；
 *  · 「发现通道未就绪」提示：`discoveryUp / httpPort` 静态位（v223.29.33 新增）。
 *
 * 刷新：1s 轮询（与旧 SettingsActivity 的 ticker 同频）+ `LocalContext` 懒读（避免每帧拿 context）。
 *
 * 2026-11-09 界面稿 01/04：页边距分档 —— <520dp 保持 16（手机不变）；520–599dp 平板
 * Compact 取 24；600–839dp Medium 取 32（稿 01「边距 16→24→32」）；≥840dp 保持 32 并
 * 内容限宽 680 居中（稿 04 原话「内容限宽 680 居中」，与状态页口径一致）。
 */
@Composable
fun SettingsPage(
    ctx: Context,
    topBar: Dp = 0.dp,
    bottomGap: Dp = 0.dp,
) {
    // topBar/bottomGap：主壳顶栏（64dp 玻璃叠层）与浮动底栏的让位空档。
    // 由页面在滚动内容首尾自留，而不是主壳容器硬垫 —— 滚动内容从栏下穿过。

    val insets = rememberApInsets()
    val adaptive = rememberApAdaptiveLayout()
    val widthDp = adaptive.width.value
    // 界面稿 01/04 边距档位（2026-11-09）：按窗口实测宽度取，不读 configuration
    // （同 TouchpadPage 的 v223.29.9 结论：configChanges 滞后一拍，读配置会错一拍）。
    val hPad = when {
        widthDp >= 840f -> 32.dp   // 稿 04：≥840dp 保持 32 + 限宽 680 居中
        widthDp >= 600f -> 32.dp   // 04 Medium：边距 32dp（稿 01「16→24→32」，≥600 取 32）
        widthDp >= 520f -> 24.dp   // 03 Compact：24dp
        else -> 16.dp              // 01 手机：<520dp 保持 16dp 不变
    }
    val limited = widthDp >= 840f  // 04：≥840dp 内容限宽 680 居中（与状态页 ≥720 口径微调，稿 04 原话）
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            tick++
        }
    }

    // tick 只用来触发重组；实际值每次都现读。
    val state = remember(tick) { snapshot(ctx) }

    // Box 只为「限宽居中」而包（04）；不加任何高度/宽度约束，
    // 子 Column 的 verticalScroll 约束传递与旧版完全一致。
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(
        modifier = Modifier
            .then(if (limited) Modifier.widthIn(max = 680.dp) else Modifier.fillMaxWidth())
            .verticalScroll(rememberScrollState())
            .padding(
                start = hPad + insets.left,
                end = hPad + insets.right,
                top = 8.dp + topBar,
                // 同上：bottomGap 由主壳按横竖屏给定（竖屏 88dp / 横屏 24dp），
                // 不再用 maxOf(..., 96.dp) 那个会把横屏值顶掉的地板。
                bottom = bottomGap + insets.bottom,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // —— 主题与外观（v223.29.3 字体上移，与传输开关同一批从主壳整块上移）——
        ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_appearance)) {
            Column {
                // 成宇 2026-11-04「设置里字体大小没有地方改」：v223.29.3 字体从主壳上移至设置页，
                // 「主题与外观」段从此同时管外观 + 字体两件事。
                var appearanceOpen by remember { mutableStateOf(true) }
                ApComponents.FoldGroup(
                    title = ctx.getString(R.string.ui_set_label_appearance),
                    countText = "",
                    open = appearanceOpen,
                    onToggleOpen = { appearanceOpen = !appearanceOpen },
                ) {
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_theme),
                        summary = ctx.getString(R.string.ui_set_sum_theme),
                        checked = ApPalette.themeMode(ctx) == 0,
                        onCheckedChange = {
                            // 0=日间 1=夜间 2=自动：三选一，上一档取反（与旧 SettingsActivity 同判据）
                            val next = (ApPalette.themeMode(ctx) + 1) % 3
                            ApPalette.setThemeMode(ctx, next)
                            tick++
                        },
                    )
                }
                var fontOpen by remember { mutableStateOf(true) }
                ApComponents.FoldGroup(
                    title = ctx.getString(R.string.ui_set_label_font_size),
                    countText = "",
                    open = fontOpen,
                    onToggleOpen = { fontOpen = !fontOpen },
                ) {
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_font_size),
                        summary = ctx.getString(R.string.ui_set_sum_font_size),
                        checked = false, // 字体是档位选择器，不用开关行；占位保留语义
                        onCheckedChange = { },
                    )
                }
                // 成宇 2026-11-04「字体大小有四个档位，设置里应该显示出来」：旧布局 item_size.xml
                // 四档是「小 / 中 / 大 / 超大」单选条，迁 Compose 时用 `ApComponents.SwitchRow`
                // 复现（三行加一枚「超大」兜底，凑四档）—— 数据层 `ApFontSize` 只有单个 int，
                // 三处 `ApFontSize.of` 调用各拿一份，与旧布局的「选中项高亮 + 其余置灰」语义一致。
                val fontSize = ApFontSize.of(ctx)
                val sizes = listOf(12, 13, 14, 15)
                sizes.forEachIndexed { i, size ->
                    val isBig = i >= 3
                    ApComponents.SwitchRow(
                        title = ctx.getString(
                            R.string.ui_set_font_size_of,
                            ctx.getString(listOf(R.string.ui_set_font_size_small, R.string.ui_set_font_size_mid, R.string.ui_set_font_size_large, R.string.ui_set_font_size_huge)[i]),
                        ),
                        summary = ctx.getString(
                            R.string.ui_set_font_size_of,
                            size.sp.toString(),
                        ),
                        checked = fontSize == size,
                        onCheckedChange = { on ->
                            if (on) ApFontSize.set(ctx, size)
                            tick++
                        },
                    )
                }
            }
        }

        // —— 模块开关（成宇 2026-11-03「模块开关挪到设置页」：旧主界面 item_module_row.xml 的开关
        //    是模块启停的唯一入口，状态页只留状态点不接管启停 —— 一处开关、一处状态，不重复）——
        ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_modules)) {
            Column {
                AgentController.ORDER.forEach { id ->
                    val m = AgentController.module(id)
                    val label = ctx.getString(AgentController.labelRes(id))
                    val detail = m?.let { AgentController.detailOf(it) } ?: "--"
                    val state = m?.state ?: ModuleState.IDLE
                    val enabled = AgentController.isEnabled(ctx, id)
                    // 状态词已在 [ModuleRowVm.stateRes] 存为资源 id（2026-11-04 数据层资源化）：
                    // 数据类不再写死中文，由 UI 层解析成字串。
                    val stateRes = when (state) {
                        ModuleState.RUNNING -> R.string.ui_module_state_running
                        ModuleState.STARTING -> R.string.ui_module_state_starting
                        ModuleState.DEGRADED -> R.string.ui_module_state_degraded
                        ModuleState.ERROR -> R.string.ui_module_state_error
                        ModuleState.STOPPING -> R.string.ui_module_state_stopping
                        ModuleState.STOPPED -> R.string.ui_module_state_stopped
                        ModuleState.IDLE -> R.string.ui_module_state_idle
                    }
                    ApComponents.ItemRow(
                        title = label,
                        summary = detail,
                        showDivider = true,
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ApComponents.Pill(
                                    text = ctx.getString(stateRes),
                                    color = stateColor(state),
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { on ->
                                        Feedback.haptic(ctx)
                                        AgentController.setModuleEnabled(ctx, id, on)
                                        tick++
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }

        // —— 发现通道（v223.29.33 新增：discoveryUp / httpPort 静态位）——
        if (!state.discoveryUp) {
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_discovery)) {
                Column {
                    ApComponents.ItemRow(
                        title = ctx.getString(R.string.ui_set_label_discovery),
                        summary = ctx.getString(R.string.ui_set_sum_discovery_not_ready),
                        showDivider = true,
                    )
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.ui_set_btn_discovery_check),
                        onClick = {
                            runCatching { AgentController.refreshEnv(ctx) }
                            tick++
                        },
                    )
                }
            }
        }
    }
    } // Box：限宽居中（界面稿 04，≥840dp）
}

// ————————————————————— 快照 —————————————————————

private class SettingsVm(
    val themeMode: Int,
    val fontSize: Int,
    val discoveryUp: Boolean,
    val httpPort: Int,
)

/** 现读一份界面快照（主线程，全部是内存/偏好读取，不碰 IO） */
private fun snapshot(ctx: Context): SettingsVm {
    val themeMode = ApPalette.themeMode(ctx)
    val fontSize = ApFontSize.of(ctx)
    val env: com.allperiph.core.EnvChecks.Env? = AgentController.env
    return SettingsVm(
        themeMode = themeMode,
        fontSize = fontSize,
        // 2026-11-08（成宇发截图）：发现通道未就绪是**静态位**，非动态检测 ——
        // discoveryUp 为 false 时「发现通道未就绪」直接显示，不用等轮询。
        discoveryUp = env?.discoveryUp == true,
        httpPort = env?.httpPort ?: 0,
    )
}

@androidx.compose.runtime.Composable
private fun stateColor(s: ModuleState): Color = colorResource(
    when (s) {
        ModuleState.RUNNING -> R.color.module_state_running
        ModuleState.STARTING, ModuleState.STOPPING -> R.color.module_state_pending
        ModuleState.DEGRADED -> R.color.module_state_pending
        ModuleState.ERROR -> R.color.module_state_error
        else -> R.color.module_state_idle
    },
)
