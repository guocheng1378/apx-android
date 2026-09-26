#pragma once
// 配置持久化：%LOCALAPPDATA%\AllPeriph\config.json，带 schemaVersion 与迁移。
#include <map>
#include <string>
#include <vector>

namespace apxpc::config {

inline constexpr int kSchemaVersion = 1;

struct HotkeyBinding {
    std::vector<std::string> mods; // "ctrl" "alt" "shift" "win"
    std::string key;               // 单字符或 F1..F24 / VK 名
    bool enabled = true;
};

struct AppConfig {
    int schemaVersion = kSchemaVersion;

    // 服务（v117：httpPort / autoOpenBrowser 随 Web 控制台下线删除；
    //       旧 config.json 里的这两个键会被忽略，不影响加载）
    bool     enableTray = true;
    bool     autostart = false;    // 开机自启
    std::string logLevel = "info"; // trace debug info warn error

    // 连接模式：wired | wireless
    std::string mode = "wired";

    // 副屏
    bool     displayEnabled = false;
    uint32_t displayW = 1080;
    uint32_t displayH = 2400;
    uint32_t displayHz = 60;
    std::string displayOrientation = "portrait"; // portrait | landscape
    std::string displayEncoder = "HEVC";
    uint32_t displayBitrateMbps = 12;
    uint32_t displayFps = 60;
    bool     displayAdaptive = true;

    // 触控板
    bool     touchpadEnabled = false;
    std::string touchpadPath = "bt"; // bt | usb | inject
    double   touchpadSensitivity = 1.0;
    uint32_t touchpadScrollStep = 3;
    bool     touchpadAccel = true;
    bool     touchpadNaturalScroll = false;

    // 音频
    std::string audioRoute = "speaker"; // speaker | headset | phone | mute

    // 热键（按 ActionId 名索引）
    std::map<std::string, HotkeyBinding> hotkeys;

    // 场景绑定（场景 id -> 热键组合键字符串，可选）
    std::map<std::string, std::string> scenes;

    // PC 侧键盘重映射（from 物理键 -> to 逻辑键，优化项）
    std::map<std::string, std::string> keymap;
};

// 加载：文件缺失或解析失败返回默认值（不报错，首次启动常见）；
// 任何字段缺失都回落默认，不破坏既有配置。
AppConfig loadConfig(const std::string& path);
void saveConfig(const std::string& path, const AppConfig& cfg);

// 默认热键（七类动作）
std::map<std::string, HotkeyBinding> defaultHotkeys();

// 配置目录（%LOCALAPPDATA%\AllPeriph）
std::string defaultConfigPath();

}  // namespace apxpc::config
