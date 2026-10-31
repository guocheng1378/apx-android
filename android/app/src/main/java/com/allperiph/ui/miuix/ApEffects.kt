package com.allperiph.ui.miuix

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 视觉效果层（2026-11-01 起的统一入口）。
 *
 * 这里只做**视觉**：模糊、按压回弹、过渡节奏。动效开关一律走 [ApMotion]，
 * 「关闭」档位下所有效果退化为瞬时，不做"更快"处理。
 */

/** 顶栏玻璃效果是否可用：Haze 依赖 RuntimeShader，Android 13（API 33）起才有 */
fun hazeSupported(): Boolean = Build.VERSION.SDK_INT >= 33

/**
 * 顶栏渐进模糊：满强度模糊 → 底边渐隐，内容从栏下滚过。
 * 不能用的机器退化为纯色底（不是"不显示顶栏"）。
 */
@Composable
fun ApGlassTopBar(
    title: String,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
) {
    val surface = MiuixTheme.colorScheme.surface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        blurRadius = 15.dp
                        // 模糊层必须不透明，否则下层卡片硬边缘会从模糊里透出来
                        backgroundColor = surface
                        noiseFactor = 0f
                        tints = listOf(HazeTint(surface.copy(alpha = 0.3f)))
                        progressive = HazeProgressive.Brush(
                            Brush.verticalGradient(
                                0f to Color.Black,
                                0.55f to Color.Black,
                                1f to Color.Black.copy(alpha = 0f),
                            ),
                        )
                    }
                } else {
                    Modifier.background(surface)
                },
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
    }
}

/** 内容容器：被顶栏模糊采样（hazeSource），没有模糊能力时是空操作 */
fun Modifier.apHazeSource(state: HazeState?): Modifier =
    if (state != null) this.hazeSource(state) else this

/** 记住一个仅在支持模糊时创建的状态，避免不支持的机器白建对象 */
@Composable
fun rememberApHazeState(): HazeState? {
    if (!hazeSupported()) return null
    return rememberHazeState()
}

/**
 * 按压回弹：按下缩到 [ApMotion.pressScale]，松手弹回。
 * 动效关闭档位下 pressScale = 1f，等于没有缩放 —— 与全仓"关就是不动"一致。
 */
@Composable
fun Modifier.apPressEffect(ctx: Context): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = if (pressed) ApMotion.pressScale(ctx) else 1f
    return this.scale(scale)
}
