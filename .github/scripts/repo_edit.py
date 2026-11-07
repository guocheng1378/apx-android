#!/usr/bin/env python3
"""repo-edit：批 U-7/v4-v7（AOSP 系统栏 + 挖孔语义 + dpi 平滑 + 控件降级 + 状态页自检行 +
PC 悬停小字 + 低栏透明悬浮 + 全界面长文本精简与颜色表达）。

v3 修正（run #89 错误日志定位）：
  1. SUB 子页目录常量写错分隔符（com.allperiph 点号 → com/allperiph 斜杠），
     7 子页 open 全部 ENOENT；真实路径经 contents API 实证为斜杠。
  2. SettingsPage「上移」按钮在 if (index > 0) 块内，缩进深一层（28/32 空格），
     v2 锚按 24/28 写导致 count=0；应用/删除/新建三个按钮 v2 已过验证，不动。
  3. 成功时清理上次遗留的 docs/edit_error.log，失败时照旧落盘全部错误。
v4（成宇 2026-11-07 凌晨截图「你自己看看」）：
  4. StatusPage 环境自检行 ValueRow → ItemRow(summary)：长诊断详情在右值位只分到
     46% 宽、两行省略吃尾巴，老包上更是把左标题挤成竖排。改挂 title 下方全宽
     summary（3 行）。run #91 已落地（90516c6f）。
v5（成宇 2026-11-07「我的软件没有要改的吗」深审——按组件选型口诀补悬停小字）：
  5. 6 处关闭类图标按钮补 title（悬停小字=Tooltip）：App.tsx 更新×、代输 Dock×、
     文件面板/设置/日志导出/代输 4 个 Modal 头×（后 4 处同时补 aria-label）。
     bot 已落地 ea9e4d3。口诀其余五条对照通过：Toast 队列 ✓、Notice 顶部常驻 ✓、
     Modal 只留真阻断 ✓、删除两段式确认 ✓、Drawer 例外（分页就地编辑）✓。
v6（成宇 2026-11-07「低栏缩回否应该是透明悬浮的而不是强占界面面积」选方案4）：
  6. ApShell bottomGap/topGap 固定值 → 跟随 barCollapsed 的 animateDpAsState：
     收起收到 8dp，与 barFx 同口径弹簧；键盘页横屏 40dp 特判不动。bot 已落地 25c7cf4b。
  7. 收起态 48dp 球实心 surfaceContainer → 半透明渐变玻璃 + 1dp 高光描边。
v7（成宇 2026-11-07「各个界面的长文本还是太多了，能删的删，能用颜色代替的用颜色」）：
  8. strings.xml 21 条界面长文案精简：删冗余括号说明（位置/路径/自明操作）、Toast 收到
     ~25 字内、提示复句改「·」分隔短句。**key 一律不动**（引用零断裂）；含 %1$s 的
     ui_filepanel_where 保留占位符、删第二行自明操作（按钮上就写着）。每条锚经本地
     grep 实证 count=1。accessibility_desc（系统无障碍页描述）与 ui_help_*（帮助页
     本体即文档）本轮不改。
  9. 颜色代替文字 2 处：StatusPage USB 提示 —— 2.0 慢档改 module_state_pending 琥珀
     警示、3.0 保持灰；ApTouchpadSurface 手势提示 —— 服务未开/没出口改
     module_state_error 红色阻断警示、正常说明保持灰。全部走既有 R.color 令牌
     （明暗两套齐全），不引硬编码色；两处锚均经本地 grep -A 验证相邻且唯一。
其余 U-7 锚已在 run #89/#90/#91 验证通过（已落地，自动 skip）。
"""
import os
import sys

INSETS = "android/app/src/main/java/com/allperiph/ui/miuix/ApInsets.kt"
SHELL = "android/app/src/main/java/com/allperiph/ui/compose/ApShellActivity.kt"
COMP = "android/app/src/main/java/com/allperiph/ui/miuix/ApComponents.kt"
SET = "android/app/src/main/java/com/allperiph/ui/compose/SettingsPage.kt"
ST = "android/app/src/main/java/com/allperiph/ui/compose/StatusPage.kt"
SUB = "android/app/src/main/java/com/allperiph"
WAPP = "pc/web/src/App.tsx"
WDOCK = "pc/web/src/components/RemoteInputDock.tsx"
WPANEL = "pc/web/src/components/FilePanelModal.tsx"
WSETM = "pc/web/src/components/SettingsModal.tsx"
WLOGM = "pc/web/src/components/LogExportModal.tsx"
WRMTM = "pc/web/src/components/RemoteInputModal.tsx"
ERRLOG = "docs/edit_error.log"

SUB_PAGES = [
    SUB + "/ui/RemoteActivity.kt",
    SUB + "/touchpad/TouchpadActivity.kt",
    SUB + "/screen/ScreenActivity.kt",
    SUB + "/ui/GamepadActivity.kt",
    SUB + "/controlled/RemoteInputActivity.kt",
    SUB + "/localsend/SendActivity.kt",
    SUB + "/ui/FilePanelActivity.kt",
]

EDITS = []

# ———————— ApInsets.kt ————————
EDITS += [
    {
        "id": "U7-1 KDoc 改口径",
        "file": INSETS,
        "kind": "replace",
        "anchor": " * 全屏：隐藏状态栏与导航栏（成宇 2026-11-02「所有页屏蔽状态栏」）。",
        "new": (
            " * edge-to-edge（成宇 2026-11-07「按安卓 aosp 规范适配，而不是直接隐藏状态栏和\n"
            " * 手势小白条」）：系统栏显示且透明，内容经 [rememberApInsets] 自行垫边。\n"
            " * 2026-11-02「所有页屏蔽状态栏」的隐藏口径已按新需求反转；函数随之更名。"
        ),
    },
    {
        "id": "U7-2 定义改 applyEdgeToEdge",
        "file": INSETS,
        "kind": "replace",
        "anchor": (
            "fun android.app.Activity.hideSystemBars() {\n"
            "    window.setDecorFitsSystemWindows(false)\n"
            "    window.decorView.post {\n"
            "        val c = window.insetsController ?: return@post\n"
            "        c.systemBarsBehavior =\n"
            "            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE\n"
            "        c.hide(android.view.WindowInsets.Type.statusBars())\n"
            "        c.hide(android.view.WindowInsets.Type.navigationBars())\n"
            "    }\n"
            "}"
        ),
        "new": (
            "fun android.app.Activity.applyEdgeToEdge() {\n"
            "    window.setDecorFitsSystemWindows(false)\n"
            "    window.decorView.post {\n"
            "        val c = window.insetsController ?: return@post\n"
            "        // AOSP 规范（成宇 2026-11-07）：系统栏**显示**且透明，内容经 WindowInsets\n"
            "        // 自行垫边；BEHAVIOR_DEFAULT = 系统默认（下滑临时呼出）。\n"
            "        c.systemBarsBehavior =\n"
            "            android.view.WindowInsetsController.BEHAVIOR_DEFAULT\n"
            "        c.show(android.view.WindowInsets.Type.systemBars())\n"
            "    }\n"
            "}"
        ),
    },
    {
        "id": "U7-3 hole注释+holeTop",
        "file": INSETS,
        "kind": "replace",
        "anchor": (
            "    // —— 挖孔/大圆角回避（2026-11-07）——\n"
            "    // 语义：**不含系统栏**。状态栏是 hideSystemBars 刻意隐藏的（内容本来就画到\n"
            "    // 最上沿），这几项只算「孔 + 圆角」，不会把 v223.22 修掉的那条系统栏空档带回来。\n"
            "    val holeTop: Dp get() = maxOf(cutout.top, rounded.top)"
        ),
        "new": (
            "    // —— 挖孔回避（2026-11-07 二轮，成宇「圆角和挖孔优化不是直接上部分不显示内容」）——\n"
            "    // 语义：只算孔，**不算圆角**。圆角半径是屏幕四角的弧，系统 UI 自己会避让；\n"
            "    // 把它垫进 top 会把顶部内容整块推走（真机表现即「上部分不显示内容」）。\n"
            "    // 系统栏显示后，状态栏高度已天然盖住孔区，这里只作弹窗等窗口的兑底。\n"
            "    val holeTop: Dp get() = cutout.top"
        ),
    },
    {
        "id": "U7-4 holeBottom",
        "file": INSETS,
        "kind": "replace",
        "anchor": "    val holeBottom: Dp get() = maxOf(cutout.bottom, rounded.bottom)",
        "new": "    val holeBottom: Dp get() = cutout.bottom",
    },
    {
        "id": "U7-5 holeLeft",
        "file": INSETS,
        "kind": "replace",
        "anchor": "    val holeLeft: Dp get() = maxOf(cutout.left, rounded.left)",
        "new": "    val holeLeft: Dp get() = cutout.left",
    },
    {
        "id": "U7-6 holeRight",
        "file": INSETS,
        "kind": "replace",
        "anchor": "    val holeRight: Dp get() = maxOf(cutout.right, rounded.right)",
        "new": "    val holeRight: Dp get() = cutout.right",
    },
]

# ———————— 7 子页改名 ————————
for p in SUB_PAGES:
    EDITS.append({
        "id": "U7-import " + p.rsplit("/", 1)[-1],
        "file": p,
        "kind": "replace",
        "anchor": "import com.allperiph.ui.miuix.hideSystemBars",
        "new": "import com.allperiph.ui.miuix.applyEdgeToEdge",
    })
    EDITS.append({
        "id": "U7-call " + p.rsplit("/", 1)[-1],
        "file": p,
        "kind": "replace",
        "anchor": "hideSystemBars()",
        "new": "applyEdgeToEdge()",
    })

# ———————— ApShellActivity.kt ————————
EDITS += [
    {
        "id": "U7-shell import",
        "file": SHELL,
        "kind": "replace",
        "anchor": "import com.allperiph.ui.miuix.hideSystemBars",
        "new": "import com.allperiph.ui.miuix.applyEdgeToEdge",
    },
    {
        "id": "U7-shell call",
        "file": SHELL,
        "kind": "replace",
        "anchor": "        hideSystemBars()",
        "new": "        applyEdgeToEdge()",
    },
    {
        "id": "U7-shell topPad",
        "file": SHELL,
        "kind": "replace",
        "anchor": (
            "        // 状态栏整块屏蔽（成宇 2026-11-02「所有页屏蔽状态栏」）：applyImmersive 里\n"
            "        // statusBars 也一并 hide 了，所以这里**不用再按边算顶部留白** —— 内容直接画到\n"
            "        // 屏幕最上沿。此前竖屏垫 insets.top（且 v223.23 前那个值恒为 0），横竖屏各说各话，\n"
            "        // 顶栏标题才会压在系统时间那一行上。\n"
            "        // 顶栏整块去除后侧向留白也由各页自己按 insets.left/right 处理，这里不再介入。\n"
            "        // 2026-11-07（成宇「优化回避屏幕大圆角和摄像头挖孔，避免遮挡」）：上一段旧\n"
            "        // 注释里「不按边算顶部留白」说的是**系统栏**（仍成立，状态栏照旧隐藏）；本行\n"
            "        // 只按挖孔/大圆角让位 —— 顶部压着摄像头孔时标题与卡片不再画进孔里。横屏\n"
            "        // 孔在左右侧，走 ApAdaptive 的 padCutout 与页内 insets.left/right，这里恒≈0。\n"
            "        val topPad = com.allperiph.ui.miuix.rememberApInsets().holeTop"
        ),
        "new": (
            "        // 2026-11-07 二轮（成宇「按安卓 aosp 规范适配，不是直接隐藏状态栏和小白条」\n"
            "        // 「圆角和挖孔优化不是直接上部分不显示内容」）：系统栏改为显示\n"
            "        // （applyEdgeToEdge），顶部按**含状态栏**的 top 垫 —— 竖屏内容从状态栏下\n"
            "        // 开始（状态栏高度已天然盖住孔区）；横屏状态栏 inset 落在侧边、top≈0 不变；\n"
            "        // 圆角不再参与垫边（四角由系统 UI 避让，垫半径会把顶部内容整块推走）。\n"
            "        val topPad = com.allperiph.ui.miuix.rememberApInsets().top"
        ),
    },
    {
        "id": "U7-shell 缩放平滑",
        "file": SHELL,
        "kind": "replace",
        "anchor": (
            "            val __base = androidx.compose.ui.platform.LocalDensity.current\n"
            "            val __scale = com.allperiph.ui.UiScalePref.current.floatValue\n"
            "            androidx.compose.runtime.CompositionLocalProvider(\n"
            "                androidx.compose.ui.platform.LocalDensity provides\n"
            "                    androidx.compose.ui.unit.Density(__base.density * __scale, __base.fontScale),\n"
            "            ) {"
        ),
        "new": (
            "            val __base = androidx.compose.ui.platform.LocalDensity.current\n"
            "            // 平滑过渡（成宇 2026-11-07「dpi 变一下 UI 跳动一下」）：缩放改 150ms\n"
            "            // 缓动到位，密度不再一帧硬切 —— 整树跟一条曲线重排，跳变消失。\n"
            "            val __scale by androidx.compose.animation.core.animateFloatAsState(\n"
            "                targetValue = com.allperiph.ui.UiScalePref.current.floatValue,\n"
            "                animationSpec = androidx.compose.animation.core.tween(150),\n"
            "            )\n"
            "            androidx.compose.runtime.CompositionLocalProvider(\n"
            "                androidx.compose.ui.platform.LocalDensity provides\n"
            "                    androidx.compose.ui.unit.Density(__base.density * __scale, __base.fontScale),\n"
            "            ) {"
        ),
    },
    # ———————— ApComponents.kt ————————
    {
        "id": "U7-dialog 缩放平滑",
        "file": COMP,
        "kind": "replace",
        "anchor": (
            "            val __ins = rememberApInsets()\n"
            "            val __d = androidx.compose.ui.platform.LocalDensity.current\n"
            "            androidx.compose.runtime.CompositionLocalProvider(\n"
            "                androidx.compose.ui.platform.LocalDensity provides\n"
            "                    androidx.compose.ui.unit.Density(\n"
            "                        __d.density * com.allperiph.ui.UiScalePref.current.floatValue,\n"
            "                        __d.fontScale,\n"
            "                    ),\n"
            "            ) {"
        ),
        "new": (
            "            val __ins = rememberApInsets()\n"
            "            val __d = androidx.compose.ui.platform.LocalDensity.current\n"
            "            // 平滑过渡（成宇 2026-11-07）：与主壳同一口径，缩放 150ms 缓动不跳变。\n"
            "            val __s by androidx.compose.animation.core.animateFloatAsState(\n"
            "                targetValue = com.allperiph.ui.UiScalePref.current.floatValue,\n"
            "                animationSpec = androidx.compose.animation.core.tween(150),\n"
            "            )\n"
            "            androidx.compose.runtime.CompositionLocalProvider(\n"
            "                androidx.compose.ui.platform.LocalDensity provides\n"
            "                    androidx.compose.ui.unit.Density(__d.density * __s, __d.fontScale),\n"
            "            ) {"
        ),
    },
    # ———————— SettingsPage.kt ————————
    {
        "id": "U7-滑条固定density",
        "file": SET,
        "kind": "replace",
        "anchor": (
            "            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_ui_scale)) {\n"
            "                SliderPreference(\n"
            "                    value = com.allperiph.ui.UiScalePref.current.floatValue,\n"
            "                    onValueChange = { com.allperiph.ui.UiScalePref.set(ctx, it) },\n"
            "                    title = ctx.getString(R.string.ui_set_label_ui_scale),\n"
            "                    valueText = \"${(com.allperiph.ui.UiScalePref.current.floatValue * 100).toInt()}%\",\n"
            "                    valueRange = com.allperiph.ui.UiScalePref.MIN..com.allperiph.ui.UiScalePref.MAX,\n"
            "                    steps = 0,\n"
            "                )\n"
            "            }"
        ),
        "new": (
            "            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_ui_scale)) {\n"
            "                // 跟手修复（成宇 2026-11-07「滑动条没有跟手」）：滑条自身不吃全局\n"
            "                // 缩放 —— 拖动时它跟着变大会边拖边变形、thumb 对不上手指。这里按\n"
            "                // 100% 渲染（当前密度 ÷ 缩放），缩放只作用于它之外的整树（上层\n"
            "                // 已改 animate 150ms 平滑，不再跳变）。\n"
            "                val __sd = LocalDensity.current\n"
            "                androidx.compose.runtime.CompositionLocalProvider(\n"
            "                    androidx.compose.ui.platform.LocalDensity provides\n"
            "                        androidx.compose.ui.unit.Density(\n"
            "                            __sd.density / com.allperiph.ui.UiScalePref.current.floatValue,\n"
            "                            __sd.fontScale,\n"
            "                        ),\n"
            "                ) {\n"
            "                SliderPreference(\n"
            "                    value = com.allperiph.ui.UiScalePref.current.floatValue,\n"
            "                    onValueChange = { com.allperiph.ui.UiScalePref.set(ctx, it) },\n"
            "                    title = ctx.getString(R.string.ui_set_label_ui_scale),\n"
            "                    valueText = \"${(com.allperiph.ui.UiScalePref.current.floatValue * 100).toInt()}%\",\n"
            "                    valueRange = com.allperiph.ui.UiScalePref.MIN..com.allperiph.ui.UiScalePref.MAX,\n"
            "                    steps = 0,\n"
            "                )\n"
            "                }\n"
            "            }"
        ),
    },
    {
        "id": "U7-按钮：应用",
        "file": SET,
        "kind": "replace",
        "anchor": "                        ApComponents.ActionButton(\n                            text = \"应用「${t.name}」\",",
        "new": "                        ApComponents.NarrowButton(\n                            text = \"应用「${t.name}」\",",
    },
    {
        "id": "U7-按钮：上移",
        "file": SET,
        "kind": "replace",
        "anchor": "                            ApComponents.ActionButton(\n                                text = \"「${t.name}」上移\",",
        "new": "                            ApComponents.NarrowButton(\n                                text = \"「${t.name}」上移\",",
    },
    {
        "id": "U7-按钮：删除",
        "file": SET,
        "kind": "replace",
        "anchor": "                        ApComponents.ActionButton(\n                            text = \"删除「${t.name}」\",",
        "new": "                        ApComponents.NarrowButton(\n                            text = \"删除「${t.name}」\",",
    },
    {
        "id": "U7-按钮：新建",
        "file": SET,
        "kind": "replace",
        "anchor": "                    ApComponents.ActionButton(\n                        text = \"从内置模板新建一份可改的\",",
        "new": "                    ApComponents.NarrowButton(\n                        text = \"从内置模板新建一份可改的\",",
    },
    # ———————— StatusPage.kt（v4：环境自检行） ————————
    {
        "id": "U7-env行改summary",
        "file": ST,
        "kind": "replace",
        "anchor": (
            "                ApComponents.ValueRow(\n"
            "                    title = ctx.getString(R.string.ui_home_label_env),\n"
            "                    value = state.envSummary,\n"
            "                )"
        ),
        "new": (
            "                // 2026-11-07（成宇发截图「你自己看看」）：环境自检是长诊断详情\n"
            "                // （root · USB 速度 · 白名单…），放 ValueRow 右值位只有 46% 宽、\n"
            "                // 两行省略会吃掉尾巴；老包上更是把左标题挤成竖排。改挂 title 下方\n"
            "                // 全宽 summary（maxLines 3），标题独立成行、诊断信息一字不丢。\n"
            "                ApComponents.ItemRow(\n"
            "                    title = ctx.getString(R.string.ui_home_label_env),\n"
            "                    summary = state.envSummary,\n"
            "                )"
        ),
    },
    # ———————— PC 面板 Tooltip 补全（v5：组件选型口诀深审） ————————
    {
        "id": "U7-tip-更新×",
        "file": WAPP,
        "kind": "replace",
        "anchor": 'aria-label="忽略更新"',
        "new": 'aria-label="忽略更新" title="忽略更新"',
    },
    {
        "id": "U7-tip-代输Dock×",
        "file": WDOCK,
        "kind": "replace",
        "anchor": '<button className="icon-btn" onClick={onClose} aria-label="关闭">',
        "new": '<button className="icon-btn" onClick={onClose} aria-label="关闭" title="关闭">',
    },
    {
        "id": "U7-tip-文件面板×",
        "file": WPANEL,
        "kind": "replace",
        "anchor": '<button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose}>✕</button>',
        "new": '<button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose} title="关闭" aria-label="关闭">✕</button>',
    },
    {
        "id": "U7-tip-设置Modal×",
        "file": WSETM,
        "kind": "replace",
        "anchor": '<button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose}>✕</button>',
        "new": '<button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose} title="关闭" aria-label="关闭">✕</button>',
    },
    {
        "id": "U7-tip-日志导出×",
        "file": WLOGM,
        "kind": "replace",
        "anchor": '<button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose}>✕</button>',
        "new": '<button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose} title="关闭" aria-label="关闭">✕</button>',
    },
    {
        "id": "U7-tip-代输Modal×",
        "file": WRMTM,
        "kind": "replace",
        "anchor": '<button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose}>✕</button>',
        "new": '<button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose} title="关闭" aria-label="关闭">✕</button>',
    },
]

# ———————— v6：低栏收起态透明悬浮（方案4 = 动态让位 + 玻璃球） ————————
EDITS += [
    {
        "id": "U6-0 import border",
        "file": SHELL,
        "kind": "replace",
        "anchor": "import androidx.compose.animation.core.tween",
        "new": "import androidx.compose.animation.core.tween\nimport androidx.compose.foundation.border",
    },
    {
        "id": "U6-1 固定让位改动态",
        "file": SHELL,
        "kind": "replace",
        "anchor": (
            "        val bottomGap = if (landscape) 24.dp else 88.dp\n"
            "        val topGap = if (landscape) 96.dp else 0.dp"
        ),
        "new": (
            "        // 方案4（成宇 2026-11-07「低栏缩回应是透明悬浮，不该强占界面面积」）：\n"
            "        // 让位量不再固定，在下方 barCollapsed 声明处按收/展状态动画 —— 收起成球时\n"
            "        // 竖屏 88dp / 横屏 96dp 收到 8dp，内容回涨与球缩同一套弹簧；展开恢复原值。"
        ),
    },
    {
        "id": "U6-2 动态让位计算",
        "file": SHELL,
        "kind": "replace",
        "anchor": "        var barCollapsed by remember { mutableStateOf(false) }",
        "new": (
            "        var barCollapsed by remember { mutableStateOf(false) }\n"
            "        // 方案4：让位量跟收/展联动（弹簧与 barFx 同口径 LowBouncy+Medium，球缩与\n"
            "        // 内容回涨一次动画到位）。收起后只剩一枚 48dp 玻璃球浮在内容上，页面让位\n"
            "        // 收到 8dp —— 竖屏多出 80dp、横屏多出 88dp 可用高度。页面侧 bottomGap 只管\n"
            "        // 低栏让位，手势条避让走 insets.bottom（StatusPage:94），收到 8dp 安全。\n"
            "        // ⚠️ 键盘页横屏 40dp 特判不动：球与胶囊同角位、下沿高度相近，仍需避让修饰键排。\n"
            "        val bottomGap by androidx.compose.animation.core.animateDpAsState(\n"
            "            targetValue = if (barCollapsed) 8.dp else if (landscape) 24.dp else 88.dp,\n"
            "            animationSpec = androidx.compose.animation.core.spring(\n"
            "                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,\n"
            "                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,\n"
            "            ),\n"
            "            label = \"bottomGap\",\n"
            "        )\n"
            "        val topGap by androidx.compose.animation.core.animateDpAsState(\n"
            "            targetValue = if (landscape) (if (barCollapsed) 8.dp else 96.dp) else 0.dp,\n"
            "            animationSpec = androidx.compose.animation.core.spring(\n"
            "                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,\n"
            "                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,\n"
            "            ),\n"
            "            label = \"topGap\",\n"
            "        )"
        ),
    },
    {
        "id": "U6-3 玻璃球",
        "file": SHELL,
        "kind": "replace",
        "anchor": (
            "                            .clip(androidx.compose.foundation.shape.CircleShape)\n"
            "                            .background(MiuixTheme.colorScheme.surfaceContainer)"
        ),
        "new": (
            "                            .clip(androidx.compose.foundation.shape.CircleShape)\n"
            "                            // 方案4·玻璃化：实心 surfaceContainer → 半透明渐变玻璃 +\n"
            "                            // 1dp 高光描边（与展开胶囊同一套质感，shader-free 老机器同款）——\n"
            "                            // 收起后球浮在内容上、内容可透出，不再是压着界面的实心圆。\n"
            "                            .background(\n"
            "                                androidx.compose.ui.graphics.Brush.verticalGradient(\n"
            "                                    listOf(\n"
            "                                        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f),\n"
            "                                        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.46f),\n"
            "                                    ),\n"
            "                                ),\n"
            "                            )\n"
            "                            .border(\n"
            "                                width = 1.dp,\n"
            "                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(\n"
            "                                    listOf(\n"
            "                                        Color.White.copy(alpha = if (navDark) 0.16f else 0.52f),\n"
            "                                        Color.White.copy(alpha = if (navDark) 0.05f else 0.14f),\n"
            "                                    ),\n"
            "                                ),\n"
            "                                shape = androidx.compose.foundation.shape.CircleShape,\n"
            "                            )"
        ),
    },
]

# ———————— v7/U8：全界面长文本精简 + 颜色代替文字（成宇 2026-11-07） ————————
STR = "android/app/src/main/res/values/strings.xml"
TPS = "android/app/src/main/java/com/allperiph/ui/compose/ApTouchpadSurface.kt"


def _str(key, old, new):
    return {
        "id": "U8-str " + key,
        "file": STR,
        "kind": "replace",
        "anchor": '<string name="' + key + '">' + old + '</string>',
        "new": '<string name="' + key + '">' + new + '</string>',
    }


EDITS += [
    _str(
        "hint_usb2",
        "当前是 USB 2.0 的速度（慢档）。换一根 USB 3.0 数据线和 3.0 接口会明显更跟手。",
        "USB 2.0 慢档 · 换 3.0 线与接口更跟手",
    ),
    _str(
        "controlled_enable_hint",
        "被控模式已打开：请到系统「无障碍」里启用「全能外设」，并允许「显示在其他应用上层」，手机发来的操作才能真正作用到本机（没授权时只能看到一个跟着动的光标）。",
        "已开被控模式：请到「无障碍」启用本应用并允许悬浮窗",
    ),
    _str(
        "ui_gesture_shield_toast_no_root",
        "没有 root，遮蔽未生效：请手动关掉系统三指手势（设置 → 更多设置 → 快捷手势 → 截屏）",
        "无 root：请在系统设置手动关闭三指截屏手势",
    ),
    _str(
        "ui_home_quick_hint",
        "长按底部悬浮栏即可随时唤出这一排（竖屏底部居中、横屏在右上角）",
        "长按底部悬浮栏唤出这一排",
    ),
    _str(
        "ui_touchpad_hint_service_off",
        "服务未启动：手势发不出去。先到「状态」页打开启动开关。",
        "服务未启动 · 先到「状态」页打开开关",
    ),
    _str(
        "ui_touchpad_hint_no_uplink",
        "没有出口：手势发不出去。先到「状态 → 受控与出口」选受控设备，或接上 USB / 蓝牙。",
        "没有出口 · 先选受控设备，或接 USB / 蓝牙",
    ),
    _str(
        "ui_filepanel_text_file_no_local",
        "还没有收到文件。对方（电脑 / 电视 / 另一台手机）在「文件传输」里选这台手机，就能把文件发过来。",
        "还没有收到文件 · 对方在「文件传输」选这台手机即可发送",
    ),
    _str(
        "ui_ball_fn_clipboard_hint",
        "本机复制文字会自动发到受控设备。此处可手动补发当前剪贴板内容。",
        "复制文字自动发送到受控设备 · 也可手动补发",
    ),
    _str(
        "ui_ball_fn_clipboard_nolink",
        "没有受控设备：先到「状态 → 受控与出口」选一台",
        "无受控设备 · 先到「状态 → 受控与出口」选一台",
    ),
    _str(
        "ui_set_orient_lock_sum",
        "把界面钉在当前朝向；锁定后不再随重力自动切页，关闭后横持手机自动切到键盘页",
        "钉在当前朝向，不再随重力切页",
    ),
    _str(
        "ui_set_auto_swap_sum",
        "关闭时页面不再随重力自动切换（默认关）；版式朝向由系统旋转锁定决定",
        "关闭后页面不随重力自动切换（默认关）",
    ),
    _str(
        "ui_set_value_kb_apply_when",
        "重进键盘页或重开触控板后生效（自定义布局在触控板键盘面板内编辑）",
        "重进键盘页或触控板后生效",
    ),
    _str("ui_pick_device_button", "选择受控设备（连上之后触摸板 / 键盘才发得出去）", "选择受控设备"),
    _str(
        "ui_pick_device_scanning",
        "正在搜索局域网设备…（对端的控制服务要开着；1~2 秒内会陆续出现）",
        "搜索局域网设备…（需对端服务已开）",
    ),
    _str("ui_ball_items_title", "悬浮球菜单项（勾选后扇形菜单立即按此重建）", "悬浮球菜单项"),
    _str(
        "ui_ball_item_add_hint",
        "点一下加进扇形菜单（排在末尾，长按可往上挪）",
        "点按加入菜单 · 长按拖动排序",
    ),
    _str("ui_ball_panel_pad_sum", "占屏高百分比；展开后拖面板顶部的把手也能改", "占屏高百分比 · 拖面板顶部把手可调"),
    _str("ui_ball_panel_kb_sum", "占屏高百分比；横屏键位多，建议不低于 60%", "占屏高百分比 · 横屏建议 ≥ 60%"),
    _str("ui_ball_menu_size_sum", "球展开后那一圈的项直径与半径；紧凑更省屏，大档更好点", "球展开后各项直径与半径 · 紧凑更省屏"),
    _str("ui_set_bg_anim_sum", "背景极缓慢地缩放/漂移；纯色底叠一层柔和光晕。关闭后完全静止", "背景缓慢漂移叠柔光 · 关闭后静止"),
    _str(
        "ui_filepanel_where",
        "收到的文件保存在：%1$s\n点「转发」发到目标设备；点「删除」移除本机文件。",
        "收到的文件：%1$s",
    ),
    {
        "id": "U8-色-USB慢档",
        "file": ST,
        "kind": "replace",
        "anchor": (
            "                    else ctx.getString(R.string.hint_usb2),\n"
            "                    fontSize = 12.sp,\n"
            "                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,"
        ),
        "new": (
            "                    else ctx.getString(R.string.hint_usb2),\n"
            "                    fontSize = 12.sp,\n"
            "                    // 颜色代替长句（成宇 2026-11-07「能用颜色代替的用颜色」）：\n"
            "                    // 慢档 = 琥珀警示色，3.0 正常保持灰 —— 不读字也能分清快慢。\n"
            "                    color = if (state.rooted) MiuixTheme.colorScheme.onSurfaceVariantSummary\n"
            "                    else colorResource(R.color.module_state_pending),"
        ),
    },
    {
        "id": "U8-色-触控板警示",
        "file": TPS,
        "kind": "replace",
        "anchor": (
            "                text = shown,\n"
            "                fontSize = 12.sp,\n"
            "                textAlign = TextAlign.Center,\n"
            "                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,"
        ),
        "new": (
            "                text = shown,\n"
            "                fontSize = 12.sp,\n"
            "                textAlign = TextAlign.Center,\n"
            "                // 颜色代替长句（成宇 2026-11-07）：服务没开 / 没出口 = 红色\n"
            "                // 阻断警示，正常手势说明保持灰 —— 不读字也能分辨能不能用。\n"
            "                color = if (serviceOff || noExit)\n"
            "                    androidx.compose.ui.res.colorResource(R.color.module_state_error)\n"
            "                else MiuixTheme.colorScheme.onSurfaceVariantSummary,"
        ),
    },
]


def main() -> int:
    files = {}
    for e in EDITS:
        files.setdefault(e["file"], []).append(e)

    errors = []
    pending = {}
    for path, edits in files.items():
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as ex:
            errors.append("open failed " + path + ": " + str(ex))
            continue

        for e in edits:
            if e["kind"] != "replace":
                errors.append("unknown kind: " + e["id"])
                continue
            if e["new"] in text:
                print("already applied, skip: " + e["id"])
                continue
            n = text.count(e["anchor"])
            if n != 1:
                errors.append(
                    e["id"] + ": anchor count=" + str(n) + " expect=1 file=" + path
                    + "\n---anchor---\n" + e["anchor"][:200]
                )
                continue
            text = text.replace(e["anchor"], e["new"], 1)
        pending[path] = text

    if errors:
        body = "\n\n".join(errors)
        try:
            with open(ERRLOG, "w", encoding="utf-8") as f:
                f.write(body)
        except OSError as ex:
            print("write errlog failed: " + str(ex))
        print("ERRORS:\n" + body)
        return 0

    try:
        os.remove(ERRLOG)
    except OSError:
        pass

    if not pending:
        print("no changes")
        return 0

    changed = []
    write_errors = []
    for path, content in pending.items():
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        except OSError as ex:
            write_errors.append("write failed " + path + ": " + str(ex))
            continue
        changed.append(path)

    if write_errors:
        body = "\n\n".join(write_errors)
        try:
            with open(ERRLOG, "w", encoding="utf-8") as f:
                f.write(body)
        except OSError:
            pass
        print("WRITE_ERRORS:\n" + body)
        return 0

    print("OK: " + ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
