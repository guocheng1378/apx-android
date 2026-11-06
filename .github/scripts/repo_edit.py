#!/usr/bin/env python3
"""repo-edit：批 U-4（悬浮栏滑动收球/展开，成宇 2026-11-07 安卓 UI 第 4 条）。

需求原文：「下方悬浮栏需要加上滑动选择功能。上滑屏幕时自动收起悬浮栏至球状，
下滑屏幕或者点击收起的悬浮栏自动展开。减少元素遮挡。」

实现（全部锚点取自 master 权威全文 ApShellActivity.kt）：
  · Shell 声明区新增 barCollapsed Compose 状态；
  · 根 Box 挂 pointerInput(page) 单指竖滑**观察**手势（Initial pass、不消费）：
    上滑收成球 / 下滑展开；96dp 阈值 + 竖≥2×横 + 单指限定（双指滚动不触发）；
    触控板页豁免（那页单指滑动是移光标），键盘页不豁免（离散点按够不到阈值）；
  · 低栏内容在胶囊/48dp 小球间切换：小球补 26dp 底距（对齐低栏组件自管的离底高度）
    与横屏 20dp 侧距，点按展开；低栏长按（Initial pass 监视）在收起态仍可唤快捷入口。

不动布局结构：不包 if/else 外层、不重排原缩进 —— 只切低栏 Box 的内容与尾部闭合。
"""
import sys

F = "android/app/src/main/java/com/allperiph/ui/compose/ApShellActivity.kt"

EDITS = [
    {
        "id": "U4-1 import size",
        "file": F,
        "kind": "append_after",
        "marker": "import androidx.compose.foundation.layout.size",
        "anchor": "import androidx.compose.foundation.layout.widthIn",
        "insert": "\nimport androidx.compose.foundation.layout.size",
    },
    {
        "id": "U4-2 barCollapsed 状态声明",
        "file": F,
        "kind": "replace",
        "anchor": "        var quickActionsOpen by remember { mutableStateOf(false) }",
        "new": (
            "        var quickActionsOpen by remember { mutableStateOf(false) }\n"
            "        // 悬浮栏收/展状态（成宇 2026-11-07 第 4 条「上滑收起成球、下滑或点球展开」）。\n"
            "        // 根 Box 的单指观察手势写它；低栏内容按它在胶囊/小球之间切换。\n"
            "        var barCollapsed by remember { mutableStateOf(false) }"
        ),
    },
    {
        "id": "U4-3 根 Box 单指竖滑观察手势",
        "file": F,
        "kind": "replace",
        "anchor": "                .then(rememberApBackdrop(this@ApShellActivity, shellBg)),",
        "new": (
            "                .then(rememberApBackdrop(this@ApShellActivity, shellBg))\n"
            "                // 单指上下滑收/展悬浮栏（成宇 2026-11-07 第 4 条）：上滑→收成球、下滑→展开。\n"
            "                // · Initial pass 只观察不消费：页面滚动、按钮点击、低栏长按全部照常；\n"
            "                // · 96dp 竖向位移且竖 ≥ 2×横 且全程单指才触发 —— 点按/斜滑/双指滚动不会误收；\n"
            "                // · 触控板页（page==1）豁免：那页单指滑动是移光标，收栏会跟光标乱跳；\n"
            "                //   键盘页不豁免 —— 打字是离散点按，够不到 96dp 的竖向阈值。\n"
            "                .pointerInput(page) {\n"
            "                    if (page != 1) {\n"
            "                        awaitEachGesture {\n"
            "                            val down = awaitFirstDown(\n"
            "                                requireUnconsumed = false,\n"
            "                                pass = PointerEventPass.Initial,\n"
            "                            )\n"
            "                            val sx = down.position.x\n"
            "                            val sy = down.position.y\n"
            "                            var fired = false\n"
            "                            while (true) {\n"
            "                                val ev = awaitPointerEvent(PointerEventPass.Initial)\n"
            "                                if (ev.changes.isEmpty()) break\n"
            "                                val c = ev.changes.firstOrNull { it.id == down.id }\n"
            "                                    ?: ev.changes.first()\n"
            "                                if (!c.pressed) break\n"
            "                                if (fired) continue\n"
            "                                val dy = c.position.y - sy\n"
            "                                val dx = c.position.x - sx\n"
            "                                if (ev.changes.count { it.pressed } == 1 &&\n"
            "                                    kotlin.math.abs(dy) > 96f * density &&\n"
            "                                    kotlin.math.abs(dy) > 2f * kotlin.math.abs(dx)\n"
            "                                ) {\n"
            "                                    fired = true\n"
            "                                    barCollapsed = dy < 0\n"
            "                                }\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                },"
        ),
    },
    {
        "id": "U4-4 低栏内容：胶囊/小球切换（开）",
        "file": F,
        "kind": "replace",
        "anchor": (
            "                FloatingNavigationBar(\n"
            "                    horizontalAlignment = barHorizontal,"
        ),
        "new": (
            "                if (barCollapsed) {\n"
            "                    // 收起态：一枚 48dp 小球顶在原角位（成宇 2026-11-07 第 4 条），点按展开。\n"
            "                    // 竖屏补 26dp 底距 = 低栏组件自管的离底高度；横屏补 20dp 侧距与低栏\n"
            "                    // End 侧内留白对位（外层 Box 的横屏 top 8dp 已由父级给出，这里不重复）。\n"
            "                    Box(\n"
            "                        modifier = Modifier\n"
            "                            .then(\n"
            "                                if (landscape) Modifier.padding(end = 20.dp)\n"
            "                                else Modifier.padding(bottom = 26.dp),\n"
            "                            )\n"
            "                            .size(48.dp)\n"
            "                            .clip(androidx.compose.foundation.shape.CircleShape)\n"
            "                            .background(MiuixTheme.colorScheme.surfaceContainer)\n"
            "                            .clickable { barCollapsed = false },\n"
            "                        contentAlignment = Alignment.Center,\n"
            "                    ) {\n"
            "                        Text(text = \"\\u2261\", fontSize = 17.sp, color = MiuixTheme.colorScheme.onSurface)\n"
            "                    }\n"
            "                } else {\n"
            "                // （else 分支内容沿用原缩进：收/展只切低栏 Box 的内容，不动布局结构）\n"
            "                FloatingNavigationBar(\n"
            "                    horizontalAlignment = barHorizontal,"
        ),
    },
    {
        "id": "U4-5 低栏内容：胶囊/小球切换（闭合）",
        "file": F,
        "kind": "replace",
        "anchor": (
            "                        enabled = true,\n"
            "                    )\n"
            "                }\n"
            "            }"
        ),
        "new": (
            "                        enabled = true,\n"
            "                    )\n"
            "                }\n"
            "                }\n"
            "            }"
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
                    print(f"VALIDATION FAILED: {e['id']} anchor count={n} expect=1\n---anchor---\n{e['anchor'][:300]}")
                    return 1
                text = text.replace(e["anchor"], e["new"], 1)
            elif e["kind"] == "append_after":
                if e["marker"] in text:
                    print(f"already applied, skip: {e['id']}")
                    continue
                n = text.count(e["anchor"])
                if n != 1:
                    print(f"VALIDATION FAILED: {e['id']} anchor count={n} expect=1\n---anchor---\n{e['anchor'][:300]}")
                    return 1
                text = text.replace(e["anchor"], e["anchor"] + e["insert"], 1)
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
