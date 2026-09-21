// Windows HID 传输：控制面走 Report ID 5（OUT 写命令 / FEATURE 读状态）。
// 依据 PROTOCOL §2.7 与 §3.3：控制面必须可靠有序，HID 中断/控制传输天然保序。
#include <windows.h>
#include <winioctl.h>  // CTL_CODE（WIN32_LEAN_AND_MEAN 下不自动包含）
#include <hidclass.h>
#include <hidusage.h>
#include <hidsdi.h>    // HidD_*
#include <hidpi.h>     // HIDP_PREPARSED_DATA / HidP_GetCaps

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "apxpc/ctrl/transport.hpp"
#include "apxpc/log.hpp"

namespace apxpc::ctrl {
namespace {

std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring w(std::max(0, n), L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), n);
    return w;
}

class HidTransportWin : public ICtrlTransport {
public:
    explicit HidTransportWin(std::string path) : path_(std::move(path)) {}

    ~HidTransportWin() override { close(); }

    StatusEx open() override {
        if (dev_ != INVALID_HANDLE_VALUE && dev_ != nullptr) return ok();
        const std::wstring wp = utf8ToWide(path_);
        HANDLE h = CreateFileW(wp.c_str(), GENERIC_READ | GENERIC_WRITE,
                               FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
                               0, nullptr);
        if (h == INVALID_HANDLE_VALUE || h == nullptr)
            return err(Status::AccessDenied, "CreateFile 失败, GetLastError=" + std::to_string(GetLastError()));
        dev_ = h;

        // 报告长度：优先用 preparsed data
        PHIDP_PREPARSED_DATA pd = nullptr;  // SDK 只提供 PHIDP_PREPARSED_DATA 别名
        if (HidD_GetPreparsedData(dev_, &pd) && pd) {
            HIDP_CAPS caps{};
            if (HidP_GetCaps(pd, &caps) == HIDP_STATUS_SUCCESS) {
                inputLen_  = std::max<size_t>(caps.InputReportByteLength, 64);
                outputLen_ = std::max<size_t>(caps.OutputReportByteLength, 64);
                featureLen_= std::max<size_t>(caps.FeatureReportByteLength, 64);
            }
            HidD_FreePreparsedData(pd);
        }
        running_.store(true);
        reader_ = std::thread([this] { readerThread(); });
        APX_LOGI("HID 传输已打开: {}", path_);
        return ok();
    }

    void close() override {
        running_.store(false);
        if (dev_ != INVALID_HANDLE_VALUE && dev_ != nullptr) {
            CancelIoEx(dev_, nullptr);   // 打断阻塞中的 ReadFile
        }
        if (reader_.joinable()) reader_.join();
        if (dev_ != INVALID_HANDLE_VALUE && dev_ != nullptr) {
            CloseHandle(dev_);
            dev_ = INVALID_HANDLE_VALUE;
        }
    }

    bool isOpen() const override { return dev_ != INVALID_HANDLE_VALUE && dev_ != nullptr; }

    StatusEx send(const uint8_t* data, size_t len) override {
        if (!isOpen()) return err(Status::NotConnected, "hid not open");

        std::vector<uint8_t> buf(std::max<size_t>(outputLen_, len), 0);
        std::memcpy(buf.data(), data, len);
        // 若设备报告长度大于实际帧，补零；若小于，仍按实际长度发送（HID 允许）
        const DWORD toWrite = (DWORD)(outputLen_ > 0 ? std::max<size_t>(outputLen_, len) : len);
        DWORD written = 0;
        if (!WriteFile(dev_, buf.data(), toWrite, &written, nullptr)) {
            // 退化：按实际长度再试一次
            if (!WriteFile(dev_, (LPVOID)data, (DWORD)len, &written, nullptr))
                return err(Status::Io, "WriteFile 失败, GetLastError=" + std::to_string(GetLastError()));
        }
        return ok();
    }

    StatusEx recv(std::vector<uint8_t>& out, unsigned timeoutMs) override {
        std::unique_lock<std::mutex> lk(qmu_);
        if (!cv_.wait_for(lk, std::chrono::milliseconds(timeoutMs),
                          [this] { return !queue_.empty() || !running_.load(); })) {
            // 队列为空：尝试直接读一次 FEATURE 报告 5（手机端可能只提供 FEATURE）
            lk.unlock();
            std::vector<uint8_t> fb(featureLen_ > 0 ? featureLen_ : 64, 0);
            fb[0] = 5;
            if (isOpen() && HidD_GetFeature(dev_, fb.data(), (ULONG)fb.size())) {
                out = std::move(fb);
                return ok();
            }
            return err(Status::Timeout, "no hid report");
        }
        if (queue_.empty()) return err(Status::NotConnected, "closed");
        out = std::move(queue_.front());
        queue_.pop_front();
        return ok();
    }

    std::string name() const override { return "hid(win)"; }

private:
    void readerThread() {
        std::vector<uint8_t> buf(std::max<size_t>(inputLen_, 1024), 0);
        while (running_.load()) {
            if (!isOpen()) break;
            DWORD got = 0;
            if (!ReadFile(dev_, buf.data(), (DWORD)buf.size(), &got, nullptr)) {
                const DWORD e = GetLastError();
                if (e == ERROR_IO_PENDING || e == ERROR_OPERATION_ABORTED) continue;
                if (!featureTried_) {   // 无中断 IN 端点 -> 转 FEATURE 轮询
                    featureTried_ = true;
                    APX_LOGI("HID 中断读不可用(Error {}), 转为 FEATURE report 5 轮询", (int)e);
                }
                std::vector<uint8_t> fb(featureLen_ > 0 ? featureLen_ : 64, 0);
                fb[0] = 5;
                if (HidD_GetFeature(dev_, fb.data(), (ULONG)fb.size())) {
                    std::lock_guard<std::mutex> g(qmu_);
                    queue_.push_back(fb);
                    cv_.notify_one();
                }
                Sleep(featureTried_ ? 20 : 100);
                continue;
            }
            if (got == 0) continue;
            std::lock_guard<std::mutex> g(qmu_);
            queue_.push_back(std::vector<uint8_t>(buf.begin(), buf.begin() + got));
            cv_.notify_one();
        }
    }

    std::string path_;
    HANDLE dev_{INVALID_HANDLE_VALUE};
    std::thread reader_;
    std::atomic<bool> running_{false};
    std::atomic<bool> featureTried_{false};
    size_t inputLen_{0}, outputLen_{0}, featureLen_{0};
    std::mutex qmu_;
    std::condition_variable cv_;
    std::deque<std::vector<uint8_t>> queue_;
};

}  // namespace

std::unique_ptr<ICtrlTransport> createHidTransport(const std::string& devicePath) {
    return std::unique_ptr<ICtrlTransport>(new HidTransportWin(devicePath));
}

}  // namespace apxpc::ctrl
