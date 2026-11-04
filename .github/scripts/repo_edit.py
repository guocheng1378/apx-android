#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 P-1（PC 桌面悬浮球）：4 文件 6 处。
web_panel.cpp×2（include + 球创建接入）+ CMakeLists×1（登记新源文件）
+ app_config.hpp×1 + app_config.cpp×2（enableBall 载入/保存）。
两阶段：①按期望次数全量校验；②全过才写盘。结构与批 O-1 v4 一致。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("pc/host/src/config/app_config.cpp", [
        (T(
            "    cfg.enableTray      = bol(\"enableTray\", true);",
        ), T(
            "    cfg.enableTray      = bol(\"enableTray\", true);",
            "    cfg.enableBall      = bol(\"enableBall\", true);",
        ), 1),
        (T(
            "    j[\"enableTray\"]      = cfg.enableTray;",
        ), T(
            "    j[\"enableTray\"]      = cfg.enableTray;",
            "    j[\"enableBall\"]      = cfg.enableBall;",
        ), 1),
    ]),
    ("pc/host/include/apxpc/config/app_config.hpp", [
        (T(
            "    bool     enableTray = true;",
        ), T(
            "    bool     enableTray = true;",
            "    bool     enableBall = true;    // 桌面悬浮球（仅 Win；config.json 手改 false 即不创建）",
        ), 1),
    ]),
    ("pc/host/CMakeLists.txt", [
        (T(
            "      \"${APXPC_ROOT}/src/ui/settings_win32.cpp\"",
            "    )",
        ), T(
            "      \"${APXPC_ROOT}/src/ui/settings_win32.cpp\"",
            "      \"${APXPC_ROOT}/src/ui/ball_win32.cpp\"",
            "    )",
        ), 1),
    ]),
    ("pc/host/src/ui/web_panel.cpp", [
        (T(
            "#include \"apxpc/ui/web_panel.hpp\"",
            "#include \"apxpc/ui/webview2_runtime.hpp\"",
        ), T(
            "#include \"apxpc/ui/ball_win32.hpp\"",
            "#include \"apxpc/ui/web_panel.hpp\"",
            "#include \"apxpc/ui/webview2_runtime.hpp\"",
        ), 1),
        (T(
            "    // 消息循环",
            "    MSG msg;",
        ), T(
            "    // v223.29.11 桌面悬浮球：主窗 X 只是隐藏到托盘，点球即唤回面板（与托盘「打开面板」同语义）。",
            "    // 与面板窗同线程同消息循环同生命周期；创建失败只记日志，不影响面板。",
            "    apxpc::ui::FloatingBall ball;",
            "    if (!bundle.cfg || bundle.cfg->enableBall) {",
            "        ball.create([&panel] {",
            "            const HWND h = panel.hwnd;",
            "            if (!h || !::IsWindow(h)) return;",
            "            ::ShowWindow(h, SW_SHOW);",
            "            ::SetForegroundWindow(h);",
            "        });",
            "    } else {",
            "        APX_LOGI(\"桌面悬浮球已在 config.json 关闭（enableBall=false）\");",
            "    }",
            "",
            "    // 消息循环",
            "    MSG msg;",
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
