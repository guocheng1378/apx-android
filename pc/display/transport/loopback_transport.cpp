// 回环传输（无设备时的离线通道）
//
// 写入的帧保存在内存队列里，可被 read() 原样取回 —— 用于：
//   1) 无手机/无 AOA 设备时跑通全链路自测（apxdisp --self-test）；
//   2) 把帧流 dump 到文件，供 pc/tools 或手机端离线比对。
#include "transport/i_transport.hpp"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include "common/log.hpp"

namespace apxdisp {
namespace {

class LoopbackChannel : public IChannel {
public:
    LoopbackChannel(bool reliable, std::deque<std::vector<uint8_t>>& queue, std::mutex& m)
        : reliable_(reliable), queue_(queue), mutex_(m) {}

    size_t write(const uint8_t* data, size_t len, uint32_t) override {
        std::lock_guard<std::mutex> lk(mutex_);
        queue_.emplace_back(data, data + len);
        bytesWritten_ += len;
        if (dump_) fwrite(data, 1, len, dump_);
        return len;
    }

    size_t read(uint8_t* buf, size_t cap, uint32_t) override {
        std::lock_guard<std::mutex> lk(mutex_);
        if (queue_.empty()) return 0;
        auto& front = queue_.front();
        const size_t n = std::min(cap, front.size());
        std::memcpy(buf, front.data(), n);
        queue_.pop_front();
        return n;
    }

    bool reliable() const override { return reliable_; }
    void cancel() override {}
    std::string lastError() const override { return {}; }

    void setDump(FILE* f) { dump_ = f; }
    uint64_t bytesWritten() const { return bytesWritten_; }

private:
    bool reliable_ = false;
    std::deque<std::vector<uint8_t>>& queue_;
    std::mutex& mutex_;
    FILE* dump_ = nullptr;
    uint64_t bytesWritten_ = 0;
};

class LoopbackTransport : public ITransport {
public:
    LoopbackTransport()
        : videoChannel_(false, videoQueue_, mutex_),
          ctrlChannel_(true, ctrlQueue_, mutex_),
          touchChannel_(true, touchQueue_, mutex_) {}

    ~LoopbackTransport() override { close(); }

    bool open(const TransportSpec&) override {
        opened_ = true;
        APX_LOG_I("Loopback 传输已就绪（无真实 USB 设备，仅用于自测/dump）");
        return true;
    }

    // 打开 dump 文件，把下行视频帧落盘
    bool openDump(const std::string& path) {
        dump_ = fopen(path.c_str(), "wb");
        if (!dump_) { lastError_ = "无法打开 dump 文件: " + path; return false; }
        videoChannel_.setDump(dump_);
        return true;
    }

    void close() override {
        opened_ = false;
        if (dump_) { fflush(dump_); fclose(dump_); dump_ = nullptr; }
    }

    bool isOpen() const override { return opened_; }
    IChannel* videoChannel() override { return &videoChannel_; }
    IChannel* controlChannel() override { return &ctrlChannel_; }
    IChannel* touchChannel() override { return &touchChannel_; }
    bool reset() override { return true; }
    std::string lastError() const override { return lastError_; }
    TransportKind kind() const override { return TransportKind::Loopback; }

    // 自测用：把下行队列交给上层校验
    std::deque<std::vector<uint8_t>>& videoQueue() { return videoQueue_; }
    std::mutex& queueMutex() { return mutex_; }

private:
    bool opened_ = false;
    std::string lastError_;
    std::mutex mutex_;
    std::deque<std::vector<uint8_t>> videoQueue_;
    std::deque<std::vector<uint8_t>> ctrlQueue_;
    std::deque<std::vector<uint8_t>> touchQueue_;
    LoopbackChannel videoChannel_;
    LoopbackChannel ctrlChannel_;
    LoopbackChannel touchChannel_;
    FILE* dump_ = nullptr;
};

}  // namespace

std::unique_ptr<ITransport> createLoopbackTransport() { return std::make_unique<LoopbackTransport>(); }

}  // namespace apxdisp
