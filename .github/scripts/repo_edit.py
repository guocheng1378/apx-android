#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 P-2（PC 单实例锁）：desktop_main.cpp 1 处。
根因：web 面板路径全库无单实例保护，双击两次/自启+手动各一次 =
两个一模一样的窗口 + 两个托盘（2026-11-05 用户报）。
修法：main() 顶部（业务代码之前、仅 _WIN32）CreateMutex 抢锁，
抢不到 → FindWindow 唤起已有主窗 → return 0，9511/托盘/WebView2/悬浮球
一个都不建。锁名对齐老面板 Local\AllPeriph.Panel.SingleInstance 语义。
两阶段：①按期望次数全量校验；②全过才写盘。结构与批 O-1 v4 / P-1 一致。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("pc/host/src/desktop_main.cpp", [
        (T(
            "#endif",
            "    try {",
            "        return runDesktopMain();",
        ), T(
            "#endif",
            "",
            "#if defined(_WIN32)",
            "    // 单实例锁（对齐老面板 Local\\AllPeriph.Panel.SingleInstance 语义，见 panel_win32.cpp）。",
            "    // web 面板路径此前没有任何单实例保护 —— 双击两次 / 自启+手动各拉一次，就是",
            "    // 两个一模一样的窗口 + 两个托盘（2026-11-05 用户报「装完有两个一样的界面」）。",
            "    // 抢不到锁就唤起已有实例的主窗然后退出，9511/托盘/WebView2/悬浮球一个都不建。",
            "    // 类名/标题必须与 web_panel.cpp 的 kClassName/kWindowTitle 保持一致。",
            "    if (::CreateMutexW(nullptr, FALSE, L\"Local\\\\AllPeriph.WebPanel.SingleInstance\") &&",
            "        ::GetLastError() == ERROR_ALREADY_EXISTS) {",
            "        const HWND exist = ::FindWindowW(L\"AllPeriphWebPanel\", L\"全能外设\");",
            "        if (exist) {",
            "            ::ShowWindow(exist, SW_SHOW);",
            "            ::SetForegroundWindow(exist);",
            "        }",
            "        return 0;",
            "    }",
            "#endif",
            "",
            "    try {",
            "        return runDesktopMain();",
        ), 1),
    ]),
]


def main() -> int:
    loaded = {}
    errors = []
    for path, entries in EDITS:
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as e:
            errors.append(f"{path}: open failed: {e}")
            continue
        loaded[path] = (text, entries)
        for i, (old, _new, expected) in enumerate(entries, 1):
            n = text.count(old)
            if n != expected:
                errors.append(f"{path} edit#{i}: count={n} expect={expected}\n---anchor---\n{old[:300]}")
    if errors:
        print("VALIDATION FAILED：")
        for e in errors:
            print(e)
        return 1
    for path, (text, entries) in loaded.items():
        for old, new, _expected in entries:
            text = text.replace(old, new)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"OK {path}: {len(entries)} edit(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
