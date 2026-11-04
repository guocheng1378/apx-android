package com.allperiph.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.compose.keyFx
import com.allperiph.ui.compose.rememberKeyFx
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
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
 * 关卡扫描的是 `ui/compose/` 目录；豁免清单当前**为空**（原先唯一的
 * `MiuixShowcaseActivity.kt` 组件总览页随 2026-11-04 删除该页一并移除），
 * 新增豁免仍须在关卡常量里写明「为什么不走 Kit」。
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

    /** 开关行：状态由调用方持有（单向数据流，与旧 Views 的双向监听不同） */
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

    /**
     * 可点整行（尾部可选）：与 [ItemRow] 同一排版，但**整行**可点并给出按压反馈。
     *
     * 用途是「长按换位 / 长按弹菜单」这类交互（成宇 2026-11-03「悬浮球的功能自定义里面长按
     * 可以上下排」）：此前这种行只能用 `ActionButton`（占满一行、形态是按钮），
     * 或者自己拼 `ItemRow` + `pointerInput` —— 后者会在每个调用点各写一份手势与反馈。
     * 这里把「反馈 + 点击 + 长按」收成一个组件，调用方只给语义。
     *
     * @param onLongClick 非空时提供长按（默认 500ms，属系统 `longPressTimeout` 量级）
     */
    @Composable
    fun ClickRow(
        title: String,
        summary: String? = null,
        trailing: (@Composable () -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val host = androidx.compose.ui.platform.LocalView.current
        // 长按可达性那一条描述要带标题，所以这个组件需要 Context（文案走 strings.xml）
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val gesture = Modifier.pointerInput(onLongClick) {
            if (onLongClick == null) {
                detectTapGestures { com.allperiph.ui.Feedback.tap(host); onClick() }
            } else {
                detectTapGestures(
                    onLongPress = { com.allperiph.ui.Feedback.haptic(host); onLongClick() },
                    onTap = { com.allperiph.ui.Feedback.tap(host); onClick() },
                )
            }
        }
        // 长按可达性：读屏用户拿不到「长按」，整行给一句可识别的提示
        val a11y = if (onLongClick == null) {
            Modifier
        } else {
            Modifier.semantics {
                contentDescription = ctx.getString(R.string.ui_common_long_press_reorder, title)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(a11y)
                .then(gesture)
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            if (onLongClick != null) {
                Text(
                    text = "\u2261",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    /** 只读取值行（如「当前版本」「本机地址」）。[modifier] 供调用方挂手势 */
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
     * 单选芯片 / 分类页签：**必须先能看出当前选的是哪个**。
     *
     * 2026-11-02 审查条 2.1：设置页四个分类页签此前是同一个 [NarrowButton]（裸 `TextButton`），
     * 没有 selected 概念 —— 17 个 Section 分四类，切完页签没有任何状态反馈。
     * 这里不塞进 [NarrowButton]（那个仍要留给「取消 / 关闭 / 扇出项」这类无状态按钮），
     * 另开一个专为「有选中态」的芯片：
     *  · [selected] 选中 → 主色 16% 胶囊底 + 主色文字 + SemiBold；
     *  · 未选中 → 透明底 + `onSurfaceSecondary` 文字，靠底色差异表达状态；
     *  · [accent] 非空时不看 [selected]，恒定用该强调色（键映射里按皮肤给快捷键 chip 上色用，
     *    见审查条 1.2 —— `ApSkin.HOTKEY` 此前全仓零消费者，设置项承诺了不存在的效果）。
     * 高度下限 44dp：页签是**手指**目标（不是键帽，键帽另按 48dp 基线处理）。
     */
    @Composable
    fun TabChip(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        accent: Color? = null,
        fx: Int = com.allperiph.ui.KeyPref.FX_NONE,
    ) {
        val tint = accent ?: MiuixTheme.colorScheme.primary
        val active = selected || accent != null
        // 成宇 2026-11-04「触摸板好几处都要有特效和震动」：芯片此前只有 clickable，
        // 按下去一点视觉反馈都没有。这里补上按键特效（档位由调用方读偏好后传下来，
        // 默认 FX_NONE 不给设置页页签凭空加手感）；震动仍归调用方的 onClick
        // ——触控板页的 chip 已经在 onClick 里 ApMotion.tap，这里再接一次就是双震。
        val chipInteraction = remember { MutableInteractionSource() }
        val chipPressed by chipInteraction.collectIsPressedAsState()
        val chipFx = rememberKeyFx(chipPressed, fx)
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(if (active) tint.copy(alpha = if (selected) 0.16f else 0.12f) else Color.Transparent)
                .clickable(
                    interactionSource = chipInteraction,
                    indication = null,
                    onClick = onClick,
                )
                .then(Modifier.keyFx(chipFx, tint))
                .heightIn(min = 44.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = if (active) tint else MiuixTheme.colorScheme.onSurfaceSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
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
     * @param scrollable 内容超出限高时**是否可滚动**（默认开）。
     *   ★ v223.29 修「横屏时有些弹框不能往下滚动」：此前这里给的是
     *   `fillMaxHeight(ratio)` + 一个**不可滚动**的 `Column` —— 竖屏高度够用看不出问题，
     *   横屏屏高只剩竖屏的约一半，内容一多就直接被裁在卡片外，而卡片本身跟屏幕一样高、
     *   外面也没有能接住手势的东西，于是「怎么划都不动」。
     *   现在默认套一层 `verticalScroll`：任何调用方都直接得到「装得下就正常排版、
     *   装不下就能滚」的行为，不必各自记得包一层滚动。
     *   ⚠️ 只有一种例外：内容里**用了 `weight(1f)`** 的弹窗（滚动容器内高度无界，
     *   weight 会失效甚至崩），那些调用方要显式传 `scrollable = false` 并自己给内部滚动。
     */
    @Composable
    fun DialogCard(
        onDismiss: () -> Unit,
        modifier: Modifier = Modifier,
        heightRatio: Float = 0.62f,
        padding: Dp = 16.dp,
        scrollable: Boolean = true,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            // fillMaxWidth 在前（与 Card 的默认一致），调用方的 modifier 可以覆盖它
            top.yukonga.miuix.kmp.basic.Card(
                modifier = Modifier.fillMaxWidth()
                    .then(modifier)
                    .fillMaxHeight(heightRatio),
            ) {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .then(
                            if (scrollable) {
                                Modifier.verticalScroll(rememberScrollState())
                            } else {
                                Modifier
                            },
                        ),
                    content = content,
                )
            }
        }
    }

    /** 动作按钮：整行宽（窄屏并排会把文字顶到边，与旧卡同一约定） */
    @Composable
    fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        // 成宇 2026-11-04「都有特效和震动了」：页级动作按钮此前是 TextButton 直通，
        // 点下去既不震也不响（键盘页的「关闭」「编辑」就在这类按钮上）。
        val abHost = androidx.compose.ui.platform.LocalView.current
        TextButton(
            text = text,
            onClick = { com.allperiph.ui.Feedback.tap(abHost); onClick() },
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
        val nbHost = androidx.compose.ui.platform.LocalView.current
        TextButton(
            text = text,
            onClick = { com.allperiph.ui.Feedback.tap(nbHost); onClick() },
            modifier = modifier,
        )
    }

    /**
     * 折叠分组：**默认收起**的组头（标题 + 计数）与展开后的子项。
     *
     * 成宇 2026-11-02「音响开关应该有麦克风和音响的单独开关，他们应该分组，不打开不出现」：
     * 四路音频方向开关按 麦克风 / 音响 分两组，默认收起，点组头展开才出现子开关；
     * 2026-11-04 同一段整块上移到状态页（「把设置里的管麦克风和音响的也挪过来」），
     * 两处都要用，就把实现从设置页的 private 挪进 Kit，只留一份。
     *
     * 折叠只动 UI、不动任何存储：子项的开关语义（[com.allperiph.ui.AudioDirections]）与
     * 折叠无关；收起时组头用 [countText] 回显「已开 N / 2 路」，不展开也知道勾了几项。
     */
    @Composable
    fun FoldGroup(
        title: String,
        countText: String,
        open: Boolean,
        onToggleOpen: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleOpen)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, fontSize = 15.sp)
                    Text(
                        text = countText,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    text = if (open) "\u25be" else "\u25b8",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (open) {
                HorizontalDivider()
                content()
            }
        }
    }

    /**
     * 液态玻璃键帽（成宇 2026-11-03「键盘再加一组液态玻璃按键的皮肤」）。
     *
     * 观感取自 Miuix 的玻璃口径（`nav_pill_*` 那批色）：**半透明底 + 顶部高光 + 细描边**，
     * 按下时整块泛白并跟着手指（不位移、不下沉 —— 下沉是机械档的语义，两档必须能一眼分开）。
     *
     * ## 为什么放在 Kit 里而不是键帽私有实现
     * 键盘面板有两个宿主：主壳键盘页（`KeyboardPage`）与操控面（`TouchpadActivity`）。
     * 两处各写一份「玻璃键帽」必然分叉（本仓在 `KeyBox` / `ModKey` / 键帽材质上已经踩过
     * 三次），所以玻璃这一档只在这里实现一次，两边按同一个形状参数调用。
     *
     * @param tint 非空时用该色作为玻璃的染色（修饰键点亮态）；null = 中性玻璃
     * @param fxMod 调用方算好的按键特效 Modifier（[com.allperiph.ui.compose.keyFx]），
     *   画在键帽内容之上、玻璃高光之下 —— 特效是「按下去的额外反馈」，不该盖掉高光
     */
    @Composable
    fun GlassKeycap(
        modifier: Modifier = Modifier,
        shape: androidx.compose.ui.graphics.Shape,
        label: String,
        textSize: androidx.compose.ui.unit.TextUnit,
        fg: Color,
        tint: Color?,
        interaction: MutableInteractionSource,
        onClick: () -> Unit,
        fxMod: Modifier = Modifier,
    ) {
        val pressed by interaction.collectIsPressedAsState()
        // 玻璃的明暗取主题「表面」色的亮度来判：深色主题下白玻璃要更淡，
        // 否则整块键帽糊成一片灰。不用 isSystemInDarkTheme —— 本工程的明暗由
        // ThemePref 写进 configuration，与 MiuixTheme 当前配色同源才算对。
        val dark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f
        val topAlpha = if (dark) 0.10f else 0.55f
        val bottomAlpha = if (dark) 0.04f else 0.28f
        val strokeAlpha = if (dark) 0.22f else 0.70f
        val base = tint ?: MiuixTheme.colorScheme.surfaceContainer
        // 玻璃底：竖向渐变的半透明白（上亮下淡），叠在键帽自己的底色上
        val glass = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = topAlpha), Color.White.copy(alpha = bottomAlpha)),
        )
        // 按下：整块再泛白一层（不位移 —— 下沉是机械档的语义，两档要能一眼分开）
        val pressedWash = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.14f)),
        )
        Box(
            modifier = modifier
                .clip(shape)
                .background(base)
                .background(glass)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = strokeAlpha), Color.Transparent),
                    ),
                    shape = shape,
                )
                .then(if (pressed) Modifier.background(pressedWash) else Modifier)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .then(fxMod),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = textSize,
                color = if (tint != null) Color.White else fg,
                textAlign = TextAlign.Center,
            )
        }
    }

}
