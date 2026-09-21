#pragma once
// 动作路由：浏览器 / 全局热键 / 本机命令行共用的唯一动作入口。
// 同时负责聚合一份与前端 demoState 同构的状态，经 SSE 推送。
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "apxpc/bandwidth/arbiter.hpp"
#include "apxpc/config/app_config.hpp"
#include "apxpc/display/display_control.hpp"
#include "apxpc/hotkey/hotkey.hpp"
#include "apxpc/net/http_server.hpp"
#include "apxpc/net/json.hpp"

namespace apxpc::api {

class ActionRouter {
public:
    ActionRouter();

    // HTTP 处理：/api/act/<name> 与 /api/q/<name>
    void handle(const net::HttpRequest& req, net::HttpResponse* res);

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
