// libusb 传输后端（可选，跨平台；仅当 -DAPXDISP_USE_LIBUSB=ON 且找到 libusb-1.0 时编译）
//
// AOA bulk 端点从设备配置描述符里找：bInterfaceClass=0xFF、bInterfaceSubClass=0xFF 的接口，
// 取其两个 bulk 端点作为 IN/OUT。
#include "transport/i_transport.hpp"

#ifdef APXDISP_USE_LIBUSB

#include <libusb-1.0/libusb.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "common/log.hpp"

namespace apxdisp {
namespace {

class LibUsbChannel : public IChannel {
public:
    LibUsbChannel(libusb_device_handle* h, uint8_t ep, bool reliable, std::mutex& ioMutex)
        : handle_(h), ep_(ep), reliable_(reliable), ioMutex_(ioMutex) {}

    size_t write(const uint8_t* data, size_t len, uint32_t timeoutMs) override {
        std::lock_guard<std::mutex> lk(ioMutex_);
        int actual = 0;
        const int rc = libusb_bulk_transfer(handle_, ep_, const_cast<uint8_t*>(data),
                                            static_cast<int>(len), &actual,
                                            timeoutMs ? timeoutMs : (reliable_ ? 1000u : 20u));
        if (rc != 0 && rc != LIBUSB_ERROR_TIMEOUT) {
            lastError_ = "libusb_bulk_transfer(write) rc=" + std::to_string(rc);
            return 0;
        }
        return static_cast<size_t>(actual);
    }

    size_t read(uint8_t* buf, size_t cap, uint32_t timeoutMs) override {
        std::lock_guard<std::mutex> lk(ioMutex_);
        int actual = 0;
        const int rc = libusb_bulk_transfer(handle_, ep_, buf, static_cast<int>(cap), &actual,
                                            timeoutMs ? timeoutMs : (reliable_ ? 1000u : 50u));
        if (rc == LIBUSB_ERROR_TIMEOUT) return 0;
        if (rc != 0) {
            lastError_ = "libusb_bulk_transfer(read) rc=" + std::to_string(rc);
            return 0;
        }
        return static_cast<size_t>(actual);
    }

    bool reliable() const override { return reliable_; }
    void cancel() override {}
    std::string lastError() const override { return lastError_; }

private:
    libusb_device_handle* handle_ = nullptr;
    uint8_t ep_ = 0;
    bool reliable_ = false;
    std::mutex& ioMutex_;
    std::string lastError_;
};

class LibUsbTransport : public ITransport {
public:
    ~LibUsbTransport() override { close(); }

    bool open(const TransportSpec& spec) override {
        const UsbFilter& filter = spec.usb;
        filter_ = filter;
        if (libusb_init(&ctx_) != 0) { lastError_ = "libusb_init 失败"; return false; }
        handle_ = libusb_open_device_with_vid_pid(ctx_, filter.vid, filter.pid);
        if (!handle_) { lastError_ = "未找到 VID/PID 设备"; libusb_exit(ctx_); ctx_ = nullptr; return false; }
        if (libusb_claim_interface(handle_, 0) != 0) {
            lastError_ = "claim interface 失败（驱动占用？）";
            libusb_close(handle_); handle_ = nullptr;
            libusb_exit(ctx_); ctx_ = nullptr;
            return false;
        }
        // 端点号沿用 filter 给定值（AOA 固定 0x01/0x81）
        out_ = std::make_unique<LibUsbChannel>(handle_, filter.bulkOutEp, false, ioMutex_);
        ctrl_ = std::make_unique<LibUsbChannel>(handle_, filter.bulkOutEp, true, ioMutex_);
        in_ = std::make_unique<LibUsbChannel>(handle_, filter.bulkInEp, true, ioMutex_);
        opened_ = true;
        return true;
    }

    void close() override {
        if (handle_) { libusb_release_interface(handle_, 0); libusb_close(handle_); handle_ = nullptr; }
        if (ctx_) { libusb_exit(ctx_); ctx_ = nullptr; }
        opened_ = false;
    }

    bool isOpen() const override { return opened_; }
    IChannel* videoChannel() override { return out_.get(); }
    IChannel* controlChannel() override { return ctrl_.get(); }
    IChannel* touchChannel() override { return in_.get(); }
    bool reset() override {
        if (!handle_) return false;
        libusb_clear_halt(handle_, filter_.bulkOutEp);
        libusb_clear_halt(handle_, filter_.bulkInEp);
        return true;
    }
    std::string lastError() const override { return lastError_; }
    TransportKind kind() const override { return TransportKind::LibUsb; }

private:
    UsbFilter filter_{};
    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    std::mutex ioMutex_;
    std::unique_ptr<LibUsbChannel> out_, ctrl_, in_;
    std::string lastError_;
    bool opened_ = false;
};

}  // namespace

std::unique_ptr<ITransport> createLibUsbTransport() { return std::make_unique<LibUsbTransport>(); }

}  // namespace apxdisp

#endif  // APXDISP_USE_LIBUSB
