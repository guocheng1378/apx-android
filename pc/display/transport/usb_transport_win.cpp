// WinUSB 传输后端（Windows）
//
// 设备发现：SetupAPI 枚举 GUID_DEVINTERFACE_USB_DEVICE，按 VID/PID（AOA 默认 18D1:2D01）
// 匹配设备接口路径；CreateFile 打开后 WinUsb_Initialize 取得 WINUSB_INTERFACE_HANDLE，
// 再用 WinUsb_QueryPipe 找出 bulk IN / bulk OUT 管道。
//
// 通道语义：
//   - 视频与控制共用 bulk OUT。控制帧持「优先锁」，持锁期间视频帧 try_lock 失败即丢弃 ——
//     这正是 PROTOCOL §3.3「视频可丢包、控制面可靠」在 USB 上的落地方式。
//   - PIPE_TRANSFER_TIMEOUT 分别设置：视频 20ms（超时即丢弃），控制 1000ms。
//   - 断线自愈由 reset()（WinUsb_AbortPipe + WinUsb_ResetPipe）与上层重连配合完成。
#ifdef _WIN32

#include "transport/i_transport.hpp"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

// initguid.h 使本 TU 实例化 DEFINE_GUID（GUID_DEVINTERFACE_USB_DEVICE 等），
// 否则 usb_transport_win.obj 引用的 GUID 为 LNK2019 未解析外部符号。
#include <initguid.h>
#include <usbioctl.h>
#include <usbiodef.h>
#include <setupapi.h>
#include <winusb.h>

#include <algorithm>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "common/log.hpp"

// winusb / setupapi 需要链接 setupapi.lib、winusb.lib（见 CMakeLists）
#pragma comment(lib, "setupapi.lib")
#pragma comment(lib, "winusb.lib")

namespace apxdisp {
namespace {

std::string lastWinError(const char* what, DWORD err) {
    char buf[192];
    snprintf(buf, sizeof(buf), "%s 失败 (GetLastError=%lu)", what, static_cast<unsigned long>(err));
    return std::string(buf);
}

std::string toLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(tolower(c)); });
    return s;
}

bool pathMatches(const std::string& lowerPath, uint16_t vid, uint16_t pid) {
    char needle[32];
    snprintf(needle, sizeof(needle), "vid_%04x&pid_%04x", vid, pid);
    return lowerPath.find(needle) != std::string::npos;
}

}  // namespace

class WinUsbTransport;

// ---------------------------------------------------------------- 通道 ----
class WinUsbChannel : public IChannel {
public:
    WinUsbChannel(WINUSB_INTERFACE_HANDLE handle, UCHAR pipeId, bool isOut, bool reliable,
                  std::mutex& priorityMutex)
        : handle_(handle), pipeId_(pipeId), isOut_(isOut), reliable_(reliable), prio_(priorityMutex) {}

    size_t write(const uint8_t* data, size_t len, uint32_t timeoutMs) override {
        if (!isOut_ || !data || len == 0) return 0;

        // 视频帧（可丢包）：控制面正在写就直接丢，绝不排队等待
        std::unique_lock<std::mutex> prioLock(prio_, std::defer_lock);
        if (!reliable_) {
            if (!prioLock.try_lock()) return 0;
        } else {
            prioLock.lock();
        }

        std::lock_guard<std::mutex> lk(ioMutex_);
        ULONG timeout = (timeoutMs == 0) ? (reliable_ ? 1000u : 20u) : timeoutMs;
        WinUsb_SetPipePolicy(handle_, pipeId_, PIPE_TRANSFER_TIMEOUT, sizeof(timeout), &timeout);

        ULONG sent = 0;
        // 单次传输上限 1MiB，避免超大帧长时间占用总线（副屏场景几乎不会触发）
        constexpr size_t kChunk = 1024u * 1024u;
        size_t total = 0;
        while (total < len) {
            const ULONG chunk = static_cast<ULONG>(std::min(kChunk, len - total));
            if (!WinUsb_WritePipe(handle_, pipeId_, const_cast<PUCHAR>(data + total), chunk, &sent, nullptr)) {
                lastError_ = lastWinError("WinUsb_WritePipe", GetLastError());
                if (!reliable_) return 0;  // 视频：写失败即丢帧
                break;
            }
            total += sent;
            if (sent == 0) break;
        }
        return total;
    }

    size_t read(uint8_t* buf, size_t cap, uint32_t timeoutMs) override {
        if (isOut_ || !buf || cap == 0) return 0;
        std::lock_guard<std::mutex> lk(ioMutex_);
        ULONG timeout = (timeoutMs == 0) ? (reliable_ ? 1000u : 50u) : timeoutMs;
        WinUsb_SetPipePolicy(handle_, pipeId_, PIPE_TRANSFER_TIMEOUT, sizeof(timeout), &timeout);

        ULONG got = 0;
        if (!WinUsb_ReadPipe(handle_, pipeId_, buf, static_cast<ULONG>(cap), &got, nullptr)) {
            const DWORD err = GetLastError();
            if (err == ERROR_SEM_TIMEOUT) return 0;  // 正常超时（无数据）
            lastError_ = lastWinError("WinUsb_ReadPipe", err);
            return 0;
        }
        return got;
    }

    bool reliable() const override { return reliable_; }

    void cancel() override {
        WinUsb_AbortPipe(handle_, pipeId_);
    }

    std::string lastError() const override { return lastError_; }

private:
    WINUSB_INTERFACE_HANDLE handle_ = nullptr;
    UCHAR  pipeId_ = 0;
    bool   isOut_ = false;
    bool   reliable_ = false;
    std::mutex& prio_;
    std::mutex ioMutex_;
    std::string lastError_;
};

// ------------------------------------------------------------ 传输实现 ----
class WinUsbTransport : public ITransport {
public:
    ~WinUsbTransport() override { close(); }

    bool open(const TransportSpec& spec) override {
        close();
        const UsbFilter& filter = spec.usb;
        filter_ = filter;

        const std::string path = findDevicePath(filter);
        if (path.empty()) {
            lastError_ = "未找到匹配的 USB 设备（VID/PID 不匹配或手机未进入 AOA 模式）";
            return false;
        }
        devicePath_ = path;

        deviceHandle_ = CreateFileA(path.c_str(), GENERIC_READ | GENERIC_WRITE,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
                                    FILE_ATTRIBUTE_NORMAL, nullptr);
        if (deviceHandle_ == INVALID_HANDLE_VALUE) {
            lastError_ = lastWinError("CreateFile", GetLastError());
            return false;
        }
        if (!WinUsb_Initialize(deviceHandle_, &usbHandle_)) {
            lastError_ = lastWinError("WinUsb_Initialize", GetLastError());
            CloseHandle(deviceHandle_);
            deviceHandle_ = INVALID_HANDLE_VALUE;
            return false;
        }
        if (!discoverPipes()) {
            close();
            return false;
        }

        outChannel_ = std::make_unique<WinUsbChannel>(usbHandle_, bulkOutPipe_, true,  false, prioMutex_);
        ctrlChannel_ = std::make_unique<WinUsbChannel>(usbHandle_, bulkOutPipe_, true,  true,  prioMutex_);
        inChannel_  = std::make_unique<WinUsbChannel>(usbHandle_, bulkInPipe_,  false, true,  prioMutex_);

        opened_ = true;
        APX_LOG_I("WinUSB 已打开: %s (OUT=0x%02X IN=0x%02X)", path.c_str(), bulkOutPipe_, bulkInPipe_);
        return true;
    }

    void close() override {
        outChannel_.reset();
        ctrlChannel_.reset();
        inChannel_.reset();
        if (usbHandle_) { WinUsb_Free(usbHandle_); usbHandle_ = nullptr; }
        if (deviceHandle_ != INVALID_HANDLE_VALUE) { CloseHandle(deviceHandle_); deviceHandle_ = INVALID_HANDLE_VALUE; }
        opened_ = false;
    }

    bool isOpen() const override { return opened_; }
    IChannel* videoChannel() override { return outChannel_.get(); }
    IChannel* controlChannel() override { return ctrlChannel_.get(); }
    IChannel* touchChannel() override { return inChannel_.get(); }

    bool reset() override {
        if (!opened_) return false;
        bool ok = true;
        if (usbHandle_) {
            if (!WinUsb_AbortPipe(usbHandle_, bulkOutPipe_)) ok = false;
            if (!WinUsb_ResetPipe(usbHandle_, bulkOutPipe_)) ok = false;
            if (!WinUsb_AbortPipe(usbHandle_, bulkInPipe_)) ok = false;
            if (!WinUsb_ResetPipe(usbHandle_, bulkInPipe_)) ok = false;
        }
        if (!ok) APX_LOG_W("管道复位失败：%s", lastWinError("WinUsb_ResetPipe", GetLastError()).c_str());
        return ok;
    }

    std::string lastError() const override { return lastError_; }
    TransportKind kind() const override { return TransportKind::WinUsb; }

private:
    std::string findDevicePath(const UsbFilter& filter) {
        if (!filter.devicePath.empty()) return filter.devicePath;

        HDEVINFO devInfo = SetupDiGetClassDevsA(reinterpret_cast<const GUID*>(&GUID_DEVINTERFACE_USB_DEVICE),
                                                nullptr, nullptr,
                                                DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
        if (devInfo == INVALID_HANDLE_VALUE) return {};

        std::string found;
        for (DWORD idx = 0;; ++idx) {
            SP_DEVICE_INTERFACE_DATA ifData{};
            ifData.cbSize = sizeof(ifData);
            if (!SetupDiEnumDeviceInterfaces(devInfo, nullptr,
                                             reinterpret_cast<const GUID*>(&GUID_DEVINTERFACE_USB_DEVICE),
                                             idx, &ifData)) {
                break;
            }
            DWORD needed = 0;
            SetupDiGetDeviceInterfaceDetailA(devInfo, &ifData, nullptr, 0, &needed, nullptr);
            if (needed == 0) continue;
            std::vector<char> detail(needed, 0);
            auto* pDetail = reinterpret_cast<SP_DEVICE_INTERFACE_DETAIL_DATA_A*>(detail.data());
            pDetail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_A);
            SP_DEVINFO_DATA devData{};
            devData.cbSize = sizeof(devData);
            if (!SetupDiGetDeviceInterfaceDetailA(devInfo, &ifData, pDetail, needed, nullptr, &devData)) continue;

            const std::string path = toLower(pDetail->DevicePath);
            if (!pathMatches(path, filter.vid, filter.pid)) continue;
            if (!filter.serial.empty() && path.find(toLower(filter.serial)) == std::string::npos) continue;
            found = pDetail->DevicePath;
            break;
        }
        SetupDiDestroyDeviceInfoList(devInfo);
        return found;
    }

    bool discoverPipes() {
        USB_INTERFACE_DESCRIPTOR iface{};
        if (!WinUsb_QueryInterfaceSettings(usbHandle_, 0, &iface)) {
            lastError_ = lastWinError("WinUsb_QueryInterfaceSettings", GetLastError());
            return false;
        }
        bool haveOut = false, haveIn = false;
        for (UCHAR i = 0; i < iface.bNumEndpoints; ++i) {
            WINUSB_PIPE_INFORMATION pipe{};
            if (!WinUsb_QueryPipe(usbHandle_, 0, i, &pipe)) continue;

            // 两个关键点：
            // 1) WinUSB 的端点类型常量是 UsbdPipeTypeBulk / UsbdPipeTypeInterrupt
            //    （winusb.h），不是 USB 规范里的 UsbBulkOut / UsbBulkIn 之类的符号 ——
            //    后者 Windows 头文件并不提供，写了会直接编译不过。
            // 2) **pipe.PipeType 只表明类型，不区分方向**。方向必须看端点地址的
            //    最高位：0x80 置位为 IN，否则为 OUT。原实现漏了这一步，
            //    会把 IN/OUT 端点混作一谈。
            const bool isIn = (pipe.PipeId & 0x80u) != 0;
            const bool isBulk = (pipe.PipeType == UsbdPipeTypeBulk);
            const bool isInterrupt = (pipe.PipeType == UsbdPipeTypeInterrupt);

            if (isBulk && !isIn) {
                bulkOutPipe_ = (filter_.bulkOutEp != 0 && pipe.PipeId == filter_.bulkOutEp)
                                   ? pipe.PipeId : (haveOut ? bulkOutPipe_ : pipe.PipeId);
                haveOut = true;
            } else if ((isBulk || isInterrupt) && isIn) {
                bulkInPipe_ = (filter_.bulkInEp != 0 && pipe.PipeId == filter_.bulkInEp)
                                  ? pipe.PipeId : (haveIn ? bulkInPipe_ : pipe.PipeId);
                haveIn = true;
            }
        }
        if (!haveOut || !haveIn) {
            lastError_ = "未找到 bulk IN/OUT 端点（设备未处于 AOA bulk 模式？）";
            return false;
        }
        return true;
    }

    UsbFilter filter_{};
    std::string devicePath_;
    std::string lastError_;
    bool opened_ = false;

    HANDLE deviceHandle_ = INVALID_HANDLE_VALUE;
    WINUSB_INTERFACE_HANDLE usbHandle_ = nullptr;
    UCHAR bulkOutPipe_ = 0;
    UCHAR bulkInPipe_ = 0;

    std::mutex prioMutex_;
    std::unique_ptr<WinUsbChannel> outChannel_;
    std::unique_ptr<WinUsbChannel> ctrlChannel_;
    std::unique_ptr<WinUsbChannel> inChannel_;
};

std::unique_ptr<ITransport> createWinUsbTransport() { return std::make_unique<WinUsbTransport>(); }

}  // namespace apxdisp

#endif  // _WIN32
