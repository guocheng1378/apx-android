#pragma once
// USB 设备模型的平台无关表示。Windows(SUSE/SetupAPI) 与 Linux(sysfs/libusb) 产出同一份结构。
#include <cstdint>
#include <string>
#include <vector>

#include <apx/hid_layout.h>
#include <apx/sensors_id.h>

namespace apxpc::usb {

// ================================ 速度 ====================================
// ARCHITECTURE §6.1 速度门禁：非 super-speed 必须降级并告警
enum class Speed : uint8_t { Unknown = 0, Low, Full, High, Super, SuperPlus };

inline const char* speedName(Speed s) noexcept {
    switch (s) {
        case Speed::Low:       return "low-speed(1.5Mbps)";
        case Speed::Full:      return "full-speed(12Mbps)";
        case Speed::High:      return "high-speed(480Mbps)";
        case Speed::Super:     return "super-speed(5Gbps)";
        case Speed::SuperPlus: return "super-speed-plus(10Gbps)";
        default:               return "unknown";
    }
}

// 与 PROTOCOL §2.7 linkSpeed 字段互转
inline uint8_t toLinkSpeedField(Speed s) noexcept {
    switch (s) {
        case Speed::Full:      return apx::kSpeedFull;
        case Speed::High:      return apx::kSpeedHigh;
        case Speed::Super:     return apx::kSpeedSuper;
        case Speed::SuperPlus: return apx::kSpeedSuperPlus;
        default:               return apx::kSpeedUnknown;
    }
}
inline Speed fromLinkSpeedField(uint8_t v) noexcept {
    switch (v) {
        case apx::kSpeedFull:      return Speed::Full;
        case apx::kSpeedHigh:      return Speed::High;
        case apx::kSpeedSuper:     return Speed::Super;
        case apx::kSpeedSuperPlus: return Speed::SuperPlus;
        default:                   return Speed::Unknown;
    }
}

// ============================ 类码常量 ====================================
enum class ClassCode : uint8_t {
    Audio          = 0x01,
    CdcControl     = 0x02,
    Hid            = 0x03,
    Physical       = 0x05,
    Imaging        = 0x06,
    Printer        = 0x07,
    MassStorage    = 0x08,
    Hub            = 0x09,
    CdcData        = 0x0A,
    ContentSecurity= 0x0D,
    Video          = 0x0E,
    Healthcare     = 0x0F,
    AudioVideo     = 0x10,
    Billboard      = 0x11,
    Diagnostic     = 0xDC,
    Wireless       = 0xE0,
    Miscellaneous  = 0xEF,
    VendorSpecific = 0xFF,
};

// 端点传输类型
enum class Transfer : uint8_t { Control = 0, Isochronous = 1, Bulk = 2, Interrupt = 3 };

// ============================ 接口/端点 ====================================
struct Endpoint {
    uint8_t  address{0};
    bool     input{false};
    Transfer transfer{Transfer::Bulk};
    uint8_t  usageType{0};       // 等时端点 Usage Type
    uint16_t maxPacketSize{0};
    uint8_t  interval{0};
    uint8_t  maxBurst{0};        // USB3 SuperSpeed Endpoint Companion 里的 bMaxBurst
    uint16_t bytesPerInterval{0};

    std::string desc() const;
};

struct Interface {
    uint8_t  number{0};
    uint8_t  altSetting{0};
    uint8_t  classCode{0};
    uint8_t  subClass{0};
    uint8_t  protocol{0};
    std::vector<Endpoint> endpoints;

    bool is(ClassCode c) const { return classCode == static_cast<uint8_t>(c); }
    // 词汇级分类（给 UI/日志用）
    std::string roleName() const;
};

// ============================ HID 集合（TLC） ==============================
// Windows 会把每个 TLC 拆成独立的 HID 子设备（零自研驱动的关键证据）
struct HidCollection {
    std::string devicePath;      // \\?\hid#vid_xxxx&pid_yyyy&...
    std::string instanceId;      // HID\VID_xxxx&PID_yyyy&MI_00
    uint16_t    usagePage{0};
    uint16_t    usage{0};
    uint16_t    inputReportLen{0};
    uint16_t    outputReportLen{0};
    uint16_t    featureReportLen{0};
    std::vector<uint8_t> reportIds;
    std::string usageName() const;
};

// ============================ COM 口（CDC ACM） ============================
struct SerialPort {
    std::string portName;        // COM7 / ttyACM0
    std::string friendlyName;
    std::string instanceId;
};

// ============================ 设备 =========================================
enum class Role : uint32_t {
    None        = 0,
    Hid         = 1u << 0,
    HidSensor   = 1u << 1,   // Usage Page 0x20
    HidDigitizer= 1u << 2,   // Usage Page 0x0D
    HidConsumer = 1u << 3,   // Usage Page 0x0C
    HidBattery  = 1u << 4,   // Usage Page 0x85
    HidVendor   = 1u << 5,   // Usage Page 0xFF00
    CdcAcm      = 1u << 6,   // GPS NMEA
    BulkChannel = 1u << 7,   // Vendor bulk（副屏/控制面）
    Uvc         = 1u << 8,
    Uac         = 1u << 9,
    Ncm         = 1u << 10,
    Mtp         = 1u << 11,
};
inline Role operator|(Role a, Role b) noexcept { return Role(uint32_t(a) | uint32_t(b)); }
inline Role operator&(Role a, Role b) noexcept { return Role(uint32_t(a) & uint32_t(b)); }
inline Role& operator|=(Role& a, Role b) noexcept { a = a | b; return a; }
inline bool hasRole(Role set, Role r) noexcept { return (uint32_t(set) & uint32_t(r)) != 0; }

struct Device {
    std::string instanceId;      // USB\VID_1D6B&PID_0104\0123456789ABCDEF
    std::string devicePath;      // 打开设备用的路径（Windows 可为 \\?\usb#...）
    std::string displayName;     // 设备管理器里的名字
    std::string manufacturer;
    uint16_t    vid{0};
    uint16_t    pid{0};
    uint16_t    bcdUsb{0};       // 0x0200 / 0x0300 / 0x0310
    std::string serialNumber;

    Speed       speed{Speed::Unknown};           // 端口协商速度
    bool        operatingAtSuperSpeedPlus{false};// V2 IOCTL 结果
    uint8_t     portNumber{0};
    std::string hubInstanceId;
    std::string driverName;                      // 如 usbccgp.sys —— 复合设备证据
    bool        isComposite{false};

    std::vector<Interface>    interfaces;
    std::vector<HidCollection> hidCollections;
    std::vector<SerialPort>   serialPorts;
    Role  roles{Role::None};

    bool has(Role r) const { return hasRole(roles, r); }
    bool isRole(const Interface& i, ClassCode c) const { return i.is(c); }

    std::string vidPid() const;
    bool superSpeed() const { return speed == Speed::Super || speed == Speed::SuperPlus; }
    // 是否可能是本工程的复合 Gadget（USB Video + HID + CDC ACM 等组合）
    bool looksLikeApxGadget() const;
};

}  // namespace apxpc::usb
