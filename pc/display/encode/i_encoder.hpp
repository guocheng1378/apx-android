// 编码器抽象（ARCHITECTURE §5 下行链路第 3 环）
//
// 设计原则：
// 1) 厂商 SDK（NVENC / oneVPL(QuickSync) / AMF）全部 **动态加载**，机器上没有对应
//    GPU 或 SDK 时不链接、不崩，probe() 直接报不可用；
// 2) 统一的第二梯队是 Windows 内置 Media Foundation Transform（MFT），
//    MFT_ENUM_FLAG_HARDWARE 会自动命中厂商随驱动安装的硬件编码器，
//    拿不到硬件时退到系统软编 MFT；
// 3) 最后兜底是 L4 自研的 RAW_LZ4（PROTOCOL §3.1 codecId=4，零依赖）；
// 4) 所有后端统一按 lowLatency + GOP=1 + 关 B 帧 配置。
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "common/types.hpp"

namespace apxdisp {

enum class EncoderBackend {
    None            = 0,
    NvEnc           = 1,  // NVIDIA NVENC（nvEncodeAPI64.dll，动态加载）
    QuickSync       = 2,  // Intel Quick Sync（libmfx64.dll / libvpl.dll，动态加载）
    Amf             = 3,  // AMD AMF（amfrt64.dll，动态加载）
    MediaFoundation = 4,  // Windows MFT（优先硬件，退软编）
    RawLz4          = 5,  // 兜底：LZ4 压缩脏矩形像素（codecId=4）
};

inline const char* backendName(EncoderBackend b) {
    switch (b) {
        case EncoderBackend::None:            return "none";
        case EncoderBackend::NvEnc:           return "nvenc";
        case EncoderBackend::QuickSync:       return "qsv";
        case EncoderBackend::Amf:             return "amf";
        case EncoderBackend::MediaFoundation: return "mf";
        case EncoderBackend::RawLz4:          return "raw_lz4";
    }
    return "?";
}

struct EncoderCaps {
    EncoderBackend backend = EncoderBackend::None;
    std::string    name;                  // 人类可读（含驱动/设备名）
    bool           available      = false;
    bool           supportsH264   = false;
    bool           supportsHevc   = false;
    bool           supportsAv1    = false;
    bool           supportsD3D11Input = false;  // 零拷贝输入
    bool           async          = false;
    bool           lowLatency     = false;
    uint32_t       maxWidth       = 0;
    uint32_t       maxHeight      = 0;
    std::string    detail;                // 不可用原因或版本信息
};

class IEncoder {
public:
    virtual ~IEncoder() = default;

    virtual EncoderBackend backend() const = 0;
    virtual bool configure(const VideoParams& params) = 0;
    virtual bool encode(const RawFrame& in, EncodedPacket& out) = 0;
    virtual bool forceKeyFrame() = 0;
    virtual void shutdown() = 0;
    virtual std::string lastError() const = 0;
};

class EncoderFactory {
public:
    // 运行时探测：返回全部后端（含不可用项及其原因），供控制面板显示与自诊断
    static std::vector<EncoderCaps> probe();

    // prefer=Auto 时按 NvEnc → QuickSync → Amf → MediaFoundation → RawLz4 顺序取第一个可用
    static std::unique_ptr<IEncoder> create(EncoderBackend prefer, const VideoParams& params,
                                            std::string* err = nullptr);

    // 由 backends 里挑出支持指定 codec 的最优项
    static EncoderBackend pick(const std::vector<EncoderCaps>& caps, CodecId codec);
};

// 各后端的工厂方法（由 encoder_factory.cpp 汇总）
std::unique_ptr<IEncoder> createNvEncEncoder();
std::unique_ptr<IEncoder> createQsvEncoder();
std::unique_ptr<IEncoder> createAmfEncoder();
std::unique_ptr<IEncoder> createMfEncoder();
std::unique_ptr<IEncoder> createRawLz4Encoder();

}  // namespace apxdisp
