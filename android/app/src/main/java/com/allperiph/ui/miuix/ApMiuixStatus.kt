package com.allperiph.ui.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页左栏「状态块」的 Miuix 版（`page_status.xml` → Compose）。
 *
 * ## 它是什么
 * 设置页左 1/3 栏顶部那一块：状态概览一行（`状态` + 环境摘要）+ 三个传输入口按钮
 * （无线 / 蓝牙 / USB）+ 一句说明 + 未启动时的引导文案。
 *
 * 与 [ApMiuixShell] 同一套路：**数据流单向** —— [StatusFragment] 每秒 refresh 时写
 * [ApMiuixStatusState]，Compose 只读；点击回调走 Activity 原方法（`showCategory`）——
 * 不复制第二份业务判断。
 *
 * ## 为什么可以整块换
 * 这一块在 v196 之后**没有任何子 View 被代码按 id 持有后再改属性**（除了三个按钮的
 * 着色与点击）——着色是纯展示、点击是导航。真正有状态的（三个总开关 `swTransport*`、
 * 模块行 `rowsWifi/Bt/Usb`、链路行 `rowsLinkHost`）都在设置页右栏，本次不动。
 *
 * ## 与原布局的对齐
 * | 原 UI | 现在 |
 * |---|---|
 * | `tvOverall` 17sp 粗体 + 语义色 | Miuix `Text`（同字号 / Bold / 色由状态写入） |
 * | `tvOverallSub` 12sp 单行省略 | 同一行右侧，`weight(1f)` + Ellipsis |
 * | 三个整行宽按钮（开启 = 主色实底 / 关闭 = 中性胶囊） | `TextButton`，`minHeight 48dp`，两套 colors |
 * | 「点按钮进入对应分类」说明行 | 同文案同字号（`hint_transport_enter`） |
 * | 未启动时的 `tvIdleHint`（root 缺失换文案） | `if (!running)` 显示同一字符串 |
 *
 * `boxRunning` / `boxIdleFeatures` 两个**空壳容器**（批C 起内容已搬走、子 View 无 id
 * 故代码不可达）不再需要 —— 原逻辑里它们只是"启动后显示/未启动隐藏"，等价于这里的
 * `running` 一个布尔。
 */

/** 状态块的状态源（Compose `mutableStateOf`，写侧一律在主线程，即 Fragment.refresh）。 */
class ApMiuixStatusState {

    /** 总状态文案（`已启用` / `未启动` / `错误` …），来自 [StatusFragment.stateLabel] */
    var overall by mutableStateOf("")

    /** 总状态颜色（ARGB）；0 = 用主题前景色 */
    var overallArgb by mutableIntStateOf(0)

    /** 环境摘要（root / USB 档位 / 电池白名单 …），来自 Host.getEnvSummary() */
    var summary by mutableStateOf("")

    /** 任一传输开启 = 已启动：驱动未启动提示的显隐 */
    var running by mutableStateOf(false)

    /** 未启动时的引导文案（无 root 时换成 root 引导） */
    var idleHint by mutableStateOf("")

    /** 三个传输入口的当前开关态（只影响按钮着色，点击只导航） */
    var wifiOn by mutableStateOf(false)
    var btOn by mutableStateOf(false)
    var usbOn by mutableStateOf(false)
}

/**
 * 状态块：概览一行 + 三个传输入口 + 说明 + 未启动提示。
 *
 * @param onTransport 点传输按钮 → 右栏切到对应分类（"wifi" / "bt" / "usb"）
 */
@Composable
fun ApMiuixStatusPane(state: ApMiuixStatusState, onTransport: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val overallColor = if (state.overallArgb != 0) Color(state.overallArgb) else colors.onBackground

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        // 状态概览：`状态` + 环境摘要挤一行（原布局也是水平 LinearLayout，右侧 weight=1）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.overall,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = overallColor,
                maxLines = 1,
            )
            Text(
                text = state.summary,
                fontSize = 12.sp,
                color = colors.onSurfaceSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            )
        }

        // 三个入口：竖排、整列宽（横排 weight=1 在 1/3 宽的栏里每个只剩 ~33dp，
        // 「无线 / 蓝牙 / USB」会被省略号切成一个字都不剩 —— 真机截图里就是三个空胶囊）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TransportEntry(R.string.lay_transport_wifi, state.wifiOn) { onTransport("wifi") }
            TransportEntry(R.string.lay_transport_bt, state.btOn) { onTransport("bt") }
            TransportEntry(R.string.lay_transport_usb, state.usbOn) { onTransport("usb") }
        }

        // 说明行：按钮是「入口」，别让人以为点了没反应
        Text(
            text = stringResource(R.string.hint_transport_enter),
            fontSize = 12.sp,
            color = colors.onSurfaceSecondary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        // 未启动：一句引导（无 root 时是 root 引导，文案由 Fragment 决定）
        if (!state.running) {
            Text(
                text = state.idleHint,
                fontSize = 12.sp,
                color = colors.onSurfaceSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * 一个传输入口按钮。
 *
 * 开启 = 主色实底 + onPrimary 文字；关闭 = 浅底（primaryContainer）+ 主色文字。
 * 原版用的是描边胶囊 drawable（`bg_btn_ghost`），Miuix `TextButton` 没有描边参数 ——
 * 差异仅在此处（浅底 vs 描边），都在同一套语义色里，观感一致。
 */
@Composable
private fun TransportEntry(labelRes: Int, on: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val buttonColors = if (on) {
        ButtonDefaults.textButtonColorsPrimary()
    } else {
        ButtonDefaults.textButtonColors(color = colors.primaryVariant, textColor = colors.primary)
    }
    TextButton(
        text = stringResource(labelRes),
        onClick = onClick,
        colors = buttonColors,
        // 可点元素下限 48dp（UI 规范第 3 条）；左右内衬收窄，窄栏里标签才排得下
        minHeight = 48.dp,
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}