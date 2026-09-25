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
#include <apxpc/wireless/ctrl9511.hpp>
#include <apxpc/wireless/input_capture.hpp>
#include <apxpc/media/media_session.hpp>
#include <apxpc/media/audio_capture.hpp>

#include <atomic>
#include <chrono>
#include <cmath>
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
        "  apxhost ctrl9511-serve [端口] [名称] [秒数]\n"
        "                     启动 9511 受控服务端（默认端口 9511），手机/TV 用「TV 控制」\n"
        "                     即可控 PC；同时广播 APX1TV 信标供手机自动发现\n"
        "  apxhost ctrl9511-connect <host>[:端口] [秒数]\n"
        "                     作为控制端连入手机/TV 的 9511 服务端（验证链路 + 反向剪贴板）\n"
        "  apxhost ctrl9511-remote <host>[:端口] [秒数]\n"
        "                     PC 真正当遥控器：连入后把本机鼠标/键盘实时镜像到对端（控手机/TV/PC）\n"
        "  apxhost media <手机IP>[:端口] [秒数]\n"
        "                     连入手机媒体通道（默认端口 9502）并打印各路计数。\n"
        "                     副屏/音箱为下行（本端发出），麦克风为上行\n"
        "  apxhost speaker <手机IP>[:端口] [秒数] [设备序号]\n"
        "                     把本机系统声音（WASAPI loopback）送到手机扬声器，\n"
        "                     并实时显示音量条 —— 用于确认采集到的就是正在放的声音。\n"
        "                     会先列出所有播放设备；不填序号 = 跟随系统默认\n"
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

// ----------------------------------------------------- 地址解析辅助 ----
/// 解析 "ip:port"；省略端口时用该子命令的默认端口。
std::pair<std::string, uint16_t> parseSpec(const std::string& spec, uint16_t defPort = 9511) {
    const size_t c = spec.rfind(':');
    if (c == std::string::npos) return {spec, defPort};
    return {spec.substr(0, c), static_cast<uint16_t>(std::atoi(spec.c_str() + c + 1))};
}

// ---------------------------------------------------------- 统一控制面 9511 ----
// PC 作为「受控端」：监听 9511，手机/TV 用现有 TvControllerClient 连入即可控 PC。
// 同时广播 APX1TV 信标，手机端设备列表自动出现本机。反向剪贴板默认开启。
int runCtrlServe(const std::string& name, uint16_t port, int seconds) {
    apxpc::wireless::Ctrl9511Server srv;
    if (!srv.start(port, "", name)) {
        std::printf("启动 9511 受控服务端失败：%s\n", srv.status().error.c_str());
        return 1;
    }
    std::printf("9511 受控服务端已启动（端口 %u，名称 %s）。手机/TV 用「TV 控制」连本机即可操控 PC。\n",
                static_cast<unsigned>(port), name.c_str());
    const auto t0 = std::chrono::steady_clock::now();
    while (true) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        const auto s = srv.status();
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - t0).count();
        std::printf("\r[%4llds] %s | 鼠标 %6llu 键盘 %6llu 触摸 %6llu 媒体 %5llu 剪贴板 %4llu 反向 %4llu 丢 %llu   ",
                    static_cast<long long>(ms / 1000),
                    s.connected ? "已连接" : "等待连接",
                    static_cast<unsigned long long>(s.mouse),
                    static_cast<unsigned long long>(s.keyboard),
                    static_cast<unsigned long long>(s.touch),
                    static_cast<unsigned long long>(s.consumer),
                    static_cast<unsigned long long>(s.clipboard),
                    static_cast<unsigned long long>(s.reverseClipboard),
                    static_cast<unsigned long long>(s.dropped));
        std::fflush(stdout);
        if (seconds > 0 && ms >= static_cast<long long>(seconds) * 1000) break;
    }
    srv.stop();
    std::puts("\n已停止");
    return 0;
}

// PC 作为「控制端」：连入手机/TV 的 9511 服务端。此处仅验证链路 + 打印反向剪贴板；
// 真正的「PC 转发本机输入」由捕获模块驱动本客户端的 send* 接口（见 input_injector_*）。
int runCtrlConnect(const std::string& host, uint16_t port, int seconds) {
    apxpc::wireless::Ctrl9511Client cli;
    cli.onReverseClipboard = [](const std::string& t) {
        std::printf("\n[反向剪贴板] %s\n", t.c_str());
        std::fflush(stdout);
    };
    if (!cli.connect(host, port, "")) {
        std::puts("连接 9511 受控端失败（确认对端已运行 ctrl9511-serve 且在同局域网）");
        return 1;
    }
    std::printf("已连入 %s:%u（反向剪贴板监听中）。Ctrl+C 退出。\n", host.c_str(),
                static_cast<unsigned>(port));
    const auto t0 = std::chrono::steady_clock::now();
    while (cli.ready()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - t0).count();
        if (seconds > 0 && ms >= static_cast<long long>(seconds) * 1000) break;
    }
    cli.disconnect();
    return 0;
}

// PC 作为遥控器：连入对端 9511 服务端后，把本机鼠标/键盘实时镜像过去（反向链路真正可用）。
// 对端可以是手机/TV/PC 的 ctrl9511-serve。按 Enter 停止（捕获线程在后台跑消息泵/事件环）。
int runCtrlRemote(const std::string& host, uint16_t port, int seconds) {
    apxpc::wireless::Ctrl9511Client cli;
    cli.onReverseClipboard = [](const std::string& t) {
        std::printf("\n[反向剪贴板] %s\n", t.c_str());
        std::fflush(stdout);
    };
    if (!cli.connect(host, port, "")) {
        std::puts("连接 9511 受控端失败（确认对端已运行 ctrl9511-serve 且同局域网）");
        return 1;
    }
    auto cap = apxpc::wireless::createPlatformCapturer(cli);
    if (!cap || !cap->start()) {
        std::puts("本地输入捕获启动失败：Windows 需消息泵/权限；macOS 需辅助功能权限；Linux 需 root");
        cli.disconnect();
        return 1;
    }
    std::printf("已连入 %s:%u，PC 本机鼠标/键盘正镜像到对端。", host.c_str(),
                static_cast<unsigned>(port));
    if (seconds > 0) {
        const auto t0 = std::chrono::steady_clock::now();
        while (cli.ready()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now() - t0).count();
            if (ms >= static_cast<long long>(seconds) * 1000) break;
        }
    } else {
        std::puts("按 Enter 停止……");
        std::cin.get();
    }
    cap->stop();
    cli.disconnect();
    std::puts("\n已停止");
    return 0;
}

// ---------------------------------------------------------------- 媒体通道 ----
// 与控制面并列的第二条连接（手机 9502）：副屏/音箱下行，麦克风上行。
// 这条 CLI 是调试用 —— 桌面端面板里是同一套 apxpc::media::MediaSession。

/// 跑一段媒体会话并周期打印各路计数（「哪条流真的在走」的客观证据）。
/// 设 tone=true 时顺便推一路 440Hz 正弦到「音箱」流，便于听音验证下行通路。
int runMediaCli(const std::string& host, uint16_t port, int seconds, bool tone) {
    apxpc::media::MediaSession session;
    if (!session.connect(host, port)) {
        std::printf("连接 %s:%u 失败：%s\n", host.c_str(), static_cast<unsigned>(port),
                    session.status().error.c_str());
        return 1;
    }
    std::printf("媒体通道已连接 %s:%u\n", host.c_str(), static_cast<unsigned>(port));

    const auto t0 = std::chrono::steady_clock::now();
    double tonePhase = 0.0;
    while (true) {
        // 音箱下行：48k/立体声/16bit，每 10ms 一片（192 字节有符号 PCM），
        // 与 Android 侧 AudioTrack 的分片粒度一致（见 MediaSession 头注释）
        if (tone) {
            constexpr int kBytes = apxpc::media::kAudioBytesPerMs * 10;
            int16_t pcm[kBytes / 2];
            for (int i = 0; i < kBytes / 2; i += 2) {
                const auto v = static_cast<int16_t>(6000.0 * std::sin(tonePhase));
                tonePhase += 2.0 * 3.14159265358979 * 440.0 / apxpc::media::kAudioSampleRate;
                if (tonePhase > 6.283185307) tonePhase -= 6.283185307;
                pcm[i] = v;       // L
                pcm[i + 1] = v;   // R
            }
            session.sendFrame(apxpc::media::kStreamAudio,
                              reinterpret_cast<const uint8_t*>(pcm), kBytes, 0);
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
        }

        const auto st = session.status();
        const auto c = session.counters();
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - t0)
                            .count();
        std::printf("\r[%4llds] %-4s | 副屏 %6llu帧 音箱 %6llu帧 | 麦克风 %6llu帧 | 丢 %llu 重同步 %llu   ",
                    static_cast<long long>(ms / 1000),
                    st.connected ? "在线" : "断开",
                    static_cast<unsigned long long>(c.videoFrames),
                    static_cast<unsigned long long>(c.audioFrames),
                    static_cast<unsigned long long>(c.micFrames),
                    static_cast<unsigned long long>(c.dropped),
                    static_cast<unsigned long long>(c.resync));
        std::fflush(stdout);
        if (!st.connected) {
            std::puts("\n媒体链路已断开");
            return 2;
        }
        if (seconds > 0 && ms >= static_cast<long long>(seconds) * 1000) break;
    }
    std::puts("");
    return 0;
}

// ------------------------------------------------------------------ 音箱 ----
// 把 PC 的**系统声音**（WASAPI loopback）送到手机扬声器。这是「音箱」的真实音源 ——
// 面板里的同名开关走的是同一套 apxpc::media::AudioCapture。
// 这条 CLI 的价值是：不用开面板就能确认「采集到的是本机正在放的声音」。
int runSpeakerCli(const std::string& host, uint16_t port, int seconds, int deviceIdx) {
    // 先列一遍可选的播放设备 —— "采错了设备"是这块最常见的坑，得让用户有据可依
    const auto devs = apxpc::media::AudioCapture::listRenderDevices();
    std::puts("可选的播放设备（不指定序号 = 跟随系统默认播放设备）：");
    if (devs.empty()) std::puts("  （没有枚举到任何播放设备）");
    for (size_t i = 0; i < devs.size(); ++i) {
        std::printf("  [%zu] %s%s\n", i, devs[i].name.c_str(),
                    devs[i].isDefault ? "   · 系统默认" : "");
    }

    apxpc::media::AudioCaptureOptions opt;
    if (deviceIdx >= 0) {
        if (deviceIdx >= static_cast<int>(devs.size())) {
            std::printf("设备序号 %d 不存在（共 %zu 个）\n", deviceIdx, devs.size());
            return 1;
        }
        opt.deviceId = devs[static_cast<size_t>(deviceIdx)].id;
        std::printf("将采集：[%d] %s\n", deviceIdx, devs[static_cast<size_t>(deviceIdx)].name.c_str());
    } else {
        std::printf("将采集：跟随系统默认（当前 %s）\n",
                    apxpc::media::AudioCapture::defaultRenderDeviceName().c_str());
    }

    apxpc::media::MediaSession session;
    if (!session.connect(host, port)) {
        std::printf("连接 %s:%u 失败：%s\n", host.c_str(), static_cast<unsigned>(port),
                    session.status().error.c_str());
        return 1;
    }

    apxpc::media::AudioCapture cap;
    std::string err;
    if (!cap.start(&session, opt, &err)) {
        std::printf("音箱采集启动失败：%s\n", err.c_str());
        return 1;
    }
    std::printf("音箱已开始：把本机系统声音送到手机扬声器（%ds）\n", seconds);

    const auto t0 = std::chrono::steady_clock::now();
    while (true) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        const auto s = cap.status();
        // 音量条：让「链路在跑但系统本身静音」一眼可辨，不用去猜
        const int bars = static_cast<int>(s.peak * 20.0 + 0.5);
        std::string meter(20, ' ');
        for (int i = 0; i < bars && i < 20; ++i) meter[i] = '#';
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - t0)
                            .count();
        std::printf("\r[%4llds] %llu 片 · %llu KB · 丢 %llu · 峰值 [%s] %.2f   ",
                    static_cast<long long>(ms / 1000),
                    static_cast<unsigned long long>(s.framesSent),
                    static_cast<unsigned long long>(s.bytesSent / 1024),
                    static_cast<unsigned long long>(s.dropped), meter.c_str(), s.peak);
        std::fflush(stdout);
        if (!session.status().connected) {
            std::puts("\n媒体链路已断开");
            cap.stop();
            return 2;
        }
        if (seconds > 0 && ms >= static_cast<long long>(seconds) * 1000) break;
    }
    cap.stop();
    std::puts("");
    return 0;
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

        // ---- 媒体通道（副屏 / 音箱 / 麦克风）----
        if (cmd == "media") {
            if (argc < 3) {
                std::fputs("用法：apxhost media <手机IP>[:端口] [秒数] [tone]\n", stderr);
                return 1;
            }
            const auto hp = parseSpec(argv[2], apxpc::media::kMediaPort);
            const int secs = argc > 3 ? std::atoi(argv[3]) : 0;
            const bool tone = argc > 4 && std::strcmp(argv[4], "tone") == 0;
            return runMediaCli(hp.first, hp.second, secs, tone);
        }
        if (cmd == "speaker") {
            if (argc < 3) {
                std::fputs("用法：apxhost speaker <手机IP>[:端口] [秒数]\n", stderr);
                return 1;
            }
            const auto hp = parseSpec(argv[2], apxpc::media::kMediaPort);
            const int secs = argc > 3 ? std::atoi(argv[3]) : 0;
            const int devIdx = argc > 4 ? std::atoi(argv[4]) : -1;
            return runSpeakerCli(hp.first, hp.second, secs, devIdx);
        }

        // ---- 统一控制面 9511（三端互通）----
        if (cmd == "ctrl9511-serve") {
            const int port = argc > 2 ? std::atoi(argv[2]) : 9511;
            const std::string name = argc > 3 ? argv[3] : "APX-PC";
            const int secs = argc > 4 ? std::atoi(argv[4]) : 0;
            return runCtrlServe(name, static_cast<uint16_t>(port), secs);
        }
        if (cmd == "ctrl9511-connect") {
            if (argc < 3) {
                std::fputs("用法：apxhost ctrl9511-connect <host>[:端口] [秒数]\n", stderr);
                return 1;
            }
            const auto hp = parseSpec(argv[2], 9511);
            const int secs = argc > 3 ? std::atoi(argv[3]) : 0;
            return runCtrlConnect(hp.first, hp.second, secs);
        }
        if (cmd == "ctrl9511-remote") {
            if (argc < 3) {
                std::fputs("用法：apxhost ctrl9511-remote <host>[:端口] [秒数]\n", stderr);
                return 1;
            }
            const auto hp = parseSpec(argv[2], 9511);
            const int secs = argc > 3 ? std::atoi(argv[3]) : 0;
            return runCtrlRemote(hp.first, hp.second, secs);
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
