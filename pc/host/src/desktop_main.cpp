// 桌面端入口：GUI 子系统（双击无控制台窗口）。
//
// 入口点仍然是 main()，靠链接选项 /ENTRY:mainCRTStartup 抑制控制台 ——
// 这样不必为了 GUI 子系统把主逻辑改成 WinMain（见 CMakeLists 的 apxdesktop 目标）。
//
// 与 CLI（apxhost.exe）的分工：
//   apxhost.exe   命令行 / 常驻服务（Web 面板 · 热键），保留给调试与脚本
//   apxdesktop.exe 桌面端「无线控制中枢」，给日常双击使用
#include "apxpc/ui/panel.hpp"

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

int main(int /*argc*/, char** /*argv*/) {
    // 开机自启注册表项由托盘写入，命令行不带参数；就算手工带参（例如旧版 CLI 的
    // "serve"）也一律忽略 —— 桌面端永远进面板。
    const int rc = apxpc::ui::runPanel();
    if (rc < 0) {
#if defined(_WIN32)
        MessageBoxW(nullptr, L"控制面板初始化失败（当前平台不支持或窗口创建失败）。",
                    L"全能外设", MB_OK | MB_ICONERROR);
#endif
        return 1;
    }
    return 0;
}
