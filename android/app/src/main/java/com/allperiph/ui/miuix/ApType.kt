package com.allperiph.ui.miuix

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 字阶令牌（2026-11-14 质感对齐批，DESIGN.md v2「miuix/HyperOS 口径」）：
 *
 * 旧字阶取自 Linear 版界面稿（27/19/17/15/13/12/11.5/10.5 八档），实际使用时漂散成
 * 11 个档位（10.5–26 全线散布、13/12sp 两档占了近半），层级对比被稀释——这就是
 * 「界面平、没质感」的直接原因之一。本对象把全仓字号收敛到 6 档 + 徽标专用 1 档：
 *
 *  · [display]  26sp：页面大标题（原 27 收敛）；
 *  · [title]    20sp：区块/弹窗标题（miuix Title3 同档）；
 *  · [headline] 17sp：行标题、卡内标题（miuix Main/Headline1 同档，存量 17/18/19 收敛）；
 *  · [body]     15sp：正文、按钮、列表行标题（存量 15/16 收敛）；
 *  · [footnote] 13sp：摘要、次要说明（miuix Footnote1 同档，存量 13/14 收敛）；
 *  · [caption]  12sp：状态副行、辅助文字（存量 12）；
 *  · [micro]    11sp：徽标（Pill）、单位（miuix Footnote2 同档，仅小件允许，存量 10.5/11/11.5 收敛）。
 *
 * 规则：
 *  1. **界面层不得再写字号字面量**——新代码一律取 [ApType]；动态字号（键盘按屏宽缩放的
 *     `dens.textSize.sp`、手柄按 `padScale` 缩放）属于尺寸自适应，不受此限，但仍须在
 *     附近注释说明基准档。
 *  2. 行高/字重仍沿用 DESIGN.md v2 的口径（标题 1.2 / 正文 1.4–1.5，中文不加负字距）；
 *     两字重 400/500 不变。
 *  3. 字号的真源是本对象；Views 侧（悬浮球/小部件布局）仍走 dimen 资源，不共用此对象。
 */
object ApType {

    /** 页面大标题（原 27 收敛） */
    val display: TextUnit = 26.sp

    /** 区块/弹窗标题（miuix Title3 同档） */
    val title: TextUnit = 20.sp

    /** 行标题、卡内标题（miuix Main/Headline1 同档） */
    val headline: TextUnit = 17.sp

    /** 正文、按钮、列表行标题 */
    val body: TextUnit = 15.sp

    /** 摘要、次要说明（miuix Footnote1 同档） */
    val footnote: TextUnit = 13.sp

    /** 状态副行、辅助文字 */
    val caption: TextUnit = 12.sp

    /** 徽标（Pill）、单位——仅小件允许（miuix Footnote2 同档） */
    val micro: TextUnit = 11.sp
}
