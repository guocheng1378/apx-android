#include "apxpc/media/h264_decoder.hpp"

#ifdef _WIN32

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>
#include <mferror.h>
#include <codecapi.h>
#include <icodecapi.h>
#include <wrl/client.h>

#include <algorithm>
#include <cstring>

#pragma comment(lib, "mfplat")
#pragma comment(lib, "mfuuid")
#pragma comment(lib, "mf")

using Microsoft::WRL::ComPtr;

namespace apxpc::media {

namespace {
// GUID 静态库链接（mfuuid.lib 提供，无需自定定义）
}  // namespace

H264Decoder::H264Decoder() = default;

H264Decoder::~H264Decoder() {
    if (mft_) {
        auto* t = static_cast<IMFTransform*>(mft_);
        t->Release();
        mft_ = nullptr;
    }
    // MFShutdown 与 MFStartup 成对（多实例安全：引用计数）
    MFShutdown();
}

bool H264Decoder::init() {
    if (inited_) return true;
    HRESULT hr = MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);
    if (FAILED(hr) && hr != MF_E_ALREADY_INITIALIZED) {
        lastError_ = "MFStartup 失败";
        return false;
    }

    // 枚举 H264 解码器 MFT（软件/硬件皆可）
    IMFActivate** acts = nullptr;
    UINT32 count = 0;
    MFT_REGISTER_TYPE_INFO in{ MFMediaType_Video, MFVideoFormat_H264 };
    hr = MFTEnumEx(MFT_CATEGORY_VIDEO_DECODER,
                   MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER,
                   &in, nullptr, &acts, &count);
    if (FAILED(hr) || count == 0) {
        lastError_ = "未找到 H264 解码器 MFT";
        if (acts) {
            for (UINT32 i = 0; i < count; ++i) acts[i]->Release();
            CoTaskMemFree(acts);
        }
        return false;
    }
    ComPtr<IMFTransform> t;
    hr = acts[0]->ActivateObject(IID_PPV_ARGS(&t));
    for (UINT32 i = 0; i < count; ++i) acts[i]->Release();
    CoTaskMemFree(acts);
    if (FAILED(hr)) {
        lastError_ = "激活解码器失败";
        return false;
    }

    // 输入：H264（不给尺寸，MFT 从码流 SPS 自探测）
    ComPtr<IMFMediaType> inType;
    MFCreateMediaType(&inType);
    inType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    inType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    hr = t->SetInputType(0, inType.Get(), 0);
    if (FAILED(hr)) {
        lastError_ = "SetInputType(H264) 失败";
        return false;
    }
    // 输出：RGB32（免手动 NV12→RGB）
    ComPtr<IMFMediaType> outType;
    MFCreateMediaType(&outType);
    outType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    outType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    hr = t->SetOutputType(0, outType.Get(), 0);
    if (FAILED(hr)) {
        lastError_ = "SetOutputType(RGB32) 失败";
        return false;
    }
    // 低延迟（Win8+ 的 H264 MFT 支持）
    ComPtr<ICodecAPI> codec;
    if (SUCCEEDED(t.As(&codec))) {
        VARIANT v{}; v.vt = VT_BOOL; v.boolVal = VARIANT_TRUE;
        codec->SetValue(&CODECAPI_AVLowLatencyMode, &v);
    }
    hr = t->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);

    mft_ = t.Detach();
    inited_ = true;
    return true;
}

bool H264Decoder::decode(const uint8_t* au, size_t len) {
    if (!init()) return false;
    auto* t = static_cast<IMFTransform*>(mft_);
    if (!au || len == 0) return false;

    // 输入样本
    ComPtr<IMFSample> sample;
    if (FAILED(MFCreateSample(&sample))) { lastError_ = "MFCreateSample 失败"; return false; }
    ComPtr<IMFMediaBuffer> buf;
    if (FAILED(MFCreateMemoryBuffer(static_cast<DWORD>(len + 4), &buf))) {
        lastError_ = "MFCreateMemoryBuffer 失败";
        return false;
    }
    BYTE* p = nullptr;
    if (FAILED(buf->Lock(&p, nullptr, nullptr))) return false;
    // AnnexB 4 字节起始码（MediaCodec 输出即 0001；保险再垫一层起始码）
    static const BYTE sc[4] = {0, 0, 0, 1};
    std::memcpy(p, sc, 4);
    std::memcpy(p + 4, au, len);
    buf->Unlock();
    buf->SetCurrentLength(static_cast<DWORD>(len + 4));
    sample->AddBuffer(buf.Get());
    sample->SetSampleTime(0);
    sample->SetSampleDuration(0);
    if (FAILED(t->ProcessInput(0, sample.Get(), 0))) {
        // 输入未接受（MFT 内部缓冲满等）：不打紧，下一帧再试
        return false;
    }

    // 循环取输出（可能先 NEED_MORE_INPUT，SPS/PPS+IDR 一起喂完后出帧）
    bool produced = false;
    while (true) {
        MFT_OUTPUT_STREAM_INFO sinfo{};
        t->GetOutputStreamInfo(0, &sinfo);
        DWORD status = 0;
        HRESULT hr = t->GetOutputStatus(&status);
        if (FAILED(hr)) break;
        if (!(status & MFT_OUTPUT_STATUS_SAMPLE_READY)) break;

        MFT_OUTPUT_DATA_BUFFER odb{};
        odb.dwStreamID = 0;
        ComPtr<IMFSample> out;
        ComPtr<IMFMediaBuffer> ob;
        if (!(sinfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
            DWORD cb = sinfo.cbSize ? sinfo.cbSize : (1920 * 1080 * 4);
            if (FAILED(MFCreateMemoryBuffer(cb, &ob))) break;
            if (FAILED(MFCreateSample(&out))) break;
            out->AddBuffer(ob.Get());
            odb.pSample = out.Get();
        }
        hr = t->ProcessOutput(0, 1, &odb, &status);
        if (FAILED(hr)) break;
        if (odb.pSample) out = odb.pSample;
        if (!out) break;

        // 读出尺寸与像素
        ComPtr<IMFMediaType> omt;
        if (SUCCEEDED(t->GetOutputCurrentType(0, &omt))) {
            UINT32 w = 0, h = 0;
            if (SUCCEEDED(MFGetAttributeSize(omt.Get(), MF_MT_FRAME_SIZE, &w, &h)) &&
                (w != width_ || h != height_)) {
                width_ = w; height_ = h;
                bgra_.assign(static_cast<size_t>(w) * h * 4, 0);
            }
        }
        ComPtr<IMFMediaBuffer> obuf;
        if (FAILED(out->ConvertToContiguousBuffer(&obuf))) break;
        BYTE* pp = nullptr;
        DWORD cbMax = 0;
        if (FAILED(obuf->Lock(&pp, nullptr, &cbMax))) break;
        const size_t need = static_cast<size_t>(width_) * height_ * 4;
        if (width_ > 0 && height_ > 0 && cbMax >= need) {
            std::memcpy(bgra_.data(), pp, need);
            produced = true;
        }
        obuf->Unlock();
    }
    return produced;
}

}  // namespace apxpc::media

#endif  // _WIN32
