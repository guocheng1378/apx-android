package com.allperiph.ui.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.allperiph.R
import com.allperiph.touchpad.GestureShield
import com.allperiph.ui.Feedback
import com.allperiph.ui.IssueLauncher
import com.allperiph.ui.FloatingBallService
import com.allperiph.ui.ThemePref
import com.allperiph.ui.GlobalKeyForwarder
import com.allperiph.ui.ThemeSkin
import com.allperiph.hid.HotkeyStore
import com.allperiph.ui.UpdateChecker
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.MiuixTokens
import com.allperiph.ui.miuix.rememberApInsets
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * 设置页（阶段 4，**本轮只迁核心分组**）。
 *
 * 覆盖范围与旧 `SettingsFragment` 的对照：
 *  · 外观：主题明暗（[ThemePref]）、取色方案（品牌蓝 / 跟随系统）、三处皮肤（触控板 / 键盘 / 快捷键）、
 *    动效、震动、音效档位 —— 与旧 `renderTheme` / `renderSkins` / `renderFx` 一一对应；
 *  · 通用：光标锁定（`apx` 偏好里的 `cursor_lock`）、三指手势遮蔽（[GestureShield]）、
 *    LocalSend 互传（`localsend_enabled` + 服务启停）；
 *  · 关于：版本行 + 检查更新 + 意见反馈 + Miuix 组件总览入口。
 *
 * **未迁移**（仍留在旧设置页）：快捷键模板的增删改与拖动排序、遥控器配对（TV 连接）、
 * 文件发送、键映射、环境诊断、日志查看/导出、主题导入导出与分享、背景图。
 * 这些分组都带对话框或文件选择器，一次迁完风险过大，留给阶段 4 的第二批。
 */
@Composable
fun SettingsPage(ctx: Context, onRecreate: () -> Unit) {
    val insets = rememberApInsets()
    var tplVersion by remember { mutableIntStateOf(0) }
    // 档位值放在 state 里：下拉组件是受控的，改完要立刻反映到界面
    var themeMode by remember { mutableIntStateOf(ThemePref.get(ctx)) }
    var colorSource by remember { mutableIntStateOf(ThemeSkin.colorSource(ctx)) }
    var motion by remember { mutableIntStateOf(ThemeSkin.motionIndex(ctx)) }
    var haptic by remember { mutableIntStateOf(ThemeSkin.hapticIndex(ctx)) }
    var sound by remember { mutableIntStateOf(ThemeSkin.soundIndex(ctx)) }

    val apxPrefs = remember { ctx.getSharedPreferences("apx", Context.MODE_PRIVATE) }
    var cursorLock by remember { mutableStateOf(apxPrefs.getBoolean("cursor_lock", true)) }
    var shield by remember { mutableStateOf(GestureShield.isEnabled(ctx)) }
    var localSend by remember { mutableStateOf(apxPrefs.getBoolean("localsend_enabled", false)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp + insets.left,
                end = 16.dp + insets.right,
                top = 8.dp,
                bottom = 96.dp + insets.bottom,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ———————————————— 外观 ————————————————
        ApComponents.Section(title = ctx.getString(R.string.ui_main_label_theme)) {
            Column {
                WindowDropdownPreference(
                    title = "主题明暗",
                    summary = ThemePref.LABELS.getOrElse(themeMode) { "" },
                    items = ThemePref.LABELS,
                    selectedIndex = themeMode,
                    onSelectedIndexChange = { i ->
                        Feedback.haptic(ctx)
                        themeMode = i
                        ThemePref.set(ctx, i)
                        // 明暗写的是 configuration，必须重建才生效（与旧实现同一处理）
                        onRecreate()
                    },
                )
                WindowDropdownPreference(
                    title = ctx.getString(R.string.ui_main_label_color_source),
                    summary = ThemeSkin.COLOR_SOURCE_LABELS.getOrElse(colorSource) { "" },
                    items = ThemeSkin.COLOR_SOURCE_LABELS,
                    selectedIndex = colorSource,
                    onSelectedIndexChange = { i ->
                        Feedback.haptic(ctx)
                        colorSource = i
                        ThemeSkin.setColorSource(ctx, i)
                        ThemeSkin.applyRuntimeTheme(ctx)
                        onRecreate()
                    },
                )
            }
        }

        ApComponents.Section(title = "皮肤") {
            Column {
                ThemeSkin.SCOPES.forEach { (scope, title) ->
                    val current = ThemeSkin.current(ctx, scope)
                    val names = ThemeSkin.PRESETS.map { it.name }
                    val selected = ThemeSkin.PRESETS.indexOfFirst { it.id == current.id }
                        .coerceAtLeast(0)
                    WindowDropdownPreference(
                        title = title,
                        summary = if (ThemeSkin.picked(ctx, scope).isBlank()) "跟随主题" else current.name,
                        items = names,
                        selectedIndex = selected,
                        onSelectedIndexChange = { i ->
                            Feedback.haptic(ctx)
                            ThemeSkin.set(ctx, scope, ThemeSkin.PRESETS[i].id)
                            ApAccentSync.apply(ctx)
                            onRecreate()
                        },
                    )
                }
            }
        }

        ApComponents.Section(title = ctx.getString(R.string.ui_main_label_motion)) {
            Column {
                WindowDropdownPreference(
                    title = ctx.getString(R.string.ui_main_label_motion),
                    summary = ThemeSkin.MOTION_LABELS.getOrElse(motion) { "" },
                    items = ThemeSkin.MOTION_LABELS,
                    selectedIndex = motion,
                    onSelectedIndexChange = { i ->
                        motion = i
                        ThemeSkin.setMotion(ctx, i)
                        Feedback.haptic(ctx)
                    },
                )
                WindowDropdownPreference(
                    title = ctx.getString(R.string.ui_main_label_haptic),
                    summary = ThemeSkin.HAPTIC_LABELS.getOrElse(haptic) { "" },
                    items = ThemeSkin.HAPTIC_LABELS,
                    selectedIndex = haptic,
                    onSelectedIndexChange = { i ->
                        haptic = i
                        ThemeSkin.setHaptic(ctx, i)
                        Feedback.haptic(ctx)
                    },
                )
                WindowDropdownPreference(
                    title = ctx.getString(R.string.ui_main_label_sound),
                    summary = ThemeSkin.SOUND_LABELS.getOrElse(sound) { "" },
                    items = ThemeSkin.SOUND_LABELS,
                    selectedIndex = sound,
                    onSelectedIndexChange = { i ->
                        sound = i
                        ThemeSkin.setSound(ctx, i)
                    },
                )
            }
        }

        // ———————————————— 通用 ————————————————
        ApComponents.Section(title = "通用") {
            Column {
                ApComponents.SwitchRow(
                    title = "光标锁定",
                    summary = "触控板光标锁定在屏幕内，不贴边滑出",
                    checked = cursorLock,
                    onCheckedChange = { on ->
                        cursorLock = on
                        apxPrefs.edit().putBoolean("cursor_lock", on).apply()
                        Feedback.haptic(ctx)
                    },
                )
                ApComponents.SwitchRow(
                    title = "三指手势遮蔽",
                    summary = "触控板里三指滑动不再被系统截屏（需 root，退出即还原）",
                    checked = shield,
                    onCheckedChange = { on ->
                        shield = on
                        GestureShield.setEnabled(ctx, on)
                        Feedback.haptic(ctx)
                    },
                )
                ApComponents.SwitchRow(
                    title = "LocalSend 互传",
                    summary = "开启后在局域网内接收文件",
                    checked = localSend,
                    onCheckedChange = { on ->
                        localSend = on
                        apxPrefs.edit().putBoolean("localsend_enabled", on).apply()
                        if (on) {
                            com.allperiph.localsend.LocalSendService.start(ctx)
                        } else {
                            com.allperiph.localsend.LocalSendService.stop(ctx)
                        }
                        Feedback.haptic(ctx)
                    },
                )
            }
        }

        var ballVisible by remember { mutableStateOf(FloatingBallService.isOn(ctx)) }

        // ———————————————— 悬浮栏 ————————————————
        // 成宇 2026-11-01：悬浮球与悬浮菜单要能隐藏。这里不新造状态，
        // 读写的还是 FloatingBallService 自己的 on/off（isOn + 启停服务），
        // 开关只是同一份状态的另一个入口 —— 避免"设置里关了球还在"这类不一致。
        ApComponents.Section(title = "悬浮栏") {
            Column {
                ApComponents.SwitchRow(
                    title = "显示悬浮球",
                    summary = "关掉后悬浮球与悬浮菜单一起隐藏，外设连接不受影响",
                    checked = ballVisible,
                    onCheckedChange = { on ->
                        ballVisible = on
                        val it = Intent(ctx, FloatingBallService::class.java)
                        if (on) {
                            ctx.startForegroundService(it)
                        } else {
                            ctx.stopService(it)
                        }
                        Feedback.haptic(ctx)
                    },
                )
            }
        }

        // ———————————————— 快捷键模板 ————————————————
        // 从旧 MainActivity 的设置页迁来：模板列表 + 应用 + 当前模板回显。
        // 数据与旧页共用 HotkeyTemplates.ALL / HotkeyStore.loadTemplate|applyTemplate，不另立存储。
        ApComponents.Section(title = "快捷键模板") {
            Column {
                ApComponents.ValueRow(
                    title = "当前模板",
                    value = HotkeyStore.loadTemplate(ctx)?.let { key ->
                        com.allperiph.hid.HotkeyTemplates.byKey(key)?.name ?: key
                    } ?: "未设置（默认）",
                )
                com.allperiph.hid.HotkeyTemplates.ALL.take(6).forEach { t ->
                    ApComponents.ActionButton(
                        text = "${t.name} · ${t.desc}",
                        onClick = {
                            com.allperiph.hid.HotkeyTemplates.byKey(t.key)?.let {
                                HotkeyStore.applyTemplate(ctx, it)
                            }
                            tplVersion++
                            Feedback.haptic(ctx)
                        },
                    )
                }
            }
        }

        // ———————————————— 环境诊断 ————————————————
        ApComponents.Section(title = "环境诊断") {
            Column {
                var env by remember { mutableStateOf(com.allperiph.ui.AgentController.env) }
                ApComponents.ActionButton(
                    text = "重新检测",
                    onClick = { com.allperiph.ui.AgentController.refreshEnv(ctx) { env = it } },
                )
                env?.let { e ->
                    ApComponents.ValueRow(title = "root", value = if (e.rooted) "已就绪" else "未获取")
                    ApComponents.ValueRow(title = "链路", value = e.rawSpeed.ifEmpty { e.linkSpeed.toString() })
                    ApComponents.ValueRow(title = "UDC", value = e.udc ?: "未找到")
                    ApComponents.ValueRow(
                        title = "电池优化",
                        value = if (e.batteryOptimized) "已忽略（推荐）" else "未忽略",
                    )
                    ApComponents.ValueRow(
                        title = "通知权限",
                        value = if (e.notificationGranted) "已授予" else "未授予",
                    )
                }
            }
        }

        // ———————————————— 文件与传输 ————————————————
        ApComponents.Section(title = "文件与传输") {
            Column {
                ApComponents.ActionButton(
                    text = "文件面板",
                    onClick = { ctx.startActivity(Intent(ctx, com.allperiph.ui.FilePanelActivity::class.java)) },
                )
                ApComponents.ActionButton(
                    text = "发送文件（LocalSend）",
                    onClick = {
                        ctx.startActivity(
                            Intent(ctx, com.allperiph.localsend.SendActivity::class.java),
                        )
                    },
                )
            }
        }

        // ———————————————— 遥控与键盘 ————————————————
        var globalKeys by remember { mutableStateOf(apxPrefs.getBoolean("global_keys", false)) }
        ApComponents.Section(title = "遥控与键盘") {
            Column {
                ApComponents.SwitchRow(
                    title = "物理键盘全局转发",
                    summary = "接上蓝牙键盘后，按键直接转给被控端（需已连接设备）",
                    checked = globalKeys,
                    onCheckedChange = { on ->
                        globalKeys = on
                        GlobalKeyForwarder.setEnabled(apxPrefs, on)
                        Feedback.haptic(ctx)
                    },
                )
                ApComponents.ValueRow(
                    title = "当前控制",
                    value = runCatching {
                        com.allperiph.wireless.ControlTarget.label
                            ?: com.allperiph.wireless.ControlTarget.host
                            ?: ""
                    }.getOrDefault("").ifBlank { "未连接（点顶栏设备名可选择）" },
                )
                ApComponents.ActionButton(
                    text = "断开当前控制",
                    onClick = {
                        runCatching { com.allperiph.wireless.ControlTarget.clear() }
                        Toast.makeText(ctx, "已断开", Toast.LENGTH_SHORT).show()
                    },
                )
                ApComponents.ActionButton(
                    text = "重新发现局域网设备",
                    onClick = {
                        com.allperiph.wireless.TvDiscovery.start()
                        Toast.makeText(ctx, "已开始搜索，稍候在顶栏设备名里选", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        // ———————————————— 主题数据 ————————————————
        ApComponents.Section(title = "主题数据") {
            Column {
                ApComponents.ActionButton(
                    text = "导出当前主题（复制到剪贴板）",
                    onClick = {
                        val json = runCatching { ThemeSkin.exportJson(ctx) }.getOrNull().orEmpty()
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        if (json.isNotBlank()) {
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("apx-theme", json))
                            Toast.makeText(ctx, "已复制 ${json.length} 字符", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "没有可导出的主题", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                ApComponents.ActionButton(
                    text = "从剪贴板导入主题",
                    onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        val n = if (text.isBlank()) 0 else runCatching { ThemeSkin.importJson(ctx, text) }.getOrDefault(0)
                        Toast.makeText(
                            ctx,
                            if (n > 0) "已导入 $n 套主题" else "剪贴板里没有可识别的主题 JSON",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }

        // ———————————————— 关于 ————————————————
        ApComponents.Section(title = ctx.getString(R.string.lay_settings_tab_about)) {
            Column {
                ApComponents.ValueRow(
                    title = "当前版本",
                    value = UpdateChecker.currentTag(),
                )
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.about_check_update),
                    onClick = {
                        UpdateChecker.check(ctx) { r ->
                            val msg = when (r) {
                                is UpdateChecker.Result.UpToDate -> "已是最新：${r.tag}"
                                is UpdateChecker.Result.NewVersion -> "发现新版：${r.tag}"
                                is UpdateChecker.Result.Failed -> "检查失败"
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.about_feedback),
                    onClick = { IssueLauncher.open(ctx) },
                )
            }
        }
    }
}
