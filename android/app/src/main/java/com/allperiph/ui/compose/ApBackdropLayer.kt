package com.allperiph.ui.compose

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.allperiph.ui.ApBackdrop
import com.allperiph.ui.ApPalette
import com.allperiph.ui.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主壳背景（**Compose 侧的唯一一份**）。
 *
 * ## 为什么需要它
 * 设置页「背景图 / 预设底色」这套沿用的是 Views 时代的 [Backdrop]，它只被
 * `TouchpadActivity` / `RemoteActivity` 这两个自绘页面调用；而迁 Compose 之后的主壳
 * （`ApShellActivity`）根 Box 画的是 `MiuixTheme.colorScheme.background` ——
 * 用户在设置页选了背景图，**主壳四个页一律看不到**（成宇 2026-11-03 反馈
 * 「设置里的背景图也要挪到主题外观分组」时暴露：入口挪了位置，但底下那层根本没接）。
 * 这里把同一条偏好接到 Compose 上，口径与 [Backdrop] 完全一致：
 * **自定义图片 > 预设底色 > 主题底色**。
 *
 * ## 返回 Modifier 而不是包一层 Box
 * 早先的写法是在壳里再套一层 `Box` 专门画背景 —— 那会让主壳根节点多一层，
 * 所有既有注释里的层级说明当场失效（并且那一版改到一半就卡在缩进上）。
 * 这里给的是 `Modifier.drawBehind`：调用方把它 `.then(...)` 进**已有的**根 Box 即可，
 * `drawBehind` 天然画在子内容之下，层级语义不变。
 *
 * ## 动态背景（成宇 2026-11-03「背景图可以动态的吗」）
 * 开了 [ApBackdrop.bgAnim] 时：
 *   · 有图 → 极缓慢的放大 + 平移（26s 一个来回，放大 4%~9%，永不留边）；
 *   · 纯色 → 底色之上叠一层强调色柔光，位置随时间漂移（纯色本身缩放是看不出动的）。
 * 关掉立即回到完全静止的状态（相位不再参与绘制）。
 *
 * ⚠️ 解码走 `Dispatchers.IO` 且只做一次（按 URI 记忆）：相册里的大图不能在
 * 组合期同步解码，否则首帧会卡住。
 */
@Composable
internal fun rememberApBackdrop(ctx: Context, fallback: Color): Modifier {
    // ⚠️ 以 ApBackdrop.rev 为 key：**设置页改完偏好要当场生效**。
    // 此前这几处都是不带 key 的 `remember {}`（只读一次偏好），于是选图 / 换底色 /
    // 开关动态背景全部要重启 App 才看得见 —— 2026-11-04 成宇的原话就是
    // 「背景图选择后没反映，要重启」。rev 在每次 setBg / setBgImage / setBgAnim 时 ++，
    // 这里随之重新读偏好并重新解码。
    val rev = ApBackdrop.rev
    val uri = remember(rev) { ApBackdrop.bgImage(ctx).orEmpty() }
    val animated = remember(rev) { ApBackdrop.bgAnim(ctx) }
    val base = remember(rev, uri) {
        val preset = ApBackdrop.BG_PRESETS.firstOrNull { it.id == ApBackdrop.bgId(ctx) }
        if (preset != null && preset.color != 0) Color(preset.color) else fallback
    }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = if (uri.isBlank()) {
            null
        } else {
            // 解码口径收在 BackdropDecode 一处（原先这里与 Views 侧 Backdrop 各一份，
            // 两份的采样条件都是「折半后仍不小于目标才继续折」，等效于对大多数照片
            // 完全不采样 —— 就是成宇 2026-11-04 报的「加载图片卡顿」）。
            withContext(Dispatchers.IO) {
                com.allperiph.ui.BackdropDecode.decode(ctx, uri)
            }?.asImageBitmap()
        }
    }
    // ⚠️ 两个 remember 都**无条件**调用：`rememberInfiniteTransition` 一旦放进
    // 「关掉动态就不走」的分支里，槽位会随开关跳变（本仓在 MiuixTokens 上踩过同类问题）。
    // 关闭档不额外建动画的开销由 phase 是否参与绘制来保证 —— 见下面 animated 的判断。
    val transition = rememberInfiniteTransition(label = "apBg")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "apBgPhase",
    )
    val glow = remember { Color(ApPalette.primary(ctx)) }
    // 关掉动态：只画一帧静态底，phase 不参与（绘制里 animated=false 直接走静止分支）
    return Modifier.drawBehind {
        paintBackdrop(bitmap, base, glow, if (animated) phase else 0f, animated)
    }
}

/** 画背景：有图铺图（可动），无图铺底色（可动时叠柔光）。 */
private fun DrawScope.paintBackdrop(
    bitmap: ImageBitmap?,
    base: Color,
    glow: Color,
    phase: Float,
    animated: Boolean,
) {
    if (bitmap == null) {
        drawRect(base)
        if (!animated) return
        // 纯色底看不出缩放，用一层柔光的位置漂移表达「在动」：
        // 半径给到画布对角线的 1.4 倍，光晕只占 10% 透明度，观感是极缓慢的明暗推移。
        val r = (size.width + size.height) * 0.7f
        val cx = size.width * (0.30f + 0.40f * phase)
        val cy = size.height * (0.28f + 0.34f * (1f - phase))
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.10f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                radius = r,
            ),
        )
        return
    }
    // 图：放大一点再平移，位移不超过多出来的那一圈，保证任何时候都不留边
    val k = if (animated) 1.05f + 0.04f * phase else 1f
    val w = (size.width * k).toInt()
    val h = (size.height * k).toInt()
    val left = -((w - size.width) * phase).toInt()
    val top = -((h - size.height) * (1f - phase)).toInt()
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(w, h),
    )
}
