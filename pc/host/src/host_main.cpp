// ============================================================================
// apxhost —— PC 宿主服务命令行入口
//
// 职责：把 discovery / ctrl / sensors 三块串起来，提供一个可手动驱动的入口。
// 图形控制面板（ui/panel_win32.cpp）是可选增量，本文件在无 UI 时也能工作
// —— 这对 CI 与无桌面环境很重要。
//
// 用法：
//   apxhost                     进入交互式命令循环
//   apxhost list                枚举设备与系统传感器后退出
//   apxhost --help
// ============================================================================

#include <apxpc/ctrl/ctrl_client.hpp>
#include <apxpc/discovery/enumerator.hpp>
#include <apxpc/discovery/discovery.hpp>
#include <apxpc/log.hpp>
#include <apxpc/sensors/sensor_reader.hpp>
#include <apxpc/version.hpp>
#include <apxpc/app/service.hpp>
#include <apxpc/wireless/wireless_link.hpp>
#include <apxpc/wireless/beacon_listener.hpp>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <thread>
#include <utility>

#ifdef _WIN32
#include <windows.h>
#endif

namespace {

void printUsage() {
    std::printf(
        "apxhost %s —— AllPeriph PC 宿主服务\n"
        "\n"
        "用法：\n"
        "  apxhost            进入交互式命令循环\n"
        "  apxhost list       枚举设备与系统传感器后退出\n"
        "  apxhost serve      启动常驻服务（Web 控制面，不自动开浏览器）\n"
        "  apxhost ui         启动常驻服务并打开控制面板\n"
        "  apxhost pair       启动服务并进入无线配对引导\n"
        "  apxhost scene      启动服务并应用场景编排\n"
        "  apxhost wireless <手机IP>[:端口] [秒数]\n"
        "                     连入手机 Wi‑Fi 控制通道并注入输入（默认端口 9500，\n"
        "                     秒数省略则一直运行到断开）\n"
        "  apxhost wireless-listen [秒数]\n"
        "                     监听手机 UDP 信标并自动连入（手机 IP 变了也不用改配置）\n"
        "  apxhost --help\n"
        "\n"
        "交互命令：\n"
        "  list       枚举设备与传感器\n"
        "  sensors    打印系统传感器读数（MS1 验收）\n"
        "  connect    连接设备并握手\n"
        "  status     打印链路状态\n"
        "  quit       退出\n",
        APXPC_VERSION_STRING);
}

// 枚举复合设备。返回找到的数量。
int doList() {
    auto devices = apxpc::discovery::enumerate();
    std::printf("发现 %zu 个 AllPeriph 设备\n", devices.size());
    for (const auto& d : devices) {
        std::printf("  %s  [%s]  vid=%04X pid=%04X  speed=%s\n",
                    d.path.c_str(), d.serial.c_str(),
                    static_cast<unsigned>(d.vid), static_cast<unsigned>(d.pid),
                    apxpc::discovery::speedName(d.speed));
    }

    // 顺带列出系统传感器 —— 这才是"免驱集成是否成功"的直接证据
    auto reader = apxpc::sensors::createSensorReader("auto");
    if (reader) {
        auto infos = reader->list();
        std::printf("\n系统已集成传感器（backend=%s）：%zu 个\n",
                    reader->backendName().c_str(), infos.size());
        for (const auto& i : infos) {
            std::printf("  %-22s [apx 0x%02X] %s\n", i.name.c_str(),
                        static_cast<unsigned>(i.apxId),
                        i.available ? "可用" : "不可用");
        }
        if (infos.empty()) {
            std::puts("  （空）提示：手机 Gadget 是否已挂载？见 pc/tools/README.md 排查路径");
        }
    }
    return static_cast<int>(devices.size());
}

int doSensors() {
    auto reader = apxpc::sensors::createSensorReader("auto");
    if (!reader) { std::fputs("创建传感器读取器失败\n", stderr); return 1; }
    auto infos = reader->list();
    if (infos.empty()) { std::puts("未发现系统集成传感器"); return 2; }
    for (const auto& info : infos) {
        const auto v = reader->read(info.apxId);
        std::printf("  %-22s ", info.name.c_str());
        if (!v.valid) { std::puts("(无读数)"); continue; }
        std::printf("ts=%llu", static_cast<unsigned long long>(v.tsNs));
        for (int i = 0; i < v.count; ++i) std::printf("  v%d=%.4f", i, v.v[i]);
        std::putchar('\n');
    }
    return 0;
}

// -------------------------------------------------------- Wi-Fi 控制通道 ----
// 手机做服务端（TCP 9500），本端连入后把收到的 streamId=3 控制帧用 SendInput
// 注入本机。两种入口：手工指定地址（wireless）与信标自动发现（wireless-listen）。

/// 解析 "ip:port"；省略端口时用手机侧默认端口
std::pair<std::string, uint16_t> parseSpec(const std::string& spec) {
    const size_t c = spec.rfind(':');
    if (c == std::string::npos) return {spec, 9500};
    return {spec.substr(0, c), static_cast<uint16_t>(std::atoi(spec.c_str() + c + 1))};
}

/// 跑一段会话并周期打印注入计数（这是「链路真的在送数据」的客观证据）。
/// seconds <= 0 表示一直运行到链路断开。
int runWirelessSession(const std::string& host, uint16_t port, const std::string& token,
                       int seconds) {
    apxpc::wireless::WirelessLink link;
    if (!link.connect(host, port, token)) {
        std::printf("连接 %s:%u 失败：%s\n", host.c_str(), static_cast<unsigned>(port),
                    link.status().error.c_str());
        return 1;
    }
    std::printf("已连接 %s:%u —— 手机端滑动触摸板 / 按键盘即注入本机。\n",
                host.c_str(), static_cast<unsigned>(port));

    const auto t0 = std::chrono::steady_clock::now();
    while (true) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        const auto st = link.status();
        const auto c = link.counters();
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - t0)
                            .count();
        std::printf("\r[%4llds] %-4s RTT %5.0fms | 鼠标 %6llu  键盘 %6llu  "
                    "多媒体 %5llu  丢弃 %llu   ",
                    static_cast<long long>(ms / 1000),
                    st.connected ? "在线" : "断开",
                    st.rttMs,
                    static_cast<unsigned long long>(c.mouse),
                    static_cast<unsigned long long>(c.keyboard),
                    static_cast<unsigned long long>(c.consumer),
                    static_cast<unsigned long long>(c.dropped));
        std::fflush(stdout);
        if (!st.connected) {
            std::puts("\n链路已断开");
            return 2;
        }
        if (seconds > 0 && ms >= static_cast<long long>(seconds) * 1000) break;
    }
    std::puts("");
    return 0;
}

/// 监听手机反向信标并自动连入（首个信标触发）
int runWirelessListen(int seconds) {
    apxpc::wireless::BeaconListener listener;
    std::atomic<bool> found{false};
    std::string host;
    uint16_t port = 9500;
    std::string token;

    const bool ok = listener.start([&](const apxpc::wireless::PhoneBeacon& pb) {
        if (found.load()) return;
        // 先写数据再置标志：读者以 found 的 acquire 读保证看到上面几行
        host = pb.host;
        port = pb.port;
        token = pb.token;
        found.store(true);
    });
    if (!ok) {
        std::puts("信标监听启动失败（UDP 9501 被占？）。可改用："
                  "apxhost wireless <手机IP>:9500");
        return 1;
    }
    std::puts("正在等待手机信标（手机端打开「Wi‑Fi 控制」模块）…");
    for (int i = 0; i < 400 && !found.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    listener.stop();   // 必须在监听线程之外调用（回调里 join 自己会死锁）
    if (!found.load()) {
        std::puts("超时：未收到手机信标。请确认手机与 PC 在同一局域网，"
                  "或改用 apxhost wireless <手机IP>:9500");
        return 1;
    }
    return runWirelessSession(host, port, token, seconds);
}

int interactive() {
    std::puts("apxhost 交互模式。输入 help 查看命令，quit 退出。");
    std::string line;
    while (true) {
        std::fputs("apx> ", stdout);
        std::fflush(stdout);
        if (!std::getline(std::cin, line)) break;
        if (line == "quit" || line == "exit" || line == "q") break;
        if (line.empty()) continue;
        if (line == "help") { printUsage(); continue; }
        if (line == "list") { doList(); continue; }
        if (line == "sensors") { doSensors(); continue; }
        if (line == "status") { doList(); continue; }   // 状态即设备+传感器快照
        if (line == "connect") {
            // 真实连接需要先发现设备再握手。此处给出明确的"未实现"提示，
            // 而不是假装成功 —— 设备不在时静默返回成功会误导使用者。
            std::puts("连接流程需要在发现设备后走 ctrl_client 握手；"
                      "当前先用 pc/tools 下的 apx_sensor_dump 做链路验证。");
            continue;
        }
        std::printf("未知命令：%s\n", line.c_str());
    }
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    // 控制台中文修复：源码字面量为 UTF-8（/utf-8），Windows 控制台默认 GBK(936) 会乱码
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
    if (argc > 1) {
        const std::string cmd = argv[1];
        if (cmd == "--help" || cmd == "-h" || cmd == "help") { printUsage(); return 0; }
        if (cmd == "--version" || cmd == "-v") { std::puts(APXPC_VERSION_STRING); return 0; }
        if (cmd == "list") return doList() > 0 ? 0 : 2;
        if (cmd == "sensors") return doSensors();

        // ---- Wi-Fi 控制通道（无蓝牙适配器的 PC 的输入承载）----
        if (cmd == "wireless") {
            if (argc < 3) {
                std::fputs("用法：apxhost wireless <手机IP>[:端口] [秒数]\n", stderr);
                return 1;
            }
            const auto hp = parseSpec(argv[2]);
            const int secs = argc > 3 ? std::atoi(argv[3]) : 0;
            return runWirelessSession(hp.first, hp.second, "", secs);
        }
        if (cmd == "wireless-listen") {
            const int secs = argc > 2 ? std::atoi(argv[2]) : 0;
            return runWirelessListen(secs);
        }

        // ---- 常驻服务 / Web 控制面板 ----
        apxpc::app::ServiceOptions so;
        // 开发期从磁盘读取前端（发布期由 CMake 内嵌）
        if (const char* dev = std::getenv("APXPC_WEB_DEV_DIR")) so.webRoot = dev;
        if (cmd == "serve")  { so.autoOpenBrowser = false; return apxpc::app::runService(so); }
        if (cmd == "ui")     { so.autoOpenBrowser = true;  return apxpc::app::runService(so); }
        if (cmd == "pair")   { so.autoOpenBrowser = true;  return apxpc::app::runService(so); }
        if (cmd == "scene")  { so.autoOpenBrowser = true;  return apxpc::app::runService(so); }

        std::fprintf(stderr, "未知参数：%s\n", cmd.c_str());
        printUsage();
        return 1;
    }
    return interactive();
}
