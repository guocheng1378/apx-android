package com.allperiph.ui.miuix

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 *
 * ⚠️ 2026-11-04：`apPressEffect` 已删除（它是死功能，见 git 历史里的 KDoc 原文）：
 * 它自己 `remember { MutableInteractionSource() }` 却**没把这个 source 交给任何
 * `clickable`** —— 调用方的 `clickable {}` 会另建自己的 source，于是它的
 * `collectIsPressedAsState()` 恒为 false、`scale` 恒 1f，**按下永不缩放**，
 * 而 KDoc 声称「按下缩到 pressScale，松手弹回」。
 * 这与 `KeyboardPage` 里那段审查注释（「不把 source 交给 clickable → 进度恒 0」）
 * 是**同一个根因** —— 它犯的正是它自己写出的教训。全仓零调用（已 search 确认）。
 * 按压回弹的正确实现走 [ApPress.apPress]（把同一个 source 交给 clickable）。
 */

/** 顶栏玻璃效果是否可用：Haze 依赖 RuntimeShader，Android 13（API 33）起才有 */
fun hazeSupported(): Boolean = Build.VERSION.SDK_INT >= 33

/**
 * 顶栏渐进模糊：满强度模糊 → 底边渐隐，内容从栏下滚过。
 * 不能用的机器退化为纯色底（不是"不显示顶栏"）。
 *
 * [title] 可空：横屏时顶栏按设计盖住状态栏（topPad = 0），再画标题会和系统时间/电量
 * 叠字（成宇 2026-11-02「横屏时顶栏的标题不要」）—— 横屏传 null，只留玻璃底。
 */
@Composable
fun ApGlassTopBar(
    title: String?,
    onDeviceClick: (() -> Unit)? = null,
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
                        blurRadius = 18.dp
                        // 液态玻璃：底色必须**半透明**，完全不透明时顶栏就是一块纯色板，
                        // 下层内容滚过去看不出玻璃感（用户反馈「上栏不透明」就是这个）。
                        // 代价是下层卡片的硬边缘会在模糊里留一点形，靠 0.55 的底色 + tint 压住。
                        backgroundColor = surface.copy(alpha = 0.55f)
                        noiseFactor = 0.02f
                        tints = listOf(HazeTint(surface.copy(alpha = 0.18f)))
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
        // 受控设备按钮（审查条 1.7）：`onDeviceClick` 这个参数从加进签名起就没被任何
        // 可点区域消费过 —— 函数体只有玻璃底与可选标题，于是主壳内没有「切换受控设备」
        // 的入口（只剩 `EXTRA_PICK_DEVICE` 启动参数与悬浮球面板）。这里补渲染：
        // 有受控目标就显示它的名字，否则显示「选择设备」，点击回调用它拉起选择器。
        if (onDeviceClick != null) {
            val label = com.allperiph.wireless.ControlTarget.label
                ?: com.allperiph.wireless.ControlTarget.host
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { onDeviceClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label ?: "选择设备",
                    fontSize = ApType.footnote,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        // 标题为空（横屏盖状态栏）就不画文字，玻璃底照常模糊状态栏那一条
        if (title != null) {
            Text(
                text = title,
                fontSize = ApType.title,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
            )
        }
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