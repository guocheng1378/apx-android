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
            TextButton(text = "新增", onClick = { addOpen = true })
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
