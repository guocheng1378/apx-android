#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 M4-c(b)（全局热键唤起球/扇形，Win + mac 双端）：ball_win32.hpp 2 + ball_win32.cpp 3
+ ball_mac.mm 2 + app_config.cpp 1 + desktop_main.cpp 5。
球侧：新增 apxpc::ball::toggleBallFan() —— Win PostMessage 回球窗口线程开/收扇
（kFanHotkeyMsg，不跨线程碰 BallState）；mac performSelectorOnMainThread 回主线程翻 toggleOpen。
注册点：desktop_main 装配处 ballhotkey::start/stop —— Win 独立线程 RegisterHotKey+WM_HOTKEY
循环；mac dlopen Carbon RegisterEventHotKey（免辅助功能授权、不改链接面）。刻意不走
HotkeyManager（actionFromName 对未知名兜底 screen.toggle 且 mac start() 返回未实现）。
组合键取 config hotkeys["ball.fan"]，缺省 Ctrl+Shift+Space（注释见 app_config.cpp）。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("pc/host/src/ball/ball_win32.hpp", [
        (T(
            "// 置前台（托盘「球」菜单 / 热键调用）",
            "void bringToFront(HWND hwnd);",
        ), T(
            "// 置前台（托盘「球」菜单 / 热键调用）",
            "void bringToFront(HWND hwnd);",
            "",
            "// 全局热键切换扇形（M4-c(b)）。任意线程可调：Win 侧只投消息，",
            "// 开合动作回到球窗口线程的 wndproc 里做（见 kFanHotkeyMsg 分支）。",
            "void toggleBallFan();",
        ), 1),
        (T(
            "} // namespace ball",
            "} // namespace apxpc",
            "",
            "#endif // _WIN32",
        ), T(
            "} // namespace ball",
            "} // namespace apxpc",
            "",
            "#else  // 非 Windows（macOS）：球 v2 实现在 ball_mac.mm（M4-b），",
            "       // 只暴露共享 API —— desktop_main 热键注册点两端同一份调用。",
            "namespace apxpc {",
            "namespace ball {",
            "",
            "// 切换扇形展开/收起；球未创建时静默返回。",
            "void toggleBallFan();",
            "",
            "} // namespace ball",
            "} // namespace apxpc",
            "",
            "#endif // _WIN32",
        ), 1),
    ]),
    ("pc/host/src/ball/ball_win32.cpp", [
        (T(
            "constexpr UINT_PTR kTimerAnim    = 1;",
            "constexpr UINT_PTR kTimerBadge   = 2;",
        ), T(
            "constexpr UINT_PTR kTimerAnim    = 1;",
            "constexpr UINT_PTR kTimerBadge   = 2;",
            "constexpr UINT     kFanHotkeyMsg = WM_APP + 41;  // 热键线程投递：切换扇形",
        ), 1),
        (T(
            "void bringToFront(HWND w) {",
            "    if (!w) return;",
            "    ::SetWindowPos(w, HWND_TOPMOST, 0, 0, 0, 0,",
            "                   SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);",
            "}",
        ), T(
            "void bringToFront(HWND w) {",
            "    if (!w) return;",
            "    ::SetWindowPos(w, HWND_TOPMOST, 0, 0, 0, 0,",
            "                   SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);",
            "}",
            "",
            "// 热键入口（M4-c(b)）：球窗口有自己的线程、热键回调在热键线程 —— 直接调",
            "// expand/collapse 会跨线程改 BallState 并提交 GDI+ 位图，所以只投消息。",
            "void toggleBallFan() {",
            "    if (!g_ballWnd) return;   // 球未创建：静默无操作",
            "    ::PostMessageW(g_ballWnd, kFanHotkeyMsg, 0, 0);",
            "}",
        ), 1),
        (T(
            "    case WM_PAINT: {                   // 表面归 UpdateLayeredWindow；这里只确认无效区",
        ), T(
            "    case kFanHotkeyMsg: {              // 热键：开→收扇；收→按注册表开扇",
            "        auto* s = g;",
            "        if (!s) return 0;",
            "        if (s->fanOpen) collapseLocked(w);",
            "        else openFanFromRegistry(w);",
            "        return 0;",
            "    }",
            "",
            "    case WM_PAINT: {                   // 表面归 UpdateLayeredWindow；这里只确认无效区",
        ), 1),
    ]),
    ("pc/host/src/ball/ball_mac.mm", [
        (T(
            "@interface BallView : NSView",
            "@end",
        ), T(
            "@interface BallView : NSView",
            "// 热键入口要用（M4-c(b)）：回调可能在非主线程，回主线程再翻转。",
            "- (void)toggleOpen;",
            "@end",
        ), 1),
        (T(
            "void destroyBall() {",
            "    stopTimer();",
        ), T(
            "// 全局热键切换扇形（M4-c(b)）：desktop_main 注册点调用，任意线程可调。",
            "void toggleBallFan() {",
            "    if (!g.win) return;",
            "    BallView* v = (BallView*)[[[g.win contentView] subviews] firstObject];",
            "    if (!v) return;",
            "    [v performSelectorOnMainThread:@selector(toggleOpen) withObject:nil waitUntilDone:NO];",
            "}",
            "",
            "void destroyBall() {",
            "    stopTimer();",
        ), 1),
    ]),
    ("pc/host/src/config/app_config.cpp", [
        (T(
            "    m[\"device.toggle\"]   = {{\"ctrl\", \"alt\"}, \"F7\", true};",
            "    return m;",
        ), T(
            "    m[\"device.toggle\"]   = {{\"ctrl\", \"alt\"}, \"F7\", true};",
            "",
            "    // 球扇形热键 ball.fan **刻意不进本表**：HotkeyManager 按 ActionId 名分发，",
            "    // actionFromName 对未知名兜底成 screen.toggle（见 hotkey_manager.cpp），",
            "    // 进表会顶掉「副屏开关」的绑定。缺省 Ctrl+Shift+Space（ctrl+shift / space）",
            "    // 由 desktop_main 的 ballhotkey::start 读取；要改就往 config.json 的 hotkeys",
            "    // 手工加 \"ball.fan\": {\"mods\":[\"ctrl\",\"shift\"],\"key\":\"space\",\"enabled\":true}。",
            "    return m;",
        ), 1),
    ]),
    ("pc/host/src/desktop_main.cpp", [
        (T(
            "#include \"apxpc/hotkey/hotkey.hpp\"",
        ), T(
            "#include \"apxpc/hotkey/hotkey.hpp\"",
            "#include \"ball/ball_win32.hpp\"   // 球 v2 共享 API：toggleBallFan（M4-c(b) 热键唤起）",
        ), 1),
        (T(
            "#if defined(__APPLE__)",
            "#include <mach-o/dyld.h>   // _dyld_get_image_header：崩溃报告的 imageOffset 基准",
            "#endif",
        ), T(
            "#if defined(__APPLE__)",
            "#include <mach-o/dyld.h>   // _dyld_get_image_header：崩溃报告的 imageOffset 基准",
            "#include <dlfcn.h>          // 球扇形热键（mac）：dlopen Carbon 取 RegisterEventHotKey",
            "#endif",
        ), 1),
        (T(
            "}  // namespace",
            "",
            "int runDesktopMain() {",
        ), T(
            "// —————— 全局热键：切换球扇形（M4-c(b)，Win + mac 双端）——————",
            "// 刻意不走 HotkeyManager：那套按 ActionId 分发（actionFromName 对未知名兜底成",
            "// screen.toggle，见 hotkey_manager.cpp），且 mac 侧 start() 直接返回「未实现」。",
            "// 这里各自注册一条独立热键，只做一件事：按下 → apxpc::ball::toggleBallFan()。",
            "// 组合键取 config.json hotkeys[\"ball.fan\"]（与仓库热键同款 mods/key 结构），",
            "// 缺省 Ctrl+Shift+Space（默认值注释见 app_config.cpp makeDefaults）。",
            "namespace ballhotkey {",
            "",
            "std::vector<std::string> g_mods = {\"ctrl\", \"shift\"};",
            "std::string g_key = \"space\";",
            "",
            "void loadBinding(const apxpc::config::AppConfig& cfg) {",
            "    auto it = cfg.hotkeys.find(\"ball.fan\");",
            "    if (it == cfg.hotkeys.end() || it->second.key.empty()) return;  // 缺省即默认",
            "    if (!it->second.mods.empty()) g_mods = it->second.mods;",
            "    g_key = it->second.key;",
            "}",
            "",
            "std::string comboText() {",
            "    std::string s;",
            "    for (const auto& m : g_mods) { s += m; s += '+'; }",
            "    s += g_key;",
            "    return s;",
            "}",
            "",
            "#if defined(_WIN32)",
            "",
            "constexpr int kId = 0x4150;   // 自有热键 id，避开 HotkeyManager 的 ActionId 取值域",
            "std::atomic<bool> g_running{false};",
            "HANDLE g_thread = nullptr;",
            "DWORD  g_tid = 0;",
            "",
            "UINT vkOf(const std::string& key) {",
            "    if (key.empty()) return 0;",
            "    if (key.size() > 1) {",
            "        if (key == \"space\") return VK_SPACE;",
            "        if (key[0] == 'f' || key[0] == 'F') {",
            "            const int n = std::atoi(key.c_str() + 1);",
            "            if (n >= 1 && n <= 24) return VK_F1 + (n - 1);",
            "        }",
            "        return 0;",
            "    }",
            "    char c = key[0];",
            "    if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');",
            "    if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return static_cast<UINT>(c);",
            "    return 0;",
            "}",
            "",
            "// RegisterHotKey 绑的是**调用线程**的消息队列 —— 注册必须发生在本线程内。",
            "DWORD WINAPI ballHotkeyThread(LPVOID) {",
            "    int fs = 0;",
            "    for (const auto& m : g_mods) {",
            "        if (m == \"ctrl\") fs |= MOD_CONTROL;",
            "        else if (m == \"alt\") fs |= MOD_ALT;",
            "        else if (m == \"shift\") fs |= MOD_SHIFT;",
            "        else if (m == \"win\") fs |= MOD_WIN;",
            "    }",
            "    const UINT vk = vkOf(g_key);",
            "    if (vk == 0) { APX_LOGW(\"球扇形热键跳过（非法键 {}）\", g_key.c_str()); return 0; }",
            "    if (!::RegisterHotKey(nullptr, kId, fs, vk))",
            "        APX_LOGW(\"球扇形热键注册失败（可能已被占用）: {}\", comboText().c_str());",
            "    else",
            "        APX_LOGI(\"球扇形热键已注册：{}\", comboText().c_str());",
            "",
            "    MSG msg;",
            "    ::PeekMessage(&msg, nullptr, 0, 0, PM_NOREMOVE);   // 建队列，stop 才投得进 WM_QUIT",
            "    while (g_running) {",
            "        if (::GetMessage(&msg, nullptr, 0, 0) <= 0) break;",
            "        if (msg.message == WM_HOTKEY && static_cast<int>(msg.wParam) == kId) {",
            "            APX_LOGI(\"球扇形热键触发：{}\", comboText().c_str());",
            "            apxpc::ball::toggleBallFan();",
            "        } else {",
            "            ::TranslateMessage(&msg);",
            "            ::DispatchMessage(&msg);",
            "        }",
            "    }",
            "    ::UnregisterHotKey(nullptr, kId);",
            "    return 0;",
            "}",
            "",
            "void start(const apxpc::config::AppConfig& cfg) {",
            "    if (g_thread) return;",
            "    loadBinding(cfg);",
            "    g_running = true;",
            "    g_thread = ::CreateThread(nullptr, 0, ballHotkeyThread, nullptr, 0, &g_tid);",
            "    if (!g_thread) { g_running = false; APX_LOGW(\"球扇形热键线程创建失败\"); }",
            "}",
            "",
            "void stop() {",
            "    if (!g_thread) return;",
            "    g_running = false;",
            "    for (int i = 0; i < 20 && !::PostThreadMessage(g_tid, WM_QUIT, 0, 0); ++i) ::Sleep(50);",
            "    if (::WaitForSingleObject(g_thread, 2000) != WAIT_OBJECT_0)",
            "        APX_LOGW(\"球扇形热键线程未按时退出（不阻塞收尾）\");",
            "    ::CloseHandle(g_thread);",
            "    g_thread = nullptr;",
            "}",
            "",
            "#elif defined(__APPLE__)",
            "",
            "// mac 走 Carbon RegisterEventHotKey：不需要「辅助功能」授权（CGEventTap / NSEvent",
            "// 全局键盘监听才要），事件由主线程 AppKit 泵分发；符号 dlopen/dlsym 取，不改链接面。",
            "struct EventHotKeyID { unsigned int signature; unsigned int id; };",
            "using InstallHandler_t = int (*)(int (*)(void*, void*, void*), unsigned int,",
            "                                 const void*, void*, void**);",
            "using AppTarget_t = void* (*)();",
            "using RegisterHotKey_t = int (*)(unsigned int, unsigned int, EventHotKeyID,",
            "                                 void*, unsigned int, void**);",
            "using UnregisterHotKey_t = int (*)(void*, void*);",
            "",
            "void* g_lib = nullptr;",
            "void* g_hk = nullptr;",
            "void* g_handler = nullptr;",
            "",
            "int onBallHot(void*, void*, void*) {",
            "    apxpc::ball::toggleBallFan();",
            "    return 0;   // noErr",
            "}",
            "",
            "void start(const apxpc::config::AppConfig& cfg) {",
            "    if (g_lib) return;",
            "    loadBinding(cfg);",
            "    if (g_key != \"space\") {",
            "        APX_LOGW(\"球扇形热键：mac 侧暂只支持 key=space（当前 {}），未注册\", g_key.c_str());",
            "        return;",
            "    }",
            "    g_lib = ::dlopen(\"/System/Library/Frameworks/Carbon.framework/Carbon\", RTLD_LAZY);",
            "    if (!g_lib)",
            "        g_lib = ::dlopen(\"/System/Library/Frameworks/Carbon.framework/Frameworks/HIToolbox.framework/HIToolbox\", RTLD_LAZY);",
            "    if (!g_lib) { APX_LOGW(\"球扇形热键：Carbon 加载失败（{}）\", ::dlerror()); return; }",
            "    auto install = reinterpret_cast<InstallHandler_t>(::dlsym(g_lib, \"InstallApplicationEventHandler\"));",
            "    auto target  = reinterpret_cast<AppTarget_t>(::dlsym(g_lib, \"GetApplicationEventTarget\"));",
            "    auto reg     = reinterpret_cast<RegisterHotKey_t>(::dlsym(g_lib, \"RegisterEventHotKey\"));",
            "    if (!install || !target || !reg) { APX_LOGW(\"球扇形热键：Carbon 符号缺失\"); return; }",
            "",
            "    unsigned int mods = 0;",
            "    for (const auto& m : g_mods) {",
            "        if (m == \"ctrl\") mods |= 4096u;       // controlKey",
            "        else if (m == \"shift\") mods |= 512u;  // shiftKey",
            "        else if (m == \"alt\") mods |= 2048u;   // optionKey（mac 的 Alt）",
            "        else if (m == \"win\") mods |= 256u;    // cmdKey",
            "    }",
            "    const unsigned int spec[2] = {0x6B657962u /*'keyb' kEventClassKeyboard*/,",
            "                                  5u /*kEventHotKeyPressed*/};",
            "    if (install(&onBallHot, 1, spec, nullptr, &g_handler) != 0) {",
            "        APX_LOGW(\"球扇形热键：事件处理器安装失败\");",
            "        return;",
            "    }",
            "    EventHotKeyID hid{0x41504631u /*'APF1'*/, 1u};",
            "    if (reg(49u /*kVK_Space*/, mods, hid, target(), 0u, &g_hk) != 0) {",
            "        APX_LOGW(\"球扇形热键注册失败（可能已被占用）: {}\", comboText().c_str());",
            "        return;",
            "    }",
            "    APX_LOGI(\"球扇形热键已注册：{}（mac Carbon）\", comboText().c_str());",
            "}",
            "",
            "void stop() {",
            "    if (!g_lib) return;",
            "    auto unreg  = reinterpret_cast<UnregisterHotKey_t>(::dlsym(g_lib, \"UnregisterEventHotKey\"));",
            "    auto target = reinterpret_cast<AppTarget_t>(::dlsym(g_lib, \"GetApplicationEventTarget\"));",
            "    if (g_hk && unreg && target) unreg(target(), g_hk);",
            "    g_hk = nullptr;",
            "    // lib 不 dlclose：退出期卸载无收益，留给进程结束回收",
            "}",
            "",
            "#else",
            "",
            "void start(const apxpc::config::AppConfig& cfg) { loadBinding(cfg); (void)comboText(); }",
            "void stop() {}",
            "",
            "#endif",
            "",
            "}  // namespace ballhotkey",
            "",
            "}  // namespace",
            "",
            "int runDesktopMain() {",
        ), 1),
        (T(
            "    if (cfg.autostart) apxpc::tray::setAutostart(true);",
        ), T(
            "    if (cfg.autostart) apxpc::tray::setAutostart(true);",
            "",
            "    // 球扇形全局热键（M4-c(b)）：装配处注册。mac 的 Carbon 事件靠主线程 AppKit",
            "    // 泵分发，必须在下方 tray->run() 之前注册。",
            "    ballhotkey::start(cfg);",
        ), 1),
        (T(
            "    ctrlSrv.stop();",
            "    hk->stop();",
        ), T(
            "    ctrlSrv.stop();",
            "    hk->stop();",
            "    ballhotkey::stop();   // 球扇形热键收尾（Win：停线程+注销；mac：UnregisterEventHotKey）",
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
