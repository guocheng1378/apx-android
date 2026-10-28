package com.allperiph.ui.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HotkeyStore
import com.allperiph.hid.HotkeyTemplates
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置页用到的几个**通用对话框**（Compose 重构线）。
 *
 * 旧版这些都是 `AlertDialog.Builder(...)`：标题 + 一条不可滚动的长文本 + 三个按钮。
 * 换成 Compose 后顺带解决两个老问题：
 *  · 日志再长也能滚（旧版长文本被对话框截断，只能复制走再看）；
 *  · 「复制 / 分享」就放在下面两个按钮上，不再塞进 AlertDialog 的三键位置里。
 */

/** 日志对话框：环形缓冲 + 复制 / 分享 */
@Composable
fun LogDialog(context: Context, onDismiss: () -> Unit) {
    val snap = remember { com.allperiph.shared.util.Log.snapshot() }
    val text = if (snap.isEmpty()) {
        stringResource(R.string.ui_log_empty_hint)
    } else {
        snap.takeLast(120).joinToString("\n") { e -> "${e.level} ${e.tag}: ${e.msg}" }
    }
    WindowDialog(
        show = true,
        title = stringResource(R.string.ui_main_label_log_view),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = text,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.ui_common_copy),
                    onClick = {
                        copyToClipboard(context, text)
                        onDismiss()
                    },
                )
                TextButton(
                    text = stringResource(R.string.ui_log_export),
                    onClick = {
                        shareText(context, text)
                        onDismiss()
                    },
                )
                TextButton(text = stringResource(R.string.ui_common_close), onClick = onDismiss)
            }
        }
    }
}

/**
 * 快捷键模板选择：列出内置 + 自定义模板，点一行就套用。
 *
 * 模板**内容**的编辑要进 HotkeyBoard（键盘页那一块），所以这里只做「选 / 套用」——
 * 旧版也是同一个分工（模板编辑在键盘页，设置页只切当前模板）。
 */
@Composable
fun TemplatePickerDialog(context: Context, onDismiss: () -> Unit, onApplied: () -> Unit) {
    val current = remember { HotkeyStore.loadTemplate(context) }
    val templates = remember {
        HotkeyTemplates.ALL + HotkeyStore.loadCustom(context)
    }
    WindowDialog(
        show = true,
        title = stringResource(R.string.lay_settings_text_hotkey_template),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (t in templates) {
                DialogChoiceRow(
                    title = t.name,
                    desc = t.desc,
                    selected = t.key == current,
                    onClick = {
                        HotkeyStore.applyTemplate(context, t)
                        onApplied()
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** 纯文字说明对话框（状态通道、手势遮蔽帮助这类） */
@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = text,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(text = stringResource(R.string.ui_common_close), onClick = onDismiss)
            }
        }
    }
}

/** 对话框里的一行：标题 + 说明 + 选中标记 */
@Composable
private fun DialogChoiceRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text = if (selected) "✓ $title" else title, fontSize = 14.sp)
        if (desc.isNotEmpty()) Text(text = desc, fontSize = 11.sp)
    }
}

// ==================== 小工具 ====================

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("APX-log", text))
}

private fun shareText(context: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
