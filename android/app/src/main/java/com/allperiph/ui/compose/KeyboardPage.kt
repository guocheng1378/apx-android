package com.allperiph.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
import com.allperiph.hid.HotkeyStore
import com.allperiph.hid.HotkeyTemplates
import com.allperiph.ui.Feedback
import com.allperiph.ui.KeyLayouts
import com.allperiph.ui.KeyPref
import com.allperiph.ui.miuix.ApComponents
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 键盘页（Compose + Miuix）：一页两个面板，**与被控操控面（`TouchpadActivity`）键盘页同款布局**。
 *
 * 布局口径（用户要求「键盘布局应该和被控页的一样」）：
 * - 快捷 / 遥控 / 游戏 / 数字 / 九宫格 / 方向 / F 区 / 自定义，共 8 套，
 *   名称与顺序与操控面 `kbNames` 完全一致，两边的数据源也共用
 *   （`KeyPref.letterRows` + `KeyLayouts`），改一处两边同时变；
 * - 「快捷」= `HidKeys.MOD_LABELS` sticky 修饰排 + `KeyPref.letterRows` 五行（与 `quickKeyboard` 同款）；
 * - 「遥控」走 `HotkeyController` Consumer 位图（与 `remoteKeyboard` 同一条链路），
 *   关机 / 重启只在主屏保留（opcode 0x22 需要被控端 root，且有利于远程救机）；
 * - 右端的「全键盘」是同一套键盘的**弹窗形态**（旧 `KeyboardPanels` 就是弹窗，习惯保留），
 *   从任何页面一眼可开，键位与页签跟主体那块一模一样。
 *
 * 「快捷面板」是主屏自己的东西：`HotkeyStore` 里的组合键网格（单击发送 / 长按编辑 /
 * 新增 / 模板行），与被控操控面的 8 套键位互不影响。
 *
 * v223.24 修正在两处：
 * 1. 候选键 usage 全部**显式写出** —— 此前用 `usageOf("F11")` 这类**多字符标签**反查，
 *    而 `usageOf` 只认单字符，落到首字母的 usage（全屏发成 f、返回发成 b、音量发成 v），
 *    正是用户报的「快捷键发不对」；存量坏值由 [HidKeys.USAGE_REPAIRS] 在读取时修掉；
 * 2. 媒体键以**真实 Consumer usage** 落库，[HidKeys.combo] 统一路由到 HotkeyController
 *    位图（与操控面媒体键同一条链路）—— 否则 0xB2 会进键盘报告，对端认不出。
 */
@Composable
fun ApKeyboardPage(topBar: Dp = 0.dp, bottomGap: Dp = 0.dp) {
    val ctx = LocalContext.current
    val cands = candidates()
    var items by remember { mutableStateOf(HotkeyStore.load(ctx)) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var addOpen by remember { mutableStateOf(false) }
    /** 全键盘弹窗（模板行右端 / 编辑器里的「全键盘」入口打开） */
    var kbOpen by remember { mutableStateOf(false) }
    /** 面板切换：0 = 整块虚拟键盘（页面主体），1 = 快捷面板（组合键网格） */
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 页面标题一律不画（成宇 2026-11-02「把所有页面标题都去除」）。
            // 这里原来固定写「快捷键（长按可编辑）」，正文却是整块虚拟键盘，
            // 看着就是「快捷键映射的是触屏页的内容」—— 标题和内容各说各话。
            // 现在只剩面板切换按钮，正文是什么由按钮高亮说明。
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 成宇 2026-11-02：「键盘页要的是一整块虚拟键盘，加能切换的快捷面板」。
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
                    ApComponents.NarrowButton(
                        text = ctx.getString(R.string.ui_kb_add),
                        onClick = { addOpen = true },
                    )
                }
            }
        }
        if (panel == 0) {
            // 虚拟键盘占主体；onClose = null 表示不是弹窗，不画关闭按钮
            ApFullKeyboard(onClose = null, bottomGap = bottomGap)
        } else {
            // 模板行：`HotkeyTemplates.ALL` 摊成一行 chip，点一下套用，存储仍是 HotkeyStore
            //（与设置页「快捷键模板」同一份，两边互不打架）。
            // 右端「全键盘」= 同一套 8 页签键盘的弹窗形态（旧 KeyboardPanels 就是想换布局
            // 只能手动改键，这里直接给整块键盘）。
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
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_full),
                    onClick = { kbOpen = true },
                )
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

    if (kbOpen) {
        KbWindow(onDismiss = { kbOpen = false })
    }

    if (editIndex in items.indices) {
        val cur = items[editIndex]
        ApComponents.DialogCard(onDismiss = { editIndex = -1 }) {
            Text(text = HidKeys.comboText(cur), fontSize = 16.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_move_up),
                    onClick = {
                        if (editIndex > 0) {
                            val l = items.toMutableList()
                            val v = l.removeAt(editIndex)
                            l.add(editIndex - 1, v)
                            commit(l)
                        }
                        editIndex = -1
                    },
                )
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_move_down),
                    onClick = {
                        if (editIndex < items.size - 1) {
                            val l = items.toMutableList()
                            val v = l.removeAt(editIndex)
                            l.add(editIndex + 1, v)
                            commit(l)
                        }
                        editIndex = -1
                    },
                )
            }
            // 改键：候选键来自 candidates()（usage 显式写出，不再是「首字母」错值）
            ApComponents.NarrowButton(
                text = ctx.getString(R.string.ui_kb_change_key),
                onClick = {
                    val l = items.toMutableList()
                    val at = cands.indexOfFirst { it.usage == cur.usage }
                    val next = cands[(if (at < 0) 0 else at + 1) % cands.size]
                    l[editIndex] = HidKeys.Combo(next.label, cur.mod, next.usage)
                    commit(l)
                    editIndex = -1
                },
            )
            ApComponents.NarrowButton(
                text = ctx.getString(R.string.ui_kb_select_keys),
                onClick = { editIndex = -1; kbOpen = true },
            )
            ApComponents.NarrowButton(
                text = ctx.getString(R.string.ui_kb_delete),
                onClick = {
                    val l = items.toMutableList()
                    l.removeAt(editIndex)
                    commit(l)
                    editIndex = -1
                },
            )
            ApComponents.NarrowButton(
                text = ctx.getString(R.string.ui_common_cancel),
                onClick = { editIndex = -1 },
            )
        }
    }

    if (addOpen) {
        ApComponents.DialogCard(onDismiss = { addOpen = false }) {
            Text(text = ctx.getString(R.string.ui_kb_add), fontSize = 16.sp)
            cands.forEach { c ->
                ApComponents.NarrowButton(
                    text = c.label,
                    onClick = {
                        commit(items + HidKeys.Combo(c.label, 0, c.usage))
                        addOpen = false
                    },
                )
            }
            ApComponents.NarrowButton(
                text = ctx.getString(R.string.ui_common_cancel),
                onClick = { addOpen = false },
            )
        }
    }
}

/**
 * 全键盘弹窗：整块虚拟键盘（含 8 页签）装进 [ApComponents.DialogCard]。
 *
 * 高度给到 0.92 屏高 —— 8 页签 + 5 行键位在 0.62 里会被压扁到按不准。
 * 内容用 `weight(1f)` 占满弹窗剩余高度，键盘内部自己按行均分。
 */
@Composable
private fun KbWindow(onDismiss: () -> Unit) {
    ApComponents.DialogCard(onDismiss = onDismiss, heightRatio = 0.92f) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ApFullKeyboard(onClose = onDismiss)
        }
    }
}

/**
 * 候选键：覆盖遥控 / 媒体 / 方向 / 常用编辑键，够日常自定义，不必在手机上敲键名。
 *
 * usage 全部**显式写出**：`usageOf("F11")` 这类多字符标签只会取到首字母（f = 0x09），
 * 存进 HotkeyStore 就是坏值；媒体键存真实 Consumer usage，由 [HidKeys.combo] 路由到位图链路。
 *
 * 放在函数里而不是文件级 `val`：键面文字要从资源取（`ctx` 只有函数内拿得到），
 * 顺便少一处 Kotlin 中文文案。
 */
@Composable
private fun candidates(): List<HidKeys.Combo> {
    val ctx = LocalContext.current
    return remember {
        listOf(
            HidKeys.Combo(ctx.getString(R.string.ui_kb_enter), 0, 0x28),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_back), 0, 0x2A),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_arrow_u), 0, 0x52),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_arrow_d), 0, 0x51),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_arrow_l), 0, 0x50),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_arrow_r), 0, 0x4F),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_home), 0, 0x4A),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_fullscreen), 0, 0x44),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_play), 0, 0xB2),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_vol_up), 0, 0xE9),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_vol_down), 0, 0xEA),
            HidKeys.Combo(ctx.getString(R.string.ui_kb_mute), 0, 0xE2),
        )
    }
}

/** 8 套页签：名称与顺序对齐操控面 `kbNames`（`TouchpadActivity`） */
private val KB_TABS = listOf(
    R.string.ui_kb_tab_quick, R.string.ui_kb_tab_remote, R.string.ui_kb_tab_game,
    R.string.ui_kb_tab_num, R.string.ui_kb_tab_grid9, R.string.ui_kb_tab_arrow,
    R.string.ui_kb_tab_fn, R.string.ui_kb_tab_custom,
)

/** sticky 修饰位的当前快照（HidKeys 全局状态 Compose 感知不到，本地镜像驱动高亮） */
private fun stickySnapshot(): Int =
    HidKeys.MOD_LABELS.fold(0) { acc, (_, bit) ->
        if (HidKeys.isSticky(bit)) acc or bit else acc
    }

/** sticky 镜像翻转一次（toggle 之后调用） */
private fun flipSticky(sticky: Int, bit: Int): Int =
    if (HidKeys.isSticky(bit)) sticky or bit else sticky and bit.inv()

/**
 * 整块虚拟键盘：页签行 + 当前页签键位。
 *
 * @param onClose 非空时右上角画「关闭」（弹窗形态）；主屏面板形态传 null。
 * @param bottomGap 底部额外让出的高度：浮动底栏盖在键盘下沿，最底行的按键必须仍可点。
 */
@Composable
internal fun ApFullKeyboard(onClose: (() -> Unit)? = null, bottomGap: Dp = 0.dp) {
    val ctx = LocalContext.current
    val dens = KeyPref.density(ctx)
    val mechanical = KeyPref.isMechanical(ctx)
    var tab by remember { mutableIntStateOf(0) }
    // 自定义行表要在编辑弹窗保存后刷新
    var customRows by remember { mutableStateOf(KeyPref.customRows(ctx)) }

    Column(
        modifier = Modifier.fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + bottomGap),
    ) {
        // —— 第 1 行：标题 + 关闭 ——
        // 只在弹窗形态画：主屏面板形态的标题由页首那行面板标题承担（v223.25 起
        // 页首标题跟随面板），这里再画一遍就是「全键盘」出现两次。
        if (onClose != null) {
            // 弹窗形态也只要关闭按钮，标题文字同样去掉（与页面口径一致）
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_close),
                    onClick = { onClose.invoke() },
                )
            }
        }

        // —— 第 2 行：页签（与操控面 kbTabs 同名同序）——
        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KB_TABS.forEachIndexed { i, res ->
                ApComponents.Pill(
                    text = ctx.getString(res),
                    color = if (tab == i) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.clickable { tab = i },
                )
            }
        }

        // —— 第 3 行：当前页签键位（weight 占满剩余高度，行内均分）——
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> QuickLayout(dens, mechanical)
                1 -> RemoteLayout()
                2 -> GameLayout(dens, mechanical)
                3 -> KeyGridLayout(KeyLayouts.NUMPAD, dens, mechanical)
                4 -> KeyGridLayout(KeyLayouts.GRID9, dens, mechanical)
                5 -> KeyGridLayout(KeyLayouts.ARROWS, dens, mechanical)
                6 -> KeyGridLayout(KeyLayouts.FN, dens, mechanical)
                else -> CustomLayout(customRows, dens, mechanical) {
                    customRows = KeyPref.customRows(ctx)
                }
            }
        }
    }
}

/** 快捷键盘：sticky 修饰排 + `KeyPref.letterRows` 五行 —— 与操控面 `quickKeyboard()` 同款 */
@Composable
private fun QuickLayout(dens: KeyPref.Density, mechanical: Boolean) {
    val ctx = LocalContext.current
    val rows = KeyPref.letterRows(ctx)
    var sticky by remember { mutableIntStateOf(stickySnapshot()) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 修饰键排（Ctrl / Alt / Shift / Win sticky，与操控面同一份 MOD_LABELS）
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HidKeys.MOD_LABELS.forEach { (text, bit) ->
                ModKey(
                    label = text,
                    active = sticky and bit != 0,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    dens = dens,
                    mechanical = mechanical,
                ) {
                    HidKeys.toggleSticky(bit)
                    sticky = flipSticky(sticky, bit)
                }
            }
        }
        // 数字 / 字母三行 / 功能行（Esc Tab Space Enter ⌫ Del）全随 KeyPref 行表走
        rows.forEach { labels ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                labels.forEach { label ->
                    KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, mechanical) {
                        HidKeys.tap(HidKeys.usageByLabel(label))
                        sticky = 0 // tap 自动清 sticky，同步熄灭修饰键（与操控面 refreshMods 同口径）
                    }
                }
            }
        }
    }
}

/**
 * 遥控键盘：媒体 / 音量（Consumer 位图，与操控面 `remoteKeyboard` 同一条 HotkeyController 链路）
 * + 电源行。
 *
 * 关机 / 重启走 opcode 0x22，需被控端 root；主屏保留这两键是为了「对端卡死时还能远程救」，
 * 被控操控面上这两键已被移除（那边误触代价更大）。弹确认框，与操控面 `confirmPower` 同语义。
 */
@Composable
private fun RemoteLayout() {
    val ctx = LocalContext.current
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var confirm by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val hk = com.allperiph.ui.HotkeyController
    Column(modifier = Modifier.fillMaxSize()) {
        listOf(
            listOf(
                ctx.getString(R.string.ui_kb_prev) to hk.BIT_PREV_TRACK,
                ctx.getString(R.string.ui_kb_play) to hk.BIT_PLAY_PAUSE,
                ctx.getString(R.string.ui_kb_next) to hk.BIT_NEXT_TRACK,
            ),
            listOf(
                ctx.getString(R.string.ui_kb_vol_down) to hk.BIT_VOLUME_DOWN,
                ctx.getString(R.string.ui_kb_mute) to hk.BIT_MUTE,
                ctx.getString(R.string.ui_kb_vol_up) to hk.BIT_VOLUME_UP,
            ),
        ).forEach { defs ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp)) {
                defs.forEach { (label, bit) ->
                    MediaTile(label, Modifier.weight(1f).fillMaxHeight()) {
                        hk.press(bit)
                        handler.postDelayed({ hk.release(bit) }, 60)
                    }
                }
            }
        }
        // 第三行：电源（软，待机 / 唤醒）/ 关机 / 重启 —— 与操控面同一行键位
        Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp)) {
            MediaTile(
                ctx.getString(R.string.ui_touchpad_label_power),
                Modifier.weight(1f).fillMaxHeight(),
            ) {
                hk.press(hk.BIT_POWER)
                handler.postDelayed({ hk.release(hk.BIT_POWER) }, 60)
            }
            MediaTile(
                ctx.getString(R.string.ui_touchpad_label_poweroff),
                Modifier.weight(1f).fillMaxHeight(),
            ) { confirm = 0 to ctx.getString(R.string.ui_touchpad_label_poweroff) }
            MediaTile(
                ctx.getString(R.string.ui_touchpad_label_reboot),
                Modifier.weight(1f).fillMaxHeight(),
            ) { confirm = 1 to ctx.getString(R.string.ui_touchpad_label_reboot) }
        }
    }

    confirm?.let { (action, label) ->
        ApComponents.DialogCard(onDismiss = { confirm = null }) {
            Text(text = ctx.getString(R.string.ui_touchpad_dialog_device, label), fontSize = 16.sp)
            Text(
                text = ctx.getString(R.string.ui_touchpad_dialog_no_only_need, label),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_common_cancel),
                    onClick = { confirm = null },
                )
                ApComponents.NarrowButton(
                    text = label,
                    onClick = {
                        val c = com.allperiph.wireless.ControlTarget.controlClient
                        if (c == null) {
                            android.widget.Toast.makeText(
                                ctx, R.string.ui_touchpad_toast_device_connect_not,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            c.power(action)
                            android.widget.Toast.makeText(
                                ctx, ctx.getString(R.string.ui_touchpad_toast_send_done, label),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        confirm = null
                    },
                )
            }
        }
    }
}

/** 游戏键盘：4×6 高频键位（技能数字 / 左手技能区 / 方向+确认）—— 与操控面 `gameKeyboard` 同款 */
@Composable
private fun GameLayout(dens: KeyPref.Density, mechanical: Boolean) {
    val layout = listOf(
        listOf("Esc", "1", "2", "3", "4", "Tab"),
        listOf("Q", "W", "E", "R", "F", "B"),
        listOf("A", "S", "D", "Shift", "Space", "Ctrl"),
        listOf("↑", "↓", "←", "→", "Enter", "Del"),
    )
    var sticky by remember { mutableIntStateOf(stickySnapshot()) }
    Column(modifier = Modifier.fillMaxSize()) {
        layout.forEach { labels ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp)) {
                labels.forEach { text ->
                    val bit = HidKeys.MOD_LABELS.firstOrNull { it.first == text }?.second
                    if (bit != null) {
                        ModKey(
                            label = text,
                            active = sticky and bit != 0,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            dens = dens,
                            mechanical = mechanical,
                        ) {
                            HidKeys.toggleSticky(bit)
                            sticky = flipSticky(sticky, bit)
                        }
                    } else {
                        KeyBox(text, Modifier.weight(1f).fillMaxHeight(), dens, mechanical) {
                            HidKeys.tap(HidKeys.usageByLabel(text))
                            sticky = 0
                        }
                    }
                }
            }
        }
    }
}

/** 扩展布局（数字 / 九宫格 / 方向 / F 区）：按 [KeyLayouts] 行列渲染，列宽按 weight 均分 */
@Composable
private fun KeyGridLayout(
    rows: List<List<KeyLayouts.Key>>,
    dens: KeyPref.Density,
    mechanical: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        rows.forEach { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp)) {
                row.forEach { k ->
                    KeyBox(k.label, Modifier.weight(k.weight).fillMaxHeight(), dens, mechanical) {
                        HidKeys.tap(k.usage)
                    }
                }
            }
        }
    }
}

/** 自定义布局：[KeyPref.customRows] 行表（与操控面「自定义」同一份数据）；未配置给引导文案 + 编辑入口 */
@Composable
private fun CustomLayout(
    rows: List<List<String>>?,
    dens: KeyPref.Density,
    mechanical: Boolean,
    onEdited: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        if (rows.isNullOrEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = ctx.getString(R.string.ui_touchpad_text_edit_longpress), fontSize = 14.sp)
            }
        } else {
            rows.forEach { labels ->
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    labels.forEach { label ->
                        KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, mechanical) {
                            HidKeys.tap(HidKeys.usageByLabel(label))
                        }
                    }
                }
            }
        }
        ApComponents.ActionButton(
            text = ctx.getString(R.string.ui_common_edit),
            onClick = {
                (ctx as? android.app.Activity)?.let { act ->
                    com.allperiph.ui.CustomKeyboardDialog.show(act) { onEdited() }
                }
            },
        )
    }
}

/** 遥控 / 电源图块：整块可点（与操控面 `mediaTile` / `actionTile` 同一交互，纯文字键面） */
@Composable
private fun MediaTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    // 媒体 / 电源图块也是「按键」，同样震一下、响一声
    val host = LocalView.current
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable { Feedback.tap(host); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

/** 修饰键帽：点亮 = 实底主色（与操控面 refreshMods 同一观感），同样支持机械立体 */
@Composable
private fun ModKey(
    label: String,
    active: Boolean,
    modifier: Modifier,
    dens: KeyPref.Density,
    mechanical: Boolean,
    onClick: () -> Unit,
) {
    // 修饰键同样要震要响（与 KeyBox 同一套 Feedback 口径）
    val host = LocalView.current
    val press: () -> Unit = { Feedback.tap(host); onClick() }
    val shape = RoundedCornerShape(dens.cornerDp.dp)
    val h = (44f * dens.keyScale).dp
    val bg = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
    val fg = if (active) Color.White else MiuixTheme.colorScheme.onSurface
    if (!mechanical) {
        Box(
            modifier = modifier
                .padding(horizontal = dens.gapDp.dp)
                .heightIn(min = h)
                .clip(shape)
                .background(bg)
                .clickable { press() },
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp, color = fg) }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val side = lerp(bg, Color.Black, 0.35f)
    Box(modifier.padding(horizontal = dens.gapDp.dp).heightIn(min = h)) {
        Box(Modifier.matchParentSize().clip(shape).background(side))
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = 3.dp)
                .offset(y = if (pressed) 2.dp else 0.dp)
                .clip(shape)
                .background(bg)
                .clickable(interactionSource = interaction, indication = null, onClick = press),
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp, color = fg) }
    }
}

/**
 * 键帽：平面 = 描边圆角块；机械 = 底座当立体侧面、顶面按下时下沉
 * （与触控板页机械键帽同一档观感）。高度给下限 44dp * 密度倍率，
 * 行高够时由外层 `weight(1f)` 拉满。
 */
@Composable
private fun KeyBox(
    label: String,
    modifier: Modifier,
    dens: KeyPref.Density,
    mechanical: Boolean,
    onClick: () -> Unit,
) {
    // 按键反馈（成宇 2026-11-02「音效、喇叭回声、震动都没有了」）：
    // Compose 迁移时丢掉了 Feedback.tap，键帽只剩视觉下沉。所有 8 套布局的键位
    // 最终都收敛到 KeyBox / ModKey / MediaTile 这三个组件，在这里统一接回，
    // 档位仍由设置页的 ApFeel 控制（关 / 轻 / 标准 / 强，音效 关 / 点击 / 键盘 / 机械轴）。
    val host = LocalView.current
    val press: () -> Unit = { Feedback.tap(host); onClick() }
    val shape = RoundedCornerShape(dens.cornerDp.dp)
    val h = (44f * dens.keyScale).dp
    val bg = MiuixTheme.colorScheme.surfaceContainer
    if (!mechanical) {
        Box(
            modifier = modifier
                .padding(horizontal = dens.gapDp.dp)
                .heightIn(min = h)
                .clip(shape)
                .background(bg)
                .clickable { press() },
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp) }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val side = lerp(bg, Color.Black, 0.35f)
    Box(modifier.padding(horizontal = dens.gapDp.dp).heightIn(min = h)) {
        Box(Modifier.matchParentSize().clip(shape).background(side))
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = 3.dp)
                .offset(y = if (pressed) 2.dp else 0.dp)
                .clip(shape)
                .background(bg)
                .clickable(interactionSource = interaction, indication = null, onClick = press),
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp) }
    }
}