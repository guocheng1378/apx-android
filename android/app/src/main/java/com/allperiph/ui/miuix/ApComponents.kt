package com.allperiph.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.ui.text.style.TextOverflow
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
                    // 排版口径（2026-11-07）：标题最多 2 行、摘要最多 3 行，超出省略 ——
                    // 再长靠页面/弹窗的竖向滚动看全，不把整行高度撑爆。
                    Text(text = title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
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
        // 全审查条 11：手势里经 rememberUpdatedState 读最新回调 —— 旧实现把
        // onClick 写进 pointerInput(onLongClick) 的 key 捕获，调用方每次重组新建
        // lambda（如 500ms 状态轮询）都会重启手势协程，长按按到一半就被掐断。
        // key 只留结构性的 hasLong（有/无长按决定手势形态），回调走状态直读。
        val currentOnClick by rememberUpdatedState(onClick)
        val currentOnLongClick by rememberUpdatedState(onLongClick)
        val hasLong = onLongClick != null
        val gesture = Modifier.pointerInput(hasLong) {
            if (!hasLong) {
                detectTapGestures { com.allperiph.ui.Feedback.tap(host); currentOnClick() }
            } else {
                detectTapGestures(
                    onLongPress = { com.allperiph.ui.Feedback.haptic(host); currentOnLongClick?.invoke() },
                    onTap = { com.allperiph.ui.Feedback.tap(host); currentOnClick() },
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
                // 排版口径（2026-11-07）：与 ItemRow 同规格 —— 标题≤2 行、摘要≤3 行。
                Text(text = title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
            if (onLongClick != null) {
                Text(
                    text = "\u2261",
                    // 同类尾部辅助文字统一 13sp（与取值行/折叠箭头对齐口径一致）
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    // 排版口径（2026-11-07 成宇「同类控件文字需要对齐」）：
                    // 尾值右对齐、最多 2 行、横向上限约半屏 —— 长地址/端口/路径
                    // 不再把左列标题挤断，且换行后与右缘对齐不再参差。
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(
                        max = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.46f).dp,
                    ),
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
            Text(text = text, fontSize = 12.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    /** [TabChip] 的高度下限（dp）：快捷键栏算「固定 3 排」总高时与这里同一口径，改一处即两处同步。 */
    const val TAB_CHIP_MIN_HEIGHT_DP = 44

    /**
     * 触控板横屏右列宽度（issue#168 第 7 条「横竖屏布局和 app 首页的触控板统一」）：
     * 首页 TouchpadPage 与操控面 ConsoleTouchpadPage 共用同一口径 ——
     * 宽屏（≥840dp）定宽 268、中板（≥600dp）定宽 292、手机按屏宽 38% 夹取 180–320。
     * 宽度口径只留这一份，改这里即两处同步，避免再长出第二份。
     */
    fun touchpadSideWidth(screenWidthDp: Float): Dp = when {
        screenWidthDp >= 840f -> 268.dp
        screenWidthDp >= 600f -> 292.dp
        else -> (screenWidthDp * 0.38f).coerceIn(180f, 320f).dp
    }

    @Composable
    fun TabChip(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        accent: Color? = null,
        fx: Int = com.allperiph.ui.KeyPref.FX_NONE,
        /**
         * 水平内边距（2026-11-12 图2「…」修复开放给调用方）：默认 14dp 不动老观感；
         * 快捷键网格的 1-slot 芯片在窄列里减到 10dp —— 窄列下（横屏右列 268dp）每
         * slot 只有 50dp 出头，14dp×2 的内边距把两个 14sp 汉字挤出文字区，省略成「…」。
         */
        chipHPad: Dp = 14.dp,
    ) {
        val tint = accent ?: MiuixTheme.colorScheme.primary
        val active = selected || accent != null
        // 成宇 2026-11-04「触摸板好几处都要有特效和震动」：芯片此前只有 clickable，
        // 按下去一点视觉反馈都没有。这里补上按键特效（档位由调用方读偏好后传下来，
        // 默认 FX_NONE 不给设置页页签凭空加手感）；震动仍归调用方的 onClick
        // ——触控板页的 chip 在 onClick 里 ApMotion.tap、键盘页页签在 onClick 里
        // Feedback.tap，这里再接一次就是双震。
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
                .heightIn(min = TAB_CHIP_MIN_HEIGHT_DP.dp)
                .padding(horizontal = chipHPad, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = if (active) tint else MiuixTheme.colorScheme.onSurfaceSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                // 页签/芯片恒单行：过长出省略号，不把页签行顶出屏外
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * 弹窗外壳：`Dialog` + 限高卡片 + 内边距，**所有弹窗内容的统一容器**。
     *
     * 走的还是 [Card]（同一份圆角与底色），只多给一个「最多占屏高多少」的上限 ——
     * 弹窗内容一长就顶到屏外是 issue#52 第 2 条的老毛病，限高交给这里做，
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
            // 2026-11-07（成宇「弹窗和编辑页显示不全」）：弹窗是独立窗口、独立组合 ——
            // 主壳 setContent 的 LocalDensity 覆盖不到这里，全局缩放必须自己再供一次；
            // __ins 读弹窗自己窗口的 insets（挖孔/圆角若与弹窗相交就垫进内容留白）。
            val __ins = rememberApInsets()
            val __d = androidx.compose.ui.platform.LocalDensity.current
            // 平滑过渡（成宇 2026-11-07）：与主壳同一口径，缩放 150ms 缓动不跳变。
            val __s by androidx.compose.animation.core.animateFloatAsState(
                targetValue = com.allperiph.ui.UiScalePref.current.floatValue,
                animationSpec = androidx.compose.animation.core.tween(150),
            )
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(__d.density * __s, __d.fontScale),
            ) {
            // 入场动效（2026-11-07）：0.94 → 1 缩放 + 淡入。规格取 ApMotion，设置页
            // 「动效」关掉时 springSpec 退化成 0ms tween，直接落终态（不是变快，是不动）。
            val __mctx = androidx.compose.ui.platform.LocalContext.current
            val __enter = androidx.compose.runtime.remember {
                androidx.compose.animation.core.Animatable(0f)
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                __enter.animateTo(
                    targetValue = 1f,
                    animationSpec = ApMotion.springSpec(
                        __mctx,
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    ),
                )
            }
            // 限高改成「最多」而不是「恰好占 62%」：
            //   · 内容短（两行字 + 一个按钮）时卡片贴着内容收 —— 此前 fillMaxHeight 会把它
            //     撑成一张大半屏的空心大卡，真机上就是「弹窗又大又空、按钮离得老远」；
            //   · 内容长时顶到上限，卡片内的 verticalScroll 接管（scrollable=true 时）。
            val __maxH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp *
                heightRatio
            // fillMaxWidth 在前（与 Card 的默认一致），调用方的 modifier 可以覆盖它
            top.yukonga.miuix.kmp.basic.Card(
                // 2026-11-07：封顶 94% —— 横向不再顶到屏边，缩放调大时也有安全余量。
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    // 横屏/平板下 0.94×屏宽 会变成一张 800dp 级的巨卡（真机横屏实测：一列
                    // 开关被拉成两道相隔 700dp 的边）。封顶 560dp 才是「一列信息」该有的宽度。
                    .widthIn(max = 560.dp)
                    .then(modifier)
                    .heightIn(max = __maxH)
                    .graphicsLayer {
                        val v = __enter.value
                        alpha = 0.6f + 0.4f * v
                        val s = 0.94f + 0.06f * v
                        scaleX = s
                        scaleY = s
                    },
            ) {
                Column(
                    modifier = Modifier
                        .padding(
                            start = padding + __ins.holeLeft,
                            top = padding + __ins.holeTop,
                            end = padding + __ins.holeRight,
                            bottom = padding + __ins.holeBottom,
                        )
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
            // 折叠头按压反馈（成宇 2026-11-12 20:14「让界面看起来更有质感」）：此前裸
            // clickable，按下去既不缩也不震。补按压缩放（ApMotion.pressScale 同口径）+
            // 点击轻震（震动档位关时一声不震），与状态页开关、动作按钮对齐。
            val fgHost = androidx.compose.ui.platform.LocalView.current
            val fgInteraction = remember { MutableInteractionSource() }
            val fgPressed by fgInteraction.collectIsPressedAsState()
            val fgScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (fgPressed) ApMotion.pressScale(fgHost.context) else 1f,
                animationSpec = if (ApMotion.enabled(fgHost.context)) {
                    androidx.compose.animation.core.tween(
                        durationMillis = 110,
                        easing = ApMotion.EnterEasing,
                    )
                } else {
                    androidx.compose.animation.core.tween(0)
                },
                label = "foldGroupPress",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = fgScale; scaleY = fgScale }
                    .clickable(
                        interactionSource = fgInteraction,
                        indication = null,
                        onClick = {
                            com.allperiph.ui.Feedback.tap(fgHost)
                            onToggleOpen()
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 排版口径（2026-11-07）：标题≤2 行；计数回显恒单行。
                    Text(text = title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = countText,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (open) "\u25be" else "\u25b8",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            // 展开/收起动效（成宇 2026-11-12 20:14「优化动效heui界面，让界面看起来更有质感」）：
            // 此前 `if (open)` 硬切 —— 内容瞬间撑开/瞬间消失，行高跳变。改 AnimatedVisibility：
            // 展开从顶向下撑开 + 淡入（进入曲线 150ms），收起向上收拢 + 淡出（退出曲线 150ms）；
            // 设置页动效关闭时 durationMs 返回 0，直接落位。状态页音频方向与设置页分组共用本实现。
            androidx.compose.animation.AnimatedVisibility(
                visible = open,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = ApMotion.durationMs(
                            fgHost.context, 150,
                        ),
                        easing = ApMotion.EnterEasing,
                    ),
                    expandFrom = Alignment.Top,
                ) + androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(
                        durationMillis = ApMotion.durationMs(fgHost.context, 150),
                        easing = ApMotion.EnterEasing,
                    ),
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = ApMotion.durationMs(fgHost.context, 150),
                        easing = ApMotion.ExitEasing,
                    ),
                    shrinkTowards = Alignment.Top,
                ) + androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(
                        durationMillis = ApMotion.durationMs(fgHost.context, 150),
                        easing = ApMotion.ExitEasing,
                    ),
                ),
            ) {
                HorizontalDivider()
                // 2026-11-13 用户截图「未加入菜单的功能」一整组标题互相压字成乱码：
                // AnimatedVisibility 的内容槽是单槽（Box 语义），content 里平铺多行会把
                // 所有行叠在同一格渲染 —— 上一个版本把 `if (open)` 硬切换成展开动效时引入，
                // 同样中招的还有键位映射组（11 行）、字号组（4 行）与状态页音频组（2 行）。
                // 包一层 Column 恢复纵向排列；单行组与外层自带 Column 的组不受影响。
                Column { content() }
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
     * @param tint 非空时用该色作为玻璃的染色（修饰键点亮态）；null = 中性玻璃。
     *   灯效**不再走这里** —— 那是 faceMod/labelMod 的绘制期职责（见下两参），
     *   以前把 rgbFace 的 Transparent 塞进 tint 才会踩「透明底隐形键」的坑（审查 #1）。
     * @param fxMod 调用方算好的按键特效 Modifier（[com.allperiph.ui.compose.keyFx]），
     *   画在键帽内容之上、玻璃高光之下 —— 特效是「按下去的额外反馈」，不该盖掉高光
     * @param faceMod 灯效键面（KeyBox 的 drawBehind）：画在玻璃高光**之下** ——
     *   灯色先落底、玻璃渐变与描边再叠上去，玻璃质感保留
     * @param labelMod 字色层：`graphicsLayer { colorFilter }` 在**绘制期**给字上灯色，
     *   组合期不读相位（皮肤审查 #3；实现见 KeyBox.labelMod）
     */
    @Composable
    fun GlassKeycap(
        modifier: Modifier = Modifier,
        shape: androidx.compose.ui.graphics.Shape,
        label: String,
        /**
         * 键帽副文字（KeyLayouts FULL 的 F 键「F1\n静音」拆出的第二行，2026-11-12 图4
         * 「文字缺失」修复）：非空时在主行下方以 0.6 倍字号渲染 —— 与 KeyBox.capText
         * 同一倍率口径，横屏行高装不下两行全尺寸字，缩副行救回可读性。
         */
        subLabel: String? = null,
        textSize: androidx.compose.ui.unit.TextUnit,
        fg: Color,
        tint: Color?,
        interaction: MutableInteractionSource,
        onClick: () -> Unit,
        fxMod: Modifier = Modifier,
        faceMod: Modifier = Modifier,
        labelMod: Modifier = Modifier,
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
        // 按下：整块再泛白一层（不位移 —— 下沉是机械档的语义，两档必须能一眼分开）
        val pressedWash = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.14f)),
        )
        Box(
            modifier = modifier
                .clip(shape)
                .background(base)
                .then(faceMod)
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
            // 副文字行：与 KeyBox.capText 同口径（主行全尺寸、副行 0.6 倍、各限单行）
            if (subLabel == null) {
                Text(
                    text = label,
                    modifier = labelMod,
                    fontSize = textSize,
                    color = if (tint != null) Color.White else fg,
                    textAlign = TextAlign.Center,
                )
            } else {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        modifier = labelMod,
                        fontSize = textSize,
                        color = if (tint != null) Color.White else fg,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = subLabel,
                        modifier = labelMod,
                        fontSize = textSize * 0.6f,
                        color = if (tint != null) Color.White else fg,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    /**
     * 竖向滚动条（issue#168 2026-11-11「显示不下时加滑动条」）：贴在滚动区左缘或右缘的
     * 细滚动条，thumb 长度按「可视 / 总内容」比例，拖动 thumb 直接驱动 [scrollState]。
     * 内容不超高（maxValue ≤ 0）时整根不画，调用方无需自己判断是否显示。
     *
     * 为什么自己画而不是用 Modifier.verticalScrollbar 之类的现成件：Compose 1.7 没有
     * 稳定的竖向滚动条 API（ScrollbarStyle 只在桌面端），而触控板页快捷键区 / 设备
     * 面板都要「看得见的滑动条」，这里收成一个最小组件供两处共用。
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun VScrollbar(
        scrollState: androidx.compose.foundation.ScrollState,
        modifier: Modifier = Modifier,
        alignEnd: Boolean,
    ) {
        if (scrollState.maxValue <= 0) return
        val density = LocalDensity.current
        BoxWithConstraints(modifier = modifier) {
            val trackPx = with(density) { maxHeight.toPx() }
            val range = scrollState.maxValue.toFloat()
            // thumb 高度 = 轨道 × 可视占比（viewport / (viewport + 可滚范围)）
            val thumbPx = (trackPx * trackPx / (trackPx + range)).coerceAtLeast(24f)
            val yMax = (trackPx - thumbPx).coerceAtLeast(1f)
            Box(
                modifier = Modifier
                    .then(
                        if (alignEnd) Modifier.align(Alignment.CenterEnd)
                        else Modifier.align(Alignment.CenterStart),
                    )
                    .offset { IntOffset(0, ((scrollState.value / range) * yMax).toInt()) }
                    .width(4.dp)
                    .height(with(density) { thumbPx.toDp() })
                    .clip(RoundedCornerShape(2.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.30f))
                    .pointerInput(range, yMax) {
                        // 拖 thumb 换算成滚动量；dispatchRawDelta 不挂协程，拖动零延迟
                        detectDragGestures { change, drag ->
                            change.consume()
                            scrollState.dispatchRawDelta(drag.y * range / yMax)
                        }
                    },
            )
        }
    }

}