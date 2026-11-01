package com.allperiph.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
 *
 * ## 硬规则（CI 关卡 `scripts/check_ui_kit.py` 执行）
 * `ui/compose/` 下的页面**只许**经由本对象取卡片 / 开关行 / 取值行 / 状态胶囊 / 动作按钮：
 *   · `ApComponents.Card`    —— 卡片、弹窗主体、键帽
 *   · `ApComponents.Section` —— 带动小标题的分组
 *   · `ApComponents.ActionButton` —— 页面级动作按钮
 *   · `ApComponents.DialogCard`  —— 弹窗统一外壳（Dialog + 限高卡片 + 内边距）
 *   · `ApComponents.NarrowButton` —— 随文字宽度的按钮（弹窗取消 / 扇出项 / chip）
 * 关卡扫描的是 `ui/compose/` 目录；唯一豁免是 `MiuixShowcaseActivity.kt`（在 `ui/miuix/`，
 * 组件画廊，演示对象就是**原生**组件，包一层就展示不出真实观感），理由已写进关卡常量。
 *
 * **为什么要有这条规则**：迁移是分屏做的，先迁的屏（键盘页、帮助页）各自写 `Card(` 和
 * `TextButton(`，后迁的屏走本对象 —— 结果同一套界面里两种写法并存，"改一次圆角要动几个文件"
 * 的老问题换个形式回来了（issue#42 排版混乱的同一根因）。
 */
object ApComponents {

    /**
     * 裸卡片容器：给**不想带小标题**的场景（弹窗主体、键位网格里的键帽）。
     *
     * [modifier] 直接透传给 Miuix `Card` —— 弹窗限高（如 `fillMaxHeight(0.62f)`）这类
     * 尺寸约束由调用方决定，本组件不预设，否则又会退化成「每个页面自己写一份尺寸」。
     *
     * 注意：本对象内的 `Card` 名字**遮蔽**了 import 进来的 Miuix `Card`（成员优先于导入），
     * 所以内部一律写 `top.yukonga.miuix.kmp.basic.Card(...)` —— 直接写 `Card(...)` 会解析成
     * 本函数自己，编译照过、运行时无限递归。
     */
    @Composable
    fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth().then(modifier)) { content() }
    }

    /** 分组：小标题 + 卡片容器（卡片内部就是 ColumnScope，子项直接排） */
    @Composable
    fun Section(
        title: String,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            SmallTitle(text = title)
            top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }

    /** 一行信息：标题（+ 摘要）在左，尾部内容在右 */
    @Composable
    fun ItemRow(
        title: String,
        summary: String? = null,
        trailing: @Composable (() -> Unit)? = null,
        showDivider: Boolean = false,
        modifier: Modifier = Modifier,
    ) {
        Column {
            Row(
                modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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

    /** 只读取值行（如「当前版本」「本机地址」）。[modifier] 供调用方挂手势（如长按版本行） */
    @Composable
    fun ValueRow(title: String, value: String, modifier: Modifier = Modifier) {
        ItemRow(
            title = title,
            modifier = modifier,
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

    /**
     * 弹窗外壳：`Dialog` + 限高卡片 + 内边距，**所有弹窗内容的统一容器**。
     *
     * 走的还是 [Card]（同一份圆角与底色），只多给一个「最多占屏高多少」的上限 ——
     * 弹窗内容一长就顶出屏外是 issue#52 第 2 条的老毛病，限高交给这里做，
     * 调用方不必各自回忆 0.62 / 0.82 / 0.92 哪个对。
     *
     * @param heightRatio 内容区最多占屏高的比例；默认 0.62（照竖屏算：800dp 屏 →
     *   内容约 496dp，标题 + 两个按钮约 160dp 仍在屏内；横屏时自动变矮并滚动）。
     * @param padding 内容区四周留白。
     */
    @Composable
    fun DialogCard(
        onDismiss: () -> Unit,
        modifier: Modifier = Modifier,
        heightRatio: Float = 0.62f,
        padding: Dp = 16.dp,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            // fillMaxWidth 在前（与 Card 的默认一致），调用方的 modifier 可以覆盖它
            top.yukonga.miuix.kmp.basic.Card(
                modifier = Modifier.fillMaxWidth()
                    .then(modifier)
                    .fillMaxHeight(heightRatio),
            ) {
                Column(modifier = Modifier.padding(padding), content = content)
            }
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

    /**
     * 窄按钮：宽度随文字，**不撑满整行**。用于弹窗里的取消/关闭/单项、网格里的 chip。
     *
     * 它本身是 Miuix `TextButton` 的直通封装（不额外加 padding / 尺寸），存在的理由只有一个：
     * 让「页面级动作按钮」[ActionButton] 与「随文字宽度的按钮」在调用处一眼可分，
     * 并让 CI 关卡能机械拦住页面里直接裸写 `TextButton(` —— 迁移期间两种写法混用，
     * 改一次内边距要翻好几个文件，就是 issue#42 排版混乱的同一根因。
     */
    @Composable
    fun NarrowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        TextButton(text = text, onClick = onClick, modifier = modifier)
    }
}
