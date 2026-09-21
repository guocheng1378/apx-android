// ============================================================================
// Linux 传感器后端（IIO sysfs）
//
// Linux 内核的 hid-sensors 驱动会把 HID Sensor TLC 暴露成
// /sys/bus/iio/devices/iio:deviceX，属性形如：
//     in_accel_x_raw / in_accel_scale
//     in_anglvel_x_raw / in_anglvel_scale
//     in_illuminance_raw / in_illuminance_scale
//     in_pressure_raw / in_pressure_scale
//     in_temp_raw / in_temp_scale
//     in_humidityrelative_raw / in_humidityrelative_scale
//     in_proximity_raw
//
// 真值 = raw * scale（+ offset，若存在）。**必须读 scale**，否则数值没有物理意义。
//
// 状态：**未经真机验证**（本机为 Windows）。逻辑依据 Linux IIO 的 sysfs ABI。
//       等有 Linux + 已 root 真机的环境时，重点验证：
//         ls /sys/bus/iio/devices/ 下是否真的出现 iio:deviceX
// ============================================================================

#include "apxpc/sensors/sensor_reader.hpp"

#if !defined(_WIN32)

#include <dirent.h>
#include <sys/types.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

namespace apxpc::sensors {
namespace {

constexpr const char* kIioRoot = "/sys/bus/iio/devices";

bool readFileTrim(const std::string& path, std::string& out) {
    std::ifstream f(path);
    if (!f.is_open()) return false;
    std::getline(f, out);
    while (!out.empty() && (out.back() == '\n' || out.back() == '\r' || out.back() == ' ')) {
        out.pop_back();
    }
    return true;
}

bool readDouble(const std::string& path, double& out) {
    std::string s;
    if (!readFileTrim(path, s) || s.empty()) return false;
    char* end = nullptr;
    const double v = std::strtod(s.c_str(), &end);
    if (end == s.c_str()) return false;
    out = v;
    return true;
}

// 一个 IIO 设备上的"通道"描述：前缀（in_accel 等）+ 轴后缀（x/y/z 或空）
struct ChannelSpec {
    const char* prefix;      // sysfs 前缀
    const char* suffix;      // "_x" / "_y" / "_z" / ""
    SensorKind  kind;
};

// 覆盖 apx 关心的传感器种类。顺序即输出顺序。
const ChannelSpec kChannels[] = {
    {"in_accel",         "_x", SensorKind::Accel},
    {"in_accel",         "_y", SensorKind::Accel},
    {"in_accel",         "_z", SensorKind::Accel},
    {"in_anglvel",       "_x", SensorKind::Gyro},
    {"in_anglvel",       "_y", SensorKind::Gyro},
    {"in_anglvel",       "_z", SensorKind::Gyro},
    {"in_magn",          "_x", SensorKind::Mag},
    {"in_magn",          "_y", SensorKind::Mag},
    {"in_magn",          "_z", SensorKind::Mag},
    {"in_illuminance",   "",   SensorKind::Light},
    {"in_pressure",      "",   SensorKind::Pressure},
    {"in_temp",          "",   SensorKind::Temperature},
    {"in_humidityrelative", "", SensorKind::Humidity},
    {"in_proximity",     "",   SensorKind::Proximity},
};

class IioReader final : public ISensorReader {
public:
    std::string backendName() const override { return "linux-iio"; }

    std::vector<SensorInfo> list() override {
        std::vector<SensorInfo> out;
        for (const auto& dev : devices()) {
            for (const auto& ch : kChannels) {
                if (!hasChannel(dev, ch)) continue;
                if (alreadyListed(out, ch.kind)) continue;  // 每个种类只列一次

                SensorInfo info;
                info.kind = ch.kind;
                info.apxId = apxIdOfKind(ch.kind);
                info.name = kindName(ch.kind);
                info.osName = dev.name;   // iio:deviceX
                info.available = true;

                // 采样频率（Hz）→ 最小间隔（ms）。取不到保持 0。
                double freq = 0;
                if (readDouble(dev.path + "/sampling_frequency", freq) && freq > 0) {
                    info.minIntervalMs = 1000.0 / freq;
                }
                out.push_back(std::move(info));
            }
        }
        return out;
    }

    SensorValue read(uint8_t apxId) override {
        SensorValue v;
        v.apxId = apxId;
        const SensorKind kind = kindOfApxId(apxId);

        if (kind == SensorKind::Accel || kind == SensorKind::Gyro || kind == SensorKind::Mag) {
            const char* prefix = (kind == SensorKind::Accel) ? "in_accel"
                               : (kind == SensorKind::Gyro)  ? "in_anglvel"
                                                             : "in_magn";
            double xyz[3]{0, 0, 0};
            bool ok = true;
            for (int i = 0; i < 3 && ok; ++i) {
                const char axis = static_cast<char>('x' + i);
                ok = readScaled(prefix, std::string("_") + axis, xyz[i]);
            }
            if (ok) {
                v.count = 3;
                v.v[0] = xyz[0]; v.v[1] = xyz[1]; v.v[2] = xyz[2];
                v.valid = true;
            }
        } else {
            const char* prefix = nullptr;
            switch (kind) {
                case SensorKind::Light:       prefix = "in_illuminance";     break;
                case SensorKind::Pressure:    prefix = "in_pressure";        break;
                case SensorKind::Temperature: prefix = "in_temp";            break;
                case SensorKind::Humidity:    prefix = "in_humidityrelative";break;
                case SensorKind::Proximity:   prefix = "in_proximity";       break;
                default: break;
            }
            if (prefix != nullptr) {
                double val = 0;
                if (readScaled(prefix, "", val)) {
                    v.count = 1;
                    v.v[0] = val;
                    v.valid = true;
                }
            }
        }

        if (v.valid) v.tsNs = monotonicNs();
        return v;
    }

    std::vector<SensorValue> readAll() override {
        std::vector<SensorValue> out;
        for (const auto& info : list()) out.push_back(read(info.apxId));
        return out;
    }

private:
    struct Device {
        std::string name;  // iio:deviceX
        std::string path;  // 完整路径
    };

    // 扫描 /sys/bus/iio/devices。用 readdir 而非 std::filesystem，
    // 避免依赖较新的 libstdc++（老发行版上的兼容性更好）。
    std::vector<Device> devices() {
        std::vector<Device> out;
        DIR* d = opendir(kIioRoot);
        if (d == nullptr) return out;  // 无 IIO 子系统（内核未启用 hid-sensors）
        while (dirent* e = readdir(d)) {
            if (std::strncmp(e->d_name, "iio:device", 10) != 0) continue;
            Device dev;
            dev.name = e->d_name;
            dev.path = std::string(kIioRoot) + "/" + e->d_name;
            out.push_back(std::move(dev));
        }
        closedir(d);
        return out;
    }

    static bool exists(const std::string& p) {
        std::ifstream f(p);
        return f.is_open();
    }

    static bool hasChannel(const Device& dev, const ChannelSpec& ch) {
        return exists(dev.path + "/" + ch.prefix + ch.suffix + "_raw");
    }

    static bool alreadyListed(const std::vector<SensorInfo>& list, SensorKind k) {
        for (const auto& i : list) if (i.kind == k) return true;
        return false;
    }

    // 真值 = raw * scale（+ offset，若有）。缺 scale 时按 1.0 处理并不可靠 ——
    // 因此 scale 缺失直接判为读取失败，宁可报"读不到"也不给错值。
    bool readScaled(const std::string& prefix, const std::string& suffix, double& out) {
        for (const auto& dev : devices()) {
            const std::string base = dev.path + "/" + prefix + suffix;
            double raw = 0, scale = 0;
            if (!readDouble(base + "_raw", raw)) continue;
            if (!readDouble(base + "_scale", scale)) continue;

            double offset = 0;
            readDouble(base + "_offset", offset);  // 可选，读不到就当 0

            out = raw * scale + offset;
            return true;
        }
        return false;
    }

    static uint64_t monotonicNs() {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
               static_cast<uint64_t>(ts.tv_nsec);
    }
};

}  // namespace

std::unique_ptr<ISensorReader> createIioReader() {
    return std::make_unique<IioReader>();
}

}  // namespace apxpc::sensors

#endif  // !_WIN32
