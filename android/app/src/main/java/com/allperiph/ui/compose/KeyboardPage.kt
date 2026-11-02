package com.allperiph.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.offset
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.hid.HidKeys
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.hid.HotkeyStore
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
fun ApKeyboardPage(topBar: Dp = 0.dp, bottomGap: Dp = 0.dp) {
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
        // 顶栏是玻璃叠层：页面顶部自留 topBar，标题与面板切换不被压在栏下
        Spacer(modifier = Modifier.height(topBar))
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
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_panel_keys),
                    onClick = { panel = 0 },
                )
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_panel_hot),
                    onClick = { panel = 1 },
                )
                if (panel == 1) {
                    ApComponents.NarrowButton(text = "新增", onClick = { addOpen = true })
                }
            }
        }
        if (panel == 0) {
            // 虚拟键盘占主体；onClose = null 表示不是弹窗，不画关闭按钮
            ApFullKeyboard(onClose = null, bottomGap = bottomGap)
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
            ApComponents.NarrowButton(
                text = ctx.getString(R.string.ui_kb_tpl_default),
                onClick = {
                    items = HidKeys.DEFAULTS.toMutableList()
                    HotkeyStore.save(ctx, HidKeys.DEFAULTS)
                },
            )
            HotkeyTemplates.ALL.forEach { t ->
                ApComponents.NarrowButton(
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
            modifier = Modifier.fillMaxSize().padding(bottom = bottomGap),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items) { index, combo ->
                ApComponents.Card(modifier = Modifier.height(64.dp)) {
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
        ApComponents.DialogCard(onDismiss = { editIndex = -1 }) {
            Text(text = HidKeys.comboText(cur), fontSize = 16.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                ApComponents.NarrowButton(text = "上移", onClick = {
                    if (editIndex > 0) {
                        val l = items.toMutableList()
                        val v = l.removeAt(editIndex)
                        l.add(editIndex - 1, v)
                        commit(l)
                    }
                    editIndex = -1
                })
                ApComponents.NarrowButton(text = "下移", onClick = {
                    if (editIndex < items.size - 1) {
                        val l = items.toMutableList()
                        val v = l.removeAt(editIndex)
                        l.add(editIndex + 1, v)
                        commit(l)
                    }
                    editIndex = -1
                })
            }
            ApComponents.NarrowButton(text = "改键（循环换下一个候选键）", onClick = {
                val l = items.toMutableList()
                val next = CANDIDATES[(CANDIDATES.indexOfFirst { it.usage == cur.usage } + 1)
                    .coerceAtLeast(0) % CANDIDATES.size]
                l[editIndex] = HidKeys.Combo(next.label, cur.mod, next.usage)
                commit(l)
                editIndex = -1
            })
            ApComponents.NarrowButton(text = "删除", onClick = {
                val l = items.toMutableList()
                l.removeAt(editIndex)
                commit(l)
                editIndex = -1
            })
            ApComponents.NarrowButton(text = "取消", onClick = { editIndex = -1 })
        }
    }

    if (addOpen) {
        ApComponents.DialogCard(onDismiss = { addOpen = false }) {
            Text(text = "新增快捷键", fontSize = 16.sp)
            CANDIDATES.take(12).forEach { c ->
                ApComponents.NarrowButton(text = c.label, onClick = {
                    commit(items + HidKeys.Combo(c.label, 0, c.usage))
                    addOpen = false
                })
            }
            ApComponents.NarrowButton(text = "取消", onClick = { addOpen = false })
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
private fun ApFullKeyboard(onClose: (() -> Unit)? = null, bottomGap: Dp = 0.dp) {
    val ctx = LocalContext.current
    var shift by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(false) }

    // 键盘外观三项（键位排列 // 键帽密度 // 机械键帽）与设置页同一份 KeyPref ——
    // 此前只被旧触控板页的键盘面板消费，主屏写死 QWERTY 调了没反应（v223.23 真机反馈）。
    // 每次进入本页都现读：设置页改完切一次页签回来就是新样子，不要求重启。
    val rows = com.allperiph.ui.KeyPref.letterRows(ctx)
    val dens = com.allperiph.ui.KeyPref.density(ctx)
    val mechanical = com.allperiph.ui.KeyPref.isMechanical(ctx)

    fun send(kc: Int, label: String, mod: Int = 0) {
        val usage = com.allperiph.ui.HidKeyMap.usageOf(kc)
        if (usage == 0) return
        val m = mod or (if (caps) 0x02 else 0) or (if (shift) 0x02 else 0)
        HidKeys.combo(HidKeys.Combo(label, m, usage))
        if (shift) shift = false
    }

    // 字母/数字/符号键按**键面文字**反查 usage（与旧触控板键盘面板同一条链路）：
    // 键位排列换成 AZERTY/Dvorak 后，同一颗键发的仍是它键面字符的 usage。
    fun sendLabel(label: String, shown: String) {
        val usage = HidKeys.usageByLabel(label)
        if (usage == 0) return
        val m = (if (caps) 0x02 else 0) or (if (shift) 0x02 else 0)
        HidKeys.combo(HidKeys.Combo(shown, m, usage))
        if (shift) shift = false
    }

    // 底部多让 bottomGap：浮动底栏盖在键盘下沿，最底行的按键必须仍可点
    Column(
        modifier = Modifier.fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + bottomGap),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = ctx.getString(R.string.ui_kb_full), fontSize = 15.sp)
            if (onClose != null) {
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_close),
                    onClick = { onClose.invoke() },
                )
            }
        }
        // 数字行（含 - =，随 KeyPref 行表）
        KeyRow(rows[0], shift, dens, mechanical) { label -> sendLabel(label, label) }
        // 字母三行随键位排列走（QWERTY // AZERTY // Dvorak）
        val upper = caps xor shift
        KeyRow(rows[1], shift, dens, mechanical) { label -> sendLabel(label, if (upper) label else label.lowercase()) }
        KeyRow(rows[2], shift, dens, mechanical) { label -> sendLabel(label, if (upper) label else label.lowercase()) }
        // ⇧ + 第三字母行 + ⌫（宽键保留：Shift//退格不在行表内）
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            WideKey(if (shift) ctx.getString(R.string.ui_kb_shift_on) else "⇧", Modifier.weight(1.2f), dens, mechanical) { shift = !shift }
            rows[3].forEach { label ->
                KeyBox(label, Modifier.weight(1f), dens, mechanical) {
                    sendLabel(label, if (upper) label else label.lowercase())
                }
            }
            WideKey("⌫", Modifier.weight(1.2f), dens, mechanical) { send(android.view.KeyEvent.KEYCODE_DEL, "⌫") }
        }
        // ⇪ Tab 空格 Enter
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            WideKey(if (caps) ctx.getString(R.string.ui_kb_caps_on) else "⇪", Modifier.weight(1f), dens, mechanical) { caps = !caps }
            WideKey("Tab", Modifier.weight(1f), dens, mechanical) { send(android.view.KeyEvent.KEYCODE_TAB, "Tab") }
            WideKey(ctx.getString(R.string.ui_kb_space), Modifier.weight(3f), dens, mechanical) { send(android.view.KeyEvent.KEYCODE_SPACE, "Space") }
            WideKey("Enter", Modifier.weight(1.4f), dens, mechanical) { send(android.view.KeyEvent.KEYCODE_ENTER, "Enter") }
        }
    }
}

@Composable
private fun KeyRow(
    keys: List<String>,
    shift: Boolean,
    dens: com.allperiph.ui.KeyPref.Density,
    mechanical: Boolean,
    onKey: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        keys.forEach { k -> KeyBox(k, Modifier.weight(1f), dens, mechanical) { onKey(k) } }
    }
}

@Composable
private fun KeyBox(
    label: String,
    modifier: Modifier,
    dens: com.allperiph.ui.KeyPref.Density,
    mechanical: Boolean,
    onClick: () -> Unit,
) {
    // 键帽密度：圆角/间距/字号/键高一起变（紧凑 0.92//标准 1.0//宽松 1.12 行高倍率）
    val shape = RoundedCornerShape(dens.cornerDp.dp)
    val h = (44f * dens.keyScale).dp
    val bg = MiuixTheme.colorScheme.surfaceContainer
    if (!mechanical) {
        Box(
            modifier = modifier
                .padding(horizontal = dens.gapDp.dp)
                .height(h)
                .clip(shape)
                .background(bg)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp) }
        return
    }
    // 机械：底座当立体侧面，顶面按下时下沉 —— 与触控板页机械键帽同一档观感
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val side = lerp(bg, Color.Black, 0.35f)
    Box(modifier.padding(horizontal = dens.gapDp.dp).height(h)) {
        Box(Modifier.matchParentSize().clip(shape).background(side))
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = 3.dp)
                .offset(y = if (pressed) 2.dp else 0.dp)
                .clip(shape)
                .background(bg)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp) }
    }
}

@Composable
private fun WideKey(
    label: String,
    modifier: Modifier,
    dens: com.allperiph.ui.KeyPref.Density,
    mechanical: Boolean,
    onClick: () -> Unit,
) = KeyBox(label, modifier, dens, mechanical, onClick)
