package com.allperiph.ui.feature.hotkey

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 快捷键条（Compose 重构线）—— 旧 `HotkeyBoard` 的 Compose 版上半部分。
 *
 * ## 手势口径（与旧版一致）
 *  · **轻点** → 发一次组合键（`HidKeys.combo`：按下修饰键 + 点键 + 全松开）；
 *  · **按住** → 组合键保持按下（`HidKeys.hold`），松手才放（`releaseHold`）。
 *    做 Ctrl+拖动、Shift+连选这类需要「按住不放」的操作时靠它；
 *  · 长按阈值用系统 `longPressTimeout`，不自己拍一个数。
 *
 * 旧版的「长按拖动换位」这里没做：拖拽换位在横向滚动容器里和滚动打架，
 * 换位改到 [HotkeyManagerDialog] 里的上移/下移（列表操作更准，也不误触）。
 */
@Composable
fun HotkeyStrip(
    context: Context,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    tvMode: Boolean = false,
) {
    var revision by remember { mutableIntStateOf(0) }
    val combos = remember(revision, tvMode) {
        if (tvMode) HotkeyStore.loadTv(context) else HotkeyStore.load(context)
    }
    val cs = MiuixTheme.colorScheme

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        itemsIndexed(combos) { _, combo ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceVariant)
                    .pointerInput(combo) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                waitForUpOrCancellation()
                                false
                            } == null
                            if (held) {
                                HidKeys.hold(combo)
                                waitForUpOrCancellation()
                                HidKeys.releaseHold()
                            } else {
                                HidKeys.combo(combo)
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = combo.label, fontSize = 13.sp, color = cs.onSurface)
            }
        }
        item {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.primary.copy(alpha = 0.12f))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            waitForUpOrCancellation()
                            onManage()
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.shell_hotkey_manage),
                    fontSize = 13.sp,
                    color = cs.primary,
                )
            }
        }
    }
}
