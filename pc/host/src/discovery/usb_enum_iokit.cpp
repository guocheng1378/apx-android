// macOS 后端：IOKit 枚举 USB 设备。
//
// 为什么需要独立后端：非 Windows 侧此前只有两份实现 —— libusb（APXPC_USE_LIBUSB=ON，
// 默认 OFF）与 sysfs（读 /sys/bus/usb/devices）。mac 两者都够不着：默认不装 libusb，
// 且没有 /sys。结果是 mac 上面板「USB」状态永远是 false —— 枚举静默返回空，看不出
// 是「没插」还是「根本没实现」。本文件补第三条腿。
//
// 与 sysfs / libusb / SetupAPI 三端的产出口径一致：同一份 apxpc::usb::Device。
// 数据来源是 IORegistry 里 IOUSBHostDevice（macOS 12+ 的新栈）的属性 + 子接口节点；
// 等价关系：
//   sysfs idVendor/idProduct      → IORegistry idVendor/idProduct
//   sysfs product/manufacturer    → "USB Product Name" / "USB Vendor Name"
//   sysfs serial                  → "USB Serial Number"
//   sysfs version                 → bcdUSB
//   sysfs speed                   → "Device Speed"（tIOUSBHostConnectionSpeed）
//   sysfs 的 1-1:1.0 接口子节点    → IOUSBHostInterface 子节点（bInterface* 属性）
//
// 明确没做的两件（不假装做了）：
//   ① 端点（Endpoint）：IOKit 需要逐接口遍历 IOUSBHostPipe，属性名随 SDK 版本漂移，
//      没真机无法验证；本后端 interfaces[].endpoints 恒为空。角色判定（HID/UVC/UAC/
//      NCM/CDC）只用 bInterfaceClass/SubClass/Protocol，不受影响。
//   ② HID 集合（TLC，usagePage）：需要打开 IOHIDDevice 读元素，涉及 TCC 输入监控授权；
//      本后端 hidCollections 恒为空，因此 HidSensor/HidDigitizer 这类细分角色在 mac 上
//      不会出现（Hid 主角色仍由 bInterfaceClass=0x03 给出）。
//   ③ CDC ACM 的串口名（/dev/tty.usbmodem*）：走 IOSerialBSDClient，另需父子回溯；
//      本次不做，serialPorts 恒为空。
//
// ★ 本文件**未经真机验证**：本机没有 mac，CI 只做到「能编译 + 链接」。属性名与速度枚举
//   全部按 SDK 头文件写（符号名而非魔法数字，避免记错值），但真实取值仍需用户回验。
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>
// 刻意**不** include <IOKit/usb/IOUSBHostFamily.h>：它不是公开 SDK 头（CI 实测
// "file not found"），tIOUSBHostConnectionSpeed 那套枚举用户态拿不到。见下方速度说明。

#include <algorithm>
#include <string>
#include <vector>

#include "apxpc/discovery/enumerator.hpp"
#include "apxpc/discovery/usb_types.hpp"
#include "apxpc/log.hpp"

namespace apxpc::usb {
namespace {

std::string cfToUtf8(CFTypeRef v) {
    if (!v || CFGetTypeID(v) != CFStringGetTypeID()) return {};
    char buf[512] = {};
    if (!CFStringGetCString(static_cast<CFStringRef>(v), buf, sizeof(buf),
                            kCFStringEncodingUTF8)) {
        return {};
    }
    return std::string(buf);
}

// 只有「读到了数字」才返回 true —— 缺属性与 0 值必须能区分（idVendor 缺失就跳过该设备，
// 而 0 是合法但无意义的厂商号）。
bool cfToU32(CFTypeRef v, uint32_t& out) {
    if (!v || CFGetTypeID(v) != CFNumberGetTypeID()) return false;
    int64_t n = 0;
    if (!CFNumberGetValue(static_cast<CFNumberRef>(v), kCFNumberSInt64Type, &n)) return false;
    out = static_cast<uint32_t>(n);
    return true;
}

class PropReader {
public:
    explicit PropReader(io_registry_entry_t entry) : entry_(entry) {}
    ~PropReader() = default;

    std::string str(const char* key) {
        CFTypeRef v = read(key);
        std::string out = cfToUtf8(v);
        if (v) CFRelease(v);
        return out;
    }

    bool u32(const char* key, uint32_t& out) {
        CFTypeRef v = read(key);
        const bool ok = cfToU32(v, out);
        if (v) CFRelease(v);
        return ok;
    }

private:
    CFTypeRef read(const char* key) {
        CFStringRef k = CFStringCreateWithCString(kCFAllocatorDefault, key,
                                                  kCFStringEncodingUTF8);
        if (!k) return nullptr;
        CFTypeRef v = IORegistryEntryCreateCFProperty(entry_, k, kCFAllocatorDefault, 0);
        CFRelease(k);
        return v;
    }

    io_registry_entry_t entry_;
};

// "Device Speed"（IORegistry 属性）→ Speed：**故意不映射**。
//
// 这个属性的值域有两套互不兼容的约定在流传（老 kUSBDeviceSpeed*：Low=0/Full=1/High=2/
// Super=3；新 tIOUSBHostConnectionSpeed：None=0/Full=1/Low=2/High=3/Super=4），
// 而唯一能定音的 tIOUSBHostConnectionSpeed **不在公开 SDK 里**（CI 实测头文件找不到）。
// 猜错的后果是「480Mbps 的线被面板标成 super-speed」——本工程的 super-speed 门禁恰恰
// 靠这个字段，标错比标 unknown 更坏。所以：留 Unknown，把原始值写进 debug 日志，
// 谁有真机（`ioreg -p IOUSB -l` 一眼就能对出来）再把映射补上。
Speed speedFromIOKit(uint32_t raw, bool have) {
    if (!have) return Speed::Unknown;
    APX_LOGD("IOKit 枚举：Device Speed 原始值 = {}（mac 侧未映射，见源码说明）", raw);
    return Speed::Unknown;
}

// 与 sysfs / libusb / SetupAPI 三端各自持有的 applyRoles 同语义（四份重复是既有状况，
// 合并要动四端 ABI，不在本次范围）。
void applyRoles(Device& d) {
    Role r = Role::None;
    for (const auto& ifc : d.interfaces) {
        switch (ifc.classCode) {
            case 0x03: r |= Role::Hid; break;
            case 0x02:
                if (ifc.subClass == 0x02 && ifc.protocol == 0x01) r |= Role::CdcAcm;
                if (ifc.subClass == 0x0D) r |= Role::Ncm;
                break;
            case 0x0E: r |= Role::Uvc; break;
            case 0x01: r |= Role::Uac; break;
            case 0x06: if (ifc.subClass == 0x01) r |= Role::Mtp; break;
            case 0xFF: r |= Role::BulkChannel; break;
            default: break;
        }
    }
    for (const auto& h : d.hidCollections) {
        switch (h.usagePage) {
            case 0x20:   r |= Role::HidSensor;    break;
            case 0x0D:   r |= Role::HidDigitizer; break;
            case 0x0C:   r |= Role::HidConsumer;  break;
            case 0x85:   r |= Role::HidBattery;   break;
            case 0xFF00: r |= Role::HidVendor;    break;
            default: break;
        }
    }
    d.roles = r;
}

void collectInterfaces(io_registry_entry_t device, Device& d) {
    io_iterator_t it = 0;
    if (IORegistryEntryGetChildIterator(device, kIOServicePlane, &it) != KERN_SUCCESS) return;
    io_registry_entry_t child = 0;
    while ((child = IOIteratorNext(it)) != 0) {
        io_name_t cls = {};
        if (IOObjectGetClass(child, cls) == KERN_SUCCESS) {
            const std::string name(cls);
            // macOS 12+ 新栈 = IOUSBHostInterface；老栈/部分外设 = IOUSBInterface。
            if (name == "IOUSBHostInterface" || name == "IOUSBInterface") {
                PropReader p(child);
                Interface ifc;
                uint32_t v = 0;
                if (p.u32("bInterfaceNumber", v)) ifc.number = static_cast<uint8_t>(v);
                if (p.u32("bInterfaceClass", v))  ifc.classCode = static_cast<uint8_t>(v);
                if (p.u32("bInterfaceSubClass", v)) ifc.subClass = static_cast<uint8_t>(v);
                if (p.u32("bInterfaceProtocol", v)) ifc.protocol = static_cast<uint8_t>(v);
                d.interfaces.push_back(ifc);
            }
        }
        IOObjectRelease(child);
    }
    IOObjectRelease(it);
}

class IokitUsbEnumerator : public IUsbEnumerator {
public:
    std::string backendName() const override { return "macos-iokit"; }

    std::vector<Device> enumerate(const EnumerateOptions& opt) override {
        std::vector<Device> devices;
        const std::vector<uint16_t> allow =
            opt.vidFilter.empty() ? defaultVidAllowlist() : opt.vidFilter;

        CFMutableDictionaryRef match = IOServiceMatching("IOUSBHostDevice");
        if (!match) {
            APX_LOGW("IOKit 枚举：IOServiceMatching(IOUSBHostDevice) 返回空");
            return devices;
        }
        io_iterator_t it = 0;
        if (IOServiceGetMatchingServices(kIOMainPortDefault, match, &it) != KERN_SUCCESS) {
            APX_LOGW("IOKit 枚举：IOServiceGetMatchingServices 失败（无 USB 服务或无权限）");
            return devices;
        }

        io_registry_entry_t service = 0;
        while ((service = IOIteratorNext(it)) != 0) {
            PropReader p(service);
            uint32_t vid = 0, pid = 0;
            // 无 idVendor/idProduct 的节点不是真 USB 设备（例如内部 hub 的占位节点）
            if (!p.u32("idVendor", vid) || !p.u32("idProduct", pid)) {
                IOObjectRelease(service);
                continue;
            }

            Device d;
            d.vid = static_cast<uint16_t>(vid);
            d.pid = static_cast<uint16_t>(pid);

            uint64_t regId = 0;
            if (IORegistryEntryGetRegistryEntryID(service, &regId) == KERN_SUCCESS) {
                d.instanceId = "IOKit:" + d.vidPid() + "/" + std::to_string(regId);
            } else {
                d.instanceId = "IOKit:" + d.vidPid();
            }

            d.displayName  = p.str("USB Product Name");
            d.manufacturer = p.str("USB Vendor Name");
            d.serialNumber = p.str("USB Serial Number");
            if (d.displayName.empty()) d.displayName = d.vidPid();

            uint32_t v = 0;
            if (p.u32("bcdUSB", v)) d.bcdUsb = static_cast<uint16_t>(v);
            // 独立取一层：把 p.u32(...) 直接嵌进实参表会让「写 v」与「读 v」求值顺序未定义
            uint32_t speedRaw = 0;
            const bool haveSpeed = p.u32("Device Speed", speedRaw);
            d.speed = speedFromIOKit(speedRaw, haveSpeed);
            d.operatingAtSuperSpeedPlus = (d.speed == Speed::SuperPlus);

            io_name_t devCls = {};
            if (IOObjectGetClass(service, devCls) == KERN_SUCCESS) d.driverName = devCls;

            collectInterfaces(service, d);
            if (d.interfaces.size() > 1) d.isComposite = true;
            applyRoles(d);

            IOObjectRelease(service);

            // 是否会被名单过滤（提前算出，供下面那行观测日志标注）
            bool skippedByAllow = false;
            if (!opt.includeAllUsb) {
                const bool inAllow = std::find(allow.begin(), allow.end(), d.vid) != allow.end();
                if (!inAllow && !d.looksLikeApxGadget()) skippedByAllow = true;
            }

            // 可观测性（USB 输入排障第一现场）：无论是否被名单过滤，每台设备都留一行
            // 「这次到底看见了谁」。此前被跳过的设备一行日志都没有——用户"插了手机但
            // Mac 没反应"时，日志里只剩本机 4 台设备的速度行，分不清是没插、被 macOS
            // 「允许配件连接」拦下（系统层直接不上总线），还是 VID/PID 落在名单外。
            std::string ifcSummary;
            for (const auto& i : d.interfaces) {
                ifcSummary += i.roleName();
                ifcSummary += ' ';
            }
            APX_LOGD("IOKit 枚举：设备 {} {} 接口=[{}] 复合={}{}",
                     d.displayName, d.vidPid(),
                     ifcSummary, d.isComposite ? "是" : "否",
                     skippedByAllow ? "（名单外，跳过）" : "");

            if (skippedByAllow) continue;
            devices.push_back(std::move(d));
        }
        IOObjectRelease(it);
        return devices;
    }
};

}  // namespace

std::unique_ptr<IUsbEnumerator> createUsbEnumerator() {
    return std::unique_ptr<IUsbEnumerator>(new IokitUsbEnumerator());
}

std::vector<Device> enumerateDevices(const EnumerateOptions& opt) {
    auto e = createUsbEnumerator();
    return e->enumerate(opt);
}

const std::vector<uint16_t>& defaultVidAllowlist() {
    static const std::vector<uint16_t> kList = {
        0x1D6B, 0x18D1, 0x04E8, 0x2717, 0x2A70, 0x12D1, 0x19D2,
        0x0BB4, 0x22D9, 0x2B4C, 0x05C6, 0x0E8D, 0x04DA, 0x2A45, 0x1949,
    };
    return kList;
}

}  // namespace apxpc::usb