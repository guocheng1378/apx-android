package com.allperiph.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.allperiph.ui.KeyPref

/**
 * 按键特效（成宇 2026-11-04「键盘的特效做几个」）。
 *
 * ## 为什么单独一个文件
 * 「按下时该有什么视觉反馈」此前散落在三处（主屏键盘页的 `KeyBox` \/ `ModKey`，
 * 操控面 `TouchpadActivity` 的键帽），各写一份必然分叉；而特效档位是**全局偏好**
 * （[KeyPref.effectIndex]），任何一处自己实现都会与设置页显示的选择不一致。
 * 所以这里收口成两件事：
 *   · [rememberKeyFx]  驱动一次按压进度（0 → 1），档位由调用方传入；
 *   · [keyFx]          把进度画出来（涟漪 \/ 高光 \/ 弹跳）。
 *
 * ## 四档
 *   · 0 关闭      —— 不画任何额外效果（调用方保留原来的水波纹，观感不变）；
 *   · 1 涟漪      —— 按下从键面中心扩散一圈强调色涟漪；
 *   · 2 高光      —— 按下整键提亮 + 一圈强调色描边，抬手即散；
 *   · 3 弹跳      —— 按下键帽轻微缩小再回弹（正弦半周期，不会来回抖）。
 *
 * ## 与「动效」档位的关系
 * 设置页的「动效」为**关闭**（[com.allperiph.ui.ApTiming.motionIndex] == 0）时，
 * 动画只做一帧（时长 0ms）—— 直接用终态，符合该档位「不做过渡」的既有语义
 * （见 `ApMotion` 的说明：关闭 = 不带过渡，而不是把弹簧调快）。
 */
internal class KeyFxValues(val fx: Int, val p: Float) {
    /** 是否有可画的东西（关闭档或进度为 0 时为 false，调用方可据此跳过额外分支） */
    val active: Boolean get() = fx != KeyPref.FX_NONE && p > 0f
}

/**
 * 驱动一次按压进度；抬手（或关闭档）回到 0。
 *
 * @param fx **档位由调用方传入**而不是在这里现读偏好：键盘页一次组合要给几十个键帽
 *   各算一份特效，每个键都去 `getSharedPreferences` 读一遍纯属浪费；而且调用方通常
 *   已经读好了档位（避免出现「设置页显示涟漪、键帽画高光」这种双源分叉）。
 */
@Composable
internal fun rememberKeyFx(pressed: Boolean, fx: Int): KeyFxValues {
    val ctx = LocalContext.current
    val anim = remember { Animatable(0f) }
    LaunchedEffect(pressed, fx) {
        if (pressed && fx != KeyPref.FX_NONE) {
            anim.snapTo(0f)
            val ms = if (fx == KeyPref.FX_BOUNCE) 220 else 420
            val ms2 = if (com.allperiph.ui.ApTiming.motionIndex(ctx) == 0) 0 else ms
            anim.animateTo(1f, tween(durationMillis = ms2))
        } else {
            anim.snapTo(0f)
        }
    }
    return KeyFxValues(fx, if (fx == KeyPref.FX_NONE) 0f else anim.value)
}

/**
 * 把 [values] 画到键帽上（普通 Modifier 扩展，不含 Composable 调用，可放在条件分支里）。
 *
 * @param accent 强调色：涟漪与高光的描边取它（与主题 \/ 模块皮肤同一取色口径）
 */
internal fun Modifier.keyFx(values: KeyFxValues, accent: Color): Modifier {
    if (values.fx == KeyPref.FX_NONE) return this
    var m = this
    if (values.fx == KeyPref.FX_BOUNCE) {
        val s = 1f - 0.08f * kotlin.math.sin((kotlin.math.PI * values.p).toFloat())
        m = m.graphicsLayer(scaleX = s, scaleY = s)
    }
    return m.drawWithContent {
        drawContent()
        if (values.p > 0f) {
            when (values.fx) {
                KeyPref.FX_RIPPLE -> {
                    val r = size.minDimension * (0.30f + 0.85f * values.p)
                    drawCircle(
                        color = accent.copy(alpha = 0.45f * (1f - values.p)),
                        radius = r,
                        center = center,
                    )
                }
                KeyPref.FX_HIGHLIGHT -> {
                    drawRect(color = Color.White.copy(alpha = 0.30f * (1f - values.p)))
                    drawRect(
                        color = accent.copy(alpha = 0.85f * (1f - values.p)),
                        style = Stroke(width = 2f),
                    )
                }
                else -> Unit
            }
        }
    }
}