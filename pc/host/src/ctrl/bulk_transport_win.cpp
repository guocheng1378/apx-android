// Windows WinUSB bulk 传输：AOA/NCM 场景下的控制面通道（可靠、有序）。
// 不写内核驱动：WinUSB 是 Windows 自带的通用驱动（winusb.sys），通过 WinUSB API 用户态访问。
#include <windows.h>
#include <winusb.h>

#include <algorithm>
#include <string>
#include <vector>

#include "apxpc/ctrl/transport.hpp"
#include "apxpc/log.hpp"

#ifndef PIPE_TRANSFER_TIMEOUT
#define PIPE_TRANSFER_TIMEOUT 0x03
#endif
#ifndef ALLOW_PARTIAL_READS
#define ALLOW_PARTIAL_READS   0x05
#endif
#ifndef RAW_IO
#define RAW_IO                0x07
#endif
#ifndef USBD_PIPE_DIRECTION_IN
#define USBD_PIPE_DIRECTION_IN(pipeId) (((pipeId) & 0x80) != 0)
#endif

namespace apxpc::ctrl {
namespace {

constexpr int kPipeTypeBulk = 2;  // UsbdPipeTypeBulk

std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring w(std::max(0, n), L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), n);
    return w;
}

class WinUsbBulkTransport : public ICtrlTransport {
public:
    explicit WinUsbBulkTransport(std::string path) : path_(std::move(path)) {}
    ~WinUsbBulkTransport() override { close(); }

    StatusEx open() override {
        if (winusb_) return ok();
        const std::wstring wp = utf8ToWide(path_);
        HANDLE h = CreateFileW(wp.c_str(), GENERIC_READ | GENERIC_WRITE,
                               FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
                               FILE_FLAG_OVERLAPPED, nullptr);
        if (h == INVALID_HANDLE_VALUE || h == nullptr)
            return err(Status::AccessDenied, "CreateFile 失败, GetLastError=" + std::to_string(GetLastError()));
        dev_ = h;

        if (!WinUsb_Initialize(dev_, &winusb_)) {
            const DWORD e = GetLastError();
            CloseHandle(dev_);
            dev_ = INVALID_HANDLE_VALUE;
            return err(Status::NotSupported,
                       "WinUsb_Initialize 失败（该接口未绑定 winusb.sys）, GetLastError=" + std::to_string(e));
        }

        // 枚举管道，挑一对 bulk IN / OUT
        for (UCHAR i = 0; i < 16; ++i) {
            WINUSB_PIPE_INFORMATION info{};
            if (!WinUsb_QueryPipe(winusb_, 0, i, &info)) break;
            if ((int)info.PipeType != kPipeTypeBulk) continue;
            // 方向位直接判断（bit7=IN），不依赖 USBD_PIPE_DIRECTION_IN 宏（不同 SDK 展开不一致）
            if (info.PipeId & 0x80) { if (!inPipe_) inPipe_ = info.PipeId; }
            else if (!outPipe_) outPipe_ = info.PipeId;
        }
        if (!inPipe_ || !outPipe_) {
            close();
            return err(Status::NotFound, "未找到 bulk IN/OUT 端点");
        }
        APX_LOGI("WinUSB bulk 已打开: {} (IN={} OUT={})", path_, (int)inPipe_, (int)outPipe_);
        return ok();
    }

    void close() override {
        if (winusb_) { WinUsb_Free(winusb_); winusb_ = nullptr; }
        if (dev_ != INVALID_HANDLE_VALUE && dev_ != nullptr) {
            CloseHandle(dev_);
            dev_ = INVALID_HANDLE_VALUE;
        }
        inPipe_ = outPipe_ = 0;
    }

    bool isOpen() const override { return winusb_ != nullptr; }

    StatusEx send(const uint8_t* data, size_t len) override {
        if (!isOpen()) return err(Status::NotConnected, "bulk not open");
        ULONG tmo = 1000;
        WinUsb_SetPipePolicy(winusb_, outPipe_, PIPE_TRANSFER_TIMEOUT, sizeof(tmo), &tmo);
        ULONG written = 0;
        if (!WinUsb_WritePipe(winusb_, outPipe_, (PUCHAR)data, (ULONG)len, &written, nullptr))
            return err(Status::Io, "WinUsb_WritePipe 失败, GetLastError=" + std::to_string(GetLastError()));
        if (written != len) return err(Status::Io, "写入长度不足");
        return ok();
    }

    StatusEx recv(std::vector<uint8_t>& out, unsigned timeoutMs) override {
        if (!isOpen()) return err(Status::NotConnected, "bulk not open");
        ULONG tmo = std::max<ULONG>(1, (ULONG)timeoutMs);
        WinUsb_SetPipePolicy(winusb_, inPipe_, PIPE_TRANSFER_TIMEOUT, sizeof(tmo), &tmo);
        UCHAR raw = 1;
        WinUsb_SetPipePolicy(winusb_, inPipe_, RAW_IO, sizeof(raw), &raw);

        std::vector<uint8_t> buf(4096);
        ULONG got = 0;
        if (!WinUsb_ReadPipe(winusb_, inPipe_, buf.data(), (ULONG)buf.size(), &got, nullptr)) {
            const DWORD e = GetLastError();
            if (e == ERROR_SEM_TIMEOUT || e == WAIT_TIMEOUT) return err(Status::Timeout, "bulk read timeout");
            return err(Status::Io, "WinUsb_ReadPipe 失败, GetLastError=" + std::to_string(e));
        }
        out.assign(buf.begin(), buf.begin() + got);
        return got ? ok() : err(Status::Timeout, "empty bulk read");
    }

    std::string name() const override { return "bulk(winusb)"; }

private:
    std::string path_;
    HANDLE dev_{INVALID_HANDLE_VALUE};
    WINUSB_INTERFACE_HANDLE winusb_{nullptr};
    UCHAR inPipe_{0}, outPipe_{0};
};

}  // namespace

std::unique_ptr<ICtrlTransport> createBulkTransport(const std::string& devicePath) {
    return std::unique_ptr<ICtrlTransport>(new WinUsbBulkTransport(devicePath));
}

}  // namespace apxpc::ctrl
