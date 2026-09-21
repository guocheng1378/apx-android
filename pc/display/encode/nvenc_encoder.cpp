// NVIDIA NVENC 编码器（vendor SDK 动态加载）
//
// 加载 nvEncodeAPI64.dll，NvEncodeAPICreateInstance 拿到函数表；失败即优雅降级。
// 需要把 NVIDIA Video Codec SDK 的 nvEncodeAPI.h 放到 pc/display/encode/vendor/nvenc/
// 并以 -DAPXDISP_HAVE_NVENC_SDK=ON 配置；否则本文件编译为「不可用」桩，不影响构建。
//
// 低延迟配置：preset = LOW_LATENCY_HQ / P1，GOP=1，frameIntervalP=1，无 B 帧，CBR low-delay HQ。
#include "encode/i_encoder.hpp"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "common/log.hpp"
#include "encode/dynlib.hpp"

#ifdef APXDISP_HAVE_NVENC_SDK

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <d3d11.h>
#include <wrl/client.h>
using Microsoft::WRL::ComPtr;
#endif
#include "nvEncodeAPI.h"

#endif  // APXDISP_HAVE_NVENC_SDK

namespace apxdisp {
namespace {

std::string hrHex(long v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "0x%08lX", static_cast<unsigned long>(v));
    return std::string(buf);
}

}  // namespace

#ifdef APXDISP_HAVE_NVENC_SDK

class NvEncEncoder : public IEncoder {
public:
    ~NvEncEncoder() override { shutdown(); }

    EncoderBackend backend() const override { return EncoderBackend::NvEnc; }

    bool configure(const VideoParams& params) override {
        params_ = params;
        if (!lib_.load("nvEncodeAPI64.dll")) {
            lastError_ = "未找到 nvEncodeAPI64.dll（" + DynLib::lastErrorText() + "）";
            return false;
        }
        using CreateInstanceFn = NVENCSTATUS (*)(NV_ENCODE_API_FUNCTION_LIST*);
        auto createInstance = lib_.symbol<CreateInstanceFn>("NvEncodeAPICreateInstance");
        if (!createInstance) { lastError_ = "NvEncodeAPICreateInstance 未导出"; return false; }

        fn_ = {};
        fn_.version = NV_ENCODE_API_FUNCTION_LIST_VER;
        if (createInstance(&fn_) != NV_ENC_SUCCESS) { lastError_ = "NvEncodeAPICreateInstance 失败"; return false; }

        if (!openSession()) return false;
        if (!initEncoder()) return false;

        // 输入/输出缓冲池（NV12）
        NV_ENC_CREATE_INPUT_BUFFER inBuf{};
        inBuf.version = NV_ENC_CREATE_INPUT_BUFFER_VER;
        inBuf.width = params_.width;
        inBuf.height = params_.height;
        inBuf.memoryHeap = NV_ENC_MEMORY_HEAP_SYSTEM;
        inBuf.bufferFmt = NV_ENC_BUFFER_FORMAT_NV12;
        if (fn_.nvEncCreateInputBuffer(session_, &inBuf) != NV_ENC_SUCCESS) {
            lastError_ = "nvEncCreateInputBuffer 失败"; return false;
        }
        inputBuffer_ = inBuf.inputBuffer;

        NV_ENC_CREATE_BITSTREAM_BUFFER bs{};
        bs.version = NV_ENC_CREATE_BITSTREAM_BUFFER_VER;
        bs.memoryHeap = NV_ENC_MEMORY_HEAP_SYSTEM;
        if (fn_.nvEncCreateBitstreamBuffer(session_, &bs) != NV_ENC_SUCCESS) {
            lastError_ = "nvEncCreateBitstreamBuffer 失败"; return false;
        }
        bitstreamBuffer_ = bs.bitstreamBuffer;

        ready_ = true;
        APX_LOG_I("NVENC 就绪：%s %ux%u GOP=%u", codecName(params_.codec), params_.width,
                  params_.height, params_.gop);
        return true;
    }

    bool encode(const RawFrame& in, EncodedPacket& out) override {
        if (!ready_) { lastError_ = "未 configure"; return false; }
        if (!in.cpuData) { lastError_ = "NVENC 当前仅支持 CPU(NV12/BGRA) 输入路径"; return false; }

        // 送 NV12（此处要求上层已给 NV12；BGRA 输入先由调用方转换，见 mf_encoder 的 bgraToNv12）
        NV_ENC_LOCK_INPUT_BUFFER lock{};
        lock.version = NV_ENC_LOCK_INPUT_BUFFER_VER;
        lock.inputBuffer = inputBuffer_;
        if (fn_.nvEncLockInputBuffer(session_, &lock) != NV_ENC_SUCCESS) {
            lastError_ = "nvEncLockInputBuffer 失败"; return false;
        }
        const size_t bytes = static_cast<size_t>(in.width) * in.height * 3 / 2;
        std::memcpy(lock.bufferDataPtr, in.cpuData, bytes);
        fn_.nvEncUnlockInputBuffer(session_, inputBuffer_);

        NV_ENC_PIC_PARAMS pic{};
        pic.version = NV_ENC_PIC_PARAMS_VER;
        pic.inputBuffer = inputBuffer_;
        pic.bufferFmt = NV_ENC_BUFFER_FORMAT_NV12;
        pic.inputWidth = params_.width;
        pic.inputHeight = params_.height;
        pic.outputBitstream = bitstreamBuffer_;
        pic.completionEvent = nullptr;           // 同步路径
        pic.pictureStruct = NV_ENC_PIC_STRUCT_FRAME;
        pic.encodePicFlags = forceIdr_ ? NV_ENC_PIC_FLAG_FORCEIDR : 0;
        forceIdr_ = false;

        NVENCSTATUS st = fn_.nvEncEncodePicture(session_, &pic);
        if (st != NV_ENC_SUCCESS && st != NV_ENC_ERR_NEED_MORE_INPUT) {
            lastError_ = "nvEncEncodePicture 失败 " + hrHex(st);
            return false;
        }

        NV_ENC_LOCK_BITSTREAM bs{};
        bs.version = NV_ENC_LOCK_BITSTREAM_VER;
        bs.outputBitstream = bitstreamBuffer_;
        if (fn_.nvEncLockBitstream(session_, &bs) != NV_ENC_SUCCESS) {
            lastError_ = "nvEncLockBitstream 失败"; return false;
        }
        out.bytes.assign(reinterpret_cast<const uint8_t*>(bs.bitstreamBufferPtr),
                         reinterpret_cast<const uint8_t*>(bs.bitstreamBufferPtr) + bs.bitstreamSizeInBytes);
        out.keyFrame = bs.pictureType == NV_ENC_PIC_TYPE_IDR;
        fn_.nvEncUnlockBitstream(session_, bitstreamBuffer_);

        out.ptsNs = in.ptsNs;
        out.codec = params_.codec;
        out.width = in.width;
        out.height = in.height;
        out.frameRateX100 = params_.frameRateX100;
        out.dirty = in.dirty;
        return true;
    }

    bool forceKeyFrame() override { forceIdr_ = true; return true; }

    void shutdown() override {
        if (session_) fn_.nvEncDestroyEncoder(session_);
        session_ = nullptr;
        lib_.unload();
        ready_ = false;
    }

    std::string lastError() const override { return lastError_; }

private:
    bool openSession() {
        NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS p{};
        p.version = NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER;
#ifdef _WIN32
        // 创建 DX11 设备作为编码会话设备（NVENC 在 Windows 上走 D3D11 最快）
        D3D_FEATURE_LEVEL lv{};
        if (SUCCEEDED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                        D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0,
                                        D3D11_SDK_VERSION, &device_, &lv, &context_))) {
            p.deviceType = NV_ENC_DEVICE_TYPE_DIRECTX;
            p.device = device_.Get();
        }
#endif
        p.apiVersion = NVENCAPI_VERSION;
        NVENCSTATUS st = fn_.nvEncOpenEncodeSessionEx(&p, &session_);
        if (st != NV_ENC_SUCCESS || !session_) {
            lastError_ = "nvEncOpenEncodeSessionEx 失败 " + hrHex(st);
            return false;
        }
        return true;
    }

    bool initEncoder() {
        GUID codecGuid = (params_.codec == CodecId::AV1) ? NV_ENC_CODEC_AV1_GUID
                       : (params_.codec == CodecId::H264 ? NV_ENC_CODEC_H264_GUID
                                                         : NV_ENC_CODEC_HEVC_GUID);
        GUID presetGuid = NV_ENC_PRESET_LOW_LATENCY_HQ_GUID;

        NV_ENC_INITIALIZE_PARAMS init{};
        init.version = NV_ENC_INITIALIZE_PARAMS_VER;
        init.encodeGUID = codecGuid;
        init.presetGUID = presetGuid;
        init.encodeWidth = params_.width;
        init.encodeHeight = params_.height;
        init.darWidth = params_.width;
        init.darHeight = params_.height;
        init.frameRateNum = params_.frameRateX100 / 100;
        init.frameRateDen = 1;
        init.enablePTD = 1;                 // 由驱动自行决定 I/P，配合 GOP=1 即全 I
        init.enableEncodeAsync = 0;         // 同步路径，省一次事件等待

        NV_ENC_CONFIG cfg{};
        cfg.version = NV_ENC_CONFIG_VER;
        NV_ENC_PRESET_CONFIG preset{};
        preset.version = NV_ENC_PRESET_CONFIG_VER;
        preset.presetCfg = cfg;
        if (fn_.nvEncGetEncodePresetConfig(session_, codecGuid, presetGuid, &preset) == NV_ENC_SUCCESS) {
            cfg = preset.presetCfg;
        }

        cfg.gopLength = params_.gop;        // = 1
        cfg.frameIntervalP = 1;             // 无 B 帧
        cfg.rcParams.rateControlMode = NV_ENC_PARAMS_RC_CBR_LOWDELAY_HQ;
        cfg.rcParams.averageBitRate = params_.bitrateKbps * 1000;
        cfg.rcParams.maxBitRate = params_.bitrateKbps * 1000;
        cfg.rcParams.vbvBufferSize = params_.bitrateKbps * 1000 / (params_.frameRateX100 / 100);
        cfg.rcParams.vbvInitialDelay = cfg.rcParams.vbvBufferSize;
        cfg.rcParams.lowDelayKeyFrameScale = 1;
        if (params_.codec == CodecId::H264) {
            cfg.encodeCodecConfig.h264Config.maxNumRefFrames = 1;
            cfg.encodeCodecConfig.h264Config.useBFramesAsRef = NV_ENC_BFRAME_REF_MODE_DISABLED;
        } else if (params_.codec == CodecId::AV1) {
            cfg.encodeCodecConfig.av1Config.maxNumRefFramesInDPB = 1;
        } else {
            cfg.encodeCodecConfig.hevcConfig.maxNumRefFramesInDPB = 1;
            cfg.encodeCodecConfig.hevcConfig.useBFramesAsRef = NV_ENC_BFRAME_REF_MODE_DISABLED;
        }
        init.encodeConfig = &cfg;

        NVENCSTATUS st = fn_.nvEncInitializeEncoder(session_, &init);
        if (st != NV_ENC_SUCCESS) {
            lastError_ = "nvEncInitializeEncoder 失败 " + hrHex(st);
            return false;
        }
        return true;
    }

    DynLib lib_;
    NV_ENCODE_API_FUNCTION_LIST fn_{};
    void* session_ = nullptr;
    void* inputBuffer_ = nullptr;
    void* bitstreamBuffer_ = nullptr;
    bool forceIdr_ = false;
    bool ready_ = false;
    VideoParams params_{};
    std::string lastError_;
#ifdef _WIN32
    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
#endif
};

#else  // !APXDISP_HAVE_NVENC_SDK

class NvEncEncoder : public IEncoder {
public:
    EncoderBackend backend() const override { return EncoderBackend::NvEnc; }
    bool configure(const VideoParams&) override {
        lastError_ = "未启用 NVENC：缺少 nvEncodeAPI.h（放置到 pc/display/encode/vendor/nvenc/ "
                     "并以 -DAPXDISP_HAVE_NVENC_SDK=ON 重新配置）";
        return false;
    }
    bool encode(const RawFrame&, EncodedPacket&) override { return false; }
    bool forceKeyFrame() override { return false; }
    void shutdown() override {}
    std::string lastError() const override { return lastError_; }

private:
    std::string lastError_;
};

#endif  // APXDISP_HAVE_NVENC_SDK

std::unique_ptr<IEncoder> createNvEncEncoder() { return std::make_unique<NvEncEncoder>(); }

}  // namespace apxdisp
