#pragma once
// 常驻服务对外接口：同进程持有 **9511 受控端 + 全局热键 + 托盘 + 配置**。
// v117：Web 控制台下线，HTTP 端口 / 自动开浏览器 / 静态资源根目录三项选项已删除。
#include <string>

namespace apxpc::app {

struct ServiceOptions {
    bool     enableTray = true;
    bool     enableHotkey = true;
    std::string configPath;        // 空则用 %LOCALAPPDATA%\AllPeriph\config.json
};

// 阻塞运行，0 表示正常退出。会正确处理 Ctrl+C / 托盘退出 / 资源释放。
int runService(const ServiceOptions& opt = {});

}  // namespace apxpc::app
