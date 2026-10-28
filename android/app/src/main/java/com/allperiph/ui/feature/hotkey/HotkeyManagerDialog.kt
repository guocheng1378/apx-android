package com.allperiph.ui.feature.hotkey

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 快捷键管理（Compose 重构线）—— 旧 `HotkeyBoard.managerDialog()` 的 Compose 版。
 *
 * 旧版把「拖动换位」放在条子上，横滚容器里拖拽会和滚动抢手势；这里换成列表的
 * 上移/下移，位置改得准也不会误触。增删改仍是直接写 `HotkeyStore`，
 * 与旧版共用同一份存储（`apx` 偏好 + 模板），新旧界面看到的永远是同一组快捷键。
 */
@Composable
fun HotkeyManagerDialog(
    context: Context,
    onDismiss: () -> Unit,
    tvMode: Boolean = false,
) {
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val list = remember(revision, tvMode) {
        if (tvMode) HotkeyStore.loadTv(context) else HotkeyStore.load(context)
    }
    val cs = MiuixTheme.colorScheme

    fun persist() {
        if (tvMode) HotkeyStore.saveTv(context, list) else HotkeyStore.save(context, list)
        HotkeyStore.markCustom(context)
        revision++
    }

    WindowDialog(
        show = true,
        title = stringResource(R.string.shell_hotkey_manage),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            list.forEachIndexed { index, combo ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = combo.label,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = HidKeys.comboText(combo), fontSize = 12.sp)
                    MiniAction("↑") {
                        if (index > 0) {
                            val tmp = list[index - 1]
                            list[index - 1] = list[index]
                            list[index] = tmp
                            persist()
                        }
                    }
                    MiniAction("↓") {
                        if (index < list.size - 1) {
                            val tmp = list[index + 1]
                            list[index + 1] = list[index]
                            list[index] = tmp
                            persist()
                        }
                    }
                    MiniAction("✕") {
                        list.removeAt(index)
                        persist()
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = stringResource(R.string.shell_hotkey_add),
                    onClick = { editing = -1 },
                )
                TextButton(
                    text = stringResource(R.string.shell_hotkey_reset),
                    onClick = {
                        list.clear()
                        list.addAll(HidKeys.DEFAULTS)
                        persist()
                    },
                )
            }
        }
    }

    val editIndex = editing
    if (editIndex != null) {
        HotkeyEditorDialog(
            initial = if (editIndex >= 0) list.getOrNull(editIndex) else null,
            onDismiss = { editing = null },
            onConfirm = { combo ->
                if (editIndex >= 0 && editIndex < list.size) list[editIndex] = combo else list.add(combo)
                editing = null
                persist()
            },
        )
    }
}

/** 编辑一条快捷键：修饰键（可多选）+ 按键（按组选） */
@Composable
private fun HotkeyEditorDialog(
    initial: HidKeys.Combo?,
    onDismiss: () -> Unit,
    onConfirm: (HidKeys.Combo) -> Unit,
) {
    var mod by remember { mutableIntStateOf(initial?.mod ?: 0) }
    var usage by remember { mutableIntStateOf(initial?.usage ?: 0x06) }
    val cs = MiuixTheme.colorScheme

    WindowDialog(
        show = true,
        title = stringResource(R.string.shell_hotkey_edit),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 修饰键：可多选（Ctrl+Shift+A 这类要能配出来）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, bit) in HidKeys.MOD_LABELS) {
                    val on = mod and bit != 0
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) cs.primary else cs.surfaceVariant)
                            .clickable { mod = if (on) mod and bit.inv() else mod or bit }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            color = if (on) cs.onPrimary else cs.onSurface,
                        )
                    }
                }
            }
            Text(
                text = HidKeys.comboText(HidKeys.Combo("", mod, usage)),
                fontSize = 13.sp,
            )
            // 按键：按组列出（字母 / 数字 / 符号 / 编辑键 / 方向键 / 功能键）
            for ((group, keys) in HidKeys.KEY_GROUPS) {
                Text(text = group, fontSize = 12.sp)
                for ((label, u) in keys) {
                    val on = usage == u
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        color = if (on) cs.primary else cs.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { usage = u }
                            .padding(vertical = 6.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = stringResource(R.string.ui_common_done),
                    onClick = {
                        onConfirm(
                            HidKeys.Combo(
                                label = HidKeys.comboText(HidKeys.Combo("", mod, usage)),
                                mod = mod,
                                usage = usage,
                            )
                        )
                    },
                )
                TextButton(text = stringResource(R.string.ui_common_close), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun MiniAction(text: String, onClick: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = text, fontSize = 12.sp)
    }
}
