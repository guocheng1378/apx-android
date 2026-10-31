package com.allperiph.tv.ui

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
 *  · **焦点必须一眼可见**：旧实现用 `TvUi.focusBg` 在选中时换底+描边；这里用
 *    `onFocusChanged` + 描边色切换达到同一目的。DPAD 上/下/左/右要能走通，
 *    所以每个动作卡片都是 `focusable()` + `clickable()`；
 *  · **远距离可读**：字号沿用 [TvUi.Type] 的梯度（DISPLAY/TITLE/BODY/CAPTION），
 *    不因为换成 Compose 就把字缩小。
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
        if (state.live.isNotBlank()) {
            Text(
                text = state.live,
                fontSize = TvUi.Type.CAPTION.sp,
                color = Color(TvUi.Pal.accent),
            )
        }

        // —— 动作：DPAD 可聚焦，焦点换底 + 描边 ——
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
    Text(
        text = text,
        fontSize = TvUi.Type.ACTION.sp,
        color = Color(if (focused) TvUi.Pal.text else TvUi.Pal.textDim),
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .background(
                color = Color(if (focused) TvUi.Pal.cardFocus else TvUi.Pal.card),
                shape = shape,
            )
            .border(
                width = 3.dp,
                color = Color(if (focused) TvUi.Pal.accent else Color.Transparent),
                shape = shape,
            )
            .defaultMinSize(minWidth = ActionMinWidth)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/** 动作卡片最小宽度：远距离也够点得准（旧实现 minimumWidth = 64dp） */
private val IntrinsicWidthMin = 88.dp

private var appCtx: android.content.Context? = null

/** 供 [TvHome] 内部取字符串（Composable 里不方便拿 Context 时用，由 Activity 注入） */
internal fun s(ctx, resId: Int, vararg args: Any): String {
    val ctx = appCtx ?: return ""
    return if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
}

internal fun bindTvHomeContext(ctx: android.content.Context) {
    appCtx = ctx.applicationContext
}
