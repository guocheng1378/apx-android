package com.allperiph.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.allperiph.R

/**
 * 设置页 · **帮助**分类（Compose 重构线）。
 *
 * 纯长文，按「先讲是什么、再讲怎么用、最后是疑难」排。文案全部是既有字符串资源
 * （`ui_help_*`），一行没重写 —— 帮助文本是要随版本一起审校的内容，不该在重构里被动过。
 */
@Composable
fun HelpSettings(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsGroup(R.string.ui_help_title_project) {
            InfoRow(stringResource(R.string.ui_help_project))
        }
        SettingsGroup(R.string.ui_help_title_link) {
            InfoRow(stringResource(R.string.ui_help_link))
        }
        SettingsGroup(R.string.ui_help_title_gesture) {
            InfoRow(stringResource(R.string.ui_help_gesture))
        }
        SettingsGroup(R.string.ui_help_title_switch) {
            InfoRow(stringResource(R.string.ui_help_cursor_lock))
            InfoRow(stringResource(R.string.ui_help_gesture_shield))
            InfoRow(stringResource(R.string.ui_help_localsend))
            InfoRow(stringResource(R.string.ui_help_export_log))
        }
        SettingsGroup(R.string.ui_help_title_faq) {
            InfoRow(stringResource(R.string.ui_help_faq))
        }
    }
}
