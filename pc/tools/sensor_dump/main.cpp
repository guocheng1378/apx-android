// ============================================================================
// apx_sensor_dump —— MS1 验收工具
//
// 用途：确认**手机传感器已经被 Windows/Linux 当作本机传感器**。
//
// 这是 MS1 里程碑的直接判据：
//   手机挂上 Gadget → HID Sensor TLC 被系统内置类驱动接管
//     → Windows：Settings > 系统 > 传感器 / Windows.Devices.Sensors 可见
//     → Linux  ：hid-sensors 映射为 /sys/bus/iio/devices/iio:deviceX
//   本工具遍历这些**系统已集成**的传感器并打印实时读数。
//
// 关键设计：**只读 OS 已抽象好的传感器，不解析私有 HID 报告**。
// 这正是"PC 端零自研驱动"的验证方式 —— 如果我们还得自己解析报告，
// 说明免驱集成没成功。
//
// 用法：
//   apx_sensor_dump                 # 列出全部传感器并读一次
//   apx_sensor_dump --watch         # 持续输出读数
//   apx_sensor_dump --interval 100  # 采样间隔（毫秒，默认 500）
//   apx_sensor_dump --expect-accel  # 专用于 MS1 判定：只关心加速度计是否可用
// ============================================================================

#include <apxpc/sensors/sensor_reader.hpp>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {

struct Options {
    bool   watch{false};
    bool   expectAccel{false};
    int    intervalMs{500};
    int    iterations{1};        // watch 模式下 0 = 无限
    std::string backend{"auto"};
};

void printUsage() {
    std::puts(
        "apx_sensor_dump —— 列出并读取系统已集成的传感器（MS1 验收工具）\n"
        "\n"
        "用法：\n"
        "  apx_sensor_dump [选项]\n"
        "\n"
        "选项：\n"
        "  --watch              持续输出读数（默认只读一次）\n"
        "  --interval <ms>      采样间隔，默认 500\n"
        "  --expect-accel       MS1 判定模式：只关心加速度计，退出码即结论\n"
        "  --backend <name>     auto | winrt | sensorapi | iio | null\n"
        "  -h, --help           显示本帮助\n"
        "\n"
        "退出码：\n"
        "  0  成功（--expect-accel 时表示加速度计可用且有读数）\n"
        "  1  参数错误\n"
        "  2  未发现传感器（Gadget 未挂载或系统驱动未接管）\n"
        "  3  --expect-accel 模式下加速度计不可用\n");
}

bool parseArgs(int argc, char** argv, Options& o) {
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "-h" || a == "--help") { printUsage(); std::exit(0); }
        else if (a == "--watch") o.watch = true;
        else if (a == "--expect-accel") o.expectAccel = true;
        else if (a == "--interval" && i + 1 < argc) o.intervalMs = std::atoi(argv[++i]);
        else if (a == "--backend" && i + 1 < argc) o.backend = argv[++i];
        else { std::fprintf(stderr, "未知参数：%s\n", a.c_str()); return false; }
    }
    if (o.intervalMs < 1) { std::fprintf(stderr, "--interval 必须 >= 1\n"); return false; }
    return true;
}

// 一行一条读数。列宽固定便于 diff/比对。
void printValue(const apxpc::sensors::SensorInfo& info,
                const apxpc::sensors::SensorValue& v) {
    std::printf("  %-22s [apx 0x%02X] ", info.name.c_str(), static_cast<unsigned>(info.apxId));
    if (!v.valid) { std::puts("(无读数)"); return; }
    std::printf("ts=%llu", static_cast<unsigned long long>(v.tsNs));
    for (int i = 0; i < v.count; ++i) std::printf("  v%d=%.4f", i, v.v[i]);
    std::putchar('\n');
}

int listAndRead(apxpc::sensors::ISensorReader& reader, const Options& o) {
    auto infos = reader.list();
    if (infos.empty()) {
        std::puts("未发现任何系统集成传感器。");
        std::puts("");
        std::puts("排查清单：");
        std::puts("  1) 手机是否已挂载 Gadget？（手机端 App 点\"切换到外设模式\"）");
        std::puts("  2) Windows 设备管理器是否出现 VID_1D6B 复合设备？");
        std::puts("  3) 其中是否有 \"HID 传感器集合\" 与 \"HID 3D 加速度传感器\"？");
        std::puts("     若加速度计状态为 Error（Code 10），说明描述符缺 Feature Report");
        std::puts("     （Report Interval / Reporting State），见 docs/REALDEVICE-NOTES.md。");
        std::puts("  4) Linux 下检查：ls /sys/bus/iio/devices/");
        return 2;
    }

    std::printf("后端：%s    发现 %zu 个传感器\n\n",
                reader.backendName().c_str(), infos.size());

    // 逐个读一次（read() 单读，readAll() 批量）
    auto values = reader.readAll();

    const int iters = o.watch ? o.iterations : 1;
    for (int n = 0; iters == 0 || n < iters; ++n) {
        if (o.watch) {
            std::printf("---- 第 %d 次 ----\n", n + 1);
            values = reader.readAll();
        }
        for (const auto& info : infos) {
            apxpc::sensors::SensorValue v{};
            bool found = false;
            for (const auto& cand : values) {
                if (cand.apxId == info.apxId) { v = cand; found = true; break; }
            }
            if (!found) v = reader.read(info.apxId);
            printValue(info, v);
        }
        if (!o.watch) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(o.intervalMs));
    }
    return 0;
}

// MS1 判定：加速度计是否可用且有真实读数
int expectAccel(apxpc::sensors::ISensorReader& reader) {
    namespace s = apxpc::sensors;
    auto infos = reader.list();

    const s::SensorInfo* accel = nullptr;
    for (const auto& i : infos) {
        if (i.kind == s::SensorKind::Accel) { accel = &i; break; }
    }

    std::puts("=== MS1 判定：加速度计 ===");
    if (accel == nullptr) {
        std::puts("结果：FAIL —— 系统里找不到\"3D 加速度计\"设备。");
        std::puts("");
        std::puts("若设备管理器里该设备存在但状态为 Error，典型原因是 HID 报告描述符");
        std::puts("缺少 Windows Sensor 类驱动必需的 Feature Report：");
        std::puts("  Report State (0x20:0x0316) / Change Sensitivity (0x030F)");
        std::puts("  / Report Interval (0x030E)");
        return 3;
    }

    std::printf("设备名：%s\n  OS 名：%s\n  最小间隔：%.1f ms\n",
                accel->name.c_str(), accel->osName.c_str(), accel->minIntervalMs);

    // 连读几次，确认不是"设备在但读不出值"
    int okCount = 0;
    constexpr int kProbe = 5;
    for (int i = 0; i < kProbe; ++i) {
        const auto v = reader.read(accel->apxId);
        if (v.valid && v.count >= 3) {
            ++okCount;
            std::printf("  样本 %d: x=%.4f y=%.4f z=%.4f\n", i + 1, v.v[0], v.v[1], v.v[2]);
        } else {
            std::printf("  样本 %d: 无效\n", i + 1);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    if (okCount == 0) {
        std::puts("结果：FAIL —— 设备存在但读不到有效读数（描述符字段可能不合规）。");
        return 3;
    }
    std::printf("结果：PASS —— %d/%d 次采样成功，加速度计已作为本机传感器工作。\n", okCount, kProbe);
    std::puts("");
    std::puts("MS1 达成：手机传感器已通过 HID 免驱集成进 PC。");
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    Options o;
    if (!parseArgs(argc, argv, o)) { printUsage(); return 1; }

    auto reader = apxpc::sensors::createSensorReader(o.backend);
    if (!reader) {
        std::fprintf(stderr, "创建传感器读取器失败（backend=%s）\n", o.backend.c_str());
        return 2;
    }
    return o.expectAccel ? expectAccel(*reader) : listAndRead(*reader, o);
}
