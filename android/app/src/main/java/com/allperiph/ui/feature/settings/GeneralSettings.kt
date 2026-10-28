package com.allperiph.ui.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.allperiph.R
import com.allperiph.touchpad.GestureShield
import com.allperiph.ui.AgentController
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 设置页 · **通用**分类里的两组（Compose 重构线）。
 *
 * ## 迁移的是哪几项
 * 这一分类的其余内容（快捷键模板、模板编辑、远程目标、LocalSend、日志导出）都要牵动
 * 主机侧的重型 UI（HotkeyBoard / 模板编辑对话框 / 设备选择器 / 文件面板），单独迁会把
 * 「一半新一半旧」摊在同一个分类里，反而更难收尾 —— 所以这一批先拿**自成闭环**的两组：
 *  · 交互开关：光标锁定、手势防误触（都是开关，改完立刻生效）；
 *  · 诊断：电池白名单、重新检测环境（都是动作）。
 *
 * 偏好读写与旧实现完全一致：
 *  · 光标锁定 —— SharedPreferences("apx") 的 `cursor_lock`，默认开；
 *  · 手势防误触 —— [GestureShield]（同一份偏好、同一套开关逻辑）。
 */
@Composable
fun GeneralSettings(context: Context, modifier: Modifier = Modifier) {
    // 改完偏好要重画这一组（偏好不是 observable，用计数器触发）
    var revision by remember { mutableIntStateOf(0) }

    val apxPrefs = remember { context.getSharedPreferences("apx", Context.MODE_PRIVATE) }
    val cursorLock = remember(revision) { apxPrefs.getBoolean("cursor_lock", true) }
    val shieldOn = remember(revision) { GestureShield.isEnabled(context) }
    // true = 已在白名单里（PowerManager 的语义与 EnvChecks.Env.batteryOptimized 相反，
    // 那边的 true 意思是「还没加入」）
    val batteryOk = remember(revision) { isInBatteryWhitelist(context) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // —— 交互开关 ——
        SmallTitle(text = stringResource(R.string.lay_settings_group_interact))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
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
            }
        }

        // —— 诊断 ——
        SmallTitle(text = stringResource(R.string.lay_settings_group_diag))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
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
                        // 重新探测环境：结果写进 EnvHolder，状态页下一帧就能看到
                        AgentController.refreshEnv(context) { env ->
                            com.allperiph.ui.EnvHolder.env = env
                        }
                    },
                )
            }
        }
    }
}

/** 是否已加入电池优化白名单（true = 已加入，不再被后台冻结） */
private fun isInBatteryWhitelist(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * 申请加入电池白名单。
 *
 * 与旧 MainActivity.requestBatteryWhitelist() 同一套：先直接申请，失败就退回系统白名单
 * 列表页（各家 ROM 对这个 Intent 的处理不一样，直接崩掉不行）。
 */
private fun requestBatteryWhitelist(context: Context) {
    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching {
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
