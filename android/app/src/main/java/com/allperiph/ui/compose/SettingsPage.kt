package com.allperiph.ui.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
fun SettingsPage(ctx: Context, onRecreate: () -> Unit) {
    val insets = rememberApInsets()
    var tplVersion by remember { mutableIntStateOf(0) }
    // 档位值放在 state 里：下拉组件是受控的，改完要立刻反映到界面
    var themeMode by remember { mutableIntStateOf(ThemePref.get(ctx)) }
    var colorSource by remember { mutableIntStateOf(ApPalette.colorSource(ctx)) }
    var motion by remember { mutableIntStateOf(ApTiming.motionIndex(ctx)) }
    var haptic by remember { mutableIntStateOf(ApFeel.hapticIndex(ctx)) }
    var sound by remember { mutableIntStateOf(ApFeel.soundIndex(ctx)) }

    val apxPrefs = remember { ctx.getSharedPreferences("apx", Context.MODE_PRIVATE) }
    var cursorLock by remember { mutableStateOf(apxPrefs.getBoolean("cursor_lock", true)) }
    var shield by remember { mutableStateOf(GestureShield.isEnabled(ctx)) }
    var localSend by remember { mutableStateOf(apxPrefs.getBoolean("localsend_enabled", false)) }

    // 大屏（平板 / 折叠展开 / 横屏）不再把设置项拉满整屏：限宽 680dp 并水平居中；
    // 手机宽度本来就 < 680dp，限宽不生效，观感与之前一致。
    // 分类导航（旧版设置就是「分类 → 二级页」；Compose 迁移后一度退化成 16 段平铺）
    // 这里按**连续区间**分组，不搬动 section 代码本身，避免大范围挪动引入编译错误。
    var cat by remember { mutableIntStateOf(0) }
    // 切分类必须回到顶部：四类的段数与高度都不同，沿用上一类的滚动偏移会直接落到空白处
    val pageScroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(cat) { pageScroll.scrollTo(0) }
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
            .padding(start = 12.dp + insets.left, end = 12.dp + insets.right, top = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        catLabels.forEachIndexed { idx, name ->
            ApComponents.NarrowButton(text = name, onClick = { cat = idx })
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
                .padding(start = 12.dp + insets.left, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            catLabels.forEachIndexed { idx, name ->
                ApComponents.NarrowButton(text = name, onClick = { cat = idx })
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
                    top = 8.dp,
                    bottom = 96.dp + insets.bottom,
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
                }
            }

            // ———————————————— 通用 ————————————————
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
                        summary = "开启后在局域网内接收文件",
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
                }
            }
        }

            var ballVisible by remember { mutableStateOf(FloatingBallService.isOn(ctx)) }

            // ———————————————— 悬浮栏 ————————————————
            // 成宇 2026-11-01：悬浮球与悬浮菜单要能隐藏。这里不新造状态，
            // 读写的还是 FloatingBallService 自己的 on/off（isOn + 启停服务），
            // 开关只是同一份状态的另一个入口 —— 避免"设置里关了球还在"这类不一致。
        if (cat == 1) {
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
                    // —— 悬浮球扇形菜单自定义（迁 Compose 时整块丢了：BallConfig 的
                    //    ALL/enabled/setEnabled/labelRes 都在，界面上没有任何入口，
                    //    菜单项永远停在 DEFAULT 四项）——
                    var ballItems by remember { mutableStateOf<List<String>>(BallConfig.enabled(ctx)) }
                    Text(
                        text = ctx.getString(R.string.ui_ball_items_title),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    BallConfig.ALL.forEach { id ->
                        ApComponents.SwitchRow(
                            title = ctx.getString(BallConfig.labelRes(id)),
                            summary = if (id == BallConfig.QUIT) ctx.getString(R.string.ui_ball_item_quit_summary) else null,
                            checked = ballItems.contains(id),
                            onCheckedChange = { on ->
                                val next = if (on) ballItems + id else ballItems - id
                                // 兜底：全都不勾会让扇形菜单没有可点项，强制保留至少一项
                                if (next.isNotEmpty()) {
                                    ballItems = next
                                    BallConfig.setEnabled(ctx, next)
                                    // 悬浮球服务把菜单缓存在内存里，重启服务让扇形按新配置重建
                                    ctx.stopService(Intent(ctx, FloatingBallService::class.java))
                                    ctx.startForegroundService(Intent(ctx, FloatingBallService::class.java))
                                }
                                Feedback.haptic(ctx)
                            },
                        )
                    }
                    // 顺序调整：BallConfig 存的是**逗号串**（见其注释：Set 无序，顺序就是用户
                    // 看到的扇形展开顺序）。之前只有勾选、没有排序入口，等于这个设计意图用户碰不到。
                    if (ballItems.size > 1) {
                        Text(
                            text = ctx.getString(R.string.ui_ball_order_title),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                        ballItems.forEachIndexed { idx, id ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${idx + 1}. " + ctx.getString(BallConfig.labelRes(id)),
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (idx > 0) {
                                    ApComponents.NarrowButton(text = "\u2191", onClick = {
                                        val next = ballItems.toMutableList()
                                        val t = next[idx]; next[idx] = next[idx - 1]; next[idx - 1] = t
                                        ballItems = next
                                        BallConfig.setEnabled(ctx, next)
                                        ctx.stopService(Intent(ctx, FloatingBallService::class.java))
                                        ctx.startForegroundService(Intent(ctx, FloatingBallService::class.java))
                                    })
                                }
                                if (idx < ballItems.lastIndex) {
                                    ApComponents.NarrowButton(text = "\u2193", onClick = {
                                        val next = ballItems.toMutableList()
                                        val t = next[idx]; next[idx] = next[idx + 1]; next[idx + 1] = t
                                        ballItems = next
                                        BallConfig.setEnabled(ctx, next)
                                        ctx.stopService(Intent(ctx, FloatingBallService::class.java))
                                        ctx.startForegroundService(Intent(ctx, FloatingBallService::class.java))
                                    })
                                }
                            }
                        }
                    }
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

            // ———————————————— 键盘外观 ————————————————
            // 为什么需要这一段：KeyPref 的三个偏好（键帽材质 / 键帽密度 / 键位排列）此前
            // **只有实现、没有入口** —— setKeycapStyle / setDensity / setLayout 全仓零调用，
            // 用户想换机械键帽或 Dvorak 只能改代码。这里给唯一的一组入口，初值直接读
            // KeyPref 自己的 index（不另立一份状态，避免"界面显示 A、实际渲染 B"）。
            var kbStyle by remember { mutableIntStateOf(com.allperiph.ui.KeyPref.keycapStyleIndex(ctx)) }
            var kbDensity by remember { mutableIntStateOf(com.allperiph.ui.KeyPref.densityIndex(ctx)) }
            var kbLayout by remember { mutableIntStateOf(com.allperiph.ui.KeyPref.layoutIndex(ctx)) }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_keyboard_look)) {
                Column {
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_main_label_keycap_style),
                        summary = com.allperiph.ui.KeyPref.KEYCAP_STYLE_LABELS.getOrElse(kbStyle) { "" },
                        items = com.allperiph.ui.KeyPref.KEYCAP_STYLE_LABELS,
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
                    // 如实说明生效时机：这三项在触控板构建键盘面板时读取，改了当场不会重排
                    ApComponents.ValueRow(
                        title = ctx.getString(R.string.ui_set_label_apply_when),
                        value = ctx.getString(R.string.ui_set_value_kb_apply_when),
                    )
                }
            }

            // ———————————————— 音频方向 ————————————————
            // applyMic / applySpeaker（USB UAC2 与 Wi‑Fi 两套）此前同样是零调用：模块启动时
            // 读到的永远是默认的「双向全开」，用户没法只留一个方向。这里的口径：
            // 模块**在跑**就热切换，**没跑**就只落盘（下次连接生效）—— 两种情况都必须记住选择。
            // 模块实例由 AgentController 的注册表提供；服务没起时它是 null，这很正常，不是错误。
            // 热切换与落盘的取舍收在 AudioDirections（四个方向各一条规则容易写漏）
            var usbMic by remember { mutableStateOf(com.allperiph.audio.AudioModule.isMicOn(ctx)) }
            var usbSpk by remember { mutableStateOf(com.allperiph.audio.AudioModule.isSpeakerOn(ctx)) }
            var wifiMic by remember { mutableStateOf(com.allperiph.audio.WirelessAudioModule.isMicOn(ctx)) }
            var wifiSpk by remember { mutableStateOf(com.allperiph.audio.WirelessAudioModule.isSpeakerOn(ctx)) }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_audio_dir)) {
                Column {
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_usb_mic),
                        summary = ctx.getString(R.string.ui_set_sum_usb_mic),
                        checked = usbMic,
                        onCheckedChange = { on ->
                            usbMic = on
                            com.allperiph.ui.AudioDirections.setUsbMic(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_usb_spk),
                        summary = ctx.getString(R.string.ui_set_sum_usb_spk),
                        checked = usbSpk,
                        onCheckedChange = { on ->
                            usbSpk = on
                            com.allperiph.ui.AudioDirections.setUsbSpeaker(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_wifi_mic),
                        summary = ctx.getString(R.string.ui_set_sum_wifi_mic),
                        checked = wifiMic,
                        onCheckedChange = { on ->
                            wifiMic = on
                            com.allperiph.ui.AudioDirections.setWifiMic(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_label_wifi_spk),
                        summary = ctx.getString(R.string.ui_set_sum_wifi_spk),
                        checked = wifiSpk,
                        onCheckedChange = { on ->
                            wifiSpk = on
                            com.allperiph.ui.AudioDirections.setWifiSpeaker(ctx, on)
                            Feedback.haptic(ctx)
                        },
                    )
                }
            }
        }

            // ———————————————— 环境诊断 ————————————————
        if (cat == 2) {
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_env)) {
                Column {
                    var env by remember { mutableStateOf(com.allperiph.ui.AgentController.env) }
                    ApComponents.ActionButton(
                        text = "重新检测",
                        onClick = { com.allperiph.ui.AgentController.refreshEnv(ctx) { env = it } },
                    )
                    env?.let { e ->
                        ApComponents.ValueRow(title = "root", value = if (e.rooted) "已就绪" else "未获取")
                        ApComponents.ValueRow(title = "链路", value = e.rawSpeed.ifEmpty { e.linkSpeed.toString() })
                        ApComponents.ValueRow(title = "UDC", value = e.udc ?: "未找到")
                        ApComponents.ValueRow(
                            title = "电池优化",
                            value = if (e.batteryOptimized) "已忽略（推荐）" else "未忽略",
                        )
                        ApComponents.ValueRow(
                            title = "通知权限",
                            value = if (e.notificationGranted) "已授予" else "未授予",
                        )
                    }
                }
            }

            // ———————————————— 文件与传输 ————————————————
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
                }
            }

            // ———————————————— 遥控与键盘 ————————————————
            // ⚠️ 必须用 GlobalKeyForwarder 自己的偏好（`apx_ime_bridge`）：App.onCreate 只从这里
            // 恢复开关。此前这里读写的都是 `apx` 里的 `global_keys`，内存字段当场改所以本会话有效，
            // 但进程重启后读不到 → 开关自己弹回关闭，用户看到的就是「开了不生效」。
            val imePrefs = remember { GlobalKeyForwarder.prefs(ctx) }
            var globalKeys by remember { mutableStateOf(GlobalKeyForwarder.isEnabled(imePrefs)) }
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
                    ApComponents.ValueRow(
                        title = "当前控制",
                        value = runCatching {
                            com.allperiph.wireless.ControlTarget.label
                                ?: com.allperiph.wireless.ControlTarget.host
                                ?: ""
                        }.getOrDefault("").ifBlank { "未连接（点顶栏设备名可选择）" },
                    )
                    ApComponents.ActionButton(
                        text = "断开当前控制",
                        onClick = {
                            runCatching { com.allperiph.wireless.ControlTarget.clear() }
                            Toast.makeText(ctx, "已断开", Toast.LENGTH_SHORT).show()
                        },
                    )
                    ApComponents.ActionButton(
                        text = "重新发现局域网设备",
                        onClick = {
                            com.allperiph.wireless.TvDiscovery.start()
                            Toast.makeText(ctx, "已开始搜索，稍候在顶栏设备名里选", Toast.LENGTH_SHORT).show()
                        },
                    )
                    // 全局转发与光标悬浮都依赖 Eta 的无障碍服务；以前这里只有一句提示、
                    // 用户还得自己翻到系统设置里找 —— 现在直接给出可点的入口。
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

            // ———————————————— 传输分类 ————————————————
            // 三个分类入口：文件（LocalSend 发送）、屏幕（投屏/被控）、接收开关（已在通用组）。
            // 之前只有"文件面板"一个入口，屏幕与发送各自藏在别的页面里。
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_transport)) {
                Column {
                    ApComponents.ActionButton(
                        text = "发送文件（选文件 → 发给目标）",
                        onClick = {
                            ctx.startActivity(Intent(ctx, com.allperiph.localsend.SendActivity::class.java))
                        },
                    )
                    ApComponents.ActionButton(
                        text = "屏幕（投屏 / 被控）",
                        onClick = {
                            ctx.startActivity(Intent(ctx, com.allperiph.screen.ScreenActivity::class.java))
                        },
                    )
                }
            }

            // ———————————————— 键映射 ————————————————
            // HidKeyMap 是「Android keyCode → HID usage」的静态真源；这里只加稀疏的用户覆盖层
            // （HidKeyOverrides），查表顺序是「覆盖 → 默认」，转发链路已在 GlobalKeyForwarder 接好。
            var overrides by remember { mutableStateOf(HidKeyOverrides.current()) }
            val overridePrefs = remember { HidKeyOverrides.prefs(ctx) }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_keymap)) {
                Column {
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

            // ———————————————— 背景图 ————————————————
            // 沿 ApBackdrop 既有路径：键 KEY_BG_IMAGE="bg_image"、读 ApBackdrop.bgImage(ctx)、
            // 写 ApBackdrop.setBgImage(ctx, uri)；解码由 Backdrop.decode() 做（采样到两倍屏宽）。
            // 选图必须 takePersistableUriPermission，否则重启后 URI 失效、背景变回纯色。
            var bgUri by remember { mutableStateOf(ApBackdrop.bgImage(ctx).orEmpty()) }
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
        if (cat == 3) {
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_bg)) {
                Column {
                    ApComponents.ValueRow(
                        title = "当前",
                        value = if (bgUri.isBlank()) "未设置（沿用预设色）" else "已选图（${bgUri.take(48)}…）",
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
                }
            }

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
                        ApComponents.ActionButton(
                            text = "关闭",
                            onClick = { showLogViewer = false },
                        )
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
