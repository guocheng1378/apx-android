#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 B（M3 接线）：web_panel.cpp 3 处。
根因：src/ball 球 v2 + 注册表四项已在 master，但全库无 installDefaultActions/
createBall 调用方——球不出现、扇形空盘。
修法：runWebPanel 内 legacy 球块原位替换为 v2 装配（enableBall 门控保留，
剪贴板=connected 前置+sendClipboard+内联读本机剪贴板，退出与托盘 setQuitCallback
同源 running=false+WM_CLOSE，speaker 留空按 registry 规则灰显）；
include 区补 v2 头；消息循环退出处补 destroyBall。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("pc/host/src/ui/web_panel.cpp", [
        (T(
            "#include \"apxpc/ui/ball_win32.hpp\"",
        ), T(
            "#include \"apxpc/ui/ball_win32.hpp\"",
            "#include \"../ball/registry.hpp\"",
            "#include \"../ball/ball_win32.hpp\"",
        ), 1),
        (T(
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
        ), T(
            "    // M3 接线（2026-11-05 批 B）：v2 球接管——四项钩子装注册表，扇形同窗绘制（src/ball）。",
            "    // legacy 壳（apxpc/ui/ball_win32.hpp）自此退役待收编，不再创建，避免双球。",
            "    // 钩子留空=registry 规则灰显不装死按钮：toggle_speaker 待接媒体栈后点亮。",
            "    if (bundle.cfg && !bundle.cfg->enableBall) {",
            "        APX_LOGI(\"桌面悬浮球已在 config.json 关闭（enableBall=false）\");",
            "    } else {",
            "        apxpc::ball::HostActions ha;",
            "        ha.open_panel = [&panel] {",
            "            const HWND h = panel.hwnd;",
            "            if (!h || !::IsWindow(h)) return;",
            "            ::ShowWindow(h, SW_RESTORE);",
            "            ::SetForegroundWindow(h);",
            "        };",
            "        ha.send_clipboard = [&bundle]() -> bool {",
            "            if (!bundle.session || !bundle.session->connected()) return false;",
            "            std::string text;",
            "            if (::OpenClipboard(nullptr)) {",
            "                if (HANDLE h = ::GetClipboardData(CF_UNICODETEXT)) {",
            "                    if (const wchar_t* w = static_cast<const wchar_t*>(::GlobalLock(h))) {",
            "                        const int n = ::WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);",
            "                        if (n > 1) { text.assign(n - 1, 0); ::WideCharToMultiByte(CP_UTF8, 0, w, -1, &text[0], n, nullptr, nullptr); }",
            "                        ::GlobalUnlock(h);",
            "                    }",
            "                }",
            "                ::CloseClipboard();",
            "            }",
            "            return !text.empty() && bundle.session->sendClipboard(text);",
            "        };",
            "        ha.exit_app = [hwnd = panel.hwnd] {",
            "            if (Panel* pp = g_panel) pp->running.store(false);",
            "            if (::IsWindow(hwnd)) ::PostMessageW(hwnd, WM_CLOSE, 0, 0);",
            "        };",
            "        ha.connected = [&bundle] { return bundle.session && bundle.session->connected(); };",
            "        ha.clipboard_pending = [] { return false; };",
            "        apxpc::ball::installDefaultActions(ha);",
            "        if (!apxpc::ball::createBall()) APX_LOGI(\"ball v2 创建失败（面板/托盘不受影响）\");",
            "    }",
        ), 1),
        (T(
            "    if (panel.bundle.localSend) panel.bundle.localSend->stop();",
        ), T(
            "    apxpc::ball::destroyBall();  // 面板退场前先收球（与 legacy 同生命周期语义）",
            "    if (panel.bundle.localSend) panel.bundle.localSend->stop();",
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
