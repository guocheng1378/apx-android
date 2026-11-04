package com.allperiph.ui.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKey
import com.allperiph.ui.ApFeel
import com.allperiph.ui.Feedback
import com.allperiph.ui.KeyPref
import com.allperiph.ui.KeycapStyle
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApMiuixTheme
import com.allperiph.ui.miuix.rememberApInsets
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 键盘面板（主壳）。
 *
 * **为什么单独有这个文件**：迁 Compose 时旧设置页里「键盘」那一整页（键位网格 + 键帽
 * 材质 / 密度 / 排列三个下拉）丢了 —— 那三项偏好**只有实现没有入口**，
 * `KeyPref` 的 setter 全仓零调用。本轮把网格补回来（设置页给入口，本页是实际渲染）。
 *
 * 键帽视觉三档由 `KeycapStyle` 决定（机械 / 液态玻璃 / 平面）—— 与 `KeyPref` 一一对应，
 * 玻璃档走 [com.allperiph.ui.miuix.ApComponents.GlassKeycap]（Kit 里唯一一份，两个宿主共用）。
 */
@Composable
fun KeyboardPage(onClose: () -> Unit, topBar: Dp = 0.dp, bottomGap: Dp = 0.dp) {
    val ctx = LocalContext.current
    val host = androidx.compose.ui.platform.LocalView.current
    val insets = rememberApInsets()
    // 这三个值在设置页改、在这里渲染 —— 用 remember(applyRev) 让主题套用后当场重读
    val style = KeyPref.keycapStyleIndex(ctx)
    val dens = KeyPref.densityIndex(ctx)
    val layout = KeyPref.layoutIndex(ctx)
    val fx = KeyPref.effectIndex(ctx)
    val scope = rememberScrollState()
    // 页签：0 = 键位网格，1 = 快捷键说明
    var tab by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp + insets.left)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = topBar),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ApComponents.Pill(
                text = ctx.getString(R.string.ui_kb_tab_grid),
                color = if (tab == 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.apKeyFeel().apPress(fx, MiuixTheme.colorScheme.primary, { tab = 0 }, downFeel = true),
            )
            ApComponents.Pill(
                text = ctx.getString(R.string.ui_kb_tab_hint),
                color = if (tab == 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.apKeyFeel().apPress(fx, MiuixTheme.colorScheme.primary, { tab = 1 }, downFeel = true),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        if (tab == 0) {
            KeyGrid(ctx, style, dens, layout, fx, scope)
        } else {
            KeyHintList(ctx, scope)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = bottomGap + insets.bottom),
            horizontalArrangement = Arrangement.End,
        ) {
            ApComponents.NarrowButton(
                text = ctx.getString(R.string.ui_kb_close),
                onClick = { Feedback.release(host); onClose() },
            )
        }
    }
}

@Composable
private fun KeyGrid(ctx: Context, style: Int, dens: Int, layout: Int, fx: Int, scope: androidx.compose.foundation.ScrollState) {
    val rows = KeyRows.of(layout)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scope)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { cell ->
                    when (cell) {
                        is KeyCell.Gap -> Box(modifier = Modifier.width(cell.dp))
                        is KeyCell.Wide -> {
                            KeyBox(
                                label = cell.label,
                                onClick = { HidKey.tap(cell.usage) },
                                fx = fx,
                                widthWeight = cell.weight,
                            )
                        }
                        is KeyCell.Sticky -> ModKey(
                            label = cell.label,
                            active = cell.active,
                            onClick = { cell.toggle() },
                            fx = fx,
                            tint = if (cell.active) MiuixTheme.colorScheme.primary else null,
                            accent = MiuixTheme.colorScheme.primary,
                        )
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyHintList(ctx: Context, scope: androidx.compose.foundation.ScrollState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scope)
            .padding(top = 8.dp),
    ) {
        KeyHint.entries.forEach { hint ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = hint.label,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    text = ctx.getString(hint.res),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 键帽（普通键）。
 *
 * 三档：液态玻璃 → [com.allperiph.ui.miuix.ApComponents.GlassKeycap]（**与操控面同一份**），
 * 非机械档 → 平面色块，机械档 → 浅面 + 下沿抬边。
 *
 * ⚠️ **改动约束（2026-11-04 重构说明）**：
 * 本函数此前与 [ModKey] 是**逐字重复**的两份（压感、`press` 回调、`shape`、`h` 下限、
 * `interaction` / `fxValues` / `fxMod`、三档渲染，连两段审查注释都是抄了两遍），
 * 真实差异只有三个颜色表达式：`bg`、`fg`、`tint`。
 * 现已合并为一份 —— 修饰键 [ModKey] 只是一行委托（`active` 决定 bg/fg，`tint` 透传）。
 * **以后新增档位只改这里**，不要在 ModKey 里再写一遍。
 *
 * @param active 修饰键点亮态（Ctrl/Shift/Alt/Win 当前被按下/锁定）：
 *   `bg` 取 primary、`fg` 取白、`tint` 交给档位染色
 * @param tint 玻璃 / 非机械 / 机械三档的染色，非空时优先于主题面色
 */
@Composable
private fun KeyBox(
    label: String,
    active: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
    fx: Int,
    widthWeight: Float? = null,
) {
    val host = LocalView.current
    val press: () -> Unit = { Feedback.release(host); onClick() }
    val dens = KeyPref.densityIndex(LocalContext.current)
    val style = KeyPref.keycapStyleIndex(LocalContext.current)
    val shape = KeyShape.of(dens)
    val h = (44f * dens.keyScale).coerceAtLeast(48f).dp
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fxValues = rememberFx(fx, pressed)
    val fxMod = Modifier.keyFx(fxValues, MiuixTheme.colorScheme.primary)

    val primary = MiuixTheme.colorScheme.primary
    val bg = if (active) primary else MiuixTheme.colorScheme.surfaceContainer
    val fg = if (active) Color.White else MiuixTheme.colorScheme.onSurface

    val base: Modifier = Modifier
        .height(h)
        .fillMaxWidth(if (widthWeight != null) widthWeight else 1f)
        .clip(shape.toShape())

    val body: @Composable () -> Unit = {
        if (style == KeycapStyle.GLASS) {
            com.allperiph.ui.miuix.ApComponents.GlassKeycap(
                modifier = Modifier.fillMaxWidth().height(h),
                shape = shape.toShape(),
                label = label,
                textSize = 13.sp,
                fg = fg,
                tint = tint,
                interaction = interaction,
                onClick = press,
                fxMod = fxMod,
            )
        } else if (style != KeycapStyle.MECHANICAL) {
            Box(
                modifier = base
                    .background(bg)
                    .apPress(fx, primary, press)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = press,
                    )
                    .then(fxMod),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = tint ?: fg,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val lift = if (MiuixTheme.colorScheme.onSurface.luminance() > 0.5f) {
                MiuixTheme.colorScheme.surfaceContainer
            } else {
                bg
            }
            Box(
                modifier = base
                    .background(lift)
                    .apPress(fx, primary, press)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = press,
                    )
                    .then(fxMod),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(h - 4.dp)
                        .clip(shape.toShape())
                        .background(tint ?: MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        color = tint ?: fg,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    body()
}

/**
 * 修饰键（Ctrl / Shift / Alt / Win）。
 *
 * 2026-11-04：**改为 [KeyBox] 的一行委托**。此前本函数与 [KeyBox] 互为复制品 ——
 * 两份完全相同的三档渲染 + 相同的 `press` / `shape` / `h` / `interaction` / `fxMod`，
 * 连两段审查注释（「审查条 2.2」与「★ 必须把同一个 interactionSource 交给 clickable」）
 * 都原样抄了两遍；差异只有 `bg` / `fg` / `tint` 三个表达式。
 *
 * 合并后调用点**零改动**（签名保持不变）；渲染差异全部由 `active` / `tint` 表达。
 * `accent` 保留为 tint 的兜底（调用方未传 tint 时按点亮态染 primary），原先它在函数体内
 * 从未被使用，是个死参数。
 *
 * ⚠️ **改动约束**：修饰键与普通键共用同一份 [interaction] —— 必须把同一个 source
 * 交给 `clickable`，否则 `collectIsPressedAsState()` 永远读 false（进度 / 按压进度都恒 0）。
 * 不把 source 交给 clickable、或在函数内自己 `remember { MutableInteractionSource() }`
 * 而不交给 clickable，都属于同一个根因的 bug —— 见 [com.allperiph.ui.miuix.ApEffects]
 * 的 `apPressEffect`（那个是死功能，本 PR 一并删）。
 */
@Composable
private fun ModKey(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    fx: Int,
    tint: Color? = null,
    accent: Color,
) {
    KeyBox(
        label = label,
        active = active,
        tint = tint ?: if (active) accent else null,
        onClick = onClick,
        fx = fx,
    )
}
