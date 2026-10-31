package com.allperiph.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 逐屏迁移共用的**最小组件集**：卡片、标题、开关行、取值行、状态胶囊、动作按钮。
 *
 * 取舍：只包装 Miuix 已有的组件（[Card] / [Switch] / [TextButton] / [SmallTitle]），
 * 不自己画一套视觉 —— 一旦自绘，HyperOS 观感就和 Miuix 原生页面不一致，
 * 后续每个屏都会长出自己的风格。
 *
 * 所有尺寸走 4 的倍数（8 / 12 / 16），与 `dimens.xml` 的梯度一致。
 */
object ApComponents {

    /** 分组：小标题 + 卡片容器（卡片内部就是 ColumnScope，子项直接排） */
    @Composable
    fun Section(
        title: String,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            SmallTitle(text = title)
            Card(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }

    /** 一行信息：标题（+ 摘要）在左，尾部内容在右 */
    @Composable
    fun ItemRow(
        title: String,
        summary: String? = null,
        trailing: @Composable (() -> Unit)? = null,
        showDivider: Boolean = false,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, fontSize = 15.sp)
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                trailing?.invoke()
            }
            if (showDivider) HorizontalDivider()
        }
    }

    /** 开关行：状态由调用方持有（单向数据流，和旧 Views 的双向监听不同） */
    @Composable
    fun SwitchRow(
        title: String,
        summary: String? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        ItemRow(
            title = title,
            summary = summary,
            trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
        )
    }

    /** 只读取值行（如「当前版本」「本机地址」） */
    @Composable
    fun ValueRow(title: String, value: String) {
        ItemRow(
            title = title,
            trailing = {
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            },
        )
    }

    /** 状态胶囊：一行短状态 + 语义色底 */
    @Composable
    fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(text = text, fontSize = 12.sp, color = color)
        }
    }

    /** 动作按钮：整行宽（窄屏并排会把文字顶到边，与旧卡同一约定） */
    @Composable
    fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
