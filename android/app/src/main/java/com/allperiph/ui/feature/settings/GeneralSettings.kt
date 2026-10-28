package com.allperiph.ui.feature.settings

import android.content.Context
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
import com.allperiph.R
import com.allperiph.hid.HotkeyStore
import com.allperiph.hid.HotkeyTemplates
import com.allperiph.touchpad.GestureShield
import com.allperiph.ui.AgentController
import com.allperiph.ui.EnvHolder
import com.allperiph.ui.miuix.MiuixForm
import com.allperiph.ui.miuix.formIndexOf
import com.allperiph.ui.miuix.formOverrideOf
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * 设置页 · **通用**分类（Compose 重构线）。
 *
 * 分四组，顺序按「你会先想改哪一个」排：交互 → 快捷键 → 界面形态 → 诊断。
 * 诊断放最后，因为它是排障时才进的地方，不该占着首屏。
 *
 * 偏好读写与旧设置页完全同一份：
 *  · 光标锁定 —— `SharedPreferences("apx")` 的 `cursor_lock`，默认开；
 *  · 手势防误触 —— [GestureShield]；
 *  · 自动排序 —— [HotkeyStore.isAutoSort] / [HotkeyStore.setAutoSort]；
 *  · 当前模板 —— [HotkeyStore.loadTemplate] / [HotkeyStore.applyTemplate]。
 */
@Composable
fun GeneralSettings(
    context: Context,
    formOverride: MiuixForm?,
    onFormOverride: (MiuixForm?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var revision by remember { mutableIntStateOf(0) }
    var showTemplates by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    val apxPrefs = remember { context.getSharedPreferences("apx", Context.MODE_PRIVATE) }
    val cursorLock = remember(revision) { apxPrefs.getBoolean("cursor_lock", true) }
    val shieldOn = remember(revision) { GestureShield.isEnabled(context) }
    val autoSort = remember(revision) { HotkeyStore.isAutoSort(context) }
    val templateName = remember(revision) {
        HotkeyTemplates.byKey(HotkeyStore.loadTemplate(context))?.name ?: "—"
    }
    val batteryOk = remember(revision) { isInBatteryWhitelist(context) }
    val formLabels = listOf(
        stringResource(R.string.form_follow),
        stringResource(R.string.form_phone),
        stringResource(R.string.form_tablet),
        stringResource(R.string.form_wide),
        stringResource(R.string.form_tv),
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // —— 交互 ——
        SettingsGroup(R.string.lay_settings_group_interact) {
            SwitchPreference(
                title = stringResource(R.string.lay_settings_cursor_lock_title),
                summary = stringResource(R.string.lay_settings_hint_cursor_lock),
                checked = cursorLock,
                onCheckedChange = {
                    apxPrefs.edit().putBoolean("cursor_lock", it).apply()
                    revision++
                },
            )
            SwitchPreference(
                title = stringResource(R.string.lay_settings_gesture_shield_title),
                summary = stringResource(R.string.lay_settings_hint_gesture_shield),
                checked = shieldOn,
                onCheckedChange = {
                    GestureShield.setEnabled(context, it)
                    revision++
                },
            )
            SwitchPreference(
                title = stringResource(R.string.lay_settings_text_hotkey),
                summary = stringResource(R.string.lay_settings_hint_hotkey),
                checked = autoSort,
                onCheckedChange = {
                    HotkeyStore.setAutoSort(context, it)
                    revision++
                },
            )
        }

        // —— 快捷键模板 ——
        SettingsGroup(R.string.lay_settings_text_hotkey_template) {
            ArrowPreference(
                title = stringResource(R.string.lay_settings_text_hotkey_template),
                summary = templateName,
                onClick = { showTemplates = true },
            )
        }

        // —— 界面形态 ——
        SettingsGroup(R.string.shell_form_label) {
            WindowDropdownPreference(
                items = formLabels,
                selectedIndex = formIndexOf(formOverride),
                title = stringResource(R.string.shell_form_label),
                summary = stringResource(R.string.shell_form_summary),
                onSelectedIndexChange = { onFormOverride(formOverrideOf(it)) },
            )
        }

        // —— 诊断 ——
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
            ArrowPreference(
                title = stringResource(R.string.ui_main_label_log_view),
                summary = stringResource(R.string.ui_log_empty_hint),
                onClick = { showLog = true },
            )
        }
    }

    if (showTemplates) {
        TemplatePickerDialog(
            context = context,
            onDismiss = { showTemplates = false },
            onApplied = { revision++ },
        )
    }
    if (showLog) {
        LogDialog(context = context, onDismiss = { showLog = false })
    }
}
