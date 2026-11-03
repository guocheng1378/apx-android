package com.allperiph.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.allperiph.R
import com.allperiph.hid.HidKeys
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
    // 页面标题与面板切换按钮一并去除（成宇 2026-11-02「键盘页不需要快捷键和虚拟键盘按钮，
    // 去除」）：这一页本来就是整块虚拟键盘，正文与按钮重复表达同一件事；
    // 「快捷键（长按可编辑）」那块组合键面板与模板行也整块拆掉 —— 模板/自定义编辑
    // 仍在设置页「快捷键与外设」里（同一份 HotkeyStore，能力没丢，只是不在键盘页占位）。
    // 页首只留 topBar 高度让开玻璃顶栏（顶栏本身也已无文字）。
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(modifier = Modifier.height(topBar))
        ApFullKeyboard(bottomGap = bottomGap)
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
 * @param onClose 非空时右上角画「关闭」—— 这是**悬浮球 overlay 面板形态**要的出口
 *   （overlay 键盘没有底部导航栏可退，不给关闭按钮就收不起来）；主屏键盘页传 null，
 *   页面上一个按钮都不画（成宇 2026-11-02「键盘页不需要快捷键和虚拟键盘按钮，去除」）。
 * @param bottomGap 底部额外让出的高度：浮动底栏（或悬浮键盘面板下沿）盖在键盘上，
 *   最底行的按键必须仍可点。
 */
@Composable
internal fun ApFullKeyboard(onClose: (() -> Unit)? = null, bottomGap: Dp = 0.dp) {
    val ctx = LocalContext.current
    val dens = KeyPref.density(ctx)
    // 键帽材质档（成宇 2026-11-03「键盘再加一组液态玻璃按键的皮肤」）：此前只有一个
    // `mechanical: Boolean`，只有「平面 / 机械」两种可能，液态玻璃这一档没有表达力 ——
    // 改成传档位值，三种材质各自成一支，键帽渲染处不再靠布尔猜。
    val keycap = KeyPref.keycapStyleIndex(ctx)
    // 按键特效档（成宇 2026-11-03「键盘的特效做几个」）：涟漪 / 高光 / 弹跳三档 + 关闭。
    // 与材质正交（材质决定键帽长什么样，特效决定按下去画什么），实现收在 KeyFx.kt 一处。
    val fx = KeyPref.effectIndex(ctx)
    var tab by remember { mutableIntStateOf(0) }
    // 自定义行表要在编辑弹窗保存后刷新
    var customRows by remember { mutableStateOf(KeyPref.customRows(ctx)) }

    Column(
        modifier = Modifier.fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp + bottomGap),
    ) {
        // —— 关闭行：只在悬浮球面板形态画（主屏键盘页 onClose = null，一个按钮都不占位）——
        if (onClose != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ApComponents.NarrowButton(
                    text = ctx.getString(R.string.ui_kb_close),
                    onClick = { onClose.invoke() },
                )
            }
        }

        // —— 第 1 行：页签（与操控面 kbTabs 同名同序）——
        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KB_TABS.forEachIndexed { i, res ->
                ApComponents.Pill(
                    text = ctx.getString(res),
                    color = if (tab == i) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    // 页签也是「一处可点面」：与键帽同一套特效 + 震动（成宇 2026-11-04）
                    modifier = Modifier.apPress(fx, MiuixTheme.colorScheme.primary) { tab = i },
                )
            }
        }

        // —— 第 3 行：当前页签键位（weight 占满剩余高度，行内均分）——
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> QuickLayout(dens, keycap, fx)
                1 -> RemoteLayout()
                2 -> GameLayout(dens, keycap, fx)
                3 -> KeyGridLayout(KeyLayouts.NUMPAD, dens, keycap, fx)
                4 -> KeyGridLayout(KeyLayouts.GRID9, dens, keycap, fx)
                5 -> KeyGridLayout(KeyLayouts.ARROWS, dens, keycap, fx)
                6 -> KeyGridLayout(KeyLayouts.FN, dens, keycap, fx)
                else -> CustomLayout(customRows, dens, keycap, fx) {
                    customRows = KeyPref.customRows(ctx)
                }
            }
        }
    }
}

/** 快捷键盘：sticky 修饰排 + `KeyPref.letterRows` 五行 —— 与操控面 `quickKeyboard()` 同款 */
@Composable
private fun QuickLayout(dens: KeyPref.Density, keycap: Int, fx: Int) {
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
                    keycap = keycap,
                    fx = fx,
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
                    KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
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
private fun GameLayout(dens: KeyPref.Density, keycap: Int, fx: Int) {
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
                            keycap = keycap,
                            fx = fx,
                        ) {
                            HidKeys.toggleSticky(bit)
                            sticky = flipSticky(sticky, bit)
                        }
                    } else {
                        KeyBox(text, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
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
    keycap: Int,
    fx: Int,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        rows.forEach { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp)) {
                row.forEach { k ->
                    KeyBox(k.label, Modifier.weight(k.weight).fillMaxHeight(), dens, keycap, fx) {
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
    keycap: Int,
    fx: Int,
    onEdited: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        if (rows.isNullOrEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // 原来复用的是触控板页那句「长按「自定义」页签编辑布局」—— 可这一页的页签
                // 只接了 clickable，全仓也没有长按处理，照着提示做永远没反应（审查 v5
                // 初稿把这里算成死路；复核后确认下方那枚「编辑」按钮是能用的，所以只是文案错）。
                Text(text = ctx.getString(R.string.ui_kb_custom_empty_hint), fontSize = 14.sp)
            }
        } else {
            rows.forEach { labels ->
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    labels.forEach { label ->
                        KeyBox(label, Modifier.weight(1f).fillMaxHeight(), dens, keycap, fx) {
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
    val fx = KeyPref.effectIndex(LocalContext.current)
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .apPress(fx, MiuixTheme.colorScheme.primary, onClick),
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
    keycap: Int,
    fx: Int,
    onClick: () -> Unit,
) {
    // 修饰键同样要震要响（与 KeyBox 同一套 Feedback 口径）
    val host = LocalView.current
    val press: () -> Unit = { Feedback.tap(host); onClick() }
    val shape = RoundedCornerShape(dens.cornerDp.dp)
    // 审查条 2.2：基础高度 44dp × 倍率（0.92/1.0/1.12）→ 40.5/44/49.3dp，两档低于 48dp
    // 触摸基线。这里只抬「下限」不动倍率的语义：紧凑档靠字号与间隙变紧，不再压缩命中区；
    // 宽松档仍按 1.12 放大（49.3dp）。
    val h = (44f * dens.keyScale).coerceAtLeast(48f).dp
    val bg = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
    val fg = if (active) Color.White else MiuixTheme.colorScheme.onSurface
    val accent = MiuixTheme.colorScheme.primary
    // 特效进度：与键帽材质共用（材质决定形状、特效决定按下画什么）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fxValues = rememberKeyFx(pressed, fx)
    val fxMod = Modifier.keyFx(fxValues, accent)
    if (keycap == KeyPref.KEYCAP_GLASS) {
        // 液态玻璃：半透明键帽 + 高光描边 + 按下泛白跟手（成宇 2026-11-03「液态玻璃按键的皮肤」）
        ApComponents.GlassKeycap(
            modifier = modifier.padding(horizontal = dens.gapDp.dp).heightIn(min = h),
            shape = shape,
            label = label,
            textSize = dens.textSize.sp,
            fg = fg,
            tint = if (active) accent else null,
            interaction = interaction,
            onClick = press,
            fxMod = fxMod,
        )
        return
    }
    if (keycap != KeyPref.KEYCAP_MECHANICAL) {
        Box(
            modifier = modifier
                .padding(horizontal = dens.gapDp.dp)
                .heightIn(min = h)
                .clip(shape)
                .background(bg)
                // ★ 必须把**同一个** interactionSource 交给 clickable（成宇 2026-11-03
                // 「键盘动效没有」）：[rememberKeyFx] 的按压进度来自
                // `interaction.collectIsPressedAsState()`。此前这里用无参 `clickable{}`，
                // Compose 会各自 new 一个内部 source —— 上面这个 `interaction` 永远收不到
                // Press，进度恒为 0，涟漪 \/ 高光 \/ 弹跳三档全部画不出来（机械档与液态玻璃
                // 档当时传对了，所以只有这两档看得到效果）。
                // indication = null：机械档同样不带水波纹，三档观感统一由 [keyFx] 表达。
                .clickable(interactionSource = interaction, indication = null, onClick = press)
                .then(fxMod),
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp, color = fg) }
        return
    }
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
                .clickable(interactionSource = interaction, indication = null, onClick = press)
                .then(fxMod),
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
    keycap: Int,
    fx: Int,
    onClick: () -> Unit,
) {
    // 按键反馈（成宇 2026-11-02「音效、喇叭回声、震动都没有了」）：
    // Compose 迁移时丢掉了 Feedback.tap，键帽只剩视觉下沉。所有 8 套布局的键位
    // 最终都收敛到 KeyBox / ModKey / MediaTile 这三个组件，在这里统一接回，
    // 档位仍由设置页的 ApFeel 控制（关 / 轻 / 标准 / 强，音效 关 / 点击 / 键盘 / 机械轴）。
    val host = LocalView.current
    val press: () -> Unit = { Feedback.tap(host); onClick() }
    val shape = RoundedCornerShape(dens.cornerDp.dp)
    // 审查条 2.2：基础高度 44dp × 倍率（0.92/1.0/1.12）→ 40.5/44/49.3dp，两档低于 48dp
    // 触摸基线。这里只抬「下限」不动倍率的语义：紧凑档靠字号与间隙变紧，不再压缩命中区；
    // 宽松档仍按 1.12 放大（49.3dp）。
    val h = (44f * dens.keyScale).coerceAtLeast(48f).dp
    val bg = MiuixTheme.colorScheme.surfaceContainer
    val accent = MiuixTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fxValues = rememberKeyFx(pressed, fx)
    val fxMod = Modifier.keyFx(fxValues, accent)
    if (keycap == KeyPref.KEYCAP_GLASS) {
        // 液态玻璃键帽：半透明 + 高光描边（成宇 2026-11-03「键盘再加一组液态玻璃按键的皮肤」）
        ApComponents.GlassKeycap(
            modifier = modifier.padding(horizontal = dens.gapDp.dp).heightIn(min = h),
            shape = shape,
            label = label,
            textSize = dens.textSize.sp,
            fg = MiuixTheme.colorScheme.onSurface,
            tint = null,
            interaction = interaction,
            onClick = press,
            fxMod = fxMod,
        )
        return
    }
    if (keycap != KeyPref.KEYCAP_MECHANICAL) {
        Box(
            modifier = modifier
                .padding(horizontal = dens.gapDp.dp)
                .heightIn(min = h)
                .clip(shape)
                .background(bg)
                // ★ 同上：不把 interactionSource 传进来，[rememberKeyFx] 就收不到按压事件，
                // 特效永远不画。这是「键盘动效没有」的真正原因。
                .clickable(interactionSource = interaction, indication = null, onClick = press)
                .then(fxMod),
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp) }
        return
    }
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
                .clickable(interactionSource = interaction, indication = null, onClick = press)
                .then(fxMod),
            contentAlignment = Alignment.Center,
        ) { Text(text = label, fontSize = dens.textSize.sp) }
    }
}
