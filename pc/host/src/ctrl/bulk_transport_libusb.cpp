// Linux/跨平台 bulk 传输（libusb）：把 /dev/bus/usb/xxx/yyy 的 fd 交给 libusb 托管。
#include <fcntl.h>
#include <unistd.h>

#include <libusb-1.0/libusb.h>

#include <string>
#include <vector>

#include "apxpc/ctrl/transport.hpp"
#include "apxpc/log.hpp"

namespace apxpc::ctrl {
namespace {

class LibusbBulkTransport : public ICtrlTransport {
public:
    explicit LibusbBulkTransport(std::string path) : path_(std::move(path)) {}
    ~LibusbBulkTransport() override { close(); }

    StatusEx open() override {
        if (handle_) return ok();
        if (libusb_init(&ctx_) != 0) return err(Status::Internal, "libusb_init 失败");

        fd_ = ::open(path_.c_str(), O_RDWR);
        if (fd_ < 0) return err(Status::AccessDenied, "open " + path_ + " 失败");
        if (libusb_wrap_sys_device(ctx_, (intptr_t)fd_, &handle_) != 0 || !handle_) {
            ::close(fd_);
            fd_ = -1;
            return err(Status::NotSupported, "libusb_wrap_sys_device 失败");
        }

        libusb_device* dev = libusb_get_device(handle_);
        libusb_config_descriptor* cfg = nullptr;
        if (libusb_get_active_config_descriptor(dev, &cfg) != 0 || !cfg)
            return err(Status::NotFound, "无法读取配置描述符");

        bool found = false;
        for (uint8_t i = 0; i < cfg->bNumInterfaces && !found; ++i) {
            const libusb_interface& li = cfg->interface[i];
            for (int a = 0; a < li.num_altsetting; ++a) {
                const libusb_interface_descriptor& id = li.altsetting[a];
                uint8_t in = 0, out = 0;
                for (uint8_t e = 0; e < id.bNumEndpoints; ++e) {
                    const libusb_endpoint_descriptor& ed = id.endpoint[e];
                    if ((ed.bmAttributes & 0x03) != LIBUSB_TRANSFER_TYPE_BULK) continue;
                    if (ed.bEndpointAddress & 0x80) in = ed.bEndpointAddress;
                    else out = ed.bEndpointAddress;
                }
                if (in && out) {
                    if (libusb_claim_interface(handle_, i) == 0) {
                        iface_ = i;
                        inEp_ = in;
                        outEp_ = out;
                        found = true;
                        break;
                    }
                }
            }
        }
        libusb_free_config_descriptor(cfg);
        if (!found) { close(); return err(Status::NotFound, "未找到可用 bulk 接口"); }
        return ok();
    }

    void close() override {
        if (handle_) {
            if (iface_ >= 0) libusb_release_interface(handle_, iface_);
            libusb_close(handle_);
            handle_ = nullptr;
            iface_ = -1;
        }
        if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
        if (ctx_) { libusb_exit(ctx_); ctx_ = nullptr; }
    }

    bool isOpen() const override { return handle_ != nullptr; }

    StatusEx send(const uint8_t* data, size_t len) override {
        if (!isOpen()) return err(Status::NotConnected, "bulk not open");
        int transferred = 0;
        const int r = libusb_bulk_transfer(handle_, outEp_, (unsigned char*)data, (int)len,
                                           &transferred, 1000);
        if (r != 0 && r != LIBUSB_ERROR_TIMEOUT) return err(Status::Io, "bulk 写失败: " + std::to_string(r));
        if ((size_t)transferred != len) return err(Status::Io, "写入长度不足");
        return ok();
    }

    StatusEx recv(std::vector<uint8_t>& out, unsigned timeoutMs) override {
        if (!isOpen()) return err(Status::NotConnected, "bulk not open");
        std::vector<uint8_t> buf(4096);
        int transferred = 0;
        const int r = libusb_bulk_transfer(handle_, inEp_, buf.data(), (int)buf.size(),
                                           &transferred, (unsigned)timeoutMs);
        if (r == LIBUSB_ERROR_TIMEOUT) return err(Status::Timeout, "bulk 读超时");
        if (r != 0) return err(Status::Io, "bulk 读失败: " + std::to_string(r));
        out.assign(buf.begin(), buf.begin() + transferred);
        return transferred ? ok() : err(Status::Timeout, "空读");
    }

    std::string name() const override { return "bulk(libusb)"; }

private:
    std::string path_;
    int fd_{-1};
    libusb_context* ctx_{nullptr};
    libusb_device_handle* handle_{nullptr};
    int iface_{-1};
    uint8_t inEp_{0}, outEp_{0};
};

}  // namespace

std::unique_ptr<ICtrlTransport> createBulkTransport(const std::string& devicePath) {
    return std::unique_ptr<ICtrlTransport>(new LibusbBulkTransport(devicePath));
}

}  // namespace apxpc::ctrl
