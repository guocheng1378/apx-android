#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 M4-a（mac 菜单栏连接角标）：tray_win32.hpp / tray_mac.mm / web_panel.cpp 共 7 处。
根因：连接状态只进面板 JSON，菜单栏图标零反馈——mac 用户不看面板时
完全不知道连没连上（规划 M4「tray_mac 角标」）。
修法：TrayIcon::setBadge(bool disconnected) 跨平台 API——
_WIN32 内联空实现（Windows 托盘角标二期，防链接缺符号），
mac 在 tray_mac.mm 实现（原子量入队 + 泵内主线程上色，同通知队列模式，
绝不跨线程碰 AppKit——issue#47 红线）；web_panel buildState 每 400ms
按 LinkPhase 喂角标（已连接熄灭，其余亮红点）。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("pc/host/include/apxpc/tray/tray_win32.hpp", [
        (T(
            "    void setPort(unsigned port) { port_ = port; }",
        ), T(
            "    void setPort(unsigned port) { port_ = port; }",
            "",
            "    /// 连接状态角标：true=未连接（菜单栏图标亮红点），false=已连接（熄）。",
            "    /// mac 实现在 tray_mac.mm（泵内主线程上色）；Windows 托盘角标二期实现，",
            "    /// 此处内联空实现保证共享代码在 _WIN32 上不缺符号。",
            "#if defined(_WIN32)",
            "    void setBadge(bool) {}",
            "#else",
            "    void setBadge(bool disconnected);",
            "#endif",
        ), 1),
    ]),
    ("pc/host/src/tray/tray_mac.mm", [
        (T(
            "MacTrayShared g_shared;",
        ), T(
            "MacTrayShared g_shared;",
            "/// 连接角标请求（面板线程写、泵线程读——同通知队列的跨线程约定）。",
            "std::atomic<bool> g_badgeOn{false};",
        ), 1),
        (T(
            "    for (auto& p : pending) deliverNotification(p.first, p.second);",
            "}",
        ), T(
            "    for (auto& p : pending) deliverNotification(p.first, p.second);",
            "}",
            "",
            "/// 连接角标：未连接给状态栏按钮加红点（attributedTitle 与图片并存），连接则清。",
            "/// 主线程专用（AppKit 规矩）——只由 run() 泵每拍调用，绝不从面板线程直调。",
            "void applyBadge() {",
            "    static bool last = false;",
            "    const bool on = g_badgeOn.load();",
            "    if (on == last || !g_statusItem) return;",
            "    last = on;",
            "    NSStatusBarButton* btn = [g_statusItem button];",
            "    if (btn == nil) return;",
            "    if (on) {",
            "        NSDictionary* attrs = @{NSForegroundColorAttributeName : [NSColor systemRedColor]};",
            "        btn.attributedTitle =",
            "            [[NSAttributedString alloc] initWithString:@\"●\" attributes:attrs];",
            "    } else {",
            "        btn.attributedTitle = [[NSAttributedString alloc] initWithString:@\"\"];",
            "    }",
            "}",
        ), 1),
        (T(
            "                drainPendingNotifications();",
        ), T(
            "                drainPendingNotifications();",
            "                applyBadge();",
        ), 1),
        (T(
            "TrayIcon::TrayStatus TrayIcon::status() const {",
        ), T(
            "void TrayIcon::setBadge(bool disconnected) {",
            "    // 只置原子量；视觉由 run() 泵每拍在主线程统一上色（同 notify 的队列模式）。",
            "    g_badgeOn.store(disconnected);",
            "}",
            "",
            "TrayIcon::TrayStatus TrayIcon::status() const {",
        ), 1),
    ]),
    ("pc/host/src/ui/web_panel.cpp", [
        (T(
            "        const auto s = b.session->snapshot();",
        ), T(
            "        const auto s = b.session->snapshot();",
            "        if (b.tray) b.tray->setBadge(s.phase != LinkPhase::Connected);  // M4 菜单栏连接角标",
        ), 1),
        (T(
            "    } else {",
            "        link[\"phase\"] = static_cast<int>(LinkPhase::Idle);",
        ), T(
            "    } else {",
            "        if (b.tray) b.tray->setBadge(true);",
            "        link[\"phase\"] = static_cast<int>(LinkPhase::Idle);",
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
