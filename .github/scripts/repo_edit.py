#!/usr/bin/env python3
"""repo-edit：批 U-2（显示适配，成宇 2026-11-07 安卓 UI 四项中的 1+2，2026-11-07）。

①全局无极界面缩放（新 UiScalePref：0.80~1.25，SliderPreference steps=0 连续滑条）：
  · 主壳 setContent 供一层 LocalDensity（四页全生效）；
  · ApComponents.DialogCard 弹窗独立组合，必须自己再供一次（否则缩放设了没反应）；
  · 设置页「界面显示」新 Section 拖动即刻生效（Compose 状态，不用 recreate）。
②挖孔/大圆角回避：ApInsets 新增 rounded 字段（API35+ roundedCorners，低版降 0）与
  holeTop/Bottom/Left/Right（**不含系统栏** —— 状态栏仍隐藏，不找回旧空档）：
  · 主壳竖屏 topPad 由 0 改为 holeTop（标题/卡片不进摄像头孔）；
  · DialogCard 内容留白叠加弹窗窗口自己的 hole 四边；
  · 弹窗宽度封顶 fillMaxWidth(0.94f)（横向不再顶到屏边）。

锚点全部经 search_code / get_file_contents 逐字节核准；新增 kind: create（新文件）。
"""
import sys

EDITS = [
    {
        "id": "A1 ApInsets 构造 rounded 字段",
        "file": "android/app/src/main/java/com/allperiph/ui/miuix/ApInsets.kt",
        "kind": "replace",
        "anchor": (
            "data class ApInsets(\n"
            "    val bars: ApEdgeInsets = ApEdgeInsets(),\n"
            "    val cutout: ApEdgeInsets = ApEdgeInsets(),\n"
            "    val imeBottom: Dp = 0.dp,\n"
            ") {"
        ),
        "new": (
            "data class ApInsets(\n"
            "    val bars: ApEdgeInsets = ApEdgeInsets(),\n"
            "    val cutout: ApEdgeInsets = ApEdgeInsets(),\n"
            "    val imeBottom: Dp = 0.dp,\n"
            "    // 2026-11-07（成宇「优化回避屏幕大圆角和摄像头挖孔」）：大圆角是独立 insets\n"
            "    // 类型（API 35+ roundedCorners），与挖孔分开读、内容安全区取并集。\n"
            "    val rounded: ApEdgeInsets = ApEdgeInsets(),\n"
            ") {"
        ),
    },
    {
        "id": "A2 ApInsets hole 访问器",
        "file": "android/app/src/main/java/com/allperiph/ui/miuix/ApInsets.kt",
        "kind": "replace",
        "anchor": "    val right: Dp get() = maxOf(bars.right, cutout.right)",
        "new": (
            "    val right: Dp get() = maxOf(bars.right, cutout.right)\n"
            "\n"
            "    // —— 挖孔/大圆角回避（2026-11-07）——\n"
            "    // 语义：**不含系统栏**。状态栏是 hideSystemBars 刻意隐藏的（内容本来就画到\n"
            "    // 最上沿），这几项只算「孔 + 圆角」，不会把 v223.22 修掉的那条系统栏空档带回来。\n"
            "    val holeTop: Dp get() = maxOf(cutout.top, rounded.top)\n"
            "    val holeBottom: Dp get() = maxOf(cutout.bottom, rounded.bottom)\n"
            "    val holeLeft: Dp get() = maxOf(cutout.left, rounded.left)\n"
            "    val holeRight: Dp get() = maxOf(cutout.right, rounded.right)"
        ),
    },
    {
        "id": "A3 ApInsets convert 读 rounded",
        "file": "android/app/src/main/java/com/allperiph/ui/miuix/ApInsets.kt",
        "kind": "replace",
        "anchor": (
            "            val ime = insets.getInsets(WindowInsets.Type.ime())\n"
            "            return ApInsets("
        ),
        "new": (
            "            val ime = insets.getInsets(WindowInsets.Type.ime())\n"
            "            // 大圆角（roundedCorners 类型需 API 35；工程 targetSdk 35 可编译，\n"
            "            // 跑在 Android 14 及以下的机器走 0 值分支 —— 不垫但也不崩）。\n"
            "            val rounded = if (android.os.Build.VERSION.SDK_INT >= 35) {\n"
            "                insets.getInsets(WindowInsets.Type.roundedCorners())\n"
            "            } else {\n"
            "                android.graphics.Insets.NONE\n"
            "            }\n"
            "            return ApInsets("
        ),
    },
    {
        "id": "A4 ApInsets 构造传 rounded",
        "file": "android/app/src/main/java/com/allperiph/ui/miuix/ApInsets.kt",
        "kind": "replace",
        "anchor": (
            "                imeBottom = pxToDp(ime.bottom),\n"
            "            )"
        ),
        "new": (
            "                imeBottom = pxToDp(ime.bottom),\n"
            "                rounded = ApEdgeInsets(\n"
            "                    pxToDp(rounded.left), pxToDp(rounded.top),\n"
            "                    pxToDp(rounded.right), pxToDp(rounded.bottom),\n"
            "                ),\n"
            "            )"
        ),
    },
    {
        "id": "B1 主壳 onCreate 读回缩放偏好",
        "file": "android/app/src/main/java/com/allperiph/ui/compose/ApShellActivity.kt",
        "kind": "replace",
        "anchor": (
            "        ApAccentSync.apply(this)\n"
            "        applyImmersive()"
        ),
        "new": (
            "        ApAccentSync.apply(this)\n"
            "        applyImmersive()\n"
            "        // 全局界面缩放（2026-11-07）：冷启动在此读回（幂等）。悬浮球等非主壳宿主\n"
            "        // 目前按 100% 渲染，等有反馈再扩到那侧（见 UiScalePref 的 KDoc）。\n"
            "        com.allperiph.ui.UiScalePref.load(this)"
        ),
    },
    {
        "id": "B2 主壳 setContent 供缩放密度",
        "file": "android/app/src/main/java/com/allperiph/ui/compose/ApShellActivity.kt",
        "kind": "replace",
        "anchor": (
            "        setContent {\n"
            "            ApMiuixTheme { Shell() }\n"
            "        }"
        ),
        "new": (
            "        setContent {\n"
            "            // 2026-11-07（成宇「做成可以无极自定义 dpi 缩放」）：整体密度乘全局缩放。\n"
            "            // 这一层只管主壳四页；弹窗是独立组合，在 ApComponents.DialogCard 里\n"
            "            // 自己再供一次 —— 两处都读 UiScalePref.current，拖滑条即刻全端生效。\n"
            "            val __base = androidx.compose.ui.platform.LocalDensity.current\n"
            "            val __scale = com.allperiph.ui.UiScalePref.current.floatValue\n"
            "            androidx.compose.runtime.CompositionLocalProvider(\n"
            "                androidx.compose.ui.platform.LocalDensity provides\n"
            "                    androidx.compose.ui.unit.Density(__base.density * __scale, __base.fontScale),\n"
            "            ) {\n"
            "                ApMiuixTheme { Shell() }\n"
            "            }\n"
            "        }"
        ),
    },
    {
        "id": "B3 主壳竖屏 topPad 改挖孔/圆角让位",
        "file": "android/app/src/main/java/com/allperiph/ui/compose/ApShellActivity.kt",
        "kind": "replace",
        "anchor": "        val topPad = 0.dp",
        "new": (
            "        // 2026-11-07（成宇「优化回避屏幕大圆角和摄像头挖孔，避免遮挡」）：上一段旧\n"
            "        // 注释里「不按边算顶部留白」说的是**系统栏**（仍成立，状态栏照旧隐藏）；本行\n"
            "        // 只按挖孔/大圆角让位 —— 顶部压着摄像头孔时标题与卡片不再画进孔里。横屏\n"
            "        // 孔在左右侧，走 ApAdaptive 的 padCutout 与页内 insets.left/right，这里恒≈0。\n"
            "        val topPad = com.allperiph.ui.miuix.rememberApInsets().holeTop"
        ),
    },
    {
        "id": "C1 DialogCard 供缩放密度",
        "file": "android/app/src/main/java/com/allperiph/ui/miuix/ApComponents.kt",
        "kind": "replace",
        "anchor": "        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {",
        "new": (
            "        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {\n"
            "            // 2026-11-07（成宇「弹窗和编辑页显示不全」）：弹窗是独立窗口、独立组合 ——\n"
            "            // 主壳 setContent 的 LocalDensity 覆盖不到这里，全局缩放必须自己再供一次；\n"
            "            // __ins 读弹窗自己窗口的 insets（挖孔/圆角若与弹窗相交就垫进内容留白）。\n"
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
    },
    {
        "id": "C2 DialogCard 尾部补 provider 闭合",
        "file": "android/app/src/main/java/com/allperiph/ui/miuix/ApComponents.kt",
        "kind": "replace",
        "anchor": (
            "                    content = content,\n"
            "                )\n"
            "            }\n"
            "        }"
        ),
        "new": (
            "                    content = content,\n"
            "                )\n"
            "            }\n"
            "            }\n"
            "        }"
        ),
    },
    {
        "id": "C3 DialogCard 内容留白叠挖孔/圆角",
        "file": "android/app/src/main/java/com/allperiph/ui/miuix/ApComponents.kt",
        "kind": "replace",
        "anchor": (
            "                    modifier = Modifier\n"
            "                        .padding(padding)\n"
            "                        .then("
        ),
        "new": (
            "                    modifier = Modifier\n"
            "                        .padding(\n"
            "                            start = padding + __ins.holeLeft,\n"
            "                            top = padding + __ins.holeTop,\n"
            "                            end = padding + __ins.holeRight,\n"
            "                            bottom = padding + __ins.holeBottom,\n"
            "                        )\n"
            "                        .then("
        ),
    },
    {
        "id": "C4 DialogCard 宽度封顶 94%",
        "file": "android/app/src/main/java/com/allperiph/ui/miuix/ApComponents.kt",
        "kind": "replace",
        "anchor": (
            "            top.yukonga.miuix.kmp.basic.Card(\n"
            "                modifier = Modifier.fillMaxWidth()\n"
            "                    .then(modifier)\n"
            "                    .fillMaxHeight(heightRatio),\n"
            "            ) {"
        ),
        "new": (
            "            top.yukonga.miuix.kmp.basic.Card(\n"
            "                // 2026-11-07：封顶 94% —— 横向不再顶到屏边，缩放调大时也有安全余量。\n"
            "                modifier = Modifier.fillMaxWidth(0.94f)\n"
            "                    .then(modifier)\n"
            "                    .fillMaxHeight(heightRatio),\n"
            "            ) {"
        ),
    },
    {
        "id": "D1 设置页新增界面显示 Section",
        "file": "android/app/src/main/java/com/allperiph/ui/compose/SettingsPage.kt",
        "kind": "replace",
        "anchor": "            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_theme)) {",
        "new": (
            "            // ————————————— 界面缩放（2026-11-07 成宇「无极自定义 dpi 缩放」） —————————————\n"
            "            // steps=0 = 连续滑条（无极）；80%~125%：再小字不可读，再大就失去\n"
            "            // 「多看一截」的意义。拖动即刻生效（UiScalePref.current 是 Compose 状态，\n"
            "            // 主壳与弹窗两处 LocalDensity 都读它，不用 recreate）。\n"
            "            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_ui_scale)) {\n"
            "                SliderPreference(\n"
            "                    value = com.allperiph.ui.UiScalePref.current.floatValue,\n"
            "                    onValueChange = { com.allperiph.ui.UiScalePref.set(ctx, it) },\n"
            "                    title = ctx.getString(R.string.ui_set_label_ui_scale),\n"
            "                    valueText = \"${(com.allperiph.ui.UiScalePref.current.floatValue * 100).toInt()}%\",\n"
            "                    valueRange = com.allperiph.ui.UiScalePref.MIN..com.allperiph.ui.UiScalePref.MAX,\n"
            "                    steps = 0,\n"
            "                )\n"
            "            }\n"
            "\n"
            "            ApComponents.Section(title = ctx.getString(R.string.ui_set_sec_theme)) {"
        ),
    },
    {
        "id": "E1 strings 新增界面显示/界面缩放",
        "file": "android/app/src/main/res/values/strings.xml",
        "kind": "replace",
        "anchor": "    <string name=\"ui_set_sec_theme\">主题数据</string>",
        "new": (
            "    <string name=\"ui_set_sec_ui_scale\">界面显示</string>\n"
            "    <string name=\"ui_set_label_ui_scale\">界面缩放</string>\n"
            "    <string name=\"ui_set_sec_theme\">主题数据</string>"
        ),
    },
    {
        "id": "F1 新建 UiScalePref.kt",
        "file": "android/app/src/main/java/com/allperiph/ui/UiScalePref.kt",
        "kind": "create",
        "marker": "object UiScalePref",
        "new": (
            "package com.allperiph.ui\n"
            "\n"
            "import androidx.compose.runtime.mutableFloatStateOf\n"
            "\n"
            "/**\n"
            " * 全局界面缩放（成宇 2026-11-07「或者做成可以无极自定义 dpi 缩放」）。\n"
            " *\n"
            " * ## 为什么需要它\n"
            " * 此前全仓只有「键帽密度」三档（[com.allperiph.ui.KeyPref]）与设备类型 —— 弹窗 /\n"
            " * 编辑页这类内容密度高的面，元素稍大就横向溢出、纵向装不下。这里存 0.80~1.25 的\n"
            " * 乘数，**两处**把它乘进 LocalDensity：\n"
            " *  · 主壳 `ApShellActivity.setContent`（四页整体）；\n"
            " *  · `ApComponents.DialogCard`（弹窗是独立窗口独立组合，只在主壳供它收不到 ——\n"
            " *    这正是「缩放设了弹窗没反应」的坑）。\n"
            " *\n"
            " * ## 读取口径\n"
            " * [current] 是 Compose 状态：设置页拖滑条即刻重组生效，不用 recreate。\n"
            " * [load] 由 ApShellActivity.onCreate 调一次（幂等）；悬浮球等非主壳宿主\n"
            " * 目前按 100% 渲染，等有反馈再扩。\n"
            " */\n"
            "object UiScalePref {\n"
            "    private const val FILE = \"apx_ui_scale\"\n"
            "    private const val KEY = \"scale\"\n"
            "\n"
            "    /** 下限/上限：再小字就不可读，再大就失去「多看一截」的意义 */\n"
            "    const val MIN = 0.80f\n"
            "    const val MAX = 1.25f\n"
            "\n"
            "    /** 当前缩放（Compose 状态 —— 读它的地方随拖动即时重组） */\n"
            "    val current = mutableFloatStateOf(1f)\n"
            "\n"
            "    /** 冷启动读回（幂等，可重复调）。 */\n"
            "    fun load(ctx: android.content.Context) {\n"
            "        val sp = ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)\n"
            "        current.floatValue = sp.getFloat(KEY, 1f).coerceIn(MIN, MAX)\n"
            "    }\n"
            "\n"
            "    /** 设置页滑条写入：内存态 + 持久化一次到位。 */\n"
            "    fun set(ctx: android.content.Context, value: Float) {\n"
            "        val v = value.coerceIn(MIN, MAX)\n"
            "        current.floatValue = v\n"
            "        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)\n"
            "            .edit().putFloat(KEY, v).apply()\n"
            "    }\n"
            "}\n"
        ),
    },
]


def main() -> int:
    files = {}
    for e in EDITS:
        files.setdefault(e["file"], []).append(e)

    pending = {}
    for path, edits in files.items():
        is_create = all(e["kind"] == "create" for e in edits)
        if is_create:
            try:
                with open(path, encoding="utf-8") as f:
                    text = f.read()
            except OSError:
                text = None
        else:
            try:
                with open(path, encoding="utf-8") as f:
                    text = f.read()
            except OSError as ex:
                print(f"open failed {path}: {ex}")
                return 1

        for e in edits:
            if e["kind"] == "create":
                if text is not None:
                    if e["marker"] in text:
                        print(f"already exists, skip: {e['id']}")
                        continue
                    print(f"VALIDATION FAILED: {e['id']} file exists without marker")
                    return 1
                text = e["new"]
            elif e["kind"] == "replace":
                if e["new"] in text:
                    print(f"already applied, skip: {e['id']}")
                    continue
                n = text.count(e["anchor"])
                if n != 1:
                    print(f"VALIDATION FAILED: {e['id']} anchor count={n} expect=1\n---anchor---\n{e['anchor'][:300]}")
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
