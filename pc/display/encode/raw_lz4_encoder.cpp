// RAW_LZ4 编码器（PROTOCOL §3.1 codecId=4）：零依赖兜底 + 离线自测
//
// 载荷布局：按帧头里 dirtyRectCount 个矩形的**顺序**，依次拼接每个矩形的 BGRA 像素
// （每个矩形 w*h*4 字节，行优先），然后对整块做 LZ4 压缩。
// 解码端已知矩形顺序与尺寸，可直接按 w*h*4 切分还原，无需额外表头。
#include "encode/i_encoder.hpp"

#include <cstring>
#include <vector>

#include "common/log.hpp"
#include "encode/lz4.hpp"
#include "protocol/frame_format.hpp"

namespace apxdisp {

class RawLz4Encoder : public IEncoder {
public:
    EncoderBackend backend() const override { return EncoderBackend::RawLz4; }

    bool configure(const VideoParams& params) override {
        params_ = params;
        ready_ = true;
        APX_LOG_I("RawLz4Encoder: %ux%u@%u.%02uHz", params.width, params.height,
                  params.frameRateX100 / 100, params.frameRateX100 % 100);
        return true;
    }

    bool encode(const RawFrame& in, EncodedPacket& out) override {
        if (!ready_) { lastError_ = "未 configure"; return false; }
        if (!in.cpuData || !in.stride) { lastError_ = "需要 CPU 像素（无 D3D 纹理路径）"; return false; }

        if (in.dirty.rects.empty() || in.dirty.full) {
            scratch_.assign(in.cpuData, in.cpuData + static_cast<size_t>(in.stride) * in.height);
        } else {
            size_t total = 0;
            for (const Rect& r : in.dirty.rects) {
                total += static_cast<size_t>(r.w) * r.h * 4;
            }
            scratch_.clear();
            scratch_.reserve(total);
            for (const Rect& r : in.dirty.rects) {
                for (uint32_t y = 0; y < r.h; ++y) {
                    const uint32_t sy = r.y + y;
                    if (sy >= in.height) break;
                    const uint8_t* p = in.cpuData + static_cast<size_t>(sy) * in.stride +
                                       static_cast<size_t>(r.x) * 4;
                    const size_t bytes = std::min<size_t>(static_cast<size_t>(r.w) * 4,
                                                          static_cast<size_t>(in.width - r.x) * 4);
                    scratch_.insert(scratch_.end(), p, p + bytes);
                }
            }
        }

        const size_t cap = lz4CompressBound(scratch_.size());
        std::vector<uint8_t> packed(cap, 0);
        const size_t n = lz4Compress(scratch_.data(), scratch_.size(), packed.data(), cap);
        if (n == 0) { lastError_ = "LZ4 压缩失败"; return false; }
        packed.resize(n);

        out.bytes = std::move(packed);
        out.ptsNs = in.ptsNs;
        out.keyFrame = true;          // 帧内编码，每帧自包含
        out.codec = CodecId::RawLz4;
        out.width = in.width;
        out.height = in.height;
        out.frameRateX100 = params_.frameRateX100;
        out.dirty = in.dirty;
        return true;
    }

    bool forceKeyFrame() override { return true; }
    void shutdown() override { ready_ = false; }
    std::string lastError() const override { return lastError_; }

private:
    VideoParams params_{};
    std::vector<uint8_t> scratch_;
    std::string lastError_;
    bool ready_ = false;
};

std::unique_ptr<IEncoder> createRawLz4Encoder() { return std::make_unique<RawLz4Encoder>(); }

}  // namespace apxdisp
