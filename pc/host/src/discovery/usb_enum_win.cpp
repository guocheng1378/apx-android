// Windows 后端：SetupAPI + cfgmgr32 枚举设备，hub IOCTL 取链路速度与配置描述符，
// HID 接口枚举各个 TLC（证明“PC 端零自研驱动、OS 已把手机变成传感器”）。
//
// 说明：刻意不依赖 WDK（usbioctl.h / usbspec.h / usbiodef.h 均为 WDK 专有），
// 所需 GUID / IOCTL / 结构体均按公开文档在本文局部定义，仅需 Windows SDK 10。
#include <initguid.h>   // 必须最先：本 TU 自己产出 GUID / PROPERTYKEY 定义

#include <windows.h>
#include <winioctl.h>   // CTL_CODE / METHOD_BUFFERED / FILE_ANY_ACCESS（Windows SDK，非 WDK）
#include <usbiodef.h>   // FILE_DEVICE_USB（Windows SDK，非 WDK）
#include <cfgmgr32.h>
#include <devpkey.h>
#include <hidsdi.h>
#include <hidpi.h>
#include <setupapi.h>

#include <algorithm>
#include <cctype>
#include <cwctype>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "apxpc/discovery/descriptor_parse.hpp"
#include "apxpc/discovery/enumerator.hpp"
#include "apxpc/discovery/usb_types.hpp"
#include "apxpc/log.hpp"

// ---------------------------------------------------------------- 局部 GUID
// GUID_DEVINTERFACE_USB_DEVICE / USB_HUB / HID（与 WDK usbiodef.h、hidclass.h 同值）
DEFINE_GUID(APX_GUID_USB_DEVICE, 0xA5DCBF10, 0x6530, 0x11D2, 0x90, 0x1F, 0x00, 0xC0, 0x4F, 0xB9, 0x51, 0xED);
DEFINE_GUID(APX_GUID_USB_HUB, 0xF18A0E88, 0xC30C, 0x11D0, 0x88, 0x15, 0x00, 0xA0, 0xC9, 0x06, 0xBE, 0xD8);
DEFINE_GUID(APX_GUID_HID, 0x4D1E55B2, 0xF16F, 0x11CF, 0x88, 0xCB, 0x00, 0x11, 0x11, 0x00, 0x00, 0x30);

namespace apxpc::usb {
namespace {

// ---------------------------------------------------------------- IOCTL
constexpr DWORD kIoctlGetDescriptor = CTL_CODE(FILE_DEVICE_USB, 273, METHOD_BUFFERED, FILE_ANY_ACCESS);
constexpr DWORD kIoctlConnInfoEx    = CTL_CODE(FILE_DEVICE_USB, 274, METHOD_BUFFERED, FILE_ANY_ACCESS);
constexpr DWORD kIoctlConnInfoExV2  = CTL_CODE(FILE_DEVICE_USB, 279, METHOD_BUFFERED, FILE_ANY_ACCESS);

// USB_NODE_CONNECTION_INFORMATION_EX（按默认对齐，与 usbioctl.h 一致）
struct UsbNodeConnInfoEx {
    uint32_t ConnectionIndex;
    uint8_t  bLength, bDescriptorType;
    uint16_t bcdUSB;
    uint8_t  bDeviceClass, bDeviceSubClass, bDeviceProtocol, bMaxPacketSize0;
    uint16_t idVendor, idProduct, bcdDevice;
    uint8_t  iManufacturer, iProduct, iSerialNumber, bNumConfigurations;
    uint8_t  CurrentConfigurationValue;
    uint8_t  Speed;          // 0=low 1=full 2=high 3=super
    uint8_t  DeviceIsHub;
    uint16_t DeviceAddress;
    uint32_t NumberOfOpenPipes;
    uint32_t ConnectionStatus;
};

struct UsbNodeConnInfoExV2 {
    uint32_t ConnectionIndex;
    uint32_t Length;
    uint32_t SupportedUsbProtocols;
    uint32_t Flags;
};
constexpr uint32_t kFlagSuperSpeedPlus = 0x01;  // 正在以 SuperSpeedPlus 或更高运行

#pragma pack(push, 1)
struct UsbDescriptorRequestHeader {
    uint32_t ConnectionIndex;
    uint8_t  bmRequest;
    uint8_t  bRequest;
    uint16_t wValue;
    uint16_t wIndex;
    uint16_t wLength;
};
#pragma pack(pop)

// ---------------------------------------------------------------- 小工具
std::string wstrToUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s(std::max(0, n), '\0');
    if (n > 0) WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), s.data(), n, nullptr, nullptr);
    return s;
}

std::wstring upper(std::wstring s) {
    for (auto& c : s) c = (wchar_t)::towupper(c);
    return s;
}

std::string upper(std::string s) {
    for (auto& c : s) c = (char)::toupper((unsigned char)c);
    return s;
}

std::vector<std::wstring> splitMultiSz(const std::wstring& ms) {
    std::vector<std::wstring> out;
    size_t i = 0;
    while (i < ms.size()) {
        const wchar_t* p = ms.c_str() + i;
        if (*p == L'\0') break;
        out.emplace_back(p);
        i += out.back().size() + 1;
    }
    return out;
}

// "USB\VID_1D6B&PID_0104\0123456789ABCDEF" -> vid/pid/serial
bool parseInstanceId(const std::wstring& id, uint16_t& vid, uint16_t& pid, std::string& serial) {
    const std::wstring up = upper(id);
    auto findTok = [&](const wchar_t* tok) -> size_t {
        const std::wstring t(tok);
        return up.find(t);
    };
    size_t v = findTok(L"VID_");
    size_t p = findTok(L"PID_");
    if (v == std::wstring::npos || p == std::wstring::npos) return false;
    vid = (uint16_t)std::wcstoul(id.substr(v + 4, 4).c_str(), nullptr, 16);
    pid = (uint16_t)std::wcstoul(id.substr(p + 4, 4).c_str(), nullptr, 16);

    serial.clear();
    size_t last = id.rfind(L'\\');
    if (last != std::wstring::npos && last + 1 < id.size())
        serial = wstrToUtf8(id.substr(last + 1));
    return true;
}

// 从 "HID\VID_xxxx&PID_yyyy&MI_00&Col02" 里取 MI_xx / Colxx
int parseAfterToken(const std::wstring& id, const std::wstring& token) {
    const std::wstring up  = upper(id);
    const std::wstring tok = upper(token);
    const size_t pos = up.find(tok);
    if (pos == std::wstring::npos) return -1;
    size_t end = pos + tok.size();
    int digits = 0;
    while (end < up.size() && ::iswxdigit(up[end]) && digits < 4) { ++end; ++digits; }
    if (digits == 0) return -1;
    return (int)std::wcstol(id.substr(pos + tok.size(), digits).c_str(), nullptr, 16);
}

bool devPropertyString(HDEVINFO hdev, SP_DEVINFO_DATA& dn, const DEVPROPKEY& key, std::wstring& out) {
    DEVPROPTYPE type = DEVPROP_TYPE_EMPTY;
    DWORD need = 0;
    BOOL ok = SetupDiGetDevicePropertyW(hdev, &dn, &key, &type, nullptr, 0, &need, 0);
    if (!ok && GetLastError() != ERROR_INSUFFICIENT_BUFFER) return false;
    if (need == 0) return false;
    std::vector<BYTE> buf(need + 2);
    if (!SetupDiGetDevicePropertyW(hdev, &dn, &key, &type, buf.data(), (DWORD)buf.size(), nullptr, 0))
        return false;
    if (type != DEVPROP_TYPE_STRING) return false;
    out.assign(reinterpret_cast<const wchar_t*>(buf.data()));
    return true;
}

bool devPropertyStringList(HDEVINFO hdev, SP_DEVINFO_DATA& dn, const DEVPROPKEY& key,
                           std::vector<std::wstring>& out) {
    DEVPROPTYPE type = DEVPROP_TYPE_EMPTY;
    DWORD need = 0;
    BOOL ok = SetupDiGetDevicePropertyW(hdev, &dn, &key, &type, nullptr, 0, &need, 0);
    if (!ok && GetLastError() != ERROR_INSUFFICIENT_BUFFER) return false;
    if (need == 0) return false;
    std::vector<BYTE> buf(need + 4);
    if (!SetupDiGetDevicePropertyW(hdev, &dn, &key, &type, buf.data(), (DWORD)buf.size(), nullptr, 0))
        return false;
    std::wstring ms(reinterpret_cast<const wchar_t*>(buf.data()), need / sizeof(wchar_t));
    out = splitMultiSz(ms);
    return true;
}

bool registryString(HDEVINFO hdev, SP_DEVINFO_DATA& dn, DWORD prop, std::wstring& out) {
    DWORD need = 0;
    SetupDiGetDeviceRegistryPropertyW(hdev, &dn, prop, nullptr, nullptr, 0, &need);
    if (need == 0) return false;
    std::vector<BYTE> buf(need + 2);
    DWORD type = 0;
    if (!SetupDiGetDeviceRegistryPropertyW(hdev, &dn, prop, &type, buf.data(), (DWORD)buf.size(), nullptr))
        return false;
    out.assign(reinterpret_cast<const wchar_t*>(buf.data()));
    return true;
}

std::wstring deviceInstanceId(HDEVINFO hdev, SP_DEVINFO_DATA& dn) {
    wchar_t id[MAX_DEVICE_ID_LEN] = {0};
    if (!SetupDiGetDeviceInstanceIdW(hdev, &dn, id, MAX_DEVICE_ID_LEN, nullptr)) return {};
    return id;
}

// 枚举某接口类的 “实例ID -> 设备路径”
std::map<std::wstring, std::wstring> interfaceMapByInstanceId(const GUID& guid) {
    std::map<std::wstring, std::wstring> out;
    HDEVINFO hdev = SetupDiGetClassDevsW(&guid, nullptr, nullptr,
                                         DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (hdev == INVALID_HANDLE_VALUE) return out;

    for (DWORD i = 0;; ++i) {
        SP_DEVINFO_DATA dn{};
        dn.cbSize = sizeof(dn);
        if (!SetupDiEnumDeviceInfo(hdev, i, &dn)) break;

        SP_DEVICE_INTERFACE_DATA ifd{};
        ifd.cbSize = sizeof(ifd);
        if (!SetupDiEnumDeviceInterfaces(hdev, &dn, &guid, 0, &ifd)) continue;

        DWORD need = 0;
        SetupDiGetDeviceInterfaceDetailW(hdev, &ifd, nullptr, 0, &need, nullptr);
        if (need == 0) continue;
        std::vector<BYTE> raw(need + 8);
        auto* detail = reinterpret_cast<PSP_DEVICE_INTERFACE_DETAIL_DATA_W>(raw.data());
        detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
        if (!SetupDiGetDeviceInterfaceDetailW(hdev, &ifd, detail, (DWORD)raw.size(), nullptr, nullptr))
            continue;

        const std::wstring instId = deviceInstanceId(hdev, dn);
        if (instId.empty()) continue;
        out[upper(instId)] = detail->DevicePath;
    }
    SetupDiDestroyDeviceInfoList(hdev);
    return out;
}

// ---------------------------------------------------------------- hub 查询
struct HubQuery {
    HANDLE handle{INVALID_HANDLE_VALUE};
    uint32_t port{0};

    ~HubQuery() { if (handle != INVALID_HANDLE_VALUE && handle != nullptr) CloseHandle(handle); }
    bool valid() const { return handle != INVALID_HANDLE_VALUE && handle != nullptr && port != 0; }
};

bool hubPortFromLocationPaths(const std::vector<std::wstring>& paths, uint32_t& port) {
    if (paths.empty()) return false;
    const std::wstring& last = paths.back();
    // ...#USBROOT(0)#USB(2)#USB(1)  —— 最后一个 USB(n) 即父集线器上的端口号
    size_t pos = std::wstring::npos;
    size_t scan = 0;
    while ((scan = last.find(L"USB(", scan)) != std::wstring::npos) {
        pos = scan;
        scan += 4;
    }
    if (pos == std::wstring::npos) return false;
    const size_t close = last.find(L')', pos);
    if (close == std::wstring::npos) return false;
    port = (uint32_t)std::wcstoul(last.substr(pos + 4, close - pos - 4).c_str(), nullptr, 10);
    return true;
}

Speed fromDeviceSpeed(uint8_t sp) {
    switch (sp) {
        case 0: return Speed::Low;
        case 1: return Speed::Full;
        case 2: return Speed::High;
        case 3: return Speed::Super;
        default: return Speed::Unknown;
    }
}

bool readConnectionSpeed(HANDLE hub, uint32_t port, Speed& speed, bool& ssplus, uint16_t* bcdUsb) {
    UsbNodeConnInfoEx info{};
    DWORD ret = 0;
    info.ConnectionIndex = port;
    if (DeviceIoControl(hub, kIoctlConnInfoEx, &info, sizeof(info), &info, sizeof(info), &ret, nullptr)) {
        speed = fromDeviceSpeed(info.Speed);
        if (bcdUsb) *bcdUsb = info.bcdUSB;
    } else {
        speed = Speed::Unknown;
    }

    UsbNodeConnInfoExV2 v2{};
    v2.ConnectionIndex = port;
    v2.Length = sizeof(v2);
    v2.SupportedUsbProtocols = 0;
    v2.Flags = 0;
    ret = 0;
    ssplus = false;
    if (DeviceIoControl(hub, kIoctlConnInfoExV2, &v2, sizeof(v2), &v2, sizeof(v2), &ret, nullptr)) {
        ssplus = (v2.Flags & kFlagSuperSpeedPlus) != 0;
        if (ssplus) speed = Speed::SuperPlus;
    }
    return speed != Speed::Unknown;
}

// GET_DESCRIPTOR_FROM_NODE_CONNECTION：先取 9 字节头部拿 wTotalLength，再取整段
std::vector<uint8_t> readConfigDescriptor(HANDLE hub, uint32_t port, uint8_t index) {
    auto fetch = [&](uint16_t want) -> std::vector<uint8_t> {
        const size_t total = sizeof(UsbDescriptorRequestHeader) + want;
        std::vector<BYTE> buf(total + 8, 0);
        auto* h = reinterpret_cast<UsbDescriptorRequestHeader*>(buf.data());
        h->ConnectionIndex = port;
        h->bmRequest = 0x80;              // device->host, standard, device
        h->bRequest  = 0x06;              // GET_DESCRIPTOR
        h->wValue    = (uint16_t)((0x02 << 8) | index);
        h->wIndex    = 0;
        h->wLength   = want;
        DWORD ret = 0;
        if (!DeviceIoControl(hub, kIoctlGetDescriptor, buf.data(), (DWORD)total, buf.data(),
                             (DWORD)total, &ret, nullptr))
            return {};
        if (ret <= sizeof(UsbDescriptorRequestHeader)) return {};
        const uint8_t* p = buf.data() + sizeof(UsbDescriptorRequestHeader);
        return std::vector<uint8_t>(p, p + (ret - sizeof(UsbDescriptorRequestHeader)));
    };

    std::vector<uint8_t> head = fetch(9);
    if (head.size() < 9) return {};
    const uint16_t totalLen = (uint16_t)(head[2] | (uint16_t(head[3]) << 8));
    if (totalLen < 9 || totalLen > 4096) return head;
    std::vector<uint8_t> full = fetch(totalLen);
    return full.empty() ? head : full;
}

// ---------------------------------------------------------------- HID 集合
struct HidEntry {
    uint16_t vid{0}, pid{0};
    int      mi{-1};
    HidCollection col;
};

std::vector<HidEntry> enumerateHidCollections() {
    std::vector<HidEntry> out;
    HDEVINFO hdev = SetupDiGetClassDevsW(&APX_GUID_HID, nullptr, nullptr,
                                         DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (hdev == INVALID_HANDLE_VALUE) return out;

    for (DWORD i = 0;; ++i) {
        SP_DEVINFO_DATA dn{};
        dn.cbSize = sizeof(dn);
        if (!SetupDiEnumDeviceInfo(hdev, i, &dn)) break;

        SP_DEVICE_INTERFACE_DATA ifd{};
        ifd.cbSize = sizeof(ifd);
        if (!SetupDiEnumDeviceInterfaces(hdev, &dn, &APX_GUID_HID, 0, &ifd)) continue;

        DWORD need = 0;
        SetupDiGetDeviceInterfaceDetailW(hdev, &ifd, nullptr, 0, &need, nullptr);
        if (need == 0) continue;
        std::vector<BYTE> raw(need + 8);
        auto* detail = reinterpret_cast<PSP_DEVICE_INTERFACE_DETAIL_DATA_W>(raw.data());
        detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
        if (!SetupDiGetDeviceInterfaceDetailW(hdev, &ifd, detail, (DWORD)raw.size(), nullptr, nullptr))
            continue;

        const std::wstring instId = deviceInstanceId(hdev, dn);
        uint16_t vid = 0, pid = 0;
        std::string serial;
        if (!parseInstanceId(instId, vid, pid, serial)) continue;

        HidEntry e;
        e.vid = vid;
        e.pid = pid;
        e.mi  = parseAfterToken(instId, L"MI_");
        e.col.devicePath = wstrToUtf8(detail->DevicePath);
        e.col.instanceId = wstrToUtf8(instId);

        // 打开设备（只读即可查询能力）取 TLC usage / 报告长度
        HANDLE h = CreateFileW(detail->DevicePath, 0, FILE_SHARE_READ | FILE_SHARE_WRITE,
                               nullptr, OPEN_EXISTING, 0, nullptr);
        if (h != INVALID_HANDLE_VALUE && h != nullptr) {
            PHIDP_PREPARSED_DATA pd = nullptr;
            if (HidD_GetPreparsedData(h, &pd) && pd) {
                HIDP_CAPS caps{};
                if (HidP_GetCaps(pd, &caps) == HIDP_STATUS_SUCCESS) {
                    e.col.usagePage        = caps.UsagePage;
                    e.col.usage            = caps.Usage;
                    e.col.inputReportLen   = caps.InputReportByteLength;
                    e.col.outputReportLen  = caps.OutputReportByteLength;
                    e.col.featureReportLen = caps.FeatureReportByteLength;

                    USHORT nVal = caps.NumberInputValueCaps;
                    std::vector<HIDP_VALUE_CAPS> vals(nVal ? nVal : 1);
                    if (nVal && HidP_GetValueCaps(HidP_Input, vals.data(), &nVal, pd) == HIDP_STATUS_SUCCESS) {
                        for (USHORT k = 0; k < nVal; ++k)
                            if (std::find(e.col.reportIds.begin(), e.col.reportIds.end(),
                                          vals[k].ReportID) == e.col.reportIds.end())
                                e.col.reportIds.push_back((uint8_t)vals[k].ReportID);
                    }
                    USHORT nBtn = caps.NumberInputButtonCaps;
                    std::vector<HIDP_BUTTON_CAPS> btns(nBtn ? nBtn : 1);
                    if (nBtn && HidP_GetButtonCaps(HidP_Input, btns.data(), &nBtn, pd) == HIDP_STATUS_SUCCESS) {
                        for (USHORT k = 0; k < nBtn; ++k)
                            if (std::find(e.col.reportIds.begin(), e.col.reportIds.end(),
                                          btns[k].ReportID) == e.col.reportIds.end())
                                e.col.reportIds.push_back((uint8_t)btns[k].ReportID);
                    }
                }
                HidD_FreePreparsedData(pd);
            }
            CloseHandle(h);
        }
        out.push_back(std::move(e));
    }
    SetupDiDestroyDeviceInfoList(hdev);
    return out;
}

// ---------------------------------------------------------------- COM 口
std::vector<SerialPort> enumerateComPorts() {
    std::vector<SerialPort> out;
    GUID portsGuid{};
    DWORD needed = 0;
    if (!SetupDiClassGuidsFromNameW(L"Ports", &portsGuid, 1, &needed) && needed == 0) {
        // 兜底：GUID_DEVINTERFACE_COMPORT
        portsGuid = {0x86E0D1E0, 0x8089, 0x11D0, {0x9C, 0xE4, 0x08, 0x00, 0x3E, 0x30, 0x1F, 0x73}};
    }
    HDEVINFO hdev = SetupDiGetClassDevsW(&portsGuid, nullptr, nullptr, DIGCF_PRESENT);
    if (hdev == INVALID_HANDLE_VALUE) return out;

    for (DWORD i = 0;; ++i) {
        SP_DEVINFO_DATA dn{};
        dn.cbSize = sizeof(dn);
        if (!SetupDiEnumDeviceInfo(hdev, i, &dn)) break;

        SerialPort sp;
        sp.instanceId = wstrToUtf8(deviceInstanceId(hdev, dn));
        std::wstring friendly;
        if (registryString(hdev, dn, SPDRP_FRIENDLYNAME, friendly))
            sp.friendlyName = wstrToUtf8(friendly);
        else
            sp.friendlyName = sp.instanceId;

        HKEY key = SetupDiOpenDevRegKey(hdev, &dn, DICS_FLAG_GLOBAL, 0, DIREG_DEV, KEY_READ);
        if (key != INVALID_HANDLE_VALUE && key != nullptr) {
            wchar_t name[64] = {0};
            DWORD sz = sizeof(name), type = 0;
            if (RegQueryValueExW(key, L"PortName", nullptr, &type, (LPBYTE)name, &sz) == ERROR_SUCCESS)
                sp.portName = wstrToUtf8(name);
            RegCloseKey(key);
        }
        if (sp.portName.empty()) continue;
        out.push_back(std::move(sp));
    }
    SetupDiDestroyDeviceInfoList(hdev);
    return out;
}

// ---------------------------------------------------------------- 角色判定
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

// ---------------------------------------------------------------- 枚举器
class WinUsbEnumerator : public IUsbEnumerator {
public:
    std::string backendName() const override { return "windows-setupapi"; }

    std::vector<Device> enumerate(const EnumerateOptions& opt) override {
        std::vector<Device> devices;

        HDEVINFO hdev = SetupDiGetClassDevsW(&APX_GUID_USB_DEVICE, nullptr, nullptr,
                                             DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
        if (hdev == INVALID_HANDLE_VALUE) return devices;

        const auto hubs  = interfaceMapByInstanceId(APX_GUID_USB_HUB);
        const auto hids  = opt.scanHidCollections ? enumerateHidCollections() : std::vector<HidEntry>{};
        const auto ports = opt.scanSerialPorts ? enumerateComPorts() : std::vector<SerialPort>{};

        const std::vector<uint16_t> allow =
            opt.vidFilter.empty() ? defaultVidAllowlist() : opt.vidFilter;

        for (DWORD i = 0;; ++i) {
            SP_DEVINFO_DATA dn{};
            dn.cbSize = sizeof(dn);
            if (!SetupDiEnumDeviceInfo(hdev, i, &dn)) break;

            Device d;
            d.instanceId = wstrToUtf8(deviceInstanceId(hdev, dn));
            uint16_t vid = 0, pid = 0;
            if (!parseInstanceId(deviceInstanceId(hdev, dn), vid, pid, d.serialNumber)) continue;
            d.vid = vid;
            d.pid = pid;

            std::wstring busDesc;
            if (devPropertyString(hdev, dn, DEVPKEY_Device_BusReportedDeviceDesc, busDesc))
                d.displayName = wstrToUtf8(busDesc);
            if (d.displayName.empty()) {
                std::wstring desc;
                if (registryString(hdev, dn, SPDRP_DEVICEDESC, desc)) d.displayName = wstrToUtf8(desc);
            }
            std::wstring mfg;
            if (devPropertyString(hdev, dn, DEVPKEY_Device_Manufacturer, mfg))
                d.manufacturer = wstrToUtf8(mfg);
            std::wstring svc;
            if (registryString(hdev, dn, SPDRP_SERVICE, svc)) d.driverName = wstrToUtf8(svc);

            SP_DEVICE_INTERFACE_DATA ifd{};
            ifd.cbSize = sizeof(ifd);
            if (SetupDiEnumDeviceInterfaces(hdev, &dn, &APX_GUID_USB_DEVICE, 0, &ifd)) {
                DWORD need = 0;
                SetupDiGetDeviceInterfaceDetailW(hdev, &ifd, nullptr, 0, &need, nullptr);
                if (need) {
                    std::vector<BYTE> raw(need + 8);
                    auto* detail = reinterpret_cast<PSP_DEVICE_INTERFACE_DETAIL_DATA_W>(raw.data());
                    detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
                    if (SetupDiGetDeviceInterfaceDetailW(hdev, &ifd, detail, (DWORD)raw.size(),
                                                         nullptr, nullptr))
                        d.devicePath = wstrToUtf8(detail->DevicePath);
                }
            }

            // ---- 位置/父集线器 -> 速度与配置描述符 ----
            std::vector<std::wstring> locPaths;
            devPropertyStringList(hdev, dn, DEVPKEY_Device_LocationPaths, locPaths);
            uint32_t port = 0;
            DEVINST parent = 0;
            if (hubPortFromLocationPaths(locPaths, port) &&
                CM_Get_Parent(&parent, dn.DevInst, 0) == CR_SUCCESS) {
                wchar_t parentId[MAX_DEVICE_ID_LEN] = {0};
                if (CM_Get_Device_IDW(parent, parentId, MAX_DEVICE_ID_LEN, 0) == CR_SUCCESS) {
                    d.hubInstanceId = wstrToUtf8(parentId);
                    const auto it = hubs.find(upper(parentId));
                    if (it != hubs.end()) {
                        HANDLE hub = CreateFileW(it->second.c_str(), GENERIC_WRITE,
                                                 FILE_SHARE_WRITE | FILE_SHARE_READ, nullptr,
                                                 OPEN_EXISTING, 0, nullptr);
                        if (hub != INVALID_HANDLE_VALUE && hub != nullptr) {
                            d.portNumber = (uint8_t)port;
                            bool ssplus = false;
                            (void)readConnectionSpeed(hub, port, d.speed, ssplus, &d.bcdUsb);
                            d.operatingAtSuperSpeedPlus = ssplus;
                            if (opt.readConfigDescriptor) {
                                auto desc = readConfigDescriptor(hub, port, 0);
                                if (!desc.empty()) {
                                    parseConfigurationDescriptor(desc, d.interfaces, &d.bcdUsb);
                                    // 设备描述符没拼在前面时，用接口信息兜底判断复合
                                    if (d.bcdUsb == 0) d.bcdUsb = (d.speed == Speed::Super ||
                                                                   d.speed == Speed::SuperPlus) ? 0x0300 : 0x0200;
                                }
                            }
                            CloseHandle(hub);
                        } else {
                            APX_LOGD("无法打开父集线器 {}: {}", d.hubInstanceId, (int)GetLastError());
                        }
                    }
                }
            }

            d.isComposite = d.interfaces.size() > 1 ||
                            (!d.driverName.empty() &&
                             upper(d.driverName).find("USBCCGP") != std::string::npos);

            // ---- 关联 HID 集合（TLC） ----
            for (const auto& h : hids) {
                if (h.vid != d.vid || h.pid != d.pid) continue;
                if (h.mi >= 0 && !d.interfaces.empty()) {
                    bool found = false;
                    for (const auto& ifc : d.interfaces)
                        if (ifc.number == (uint8_t)h.mi) found = true;
                    if (!found && d.interfaces.size() > 1) continue;
                }
                d.hidCollections.push_back(h.col);
            }

            // ---- 关联 COM 口（CDC ACM -> GPS NMEA）----
            char vidPidTag[32];
            std::snprintf(vidPidTag, sizeof(vidPidTag), "VID_%04X&PID_%04X", d.vid, d.pid);
            const std::string tag(vidPidTag);
            for (const auto& sp : ports) {
                if (upper(sp.instanceId).find(tag) != std::string::npos) d.serialPorts.push_back(sp);
            }

            applyRoles(d);

            // ---- 过滤 ----
            if (!opt.includeAllUsb) {
                const bool inAllow = std::find(allow.begin(), allow.end(), d.vid) != allow.end();
                if (!inAllow && !d.looksLikeApxGadget()) continue;
            }
            devices.push_back(std::move(d));
        }
        SetupDiDestroyDeviceInfoList(hdev);
        return devices;
    }
};

}  // namespace

std::unique_ptr<IUsbEnumerator> createUsbEnumerator() {
    return std::unique_ptr<IUsbEnumerator>(new WinUsbEnumerator());
}

std::vector<Device> enumerateDevices(const EnumerateOptions& opt) {
    auto e = createUsbEnumerator();
    return e->enumerate(opt);
}

const std::vector<uint16_t>& defaultVidAllowlist() {
    // 常见手机/开发板 USB VID；ConfigFS Gadget 默认用 Linux Foundation 1D6B。
    static const std::vector<uint16_t> kList = {
        0x1D6B,  // Linux Foundation（f_hid/f_acm/f_uvc 默认）
        0x18D1,  // Google / Pixel
        0x04E8,  // Samsung
        0x2717,  // Xiaomi
        0x2A70,  // Xiaomi (fastboot 等)
        0x12D1,  // Huawei
        0x19D2,  // ZTE
        0x0BB4,  // HTC
        0x22D9,  // OPPO
        0x2B4C,  // OnePlus/Leeco
        0x05C6,  // Qualcomm
        0x0E8D,  // MediaTek
        0x04DA,  // Sony Mobile
        0x2A45,  // Vivo
        0x1949,  // Lab126/Kindle（AOA 常见）
    };
    return kList;
}

}  // namespace apxpc::usb
