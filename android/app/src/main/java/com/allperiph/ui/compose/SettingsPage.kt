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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allperiph.R
import com.allperiph.touchpad.GestureShield
import com.allperiph.ui.DeviceFormPref
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
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import androidx.compose.ui.unit.sp
import com.allperiph.ui.BallConfig
import com.allperiph.ui.PanelSize
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import com.allperiph.ui.BallIcon

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
 *  · 关于：版本行 + 检查更新 + 意见反馈。
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
    // 一键主题会让皮肤 / 键帽 / 特效 / 震动 / 音效一起变，这些下拉的初值都得以它为准重读。
    // 版本号本身只增不减，用于 remember 的 key（不参与显示）。
    val presetLookRev = com.allperiph.ui.ApThemePresets.applyRev
    var themeMode by remember { mutableIntStateOf(ThemePref.get(ctx)) }
    var colorSource by remember { mutableIntStateOf(ApPalette.colorSource(ctx)) }
    var motion by remember { mutableIntStateOf(ApTiming.motionIndex(ctx)) }
    // 震动 / 音效也跟「一键主题」走：套完要显示新档（ApThemePresets.apply 写的就是这两个门面）
    var haptic by remember(presetLookRev) { mutableIntStateOf(ApFeel.hapticIndex(ctx)) }
    var sound by remember(presetLookRev) { mutableIntStateOf(ApFeel.soundIndex(ctx)) }
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
            // v223.29.6：**不再 onRecreate()**。背景层现在以 `ApBackdrop.rev` 为 key
            // 重读偏好与重新解码（见 rememberApBackdrop），选完图当场就换 —— 不必再把
            // 整个界面重建一遍（成宇 2026-11-04「背景图选择后没反映，要重启」的根治）。
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
    // 一指横滑切分组（成宇 2026-11-10「设置页的分组切换不方便」+「就按这套做」；
    // 2026-11-10「鼠标调节高度，键盘高度，这两个和滑动切分组冲突，界面调节调节时界面会跑」修正）：
    // 向左滑 = 下一组、向右滑 = 上一组（与横屏四指切页同方向），页签保留不动。
    // 冲突仲裁靠**事件归属**，不猜意图 —— 判定必须挂 Main pass（子级先于本修饰符处理）：
    // · 滑杆是本页唯一横向拖动的控件，它的拖动一动手就吃掉事件 —— Main pass 里同一
    //   事件先过滑杆再到这里，`isConsumed` 当场可见，整段手势立刻退出；此前挂 Initial
    //   pass（父级先看）时子控件的消费永远看不到（每个新事件都是干净实例），56dp 阈值
    //   一到就翻组并把剩余序列吃掉，滑杆拖一半页面跑了 —— 真机复现即此因；
    // · 垂直滚动列表同理（滚动消费纵向拖动），配合「横向 > 2×纵向」的方向门槛，
    //   上下滑 / 斜滑都不会误触发；与主壳竖滑收低栏（纵向 > 2×横向）方向正交；
    // · 阈值 56dp 与主壳四指切页同一套；触发后把剩余序列消费掉，
    //   一滑只翻一组，页签 / 开关的点击不受影响（短按到不了阈值）；
    // · 双指落下即放弃（与低栏长按同口径：手势语义是「一根手指」）。
    // 阈值密度用 rememberUpdatedState：pointerInput(Unit) 不随重组重启，直接捕获
    // 局部变量会把**第一次组合**的密度焊死——界面缩放（80%~125%）一改，56dp 的
    // 阈值就漂移（最坏差 45%），与四指切页「拿旧值当新值」同族。每帧读最新值。
    val swipeDensity by androidx.compose.runtime.rememberUpdatedState(LocalDensity.current.density)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Main,
                    )
                    val sx = down.position.x
                    val sy = down.position.y
                    var fired = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        // 子控件（滑杆 / 滚动列表）认领了拖动 → 手势让路，整段作废
                        if (ev.changes.any { it.isConsumed }) break
                        if (ev.changes.count { it.pressed } > 1) break
                        val c = ev.changes.firstOrNull { it.id == down.id }
                            ?: ev.changes.firstOrNull()
                            ?: break
                        if (!c.pressed) break
                        if (fired) continue
                        val dx = c.position.x - sx
                        val dy = c.position.y - sy
                        if (kotlin.math.abs(dx) > 56f * swipeDensity &&
                            kotlin.math.abs(dx) > 2f * kotlin.math.abs(dy)
                        ) {
                            fired = true
                            cat = if (dx < 0) (cat + 1) % catLabels.size
                            else (cat + catLabels.size - 1) % catLabels.size
                            Feedback.haptic(ctx)
                            // 翻完收掉剩余序列：一滑只翻一组
                            while (true) {
                                val ev2 = awaitPointerEvent(PointerEventPass.Main)
                                ev2.changes.forEach { it.consume() }
                                if (ev2.changes.none { it.pressed }) break
                            }
                            break
                        }
                    }
                }
            },
    ) {
    if (!twoPane) {
    // 竖屏页签：四个分类名合计 20 个汉字（主题与外观 / 快捷键与外设 / 工具与传输 / 数据与维护），
    // 400dp 宽的手机上每个只剩约 89dp，装不下 6 个汉字 —— 真机（v223.22）上「快捷键与外设」
    // 「工具与传输」被压成两行、整行顶到右边界，观感像被裁切。
    //
    // 修法不是缩字号也不是改文案（后者要动 strings.xml，还会丢分类语义），而是给这一行
    // **横向滚动**：可横滑的 Row 给子项无限宽约束，Miuix `TextButton` 内部就不会再折行，
    // 窄屏横滑即可看全；宽屏（≥600dp 走 twoPane）仍是左侧竖排，不受影响。
    // padding 放在 horizontalScroll 之前：左右留白固定，只有按钮参与滚动。
    // issue#168 第 4 条（2026-11-11）：竖屏四签改**紧凑居中**。horizontalScroll 的 Row 实测宽度
    // 是「内容宽夹外层约束」—— 窄屏装不下时 Row 占满并保留横滑兜底，装得下时 Row 等于内容宽，
    // 外层 Box 居中即可；间距 6→4dp 收紧，四签更聚拢。
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Row(
        modifier = Modifier
            .padding(start = 12.dp + insets.left, end = 12.dp + insets.right, top = 4.dp + topBar)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        catLabels.forEachIndexed { idx, name ->
            // 审查条 2.1：加选中态（此前四个页签一模一样，切完看不出在哪一类）
            ApComponents.TabChip(text = name, selected = idx == cat, onClick = { cat = idx })
        }
    }
    }

    }
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
    if (twoPane) {
        Column(
            modifier = Modifier
                .width(180.dp)
                .fillMaxHeight()
                // 左侧分类列只垫 8dp：横屏那张低栏挪到了**右上角**，左侧这一竖列
                // 与它不重叠，跟着垫 96dp 只会在左上角留一段没用的空档。
                .padding(start = 12.dp + insets.left, top = 8.dp)
                // issue#168 第 3 条：横屏左侧四签「靠左居中」—— 四项固定、总高有限
                // （约 4×48dp，最矮的横屏窗口也放得下），竖直居中比贴顶好看；
                // 去掉防御性滚动（可滚动布局里居中参数不生效）。
                ,
            verticalArrangement = Arrangement.Center,
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
            // ———————————————— 一键主题（第①层，成宇 2026-11-04「做成主题最好」） ————————————————
            // 设置页「主题与外观」三层结构：①一键主题（明暗 / 取色 + 四套命名搭配）→
            // ②常用微调（整机强调色 / 键帽材质 / 特效 / 动效 / 音效）→ ③进阶设置（默认
            // 折叠：分模块皮肤 / 键帽密度 / 键位排列）。换「整体感觉」只看①，调细节去②，
            // ①②满足不了的少数派需求进③翻。
            // 明暗 / 取色从原「外观」段并入本段：它们本就是一键主题的一部分，单独占一段
            // 既多一屏又割裂。四套命名搭配一键写进同一批偏好（ApThemePresets.apply 是
            // 唯一写入口，不新造状态），套完仍可逐项微调，微调后这里显示「自定义」。
            ApComponents.Section(title = ctx.getString(R.string.ui_theme_preset_sec)) {
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

            // ———————————————— 一键主题（成宇 2026-11-04「做成主题最好」） ————————————————
            // 皮肤 / 键帽 / 特效 / 震动 / 音效 分散在下面三个分组，换「整体感觉」要改五项。
            // 这里给四套命名搭配，一键写进同一批偏好（ApThemePresets.apply 是唯一写入口，
            // 不新造状态）。套完仍可逐项微调，微调后这里显示「自定义」。
            ApComponents.Section(title = ctx.getString(R.string.ui_theme_preset_sec)) {
                Column {
                    // 以 applyRev 为 key：套完主题当场重算「当前是这套」，下面的
                    // 键帽/特效/震动/音效四个下拉也一并重读到新档（各自 remember 的 key 同源）。
                    val presetRev = com.allperiph.ui.ApThemePresets.applyRev
                    val matched = androidx.compose.runtime.remember(presetRev) {
                        com.allperiph.ui.ApThemePresets.ALL.firstOrNull {
                            com.allperiph.ui.ApThemePresets.matches(ctx, it)
                        }
                    }
                    ApComponents.ValueRow(
                        title = ctx.getString(R.string.ui_theme_preset_current),
                        value = matched?.let { com.allperiph.ui.ApThemePresets.name(ctx, it) }
                            ?: ctx.getString(R.string.ui_theme_preset_custom),
                    )
                    com.allperiph.ui.ApThemePresets.ALL.forEach { p ->
                        ApComponents.ActionButton(
                            text = com.allperiph.ui.ApThemePresets.name(ctx, p) + " · " +
                                com.allperiph.ui.ApThemePresets.desc(ctx, p),
                            onClick = {
                                com.allperiph.ui.ApThemePresets.apply(ctx, p)
                                ApAccentSync.apply(ctx)
                                Feedback.haptic(ctx)
                            },
                        )
                    }
                }
            }

            // ———————————————— 常用微调（第②层） ————————————————
            // 「整机强调色」一次写 ApSkin.SCOPES 三处（与一键主题同一口径），绝大多数
            // 换肤需求点这一个下拉就够；确有「触控板蓝、键盘紫」这类分模块需求的，
            // 去第③层「进阶设置」逐个改。键帽材质 / 特效从「键盘外观」段上提，动效 /
            // 音效从原「动效」段并入：调观感手感时都在这一层解决。
            // KeyPref 偏好此前**只有实现、没有入口** —— setKeycapStyle / setDensity /
            // setLayout 全仓零调用，用户想换机械键帽或 Dvorak 只能改代码。这里给唯一的
            // 一组入口，初值直接读 KeyPref 自己的 index（不另立一份状态，避免"界面显示
            // A、实际渲染 B"）。key = 一键主题的 applyRev：套完整套主题后这些下拉要立刻
            // 显示新档，否则界面写「机械键盘」而实际存的是「液态玻璃」（成宇 2026-11-04
            // 这套需求的核心就是「整套一起变」，显示与存储必须同源）。
            val lookRev = com.allperiph.ui.ApThemePresets.applyRev
            var kbStyle by remember(lookRev) { mutableIntStateOf(com.allperiph.ui.KeyPref.keycapStyleIndex(ctx)) }
            var kbDensity by remember(lookRev) { mutableIntStateOf(com.allperiph.ui.KeyPref.densityIndex(ctx)) }
            var kbLayout by remember(lookRev) { mutableIntStateOf(com.allperiph.ui.KeyPref.layoutIndex(ctx)) }
            var kbFx by remember(lookRev) { mutableIntStateOf(com.allperiph.ui.KeyPref.effectIndex(ctx)) }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_fine_tune)) {
                Column {
                    val skinPicks = ApSkin.SCOPES.map { (_, scope) -> ApSkin.picked(ctx, scope) }
                    val wholeSkinSelected = if (skinPicks.toSet().size == 1) {
                        val unified = skinPicks.first()
                        if (unified.isBlank()) ApSkin.PRESETS.size
                        else ApSkin.PRESETS.indexOfFirst { it.id == unified }.coerceAtLeast(0)
                    } else {
                        ApSkin.PRESETS.size
                    }
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_set_skin_whole),
                        summary = when {
                            skinPicks.all { it.isBlank() } -> ctx.getString(R.string.ui_set_skin_follow)
                            skinPicks.toSet().size == 1 ->
                                ApSkin.PRESETS.firstOrNull { it.id == skinPicks.first() }?.name
                                    ?: ctx.getString(R.string.ui_set_skin_follow)
                            else -> ctx.getString(R.string.ui_set_skin_mixed)
                        },
                        items = ApSkin.PRESETS.map { it.name } + ctx.getString(R.string.ui_set_skin_follow),
                        selectedIndex = wholeSkinSelected,
                        onSelectedIndexChange = { i ->
                            Feedback.haptic(ctx)
                            val id = if (i < ApSkin.PRESETS.size) ApSkin.PRESETS[i].id else null
                            ApSkin.SCOPES.forEach { (scope, _) ->
                                ApSkin.set(ctx, scope, id)
                            }
                            ApAccentSync.apply(ctx)
                            onRecreate()
                        },
                    )
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
                    // 「震动」按成宇 2026-11-03「震动没有映射过来，映射到外设模式里了」留在
                    // 「快捷键与外设 → 通用」：音效是**出声**（外观体验），震动是**按键手感**
                    // （手机怎么响应输入），与光标锁定 \/ 手势遮蔽同类。本层只收动效与音效。
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
                                // 不再 onRecreate()：背景层以 ApBackdrop.rev 为 key 自动重读偏好
                                // （v223.29.6「选图/换底色/开关动态当场生效」）。
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
                            Feedback.haptic(ctx)
                            // setBg 里 rev++ → 背景层当场换底，不必重建 Activity
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
                            // setBgAnim 里 rev++ → 主壳背景层当场启停；两个自绘页
                            // （触控板 / 遥控）下次进入时照样读到新值
                        },
                    )
                    ApComponents.ValueRow(
                        title = ctx.getString(R.string.ui_set_bg_scope),
                        value = ctx.getString(R.string.ui_set_bg_scope_value),
                    )
                }
            }
            // ———————————————— 进阶设置（第③层，默认折叠） ————————————————
            // 分模块皮肤 / 键帽密度 / 键位排列：①②两层覆盖不了的少数派需求。
            // 默认收起 —— 日常换肤不该在七八个下拉里找入口，需要的人展开一次即可。
            // 生效时机的如实说明保留在展开区内。
            var advOpen by remember { mutableStateOf(false) }
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_advanced)) {
                Column {
                    ApComponents.ActionButton(
                        text = ctx.getString(
                            if (advOpen) R.string.ui_set_adv_collapse else R.string.ui_set_adv_expand,
                        ),
                        onClick = { advOpen = !advOpen },
                    )
                    if (advOpen) {
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
                    // ★ v223.29.9 设备形态（成宇 2026-11-05「是否有识别手机，平板，电视的设置」）。
                    // 默认「自动」不改任何现有排版；选手机 / 平板 / 电视才覆盖
                    // [rememberApAdaptiveLayout] 推出来的形态（见 [DeviceFormPref.resolve]）。
                    // summary 在「自动」时写成「自动（当前：手机）」—— 让用户看见自动判断的结论，
                    // 否则只写「自动」他不知道自己现在到底按哪个形态在排。
                    var formIdx by remember { mutableStateOf(DeviceFormPref.formIndex(ctx)) }
                    // 宽度取窗口实测像素（容器宽），0 时退回 Configuration —— 便携屏 / 分屏下
                    // Configuration.screenWidthDp 说的是「屏幕」不是「窗口」，会偏大。
                    val formWinPx = LocalWindowInfo.current.containerSize.width
                    val formWidthDp = if (formWinPx > 0) {
                        with(LocalDensity.current) { formWinPx.toDp().value }
                    } else {
                        ctx.resources.configuration.screenWidthDp.toFloat()
                    }
                    WindowDropdownPreference(
                        title = ctx.getString(R.string.ui_form_title),
                        summary = DeviceFormPref.describe(ctx, formWidthDp),
                        items = DeviceFormPref.labels(ctx),
                        selectedIndex = formIdx,
                        onSelectedIndexChange = { i ->
                            formIdx = i
                            DeviceFormPref.setFormIndex(ctx, i)
                            Feedback.haptic(ctx)
                            // 形态是壳级排版输入（顶栏高度、双栏断点、低栏位置全由它推），
                            // 改完必须整壳重建才会按新形态重排；与「主题明暗 / 色彩源」
                            // 同一口径（那些也走 onRecreate）。
                            onRecreate()
                        },
                    )
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
                    // 横持自动切页（成宇 2026-11-04「横竖屏切换好像被你强制了」）：
                    // 默认关。旧行为是「一转手机就换页」，读加速度计、不看系统方向锁，
                    // 用户开着竖屏锁定也会被翻走。这里给它一个显式开关注释，别再默认抢方向。
                    var autoSwap by remember { mutableStateOf(OrientLock.autoSwapEnabled(ctx)) }
                    ApComponents.SwitchRow(
                        title = ctx.getString(R.string.ui_set_auto_swap_title),
                        summary = ctx.getString(R.string.ui_set_auto_swap_sum),
                        checked = autoSwap,
                        onCheckedChange = { on ->
                            autoSwap = on
                            OrientLock.setAutoSwap(ctx, on)
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
                        summary = when {
                            localSendWanted && !localSend ->
                                ctx.getString(R.string.ui_set_sum_localsend_stale)
                            // 服务在前台、HTTP 也正常，但发现通道（UDP）没起来时，PC 面板的
                            // 「附近设备」会一直是空的 —— 这里必须说出来，否则用户只会觉得
                            // 「开关明明开着却用不了」（v223.29.32 现场就是这个状态）。
                            localSend && !com.allperiph.localsend.LocalSendService.discoveryUp ->
                                "发现通道未就绪（电脑可能看不到这台设备）：关掉再打开一次即可重建"
                            else -> "开启后在局域网内接收文件"
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
                    // issue#168 第 11 条三追（2026-11-11）：切后台唤起悬浮球在 Android 12+
                    // 被「后台启动前台服务」限制拦住，而拥有 SYSTEM_ALERT_WINDOW（悬浮窗）
                    // 权限的应用**豁免**这条限制 —— 引导用户授一次权限，自愈链路就通了。
                    // 状态在每次回到前台时重读（设置页授权返回后要立即反映），不缓存。
                    var overlayGranted by remember {
                        mutableStateOf(android.provider.Settings.canDrawOverlays(ctx))
                    }
                    androidx.compose.runtime.DisposableEffect(ctx) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                overlayGranted = android.provider.Settings.canDrawOverlays(ctx)
                            }
                        }
                        val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                        owner.lifecycle.addObserver(observer)
                        onDispose { owner.lifecycle.removeObserver(observer) }
                    }
                    ApComponents.ClickRow(
                        title = "悬浮窗权限",
                        summary = if (overlayGranted) "已授予，切后台自动唤起悬浮球可正常生效"
                        else "未授予：切后台自动唤起悬浮球会被系统拦截，点这里去系统设置授权",
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:" + ctx.packageName),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    // issue#168 第 9 条（2026-11-11）：球面图标内置六选一；相册图片皮肤见下批
                    // （相册取图 + 球面裁圆，取图入口在本分组尾部）。
                    // 点击 = 落盘（BallIcon.set）+ 对活着的球即时换图（FloatingBallService.applyIcon）；
                    // 服务没跑时静默 —— 下次 addBall 挂球会按落盘的选择重设。
                    var ballIcon by remember { mutableStateOf(BallIcon.picked(ctx)) }
                    // 相册皮肤取图（issue#168 第 9 条剩余）：GetContent 选图 → 中心裁方
                    // 副本落私有目录（BallIcon.setPhoto）→ 选中 CUSTOM → 活着的球即时换图。
                    // 用 GetContent 而非 PickVisualMedia：只读一次立即拷贝，不需要持久授权。
                    val ballSkinPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    ) { uri ->
                        if (uri != null && BallIcon.setPhoto(ctx, uri)) {
                            ballIcon = BallIcon.CUSTOM
                            FloatingBallService.applyIcon(ctx)
                            Feedback.haptic(ctx)
                        }
                    }
                    Text(
                        text = "球面图标",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BallIcon.CHOICES.forEach { name ->
                            val selected = name == ballIcon
                            Image(
                                painter = painterResource(BallIcon.res(name)),
                                contentDescription = name,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        ballIcon = name
                                        BallIcon.set(ctx, name)
                                        FloatingBallService.applyIcon(ctx)
                                        Feedback.haptic(ctx)
                                    },
                                // 预览色对齐球面观感：未选中次级文字色、选中主题主色 ——
                                // 选中态靠描边 + 着色双通道，只靠描边在 36dp 上不够醒目。
                                // foundation.Image 没有 colorTint 参数，着色走 colorFilter.tint
                                // （Miuix 的 Icon 才有 colorTint 这名字，别混）。
                                colorFilter = ColorFilter.tint(
                                    if (selected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceSecondary,
                                ),
                            )
                        }
                        // 「相册」入口：与内置图标同排的 36dp 圆点，点开系统选图。
                        // 皮肤预览用选中态描边 + 主题色「相册」字样（不放原图 —— 取图前
                        // 没有可显示的内容，取完图 ballIcon 即为 CUSTOM，重进设置页可见）。
                        val skinSelected = ballIcon == BallIcon.CUSTOM
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (skinSelected) 2.dp else 0.dp,
                                    color = if (skinSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { ballSkinPicker.launch("image/*") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "相册",
                                fontSize = 11.sp,
                                color = if (skinSelected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceSecondary,
                            )
                        }
                    }
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
                    // ★ v223.29.9 悬浮面板高度（成宇 2026-11-05「悬浮窗出来的窗口应该可以调节大小」）。
                    // 触控板与键盘面板的高度此前是写死的屏幕比例，这里给两枚滑块直接给数值；
                    // 展开面板时顶部还有一个拖拽把手可以上下拉（两条路写的是同一份偏好）。
                    // 「未自定义」显示 0 会看着像坏了，所以按当前实际比例回显（竖屏触控板 30%、
                    // 键盘 52%；横屏 34% / 66% —— 与 FloatingBallService 的旧版写死值同口径）。
                    val isL = rememberApAdaptiveLayout().landscape
                    var padPct by remember(isL) {
                        mutableIntStateOf(
                            PanelSize.percent(ctx, PanelSize.TOUCHPAD)
                                .takeIf { it > 0 } ?: if (isL) 34 else 30,
                        )
                    }
                    var kbPct by remember(isL) {
                        mutableIntStateOf(
                            PanelSize.percent(ctx, PanelSize.KEYBOARD)
                                .takeIf { it > 0 } ?: if (isL) 66 else 52,
                        )
                    }
                    SliderPreference(
                        title = ctx.getString(R.string.ui_ball_panel_pad),
                        summary = ctx.getString(R.string.ui_ball_panel_pad_sum),
                        value = padPct.toFloat(),
                        onValueChange = { padPct = it.toInt() },
                        valueRange = PanelSize.MIN_PCT.toFloat()..PanelSize.MAX_PCT.toFloat(),
                        steps = 69,
                        valueText = ctx.getString(R.string.ui_ball_panel_pct, padPct),
                        onValueChangeFinished = {
                            PanelSize.setPercent(ctx, PanelSize.TOUCHPAD, padPct)
                            Feedback.haptic(ctx)
                        },
                    )
                    SliderPreference(
                        title = ctx.getString(R.string.ui_ball_panel_kb),
                        summary = ctx.getString(R.string.ui_ball_panel_kb_sum),
                        value = kbPct.toFloat(),
                        onValueChange = { kbPct = it.toInt() },
                        valueRange = PanelSize.MIN_PCT.toFloat()..PanelSize.MAX_PCT.toFloat(),
                        steps = 69,
                        valueText = ctx.getString(R.string.ui_ball_panel_pct, kbPct),
                        onValueChangeFinished = {
                            PanelSize.setPercent(ctx, PanelSize.KEYBOARD, kbPct)
                            Feedback.haptic(ctx)
                        },
                    )
                    // ★ 2026-11-07 扇形菜单大小（成宇「悬浮球弹出的窗口要能调节大小」）：
                    // 上面两枚滑块管的是**面板高度**，这一行管球展开后那一圈菜单 ——
                    // 项直径与扇形半径一起换档，点一下循环（紧凑 → 标准 → 大），立即落盘。
                    // 菜单是每次展开现建的，回到桌面点球就能看到新尺寸。
                    var menuScale by remember {
                        mutableIntStateOf(BallConfig.menuScale(ctx))
                    }
                    ApComponents.ClickRow(
                        title = ctx.getString(R.string.ui_ball_menu_size),
                        summary = ctx.getString(R.string.ui_ball_menu_size_sum),
                        trailing = {
                            Text(
                                text = ctx.getString(
                                    when (menuScale) {
                                        BallConfig.MENU_COMPACT -> R.string.ui_ball_menu_size_compact
                                        BallConfig.MENU_LARGE -> R.string.ui_ball_menu_size_large
                                        else -> R.string.ui_ball_menu_size_normal
                                    },
                                ),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            )
                        },
                        onClick = {
                            val next = when (menuScale) {
                                BallConfig.MENU_COMPACT -> BallConfig.MENU_NORMAL
                                BallConfig.MENU_NORMAL -> BallConfig.MENU_LARGE
                                else -> BallConfig.MENU_COMPACT
                            }
                            menuScale = next
                            BallConfig.setMenuScale(ctx, next)
                            Feedback.haptic(ctx)
                        },
                    )
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
                    // 摄像头此前只有「PC 面板开关 / 通知栏动作」两条拉起路径，
                    // 应用内没有任何入口 —— 用户反馈「手机端没有摄像头的入口」。
                    ApComponents.ActionButton(
                        text = ctx.getString(R.string.ui_set_btn_camera),
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(ctx, com.allperiph.camera.CameraPushActivity::class.java),
                                )
                            }
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
            // ————————————— 界面缩放（2026-11-07 成宇「无极自定义 dpi 缩放」） —————————————
            // steps=0 = 连续滑条（无极）；80%~125%：再小字不可读，再大就失去
            // 「多看一截」的意义。拖动即刻生效（UiScalePref.current 是 Compose 状态，
            // 主壳与弹窗两处 LocalDensity 都读它，不用 recreate）。
            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_ui_scale)) {
                // 跟手修复（成宇 2026-11-07「滑动条没有跟手」）：滑条自身不吃全局
                // 缩放 —— 拖动时它跟着变大会边拖边变形、thumb 对不上手指。这里按
                // 100% 渲染（当前密度 ÷ 缩放），缩放只作用于它之外的整树（上层
                // 已改 animate 150ms 平滑，不再跳变）。
                val __sd = LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(
                            __sd.density / com.allperiph.ui.UiScalePref.current.floatValue,
                            __sd.fontScale,
                        ),
                ) {
                SliderPreference(
                    value = com.allperiph.ui.UiScalePref.current.floatValue,
                    // 拖动中只改内存（setLive），松手才落盘（persist）——每帧写
                    // SharedPreferences 是「界面调节卡着」的来源，见 UiScalePref.setLive。
                    onValueChange = { com.allperiph.ui.UiScalePref.setLive(it) },
                    onValueChangeFinished = { com.allperiph.ui.UiScalePref.persist(ctx) },
                    title = ctx.getString(R.string.ui_set_label_ui_scale),
                    valueText = "${(com.allperiph.ui.UiScalePref.current.floatValue * 100).toInt()}%",
                    valueRange = com.allperiph.ui.UiScalePref.MIN..com.allperiph.ui.UiScalePref.MAX,
                    steps = 0,
                )
                }
            }

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
                        ApComponents.NarrowButton(
                            text = "应用「${t.name}」",
                            onClick = {
                                HotkeyStore.applyTemplate(ctx, t)
                                Toast.makeText(ctx, "已应用：${t.name}", Toast.LENGTH_SHORT).show()
                                Feedback.haptic(ctx)
                            },
                        )
                        if (index > 0) {
                            ApComponents.NarrowButton(
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
                        ApComponents.NarrowButton(
                            text = "删除「${t.name}」",
                            onClick = {
                                val l = customs.toMutableList()
                                l.removeAt(index)
                                customs = l
                                HotkeyStore.saveCustom(ctx, l)
                            },
                        )
                    }
                    // issue#168 第 3 条：这是模板管理区唯一的页级动作按钮，别的动作按钮都是
                    // 整行居中，就它随文字宽靠左 —— 升级成 ActionButton 与周围对齐。
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
                    ApComponents.ValueRow(
                        title = "当前版本",
                        value = UpdateChecker.currentTag(),
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
                                if (r is UpdateChecker.Result.Failed) {
                                    // v223.29.32：失败不再只吐一行『检查失败』就结束（上一版
                                    // 『在线更新不了』的体验根因）—— 弹出两个手动出口。
                                    com.allperiph.ui.ApDialog.builder(ctx)
                                        .setTitle("检查更新失败")
                                        .setMessage("网络可能不通（GitHub 直连与镜像均失败）。可手动打开 Release 页下载安装包。")
                                        .setPositiveButton("国内镜像打开") { _, _ -> UpdateChecker.openReleaseMirror(ctx) }
                                        .setNeutralButton("GitHub 原页") { _, _ -> UpdateChecker.openReleasePage(ctx) }
                                        .setNegativeButton("稍后", null)
                                        .show()
                                    return@check
                                }
                                val msg = when (r) {
                                    is UpdateChecker.Result.UpToDate -> "已是最新：${r.tag}"
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
