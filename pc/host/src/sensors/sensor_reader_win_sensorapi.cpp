// ============================================================================
// Windows 传感器后端（经典 Sensor API / COM）
//
// 用 ISensorManager 遍历**系统已集成的传感器**并读值。
// 关键：这是"PC 端零自研驱动"主张的验证手段 —— 我们读的是 OS 自己的接口，
// 而不是自己解析 HID 报告。手机 Gadget 的 HID Sensor TLC 被 Windows 内置的
// SensorsHIDClassDriver 接管后，就会出现在这里。
//
// 为什么不只用 C++/WinRT：WinRT 需要 Windows SDK 的 cppwinrt 生成头，
// 构建门槛更高。经典 COM API 只要 windows.h + sensorsapi.h 即可，
// 作为**兜底后端**更稳。两者可共存（createSensorReader("auto") 优先 WinRT）。
//
// 状态：**未经编译验证**（本机无 Windows SDK）。逻辑依据 MSDN 的
//       ISensorManager / ISensor / ISensorDataReport 接口契约编写。
// ============================================================================

#include "apxpc/sensors/sensor_reader.hpp"

#if defined(_WIN32)

#include <windows.h>

#include <sensorsapi.h>
#include <sensors.h>
#include <propvarutil.h>

#include <comdef.h>
#include <objbase.h>

#include <string>
#include <vector>

#include "apxpc/log.hpp"

namespace apxpc::sensors {
namespace {

// COM 智能指针（避免引入手写 Release 的疏漏；_com_ptr_t 由 comdef.h 提供）
using SensorManagerPtr  = _com_ptr_t<_com_IIID<ISensorManager, &__uuidof(ISensorManager)>>;
using SensorCollectionPtr = _com_ptr_t<_com_IIID<ISensorCollection, &__uuidof(ISensorCollection)>>;
using SensorPtr         = _com_ptr_t<_com_IIID<ISensor, &__uuidof(ISensor)>>;
using SensorDataReportPtr = _com_ptr_t<_com_IIID<ISensorDataReport, &__uuidof(ISensorDataReport)>>;

// COM 初始化守卫：本后端可能被独立工具调用，不能假设调用方已初始化 COM。
class ComGuard {
public:
    ComGuard() {
        const HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        // S_FALSE 表示本线程已初始化过；RPC_E_CHANGED_MODE 表示已被别人以
        // 单线程套间初始化 —— 两者都不算失败，但只有前者需要我们在析构时反初始化。
        owned_ = SUCCEEDED(hr) && hr != S_FALSE;
    }
    ~ComGuard() { if (owned_) CoUninitialize(); }
    ComGuard(const ComGuard&) = delete;
    ComGuard& operator=(const ComGuard&) = delete;
private:
    bool owned_{false};
};

// 由 Sensor API 的 SENSOR_TYPE_* GUID 映射回 apx 的 SensorKind
SensorKind kindOfSensorType(const GUID& type) {
    if (IsEqualGUID(type, SENSOR_TYPE_ACCELEROMETER_3D))       return SensorKind::Accel;
    if (IsEqualGUID(type, SENSOR_TYPE_GYROMETER_3D))           return SensorKind::Gyro;
    if (IsEqualGUID(type, SENSOR_TYPE_COMPASS_3D))             return SensorKind::Mag;
    if (IsEqualGUID(type, SENSOR_TYPE_AMBIENT_LIGHT))          return SensorKind::Light;
    if (IsEqualGUID(type, SENSOR_TYPE_AMBIENT_TEMPERATURE))    return SensorKind::Temperature;
    if (IsEqualGUID(type, SENSOR_TYPE_ATMOSPHERIC_PRESSURE))   return SensorKind::Pressure;
    if (IsEqualGUID(type, SENSOR_TYPE_HUMIDITY))               return SensorKind::Humidity;
    if (IsEqualGUID(type, SENSOR_TYPE_DEVICE_ORIENTATION))     return SensorKind::Orientation;
    if (IsEqualGUID(type, SENSOR_TYPE_INCLINOMETER_3D))        return SensorKind::Inclinometer;
    if (IsEqualGUID(type, SENSOR_TYPE_PROXIMITY))              return SensorKind::Proximity;
    if (IsEqualGUID(type, SENSOR_TYPE_PEDOMETER))              return SensorKind::StepCounter;
    return SensorKind::Custom;
}

// Windows 上"传感器是否可用"由 GetState 给出（SENSOR_STATE_READY 才可读）
bool sensorReady(ISensor* sensor) {
    SensorState state = SENSOR_STATE_ERROR;
    if (FAILED(sensor->GetState(&state))) return false;
    // SENSOR_STATE_READY / SENSOR_STATE_ACCESS_DENIED / NOT_SUPPORTED / ERROR
    return state == SENSOR_STATE_READY;
}

// 从 ISensorDataReport 取一个 FLOAT 值。取不到就返回 false（不改动 out）。
bool readFloat(ISensorDataReport* report, REFPROPERTYKEY key, double& out) {
    PROPVARIANT pv;
    PropVariantInit(&pv);
    const HRESULT hr = report->GetSensorValue(key, &pv);
    bool ok = false;
    if (SUCCEEDED(hr)) {
        if (pv.vt == VT_R4) { out = static_cast<double>(pv.fltVal); ok = true; }
        else if (pv.vt == VT_R8) { out = pv.dblVal; ok = true; }
    }
    PropVariantClear(&pv);
    return ok;
}

class SensorApiReader final : public ISensorReader {
public:
    std::string backendName() const override { return "win-sensorapi"; }

    std::vector<SensorInfo> list() override {
        std::vector<SensorInfo> out;
        SensorCollectionPtr all;
        if (!enumerate(&all)) return out;

        ULONG count = 0;
        if (FAILED(all->GetCount(&count))) return out;

        for (ULONG i = 0; i < count; ++i) {
            SensorPtr sensor;
            if (FAILED(all->GetAt(i, &sensor)) || sensor == nullptr) continue;

            SensorInfo info;
            info.available = sensorReady(sensor);

            // 友好名（系统传感器面板里显示的名字）
            BSTR name = nullptr;
            if (SUCCEEDED(sensor->GetFriendlyName(&name)) && name != nullptr) {
                info.osName = narrow(name);
                SysFreeString(name);
            }

            // 传感器类型 → 我们的 SensorKind
            GUID type{};
            if (SUCCEEDED(sensor->GetType(&type))) {
                info.kind = kindOfSensorType(type);
            }

            // PC 侧无法直接得知手机的 apxId —— 只能按种类反查。
            // 这也是为什么 apxIdOfKind() 存在：两端用"种类"作为对齐依据，
            // 而不是编号（编号是手机侧的内部分配，PC 看不见）。
            info.apxId = apxIdOfKind(info.kind);

            info.name = info.osName.empty() ? kindName(info.kind) : info.osName;

            // 最小间隔（毫秒）。取不到就保持 0（未知）。
            PROPVARIANT pv;
            PropVariantInit(&pv);
            if (SUCCEEDED(sensor->GetProperty(SENSOR_PROPERTY_MIN_REPORT_INTERVAL, &pv)) &&
                pv.vt == VT_UI4) {
                info.minIntervalMs = static_cast<double>(pv.ulVal);
            }
            PropVariantClear(&pv);

            out.push_back(std::move(info));
        }
        return out;
    }

    SensorValue read(uint8_t apxId) override {
        SensorValue v;
        v.apxId = apxId;

        const SensorKind kind = kindOfApxId(apxId);
        SensorPtr sensor = findByKind(kind);
        if (sensor == nullptr) return v;

        SensorDataReportPtr report;
        if (FAILED(sensor->GetData(&report)) || report == nullptr) return v;

        // 按种类取对应的数据字段。Windows 的传感器数据用 SENSOR_DATA_TYPE_* 键。
        switch (kind) {
            case SensorKind::Accel:
            case SensorKind::AccelUncal:
                v.count = 3;
                v.valid = readFloat(report, SENSOR_DATA_TYPE_ACCELERATION_X_G, v.v[0]) &&
                          readFloat(report, SENSOR_DATA_TYPE_ACCELERATION_Y_G, v.v[1]) &&
                          readFloat(report, SENSOR_DATA_TYPE_ACCELERATION_Z_G, v.v[2]);
                break;
            case SensorKind::Gyro:
            case SensorKind::GyroUncal:
                v.count = 3;
                v.valid = readFloat(report, SENSOR_DATA_TYPE_ANGULAR_VELOCITY_X_DEG_PER_S, v.v[0]) &&
                          readFloat(report, SENSOR_DATA_TYPE_ANGULAR_VELOCITY_Y_DEG_PER_S, v.v[1]) &&
                          readFloat(report, SENSOR_DATA_TYPE_ANGULAR_VELOCITY_Z_DEG_PER_S, v.v[2]);
                break;
            case SensorKind::Mag:
            case SensorKind::MagUncal:
                v.count = 3;
                v.valid = readFloat(report, SENSOR_DATA_TYPE_MAGNETIC_HEADING_X_DEGREES, v.v[0]) &&
                          readFloat(report, SENSOR_DATA_TYPE_MAGNETIC_HEADING_Y_DEGREES, v.v[1]) &&
                          readFloat(report, SENSOR_DATA_TYPE_MAGNETIC_HEADING_Z_DEGREES, v.v[2]);
                break;
            case SensorKind::Light:
                v.count = 1;
                v.valid = readFloat(report, SENSOR_DATA_TYPE_LIGHT_LEVEL_LUX, v.v[0]);
                break;
            case SensorKind::Temperature:
                v.count = 1;
                v.valid = readFloat(report, SENSOR_DATA_TYPE_TEMPERATURE_CELSIUS, v.v[0]);
                break;
            case SensorKind::Pressure:
                v.count = 1;
                v.valid = readFloat(report, SENSOR_DATA_TYPE_ATMOSPHERIC_PRESSURE_KPA, v.v[0]);
                break;
            case SensorKind::Humidity:
                v.count = 1;
                v.valid = readFloat(report, SENSOR_DATA_TYPE_HUMIDITY_PERCENT, v.v[0]);
                break;
            case SensorKind::Proximity:
                v.count = 1;
                v.valid = readFloat(report, SENSOR_DATA_TYPE_DISTANCE_METERS, v.v[0]);
                break;
            default:
                // 方向 / 倾角 / 计步等：本项目不依赖，先标记为不可读，
                // 避免给出看似有效实则错误的数据。
                v.valid = false;
                break;
        }

        if (v.valid) {
            // PC 侧单调时基，供 ClockSync 与手机时间戳对齐（PROTOCOL §1）
            v.tsNs = apxpc::monotonicNs();
        }
        return v;
    }

    std::vector<SensorValue> readAll() override {
        std::vector<SensorValue> out;
        for (const auto& info : list()) {
            if (!info.available) continue;
            out.push_back(read(info.apxId));
        }
        return out;
    }

private:
    // 枚举全部传感器。失败时 lastError_ 里留有原因（便于工具打印）。
    bool enumerate(ISensorCollection** out) {
        SensorManagerPtr mgr;
        HRESULT hr = mgr.CreateInstance(__uuidof(SensorManager), nullptr, CLSCTX_INPROC_SERVER);
        if (FAILED(hr)) {
            lastError_ = "CoCreateInstance(SensorManager) 失败：" + hex(hr);
            return false;
        }
        hr = mgr->GetSensorsByCategory(SENSOR_CATEGORY_ALL, out);
        if (FAILED(hr)) {
            lastError_ = "GetSensorsByCategory 失败：" + hex(hr);
            return false;
        }
        return true;
    }

    SensorPtr findByKind(SensorKind kind) {
        SensorCollectionPtr all;
        if (!enumerate(&all)) return nullptr;

        ULONG count = 0;
        if (FAILED(all->GetCount(&count))) return nullptr;

        for (ULONG i = 0; i < count; ++i) {
            SensorPtr sensor;
            if (FAILED(all->GetAt(i, &sensor)) || sensor == nullptr) continue;
            GUID type{};
            if (FAILED(sensor->GetType(&type))) continue;
            if (kindOfSensorType(type) == kind) return sensor;
        }
        return nullptr;
    }

    static std::string narrow(const wchar_t* w) {
        if (w == nullptr) return {};
        const int need = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
        if (need <= 1) return {};
        std::string s(static_cast<size_t>(need - 1), '\0');
        WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), need, nullptr, nullptr);
        return s;
    }

    static std::string hex(long hr) {
        char buf[16];
        std::snprintf(buf, sizeof(buf), "0x%08lX", static_cast<unsigned long>(hr));
        return buf;
    }

    std::string lastError_;
};

}  // namespace

std::unique_ptr<ISensorReader> createSensorApiReader() {
    return std::make_unique<SensorApiReader>();
}

}  // namespace apxpc::sensors

#endif  // _WIN32
