// 合成画面抓屏源（Null capture）
//
// 用途：无虚拟显示器 / CI / 离线自测时也能跑通「抓屏→编码→组帧→传输」全链路。
// 它按帧号在一个固定网格里轮流制造脏矩形，使下游的合并、组帧、CRC 逻辑得到真实校验。
#include "capture/i_capture.hpp"

#include <cstring>
#include <vector>

#include "common/log.hpp"
#include "common/clock.hpp"

namespace apxdisp {

class NullCapture : public ICapture {
public:
    bool open(const CaptureTarget& target) override {
        info_.width  = 1080;
        info_.height = 2400;
        info_.stride = info_.width * 4;
        info_.deviceName = L"\\\\.\\DISPLAY-APXNULL";
        info_.zeroCopyD3D11 = false;
        (void)target;
        pixels_.assign(static_cast<size_t>(info_.stride) * info_.height, 0x20);
        opened_ = true;
        APX_LOG_I("NullCapture 打开: %ux%u（合成画面，仅供自测）", info_.width, info_.height);
        return true;
    }

    void close() override { opened_ = false; pixels_.clear(); }
    bool isOpen() const override { return opened_; }
    bool needsReopen() const override { return false; }
    const CaptureInfo& info() const override { return info_; }
    std::string lastError() const override { return {}; }

    bool acquireFrame(RawFrame& out, uint32_t timeoutMs) override {
        if (!opened_) return false;
        (void)timeoutMs;

        // 每帧只让 2 个格子变化，模拟真实桌面的局部更新
        const uint32_t cell = 120;
        const uint32_t cellsX = info_.width / cell;
        const uint32_t cellsY = info_.height / cell;
        const uint64_t f = frameIndex_++;

        std::vector<Rect> dirty;
        for (int k = 0; k < 2; ++k) {
            const uint32_t idx = static_cast<uint32_t>((f * 2 + k) % (cellsX * cellsY));
            const uint32_t cx = idx % cellsX;
            const uint32_t cy = idx / cellsX;
            Rect r{};
            r.x = static_cast<uint16_t>(cx * cell);
            r.y = static_cast<uint16_t>(cy * cell);
            r.w = static_cast<uint16_t>(cell);
            r.h = static_cast<uint16_t>(cell);
            dirty.push_back(r);
            // 在影子上画个递增灰度块，方便肉眼/工具确认内容确实在变
            const uint8_t v = static_cast<uint8_t>((f * 8 + k * 40) & 0xFF);
            for (uint32_t y = r.y; y < r.y + r.h && y < info_.height; ++y) {
                uint8_t* row = pixels_.data() + static_cast<size_t>(y) * info_.stride;
                for (uint32_t x = r.x; x < r.x + r.w && x < info_.width; ++x) {
                    row[x * 4 + 0] = v;
                    row[x * 4 + 1] = v;
                    row[x * 4 + 2] = v;
                    row[x * 4 + 3] = 0xFF;
                }
            }
        }

        out.width  = info_.width;
        out.height = info_.height;
        out.stride = info_.stride;
        out.ptsNs  = static_cast<uint64_t>(nowNs());
        out.frameNumber = f;
        out.cpuData = pixels_.data();
        out.d3d11Texture = nullptr;
        out.dirty.rects = dirty;
        out.dirty.full = false;
        return true;
    }

private:
    bool opened_ = false;
    CaptureInfo info_{};
    std::vector<uint8_t> pixels_;
    uint64_t frameIndex_ = 0;
};

std::unique_ptr<ICapture> createNullCapture() { return std::make_unique<NullCapture>(); }

}  // namespace apxdisp
