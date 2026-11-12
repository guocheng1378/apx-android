package com.allperiph.ui.miuix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 区块错落入场（成宇 2026-11-12 21:1x「再攒几笔一起发」，UI/动效质感第三批）。
 *
 * **为什么会有这个文件**：页面级进出场（壳层 pageIn 200ms 上浮淡入）是「整页一起动」，
 * 页内分节卡/键行还是同时亮相，观感上少一层纵深。错落（stagger）让同页的块按序号
 * 依次入场 —— 每块错 40ms，最长 160ms 就全部开跑，不让人等（页面要立刻能用）。
 *
 * ## 口径（同 ApMotion 界面稿）
 *  · 每块：淡入（alpha 0→1）+ 轻微上浮（6dp，界面稿「位移上限 6px」按 dp 计）；
 *  · 时长 150ms（进入曲线），步进延迟 40ms、封顶 160ms；
 *  · 动效关 = 初值直接 1f，不跑任何动画（连 0ms 的空帧都省掉）。
 *
 * ## 与壳层 pageIn 的关系
 * 两层动画叠加（整页浮入 + 页内块错落）是 M1 stagger 的常规做法：页面先给人
 * 「进来了」的整体感，块再依次落位。块用 weight 占位，动画只动 graphicsLayer，
 * 不产生布局跳动，也不会影响触控板/键盘页的手势订阅（没有第二份内容在场）。
 *
 * 用法（照抄 rememberKeyFx 的两段式：composable 驱动进度 + Modifier 画进度）：
 *   val v = rememberEnterStagger(0); Modifier.enterStagger(v)
 * 同一批次里的块按 0、1、2… 传序号；切页/切布局会重建组合，错落自然重放。
 */
internal class EnterValues(val p: Float)

/**
 * 驱动第 [index] 块的入场进度（0 → 1）。
 * 初值在动效关闭时直接取 1f：设置页「动效」关 = 不做过渡，直接落位，
 * 避免 LaunchedEffect 首帧延迟造成的一帧空白。
 */
@Composable
internal fun rememberEnterStagger(index: Int): EnterValues {
    val ctx = LocalContext.current
    val anim = remember { Animatable(if (ApMotion.enabled(ctx)) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (anim.value < 1f) {
            // 步进延迟只算「等位」，不计入动画时长（150ms 那档照走门禁）
            delay((index * 40L).coerceAtMost(160L))
            anim.animateTo(
                1f,
                tween(
                    durationMillis = ApMotion.durationMs(ctx, 150),
                    easing = ApMotion.EnterEasing,
                ),
            )
        }
    }
    return EnterValues(anim.value)
}

/** 把入场进度画到块上（普通 Modifier 扩展，不含 Composable 调用，可放在循环里） */
internal fun Modifier.enterStagger(v: EnterValues): Modifier = graphicsLayer {
    alpha = v.p
    translationY = (1f - v.p) * 6.dp.toPx()
}
