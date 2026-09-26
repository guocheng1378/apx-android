#pragma once
// 动作路由：**全局热键**（以及将来的 CLI）共用的动作入口。
//
// v117：Web 控制台整体下线 —— HTTP 入口 `handle()` 与 `apxpc/net/http_server.hpp`
// 依赖已删除，SSE 状态广播（buildState + 前端 demoState）也一并去掉。
// 现在唯一调用者是 `runService()` 里的热键回调（screen.toggle / screen.cycle /
// touchpad.toggle / sensor.toggle / audio.route / device.toggle）。
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "apxpc/bandwidth/arbiter.hpp"
#include "apxpc/config/app_config.hpp"
#include "apxpc/display/display_control.hpp"
#include "apxpc/hotkey/hotkey.hpp"
#include "apxpc/net/json.hpp"

namespace apxpc::api {

class ActionRouter {
public:
    ActionRouter();

    // 聚合状态（与前端 demoState 同构）
    net::Json buildState();

    // 配置落盘路径（service 设置）
    void setConfigPath(const std::string& p) { configPath_ = p; }
    config::AppConfig& config() { return cfg_; }

    void setDisplayController(std::unique_ptr<display::IDisplayController> c) {
        display_ = std::move(c);
    }
    void setHotkeyManager(std::shared_ptr<hotkey::HotkeyManager> hk) { hotkey_ = std::move(hk); }
    // v1.11：无线（WiFi 配对/数据通道）功能移除，setPairing 一并删除

    // 供宿主（热键/命令行）直接触发动作
    void act(const std::string& name, const net::Json& payload, net::Json& out);
    void query(const std::string& name, net::Json& out);

private:
    void persist();
    void applyHotkeyChange(); // 同步到 HotkeyManager

    config::AppConfig cfg_;
    std::string configPath_;
    mutable std::mutex mu_;

    bandwidth::BandwidthArbiter arbiter_;
    std::unique_ptr<display::IDisplayController> display_;
    std::shared_ptr<hotkey::HotkeyManager> hotkey_;

    // 运行时状态（不持久化）
    std::map<std::string, bool> gesture_;      // 手势启停
    std::map<std::string, bool> sensorOn_;     // 传感器启停（默认开）
    std::map<std::string, int>  sensorRate_;   // 采样率 Hz
    std::vector<double> rttHistory_;           // 最近 60 个 rtt(ms)
    double lastRttMs_ = 1.2;
    bool   deviceConnected_ = false;
    std::string deviceName_;
    std::string deviceSerial_;
};

}  // namespace apxpc::api
