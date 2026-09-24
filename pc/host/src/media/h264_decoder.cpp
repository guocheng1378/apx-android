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
    // **调用线程必须初始化 COM**：解码跑在媒体收流线程（无 COM 环境），
    // 不初始化则 MFTEnumEx/MFCreateX 全部静默失败（真机踩过：帧在收、画面全黑）。
    // 引用计数式初始化，线程长存即可，不做配对释放。
    ::CoInitializeEx(nullptr, COINIT_MULTITHREADED);
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
    // 官方流程：输入类型设置后**立即**从 MFT 枚举可用输出类型（自带分辨率，NV12 通常排第一）。
    // 之前自己造的 NV12（无尺寸）被拒 → 输出类型一直没设 → 永远 NEED_MORE_INPUT。
    for (DWORD ti = 0; ; ++ti) {
        ComPtr<IMFMediaType> avail;
        if (FAILED(t->GetOutputAvailableType(0, ti, &avail))) break;
        GUID sub{};
        if (FAILED(avail->GetGUID(MF_MT_SUBTYPE, &sub))) continue;
        if (sub == MFVideoFormat_NV12) {
            if (SUCCEEDED(t->SetOutputType(0, avail.Get(), 0))) {
                outTypeSet_ = true;
                UINT32 w = 0, h = 0;
                if (SUCCEEDED(MFGetAttributeSize(avail.Get(), MF_MT_FRAME_SIZE, &w, &h))) {
                    width_ = w; height_ = h;
                    bgra_.assign(static_cast<size_t>(w) * h * 4, 0);
                }
            }
            break;
        }
    }
    // 输出类型**不在这里设置**：未喂帧时 MFT 还不知道分辨率，NV12/RGB32 都会被拒——
    // 而 decode 必须先 ProcessInput（喂入 SPS/PPS）MFT 才知道尺寸。顺序见 decode()。
    outTypeSet_ = false;
    // 低延迟（Win8+ 的 H264 MFT 支持）
    ComPtr<ICodecAPI> codec;
    if (SUCCEEDED(t.As(&codec))) {
        VARIANT v{}; v.vt = VT_BOOL; v.boolVal = VARIANT_TRUE;
        codec->SetValue(&CODECAPI_AVLowLatencyMode, &v);
    }

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
    // 递增时间戳（100ns 单位，按 15fps 估）：H264 MFT 对全 0 时间戳会卡住帧缓冲不出帧
    sample->SetSampleTime(static_cast<LONGLONG>(inFrames_) * 666667);
    sample->SetSampleDuration(666667);
    const HRESULT inHr = t->ProcessInput(0, sample.Get(), 0);
    if (FAILED(inHr)) {
        // 输入未接受（MFT 内部缓冲满等）：不打紧，下一帧再试
        lastHr_ = static_cast<uint32_t>(inHr);
        return false;
    }
    inFrames_++;

    // **喂入首帧（SPS/PPS）之后**再设输出类型。自己造的 NV12 类型（无尺寸）会被拒——
    // 正确做法：**枚举 MFT 提供的可用输出类型**（自带分辨率信息），挑 NV12 设置。
    if (!outTypeSet_) {
        for (DWORD ti = 0; ; ++ti) {
            ComPtr<IMFMediaType> avail;
            if (FAILED(t->GetOutputAvailableType(0, ti, &avail))) break;
            GUID sub{};
            if (FAILED(avail->GetGUID(MF_MT_SUBTYPE, &sub))) continue;
            if (sub == MFVideoFormat_NV12) {
                if (SUCCEEDED(t->SetOutputType(0, avail.Get(), 0))) {
                    outTypeSet_ = true;
                    // 官方消息顺序：类型齐 → BEGIN_STREAMING → START_OF_STREAM
                    t->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
                    t->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
                }
                break;
            }
        }
    }

    // 循环取输出（可能先 NEED_MORE_INPUT，SPS/PPS+IDR 一起喂完后出帧）
    bool produced = false;
    while (true) {
        MFT_OUTPUT_STREAM_INFO sinfo{};
        t->GetOutputStreamInfo(0, &sinfo);
        DWORD status = 0;
        // 注意：不少同步 MFT 对 GetOutputStatus 返回 E_NOTIMPL——失败**不退出**，
        // 直接尝试 ProcessOutput（NEED_MORE_INPUT 会自然返回）
        HRESULT hr = t->GetOutputStatus(&status);
        if (SUCCEEDED(hr) && !(status & MFT_OUTPUT_STATUS_SAMPLE_READY)) break;

        MFT_OUTPUT_DATA_BUFFER odb{};
        odb.dwStreamID = 0;
        ComPtr<IMFSample> out;
        ComPtr<IMFMediaBuffer> ob;
        if (!(sinfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
            DWORD cb = sinfo.cbSize ? sinfo.cbSize : (1920 * 1088 * 3 / 2);
            if (FAILED(MFCreateMemoryBuffer(cb, &ob))) break;
            if (FAILED(MFCreateSample(&out))) break;
            out->AddBuffer(ob.Get());
            odb.pSample = out.Get();
        }
        hr = t->ProcessOutput(0, 1, &odb, &status);
        if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) break;      // 正常：等下一帧输入
        if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
            lastHr_ = static_cast<uint32_t>(hr);
            // 分辨率变化：NV12 输出类型保持（MFT 自动适配），尺寸重新探测
            width_ = 0; height_ = 0;
            bgra_.clear();
            continue;
        }
        if (FAILED(hr)) {
            lastHr_ = static_cast<uint32_t>(hr);
            break;
        }
        if (odb.pSample) out = odb.pSample;
        if (!out) break;

        // 读出尺寸
        ComPtr<IMFMediaType> omt;
        if (SUCCEEDED(t->GetOutputCurrentType(0, &omt))) {
            UINT32 w = 0, h = 0;
            if (SUCCEEDED(MFGetAttributeSize(omt.Get(), MF_MT_FRAME_SIZE, &w, &h)) &&
                (w != width_ || h != height_)) {
                width_ = w; height_ = h;
                bgra_.assign(static_cast<size_t>(w) * h * 4, 0);
            }
        }
        if (width_ == 0 || height_ == 0) break;

        // NV12 → BGRA（Y 平面 + UV 交错平面，BT.601）
        ComPtr<IMFMediaBuffer> obuf;
        if (FAILED(out->ConvertToContiguousBuffer(&obuf))) break;
        BYTE* pp = nullptr;
        DWORD cbMax = 0;
        if (FAILED(obuf->Lock(&pp, nullptr, &cbMax))) break;
        const int W = static_cast<int>(width_), H = static_cast<int>(height_);
        const size_t need = static_cast<size_t>(W) * H * 3 / 2;
        if (cbMax >= need) {
            const uint8_t* Y = pp;
            const uint8_t* UV = pp + static_cast<size_t>(W) * H;
            auto* dst = reinterpret_cast<uint32_t*>(bgra_.data());
            for (int y = 0; y < H; ++y) {
                const uint8_t* yrow = Y + static_cast<size_t>(y) * W;
                const uint8_t* uvrow = UV + static_cast<size_t>(y >> 1) * W;
                auto* drow = dst + static_cast<size_t>(y) * W;
                for (int x = 0; x < W; ++x) {
                    const int yy = yrow[x] - 16;
                    const int u = uvrow[x & ~1] - 128;
                    const int v = uvrow[(x & ~1) + 1] - 128;
                    int r = (298 * yy + 409 * v + 128) >> 8;
                    int gg = (298 * yy - 100 * u - 208 * v + 128) >> 8;
                    int b = (298 * yy + 516 * u + 128) >> 8;
                    r = r < 0 ? 0 : (r > 255 ? 255 : r);
                    gg = gg < 0 ? 0 : (gg > 255 ? 255 : gg);
                    b = b < 0 ? 0 : (b > 255 ? 255 : b);
                    drow[x] = static_cast<uint32_t>(b | (gg << 8) | (r << 16) | 0xFF000000u);
                }
            }
            produced = true;
            outFrames_++;
        }
        obuf->Unlock();
    }
    return produced;
}

}  // namespace apxpc::media

#endif  // _WIN32
