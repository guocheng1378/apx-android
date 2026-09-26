// Media Foundation 编码器（Windows 内置，无第三方依赖）
//
// 定位：厂商 SDK 不可用时的**第二梯队**。用 MFT_ENUM_FLAG_HARDWARE 枚举视频编码器 MFT：
//   - 命中 NVIDIA / Intel / AMD 随驱动安装的硬件 MFT 时即为硬编；
//   - 未命中时退到系统 H.264 软编 MFT（MFT_ENUM_FLAG_SYNCMFT / 去掉 HARDWARE 标志）。
// 低延迟配置：CODECAPI_AVEncCommonLowLatency=TRUE、GOP=1、GlobalLowDelayVBR CBR 近似。
//
// 输入：MFT 要求 NV12，因此在 CPU 路径上做 BGRA→NV12 转换（BT.601 全范围近似）；
//       若抓屏给出了 ID3D11Texture2D 且 MFT 支持 IMFVideoSampleAllocator/句柄，可直接送纹理
//       （本实现先用 CPU 路径保证可用，零拷贝留作优化项，见 README「延迟预算」）。
#ifdef _WIN32

#include "encode/i_encoder.hpp"

#include <wmcodecdsp.h>  // 微软 H264 软编 MFT 声明

// v1.10：CLSID_CMSH264EncoderMFT 的符号在 uuid.lib——为免动 CMake 链接配置
// 直接本地定义（Microsoft H264 Video Encoder MFT，Windows 自带组件）。
namespace {
const CLSID kCLSID_CMSH264EncoderMFT = {
    0x6CA50344, 0x051A, 0x4DED, {0x97, 0x79, 0xA4, 0x33, 0x05, 0x16, 0x5E, 0x35}};
}



#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
// v1.10：MFUnlockAsyncMFT（异步 MFT 解锁）声明受 WINVER>=Win8 门控，
// 不定义 _WIN32_WINNT 时旧默认值会隐藏该声明（编译报「找不到标识符」）。
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <windows.h>

#include <codecapi.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <mftransform.h>
#include <wmcodecdsp.h>
#include <wrl/client.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "common/log.hpp"

using Microsoft::WRL::ComPtr;

namespace apxdisp {
namespace {

// BGRA(自顶向下) -> NV12(自顶向下)，BT.601 有限范围近似
void bgraToNv12(const uint8_t* bgra, uint32_t w, uint32_t h, uint32_t stride,
                std::vector<uint8_t>& nv12) {
    nv12.assign(static_cast<size_t>(w) * h * 3 / 2, 0);
    uint8_t* yPlane = nv12.data();
    uint8_t* uvPlane = nv12.data() + static_cast<size_t>(w) * h;

    for (uint32_t y = 0; y < h; ++y) {
        const uint8_t* src = bgra + static_cast<size_t>(y) * stride;
        uint8_t* dstY = yPlane + static_cast<size_t>(y) * w;
        for (uint32_t x = 0; x < w; ++x) {
            const uint8_t b = src[x * 4 + 0];
            const uint8_t g = src[x * 4 + 1];
            const uint8_t r = src[x * 4 + 2];
            const int yy = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            dstY[x] = static_cast<uint8_t>(yy < 0 ? 0 : (yy > 255 ? 255 : yy));
        }
    }
    for (uint32_t y = 0; y < h; y += 2) {
        const uint8_t* src0 = bgra + static_cast<size_t>(y) * stride;
        const uint8_t* src1 = (y + 1 < h) ? (bgra + static_cast<size_t>(y + 1) * stride) : src0;
        uint8_t* dstUv = uvPlane + static_cast<size_t>(y / 2) * w;
        for (uint32_t x = 0; x < w; x += 2) {
            const uint8_t* p0 = src0 + x * 4;
            const uint8_t* p1 = (x + 1 < w) ? (src0 + (x + 1) * 4) : p0;
            const uint8_t* p2 = src1 + x * 4;
            const uint8_t* p3 = (x + 1 < w) ? (src1 + (x + 1) * 4) : p2;
            auto avg = [](const uint8_t* a, const uint8_t* b, const uint8_t* c, const uint8_t* d,
                          int off) -> int {
                return (a[off] + b[off] + c[off] + d[off]) / 4;
            };
            const int bAvg = avg(p0, p1, p2, p3, 0);
            const int gAvg = avg(p0, p1, p2, p3, 1);
            const int rAvg = avg(p0, p1, p2, p3, 2);
            const int u = ((-38 * rAvg - 74 * gAvg + 112 * bAvg + 128) >> 8) + 128;
            const int v = ((112 * rAvg - 94 * gAvg - 18 * bAvg + 128) >> 8) + 128;
            dstUv[x + 0] = static_cast<uint8_t>(u < 0 ? 0 : (u > 255 ? 255 : u));
            dstUv[x + 1] = static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
        }
    }
}

GUID codecGuid(CodecId id) {
    switch (id) {
        case CodecId::HEVC: return MFVideoFormat_HEVC;
        case CodecId::AV1:  return MFVideoFormat_AV1;
        case CodecId::H264:
        default:            return MFVideoFormat_H264;
    }
}

const wchar_t* codecWName(CodecId id) {
    switch (id) {
        case CodecId::HEVC: return L"HEVC";
        case CodecId::AV1:  return L"AV1";
        default:            return L"H264";
    }
}

}  // namespace

class MfEncoder : public IEncoder {
public:
    MfEncoder(bool requireHardware) : requireHardware_(requireHardware) {}
    ~MfEncoder() override { shutdown(); }

    EncoderBackend backend() const override { return EncoderBackend::MediaFoundation; }

    bool configure(const VideoParams& params) override {
        params_ = params;
        lastError_.clear();

        HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        comInitialized_ = SUCCEEDED(hr) || hr == RPC_E_CHANGED_MODE;

        // v1.10 修复：此前从未调用 MFStartup——MF 平台未初始化时 MFTEnumEx/
        // SetInputType 全部失败（真机验证：--list-encoders 报 SetInputType(NV12) 失败）。
        // 注意不能用 MFSTARTUP_LITE：Lite 模式不加载工作队列，编码器 MFT
        // 内部需要它（实测 SetInputType 报 0xC00D6D77 TYPE_NOT_SET）。
        hr = MFStartup(MF_VERSION, 0);
        mfStarted_ = SUCCEEDED(hr);
        if (!mfStarted_) {
            char buf[96];
            snprintf(buf, sizeof(buf), "MFStartup 失败 hr=0x%08lX", static_cast<unsigned long>(hr));
            lastError_ = buf;
            return false;
        }

        if (!findEncoder(params.codec)) {
            // HEVC/AV1 硬件不可用时退到 H.264，保证链路不断
            if (params.codec != CodecId::H264 && findEncoder(CodecId::H264)) {
                APX_LOG_W("%ls 编码器不可用，降级到 H264", codecWName(params.codec));
                params_.codec = CodecId::H264;
            } else {
                return false;
            }
        }

        if (!openTransform()) {
            // v1.10 保底：MFTEnumEx 激活的对象行为异常（真机 SetInputType 恒报
            // MF_E_TRANSFORM_ASYNC_LOCKED 且无属性表）——直接 CoCreate 微软
            // H264 软编 MFT（同步、零依赖、随 Windows 分发）。
            APX_LOG_W("MFT 枚举路径失败（%s），改用微软 H264 软编 MFT", lastError_.c_str());
            lastError_.clear();
            activator_.Reset();
            transform_.Reset();
            params_.codec = CodecId::H264;
            HRESULT chr = CoCreateInstance(kCLSID_CMSH264EncoderMFT, nullptr,
                                           CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&transform_));
            if (FAILED(chr) || !transform_) {
                char buf[96];
                snprintf(buf, sizeof(buf), "CoCreateInstance(CMSH264EncoderMFT) 失败 hr=0x%08lX",
                         static_cast<unsigned long>(chr));
                lastError_ = buf;
                return false;
            }
            transform_->QueryInterface(IID_PPV_ARGS(&codecApi_));
            transform_->QueryInterface(IID_PPV_ARGS(&eventGen_));
            async_ = eventGen_ != nullptr;
            if (!setMediaTypes()) return false;
        }
        if (!setLowLatencyOptions()) {
            APX_LOG_W("低延迟属性设置失败（不致命）：%s", lastError_.c_str());
        }
        if (!startStreaming()) return false;

        nv12_.reserve(static_cast<size_t>(params_.width) * params_.height * 3 / 2);
        ready_ = true;
        APX_LOG_I("MF 编码器就绪: %ls %ux%u", codecWName(params_.codec), params_.width, params_.height);
        return true;
    }

    bool encode(const RawFrame& in, EncodedPacket& out) override {
        if (!ready_) { lastError_ = "未 configure"; return false; }
        if (!in.cpuData) { lastError_ = "MF 编码器当前仅支持 CPU(BGRA) 输入"; return false; }

        bgraToNv12(in.cpuData, in.width, in.height, in.stride, nv12_);
        // v1.10：GOP=1 的 CODECAPI 可能被 MFT 忽略（部分属性拒绝）——若 GOP 配置
        // 未生效，重连后的手机端「等关键帧」会永久死锁（真机实锤）。兜底：每 60 帧
        // 强制一个 IDR，保证断链恢复后 ≤2s 内必有关键帧。
        if (++framesIn_ % 60 == 0) forceKeyFrame();
        if (!feedInput(in.ptsNs)) return false;
        // v1.10：drainOutput 无输出（NEED_MORE_INPUT，编码器内部延迟）不是失败——
        // 返回 true + 空 bytes，由调用方按「无输出可发」处理。
        drainOutput(out);
        // 元数据必须每次填（drainOutput 只填 bytes/pts/keyFrame）
        out.codec = params_.codec;
        out.width = in.width;
        out.height = in.height;
        out.frameRateX100 = params_.frameRateX100;
        out.dirty = in.dirty;
        return true;
    }

    bool forceKeyFrame() override {
        if (!codecApi_) return false;
        VARIANT v{};
        v.vt = VT_UI4;
        v.ulVal = 1;
        return SUCCEEDED(codecApi_->SetValue(&CODECAPI_AVEncVideoForceKeyFrame, &v));
    }

    /**
     * 运行期改码率（自适应码率用）。
     *
     * 两条路依次试（不同 MFT 脾气不一样）：
     *   ① `ICodecAPI::CODECAPI_AVEncCommonMeanBitRate` —— 流化后仍可改，GlobalLowDelayVBR 下立即生效；
     *   ② 改输出类型的 `MF_MT_AVG_BITRATE` 再 SetOutputType —— 少数 MFT 只认这条。
     * 这是"不重建编码器就能降码率"的关键：重建一次会掉 ~200ms 画面（见 encodeThread 的自愈注释）。
     */
    bool setBitrate(uint32_t kbps) override {
        if (kbps == 0) return false;
        params_.bitrateKbps = kbps;
        if (!ready_ || !transform_) return false;
        if (codecApi_) {
            VARIANT v{};
            v.vt = VT_UI4;
            v.ulVal = kbps * 1000;
            if (SUCCEEDED(codecApi_->SetValue(&CODECAPI_AVEncCommonMeanBitRate, &v))) return true;
        }
        ComPtr<IMFMediaType> t;
        if (SUCCEEDED(transform_->GetOutputCurrentType(0, &t)) && t) {
            if (SUCCEEDED(t->SetUINT32(MF_MT_AVG_BITRATE, kbps * 1000)) &&
                SUCCEEDED(transform_->SetOutputType(0, t.Get(), 0))) {
                return true;
            }
        }
        lastError_ = "运行期改码率被编码器拒绝";
        return false;
    }

    bool supportsRuntimeBitrate() const override { return true; }

    uint32_t bitrateKbps() const override { return params_.bitrateKbps; }

    void shutdown() override {
        if (transform_) {
            transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
        }
        transform_.Reset();
        codecApi_.Reset();
        eventGen_.Reset();
        ready_ = false;
        if (mfStarted_) { MFShutdown(); mfStarted_ = false; }
    }

    std::string lastError() const override { return lastError_; }

private:
    bool findEncoder(CodecId codec) {
        MFT_REGISTER_TYPE_INFO inType{MFMediaType_Video, MFVideoFormat_NV12};
        MFT_REGISTER_TYPE_INFO outType{MFMediaType_Video, codecGuid(codec)};
        IMFActivate** acts = nullptr;
        UINT32 count = 0;

        // v1.10：只枚举**同步** MFT——异步硬件 MFT（GPU 编码器）在未注入
        // IMFDXGIDeviceManager 时 SetInputType 必然失败（真机验证）。软编
        // （微软 H264 Encoder MFT，同步）零依赖直接可用；GPU 直通后接 D3D11
        // 管线时再开 HARDWARE|ASYNCMFT + DeviceManager。
        UINT32 flags = MFT_ENUM_FLAG_SORTANDFILTER | MFT_ENUM_FLAG_SYNCMFT;
        if (requireHardware_) flags |= MFT_ENUM_FLAG_HARDWARE;

        HRESULT hr = MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, flags, &inType, &outType, &acts, &count);
        if (FAILED(hr) || count == 0) {
            if (acts) CoTaskMemFree(acts);
            // 硬件枚举无果 -> 允许软编 MFT
            flags = MFT_ENUM_FLAG_SORTANDFILTER | MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_ASYNCMFT;
            hr = MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, flags, &inType, &outType, &acts, &count);
            if (FAILED(hr) || count == 0) {
                if (acts) CoTaskMemFree(acts);
                char buf[128];
                snprintf(buf, sizeof(buf), "未找到 %ls 编码器 MFT (hr=0x%08lX)",
                         codecWName(codec), static_cast<unsigned long>(hr));
                lastError_ = buf;
                return false;
            }
        }
        // 取第一个（SORTANDFILTER 已按优先级排序）
        activator_ = acts[0];
        for (UINT32 i = 0; i < count; ++i) if (acts[i]) acts[i]->Release();
        CoTaskMemFree(acts);
        return true;
    }

    bool openTransform() {
        if (FAILED(activator_->ActivateObject(IID_PPV_ARGS(&transform_))) || !transform_) {
            lastError_ = "ActivateObject 失败";
            return false;
        }
        transform_->QueryInterface(IID_PPV_ARGS(&codecApi_));
        transform_->QueryInterface(IID_PPV_ARGS(&eventGen_));
        async_ = eventGen_ != nullptr;

        // v1.10：异步 MFT（GPU 编码器）会报 MF_E_TRANSFORM_ASYNC_LOCKED 且需要
        // 完整 D3D 设备管线才能解锁使用——本实现不做 GPU 直通，异步对象一律
        // 释放并回退到微软同步软编（见 configure 的 CoCreateInstance 保底）。
        if (async_) {
            APX_LOG_W("枚举到异步 MFT（需 D3D 管线），放弃改走软编保底");
            transform_.Reset();
            lastError_ = "枚举到异步 MFT";
            return false;
        }

        // v1.10 诊断：记录命中的 MFT 名称（拼进 lastError 便于 probe 表展示）
        {
            ComPtr<IMFAttributes> attrs;
            wchar_t name[128] = L"?";
            UINT32 nameLen = 0;
            if (SUCCEEDED(transform_->GetAttributes(attrs.GetAddressOf())) &&
                SUCCEEDED(attrs->GetString(MFT_FRIENDLY_NAME_Attribute, name, 128, &nameLen))) {
                char nb[128];
                WideCharToMultiByte(CP_UTF8, 0, name, -1, nb, sizeof(nb), nullptr, nullptr);
                mftName_ = nb;
                APX_LOG_D("命中 MFT: %ls (async=%d)", name, async_ ? 1 : 0);
            }
        }

        if (!setMediaTypes()) return false;
        return true;
    }

    bool setMediaTypes() {
        // v1.10：编码器 MFT 的类型协商顺序——**必须先 SetOutputType 再 SetInputType**
        //（0xC00D6D77 = MF_E_TRANSFORM_TYPE_NOT_SET：编码器要先知道目标编码格式，
        // 才能决定接受的输入格式，真机 probe 实测）。
        ComPtr<IMFMediaType> outType;
        MFCreateMediaType(&outType);
        outType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        outType->SetGUID(MF_MT_SUBTYPE, codecGuid(params_.codec));
        MFSetAttributeSize(outType.Get(), MF_MT_FRAME_SIZE, params_.width, params_.height);
        MFSetAttributeRatio(outType.Get(), MF_MT_FRAME_RATE, params_.frameRateX100 / 100, 1);
        MFSetAttributeRatio(outType.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        outType->SetUINT32(MF_MT_AVG_BITRATE, params_.bitrateKbps * 1000);
        outType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        outType->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_High);
        HRESULT ohr = transform_->SetOutputType(0, outType.Get(), 0);
        if (FAILED(ohr)) {
            char buf[128];
            snprintf(buf, sizeof(buf), "SetOutputType 失败 hr=0x%08lX",
                     static_cast<unsigned long>(ohr));
            lastError_ = buf;
            return false;
        }

        ComPtr<IMFMediaType> inType;
        MFCreateMediaType(&inType);
        inType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        inType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
        MFSetAttributeSize(inType.Get(), MF_MT_FRAME_SIZE, params_.width, params_.height);
        MFSetAttributeRatio(inType.Get(), MF_MT_FRAME_RATE, params_.frameRateX100 / 100, 1);
        MFSetAttributeRatio(inType.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        inType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        inType->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, 1);
        // v1.10 诊断：先列出 MFT 声明的可用输入类型（前 3 项）
        for (DWORD i = 0; i < 3; ++i) {
            ComPtr<IMFMediaType> av;
            HRESULT ahr = transform_->GetInputAvailableType(0, i, av.GetAddressOf());
            if (FAILED(ahr)) break;
            GUID sub{};
            av->GetGUID(MF_MT_SUBTYPE, &sub);
            APX_LOG_D("输入可用类型[%u] = %08X-%04X-%04X-...", i,
                      sub.Data1, sub.Data2, sub.Data3);
        }
        HRESULT ihr = transform_->SetInputType(0, inType.Get(), 0);
        if (FAILED(ihr)) {
            char buf[192];
            snprintf(buf, sizeof(buf), "[%s] SetInputType(NV12) 失败 hr=0x%08lX",
                     mftName_.c_str(), static_cast<unsigned long>(ihr));
            lastError_ = buf;
            return false;
        }
        return true;
    }

    bool setLowLatencyOptions() {
        if (!codecApi_) { lastError_ = "无 ICodecAPI"; return false; }
        auto setUint = [&](const GUID& key, UINT32 v) {
            VARIANT var{};
            var.vt = VT_UI4;
            var.ulVal = v;
            return SUCCEEDED(codecApi_->SetValue(&key, &var));
        };
        auto setBool = [&](const GUID& key, bool v) {
            VARIANT var{};
            var.vt = VT_BOOL;
            var.boolVal = v ? VARIANT_TRUE : VARIANT_FALSE;
            return SUCCEEDED(codecApi_->SetValue(&key, &var));
        };

        bool ok = true;
        ok &= setBool(CODECAPI_AVEncCommonLowLatency, true);
        ok &= setUint(CODECAPI_AVEncCommonRateControlMode, 5);   // eAVEncCommonRateControlMode_GlobalLowDelayVBR
        ok &= setUint(CODECAPI_AVEncCommonMeanBitRate, params_.bitrateKbps * 1000);
        ok &= setUint(CODECAPI_AVEncMPVGOPSize, params_.gop);    // GOP = 1
        ok &= setUint(CODECAPI_AVEncVideoMaxNumRefFrame, 1);
        if (!ok) lastError_ = "部分低延迟属性被拒绝（编码器不支持）";
        return ok;
    }

    bool startStreaming() {
        if (FAILED(transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)) ||
            FAILED(transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0))) {
            lastError_ = "ProcessMessage(BEGIN/START) 失败";
            return false;
        }
        return true;
    }

    bool feedInput(int64_t ptsNs) {
        ComPtr<IMFSample> sample;
        ComPtr<IMFMediaBuffer> buffer;
        const size_t size = static_cast<size_t>(params_.width) * params_.height * 3 / 2;
        if (FAILED(MFCreateMemoryBuffer(static_cast<DWORD>(size), &buffer))) return false;
        BYTE* dst = nullptr;
        if (FAILED(buffer->Lock(&dst, nullptr, nullptr))) return false;
        std::memcpy(dst, nv12_.data(), size);
        buffer->SetCurrentLength(static_cast<DWORD>(size));
        buffer->Unlock();

        MFCreateSample(&sample);
        sample->AddBuffer(buffer.Get());
        sample->SetSampleTime(ptsNs / 100);  // MF 时间单位 100ns
        sample->SetSampleDuration(10000000 / (params_.frameRateX100 / 100));

        HRESULT hr = transform_->ProcessInput(0, sample.Get(), 0);
        if (hr == MF_E_NOTACCEPTING) {
            drainOutput(lastOut_);  // 先抽干输出再重试一次
            hr = transform_->ProcessInput(0, sample.Get(), 0);
        }
        if (FAILED(hr)) {
            char buf[96];
            snprintf(buf, sizeof(buf), "ProcessInput 失败 hr=0x%08lX", static_cast<unsigned long>(hr));
            lastError_ = buf;
            return false;
        }
        return true;
    }

    bool drainOutput(EncodedPacket& out) {
        DWORD status = 0;

        // v1.10 关键修复：MFT 输出 sample 所有权语义——
        //   MFT_OUTPUT_STREAM_PROVIDES_SAMPLES 置位 = MFT 自己分配（调用者不传）；
        //   **未置位（flags=0）= 调用者必须提供 pSample**，否则 ProcessOutput
        //   报 E_POINTER，输出永远取不走 → 输入队列堵死（ProcessInput
        //   MF_E_NOTACCEPTING，真机 0xC00D36B5 每帧复现）。此前判定写反了。
        MFT_OUTPUT_STREAM_INFO sinfo{};
        transform_->GetOutputStreamInfo(0, &sinfo);
        const bool needProvide = (sinfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) == 0;
        const DWORD outBufSize = sinfo.cbSize
            ? sinfo.cbSize
            : static_cast<DWORD>(static_cast<size_t>(params_.width) * params_.height * 3 / 2);
        if (!diagPrinted_) {
            APX_LOG_D("诊断: needProvide=%d flags=0x%08lX cbSize=%lu async=%d",
                      needProvide ? 1 : 0, static_cast<unsigned long>(sinfo.dwFlags),
                      static_cast<unsigned long>(sinfo.cbSize), async_ ? 1 : 0);
            diagPrinted_ = true;
        }

        for (int attempt = 0; attempt < 8; ++attempt) {
            MFT_OUTPUT_DATA_BUFFER outBuf{};
            ComPtr<IMFSample> sample;
            ComPtr<IMFMediaBuffer> ob;
            if (needProvide) {
                if (FAILED(MFCreateMemoryBuffer(outBufSize, &ob))) return false;
                if (FAILED(MFCreateSample(&sample))) return false;
                sample->AddBuffer(ob.Get());
                outBuf.pSample = sample.Get();
            }
            HRESULT hr = transform_->ProcessOutput(0, 1, &outBuf, &status);
            if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
                if (!diagPrinted2_) {
                    APX_LOG_D("诊断: ProcessOutput NEED_MORE_INPUT（第 %d 次尝试）", attempt);
                    diagPrinted2_ = true;
                }
                return false;
            }
            if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
                // 输出类型变化：重新协商一次
                ComPtr<IMFMediaType> newType;
                if (SUCCEEDED(transform_->GetOutputCurrentType(0, &newType)) && newType) {
                    newType->SetUINT32(MF_MT_AVG_BITRATE, params_.bitrateKbps * 1000);
                    transform_->SetOutputType(0, newType.Get(), 0);
                }
                if (outBuf.pEvents) outBuf.pEvents->Release();
                continue;
            }
            if (FAILED(hr)) {
                if (outBuf.pEvents) outBuf.pEvents->Release();
                char buf[96];
                snprintf(buf, sizeof(buf), "ProcessOutput 失败 hr=0x%08lX", static_cast<unsigned long>(hr));
                lastError_ = buf;
                return false;
            }
            // 取回输出 sample：PROVIDE 模式下是我们自己的 sample（已有引用），
            // 非 PROVIDE 模式下 MFT 给的 pSample 归调用方（转移所有权防泄漏）。
            ComPtr<IMFSample> owned;
            if (needProvide) {
                owned = sample;
            } else {
                owned.Attach(outBuf.pSample);
                outBuf.pSample = nullptr;
            }
            if (outBuf.pEvents) outBuf.pEvents->Release();
            if (!owned) continue;

            ComPtr<IMFMediaBuffer> buf;
            if (FAILED(owned->ConvertToContiguousBuffer(&buf)) || !buf) continue;
            BYTE* data = nullptr;
            DWORD len = 0;
            if (FAILED(buf->Lock(&data, nullptr, &len))) continue;
            out.bytes.assign(data, data + len);
            buf->Unlock();

            UINT32 clean = 0;
            owned->GetUINT32(MFSampleExtension_CleanPoint, &clean);
            // v1.10：首帧必为 IDR——部分 MFT 不设置 CleanPoint 扩展，若首帧不标
            // 关键帧，手机端「等关键帧」逻辑会丢弃所有后续帧（死锁，真机实锤）。
            out.keyFrame = clean != 0 || framesOut_ == 0;
            ++framesOut_;
            LONGLONG pts100ns = 0;
            if (SUCCEEDED(owned->GetSampleTime(&pts100ns))) out.ptsNs = pts100ns * 100;
            return true;
        }
        return false;
    }

    bool requireHardware_ = true;
    bool comInitialized_ = false;
    bool mfStarted_ = false;
    bool ready_ = false;
    std::string mftName_;
    bool async_ = false;
    bool diagPrinted_ = false;
    bool diagPrinted2_ = false;
    size_t framesOut_ = 0;
    size_t framesIn_ = 0;
    VideoParams params_{};
    std::string lastError_;
    std::vector<uint8_t> nv12_;
    EncodedPacket lastOut_;
    ComPtr<IMFActivate> activator_;
    ComPtr<IMFTransform> transform_;
    ComPtr<ICodecAPI> codecApi_;
    ComPtr<IMFMediaEventGenerator> eventGen_;
};

std::unique_ptr<IEncoder> createMfEncoder() { return std::make_unique<MfEncoder>(true); }

}  // namespace apxdisp

#endif  // _WIN32
