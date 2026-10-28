package com.allperiph.ui.feature.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一个键帽：圆角块 + 居中文字；[accent] = 粘滞修饰键已按下 */
@Composable
internal fun KeyCap(
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

/**
 * 游戏面板：一张固定键位表（Esc/1-4/Tab、Q W E R F B、A S D Shift Space Ctrl、方向键 + Enter/Del）。
 *
 * 为什么是固定表而不是全键盘：玩游戏时手指落点是肌肉记忆，键位不能随窗口宽度重排；
 * 这里六列等分，横屏双拇指正好够到。Shift / Ctrl 仍是**粘滞**，跑动+转向要能按住不放。
 */
@Composable
private fun GamePanel() {
    val layout = listOf(
        listOf("Esc", "1", "2", "3", "4", "Tab"),
        listOf("Q", "W", "E", "R", "F", "B"),
        listOf("A", "S", "D", "Shift", "Space", "Ctrl"),
        listOf("↑", "↓", "←", "→", "Enter", "Del"),
    )
    var revision by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        for (row in layout) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                for (label in row) {
                    val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == label }?.second
                    KeyCap(
                        label = label,
                        accent = bit != null && HidKeys.isSticky(bit),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (bit != null) HidKeys.toggleSticky(bit)
                        else HidKeys.tap(HidKeys.usageByLabel(label))
                        revision++
                    }
                }
            }
        }
    }
}
