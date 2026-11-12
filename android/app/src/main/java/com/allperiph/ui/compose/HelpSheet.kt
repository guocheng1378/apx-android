package com.allperiph.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.ApMotion
import com.allperiph.ui.miuix.ApMiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 完整说明（帮助与说明）。
 *
 * **为什么会有这个文件**：界面层迁 Compose 时，旧设置页里「帮助与说明」那一层整块丢了 ——
 * `strings.xml` 里 `ui_help_*` 十几个键的文案都还在，代码里却一处引用都没有，
 * 用户看到的是「主页什么说明都没有」。本轮把它补回：主页给「三步上手」摘要 + 本页入口，
 * 本页按「三步上手 / 项目说明 / 链路与延迟 / 手势与操作 / 功能说明 / 常见问题 / 开关与日志」
 * 七段排开，**文案全部复用既有 strings**，不新造一套口径。
 *
 * v223.29.73（UI 审查发现 14）：七段长文（含新增 FAQ⑤ Mac 配件授权）原来全部塞进
 * 一个 0.82 高的滚动弹窗，没有目录，找某一段要一直划 —— 改成**分段页签**：
 * 顶部横滑页签选段，正文只渲染当前段；段标题仍复用既有 strings，不新造口径。
 */
@Composable
fun ApHelpSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // 七段定义：标题即页签文案；正文复用原 strings（功能说明段与旧版一样四条拼接）。
    val sections = remember {
        listOf(
            ctx.getString(R.string.ui_help_title_howto) to
                ctx.getString(R.string.ui_help_howto),
            ctx.getString(R.string.ui_help_title_project) to
                ctx.getString(R.string.ui_help_project),
            ctx.getString(R.string.ui_help_title_link) to
                ctx.getString(R.string.ui_help_link),
            ctx.getString(R.string.ui_help_title_gesture) to
                ctx.getString(R.string.ui_help_gesture),
            ctx.getString(R.string.ui_help_title_features) to listOf(
                ctx.getString(R.string.ui_help_cursor_lock),
                ctx.getString(R.string.ui_help_gesture_shield),
                ctx.getString(R.string.ui_help_localsend),
                ctx.getString(R.string.ui_help_ball),
            ).joinToString("\n"),
            ctx.getString(R.string.ui_help_title_faq) to
                ctx.getString(R.string.ui_help_faq),
            ctx.getString(R.string.ui_help_title_switch) to
                ctx.getString(R.string.ui_help_export_log),
        )
    }
    var sec by remember { mutableIntStateOf(0) }
    ApMiuixTheme {
        // scrollable = false：本弹窗内部用 weight(1f) + 自己的 verticalScroll 分区
        // （标题/页签/正文滚动/底部按钮四段），外层再包一层滚动会让 weight 在无界高度里失效。
        ApComponents.DialogCard(onDismiss = onDismiss, heightRatio = 0.82f, scrollable = false) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(text = ctx.getString(R.string.ui_main_cat_help), fontSize = 18.sp)
                // 分段页签（横滑放得下七段）：当前段高亮，点一下只换正文
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEachIndexed { i, (title, _) ->
                        ApComponents.TabChip(
                            text = title,
                            selected = i == sec,
                            onClick = { sec = i },
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    // 正文段切换动效（成宇 2026-11-12 20:14「优化动效heui界面，让界面看起来更有质感」）：
                    // 此前点页签后正文硬切，观感生硬。改 AnimatedContent：新段淡入并轻微上滑
                    // （进入曲线 150ms），旧段淡出（退出曲线 150ms）—— 滑距取自身高度的 1/8，
                    // 轻微即可。容器在 verticalScroll 里高度无界，正文各段长短不一，
                    // 尺寸变化用 snap 瞬落（默认 SizeTransform 走弹簧，与「禁弹簧禁过冲」
                    // 口径冲突，故显式给 snap 规格）—— 只过渡淡入淡出与位移，高度不逐帧动画。
                    // 动效关闭时 durationMs 返回 0，切换直接落位。
                    AnimatedContent(
                        targetState = sec,
                        transitionSpec = {
                            if (ApMotion.enabled(ctx)) {
                                (
                                    fadeIn(
                                        tween(
                                            durationMillis = ApMotion.durationMs(ctx, 150),
                                            easing = ApMotion.EnterEasing,
                                        ),
                                    ) + slideInVertically(
                                        tween(
                                            durationMillis = ApMotion.durationMs(ctx, 150),
                                            easing = ApMotion.EnterEasing,
                                        ),
                                    ) { it / 8 }
                                    ) togetherWith (
                                    fadeOut(
                                        tween(
                                            durationMillis = ApMotion.durationMs(ctx, 150),
                                            easing = ApMotion.ExitEasing,
                                        ),
                                    ) + slideOutVertically(
                                        tween(
                                            durationMillis = ApMotion.durationMs(ctx, 150),
                                            easing = ApMotion.ExitEasing,
                                        ),
                                    ) { -it / 8 }
                                    )
                            } else {
                                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            }
                        },
                        sizeTransform = { _, _ ->
                            SizeTransform(clip = true) { _, _ -> snap() }
                        },
                        label = "helpBody",
                    ) { s ->
                        HelpBlock(sections[s].first, sections[s].second)
                    }
                }
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_help_close),
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(text = title, fontSize = 15.sp)
        Text(
            text = body,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
