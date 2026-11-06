#!/usr/bin/env python3
"""repo-edit：批 U-5（图标风格统一，成宇 2026-11-07 安卓 UI 第 3 条）。

现状（全仓逐文件搜 + BallConfig 全文）：
  · 悬浮球菜单 7 项 ic_apx_* 全是**实心描边类矢量**（ic_apx_wifi/bt/usb/expand/keyboard/clip/…）
  · 抽屉导入 top.yukonga.miuix.kmp.icon.*（Home/Settings…）是**第三方库图标包** —— 风格混用
  · 页面里 Text / MiuixIcons 与自定义 drawable 混用

方案：不重画 —— 把 7 个 ic_apx_* 全部替换成**同一家族描边矢量**，iconRes 只改引用、
逐行逐项、零布局风险；BallConfig 注释同步说明统一口径。
"""
import sys

F = "android/app/src/main/java/com/allperiph/ui/BallConfig.kt"

EDITS = [
    {
        "id": "U5-1 ic_apx_touchpad → ic_outline_touchpad",
        "file": F,
        "kind": "replace",
        "anchor": "TOUCHPAD -> R.drawable.ic_apx_touchpad",
        "new": "TOUCHPAD -> R.drawable.ic_outline_touchpad",
    },
    {
        "id": "U5-2 ic_apx_devices → ic_outline_devices",
        "file": F,
        "kind": "replace",
        "anchor": "DEVICES -> R.drawable.ic_apx_devices",
        "new": "DEVICES -> R.drawable.ic_outline_devices",
    },
    {
        "id": "U5-3 ic_apx_files → ic_outline_files",
        "file": F,
        "kind": "replace",
        "anchor": "FILES -> R.drawable.ic_apx_files",
        "new": "FILES -> R.drawable.ic_outline_files",
    },
    {
        "id": "U5-4 ic_apx_wifi → ic_outline_wifi",
        "file": F,
        "kind": "replace",
        "anchor": "MODE_WIFI -> R.drawable.ic_apx_wifi",
        "new": "MODE_WIFI -> R.drawable.ic_outline_wifi",
    },
    {
        "id": "U5-5 ic_apx_bt → ic_outline_bt",
        "file": F,
        "kind": "replace",
        "anchor": "MODE_BT -> R.drawable.ic_apx_bt",
        "new": "MODE_BT -> R.drawable.ic_outline_bt",
    },
    {
        "id": "U5-6 ic_apx_usb → ic_outline_usb",
        "file": F,
        "kind": "replace",
        "anchor": "MODE_USB -> R.drawable.ic_apx_usb",
        "new": "MODE_USB -> R.drawable.ic_outline_usb",
    },
    {
        "id": "U5-7 ic_apx_expand → ic_outline_expand",
        "file": F,
        "kind": "replace",
        "anchor": "CONSOLE -> R.drawable.ic_apx_expand",
        "new": "CONSOLE -> R.drawable.ic_outline_expand",
    },
    {
        "id": "U5-8 ic_apx_keyboard → ic_outline_keyboard",
        "file": F,
        "kind": "replace",
        "anchor": "KEYBOARD -> R.drawable.ic_apx_keyboard",
        "new": "KEYBOARD -> R.drawable.ic_outline_keyboard",
    },
    {
        "id": "U5-9 ic_apx_gamepad → ic_outline_gamepad",
        "file": F,
        "kind": "replace",
        "anchor": "GAMEPAD -> R.drawable.ic_apx_gamepad",
        "new": "GAMEPAD -> R.drawable.ic_outline_gamepad",
    },
    {
        "id": "U5-10 ic_apx_clipboard → ic_outline_clipboard",
        "file": F,
        "kind": "replace",
        "anchor": "CLIPBOARD -> R.drawable.ic_apx_clipboard",
        "new": "CLIPBOARD -> R.drawable.ic_outline_clipboard",
    },
    {
        "id": "U5-11 ic_apx_screen → ic_outline_screen",
        "file": F,
        "kind": "replace",
        "anchor": "SCREEN -> R.drawable.ic_apx_screen",
        "new": "SCREEN -> R.drawable.ic_outline_screen",
    },
    {
        "id": "U5-12 ic_apx_power → ic_outline_power",
        "file": F,
        "kind": "replace",
        "anchor": "QUIT -> R.drawable.ic_apx_power",
        "new": "QUIT -> R.drawable.ic_outline_power",
    },
    {
        "id": "U5-13 注释说明统一口径",
        "file": F,
        "kind": "replace",
        "anchor": "两者都不能空，否则菜单展开后是一颗空球（点哪儿都没反应，比不展开还糟）。\n */",
        "new": "两者都不能空，否则菜单展开后是一颗空球（点哪儿都没反应，比不展开还糟）。\n *\n * 图标全部采用 ic_outline_* 描边系列（2026-11-07 成宇「图标风格统一」），与\n * 第三方库图标包（top.yukonga.miuix.kmp.icon.*）分离，避免风格混用。\n */",
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
