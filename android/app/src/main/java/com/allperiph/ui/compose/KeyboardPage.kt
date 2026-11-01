package com.allperiph.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import com.allperiph.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.allperiph.hid.HotkeyTemplates

/**
 * 键盘页（Compose + Miuix）：把原来 `KeyboardPanels` 的 8 套程序化 View 换成一套
 * 「快捷键网格 + 长按编辑」的 Miuix 界面。
 *
 * 交互口径（与需求一一对应）：
 * - **按钮**：每个快捷键是一个可点卡片（Miuix `Card` + `Text`），单击即 `HidKeys.combo` 发出；
 * - **列表**：整体是 `LazyVerticalGrid` 的自适应网格（等价于列表 + 分组，键多时自动换列）；
 * - **长按**：长按弹编辑弹窗（改名/改键/上移/下移/删除），替代原来那套「点一下不知道去哪改」；
 * - **弹窗**：编辑用 `Dialog` 承载 Miuix `Card`；新增键从候选键列表里选，避免手机上敲文本；
 * - **持久化**：每次改动 `HotkeyStore.save` + `markCustom`，与 `KeyboardPanels` 用的是同一份存储，
 *   两条入口（旧 View / 新 Compose）切换不会丢配置。
 */
@Composable
fun ApKeyboardPage() {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(HotkeyStore.load(ctx)) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var addOpen by remember { mutableStateOf(false) }
    /** 面板切换：0 = 整块虚拟键盘（页面主体），1 = 快捷面板（旧快捷键网格） */
    var panel by remember { mutableIntStateOf(0) }

    fun commit(next: List<HidKeys.Combo>) {
        items = next.toMutableList()
        HotkeyStore.save(ctx, next)
        HotkeyStore.markCustom(ctx)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "快捷键（长按可编辑）", fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 成宇 2026-11-02：「键盘页要的是一整块虚拟键盘，加能切换的快捷面板」。
                // 原来全键盘是 Dialog 弹窗、页面主体只剩一张网格 —— 主功能被藏在弹窗里。
                // 现在两者是本页的两个面板：全键盘直接占满主体，网格退为可切换的快捷面板。
                TextButton(
                    text = ctx.getString(R.string.ui_kb_panel_keys),
                    onClick = { panel = 0 },
                )
                TextButton(
                    text = ctx.getString(R.string.ui_kb_panel_hot),
                    onClick = { panel = 1 },
                )
                if (panel == 1) {
                    TextButton(text = "新增", onClick = { addOpen = true })
                }
            }
        }
        if (panel == 0) {
            // 虚拟键盘占主体；onClose = null 表示不是弹窗，不画关闭按钮
            ApFullKeyboard(onClose = null)
        } else {
        // 模板行：旧 `KeyboardPanels` 有 8 套现成键位布局，迁 Compose 后只剩用户自己那份，
        // 想换布局只能手动一个个改键。这里把 `HotkeyTemplates.ALL` 摊成一行 chip，点一下套用，
        // 存储仍是 HotkeyStore（与设置页「快捷键模板」同一份，两边互不打架）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(
                text = ctx.getString(R.string.ui_kb_tpl_default),
                onClick = {
                    items = HidKeys.DEFAULTS.toMutableList()
                    HotkeyStore.save(ctx, HidKeys.DEFAULTS)
                },
            )
            HotkeyTemplates.ALL.forEach { t ->
                TextButton(
                    text = t.name,
                    onClick = {
                        HotkeyStore.applyTemplate(ctx, t)
                        items = HotkeyStore.load(ctx)
                    },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items) { index, combo ->
                Card(modifier = Modifier.height(64.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(combo) {
                                detectTapGestures(
                                    onTap = { HidKeys.combo(combo) },
                                    onLongPress = { editIndex = index },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = HidKeys.comboText(combo),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        }
    }

    if (editIndex in items.indices) {
        val cur = items[editIndex]
        Dialog(onDismissRequest = { editIndex = -1 }) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = HidKeys.comboText(cur), fontSize = 16.sp)
                    Row(modifier = Modifier.padding(vertical = 8.dp)) {
                        TextButton(text = "上移", onClick = {
                            if (editIndex > 0) {
                                val l = items.toMutableList()
                                val v = l.removeAt(editIndex)
                                l.add(editIndex - 1, v)
                                commit(l)
                            }
                            editIndex = -1
                        })
                        TextButton(text = "下移", onClick = {
                            if (editIndex < items.size - 1) {
                                val l = items.toMutableList()
                                val v = l.removeAt(editIndex)
                                l.add(editIndex + 1, v)
                                commit(l)
                            }
                            editIndex = -1
                        })
                    }
                    TextButton(text = "改键（循环换下一个候选键）", onClick = {
                        val l = items.toMutableList()
                        val next = CANDIDATES[(CANDIDATES.indexOfFirst { it.usage == cur.usage } + 1)
                            .coerceAtLeast(0) % CANDIDATES.size]
                        l[editIndex] = HidKeys.Combo(next.label, cur.mod, next.usage)
                        commit(l)
                        editIndex = -1
                    })
                    TextButton(text = "删除", onClick = {
                        val l = items.toMutableList()
                        l.removeAt(editIndex)
                        commit(l)
                        editIndex = -1
                    })
                    TextButton(text = "取消", onClick = { editIndex = -1 })
                }
            }
        }
    }

    if (addOpen) {
        Dialog(onDismissRequest = { addOpen = false }) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "新增快捷键", fontSize = 16.sp)
                    CANDIDATES.take(12).forEach { c ->
                        TextButton(text = c.label, onClick = {
                            commit(items + HidKeys.Combo(c.label, 0, c.usage))
                            addOpen = false
                        })
                    }
                    TextButton(text = "取消", onClick = { addOpen = false })
                }
            }
        }
    }
}

/** 候选键：覆盖遥控/媒体/方向/常用编辑键，够日常自定义，不必在手机上敲键名 */
private val CANDIDATES = listOf(
    HidKeys.Combo("确定", 0, HidKeys.usageOf("Enter")),
    HidKeys.Combo("返回", 0, HidKeys.usageOf("Backspace")),
    HidKeys.Combo("上", 0, HidKeys.usageOf("Up")),
    HidKeys.Combo("下", 0, HidKeys.usageOf("Down")),
    HidKeys.Combo("左", 0, HidKeys.usageOf("Left")),
    HidKeys.Combo("右", 0, HidKeys.usageOf("Right")),
    HidKeys.Combo("Home", 0, HidKeys.usageOf("Home")),
    HidKeys.Combo("播放/暂停", 0, HidKeys.usageOf("PlayPause")),
    HidKeys.Combo("音量+", 0, HidKeys.usageOf("VolumeUp")),
    HidKeys.Combo("音量-", 0, HidKeys.usageOf("VolumeDown")),
    HidKeys.Combo("静音", 0, HidKeys.usageOf("Mute")),
    HidKeys.Combo("全屏", 0, HidKeys.usageOf("F11")),
)

/**
 * 全键盘（旧 `KeyboardPanels` 里那块完整 QWERTY 面板，迁 Compose 时整块丢了）。
 *
 * 每个键按 Android keycode 换成 HID usage（`HidKeyMap.usageOf`）后走 `HidKeys.combo`，
 * 与快捷键网格同一条发送链路；Shift 只对**下一次**按键生效（打一个大写就自动松开，
 * 与手机输入法的高频用法一致），长按 Shift 不做锁定 —— 需要连打大写时用「⇪」。
 */
@Composable
private fun ApFullKeyboard(onClose: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var shift by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(false) }

    fun send(kc: Int, label: String, mod: Int = 0) {
        val usage = com.allperiph.ui.HidKeyMap.usageOf(kc)
        if (usage == 0) return
        val m = mod or (if (caps) 0x02 else 0) or (if (shift) 0x02 else 0)
        HidKeys.combo(HidKeys.Combo(label, m, usage))
        if (shift) shift = false
    }

    fun letterRow(chars: String) = chars.map { it.toString() }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = ctx.getString(R.string.ui_kb_full), fontSize = 15.sp)
            if (onClose != null) {
                TextButton(text = ctx.getString(R.string.ui_kb_close), onClick = { onClose.invoke() })
            }
        }
        // 数字行
        KeyRow(letterRow("1234567890"), shift = shift, onKey = { label ->
            val kc = if (label == "0") android.view.KeyEvent.KEYCODE_0
            else android.view.KeyEvent.KEYCODE_1 + (label.toInt() - 1)
            send(kc, label)
        })
        // qwertyuiop
        KeyRow(letterRow("qwertyuiop"), shift = shift, onKey = { label ->
            val upper = caps xor shift
            send(android.view.KeyEvent.KEYCODE_A + (label[0] - 'a'), if (upper) label.uppercase() else label)
        })
        // asdfghjkl
        KeyRow(letterRow("asdfghjkl"), shift = shift, onKey = { label ->
            val upper = caps xor shift
            send(android.view.KeyEvent.KEYCODE_A + (label[0] - 'a'), if (upper) label.uppercase() else label)
        })
        // ⇧ zxcvbnm ⌫
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            WideKey(if (shift) ctx.getString(R.string.ui_kb_shift_on) else "⇧", Modifier.weight(1.2f)) { shift = !shift }
            "zxcvbnm".forEach { ch ->
                KeyBox(ch.toString(), Modifier.weight(1f)) {
                    val upper = caps xor shift
                    send(android.view.KeyEvent.KEYCODE_A + (ch - 'a'), if (upper) ch.uppercase() else ch.toString())
                }
            }
            WideKey("⌫", Modifier.weight(1.2f)) { send(android.view.KeyEvent.KEYCODE_DEL, "⌫") }
        }
        // ⇪ Tab 空格 Enter
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            WideKey(if (caps) ctx.getString(R.string.ui_kb_caps_on) else "⇪", Modifier.weight(1f)) { caps = !caps }
            WideKey("Tab", Modifier.weight(1f)) { send(android.view.KeyEvent.KEYCODE_TAB, "Tab") }
            WideKey(ctx.getString(R.string.ui_kb_space), Modifier.weight(3f)) { send(android.view.KeyEvent.KEYCODE_SPACE, "Space") }
            WideKey("Enter", Modifier.weight(1.4f)) { send(android.view.KeyEvent.KEYCODE_ENTER, "Enter") }
        }
    }
}

@Composable
private fun KeyRow(keys: List<String>, shift: Boolean, onKey: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        keys.forEach { k ->
            if (k == "a" ) {
                // 第二行起左侧留白，模拟键盘错位（视觉上更像实体键盘）
            }
            KeyBox(k, Modifier.weight(1f)) { onKey(k) }
        }
    }
}

@Composable
private fun KeyBox(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(text = label, fontSize = 15.sp) }
}

@Composable
private fun WideKey(label: String, modifier: Modifier, onClick: () -> Unit) = KeyBox(label, modifier, onClick)
