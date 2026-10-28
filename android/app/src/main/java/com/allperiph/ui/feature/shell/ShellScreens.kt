package com.allperiph.ui.feature.shell

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.feature.settings.LookSettings
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
 * 设置页（新外壳这一版已有两个**真**分组）：
 *  · **外观**分类（[LookSettings]）：主题明暗、三个模块的皮肤、背景、动效、震动、声音 ——
 *    偏好读写走既有的 ThemePref / ThemeSkin，与旧设置页同一份存储；
 *  · **界面形态**：跟随窗口宽度自动判定，或手动钉死（真机验收各形态时用）。
 *
 * 其余分类（通用 / 遥控 / 关于 / 帮助）随迁移逐个补上（见 docs/COMPOSE-REFACTOR.md §3）。
 */
@Composable
fun ShellSettingsScreen(
    context: Context,
    modifier: Modifier = Modifier,
    formOverride: MiuixForm?,
    onFormOverride: (MiuixForm?) -> Unit,
) {
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
        // 外观分类：主题 / 皮肤 / 背景 / 动效 / 震动 / 声音（真设置，走既有偏好）
        LookSettings(context)

        // 界面形态（重构线新增的全局开关）：跟随窗口宽度，或手动钉死
        SmallTitle(text = stringResource(R.string.shell_form_label))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
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
