package com.allperiph.ui.feature.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.allperiph.ui.KeyLayouts
import com.allperiph.ui.KeyPref
import com.allperiph.ui.miuix.CapsuleRow
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 键盘页（Compose 重构线）—— 旧 `KeyboardFragment` + `KeyboardPanels` 的 Compose 版。
 *
 * ## 只换界面，键位与发键全部复用
 *  · 键位数据仍是 [KeyLayouts]（小键盘 / 九宫格 / 方向键 / F 区，usage 显式写出）与
 *    [KeyPref.letterRows]（QWERTY / AZERTY / Dvorak 三套字母排布 + 数字行 + 功能行）；
 *  · 发键仍是 [HidKeys.tap] / [HidKeys.toggleSticky] —— 与旧页同一条链路，
 *    「粘滞修饰键」（Ctrl 按下后再按 C = Ctrl+C）的语义不会两边不一致。
 *
 * ## 面板切换
 * 与设置页同一套胶囊选择器（[CapsuleRow]）：一排互斥选项、横向可滚、选中实心。
 * 旧版是「左上角一个下拉 + 一排 tab」，两处入口做同一件事，容易点错。
 */
@Composable
fun KeyboardScreen(modifier: Modifier = Modifier) {
    var panel by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(
        R.string.shell_kb_qwerty,
        R.string.shell_kb_numpad,
        R.string.shell_kb_grid9,
        R.string.shell_kb_arrows,
        R.string.shell_kb_fn,
    )

    Column(modifier = modifier.fillMaxSize()) {
        CapsuleRow(labelRes = labels, selectedIndex = panel, onSelect = { panel = it })
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            when (panel) {
                0 -> QwertyPanel()
                1 -> KeyGrid(KeyLayouts.NUMPAD)
                2 -> KeyGrid(KeyLayouts.GRID9)
                3 -> KeyGrid(KeyLayouts.ARROWS)
                else -> KeyGrid(KeyLayouts.FN)
            }
        }
    }
}

/** QWERTY 全键面板：修饰键行（可粘滞）+ 数字行 + 三排字母 + 功能行 */
@Composable
private fun QwertyPanel() {
    val ctx = LocalContext.current
    // 粘滞状态在 HidKeys 里（与旧页同一份），点一下要重画才能反应到键帽高亮
    var revision by remember { mutableIntStateOf(0) }
    val rows = remember(revision) { KeyPref.letterRows(ctx) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            for ((label, bit) in HidKeys.MOD_LABELS) {
                KeyCap(
                    label = label,
                    accent = HidKeys.isSticky(bit),
                    modifier = Modifier.weight(1f),
                ) {
                    HidKeys.toggleSticky(bit)
                    revision++
                }
            }
        }
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                for (label in row) {
                    KeyCap(label = label, modifier = Modifier.weight(1f)) {
                        // 键面文字 → usage 由 HidKeys 反查（含 ';' '[' 这类符号位）
                        HidKeys.tap(HidKeys.usageByLabel(label))
                    }
                }
            }
        }
    }
}

/** 扩展面板：数据就是 [KeyLayouts] 的若干行，每键自带 usage 与宽度权重 */
@Composable
private fun KeyGrid(rows: List<List<KeyLayouts.Key>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                for (key in row) {
                    KeyCap(label = key.label, modifier = Modifier.weight(key.weight)) {
                        HidKeys.tap(key.usage)
                    }
                }
            }
        }
    }
}

/** 一个键帽：圆角块 + 居中文字；[accent] = 粘滞修饰键已按下 */
@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (accent) cs.onPrimary else cs.onSurface,
        )
    }
}
