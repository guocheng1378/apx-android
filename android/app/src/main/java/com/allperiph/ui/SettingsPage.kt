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
 *  · 三档主题：跟随系统 / 浅色 / 深色，走 [ThemePref]（同包，偏好与主壳同文件 `apx_theme`）；
 *  · 界面缩放：[UiScalePref]（0.80~1.25 乘数，主壳 setContent 乘进 LocalDensity；
 *    滑条在主壳，这里给四档离散选中）；
 *  · 模块开关（成宇 2026-11-03「模块开关挪到设置页」）：`AgentController.isEnabled/setEnabled`。
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
    val insets = rememberApInsets()
    val adaptive = rememberApAdaptiveLayout()
    val widthDp = adaptive.width.value
    val shortSide = minOf(widthDp, adaptive.height.value)
    // 界面稿 01/04 边距档位（2026-11-09）：按窗口实测宽度取，不读 configuration
    // （同 TouchpadPage 的 v223.29.9 结论：configChanges 滞后一拍，读配置会错一拍）。
    val hPad = when {
        shortSide >= 600f -> 32.dp   // 稿 04：真平板 600+ 取 32 + 限宽 680 居中
        shortSide >= 520f -> 24.dp   // 03 Compact：520–599dp 取 24dp
        else -> 16.dp                // 01 手机：<520dp 保持 16dp 不变
    }
    val limited = widthDp >= 840f && shortSide >= 520f   // 手机永不限宽（手机横屏短边 411 走这里不触发）
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            tick++
        }
    }

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
        ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_appearance)) {
            Column {
                var appearanceOpen by remember { mutableStateOf(true) }
                ApComponents.FoldGroup(
                    title = ctx.getString(R.string.ui_set_label_appearance),
                    countText = "",
                    open = appearanceOpen,
                    onToggleOpen = { appearanceOpen = !appearanceOpen },
                ) {
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_theme),
                        summary = ctx.getString(
                            R.string.ui_set_sum_theme,
                            ThemePref.LABELS[ThemePref.get(ctx)],
                        ),
                        checked = ThemePref.get(ctx) == ThemePref.DARK,
                        onCheckedChange = {
                            ThemePref.set(ctx, (ThemePref.get(ctx) + 1) % 3)
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
                    val scale = UiScalePref.current.floatValue
                    val scales = listOf(0.80f, 0.90f, 1.00f, 1.25f)
                    scales.forEachIndexed { i, v ->
                        ApComponents.SwitchRow(
                            title = ctx.getString(
                                R.string.ui_set_font_size_of,
                                ctx.getString(listOf(R.string.ui_set_font_size_small, R.string.ui_set_font_size_mid, R.string.ui_set_font_size_large, R.string.ui_set_font_size_huge)[i]),
                            ),
                            summary = ctx.getString(
                                R.string.ui_set_font_size_of,
                                "%.2f×".format(v),
                            ),
                            checked = scale == v,
                            onCheckedChange = { on ->
                                if (on) UiScalePref.set(ctx, v)
                                tick++
                            },
                        )
                    }
                }
            }
        }
        ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_modules)) {
            Column {
                AgentController.ORDER.forEach { id ->
                    val m = AgentController.module(id)
                    val label = ctx.getString(AgentController.labelRes(id))
                    val detail = m?.let { AgentController.detailOf(it) } ?: "--"
                    val state = m?.state ?: ModuleState.IDLE
                    val enabled = AgentController.isEnabled(ctx, id)
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
    }
    } // Box：限宽居中（界面稿 04，≥840dp）
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
