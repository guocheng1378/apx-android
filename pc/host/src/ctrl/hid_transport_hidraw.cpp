// Linux hidraw 传输（/dev/hidraw*）：对应 Windows 的 HID Report 5 通道。
#include <fcntl.h>
#include <linux/hidraw.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <atomic>
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

class HidRawTransport : public ICtrlTransport {
public:
    explicit HidRawTransport(std::string path) : path_(std::move(path)) {}
    ~HidRawTransport() override { close(); }

    StatusEx open() override {
        if (fd_ >= 0) return ok();
        const int fd = ::open(path_.c_str(), O_RDWR | O_NONBLOCK);
        if (fd < 0) return err(Status::AccessDenied, "open " + path_ + " 失败");
        fd_ = fd;

        int rlen = 0;
        if (ioctl(fd_, HIDIOCGRDESCSIZE, &rlen) == 0 && rlen > 0) {
            struct hidraw_report_descriptor rdesc {};
            rdesc.size = (unsigned)rlen;
            if (ioctl(fd_, HIDIOCGRDESC, &rdesc) == 0) {
                // 粗略取最大 Input Report 长度：描述符里 0x85 之后的 Report Count/Size 不展开，
                // 直接用 1024 上限（PROTOCOL §2.1 单报告 ≤ 1024）
                (void)rdesc;
            }
        }
        // 去掉 O_NONBLOCK 后由 poll 控制超时，线程里读
        int flags = fcntl(fd_, F_GETFL, 0);
        if (flags >= 0) fcntl(fd_, F_SETFL, flags & ~O_NONBLOCK);

        running_.store(true);
        reader_ = std::thread([this] { readerThread(); });
        return ok();
    }

    void close() override {
        running_.store(false);
        if (reader_.joinable()) reader_.join();
        if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
    }

    bool isOpen() const override { return fd_ >= 0; }

    StatusEx send(const uint8_t* data, size_t len) override {
        if (!isOpen()) return err(Status::NotConnected, "hidraw not open");
        const ssize_t n = ::write(fd_, data, len);
        if (n < 0 || (size_t)n != len) return err(Status::Io, "hidraw write 失败");
        return ok();
    }

    StatusEx recv(std::vector<uint8_t>& out, unsigned timeoutMs) override {
        std::unique_lock<std::mutex> lk(mu_);
        if (!cv_.wait_for(lk, std::chrono::milliseconds(timeoutMs),
                          [this] { return !queue_.empty() || !running_.load(); }))
            return err(Status::Timeout, "no hidraw report");
        if (queue_.empty()) return err(Status::NotConnected, "closed");
        out = std::move(queue_.front());
        queue_.pop_front();
        return ok();
    }

    std::string name() const override { return "hidraw(linux)"; }

private:
    void readerThread() {
        std::vector<uint8_t> buf(1024);
        while (running_.load()) {
            if (fd_ < 0) break;
            pollfd pfd{};
            pfd.fd = fd_;
            pfd.events = POLLIN;
            const int r = ::poll(&pfd, 1, 100);
            if (r <= 0) continue;
            const ssize_t n = ::read(fd_, buf.data(), buf.size());
            if (n <= 0) continue;
            std::lock_guard<std::mutex> g(mu_);
            queue_.push_back(std::vector<uint8_t>(buf.begin(), buf.begin() + n));
            cv_.notify_one();
        }
    }

    std::string path_;
    int fd_{-1};
    std::thread reader_;
    std::atomic<bool> running_{false};
    std::mutex mu_;
    std::condition_variable cv_;
    std::deque<std::vector<uint8_t>> queue_;
};

}  // namespace

std::unique_ptr<ICtrlTransport> createHidTransport(const std::string& devicePath) {
    return std::unique_ptr<ICtrlTransport>(new HidRawTransport(devicePath));
}

}  // namespace apxpc::ctrl
