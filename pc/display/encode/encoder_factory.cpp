// 编码器工厂：运行时探测 + 按优先级选取 + 失败自动降级
#include "encode/i_encoder.hpp"

#include <algorithm>
#include <vector>

#include "common/log.hpp"

namespace apxdisp {

namespace {

const EncoderBackend kPriority[] = {
    EncoderBackend::NvEnc,
    EncoderBackend::QuickSync,
    EncoderBackend::Amf,
    EncoderBackend::MediaFoundation,
    EncoderBackend::RawLz4,
};

std::unique_ptr<IEncoder> createByBackend(EncoderBackend b) {
    switch (b) {
        case EncoderBackend::NvEnc:           return createNvEncEncoder();
        case EncoderBackend::QuickSync:       return createQsvEncoder();
        case EncoderBackend::Amf:             return createAmfEncoder();
        case EncoderBackend::MediaFoundation:
#ifdef _WIN32
            return createMfEncoder();
#else
            return nullptr;
#endif
        case EncoderBackend::RawLz4:          return createRawLz4Encoder();
        default:                              return nullptr;
    }
}

bool supports(const EncoderCaps& c, CodecId codec) {
    switch (codec) {
        case CodecId::H264:  return c.supportsH264;
        case CodecId::HEVC:  return c.supportsHevc;
        case CodecId::AV1:   return c.supportsAv1;
        case CodecId::MJPEG: return false;   // 硬编一般不提供 MJPEG，走 RAW_LZ4 兜底
        case CodecId::RawLz4: return c.backend == EncoderBackend::RawLz4;
        default:             return false;
    }
}

// 用极小分辨率试跑一次 configure，判断该后端在本机是否真的可用
EncoderCaps probeOne(EncoderBackend b) {
    EncoderCaps caps{};
    caps.backend = b;
    caps.name = backendName(b);

    auto enc = createByBackend(b);
    if (!enc) {
        caps.detail = "本平台未编译该后端";
        return caps;
    }
    VideoParams probe{};
    probe.width = 320;
    probe.height = 240;
    probe.frameRateX100 = 3000;
    probe.bitrateKbps = 2000;
    probe.codec = CodecId::HEVC;

    if (!enc->configure(probe)) {
        caps.detail = enc->lastError();
        enc->shutdown();
        return caps;
    }
    caps.available = true;
    caps.supportsHevc = true;
    // 探测其余 codec（HEVC 都支持时 H.264 基本也支持；AV1 再单独试）
    VideoParams p2 = probe;
    p2.codec = CodecId::H264;
    auto e2 = createByBackend(b);
    if (e2 && e2->configure(p2)) { caps.supportsH264 = true; e2->shutdown(); }
    VideoParams p3 = probe;
    p3.codec = CodecId::AV1;
    auto e3 = createByBackend(b);
    if (e3 && e3->configure(p3)) { caps.supportsAv1 = true; e3->shutdown(); }

    caps.lowLatency = true;
    caps.async = (b != EncoderBackend::RawLz4);
    caps.maxWidth = 4096;
    caps.maxHeight = 4096;
    caps.supportsD3D11Input = (b == EncoderBackend::NvEnc || b == EncoderBackend::Amf);
    if (caps.detail.empty()) caps.detail = "可用";
    enc->shutdown();
    return caps;
}

}  // namespace

std::vector<EncoderCaps> EncoderFactory::probe() {
    std::vector<EncoderCaps> out;
    for (EncoderBackend b : kPriority) out.push_back(probeOne(b));
    return out;
}

EncoderBackend EncoderFactory::pick(const std::vector<EncoderCaps>& caps, CodecId codec) {
    for (EncoderBackend b : kPriority) {
        for (const auto& c : caps) {
            if (c.backend == b && c.available && supports(c, codec)) return b;
        }
    }
    return EncoderBackend::RawLz4;  // 兜底
}

std::unique_ptr<IEncoder> EncoderFactory::create(EncoderBackend prefer, const VideoParams& params,
                                                 std::string* err) {
    std::vector<EncoderBackend> order;
    if (prefer == EncoderBackend::None) {
        const auto caps = probe();
        const EncoderBackend chosen = pick(caps, params.codec);
        order.push_back(chosen);  // 选中的排最前
        for (EncoderBackend b : kPriority) {
            if (b != chosen) order.push_back(b);
        }
        APX_LOG_I("自动选择编码后端: %s", backendName(chosen));
    } else {
        order.push_back(prefer);
        for (EncoderBackend b : kPriority) {
            if (b != prefer) order.push_back(b);
        }
    }

    std::string lastErr;
    for (EncoderBackend b : order) {
        auto enc = createByBackend(b);
        if (!enc) continue;
        if (enc->configure(params)) return enc;
        lastErr = std::string(backendName(b)) + ": " + enc->lastError();
        APX_LOG_W("编码后端 %s 不可用 -> %s", backendName(b), enc->lastError().c_str());
        enc->shutdown();
    }
    if (err) *err = lastErr;
    return nullptr;
}

}  // namespace apxdisp
