package com.allperiph.ui.feature.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.ui.KeyPref
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 自定义布局面板（Compose 重构线）—— 旧 `KeyboardPanels.customKeyboard()` + `CustomKeyboardDialog`。
 *
 * 存储仍是 [KeyPref.customRows] / [KeyPref.setCustomRows]（`apx_kb` 里的 JSON 行表），
 * 所以新旧界面看到的是同一套自定义键位，不存在两份数据要同步。
 *
 * 键面文字发键时由 [HidKeys.usageByLabel] 反查 usage：编辑器只给 [HidKeys.KEY_GROUPS] 里的键
 * （全部有确定的 usage），排不出「按不响」的键。
 */
@Composable
fun CustomPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(KeyPref.customRows(context)) }
    var editing by remember { mutableStateOf(false) }
    val cs = MiuixTheme.colorScheme

    if (rows.isNullOrEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.shell_custom_kbd_hint),
                fontSize = 13.sp,
                color = cs.onSurfaceVariantSummary,
            )
            TextButton(
                text = stringResource(R.string.shell_custom_kbd_edit),
                onClick = { editing = true },
            )
        }
    } else {
        Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val data = rows!!
            data.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    row.forEach { label ->
                        KeyCap(label = label, modifier = Modifier.weight(1f)) {
                            HidKeys.tap(HidKeys.usageByLabel(label))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = stringResource(R.string.shell_custom_kbd_edit),
                    onClick = { editing = true },
                )
            }
        }
    }

    if (editing) {
        CustomLayoutEditorDialog(
            initial = rows ?: emptyList(),
            onDismiss = { editing = false },
            onSave = { saved ->
                KeyPref.setCustomRows(context, saved)
                rows = saved
                editing = false
            },
        )
    }
}

/**
 * 编辑器：一行一行排。点键帽删除，行尾「加键」从分组列表里挑，「加一行」在末尾追加。
 *
 * 不用拖动排序：这套界面是「打字之前排一次」的配置动作，不是高频操作，
 * 上下加/删已经够用，拖拽在弹窗里反而容易误触。
 */
@Composable
private fun CustomLayoutEditorDialog(
    initial: List<List<String>>,
    onDismiss: () -> Unit,
    onSave: (List<List<String>>) -> Unit,
) {
    var rows by remember {
        mutableStateOf(initial.map { it.toMutableList() }.toMutableList<List<String>>())
    }
    var pickingRow by remember { mutableStateOf<Int?>(null) }
    val cs = MiuixTheme.colorScheme

    WindowDialog(
        show = true,
        title = stringResource(R.string.ui_custom_kbd_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEachIndexed { keyIndex, label ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(cs.surfaceVariant)
                                .clickable {
                                    val next = rows.toMutableList()
                                    val newRow = next[rowIndex].toMutableList()
                                    newRow.removeAt(keyIndex)
                                    next[rowIndex] = newRow
                                    rows = next
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(text = label, fontSize = 12.sp)
                        }
                    }
                    TextButton(
                        text = stringResource(R.string.shell_custom_kbd_add_key),
                        onClick = { pickingRow = rowIndex },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = stringResource(R.string.shell_custom_kbd_add_row),
                    onClick = { rows = rows + mutableListOf<String>() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = stringResource(R.string.ui_common_save),
                    onClick = { onSave(rows.map { it.toList() }) },
                )
                TextButton(
                    text = stringResource(R.string.ui_common_clear),
                    onClick = { rows = mutableListOf() },
                )
                TextButton(text = stringResource(R.string.ui_common_cancel), onClick = onDismiss)
            }
        }
    }

    val target = pickingRow
    if (target != null) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_custom_kbd_pick_group),
            onDismissRequest = { pickingRow = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for ((group, keys) in HidKeys.KEY_GROUPS) {
                    Text(text = group, fontSize = 12.sp)
                    for ((label, _) in keys) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = rows.toMutableList()
                                    if (target < next.size) {
                                        val newRow = next[target].toMutableList()
                                        newRow.add(label)
                                        next[target] = newRow
                                        rows = next
                                    }
                                    pickingRow = null
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
