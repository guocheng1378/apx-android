package com.allperiph.ui.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 信息面「列表-详情」双栏壳（平板适配 §6.2，2026-11-11）。
 *
 * 为什么抽它：设置页此前自己拼 Row（左分类列 + 右详情），ROADMAP §6.2 要把这套
 * 「左列表 / 右详情」推广到状态页等信息面页面——各页再各拼一份，栏宽、留白、
 * 居中口径必然漂移。收成一个骨架组件：**只管排版不管内容**，左右两栏都是调用方
 * 给的 Composable，滚动与限宽由页面自己决定（右栏内容高度差异大，不宜替页面假设）。
 *
 * 档位约定（§6.1）：调用方先用 [rememberApAdaptiveLayout] 判断 twoPane（≥600dp），
 * 只有双栏分支才调用本壳；窄屏单栏不经过它。栏宽默认 180dp 是设置页迁移口径，
 * 状态页同值；若后续某页需要不同栏宽，走参数而不是另写壳。
 *
 * 左栏内容**竖直居中**：列表类内容通常只有 3~5 项（分类/模块），贴顶会在长屏上
 * 留一整段空档，与设置页迁移前的观感一致（issue#168 第 3 条同口径）。
 */
@Composable
fun ApTwoPaneScaffold(
    /** 左栏（列表/导航/开关列表），固定 [leftWidth] 宽、内容竖直居中 */
    left: @Composable () -> Unit,
    /** 右栏（详情/诊断），吃掉剩余宽度；内部限宽与滚动由调用方排版 */
    right: @Composable () -> Unit,
    /** 传入 weight(1f) 等外层约束（如 ColumnScope.weight）由调用方拼接 */
    modifier: Modifier = Modifier,
    /** 左栏宽度；180dp = 设置页迁移口径 */
    leftWidth: Dp = 180.dp,
    /**
     * 左栏**按比例**占宽（issue#168 2026-11-12「状态页横屏左右分栏，左侧过窄，应该分为
     * 左右各二分之一」）：非空时左栏走 `weight(leftWeight)`（转屏按新窗宽自动重算），
     * 与 [leftWidth] 二选一 —— 状态页传 1f（左 1/2 + 右 weight(1f) = 各半），
     * 设置页 / 文件页不传，保持 180dp 固定栏的旧观感。
     */
    leftWeight: Float? = null,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = (if (leftWeight != null) Modifier.weight(leftWeight) else Modifier.width(leftWidth))
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) { left() }
        // 右栏 fillMaxHeight 让内部 Box(fillMaxSize) 的调用方拿到完整高度；
        // 权重吃掉左栏之外的剩余宽度
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { right() }
    }
}
