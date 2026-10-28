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
 * 新外壳里的首页与状态页（设置页已独立成 ui/feature/settings/SettingsPage）。
 *
 * **状态页已经是真的**（旧 StatusFragment 的 Compose 版，见 [StatusScreen]）；
 * 首页还是占位 —— 触控板页 / 键盘页按阶段逐个迁，迁完一页删一页旧实现（不留双轨）。
 */

@Composable
fun ShellHomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SmallTitle(text = stringResource(R.string.shell_home_title))
        Text(text = stringResource(R.string.shell_home_placeholder), fontSize = 13.sp)
    }
}

/** 状态页：真数据（AgentController / EnvHolder / Uplink），见 StatusProvider。 */
@Composable
fun ShellStatusScreen(context: Context, modifier: Modifier = Modifier) {
    StatusScreen(context = context, modifier = modifier)
}
