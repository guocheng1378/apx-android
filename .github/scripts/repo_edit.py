#!/usr/bin/env python3
"""repo-edit：批 U-7（AOSP 系统栏规范 + 挖孔圆角语义修正 + dpi 滑条跟手/平滑 +
编辑控件降级，成宇 2026-11-07 深化反馈）。

需求原文（核心四条）：
  1.「需要根据安卓 aosp 规范适配，而不是直接隐藏状态栏和手势小白条」
  2.「屏幕圆角和摄像头挖孔优化，不是直接上部分不显示内容」
  3.「dpi 缩放滑动条没有跟手，而且 dpi 变一下 ui 跳动一下，需要优化」
  4.「弹窗和横竖屏内容排版…内容显示不全，并且没有跟随 dpi 调整变化；编辑类控件
     （排序、删除、添加）应降低优先级，先把编辑内容显示完整，再放这些控件」

实现（锚点全部取自 master 权威全文）：
  · hideSystemBars() → applyEdgeToEdge()：show(systemBars) + BEHAVIOR_DEFAULT，
    9 处引用（7 子页 + 主壳）同批改名；
  · holeX 只算孔不算圆角（rounded 半径垫进 top 就是「上部分不显示内容」的根因）；
    主壳 topPad 改 readApInsets().top（含状态栏，AOSP 垫边口径）；
  · 主壳与弹窗两处 LocalDensity 缩放改 animateFloatAsState(150ms) —— 不再跳变；
  · 缩放滑条自身按 100% 渲染（除以当前缩放）—— 不变形、严格跟手；
  · 模板管理四个编辑按钮 ActionButton → NarrowButton（随文字宽，降优先级）。
"""
import sys

INSETS = "android/app/src/main/java/com/allperiph/ui/miuix/ApInsets.kt"
SHELL = "android/app/src/main/java/com/allperiph/ui/compose/ApShellActivity.kt"
COMP = "android/app/src/main/java/com/allperiph/ui/miuix/ApComponents.kt"
SET = "android/app/src/main/java/com/allperiph/ui/compose/SettingsPage.kt"
SUB = "android/app/src/main/java/com/allperiph"

SUB_PAGES = [
    (f"{SUB}/ui/RemoteActivity.kt"),
    (f"{SUB}/touchpad/TouchpadActivity.kt"),
    (f"{SUB}/screen/ScreenActivity.kt"),
    (f"{SUB}/ui/GamepadActivity.kt"),
    (f"{SUB}/controlled/RemoteInputActivity.kt"),
    (f"{SUB}/localsend/SendActivity.kt"),
    (f"{SUB}/ui/FilePanelActivity.kt"),
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
        "id": "U7-2 hideSystemBars 定义 → applyEdgeToEdge(show)",
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
        "id": "U7-3 hole 注释 + holeTop 去 rounded",
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
            "    // 系统栏显示后，状态栏高度已天然盖住孔区，这里只作弹窗等窗口的兜底。\n"
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

# ———————— 7 子页改名（import + call 各一锚，每文件内均唯一） ————————
for p in SUB_PAGES:
    EDITS.append({
        "id": f"U7-import {p.rsplit('/', 1)[-1]}",
        "file": p,
        "kind": "replace",
        "anchor": "import com.allperiph.ui.miuix.hideSystemBars",
        "new": "import com.allperiph.ui.miuix.applyEdgeToEdge",
    })
    EDITS.append({
        "id": f"U7-call {p.rsplit('/', 1)[-1]}",
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
        "id": "U7-shell topPad 改含状态栏口径",
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
        "id": "U7-shell 缩放平滑动画",
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
    # ———————— ApComponents.kt：弹窗缩放同款平滑 ————————
    {
        "id": "U7-dialog 缩放平滑动画",
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
        "id": "U7-滑条固定 density（跟手）",
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
        "id": "U7-模板按钮降级：应用",
        "file": SET,
        "kind": "replace",
        "anchor": (
            "                        ApComponents.ActionButton(\n"
            "                            text = \"应用「${t.name}」\",""
        ),
        "new": (
            "                        ApComponents.NarrowButton(\n"
            "                            text = \"应用「${t.name}」\",""
        ),
    },
    {
        "id": "U7-模板按钮降级：上移",
        "file": SET,
        "kind": "replace",
        "anchor": (
            "                        ApComponents.ActionButton(\n"
            "                            text = \"「${t.name}」上移\",""
        ),
        "new": (
            "                        ApComponents.NarrowButton(\n"
            "                            text = \"「${t.name}」上移\",""
        ),
    },
    {
        "id": "U7-模板按钮降级：删除",
        "file": SET,
        "kind": "replace",
        "anchor": (
            "                        ApComponents.ActionButton(\n"
            "                            text = \"删除「${t.name}」\",""
        ),
        "new": (
            "                        ApComponents.NarrowButton(\n"
            "                            text = \"删除「${t.name}」\",""
        ),
    },
    {
        "id": "U7-模板按钮降级：新建",
        "file": SET,
        "kind": "replace",
        "anchor": (
            "                    ApComponents.ActionButton(\n"
            "                        text = \"从内置模板新建一份可改的\",""
        ),
        "new": (
            "                    ApComponents.NarrowButton(\n"
            "                        text = \"从内置模板新建一份可改的\",""
        ),
    },
]


def main() -> int:
    files = {}
    for e in EDITS:
        files.setdefault(e["file"], []).append(e)

    pending = {}
    for path, edits in files.items():
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as ex:
            print(f"open failed {path}: {ex}")
            return 1

        for e in edits:
            if e["kind"] == "replace":
                if e["new"] in text:
                    print(f"already applied, skip: {e['id']}")
                    continue
                n = text.count(e["anchor"])
                if n != 1:
                    print(f"VALIDATION FAILED: {e['id']} anchor count={n} expect=1\n---anchor---\n{e['anchor'][:200]}")
                    return 1
                text = text.replace(e["anchor"], e["new"], 1)
            else:
                print(f"VALIDATION FAILED: unknown kind {e['kind']}")
                return 1
        pending[path] = text

    if not pending:
        print("no changes")
        return 0
    changed = []
    for path, content in pending.items():
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        except OSError as ex:
            print(f"write failed {path}: {ex}")
            return 1
        changed.append(path)
    print("OK: " + ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
