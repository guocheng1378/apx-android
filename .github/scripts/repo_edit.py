#!/usr/bin/env python3
"""repo-edit：批 U-3（文字排版口径统一，成宇 2026-11-07「过长内容换行/滚动，同类控件对齐」）。

诊断：全仓 `TextOverflow` 0 命中、Compose 侧无任何 maxLines —— miuix Text 默认
     overflow=Clip、maxLines=MAX_VALUE：长文本要么无限换行把行高撑爆、要么尾部
     取值把左列标题挤断，呈现成「乱七八糟」。

Kit 级排版口径（ApComponents 一处改，全页面随 Kit 生效）：
  · 标题（ItemRow/ClickRow/FoldGroup）：最多 2 行，超出省略号；
  · 摘要/计数：最多 3 行（1 行），超出省略号 —— 再长靠页面/弹窗已有的竖向滚动看全；
  · 尾部取值（ValueRow）：右对齐、最多 2 行、横向封顶 46% 屏宽（不再挤断左列）；
  · 胶囊/页签/长按拖柄/折叠箭头：单行省略（按钮类维持随文字宽）；
  · 同类尾部辅助文字字号统一 13sp（≡ 手柄 14→13，与取值/箭头对齐）。

miuix Text 签名已对同源开源副本逐参核对（maxLines/overflow/textAlign 均存在，
默认 overflow=Clip、maxLines=Int.MAX_VALUE）。锚点全部取自 master 最新 get_file 全文。
"""
import sys

F = "android/app/src/main/java/com/allperiph/ui/miuix/ApComponents.kt"

EDITS = [
    {
        "id": "U3-1 import widthIn",
        "file": F,
        "kind": "append_after",
        "marker": "import androidx.compose.foundation.layout.widthIn",
        "anchor": "import androidx.compose.foundation.layout.heightIn",
        "insert": "\nimport androidx.compose.foundation.layout.widthIn",
    },
    {
        "id": "U3-2 import TextOverflow",
        "file": F,
        "kind": "append_after",
        "marker": "import androidx.compose.ui.text.style.TextOverflow",
        "anchor": "import androidx.compose.ui.text.style.TextAlign",
        "insert": "\nimport androidx.compose.ui.text.style.TextOverflow",
    },
    {
        "id": "U3-3 ItemRow 标题2行/摘要3行",
        "file": F,
        "kind": "replace",
        "anchor": (
            "                Column(modifier = Modifier.weight(1f)) {\n"
            "                    Text(text = title, fontSize = 15.sp)\n"
            "                    if (!summary.isNullOrBlank()) {\n"
            "                        Text(\n"
            "                            text = summary,\n"
            "                            fontSize = 12.sp,\n"
            "                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,\n"
            "                        )\n"
            "                    }\n"
            "                }\n"
            "                trailing?.invoke()\n"
            "            }\n"
            "            if (showDivider) HorizontalDivider()"
        ),
        "new": (
            "                Column(modifier = Modifier.weight(1f)) {\n"
            "                    // 排版口径（2026-11-07）：标题最多 2 行、摘要最多 3 行，超出省略 ——\n"
            "                    // 再长靠页面/弹窗的竖向滚动看全，不把整行高度撑爆。\n"
            "                    Text(text = title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)\n"
            "                    if (!summary.isNullOrBlank()) {\n"
            "                        Text(\n"
            "                            text = summary,\n"
            "                            fontSize = 12.sp,\n"
            "                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,\n"
            "                            maxLines = 3,\n"
            "                            overflow = TextOverflow.Ellipsis,\n"
            "                        )\n"
            "                    }\n"
            "                }\n"
            "                trailing?.invoke()\n"
            "            }\n"
            "            if (showDivider) HorizontalDivider()"
        ),
    },
    {
        "id": "U3-4 ClickRow 标题2行/摘要3行",
        "file": F,
        "kind": "replace",
        "anchor": (
            "            Column(modifier = Modifier.weight(1f)) {\n"
            "                Text(text = title, fontSize = 15.sp)\n"
            "                if (!summary.isNullOrBlank()) {\n"
            "                    Text(\n"
            "                        text = summary,\n"
            "                        fontSize = 12.sp,\n"
            "                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,\n"
            "                    )\n"
            "                }\n"
            "            }\n"
            "            trailing?.invoke()\n"
            "            if (onLongClick != null) {"
        ),
        "new": (
            "            Column(modifier = Modifier.weight(1f)) {\n"
            "                // 排版口径（2026-11-07）：与 ItemRow 同规格 —— 标题≤2 行、摘要≤3 行。\n"
            "                Text(text = title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)\n"
            "                if (!summary.isNullOrBlank()) {\n"
            "                    Text(\n"
            "                        text = summary,\n"
            "                        fontSize = 12.sp,\n"
            "                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,\n"
            "                        maxLines = 3,\n"
            "                        overflow = TextOverflow.Ellipsis,\n"
            "                    )\n"
            "                }\n"
            "            }\n"
            "            trailing?.invoke()\n"
            "            if (onLongClick != null) {"
        ),
    },
    {
        "id": "U3-5 ClickRow 长按手柄字号统一13sp/单行",
        "file": F,
        "kind": "replace",
        "anchor": (
            "                Text(\n"
            "                    text = \"\\u2261\",\n"
            "                    fontSize = 14.sp,\n"
            "                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,\n"
            "                )"
        ),
        "new": (
            "                Text(\n"
            "                    text = \"\\u2261\",\n"
            "                    // 同类尾部辅助文字统一 13sp（与取值行/折叠箭头对齐口径一致）\n"
            "                    fontSize = 13.sp,\n"
            "                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,\n"
            "                    maxLines = 1,\n"
            "                    overflow = TextOverflow.Ellipsis,\n"
            "                )"
        ),
    },
    {
        "id": "U3-6 FoldGroup 标题/计数限行",
        "file": F,
        "kind": "replace",
        "anchor": (
            "                Column(modifier = Modifier.weight(1f)) {\n"
            "                    Text(text = title, fontSize = 15.sp)\n"
            "                    Text(\n"
            "                        text = countText,\n"
            "                        fontSize = 12.sp,\n"
            "                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,\n"
            "                    )\n"
            "                }"
        ),
        "new": (
            "                Column(modifier = Modifier.weight(1f)) {\n"
            "                    // 排版口径（2026-11-07）：标题≤2 行；计数回显恒单行。\n"
            "                    Text(text = title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)\n"
            "                    Text(\n"
            "                        text = countText,\n"
            "                        fontSize = 12.sp,\n"
            "                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,\n"
            "                        maxLines = 1,\n"
            "                        overflow = TextOverflow.Ellipsis,\n"
            "                    )\n"
            "                }"
        ),
    },
    {
        "id": "U3-7 ValueRow 尾值右对齐/2行/半屏封顶",
        "file": F,
        "kind": "replace",
        "anchor": (
            "            trailing = {\n"
            "                Text(\n"
            "                    text = value,\n"
            "                    fontSize = 13.sp,\n"
            "                    color = MiuixTheme.colorScheme.onSurfaceSecondary,\n"
            "                )\n"
            "            },"
        ),
        "new": (
            "            trailing = {\n"
            "                Text(\n"
            "                    text = value,\n"
            "                    fontSize = 13.sp,\n"
            "                    color = MiuixTheme.colorScheme.onSurfaceSecondary,\n"
            "                    // 排版口径（2026-11-07 成宇「同类控件文字需要对齐」）：\n"
            "                    // 尾值右对齐、最多 2 行、横向上限约半屏 —— 长地址/端口/路径\n"
            "                    // 不再把左列标题挤断，且换行后与右缘对齐不再参差。\n"
            "                    textAlign = TextAlign.End,\n"
            "                    maxLines = 2,\n"
            "                    overflow = TextOverflow.Ellipsis,\n"
            "                    modifier = Modifier.widthIn(\n"
            "                        max = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.46f).dp,\n"
            "                    ),\n"
            "                )\n"
            "            },"
        ),
    },
    {
        "id": "U3-8 Pill 单行省略",
        "file": F,
        "kind": "replace",
        "anchor": "            Text(text = text, fontSize = 12.sp, color = color)",
        "new": "            Text(text = text, fontSize = 12.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)",
    },
    {
        "id": "U3-9 TabChip 单行省略",
        "file": F,
        "kind": "replace",
        "anchor": (
            "            Text(\n"
            "                text = text,\n"
            "                fontSize = 14.sp,\n"
            "                color = if (active) tint else MiuixTheme.colorScheme.onSurfaceSecondary,\n"
            "                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,\n"
            "            )"
        ),
        "new": (
            "            Text(\n"
            "                text = text,\n"
            "                fontSize = 14.sp,\n"
            "                color = if (active) tint else MiuixTheme.colorScheme.onSurfaceSecondary,\n"
            "                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,\n"
            "                // 页签/芯片恒单行：过长出省略号，不把页签行顶出屏外\n"
            "                maxLines = 1,\n"
            "                overflow = TextOverflow.Ellipsis,\n"
            "            )"
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
