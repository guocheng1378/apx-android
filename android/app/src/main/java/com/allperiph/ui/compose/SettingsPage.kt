package com.allperiph.ui.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allperiph.R
import com.allperiph.touchpad.GestureShield
import com.allperiph.ui.Feedback
import com.allperiph.ui.IssueLauncher
import com.allperiph.ui.FloatingBallService
import com.allperiph.ui.ThemePref
import com.allperiph.ui.GlobalKeyForwarder
import com.allperiph.ui.HidKeyOverrides
import com.allperiph.ui.ApBackdrop
import com.allperiph.ui.ApFeel
import com.allperiph.ui.ApPalette
import com.allperiph.ui.ApSkin
import com.allperiph.ui.OrientLock
import com.allperiph.ui.ApTiming
import com.allperiph.hid.HotkeyStore
import com.allperiph.ui.UpdateChecker
import androidx.compose.ui.window.Dialog
import com.allperiph.ui.miuix.ApComponents
import com.allperiph.ui.miuix.MiuixTokens
import com.allperiph.ui.miuix.rememberApInsets
import com.allperiph.ui.miuix.rememberApAdaptiveLayout
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import androidx.compose.ui.unit.sp
import com.allperiph.ui.BallConfig
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width

/**
 * 设置页（阶段 4，**本轮只迁核心分组**）。
 *
 * 覆盖范围与旧 `SettingsFragment` 的对照：
 *  · 外观：主题明暗（[ThemePref]）、取色方案（品牌蓝 / 跟随系统）、三处皮肤（触控板 / 键盘 / 快捷键）、
 *    动效、震动、音效档位 —— 与旧 `renderTheme` / `renderSkins` / `renderFx` 一一对应；
 *  · 通用：光标锁定（`apx` 偏好里的 `cursor_lock`）、三指手势遮蔽（[GestureShield]）、
 *    LocalSend 互传（`localsend_enabled` + 服务启停）；
 *  · 键盘外观：键帽材质 / 键帽密度 / 键位排列（[com.allperiph.ui.KeyPref]）——
 *    这三项在迁 Compose 之前只有实现、没有入口，2026-11-01 补回；
 *  · 音频方向：USB（UAC2）与 Wi‑Fi 两套音频的麦克风 / 音箱方向开关 —— 同理补回，
 *    模块在跑就热切换，没跑就只落盘；
 *  · 输入与外设：虚拟手柄（`GamepadActivity`）、外接触控板 root 直读（`RootPointerBridge`）；
 *  · 关于：版本行 + 检查更新 + 意见反馈；**长按版本行**进入 Miuix 组件总览（开发页）。
 *
 * 「补回入口」的判据与把关由 `scripts/check_entrypoints.py` 守（CI 的 protocol job）：
 * 清单里 exported=false 又全仓零启动的 Activity、零调用的能力 API、页面手写偏好键，都会变红。
 *
 * **仍未迁移**（旧 `SettingsFragment` 里还有、本页没有的）：遥控器配对（TV 连接）的旧交互。
 * 其余分组（快捷键模板与自定义、键映射、环境诊断、文件与投屏、主题导入导出、背景图、
 * 运行日志）都已在本页落地 —— 此前这段注释一直写着"未迁移"，与本文件实际内容不符。
 */
@Composable
fun SettingsPage(ctx: Context, onRecreate: () -> Unit, topBar: Dp = 0.dp, bottomGap: Dp = 0.dp) {
    val insets = rememberApInsets()
    var tplVersion by remember { mutableIntStateOf(0) }
    // 档位值放在 state 里：下拉组件是受控的，改完要立刻反映到界面
    var themeMode by remember { mutableIntStateOf(ThemePref.get(ctx)) }
    var colorSource by remember { mutableIntStateOf(ApPalette.colorSource(ctx)) }
    var motion by remember { mutableIntStateOf(ApTiming.motionIndex(ctx)) }
    var haptic by remember { mutableIntStateOf(ApFeel.hapticIndex(ctx)) }
    var sound by remember { mutableIntStateOf(ApFeel.soundIndex(ctx)) }
    // —— 背景（成宇 2026-11-03「设置里的背景图也要挪到主题外观分组」「背景图可以动态的吗」）——
    // 沿 ApBackdrop 既有路径：键 KEY_BG_IMAGE="bg_image"、读 ApBackdrop.bgImage(ctx)、
    // 写 ApBackdrop.setBgImage(ctx, uri)；解码由 Backdrop / ApBackdropSurface 做（采样到两倍屏宽）。
    // 选图必须 takePersistableUriPermission，否则重启后 URI 失效、背景变回纯色。
    //
    // ⚠️ 声明位置必须在 `if (cat == 0)` **之前**：这一段原来在 cat3 的上面，而 cat0 的
    // 背景组现在要用到它 —— Kotlin 的局部变量不允许前向引用（v223.29.3 的 CI 首轮失败
    // 就是同一类错误：段落搬移只搬了 UI，状态声明留在旧位置）。
    var bgUri by remember { mutableStateOf(ApBackdrop.bgImage(ctx).orEmpty()) }
    var bgId by remember { mutableStateOf(ApBackdrop.bgId(ctx)) }
    var bgAnim by remember { mutableStateOf(ApBackdrop.bgAnim(ctx)) }
    val bgPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            ApBackdrop.setBgImage(ctx, uri.toString())
            bgUri = uri.toString()
            Toast.makeText(ctx, "背景图已更新", Toast.LENGTH_SHORT).show()
        }
    }

    val apxPrefs = remember { ctx.getSharedPreferences("apx", Context.MODE_PRIVATE) }
    var cursorLock by remember { mutableStateOf(apxPrefs.getBoolean("cursor_lock", true)) }
    var shield by remember { mutableStateOf(GestureShield.isEnabled(ctx)) }
    // 开关显示**实况**，不显示意愿位：`localsend_enabled` 只是「用户希望开着」的记录，
    // 进程一死服务就没了，重启后开关却还是「开」（审查 v5 §9.3 的补丁 0004）。
    var localSend by remember { mutableStateOf(com.allperiph.localsend.LocalSendService.isRunning) }
    val localSendWanted = apxPrefs.getBoolean("localsend_enabled", false)

    // 大屏（平板 / 折叠展开 / 横屏）不再把设置项拉满整屏：限宽 680dp 并水平居中；
    // 手机宽度本来就 < 680dp，限宽不生效，观感与之前一致。
    // 分类导航（旧版设置就是「分类 → 二级页」；Compose 迁移后一度退化成 16 段平铺）
    // 这里按**连续区间**分组，不搬动 section 代码本身，避免大范围挪动引入编译错误。
    var cat by remember { mutableIntStateOf(0) }
    // 切分类必须回到顶部：四类的段数与高度都不同，沿用上一类的滚动偏移会直接落到空白处
    val pageScroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(cat) { pageScroll.scrollTo(0) }
    // 返回键（审查条 2.6）：旧设置页有 btnBackHome 回分类首页，随 page_settings.xml 一起丢了。
    // 分类非 0 时先归零（与上面切分类回顶同一层），归零后让交给外层壳处理页签。
    androidx.activity.compose.BackHandler(enabled = cat != 0) { cat = 0 }
    val catLabels = listOf(
        ctx.getString(R.string.ui_set_cat_theme), ctx.getString(R.string.ui_set_cat_keys),
        ctx.getString(R.string.ui_set_cat_tools), ctx.getString(R.string.ui_set_cat_data),
    )
    // 宽屏（≥600dp，ApAdaptiveLayout.twoPane）：分类竖排在左侧当「列表」，内容在右当「详情」。
    // 这一步只重排外壳，16 个 Section 的代码位置与前一轮完全一致；同类目内再分两栏需要把
    // 每段拆成可组合函数，属另一轮。
    val twoPane = rememberApAdaptiveLayout().twoPane
    Column(modifier = Modifier.fillMaxSize()) {
    if (!twoPane) {
    // 竖屏页签：四个分类名合计 20 个汉字（主题与外观 / 快捷键与外设 / 工具与传输 / 数据与维护），
    // 400dp 宽的手机上每个只剩约 89dp，装不下 6 个汉字 —— 真机（v223.22）上「快捷键与外设」
    // 「工具与传输」被压成两行、整行顶到右边界，观感像被裁切。
    //
    // 修法不是缩字号也不是改文案（后者要动 strings.xml，还会丢分类语义），而是给这一行
    // **横向滚动**：可横滑的 Row 给子项无限宽约束，Miuix `TextButton` 内部就不会再折行，
    // 窄屏横滑即可看全；宽屏（≥600dp 走 twoPane）仍是左侧竖排，不受影响。
    // padding 放在 horizontalScroll 之前：左右留白固定，只有按钮参与滚动。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp + insets.left, end = 12.dp + insets.right, top = 4.dp + topBar)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        catLabels.forEachIndexed { idx, name ->
            // 审查条 2.1：加选中态（此前四个页签一模一样，切完看不出在哪一类）
            ApComponents.TabChip(text = name, selected = idx == cat, onClick = { cat = idx })
        }
    }

    }
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
    if (twoPane) {
        Column(
            modifier = Modifier
                .width(180.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                // 左侧分类列只垫 8dp：横屏那张低栏挪到了**右上角**，左侧这一竖列
                // 与它不重叠，跟着垫 96dp 只会在左上角留一段没用的空档。
                .padding(start = 12.dp + insets.left, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            catLabels.forEachIndexed { idx, name ->
                ApComponents.TabChip(
                    text = name,
                    selected = idx == cat,
                    onClick = { cat = idx },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .verticalScroll(pageScroll)
                .padding(
                    start = 16.dp + insets.left,
                    end = 16.dp + insets.right,
                    // twoPane 时右侧详情区顶部要避开顶栏；竖屏内容在页签行之下，无需再让
                    top = if (twoPane) 8.dp + topBar else 8.dp,
                    // 主壳已不再容器级垫底栏空档，这里自留（96dp 是迁移期口径，不回退）
                    // 主壳传来的 bottomGap 就是最终值：竖屏 88dp（底栏自身高度 + 呼吸），
                    // 横屏 24dp —— 横屏的低栏已挪到右上角，底部不再需要为它预留空档
                    // （成宇 2026-11-02「横屏后悬浮低栏可以悬浮在右上角」）。
                    // 此前这里写的是 maxOf(bottomGap, 96.dp)，那个 96 的硬地板会把横屏的
                    // 24dp 顶掉，空档搬不到顶部去。
                    bottom = bottomGap + insets.bottom,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ———————————————— 外观 ————————————————
        if (cat == 0) {
            ApComponents.Section(title = ctx.getString(R.string.ui_main_label_theme)) {
                Column {
                    WindowDropdownPreference(
                        title = "主题明暗",
                        summary = ThemePref.LABELS.getOrElse(themeMode) { "" },
                        items = ThemePref.LABELS,
                        selectedIndex = themeMode,
                        onSelectedIndexChange = { i ->
                            Feedback.haptic(ctx)
                            themeMode = i
                            ThemePref.set(ctx, i)
                            // 明暗写的是 configuration，必须重建才生效（与旧实现同一处理）
                            onRecreate()
                        },
                    )
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_main_label_color_source),
                        summary = ApPalette.COLOR_SOURCE_LABELS.getOrElse(colorSource) { "" },
                        items = ApPalette.COLOR_SOURCE_LABELS,
                        selectedIndex = colorSource,
                        onSelectedIndexChange = { i ->
                            Feedback.haptic(ctx)
                            colorSource = i
                            ApPalette.setColorSource(ctx, i)
                            ApPalette.applyRuntimeTheme(ctx)
                            onRecreate()
                        },
                    )
                }
            }

            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_skin)) {
                Column {
                    ApSkin.SCOPES.forEach { (scope, title) ->
                        val current = ApSkin.current(ctx, scope)
                        val names = ApSkin.PRESETS.map { it.name }
                        val selected = ApSkin.PRESETS.indexOfFirst { it.id == current.id }
                            .coerceAtLeast(0)
                        WindowDropdownPreference(
                            title = title,
                            summary = if (ApSkin.picked(ctx, scope).isBlank()) "跟随主题" else current.name,
                            items = names,
                            selectedIndex = selected,
                            onSelectedIndexChange = { i ->
                                Feedback.haptic(ctx)
                                ApSkin.set(ctx, scope, ApSkin.PRESETS[i].id)
                                ApAccentSync.apply(ctx)
                                onRecreate()
                            },
                        )
                    }
                }
            }

            ApComponents.Section(title = ctx.getString(R.string.ui_main_label_motion)) {
                Column {
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_main_label_motion),
                        summary = ApTiming.MOTION_LABELS.getOrElse(motion) { "" },
                        items = ApTiming.MOTION_LABELS,
                        selectedIndex = motion,
                        onSelectedIndexChange = { i ->
                            motion = i
                            ApTiming.setMotion(ctx, i)
                            Feedback.haptic(ctx)
                        },
                    )
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_main_label_sound),
                        summary = ApFeel.SOUND_LABELS.getOrElse(sound) { "" },
                        items = ApFeel.SOUND_LABELS,
                        selectedIndex = sound,
                        onSelectedIndexChange = { i ->
                            sound = i
                            ApFeel.setSound(ctx, i)
                        },
                    )
                    // 「震动」按成宇 2026-11-03「震动没有映射过来，映射到外设模式里了」搬到
                    // 「快捷键与外设 → 通用」：音效是**出声**（外观体验），震动是**按键手感**
                    // （手机怎么响应输入），与光标锁定 \/ 手势遮蔽同类。这里只留动效与音效。
                }
            }

            // ———————————————— 背景图（成宇 2026-11-03 从「数据与维护」搬来） ————————————————
            // 背景是外观项：预设底色 + 相册图 + 动态背景三件事都在这一段。
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_bg)) {
                Column {
                    ApComponents.ValueRow(
                        title = ctx.getString(R.string.ui_set_bg_current),
                        value = ctx.getString(
                            if (bgUri.isBlank()) R.string.ui_set_bg_current_none
                            else R.string.ui_set_bg_current_set,
                        ),
                    )
                    ApComponents.ActionButton(
                        text = "选择图片",
                        onClick = { bgPicker.launch(arrayOf("image/*")) },
                    )
                    if (bgUri.isNotBlank()) {
                        ApComponents.ActionButton(
                            text = "清除背景图",
                            onClick = {
                                ApBackdrop.setBgImage(ctx, null)
                                bgUri = ""
                                Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    // 预设底色：与「皮肤」一样是枚举，用下拉（成宇 2026-11-02「能用下拉列表的不用」）
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_set_bg_preset),
                        summary = ApBackdrop.BG_PRESETS
                            .firstOrNull { it.id == bgId }?.name
                            ?: ctx.getString(R.string.ui_set_bg_follow_theme),
                        items = ApBackdrop.BG_PRESETS.map { it.name },
                        selectedIndex = ApBackdrop.BG_PRESETS
                            .indexOfFirst { it.id == bgId }.coerceAtLeast(0),
                        onSelectedIndexChange = { i ->
                            bgId = ApBackdrop.BG_PRESETS[i].id
                            ApBackdrop.setBg(ctx, bgId)
                            onRecreate()
                        },
                    )
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_bg_anim),
                        summary = ctx.getString(R.string.ui_set_bg_anim_sum),
                        checked = bgAnim,
                        onCheckedChange = { on ->
                            bgAnim = on
                            ApBackdrop.setBgAnim(ctx, on)
                            Feedback.haptic(ctx)
                            // 主壳与两个自绘页都读同一份偏好，重建一次让当场生效
                            onRecreate()
                        },
                    )
                    ApComponents.ValueRow(
                        title = ctx.getString(R.string.ui_set_bg_scope),
                        value = ctx.getString(R.string.ui_set_bg_scope_value),
                    )
                }
            }
            // 为什么需要这一段：KeyPref 的三个偏好（键帽材质 / 键帽密度 / 键位排列）此前
            // **只有实现、没有入口** —— setKeycapStyle / setDensity / setLayout 全仓零调用，
            // 用户想换机械键帽或 Dvorak 只能改代码。这里给唯一的一组入口，初值直接读
            // KeyPref 自己的 index（不另立一份状态，避免"界面显示 A、实际渲染 B"）。
            var kbStyle by remember { mutableIntStateOf(com.allperiph.ui.KeyPref.keycapStyleIndex(ctx)) }
            var kbDensity by remember { mutableIntStateOf(com.allperiph.ui.KeyPref.densityIndex(ctx)) }
            var kbLayout by remember { mutableIntStateOf(com.allperiph.ui.KeyPref.layoutIndex(ctx)) }
            var kbFx by remember { mutableIntStateOf(com.allperiph.ui.KeyPref.effectIndex(ctx)) }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_keyboard_look)) {
                Column {
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_main_label_keycap_style),
                        summary = com.allperiph.ui.KeyPref.keycapStyleLabels(ctx).getOrElse(kbStyle) { "" },
                        items = com.allperiph.ui.KeyPref.keycapStyleLabels(ctx),
                        selectedIndex = kbStyle,
                        onSelectedIndexChange = { i ->
                            kbStyle = i
                            com.allperiph.ui.KeyPref.setKeycapStyle(ctx, i)
                            Feedback.haptic(ctx)
                        },
                    )
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_set_label_keycap_density),
                        summary = com.allperiph.ui.KeyPref.DENSITY_LABELS.getOrElse(kbDensity) { "" },
                        items = com.allperiph.ui.KeyPref.DENSITY_LABELS,
                        selectedIndex = kbDensity,
                        onSelectedIndexChange = { i ->
                            kbDensity = i
                            com.allperiph.ui.KeyPref.setDensity(ctx, i)
                            Feedback.haptic(ctx)
                        },
                    )
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_main_label_keyrow_arrange),
                        summary = com.allperiph.ui.KeyPref.LAYOUT_LABELS.getOrElse(kbLayout) { "" },
                        items = com.allperiph.ui.KeyPref.LAYOUT_LABELS,
                        selectedIndex = kbLayout,
                        onSelectedIndexChange = { i ->
                            kbLayout = i
                            com.allperiph.ui.KeyPref.setLayout(ctx, i)
                            Feedback.haptic(ctx)
                        },
                    )
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_set_label_keycap_effect),
                        summary = com.allperiph.ui.KeyPref.effectLabels(ctx).getOrElse(kbFx) { "" },
                        items = com.allperiph.ui.KeyPref.effectLabels(ctx),
                        selectedIndex = kbFx,
                        onSelectedIndexChange = { i ->
                            kbFx = i
                            com.allperiph.ui.KeyPref.setEffect(ctx, i)
                            Feedback.haptic(ctx)
                        },
                    )
                    // 如实说明生效时机：这三项在触控板构建键盘面板时读取，改了当场不会重排
                    ApComponents.ValueRow(
                        title = ctx.getString(R.string.ui_set_label_apply_when),
                        value = ctx.getString(R.string.ui_set_value_kb_apply_when),
                    )
                }
            }


            // ———————————————— 通用 ————————————————
        }

            var ballVisible by remember { mutableStateOf(FloatingBallService.isOn(ctx)) }

            // ———————————————— 悬浮栏 ————————————————
            // 成宇 2026-11-01：悬浮球与悬浮菜单要能隐藏。这里不新造状态，
            // 读写的还是 FloatingBallService 自己的 on/off（isOn + 启停服务），
            // 开关只是同一份状态的另一个入口 —— 避免"设置里关了球还在"这类不一致。
        if (cat == 1) {
            // 成宇 2026-11-03「快捷键与外设里的通用移到分组悬浮栏上面」：
            // 整段从「工具与传输」（cat2）搬到「快捷键与外设」（cat1），且**排在悬浮栏之前**
            // —— 光标锁定 / 方向锁定 / 三指手势遮蔽 / 互传 / 震动都是「手机怎么响应输入」，
            // 与「外设」这个分类名相符，放在工具与传输里名不副实。
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_general)) {
                Column {
                    ApComponents.SwitchRow(
                        title = "光标锁定",
                        summary = "触控板光标锁定在屏幕内，不贴边滑出",
                        checked = cursorLock,
                        onCheckedChange = { on ->
                            cursorLock = on
                            apxPrefs.edit().putBoolean("cursor_lock", on).apply()
                            Feedback.haptic(ctx)
                        },
                    )
                    // 方向锁定（审查条 1.1）：整条能力在迁移里丢了，这里把它放回壳级开关。
                    // 与「重力判读自动切页」的关系写在 OrientLock 的 KDoc 里：锁定时一并暂停切页。
                    var orientLock by remember { mutableStateOf(OrientLock.isOn(ctx)) }
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_orient_lock_title),
                        summary = ctx.getString(R.string.ui_set_orient_lock_sum),
                        checked = orientLock,
                        onCheckedChange = { on ->
                            orientLock = on
                            OrientLock.set(ctx, on)
                            (ctx as? android.app.Activity)?.let { OrientLock.apply(it) }
                            Feedback.haptic(ctx)
                        },
                    )
                    ApComponents.SwitchRow(
                        title = "三指手势遮蔽",
                        summary = "触控板里三指滑动不再被系统截屏（需 root，退出即还原）",
                        checked = shield,
                        onCheckedChange = { on ->
                            shield = on
                            GestureShield.setEnabled(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                    ApComponents.SwitchRow(
                        title = "LocalSend 互传",
                        summary = if (localSendWanted && !localSend) {
                            ctx.getString(R.string.ui_set_sum_localsend_stale)
                        } else {
                            "开启后在局域网内接收文件"
                        },
                        checked = localSend,
                        onCheckedChange = { on ->
                            localSend = on
                            apxPrefs.edit().putBoolean("localsend_enabled", on).apply()
                            if (on) {
                                com.allperiph.localsend.LocalSendService.start(ctx)
                            } else {
                                com.allperiph.localsend.LocalSendService.stop(ctx)
                            }
                            Feedback.haptic(ctx)
                        },
                    )
                    // 震动档位（成宇 2026-11-03「震动没有映射过来，映射到外设模式里了」）：
                    // 从「主题与外观 → 动效」搬来。它决定每次按键震多强 —— [Feedback]
                    // 内部按 [ApFeel.hapticIndex] 判档，选「关」就完全不震。
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_main_label_haptic),
                        summary = ApFeel.HAPTIC_LABELS.getOrElse(haptic) { "" },
                        items = ApFeel.HAPTIC_LABELS,
                        selectedIndex = haptic,
                        onSelectedIndexChange = { i ->
                            haptic = i
                            ApFeel.setHaptic(ctx, i)
                            Feedback.haptic(ctx)
                        },
                    )
                }
            }

            // —— 悬浮球扇形菜单自定义（迁 Compose 时整块丢了：BallConfig 的
            //    ALL/enabled/setEnabled/labelRes 都在，界面上没有任何入口，
            //    菜单项永远停在 DEFAULT 四项）——
            var ballItems by remember { mutableStateOf<List<String>>(BallConfig.enabled(ctx)) }
            // 成宇 2026-11-03「悬浮球的功能自定义里面长按可以上下排」：
            // 长按某一行 → 弹「上移 / 下移 / 置顶 / 置底」，顺序就地改。
            // 为什么不再用每行一个「第 N 位」下拉（v223.29.3 刚改成那样）：
            // 12 项 = 12 行带下拉的行，编辑一段顺序要滚两屏，而下拉本身也会整屏撑开。
            // 长按调序把「顺序」这件事收回行自己身上，列表只剩 12 行开关。
            var ballItemMenu by remember { mutableStateOf<String?>(null) }
            var ballItemsOpen2 by remember { mutableStateOf(false) }
            fun applyBallOrder(next: List<String>) {
                ballItems = next
                BallConfig.setEnabled(ctx, next)
                // 服务把菜单缓存在内存里，重启让扇形按新顺序重建
                ctx.stopService(Intent(ctx, FloatingBallService::class.java))
                ctx.startForegroundService(Intent(ctx, FloatingBallService::class.java))
            }


            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_ball)) {
                Column {
                    ApComponents.SwitchRow(
                        title = "显示悬浮球",
                        summary = "关掉后悬浮球与悬浮菜单一起隐藏，外设连接不受影响",
                        checked = ballVisible,
                        onCheckedChange = { on ->
                            ballVisible = on
                            val it = Intent(ctx, FloatingBallService::class.java)
                            if (on) {
                                ctx.startForegroundService(it)
                            } else {
                                ctx.stopService(it)
                            }
                            Feedback.haptic(ctx)
                        },
                    )
                    // 成宇 2026-11-03「设置页悬浮窗的功能可以改成下拉列表，还有一些长的列都要改」：
                    // 12 个开关平铺把设置页拉得很长，收进默认收起的折叠分组，组头一眼看到勾了几项。
                    var ballItemsOpen by remember { mutableStateOf(false) }
                    ApComponents.FoldGroup(
                        title = ctx.getString(R.string.ui_ball_items_title),
                        countText = "已勾选 " + ballItems.size + " / " + BallConfig.ALL.size,
                        open = ballItemsOpen,
                        onToggleOpen = { ballItemsOpen = !ballItemsOpen },
                    ) {
                    // 只列**已勾选**的项，顺序就是它们的现状顺序：给一项没用的功能排位置
                    // 没有意义，也会让「第 N 位」与扇形里的实际位置对不上。
                    ballItems.forEachIndexed { idx, id ->
                        ApComponents.ClickRow(
                            title = ctx.getString(BallConfig.labelRes(id)),
                            summary = if (id == BallConfig.QUIT) {
                                ctx.getString(R.string.ui_ball_item_quit_summary)
                            } else {
                                ctx.getString(R.string.ui_ball_item_pos, idx + 1, ballItems.size)
                            },
                            onLongClick = { ballItemMenu = id },
                            onClick = {
                                // 短按 = 关掉这一项（原先的开关语义原样保留；最后一项不许关）
                                if (ballItems.size > 1) {
                                    applyBallOrder(ballItems - id)
                                } else {
                                    Toast.makeText(ctx, ctx.getString(R.string.ui_ball_item_keep_one), Toast.LENGTH_SHORT).show()
                                }
                                Feedback.haptic(ctx)
                            },
                        )
                    }
                    }
                    // 未勾选的功能：这里给「加进扇形」的入口（加进列表即排在末尾，
                    // 再长按它就能往上挪）—— 没有它，新功能永远只能排在最后。
                    val ballHidden = BallConfig.ALL.filterNot { ballItems.contains(it) }
                    if (ballHidden.isNotEmpty()) {
                        ApComponents.FoldGroup(
                            title = ctx.getString(R.string.ui_ball_items_more),
                            countText = ctx.getString(R.string.ui_ball_items_more_count, ballHidden.size),
                            open = ballItemsOpen2,
                            onToggleOpen = { ballItemsOpen2 = !ballItemsOpen2 },
                        ) {
                            ballHidden.forEach { id ->
                                ApComponents.ClickRow(
                                    title = ctx.getString(BallConfig.labelRes(id)),
                                    summary = ctx.getString(R.string.ui_ball_item_add_hint),
                                    onClick = {
                                        applyBallOrder(ballItems + id)
                                        Feedback.haptic(ctx)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 长按某一项唤出的顺序菜单（上移 / 下移 / 置顶 / 置底）
            ballItemMenu?.let { id ->
                val idx = ballItems.indexOf(id).coerceAtLeast(0)
                ApComponents.DialogCard(onDismiss = { ballItemMenu = null }) {
                    Text(text = ctx.getString(BallConfig.labelRes(id)), fontSize = 17.sp)
                    Text(
                        text = ctx.getString(R.string.ui_ball_item_pos, idx + 1, ballItems.size),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    val move: (Int) -> Unit = { to ->
                        val next = ballItems.toMutableList()
                        val v = next.removeAt(idx)
                        next.add(to.coerceIn(0, next.size), v)
                        applyBallOrder(next)
                        Feedback.haptic(ctx)
                        ballItemMenu = null
                    }
                    if (idx > 0) {
                        ApComponents.ActionButton(
                            text = ctx.getString(R.string.ui_ball_move_up),
                            onClick = { move(idx - 1) },
                        )
                        ApComponents.ActionButton(
                            text = ctx.getString(R.string.ui_ball_move_top),
                            onClick = { move(0) },
                        )
                    }
                    if (idx < ballItems.size - 1) {
                        ApComponents.ActionButton(
                            text = ctx.getString(R.string.ui_ball_move_down),
                            onClick = { move(idx + 1) },
                        )
                        ApComponents.ActionButton(
                            text = ctx.getString(R.string.ui_ball_move_bottom),
                            onClick = { move(ballItems.size) },
                        )
                    }
                    ApComponents.NarrowButton(
                        text = ctx.getString(R.string.ui_common_cancel),
                        onClick = { ballItemMenu = null },
                    )
                }
            }

            // ———————————————— 快捷键模板 ————————————————
            // 从旧 MainActivity 的设置页迁来：模板列表 + 应用 + 当前模板回显。
            // 数据与旧页共用 HotkeyTemplates.ALL / HotkeyStore.loadTemplate|applyTemplate，不另立存储。
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_tpl)) {
                Column {
                    // 成宇 2026-11-02「设置页太多了，能用下拉列表的不用」：
                    // 原来「当前模板」一行 + 六个模板各一枚 ActionButton，七行占满一屏；
                    // 模板是互斥枚举，改用下拉一个组件就装下全部（含未设置态回显）。
                    val tplAll = com.allperiph.hid.HotkeyTemplates.ALL
                    val tplKey = HotkeyStore.loadTemplate(ctx)
                    val tplIdx = tplAll.indexOfFirst { it.key == tplKey }
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_set_sec_tpl),
                        summary = if (tplIdx >= 0) {
                            "${tplAll[tplIdx].name} · ${tplAll[tplIdx].desc}"
                        } else {
                            "未设置（默认）"
                        },
                        items = tplAll.map { "${it.name} · ${it.desc}" },
                        selectedIndex = if (tplIdx >= 0) tplIdx else 0,
                        onSelectedIndexChange = { i ->
                            HotkeyStore.applyTemplate(ctx, tplAll[i])
                            tplVersion++
                            Feedback.haptic(ctx)
                        },
                    )
                }
            }
        }

        if (cat == 2) {

            // ———————————————— 文件与传输 ————————————————
            // v223.28：「环境诊断」整块上移到状态页「连接状态」（同一份 EnvChecks 数据
            // 此前两页各显示一半，双源；成宇 2026-11-02「本应该在状态页的内容却在设置页」）。
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_files)) {
                Column {
                    ApComponents.ActionButton(
                        text = "文件面板",
                        onClick = { ctx.startActivity(Intent(ctx, com.allperiph.ui.FilePanelActivity::class.java)) },
                    )
                    ApComponents.ActionButton(
                        text = "发送文件（LocalSend）",
                        onClick = {
                            ctx.startActivity(
                                Intent(ctx, com.allperiph.localsend.SendActivity::class.java),
                            )
                        },
                    )
                    // 审查条 1.9：原来另有一段叫「传输与投屏」，里面只有两枚跳转按钮、没有分类，
                    // 且「发送文件」与本节重复（两个文案指向同一个 SendActivity）。合并后本节
                    // 名副其实：文件面板 / 发送文件 / 屏幕，各一枚，文案不重复。
                    ApComponents.ActionButton(
                        text = "屏幕（投屏 / 被控）",
                        onClick = {
                            ctx.startActivity(Intent(ctx, com.allperiph.screen.ScreenActivity::class.java))
                        },
                    )
                }
            }

            // ———————————————— 键盘与无障碍 ————————————————
            // ⚠️ 必须用 GlobalKeyForwarder 自己的偏好（`apx_ime_bridge`）：App.onCreate 只从这里
            // 恢复开关。此前这里读写的都是 `apx` 里的 `global_keys`，内存字段当场改所以本会话有效，
            // 但进程重启后读不到 → 开关自己弹回关闭，用户看到的就是「开了不生效」。
            val imePrefs = remember { GlobalKeyForwarder.prefs(ctx) }
            var globalKeys by remember { mutableStateOf(GlobalKeyForwarder.isEnabled(imePrefs)) }
            // 受控设备选择器（v223.28）：「当前控制 / 选择受控设备 / 断开」三行整体上移到
            // 状态页「受控与出口」——链路状态属于运行时信息；本段只留偏好类：
            // 物理键盘全局转发 + 无障碍设置入口。
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_remote)) {
                Column {
                    ApComponents.SwitchRow(
                        title = "物理键盘全局转发",
                        summary = "接上蓝牙键盘后，按键直接转给被控端（需已连接设备）",
                        checked = globalKeys,
                        onCheckedChange = { on ->
                            globalKeys = on
                            GlobalKeyForwarder.setEnabled(imePrefs, on)
                            Feedback.haptic(ctx)
                        },
                    )
                    // 遥控器（审查条 1.6）：此前全仓只有旧控制台 TouchpadActivity 的 pill 能拉起，
                    // 主壳（唯一 launcher 入口）没有任何路径 —— 用户不开悬浮球就进不去。
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.ui_set_btn_remote),
                        onClick = {
                            ctx.startActivity(Intent(ctx, com.allperiph.ui.RemoteActivity::class.java))
                        },
                    )
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.ui_main_btn_a11y_settings),
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }.onFailure {
                                Toast.makeText(ctx, ctx.getString(R.string.ui_set_toast_a11y_no_settings), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }

            // ———————————————— 输入与外设 ————————————————
            // GamepadActivity 整页（双虚拟摇杆 + 常用按键，出口 USB HID / 蓝牙 HID / 无线 0x07）
            // 此前**全仓零 startActivity**：Manifest 声明在、代码在，用户永远打不开。
            // 外接触控板 root 直读（RootPointerBridge）同理 —— 开关只存在于偏好里。
            var rootPointer by remember {
                mutableStateOf(com.allperiph.touchpad.RootPointerBridge.isEnabled(
                    com.allperiph.touchpad.RootPointerBridge.prefs(ctx),
                ))
            }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_input)) {
                Column {
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.ui_set_btn_gamepad),
                        onClick = {
                            ctx.startActivity(Intent(ctx, com.allperiph.ui.GamepadActivity::class.java))
                        },
                    )
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_main_label_root_pointer),
                        summary = ctx.getString(R.string.ui_set_sum_root_pointer),
                        checked = rootPointer,
                        onCheckedChange = { on ->
                            rootPointer = on
                            com.allperiph.touchpad.RootPointerBridge.setEnabled(
                                ctx,
                                com.allperiph.touchpad.RootPointerBridge.prefs(ctx),
                                on,
                            )
                            Feedback.haptic(ctx)
                        },
                    )
                }
            }

            // ———————————————— 主题数据 ————————————————
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_theme)) {
                Column {
                    ApComponents.ActionButton(
                        text = "导出当前主题（复制到剪贴板）",
                        onClick = {
                            val json = runCatching { ApSkin.exportJson(ctx) }.getOrNull().orEmpty()
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            if (json.isNotBlank()) {
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("apx-theme", json))
                                Toast.makeText(ctx, "已复制 ${json.length} 字符", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "没有可导出的主题", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    ApComponents.ActionButton(
                        text = "从剪贴板导入主题",
                        onClick = {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                            val n = if (text.isBlank()) 0 else runCatching { ApSkin.importJson(ctx, text) }.getOrDefault(0)
                            Toast.makeText(
                                ctx,
                                if (n > 0) "已导入 $n 套主题" else "剪贴板里没有可识别的主题 JSON",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }

            // ———————————————— 模板管理（自定义） ————————————————
            // 增：从内置模板复制一份可改的；删：直接移除；序：上移/下移（手机上按钮比拖动更稳，
            // 拖动排序留给平板/电视端的手势实现）。数据全部落在 HotkeyStore.loadCustom/saveCustom，
            // 与旧设置页同一份存储，不会出现"两套模板"。
            var customs by remember { mutableStateOf(HotkeyStore.loadCustom(ctx)) }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_tpl_custom)) {
                Column {
                    if (customs.isEmpty()) {
                        ApComponents.ValueRow(title = "还没有自定义模板", value = "点下面按钮从内置模板复制一份")
                    }
                    customs.forEachIndexed { index, t ->
                        ApComponents.ValueRow(title = "${index + 1}. ${t.name}", value = t.desc)
                        ApComponents.ActionButton(
                            text = "应用「${t.name}」",
                            onClick = {
                                HotkeyStore.applyTemplate(ctx, t)
                                Toast.makeText(ctx, "已应用：${t.name}", Toast.LENGTH_SHORT).show()
                                Feedback.haptic(ctx)
                            },
                        )
                        if (index > 0) {
                            ApComponents.ActionButton(
                                text = "「${t.name}」上移",
                                onClick = {
                                    val l = customs.toMutableList()
                                    val v = l.removeAt(index)
                                    l.add(index - 1, v)
                                    customs = l
                                    HotkeyStore.saveCustom(ctx, l)
                                },
                            )
                        }
                        ApComponents.ActionButton(
                            text = "删除「${t.name}」",
                            onClick = {
                                val l = customs.toMutableList()
                                l.removeAt(index)
                                customs = l
                                HotkeyStore.saveCustom(ctx, l)
                            },
                        )
                    }
                    ApComponents.ActionButton(
                        text = "从内置模板新建一份可改的",
                        onClick = {
                            val used = customs.map { it.name }.toSet()
                            val src = com.allperiph.hid.HotkeyTemplates.ALL.firstOrNull { it.name !in used }
                            if (src == null) {
                                Toast.makeText(ctx, "内置模板都已经复制过了", Toast.LENGTH_SHORT).show()
                            } else {
                                val copy = src.copy(
                                    key = "custom_" + System.currentTimeMillis(),
                                    name = src.name + "（自定义）",
                                    desc = "由内置模板「${src.name}」复制，可改",
                                )
                                val l = customs.toMutableList().also { it.add(copy) }
                                customs = l
                                HotkeyStore.saveCustom(ctx, l)
                                Toast.makeText(ctx, "已新建：${copy.name}", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }

            // ———————————————— 键映射 ————————————————
            // HidKeyMap 是「Android keyCode → HID usage」的静态真源；这里只加稀疏的用户覆盖层
            // （HidKeyOverrides），查表顺序是「覆盖 → 默认」。
            // 两条转发路径都已接：GlobalKeyForwarder（无障碍全局）与 PhysKeyBridge（应用内窗口回调），
            // 后者此前直接查静态表——不开全局转发时改键等于没改（2026-11-02 审查条 1.4）。
            var overrides by remember { mutableStateOf(HidKeyOverrides.current()) }
            val overridePrefs = remember { HidKeyOverrides.prefs(ctx) }
            // 成宇 2026-11-03「键映射这一块太长了，用折叠」：11 个键位各一行下拉 = 11 行，
            // 整段是设置页最长的一段。收进默认收起的折叠分组，组头直接回显「已改 N 处」，
            // 不展开也知道改没改过（与悬浮球菜单项那段同一做法，同一份 FoldGroup）。
            var keymapOpen by remember { mutableStateOf(false) }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_keymap)) {
                Column {
                    ApComponents.FoldGroup(
                        title = ctx.getString(R.string.ui_set_keymap_group),
                        countText = ctx.getString(R.string.ui_set_keymap_changed, overrides.size),
                        open = keymapOpen,
                        onToggleOpen = { keymapOpen = !keymapOpen },
                    ) {
                    HidKeyOverrides.KEY_CHOICES.forEach { pair ->
                        val label = pair.first
                        val keyCode = pair.second
                        val cur = overrides[keyCode]
                        val def = com.allperiph.ui.HidKeyMap.usageOf(keyCode)
                        // 成宇 2026-11-02「能用下拉列表的不用」：一个键位原来是三行
                        // （值行 + 换下一个候选 + 恢复默认），11 个键位就是 33 行。
                        // 候选本身是枚举，改成一行下拉：第 0 项是「恢复默认」，其余是 20 个 usage。
                        val defLabel = if (def == 0) "默认（不转发）" else "默认（0x%02X）".format(def)
                        val items = listOf(defLabel) + HidKeyOverrides.USAGE_CHOICES.map {
                            "${it.first}（0x%02X）".format(it.second)
                        }
                        val curIdx = if (cur == null) 0 else {
                            HidKeyOverrides.USAGE_CHOICES.indexOfFirst { it.second == cur } + 1
                        }
                        WindowDropdownPreference(
                            title = label,
                            summary = if (cur == null) defLabel else "已改（0x%02X）".format(cur),
                            items = items,
                            selectedIndex = curIdx.coerceAtLeast(0),
                            onSelectedIndexChange = { i ->
                                if (i == 0) {
                                    HidKeyOverrides.clear(overridePrefs, keyCode)
                                } else {
                                    HidKeyOverrides.set(
                                        overridePrefs, keyCode, HidKeyOverrides.USAGE_CHOICES[i - 1].second,
                                    )
                                }
                                overrides = HidKeyOverrides.current()
                                Feedback.haptic(ctx)
                            },
                        )
                    }
                    }
                }
            }
        }

            // ———————————————— 背景图 ————————————————
            // 成宇 2026-11-03「设置里的背景图也要挪到主题外观分组」：整段从「数据与维护」
            // （cat3）搬到「主题与外观」（cat0）的动效段之后 —— 背景是外观项，不该跟
            // 日志、关于这类维护项混在一起。数据路径不变（ApBackdrop）。
        if (cat == 3) {
            // ———————————————— 运行日志 ————————————————
            // 日志不是新造的：Log 有 512 条环形缓冲（snapshot/clear），LogExport 负责落盘
            // （apx-logs-<kind>@<型号>-<stamp>.txt，默认目录 下载/AllPeriph，API 29+ 走 MediaStore）。
            // 本页做三件事：筛选、查看（弹窗限高 62%）、导出（可自定义文件名与子目录）。
            var logMin by remember { mutableIntStateOf(1) }
            var logSpan by remember { mutableIntStateOf(1) }
            var showLogViewer by remember { mutableStateOf(false) }
            val logLevels = listOf("全部（含调试）", "Debug 以上", "Info 以上", "只看警告", "只看错误")
            val logSpans = listOf("最近 5 分钟", "最近 15 分钟", "最近 1 小时", "全部缓冲")
            val logNameState = androidx.compose.foundation.text.input.rememberTextFieldState()
            val logDirState = androidx.compose.foundation.text.input.rememberTextFieldState()
            fun filteredLogs(): List<com.allperiph.shared.util.Log.Entry> {
                val minLevel = when (logMin) {
                    0 -> com.allperiph.shared.util.Log.Level.V
                    1 -> com.allperiph.shared.util.Log.Level.D
                    2 -> com.allperiph.shared.util.Log.Level.I
                    3 -> com.allperiph.shared.util.Log.Level.W
                    else -> com.allperiph.shared.util.Log.Level.E
                }
                val windowMs = when (logSpan) {
                    0 -> 5 * 60_000L
                    1 -> 15 * 60_000L
                    2 -> 60 * 60_000L
                    else -> Long.MAX_VALUE
                }
                val now = System.currentTimeMillis()
                return com.allperiph.shared.util.Log.snapshot()
                    .filter { it.level.ordinal >= minLevel.ordinal && now - it.wallMs <= windowMs }
            }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_log)) {
                Column {
                    ApComponents.ValueRow(
                        title = "缓冲",
                        value = "${com.allperiph.shared.util.Log.snapshot().size} 条（上限 512，超出滚动丢弃）",
                    )
                    WindowDropdownPreference(
                        title = "级别筛选",
                        summary = logLevels.getOrElse(logMin) { logLevels[0] },
                        items = logLevels,
                        selectedIndex = logMin,
                        onSelectedIndexChange = { logMin = it },
                    )
                    WindowDropdownPreference(
                        title = "时间范围",
                        summary = logSpans.getOrElse(logSpan) { logSpans[0] },
                        items = logSpans,
                        selectedIndex = logSpan,
                        onSelectedIndexChange = { logSpan = it },
                    )
                    ApComponents.ActionButton(
                        text = "查看最近日志（当前筛选）",
                        onClick = { showLogViewer = true },
                    )
                    top.yukonga.miuix.kmp.basic.TextField(
                        state = logNameState,
                        label = "文件名（留空用 apx-logs-log@型号-时间.txt）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    top.yukonga.miuix.kmp.basic.TextField(
                        state = logDirState,
                        label = "子目录（留空用 下载/AllPeriph）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ApComponents.ActionButton(
                        text = "导出日志",
                        onClick = {
                            val rows = filteredLogs()
                            if (rows.isEmpty()) {
                                Toast.makeText(ctx, "该条件下没有日志", Toast.LENGTH_SHORT).show()
                            } else {
                                val body = buildString {
                                    appendLine("# apx-android 日志导出")
                                    appendLine("# 时间：${com.allperiph.shared.util.LogExport.timestamp()}")
                                    appendLine("# 级别：${logLevels[logMin]} / 范围：${logSpans[logSpan]}")
                                    appendLine("# 条数：${rows.size}")
                                    rows.forEach { e ->
                                        append('[').append(e.level.name).append("] ")
                                        append(e.tag).append(": ").appendLine(e.msg)
                                    }
                                }
                                val customName = logNameState.text.toString().trim()
                                val name = if (customName.isBlank()) {
                                    com.allperiph.shared.util.LogExport.fileName("log")
                                } else if (customName.endsWith(".txt")) customName else "$customName.txt"
                                val subDir = logDirState.text.toString().trim().ifBlank { null }
                                val saved = runCatching {
                                    com.allperiph.shared.util.LogExport.saveToDownloads(ctx, name, body, subDir)
                                }
                                Toast.makeText(
                                    ctx,
                                    saved.getOrElse { "导出失败：${it.message}" },
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                    ApComponents.ActionButton(
                        text = "清空日志缓冲",
                        onClick = {
                            com.allperiph.shared.util.Log.clear()
                            Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            if (showLogViewer) {
                val rows = filteredLogs().takeLast(200)
                Dialog(onDismissRequest = { showLogViewer = false }) {
                    ApComponents.DialogCard(
                        onDismiss = { showLogViewer = false },
                        heightRatio = 0.62f,
                        // 内部是 weight(1f) + 自己的 verticalScroll（日志列），外层不再包滚动
                        scrollable = false,
                        // 原实现就是 12dp 内边距（比默认 16 更紧凑，一屏能多看几条），保持不变
                        padding = 12.dp,
                    ) {
                        Text(
                            text = "最近日志（${rows.size} 条，上限 200）",
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            rows.forEach { e ->
                                Text(
                                    text = "[${e.level.name}] ${e.tag}: ${e.msg}",
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 成宇 2026-11-03「日志要加复制」：把当前筛选下的整段日志
                            // 一次性放进系统剪贴板（不是只复制看得见的那 200 行 ——
                            // 弹窗为了不卡只渲染 200 条，复制走 filteredLogs() 的全量）。
                            ApComponents.NarrowButton(
                                text = ctx.getString(R.string.ui_log_copy_all),
                                onClick = {
                                    val all = filteredLogs()
                                    if (all.isEmpty()) {
                                        Toast.makeText(ctx, ctx.getString(R.string.ui_log_nothing), Toast.LENGTH_SHORT).show()
                                    } else {
                                        val body = buildString {
                                            all.forEach { e ->
                                                append('[').append(e.level.name).append("] ")
                                                append(e.tag).append(": ").appendLine(e.msg)
                                            }
                                        }
                                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        cm?.setPrimaryClip(
                                            android.content.ClipData.newPlainText("apx-logs", body),
                                        )
                                        Toast.makeText(
                                            ctx, ctx.getString(R.string.ui_log_copied, all.size),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                            ApComponents.NarrowButton(
                                text = ctx.getString(R.string.ui_common_close),
                                onClick = { showLogViewer = false },
                            )
                        }
                    }
                }
            }

            // ———————————————— 关于 ————————————————
            ApComponents.Section(title = ctx.getString(R.string.lay_settings_tab_about)) {
                Column {
                    // 长按版本行 = 组件总览（开发页）。不放入口按钮：这页是给迁移期对照 Miuix
                    // 组件真实观感用的，普通用户看到「组件总览」只会困惑；长按是刻意低调的入口。
                    ApComponents.ValueRow(
                        title = "当前版本",
                        value = UpdateChecker.currentTag(),
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            ctx,
                                            com.allperiph.ui.miuix.MiuixShowcaseActivity::class.java,
                                        )
                                    )
                                }
                            )
                        },
                    )
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.about_check_update),
                        onClick = {
                            UpdateChecker.check(ctx) { r ->
                                // 发现新版必须弹对话框（下载直链 + 打开 Release 页）。
                                // 此前这里只 Toast 一行「发现新版：vX」就结束 —— 用户看得见提示、
                                // 却没有任何可点的下一步，正属于「只有提示不执行」。
                                if (r is UpdateChecker.Result.NewVersion) {
                                    UpdateChecker.showUpdateDialog(ctx, r.tag, r.url)
                                    return@check
                                }
                                val msg = when (r) {
                                    is UpdateChecker.Result.UpToDate -> "已是最新：${r.tag}"
                                    is UpdateChecker.Result.Failed -> "检查失败"
                                    else -> ""
                                }
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.about_feedback),
                        onClick = { IssueLauncher.open(ctx) },
                    )
                }
            }
        }
        }
    }
    }
    }

}
