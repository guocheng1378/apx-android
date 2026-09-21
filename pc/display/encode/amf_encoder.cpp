// AMD AMF 编码器（动态加载 amfrt64.dll）
//
// AMF 提供 VCE/VCN 的 H.264 / HEVC / AV1 编码。运行时库 amfrt64.dll 随 AMD 驱动安装。
// 需要 AMD AMF SDK 头文件（core/Factory.h 等）：-DAPXDISP_HAVE_AMF_SDK=ON；
// 否则本文件编译为「不可用」桩。
#include "encode/i_encoder.hpp"

#include <cstring>
#include <string>
#include <vector>

#include "common/log.hpp"
#include "encode/dynlib.hpp"

#ifdef APXDISP_HAVE_AMF_SDK
#include <components/VideoEncoderAV1.h>
#include <components/VideoEncoderHEVC.h>
#include <core/Factory.h>
#include <core/Platform.h>
#endif

namespace apxdisp {

#ifdef APXDISP_HAVE_AMF_SDK

class AmfEncoder : public IEncoder {
public:
    ~AmfEncoder() override { shutdown(); }

    EncoderBackend backend() const override { return EncoderBackend::Amf; }

    bool configure(const VideoParams& params) override {
        params_ = params;
        if (!lib_.load("amfrt64.dll")) {
            lastError_ = "未找到 amfrt64.dll（" + DynLib::lastErrorText() + "）";
            return false;
        }
        using CreateContextFn = AMF_RESULT (*)(amf::AMFContext**);
        auto createContext = lib_.symbol<CreateContextFn>("AMFCreateContext");
        if (!createContext) { lastError_ = "amfrt64.dll 未导出 AMFCreateContext"; return false; }
        if (createContext(&context_) != AMF_OK || !context_) {
            lastError_ = "AMFCreateContext 失败"; return false;
        }

        const wchar_t* component = (params_.codec == CodecId::AV1) ? AMFVideoEncoderAV1
                                 : (params_.codec == CodecId::H264) ? AMFVideoEncoderVCE
                                                                    : AMFVideoEncoder_HEVC;
        if (amf::AMFCreateComponent(context_, component, &encoder_) != AMF_OK || !encoder_) {
            lastError_ = "AMFCreateComponent 失败（驱动不支持该 codec？）";
            return false;
        }

        // 低延迟：Usage=LowLatency、GOP=1、无 B 帧、CBR
        const bool hevc = (params_.codec != CodecId::H264);
        if (hevc) {
            encoder_->SetProperty(AMF_VIDEO_ENCODER_HEVC_USAGE, AMF_VIDEO_ENCODER_HEVC_USAGE_LOW_LATENCY);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_HEVC_RATE_CONTROL_METHOD,
                                  AMF_VIDEO_ENCODER_HEVC_RATE_CONTROL_METHOD_CBR);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_HEVC_TARGET_BITRATE,
                                  static_cast<int64_t>(params_.bitrateKbps) * 1000);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_HEVC_GOP_SIZE, params_.gop);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_HEVC_MAX_NUM_REFRAMES, 1);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_HEVC_ENABLE_B_FRAME, false);
        } else {
            encoder_->SetProperty(AMF_VIDEO_ENCODER_USAGE, AMF_VIDEO_ENCODER_USAGE_LOW_LATENCY);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_RATE_CONTROL_METHOD,
                                  AMF_VIDEO_ENCODER_RATE_CONTROL_METHOD_CBR);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_TARGET_BITRATE,
                                  static_cast<int64_t>(params_.bitrateKbps) * 1000);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_GOP_SIZE, params_.gop);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_MAX_NUM_REFRAMES, 1);
            encoder_->SetProperty(AMF_VIDEO_ENCODER_B_PIC_PATTERN, 0);
        }

        if (encoder_->Init(amf::AMF_SURFACE_NV12, params_.width, params_.height) != AMF_OK) {
            lastError_ = "AMF encoder Init 失败";
            encoder_ = nullptr;
            return false;
        }

        ready_ = true;
        APX_LOG_I("AMF 就绪：%s %ux%u GOP=%u", codecName(params_.codec), params_.width,
                  params_.height, params_.gop);
        return true;
    }

    bool encode(const RawFrame& in, EncodedPacket& out) override {
        if (!ready_) { lastError_ = "未 configure"; return false; }
        if (!in.cpuData) { lastError_ = "AMF 当前仅支持系统内存(NV12) 输入路径"; return false; }

        amf::AMFSurfacePtr surface;
        if (context_->AllocSurface(amf::AMF_MEMORY_HOST, amf::AMF_SURFACE_NV12,
                                   params_.width, params_.height, &surface) != AMF_OK) {
            lastError_ = "AllocSurface 失败"; return false;
        }
        amf::AMFPlanePtr planeY = surface->GetPlane(amf::AMF_PLANE_Y);
        amf::AMFPlanePtr planeUv = surface->GetPlane(amf::AMF_PLANE_UV);
        if (!planeY || !planeUv) { lastError_ = "AMF 平面获取失败"; return false; }

        const size_t yBytes = static_cast<size_t>(in.width) * in.height;
        std::memcpy(planeY->GetNative(), in.cpuData, yBytes);
        std::memcpy(planeUv->GetNative(), in.cpuData + yBytes, yBytes / 2);

        if (encoder_->SubmitInput(surface) != AMF_OK) { lastError_ = "SubmitInput 失败"; return false; }

        amf::AMFDataPtr data;
        AMF_RESULT res = encoder_->QueryOutput(&data);
        if (res == AMF_REPEAT) return false;             // 编码器还没吐帧
        if (res != AMF_OK || !data) { lastError_ = "QueryOutput 失败"; return false; }

        amf::AMFBufferPtr buffer(data);
        const uint8_t* p = static_cast<const uint8_t*>(buffer->GetNative());
        const size_t n = buffer->GetSize();
        out.bytes.assign(p, p + n);
        out.keyFrame = true;                             // GOP=1，每帧自包含
        out.ptsNs = in.ptsNs;
        out.codec = params_.codec;
        out.width = in.width;
        out.height = in.height;
        out.frameRateX100 = params_.frameRateX100;
        out.dirty = in.dirty;
        return true;
    }

    bool forceKeyFrame() override { return encoder_ && encoder_->Drain() == AMF_OK; }

    void shutdown() override {
        if (encoder_) { encoder_->Terminate(); encoder_ = nullptr; }
        if (context_) { context_->Terminate(); context_ = nullptr; }
        lib_.unload();
        ready_ = false;
    }

    std::string lastError() const override { return lastError_; }

private:
    DynLib lib_;
    amf::AMFContext* context_ = nullptr;
    amf::AMFComponent* encoder_ = nullptr;
    bool ready_ = false;
    VideoParams params_{};
    std::string lastError_;
};

#else  // !APXDISP_HAVE_AMF_SDK

class AmfEncoder : public IEncoder {
public:
    EncoderBackend backend() const override { return EncoderBackend::Amf; }
    bool configure(const VideoParams&) override {
        lastError_ = "未启用 AMF：缺少 AMD AMF SDK 头文件（core/Factory.h、components/VideoEncoderHEVC.h），"
                     "以 -DAPXDISP_HAVE_AMF_SDK=ON 重新配置";
        return false;
    }
    bool encode(const RawFrame&, EncodedPacket&) override { return false; }
    bool forceKeyFrame() override { return false; }
    void shutdown() override {}
    std::string lastError() const override { return lastError_; }

private:
    std::string lastError_;
};

#endif  // APXDISP_HAVE_AMF_SDK

std::unique_ptr<IEncoder> createAmfEncoder() { return std::make_unique<AmfEncoder>(); }

}  // namespace apxdisp
