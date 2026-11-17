#pragma once
// 配置持久化：%LOCALAPPDATA%\AllPeriph\config.json，带 schemaVersion 与迁移。
#include <cstdint>
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
    bool     enableBall = true;    // 桌面悬浮球（仅 Win；config.json 手改 false 即不创建）
    // 悬浮球「功能自定义」（config.json 顶层键 ballActions）：
    //   空数组 = 默认全部功能；非空 = 白名单，**数组顺序即球上扇形的排布顺序**。
    //   可选 id 见 ball/registry.cpp 的 actionCatalog()：open_panel / send_clipboard /
    //   toggle_speaker / toggle_mic / toggle_display / conn_status / exit。
    std::vector<std::string> ballActions;
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

    // 9511 控制面配对 token（docs/REQ-无线安全配对.md）：
    // 128-bit 随机 hex（32 字符），首次启动生成并持久化；被控端信标回带同一 token
    // 才允许握手。空串 = 不启用校验（对端旧版/未启用 token 时保持互通）。
    std::string pairingToken;

    // R2 通道加密（NoisePSK_IK_25519_ChaChaPoly_SHA256）：本端静态私钥，64 hex
    //（Curve25519 私钥 32 字节）。首次启用加密时生成并持久化，与 pairingToken
    // 同样「进程内不改动」—— 换掉即等于换身份，对端 TOFU 白名单要重配。
    std::string noiseKeyPriv;

    // R2 对端静态公钥白名单（§4.4，2026-11-17）：信任的对端静态公钥，64 hex 数组
    //（集合化 —— 多台电脑控一台手机是合法场景）。**空数组 = 无白名单，回退 TOFU
    // 旧行为**（首连静默收录并气泡提示）；非空 = 只放行表内公钥，陌生拒绝。
    // 与手机端 prefs "apx_noise"/trustedPeerPubs 同语义，两端各自落盘。
    std::vector<std::string> trustedPeerPubs;
};

// 加载：文件缺失或解析失败返回默认值（不报错，首次启动常见）；
// 任何字段缺失都回落默认，不破坏既有配置。
AppConfig loadConfig(const std::string& path);
void saveConfig(const std::string& path, const AppConfig& cfg);

// 读取 pairingToken；为空则生成 128-bit 随机 hex 写回配置并返回（load→generate→save）。
std::string ensurePairingToken(const std::string& path);

// 默认热键（七类动作）
std::map<std::string, HotkeyBinding> defaultHotkeys();

// 配置目录（%LOCALAPPDATA%\AllPeriph）
std::string defaultConfigPath();

}  // namespace apxpc::config
