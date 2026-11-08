package com.allperiph.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import com.allperiph.tv.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

/** 电视端主屏的界面状态（由 Activity 的 2s 轮询与后台 IO 写入） */
data class TvHomeState(
    val status: String = "",
    val ability: String = "",
    val live: String = "",
    val addr: String? = null,
    val files: List<File> = emptyList(),
    val accepting: Boolean = false,
)

/**
 * 电视端主屏（阶段 5）。
 *
 * 结构与旧 [MainActivity.buildUi] 一一对应：
 * 标题 → 一句话说明 → 当前状态 → 本机地址与连法 → 注入能力 → 对端操作实时提示 →
 * 五个动作 → 遥控器怎么用 → 收到的文件 → 输入提示。
 *
 * ## 电视端的两条硬要求
 *  · **焦点必须一眼可见**：焦点态 = 3dp 主色外描边 + 背景 [TvUi.Pal.focusBg]（#20242D），
 *    150ms 淡切（界面稿 06）。DPAD 上/下/左/右要能走通，
 *    所以每个动作卡片都是 `focusable()` + `clickable()`；
 *  · **远距离可读**：字号沿用 [TvUi.Type] 的梯度（DISPLAY/TITLE/BODY/CAPTION），
 *    不因为换成 Compose 就把字缩小。
 *
 * 2026-11-09 界面稿补充：对端操作实时提示（信息条）5 秒后自动收起，200ms 淡出。
 *
 * 未迁移：RemoteInputOverlay（仍是 View 浮层，画在 ComposeView 之上）、TvOverlay 光标。
 */
@Composable
fun TvHome(
    ctx: android.content.Context,
    state: TvHomeState,
    onFiles: () -> Unit,
    onScreen: () -> Unit,
    onSelfCheck: () -> Unit,
    onAccessibility: () -> Unit,
    onToggleService: () -> Unit,
) {
    val pad = 20.dp
    // 信息条：新提示出现后 5 秒自动收起（界面稿 06 建议值），淡入淡出 200ms。
    var liveVisible by remember(state.live) { mutableStateOf(state.live.isNotBlank()) }
    LaunchedEffect(state.live) {
        liveVisible = state.live.isNotBlank()
        if (liveVisible) {
            delay(5_000)
            liveVisible = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = pad, end = pad, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = s(ctx, R.string.app_name),
            fontSize = TvUi.Type.DISPLAY.sp,
            color = Color(TvUi.Pal.accent),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = s(ctx, R.string.tv_tagline),
            fontSize = TvUi.Type.CAPTION.sp,
            color = Color(TvUi.Pal.textDim),
        )
        Text(
            text = state.status,
            fontSize = TvUi.Type.TITLE.sp,
            color = Color(TvUi.Pal.text),
        )
        state.addr?.let { addr ->
            Text(
                text = s(ctx, R.string.tv_addr, addr),
                fontSize = TvUi.Type.CAPTION.sp,
                color = Color(TvUi.Pal.textDim),
            )
            Text(
                text = s(ctx, R.string.tv_addr_hint),
                fontSize = TvUi.Type.MICRO.sp,
                color = Color(TvUi.Pal.textWeak),
            )
        }
        Text(
            text = state.ability,
            fontSize = TvUi.Type.BODY.sp,
            color = Color(TvUi.Pal.text),
            fontWeight = FontWeight.Bold,
        )
        AnimatedVisibility(
            visible = state.live.isNotBlank() && liveVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Text(
                text = state.live,
                fontSize = TvUi.Type.CAPTION.sp,
                color = Color(TvUi.Pal.accent),
            )
        }

        // —— 动作：DPAD 可聚焦，焦点换底 + 主色描边（150ms 淡切，界面稿 06）——
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvAction(s(ctx, R.string.tv_action_files), onFiles)
            TvAction(s(ctx, R.string.tv_action_screen), onScreen)
            TvAction(s(ctx, R.string.tv_action_selfcheck), onSelfCheck)
            TvAction(s(ctx, R.string.tv_action_a11y), onAccessibility)
            TvAction(
                s(ctx, 
                    if (state.accepting) R.string.tv_accept_off else R.string.tv_accept_on,
                ),
                onToggleService,
            )
        }

        Text(
            text = s(ctx, R.string.tv_hint),
            fontSize = TvUi.Type.MICRO.sp,
            color = Color(TvUi.Pal.textDim),
        )
        Text(
            text = s(ctx, R.string.tv_files_title),
            fontSize = TvUi.Type.TITLE.sp,
            color = Color(TvUi.Pal.text),
            fontWeight = FontWeight.Bold,
        )
        if (state.files.isEmpty()) {
            Text(
                text = s(ctx, R.string.tv_files_empty),
                fontSize = TvUi.Type.MICRO.sp,
                color = Color(TvUi.Pal.textDim),
            )
        } else {
            state.files.forEach { f ->
                Text(
                    text = "  ${f.name}（${TvFiles.sizeText(f.length())}）",
                    fontSize = TvUi.Type.MICRO.sp,
                    color = Color(TvUi.Pal.textDim),
                )
            }
        }
        Text(
            text = s(ctx, R.string.tv_ime_hint),
            fontSize = TvUi.Type.MICRO.sp,
            color = Color(TvUi.Pal.textWeak),
        )
    }
}

@Composable
private fun TvAction(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvUi.Radius.CARD.dp)
    // 焦点三态（背景 / 环 / 文字）都 150ms 淡切；背景用暗档 #20242D，不再整块变蓝。
    val bg by animateColorAsState(
        targetValue = Color(if (focused) TvUi.Pal.focusBg else TvUi.Pal.card),
        animationSpec = tween(150),
        label = "focusBg",
    )
    val ring by animateColorAsState(
        targetValue = if (focused) Color(TvUi.Pal.accent) else Color.Transparent,
        animationSpec = tween(150),
        label = "focusRing",
    )
    val fg by animateColorAsState(
        targetValue = Color(if (focused) TvUi.Pal.text else TvUi.Pal.textDim),
        animationSpec = tween(150),
        label = "focusFg",
    )
    Text(
        text = text,
        fontSize = TvUi.Type.ACTION.sp,
        color = fg,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .background(color = bg, shape = shape)
            .border(width = 3.dp, color = ring, shape = shape)
            .defaultMinSize(minWidth = IntrinsicWidthMin)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/** 动作卡片最小宽度：远距离也够点得准（旧实现 minimumWidth = 64dp） */
private val IntrinsicWidthMin = 88.dp

private var appCtx: android.content.Context? = null

/** 供 [TvHome] 内部取字符串（Composable 里不方便拿 Context 时用，由 Activity 注入） */
internal fun s(ctx: android.content.Context, resId: Int, vararg args: Any): String {
    val base = appCtx ?: ctx
    return if (args.isEmpty()) base.getString(resId) else base.getString(resId, *args)
}

internal fun bindTvHomeContext(ctx: android.content.Context) {
    appCtx = ctx.applicationContext
}
