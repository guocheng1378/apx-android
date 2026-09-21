#pragma once
// 常驻服务对外接口：同进程持有 HTTP 控制面 + 热键 + 托盘 + 配置 + 状态广播。
#include <cstdint>
#include <string>

namespace apxpc::app {

struct ServiceOptions {
    uint16_t httpPort = 47990;     // 0 = 随机
    bool     autoOpenBrowser = true;
    bool     enableTray = true;
    bool     enableHotkey = true;
    std::string configPath;        // 空则用 %LOCALAPPDATA%\AllPeriph\config.json
    std::string webRoot;           // 静态资源根目录（开发期 APXPC_WEB_DEV_DIR）
};

// 阻塞运行，0 表示正常退出。会正确处理 Ctrl+C / 托盘退出 / 资源释放。
int runService(const ServiceOptions& opt = {});

}  // namespace apxpc::app
