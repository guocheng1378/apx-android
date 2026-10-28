package com.allperiph.ui.feature.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.ui.ThemePref
import com.allperiph.ui.ThemeSkin
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置页 · **外观**分类（Compose 重构线）。
 *
 * 四组：主题（明暗）→ 皮肤（三个模块各自一套强调色）→ 背景与动效（观感）→ 主题文件（带走/搬来）。
 * 顺序按「从整体到局部」：先定明暗，再定强调色，最后才是背景与动效这类细枝末节。
 *
 * 偏好读写走**既有的** [ThemePref] / [ThemeSkin] —— 与旧设置页同一份存储，
 * 新旧界面改的是同一个东西，不会出现「两边设置各存一份」。
 */
@Composable
fun LookSettings(context: Context, modifier: Modifier = Modifier) {
    // 任一项变了都要重画这一组（偏好没有 observable，用一个计数器做触发）
    var revision by remember { mutableIntStateOf(0) }
    var skinScope by remember { mutableStateOf<String?>(null) }
    var bgDialog by remember { mutableStateOf(false) }

    // 自定义背景图：走 Activity 结果 API（Compose 侧自带 launcher，不必改 ApShellActivity）
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 持久读权限：不申请的话重启后这张图就读不到了
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        ThemeSkin.setBgImage(context, uri.toString())
        revision++
    }
    // 导出主题：SAF 建文档（与旧实现同一个文件名与 MIME）
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = ThemeSkin.exportJson(context)
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
        revision++
    }
    // 导入主题：读文件 → 合并 → 重建界面（配色是资源级切换，重建才全生效）
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull().orEmpty()
        val n = ThemeSkin.importJson(context, text)
        if (n >= 0) (context as? Activity)?.recreate() else revision++
    }

    val themeMode = remember(revision) { ThemePref.get(context) }
    val bgId = remember(revision) { ThemeSkin.bgId(context) }
    val bgImage = remember(revision) { ThemeSkin.bgImage(context) }
    val motionIndex = remember(revision) { ThemeSkin.motionIndex(context) }
    val hapticIndex = remember(revision) { ThemeSkin.hapticIndex(context) }
    val soundIndex = remember(revision) { ThemeSkin.soundIndex(context) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // —— 主题 ——
        SettingsGroup(R.string.lay_settings_text_theme) {
            WindowDropdownPreference(
                items = ThemePref.LABELS,
                selectedIndex = themeMode,
                title = stringResource(R.string.lay_settings_text_theme),
                summary = stringResource(R.string.shell_theme_summary),
                onSelectedIndexChange = { index ->
                    if (index != ThemePref.get(context)) {
                        ThemePref.set(context, index)
                        // 明暗走 values/values-night，重建才切得过来（旧设置页同款做法）
                        (context as? Activity)?.recreate()
                    }
                },
            )
        }

        // —— 皮肤（三个模块各自一套强调色）——
        SettingsGroup(R.string.lay_settings_text_color) {
            for ((scope, title) in ThemeSkin.SCOPES) {
                val skin = ThemeSkin.current(context, scope)
                ArrowPreference(
                    title = title,
                    summary = skin.name,
                    onClick = { skinScope = scope },
                )
            }
        }

        // —— 背景与动效 ——
        SettingsGroup(R.string.ui_main_label_background) {
            val bgName = ThemeSkin.BG_PRESETS.firstOrNull { it.id == bgId }?.name
                ?: ThemeSkin.BG_PRESETS.first().name
            ArrowPreference(
                title = stringResource(R.string.ui_main_label_background),
                summary = if (!bgImage.isNullOrBlank()) {
                    stringResource(R.string.ui_main_text_image)
                } else {
                    bgName
                },
                onClick = { bgDialog = true },
            )
            WindowDropdownPreference(
                items = ThemeSkin.MOTION_LABELS,
                selectedIndex = motionIndex,
                title = stringResource(R.string.ui_main_label_motion),
                summary = stringResource(R.string.ui_main_label_motion_2),
                onSelectedIndexChange = {
                    ThemeSkin.setMotion(context, it)
                    revision++
                },
            )
            WindowDropdownPreference(
                items = ThemeSkin.HAPTIC_LABELS,
                selectedIndex = hapticIndex,
                title = stringResource(R.string.ui_main_label_haptic),
                summary = stringResource(R.string.ui_main_label_haptic_2),
                onSelectedIndexChange = {
                    ThemeSkin.setHaptic(context, it)
                    revision++
                },
            )
            WindowDropdownPreference(
                items = ThemeSkin.SOUND_LABELS,
                selectedIndex = soundIndex,
                title = stringResource(R.string.ui_main_label_sound),
                summary = stringResource(R.string.ui_main_label_sound_2),
                onSelectedIndexChange = {
                    ThemeSkin.setSound(context, it)
                    revision++
                },
            )
        }

        // —— 主题文件：导出 / 导入 / 分享 ——
        SettingsGroup(R.string.lay_settings_text_theme) {
            ArrowPreference(
                title = stringResource(R.string.lay_settings_text_export),
                onClick = { exportPicker.launch("allperiph-theme.json") },
            )
            ArrowPreference(
                title = stringResource(R.string.lay_settings_text_import),
                onClick = { importPicker.launch(arrayOf("*/*")) },
            )
            ArrowPreference(
                title = stringResource(R.string.lay_settings_text_share),
                onClick = {
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "allperiph-theme.json")
                        putExtra(android.content.Intent.EXTRA_TEXT, ThemeSkin.exportJson(context))
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(i, null)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
        }
    }

    // —— 皮肤选择对话框 ——
    val scope = skinScope
    if (scope != null) {
        val presets = remember(scope, revision) { ThemeSkin.PRESETS }
        val customs = remember(scope, revision) { ThemeSkin.customs(context) }
        val follow = remember(scope, revision) { ThemeSkin.followTheme(context) }
        val currentId = remember(scope, revision) { ThemeSkin.picked(context, scope) }
        WindowDialog(
            show = true,
            title = ThemeSkin.SCOPES.firstOrNull { it.first == scope }?.second.orEmpty(),
            onDismissRequest = { skinScope = null },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SkinChoice(follow.name, currentId.isNullOrBlank()) {
                    ThemeSkin.set(context, scope, null)
                    skinScope = null
                    revision++
                }
                for (skin in presets) {
                    SkinChoice(skin.name, currentId == skin.id) {
                        ThemeSkin.set(context, scope, skin.id)
                        skinScope = null
                        revision++
                    }
                }
                for (skin in customs) {
                    SkinChoice(skin.name, currentId == skin.id) {
                        ThemeSkin.set(context, scope, skin.id)
                        skinScope = null
                        revision++
                    }
                }
            }
        }
    }

    // —— 背景选择对话框 ——
    if (bgDialog) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_main_label_background),
            onDismissRequest = { bgDialog = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (bg in ThemeSkin.BG_PRESETS) {
                    SkinChoice(bg.name, bgId == bg.id && bgImage.isNullOrBlank()) {
                        ThemeSkin.setBg(context, bg.id)
                        // 选了纯色就撤掉图片（旧实现同款：纯色与图片互斥）
                        ThemeSkin.setBgImage(context, null)
                        bgDialog = false
                        revision++
                    }
                }
                SkinChoice(stringResource(R.string.shell_bg_pick_image), false) {
                    bgDialog = false
                    imagePicker.launch("image/*")
                }
                if (!bgImage.isNullOrBlank()) {
                    SkinChoice(stringResource(R.string.shell_bg_clear_image), false) {
                        ThemeSkin.setBgImage(context, null)
                        bgDialog = false
                        revision++
                    }
                }
            }
        }
    }
}

/** 对话框里的一行选项（选中打勾） */
@Composable
private fun SkinChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = if (selected) "✓ $title" else title,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
