package com.allperiph.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一排**胶囊选择器**（重构线通用件）。
 *
 * 设置页用它切分类，键盘页用它切面板 —— 同一个交互（一排互斥选项、横向可滚、选中实心），
 * 只写一份，免得两页各画一套、宽度与圆角还不一致。
 *
 * 标签用**资源 id** 而不是字符串：调用方传 `listOf(R.string.x, ...)`，
 * 文字由这里 `stringResource` 解析 —— 这样调用点不会把中文写进 Kotlin 常量，
 * 也就不会撑高「硬编码文案」的基线。
 */
@Composable
fun CapsuleRow(
    labelRes: List<Int>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labelRes.forEachIndexed { i, res ->
            val on = i == selectedIndex
            Text(
                text = stringResource(res),
                fontSize = 14.sp,
                color = if (on) cs.onPrimary else cs.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) cs.primary else cs.surfaceVariant)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}
