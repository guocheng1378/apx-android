#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 M4-c(a)（Windows 托盘连接角标真现）：tray_win32.hpp 1 处 + tray_win32.cpp 4 处。
根因：M4-a 的 setBadge 在 _WIN32 是内联空桩——Windows 托盘图标永远无连接反馈。
修法：hpp 空桩改统一声明（mac 定义在 tray_mac.mm 不变）；cpp 内新增
setBadge 真现：底图 g_baseIcon + 右下角红点（DIB 画制，32bpp 全不透明像素
避开预乘 alpha 坑，遮罩白填充保证不透）→ CreateIconIndirect → NIM_MODIFY；
create 存底图、quit 销毁合成件。Shell_NotifyIcon 线程安全（文件注释原话），
web_panel 400ms tick 直调无需泵。状态短路：未变化不合成不 NIM。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("pc/host/include/apxpc/tray/tray_win32.hpp", [
        (T(
            "    /// 连接状态角标：true=未连接（菜单栏图标亮红点），false=已连接（熄）。",
            "    /// mac 实现在 tray_mac.mm（泵内主线程上色）；Windows 托盘角标二期实现，",
            "    /// 此处内联空实现保证共享代码在 _WIN32 上不缺符号。",
            "#if defined(_WIN32)",
            "    void setBadge(bool) {}",
            "#else",
            "    void setBadge(bool disconnected);",
            "#endif",
        ), T(
            "    /// 连接状态角标：true=未连接（图标亮红点），false=已连接（熄）。",
            "    /// mac 实现在 tray_mac.mm（泵内主线程上色）；Windows 实现在 tray_win32.cpp",
            "    ///（合成红点 HICON + NIM_MODIFY，Shell_NotifyIcon 线程安全可任意线程直调）。",
            "    void setBadge(bool disconnected);",
        ), 1),
    ]),
    ("pc/host/src/tray/tray_win32.cpp", [
        (T(
            "const UINT WM_TRAY = WM_APP + 1;",
        ), T(
            "const UINT WM_TRAY = WM_APP + 1;",
            "// 连接角标状态（M4-c）：底图 + 合成缓存 + 当前亮灭。setBadge 任意线程可调。",
            "static HICON g_baseIcon  = nullptr;  // create() 里存（系统共享图标不销毁）",
            "static HICON g_badgeIcon = nullptr;  // 合成件，自己造自己毁",
            "static bool  g_badged    = false;",
        ), 1),
        (T(
            "    nid.hIcon = icon ? icon : LoadIcon(nullptr, IDI_APPLICATION);",
        ), T(
            "    nid.hIcon = icon ? icon : LoadIcon(nullptr, IDI_APPLICATION);",
            "    g_baseIcon = nid.hIcon;  // 角标合成底图",
        ), 1),
        (T(
            "// Windows：托盘线程在 create() 内自跑消息循环，这里无事可做。",
            "// 接口为跨平台统一而设（mac 上 run() 是主线程 AppKit 事件泵，见 tray_mac.mm）。",
            "bool TrayIcon::run(const std::function<bool()>&) { return false; }",
        ), T(
            "// 连接角标（M4-c）：底图右下角合成红点 → NIM_MODIFY 换图标。Shell_NotifyIcon",
            "// 线程安全（见 notify 注释），web_panel 400ms tick 直调；未变化短路不合成不 NIM。",
            "// 合成取全不透明像素（DIB 画制 + 白填充遮罩）——避开 32bpp 预乘 alpha 的坑。",
            "void TrayIcon::setBadge(bool disconnected) {",
            "    if (!window_ || !running_ || !g_baseIcon) return;",
            "    if (disconnected == g_badged) return;",
            "    g_badged = disconnected;",
            "    HICON shown = g_baseIcon;",
            "    if (disconnected) {",
            "        if (!g_badgeIcon) {",
            "            ICONINFO ii{};",
            "            if (GetIconInfo(g_baseIcon, &ii)) {",
            "                BITMAP bm{};",
            "                GetObject(ii.hbmColor ? ii.hbmColor : ii.hbmMask, sizeof bm, &bm);",
            "                const int w = bm.bmWidth > 0 ? bm.bmWidth : GetSystemMetrics(SM_CXSMICON);",
            "                const int h = bm.bmHeight > 0 ? bm.bmHeight : GetSystemMetrics(SM_CYSMICON);",
            "                HDC scr = GetDC(nullptr);",
            "                HDC mem = CreateCompatibleDC(scr);",
            "                BITMAPINFO bi{};",
            "                bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);",
            "                bi.bmiHeader.biWidth = w;",
            "                bi.bmiHeader.biHeight = -h;  // 自上而下",
            "                bi.bmiHeader.biPlanes = 1;",
            "                bi.bmiHeader.biBitCount = 32;",
            "                void* bits = nullptr;",
            "                HBITMAP col = CreateDIBSection(mem, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);",
            "                HBITMAP mask = nullptr;",
            "                if (col && bits) {",
            "                    HGDIOBJ old = SelectObject(mem, col);",
            "                    PatBlt(mem, 0, 0, w, h, BLACKNESS);        // alpha=0 打底",
            "                    DrawIconEx(mem, 0, 0, g_baseIcon, w, h, 0, nullptr, DI_NORMAL);",
            "                    const int d = w / 3 > 4 ? w / 3 : 4;",
            "                    HBRUSH red = CreateSolidBrush(RGB(230, 50, 45));",
            "                    HGDIOBJ ob = SelectObject(mem, red);",
            "                    HGDIOBJ op = SelectObject(mem, GetStockObject(NULL_PEN));",
            "                    Ellipse(mem, w - d - 1, h - d - 1, w - 1, h - 1);",
            "                    SelectObject(mem, ob);",
            "                    SelectObject(mem, op);",
            "                    DeleteObject(red);",
            "                    mask = CreateBitmap(w, h, 1, 1, nullptr);",
            "                    SelectObject(mem, mask);",
            "                    PatBlt(mem, 0, 0, w, h, WHITENESS);",
            "                    SelectObject(mem, old);",
            "                    ICONINFO out{};",
            "                    out.fIcon = TRUE;",
            "                    out.hbmMask = mask;",
            "                    out.hbmColor = col;",
            "                    g_badgeIcon = CreateIconIndirect(&out);",
            "                    DeleteObject(col);",
            "                    if (mask) DeleteObject(mask);",
            "                }",
            "                DeleteDC(mem);",
            "                ReleaseDC(nullptr, scr);",
            "                DeleteObject(ii.hbmColor);",
            "                DeleteObject(ii.hbmMask);",
            "            }",
            "        }",
            "        if (g_badgeIcon) shown = g_badgeIcon;",
            "    }",
            "    NOTIFYICONDATA nid{};",
            "    nid.cbSize = sizeof nid;",
            "    nid.hWnd = static_cast<HWND>(window_);",
            "    nid.uID = 1;",
            "    nid.uFlags = NIF_ICON;",
            "    nid.hIcon = shown;",
            "    Shell_NotifyIcon(NIM_MODIFY, &nid);",
            "}",
            "",
            "// Windows：托盘线程在 create() 内自跑消息循环，这里无事可做。",
            "// 接口为跨平台统一而设（mac 上 run() 是主线程 AppKit 事件泵，见 tray_mac.mm）。",
            "bool TrayIcon::run(const std::function<bool()>&) { return false; }",
        ), 1),
        (T(
            "        Shell_NotifyIcon(NIM_DELETE, &nid);",
        ), T(
            "        Shell_NotifyIcon(NIM_DELETE, &nid);",
            "        if (g_badgeIcon) { DeleteObject(g_badgeIcon); g_badgeIcon = nullptr; }",
            "        g_badged = false;",
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
