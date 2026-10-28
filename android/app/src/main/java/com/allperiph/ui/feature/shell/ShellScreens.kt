package com.allperiph.ui.feature.shell

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.feature.status.StatusScreen
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text

/**
 * 新外壳里的状态页（触控板页在 ui/feature/touchpad，设置页在 ui/feature/settings）。
 *
 * **状态页已经是真的**（旧 StatusFragment 的 Compose 版，见 [StatusScreen]）；
 * 键盘页仍在旧实现里，按阶段继续迁（迁完一页删一页旧实现，不留双轨）。
 */

/** 状态页：真数据（AgentController / EnvHolder / Uplink），见 StatusProvider。 */
@Composable
fun ShellStatusScreen(context: Context, modifier: Modifier = Modifier) {
    StatusScreen(context = context, modifier = modifier)
}
