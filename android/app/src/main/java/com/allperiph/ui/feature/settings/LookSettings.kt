package com.allperiph.ui.feature.settings

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置页 · **外观**分类（Compose 重构线）。
 *
 * 这一组全是「写偏好 + 立刻可验」的东西，所以先迁：主题明暗、三个模块的皮肤（触控板 /
 * 键盘 / 快捷键）、背景、动效、震动、声音。偏好读写走**既有的** [ThemePref] / [ThemeSkin]
 * —— 与旧设置页同一份存储，新旧界面改的是同一个东西，不会出现「两边设置各存一份」。
 *
 * ## 与旧实现的口径一致处
 *  · 主题切换后要**重建 Activity**（明暗是 values/values-night 资源切换，重建才生效）；
 *  · 选了纯色背景会**撤掉**自定义图片（旧实现同款：纯色与图片互斥）；
 *  · 自定义背景要**持久读权限**（不申请的话重启后读不到那张图）。
 */
@Composable
fun LookSettings(context: Context, modifier: Modifier = Modifier) {
    // 任一项变了都要重画这一组（偏好没有 observable，用一个计数器做触发）
    var revision by remember { mutableIntStateOf(0) }
    var skinScope by remember { mutableStateOf<String?>(null) }
    var bgDialog by remember { mutableStateOf(false) }

    // 自定义背景图：走 Activity 结果 API（Compose 侧自带 launcher，不必改 MainActivity）
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

    val themeMode = remember(revision) { ThemePref.get(context) }
    val bgId = remember(revision) { ThemeSkin.bgId(context) }
    val bgImage = remember(revision) { ThemeSkin.bgImage(context) }
    val motionIndex = remember(revision) { ThemeSkin.motionIndex(context) }
    val hapticIndex = remember(revision) { ThemeSkin.hapticIndex(context) }
    val soundIndex = remember(revision) { ThemeSkin.soundIndex(context) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallTitle(text = stringResource(R.string.ui_main_cat_look))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                // —— 主题：跟随系统 / 浅色 / 深色 ——
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
                // —— 三个模块的皮肤 ——
                for ((scope, title) in ThemeSkin.SCOPES) {
                    val skin = ThemeSkin.current(context, scope)
                    ArrowPreference(
                        title = title,
                        summary = skin.name,
                        onClick = { skinScope = scope },
                    )
                }
                // —— 背景 ——
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
                // —— 动效 / 震动 / 声音 ——
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
            Column(modifier = Modifier.fillMaxWidth()) {
                ChoiceRow(
                    title = follow.name,
                    selected = currentId.isNullOrBlank(),
                    onClick = {
                        ThemeSkin.set(context, scope, null)
                        skinScope = null
                        revision++
                    },
                )
                for (skin in presets) {
                    ChoiceRow(
                        title = skin.name,
                        selected = currentId == skin.id,
                        onClick = {
                            ThemeSkin.set(context, scope, skin.id)
                            skinScope = null
                            revision++
                        },
                    )
                }
                for (skin in customs) {
                    ChoiceRow(
                        title = skin.name,
                        selected = currentId == skin.id,
                        onClick = {
                            ThemeSkin.set(context, scope, skin.id)
                            skinScope = null
                            revision++
                        },
                    )
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
            Column(modifier = Modifier.fillMaxWidth()) {
                for (bg in ThemeSkin.BG_PRESETS) {
                    ChoiceRow(
                        title = bg.name,
                        selected = bgId == bg.id && bgImage.isNullOrBlank(),
                        onClick = {
                            ThemeSkin.setBg(context, bg.id)
                            // 选了纯色就撤掉图片（旧实现同款：纯色与图片互斥）
                            ThemeSkin.setBgImage(context, null)
                            bgDialog = false
                            revision++
                        },
                    )
                }
                ChoiceRow(
                    title = stringResource(R.string.shell_bg_pick_image),
                    selected = false,
                    onClick = {
                        bgDialog = false
                        imagePicker.launch("image/*")
                    },
                )
                if (!bgImage.isNullOrBlank()) {
                    ChoiceRow(
                        title = stringResource(R.string.shell_bg_clear_image),
                        selected = false,
                        onClick = {
                            ThemeSkin.setBgImage(context, null)
                            bgDialog = false
                            revision++
                        },
                    )
                }
            }
        }
    }
}

/** 对话框里的一行选项（选中打勾） */
@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = if (selected) "✓ $title" else title,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
