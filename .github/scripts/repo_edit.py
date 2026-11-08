#!/usr/bin/env python3
"""repo-edit：#1 灯效调色（用户 2026-11-08「灯效太黑了，能改成调节颜色吗」）。

  E1 KeyPref 新增 rgbHue/rgbBright 持久化（0..359° / 40..160%）
  E2 RgbTune 全局 state（**只允许绘制阶段读** —— 滑条写、公式读，只重绘不重组）
  E3 rgbFaceAt 接入调色 + 呼吸暗谷 0.30→0.45（「太黑」根因）
  E4 touchLightAt（扩散波光）同样吃调色
  E5 组合期一次性灌入持久值 + tuneOpen 面板开关
  E6 「调色」入口 chip + 色相/亮度面板（全 TabChip/horizontalScroll，零新 import）

公式仍只此一份 —— 遥控图块自动同步；锚点唯一；幂等；累积式（run #97 修正版框架）。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"

KP = "android/app/src/main/java/com/allperiph/ui/KeyPref.kt"
KB = "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt"

EDITS = [
    {
        "id": "E1 KeyPref 调色存取",
        "file": KP,
        "anchor": (
            "    fun setRgb(ctx: Context, i: Int) {\n"
            "        prefs(ctx).edit().putInt(KEY_RGB, i.coerceIn(0, RGB_COUNT - 1)).apply()\n"
            "    }"
        ),
        "new": (
            "    fun setRgb(ctx: Context, i: Int) {\n"
            "        prefs(ctx).edit().putInt(KEY_RGB, i.coerceIn(0, RGB_COUNT - 1)).apply()\n"
            "    }\n"
            "\n"
            "    // ———————————————— 灯效调色（色相 + 亮度） ————————————————\n"
            "    // 用户 2026-11-08「灯效太黑了，能改成调节颜色吗」：色相偏移 0..359°、亮度增益 40..160%。\n"
            "\n"
            "    fun rgbHue(ctx: Context): Int =\n"
            "        prefs(ctx).getInt(\"rgb_hue\", 0).coerceIn(0, 359)\n"
            "\n"
            "    fun setRgbHue(ctx: Context, v: Int) {\n"
            "        prefs(ctx).edit().putInt(\"rgb_hue\", v.coerceIn(0, 359)).apply()\n"
            "    }\n"
            "\n"
            "    fun rgbBright(ctx: Context): Int =\n"
            "        prefs(ctx).getInt(\"rgb_bright\", 100).coerceIn(40, 160)\n"
            "\n"
            "    fun setRgbBright(ctx: Context, v: Int) {\n"
            "        prefs(ctx).edit().putInt(\"rgb_bright\", v.coerceIn(40, 160)).apply()\n"
            "    }"
        ),
    },
    {
        "id": "E2 RgbTune 全局调色 state",
        "file": KB,
        "anchor": "internal class RgbSpec(val mode: Int, val phase: State<Float>?)",
        "new": (
            "/**\n"
            " * 灯效调色（用户 2026-11-08「灯效太黑了，能改成调节颜色」）：色相偏移 0..359° +\n"
            " * 亮度增益 40..160%。全局单例 —— **只允许在绘制阶段读**（rgbFaceAt / touchLightAt\n"
            " * 内部）：调色 chip 按下触发的是键帽重绘，不是 84 键重组，与皮肤审查 #3 同一条红线。\n"
            " */\n"
            "internal object RgbTune {\n"
            "    var hueShift by mutableIntStateOf(0)\n"
            "    var bright by mutableIntStateOf(100)\n"
            "}\n"
            "\n"
            "internal class RgbSpec(val mode: Int, val phase: State<Float>?)"
        ),
    },
    {
        "id": "E3 rgbFaceAt 调色 + 呼吸提亮",
        "file": KB,
        "anchor": (
            "    // b33：呼吸暗谷 0.30 起（全灭会整副键盘消失）；呼吸档按横坐标错相成彩虹\n"
            "    val h = phase + x * (if (breathe) 0.35f else 0.6f)\n"
            "    val v = if (breathe) 0.30f + 0.65f * env else 0.95f\n"
            "    return hsvColor(h, 0.97f, (v * vScale).coerceAtLeast(0f))"
        ),
        "new": (
            "    // b33：呼吸暗谷曾 0.30（成宇 2026-11-08「灯效太黑了」→ 提到 0.45 起步）；\n"
            "    // 呼吸档按横坐标错相成彩虹。h/v 再叠调色（RgbTune：色相偏移 + 亮度增益，\n"
            "    // 调色 chip 写、这里在绘制阶段读 —— 只重绘不重组）。\n"
            "    val h = phase + x * (if (breathe) 0.35f else 0.6f) + RgbTune.hueShift\n"
            "    val v = (if (breathe) 0.45f + 0.50f * env else 0.95f) * (RgbTune.bright / 100f)\n"
            "    return hsvColor(h, 0.97f, (v * vScale).coerceAtLeast(0f))"
        ),
    },
    {
        "id": "E4 扩散波光吃调色",
        "file": KB,
        "anchor": (
            "    if (best <= 0f) return Color.Transparent\n"
            "    return hsvColor(hue, 0.97f, 0.06f + 0.89f * best.coerceAtMost(1f))"
        ),
        "new": (
            "    if (best <= 0f) return Color.Transparent\n"
            "    // 扩散波光同样吃调色（色相偏移 + 亮度增益，绘制期读 —— 与键面同口径）\n"
            "    val vv = (0.06f + 0.89f * best.coerceAtMost(1f)) * (RgbTune.bright / 100f)\n"
            "    return hsvColor(hue + RgbTune.hueShift, 0.97f, vv.coerceIn(0f, 1f))"
        ),
    },
    {
        "id": "E5 组合期灌值 + 面板开关",
        "file": KB,
        "anchor": "    var rgbMode by remember { mutableIntStateOf(KeyPref.rgbIndex(ctx)) }",
        "new": (
            "    var rgbMode by remember { mutableIntStateOf(KeyPref.rgbIndex(ctx)) }\n"
            "    // 调色：组合期一次性从持久层灌入（此后 RgbTune 只被绘制阶段读）；面板开关\n"
            "    remember { RgbTune.hueShift = KeyPref.rgbHue(ctx); RgbTune.bright = KeyPref.rgbBright(ctx) }\n"
            "    var tuneOpen by remember { mutableStateOf(false) }"
        ),
    },
    {
        "id": "E6 调色入口 chip",
        "file": KB,
        "anchor": (
            "                TabChip(\n"
            "                    label = KeyPref.rgbLabels(ctx)[rgbMode],\n"
            "                    selected = rgbMode != KeyPref.RGB_OFF,\n"
            "                    onClick = {\n"
            "                        rgbMode = (rgbMode + 1) % KeyPref.RGB_COUNT\n"
            "                        KeyPref.setRgb(ctx, rgbMode)\n"
            "                    },\n"
            "                )"
        ),
        "new": (
            "                TabChip(\n"
            "                    label = KeyPref.rgbLabels(ctx)[rgbMode],\n"
            "                    selected = rgbMode != KeyPref.RGB_OFF,\n"
            "                    onClick = {\n"
            "                        rgbMode = (rgbMode + 1) % KeyPref.RGB_COUNT\n"
            "                        KeyPref.setRgb(ctx, rgbMode)\n"
            "                    },\n"
            "                )\n"
            "                // 调色入口（灯效开着才给；点开 = 展开下方色相/亮度面板）\n"
            "                if (rgbMode != KeyPref.RGB_OFF) {\n"
            "                    TabChip(label = \"调色\", selected = tuneOpen, onClick = { tuneOpen = !tuneOpen })\n"
            "                }"
        ),
    },
    {
        "id": "E7 调色面板",
        "file": KB,
        "anchor": "                // —— 第 3 行：当前页签键位",
        "new": (
            "                // —— 调色面板：色相名直选 + 亮度三档（全 TabChip，可横滑；写 RgbTune 即时生效）\n"
            "                if (tuneOpen && rgbMode != KeyPref.RGB_OFF) {\n"
            "                    Row(\n"
            "                        Modifier\n"
            "                            .fillMaxWidth()\n"
            "                            .horizontalScroll(rememberScrollState())\n"
            "                            .padding(horizontal = 10.dp, vertical = 6.dp),\n"
            "                        horizontalArrangement = Arrangement.spacedBy(6.dp),\n"
            "                    ) {\n"
            "                        listOf(\n"
            "                            \"原\" to 0, \"红\" to 340, \"橙\" to 30, \"黄\" to 55,\n"
            "                            \"绿\" to 140, \"青\" to 190, \"蓝\" to 240, \"紫\" to 285,\n"
            "                        ).forEach { (label, hue) ->\n"
            "                            TabChip(\n"
            "                                label = label,\n"
            "                                selected = RgbTune.hueShift == hue,\n"
            "                                onClick = {\n"
            "                                    RgbTune.hueShift = hue\n"
            "                                    KeyPref.setRgbHue(ctx, hue)\n"
            "                                },\n"
            "                            )\n"
            "                        }\n"
            "                        Spacer(Modifier.padding(horizontal = 4.dp))\n"
            "                        listOf(\"暗\" to 60, \"中\" to 100, \"亮\" to 140).forEach { (label, b) ->\n"
            "                            TabChip(\n"
            "                                label = label,\n"
            "                                selected = RgbTune.bright == b,\n"
            "                                onClick = {\n"
            "                                    RgbTune.bright = b\n"
            "                                    KeyPref.setRgbBright(ctx, b)\n"
            "                                },\n"
            "                            )\n"
            "                        }\n"
            "                    }\n"
            "                }\n"
            "                // —— 第 3 行：当前页签键位"
        ),
    },
]


def main() -> int:
    errors = []
    pending = {}
    for e in EDITS:
        path = e["file"]
        base = pending.get(path)
        if base is None:
            try:
                with open(path, encoding="utf-8") as f:
                    base = f.read()
            except OSError as ex:
                errors.append("open failed " + path + ": " + str(ex))
                continue
        if e["new"] in base:
            print("already applied, skip: " + e["id"])
            continue
        n = base.count(e["anchor"])
        if n != 1:
            errors.append(
                e["id"] + ": anchor count=" + str(n) + " expect=1 file=" + path
                + "\n---anchor---\n" + e["anchor"][:200]
            )
            continue
        pending[path] = base.replace(e["anchor"], e["new"], 1)

    if errors:
        body = "\n\n".join(errors)
        try:
            with open(ERRLOG, "w", encoding="utf-8") as f:
                f.write(body)
        except OSError as ex:
            print("write errlog failed: " + str(ex))
        print("ERRORS:\n" + body)
        return 1

    try:
        os.remove(ERRLOG)
    except OSError:
        pass

    if not pending:
        print("no changes")
        return 0

    changed = []
    for path, content in pending.items():
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            changed.append(path)
        except OSError as ex:
            print("write failed " + path + ": " + str(ex))
            return 1

    print("OK: " + ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
