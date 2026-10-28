package com.allperiph.ui.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.allperiph.BuildConfig
import com.allperiph.R
import com.allperiph.ui.AgentController
import com.allperiph.ui.EnvHolder
import com.allperiph.ui.UpdateChecker
import com.allperiph.ui.miuix.MiuixShowcaseActivity
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 设置页 · **关于**分类（Compose 重构线）。
 *
 * 版本 → 更新检查 → 组件总览 → 维护（白名单 / 重新检测）。
 * 更新检查复用 [UpdateChecker]（同一个 API、同一套镜像回退与限频规则），不另写一份网络逻辑。
 */
@Composable
fun AboutSettings(context: Context, modifier: Modifier = Modifier) {
    var revision by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val batteryOk = remember(revision) { isInBatteryWhitelist(context) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // —— 版本与更新 ——
        SettingsGroup(R.string.shell_about_version) {
            InfoRow(
                text = "${UpdateChecker.currentTag()}（versionName ${BuildConfig.VERSION_NAME}）",
            )
            ArrowPreference(
                title = stringResource(R.string.shell_about_update),
                summary = when {
                    checking -> stringResource(R.string.shell_about_checking)
                    note.isNotEmpty() -> note
                    else -> ""
                },
                onClick = {
                    if (checking) return@ArrowPreference
                    checking = true
                    note = ""
                    UpdateChecker.check(context) { r ->
                        checking = false
                        when (r) {
                            is UpdateChecker.Result.UpToDate ->
                                note = context.getString(R.string.shell_about_latest, r.tag)
                            is UpdateChecker.Result.NewVersion ->
                                UpdateChecker.showUpdateDialog(context, r.tag, r.url)
                            is UpdateChecker.Result.Failed ->
                                note = context.getString(R.string.shell_about_failed)
                        }
                    }
                },
            )
            ArrowPreference(
                title = stringResource(R.string.shell_about_showcase),
                onClick = {
                    context.startActivity(
                        Intent(context, MiuixShowcaseActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
        }

        // —— 维护 ——
        SettingsGroup(R.string.lay_settings_group_diag) {
            ArrowPreference(
                title = stringResource(R.string.action_battery_whitelist),
                summary = if (batteryOk) {
                    stringResource(R.string.value_battery_ok)
                } else {
                    stringResource(R.string.value_battery_limited)
                },
                onClick = { requestBatteryWhitelist(context) },
            )
            ArrowPreference(
                title = stringResource(R.string.action_refresh),
                summary = stringResource(R.string.lay_settings_diag_hint),
                onClick = {
                    AgentController.refreshEnv(context) { env -> EnvHolder.env = env }
                    revision++
                },
            )
        }
    }
}
