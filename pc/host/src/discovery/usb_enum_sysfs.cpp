// Linux 后端（无 libusb 依赖）：直接读 /sys/bus/usb/devices 与 /sys/bus/hid/devices。
// Windows 的 hub IOCTL 在这里等价于读 sysfs 的 "speed" 文件（内核已解析端口协商速度）。
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "apxpc/discovery/descriptor_parse.hpp"
#include "apxpc/discovery/enumerator.hpp"
#include "apxpc/discovery/usb_types.hpp"
#include "apxpc/log.hpp"

namespace apxpc::usb {
namespace {

std::string readTextFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return {};
    std::ostringstream ss;
    ss << f.rdbuf();
    std::string s = ss.str();
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    return s;
}

std::string trim(const std::string& s) {
    size_t a = s.find_first_not_of(" \t");
    if (a == std::string::npos) return {};
    size_t b = s.find_last_not_of(" \t");
    return s.substr(a, b - a + 1);
}

uint32_t parseHexU32(const std::string& s) {
    return (uint32_t)std::strtoul(trim(s).c_str(), nullptr, 16);
}

std::vector<uint8_t> readBinaryFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return {};
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

std::vector<std::string> listDir(const std::string& path) {
    std::vector<std::string> out;
    DIR* d = ::opendir(path.c_str());
    if (!d) return out;
    while (dirent* e = ::readdir(d)) {
        const std::string n = e->d_name;
        if (n == "." || n == "..") continue;
        out.push_back(n);
    }
    ::closedir(d);
    std::sort(out.begin(), out.end());
    return out;
}

std::string readLinkTarget(const std::string& path) {
    char buf[4096];
    const ssize_t n = ::readlink(path.c_str(), buf, sizeof(buf) - 1);
    if (n <= 0) return {};
    buf[n] = '\0';
    return buf;
}

std::string baseName(const std::string& p) {
    const size_t i = p.rfind('/');
    return (i == std::string::npos) ? p : p.substr(i + 1);
}

// 只扫最低限度的 HID 报告描述符：取首个 Usage Page 与所有 Report ID
struct ParsedHid {
    uint16_t usagePage{0};
    uint16_t usage{0};
    std::vector<uint8_t> reportIds;
};
ParsedHid parseHidReportDescriptor(const std::vector<uint8_t>& d) {
    ParsedHid out;
    size_t i = 0;
    while (i < d.size()) {
        const uint8_t item = d[i];
        if (item == 0xFE) {  // long item
            if (i + 2 >= d.size()) break;
            i += 3 + d[i + 1];
            continue;
        }
        const uint8_t size = item & 0x03;
        const uint8_t tag  = item & 0xFC;
        uint32_t val = 0;
        if (size == 1 && i + 1 < d.size()) val = d[i + 1];
        else if (size == 2 && i + 2 < d.size()) val = uint32_t(d[i + 1]) | (uint32_t(d[i + 2]) << 8);
        else if (size == 3 && i + 3 < d.size())
            val = uint32_t(d[i + 1]) | (uint32_t(d[i + 2]) << 8) | (uint32_t(d[i + 3]) << 16);

        if (tag == 0x04 && out.usagePage == 0) out.usagePage = (uint16_t)val;  // Usage Page(global)
        if (tag == 0x08 && out.usage == 0)    out.usage = (uint16_t)val;      // Usage(local)
        if (tag == 0x84) out.reportIds.push_back((uint8_t)val);               // Report ID(global)
        i += 1 + (size == 3 ? 4 : size);
    }
    return out;
}

Speed speedFromString(const std::string& s) {
    const std::string v = trim(s);
    if (v == "1.5")   return Speed::Low;
    if (v == "12")    return Speed::Full;
    if (v == "480")   return Speed::High;
    if (v == "5000")  return Speed::Super;
    if (v == "10000" || v == "20000") return Speed::SuperPlus;
    return Speed::Unknown;
}

struct HidEntry {
    uint16_t vid{0}, pid{0};
    HidCollection col;
};

std::vector<HidEntry> enumerateHidDevices() {
    std::vector<HidEntry> out;
    for (const auto& n : listDir("/sys/bus/hid/devices")) {
        // 形如 0003:1D6B:0104.0001（总线:VID:PID.序号）
        if (n.size() < 14 || n[4] != ':') continue;
        const std::string vidS = n.substr(5, 4);
        const std::string pidS = n.substr(10, 4);
        HidEntry e;
        e.vid = (uint16_t)std::strtoul(vidS.c_str(), nullptr, 16);
        e.pid = (uint16_t)std::strtoul(pidS.c_str(), nullptr, 16);
        if (e.vid == 0) continue;

        const std::string base = "/sys/bus/hid/devices/" + n;
        e.col.instanceId = n;
        // hidraw 节点
        for (const auto& sub : listDir(base)) {
            if (sub.rfind("hidraw", 0) == 0) {
                e.col.devicePath = "/dev/" + sub;
                break;
            }
        }
        if (e.col.devicePath.empty()) {
            for (const auto& sub : listDir(base)) {
                if (sub.rfind("hidraw", 0) == 0) { e.col.devicePath = "/dev/" + sub; break; }
            }
        }
        // 从 report_descriptor 拿到 usage page / report id
        const std::vector<uint8_t> desc = readBinaryFile(base + "/report_descriptor");
        ParsedHid p = parseHidReportDescriptor(desc);
        e.col.usagePage = p.usagePage;
        e.col.usage     = p.usage;
        e.col.reportIds = p.reportIds;
        // 报告长度：无 hidraw ioctl 时无法精确，取描述符声明的最大长度近似
        e.col.inputReportLen  = 0;
        e.col.outputReportLen = 0;
        e.col.featureReportLen = 0;
        out.push_back(std::move(e));
    }
    return out;
}

std::vector<SerialPort> enumerateTtyDevices() {
    std::vector<SerialPort> out;
    for (const auto& n : listDir("/sys/class/tty")) {
        if (n.rfind("ttyACM", 0) != 0 && n.rfind("ttyUSB", 0) != 0 && n.rfind("ttyGS", 0) != 0)
            continue;
        SerialPort sp;
        sp.portName    = "/dev/" + n;
        sp.instanceId  = readLinkTarget("/sys/class/tty/" + n + "/device");
        sp.friendlyName = n;
        out.push_back(std::move(sp));
    }
    return out;
}

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

class SysfsUsbEnumerator : public IUsbEnumerator {
public:
    std::string backendName() const override { return "linux-sysfs"; }

    std::vector<Device> enumerate(const EnumerateOptions& opt) override {
        std::vector<Device> devices;
        const auto hids  = opt.scanHidCollections ? enumerateHidDevices() : std::vector<HidEntry>{};
        const auto ports = opt.scanSerialPorts ? enumerateTtyDevices() : std::vector<SerialPort>{};
        const std::vector<uint16_t> allow =
            opt.vidFilter.empty() ? defaultVidAllowlist() : opt.vidFilter;

        for (const auto& n : listDir("/sys/bus/usb/devices")) {
            if (n.find(':') != std::string::npos) continue;  // 接口节点，跳过
            const std::string base = "/sys/bus/usb/devices/" + n;

            Device d;
            const std::string vidS = readTextFile(base + "/idVendor");
            const std::string pidS = readTextFile(base + "/idProduct");
            if (vidS.empty() || pidS.empty()) continue;
            d.vid = (uint16_t)std::strtoul(vidS.c_str(), nullptr, 16);
            d.pid = (uint16_t)std::strtoul(pidS.c_str(), nullptr, 16);

            d.instanceId   = n;
            d.displayName  = readTextFile(base + "/product");
            d.manufacturer = readTextFile(base + "/manufacturer");
            d.serialNumber = readTextFile(base + "/serial");
            if (d.displayName.empty()) d.displayName = readTextFile(base + "/interface");

            const std::string ver = readTextFile(base + "/version");   // " 3.00"
            if (!ver.empty()) {
                const float f = std::strtof(ver.c_str(), nullptr);
                const int major = (int)f;
                const int minor = (int)((f - major) * 100.0f + 0.5f);
                d.bcdUsb = (uint16_t)((major << 8) | (minor & 0xFF));
            }
            d.speed = speedFromString(readTextFile(base + "/speed"));
            d.operatingAtSuperSpeedPlus = (d.speed == Speed::SuperPlus);

            // 复合设备证据：内核绑定 usb 通用驱动且包含多个接口
            const std::string drv = readLinkTarget(base + "/driver");
            if (!drv.empty()) {
                d.driverName  = baseName(drv);
                d.isComposite = d.driverName.find("usb") != std::string::npos;
            }

            // 接口与端点
            for (const auto& sub : listDir(base)) {
                if (sub.find(":") == std::string::npos) continue;
                const std::string ibase = base + "/" + sub;
                Interface ifc;
                ifc.number     = (uint8_t)parseHexU32(readTextFile(ibase + "/bInterfaceNumber"));
                ifc.classCode  = (uint8_t)parseHexU32(readTextFile(ibase + "/bInterfaceClass"));
                ifc.subClass   = (uint8_t)parseHexU32(readTextFile(ibase + "/bInterfaceSubClass"));
                ifc.protocol   = (uint8_t)parseHexU32(readTextFile(ibase + "/bInterfaceProtocol"));
                for (const auto& epn : listDir(ibase)) {
                    if (epn.rfind("ep_", 0) != 0) continue;
                    const std::string ebase = ibase + "/" + epn;
                    Endpoint ep;
                    ep.address       = (uint8_t)parseHexU32(readTextFile(ebase + "/bEndpointAddress"));
                    ep.input         = (ep.address & 0x80) != 0;
                    const uint8_t attr = (uint8_t)parseHexU32(readTextFile(ebase + "/bmAttributes"));
                    ep.transfer      = static_cast<Transfer>(attr & 0x03);
                    ep.maxPacketSize = (uint16_t)parseHexU32(readTextFile(ebase + "/wMaxPacketSize"));
                    ep.interval      = (uint8_t)parseHexU32(readTextFile(ebase + "/bInterval"));
                    ifc.endpoints.push_back(ep);
                }
                d.interfaces.push_back(ifc);
            }
            if (d.interfaces.size() > 1) d.isComposite = true;

            if (opt.readConfigDescriptor) {
                auto desc = readBinaryFile(base + "/descriptors");
                if (!desc.empty()) parseConfigurationDescriptor(desc, d.interfaces, &d.bcdUsb);
            }

            for (const auto& h : hids) {
                if (h.vid == d.vid && h.pid == d.pid) d.hidCollections.push_back(h.col);
            }
            for (const auto& sp : ports) {
                if (sp.instanceId.find(n) != std::string::npos) d.serialPorts.push_back(sp);
            }
            applyRoles(d);

            if (!opt.includeAllUsb) {
                const bool inAllow = std::find(allow.begin(), allow.end(), d.vid) != allow.end();
                if (!inAllow && !d.looksLikeApxGadget()) continue;
            }
            devices.push_back(std::move(d));
        }
        return devices;
    }
};

}  // namespace

std::unique_ptr<IUsbEnumerator> createUsbEnumerator() {
    return std::unique_ptr<IUsbEnumerator>(new SysfsUsbEnumerator());
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
