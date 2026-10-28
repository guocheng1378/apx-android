package com.allperiph.ui.feature.shell

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.ThemePref
import com.allperiph.ui.feature.status.StatusScreen
import com.allperiph.ui.miuix.MiuixForm
import com.allperiph.ui.miuix.formIndexOf
import com.allperiph.ui.miuix.formOverrideOf
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * 新外壳的三个页面。
 *
 * **状态页已经是真的**（旧 StatusFragment 的 Compose 版，见 [StatusScreen]）；
 * 首页与设置页还是占位 —— 触控板页 / 键盘页 / 设置分类页按阶段逐个迁，
 * 迁完一页删一页旧实现（不留双轨）。
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

/**
 * 设置页（新外壳这一版先放两个**全局**开关）：
 *  · 主题：跟随系统 / 浅色 / 深色 —— 走既有 [ThemePref]（与旧设置页同一份偏好），
 *    改完重建 Activity（明暗是 values/values-night 资源切换，重建才生效）；
 *  · 界面形态：跟随窗口宽度自动判定，或手动钉死（真机验收各形态时用）。
 *
 * 其余 46 项设置随「分类 → 偏好行」数据驱动重建（见 docs/COMPOSE-REFACTOR.md §3）。
 */
@Composable
fun ShellSettingsScreen(
    context: Context,
    modifier: Modifier = Modifier,
    formOverride: MiuixForm?,
    onFormOverride: (MiuixForm?) -> Unit,
) {
    val themeMode = remember { ThemePref.get(context) }
    val formLabels = listOf(
        stringResource(R.string.form_follow),
        stringResource(R.string.form_phone),
        stringResource(R.string.form_tablet),
        stringResource(R.string.form_wide),
        stringResource(R.string.form_tv),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SmallTitle(text = stringResource(R.string.shell_settings_title))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                WindowDropdownPreference(
                    items = ThemePref.LABELS,
                    selectedIndex = themeMode,
                    title = stringResource(R.string.shell_theme_label),
                    summary = stringResource(R.string.shell_theme_summary),
                    onSelectedIndexChange = { index ->
                        ThemePref.set(context, index)
                        // 明暗走 values/values-night 资源，重建才切得过来（旧设置页同款做法）
                        (context as? Activity)?.recreate()
                    },
                )
                WindowDropdownPreference(
                    items = formLabels,
                    selectedIndex = formIndexOf(formOverride),
                    title = stringResource(R.string.shell_form_label),
                    summary = stringResource(R.string.shell_form_summary),
                    onSelectedIndexChange = { onFormOverride(formOverrideOf(it)) },
                )
            }
        }
        Text(text = stringResource(R.string.shell_settings_placeholder), fontSize = 12.sp)
    }
}
