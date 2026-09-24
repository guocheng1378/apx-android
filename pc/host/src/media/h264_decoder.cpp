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
#include <codecapi.h>     // CODECAPI_AVLowLatencyMode
#include <wmcodecdsp.h>   // CLSID_CMSH264DecoderMFT（微软官方 H264 软解 MFT）+ ICodecAPI 接口
#include <wrl/client.h>

#pragma comment(lib, "wmcodecdspuuid")   // CLSID_CMSH264DecoderMFT

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

    // **首选微软官方软解 MFT**（CLSID_CMSH264DecoderMFT，微软文档推荐直接
    // CoCreateInstance）：MFTEnumEx 枚举到的第一个可能是行为怪异的第三方/硬件
    // 解码器（真机踩过：ProcessInput 全收、永不出帧）。官方失败再退回枚举。
    ComPtr<IMFTransform> t;
    hr = CoCreateInstance(CLSID_CMSH264DecoderMFT, nullptr, CLSCTX_INPROC_SERVER,
                          IID_PPV_ARGS(&t));
    if (FAILED(hr)) {
        // 回退：枚举
        IMFActivate** acts = nullptr;
        UINT32 count = 0;
        MFT_REGISTER_TYPE_INFO in{ MFMediaType_Video, MFVideoFormat_H264 };
        hr = MFTEnumEx(MFT_CATEGORY_VIDEO_DECODER,
                       MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER,
                       &in, nullptr, &acts, &count);
        if (SUCCEEDED(hr) && count > 0) {
            hr = acts[0]->ActivateObject(IID_PPV_ARGS(&t));
        } else {
            lastError_ = "未找到任何 H264 解码器 MFT";
        }
        if (acts) {
            for (UINT32 i = 0; i < count; ++i) acts[i]->Release();
            CoTaskMemFree(acts);
        }
        if (FAILED(hr)) return false;
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
    // 输出类型**不在这里设置**：未喂帧时 MFT 还不知道分辨率，提前设置的类型在
    // 首帧后必然触发 STREAM_CHANGE，而二次 SetOutputType 会被拒 → 永远出不了帧。
    // 统一策略：decode() 里喂入 SPS/PPS 后再枚举设置（一处、只设一次）。
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

/// 枚举 MFT 提供的输出类型，挑 NV12 设置。分辨率探测交给 MFT（码流 SPS 决定）。
/// 返回是否设置成功；STREAM_CHANGE 后也可复用来重建输出类型。
static bool trySetOutputType(IMFTransform* t) {
    for (DWORD ti = 0; ; ++ti) {
        ComPtr<IMFMediaType> avail;
        if (FAILED(t->GetOutputAvailableType(0, ti, &avail))) break;
        GUID sub{};
        if (FAILED(avail->GetGUID(MF_MT_SUBTYPE, &sub))) continue;
        if (sub == MFVideoFormat_NV12) {
            return SUCCEEDED(t->SetOutputType(0, avail.Get(), 0));
        }
    }
    return false;
}

bool H264Decoder::decode(const uint8_t* au, size_t len) {
    if (!init()) return false;
    auto* t = static_cast<IMFTransform*>(mft_);
    if (!au || len == 0) return false;

    // 输入样本：**载荷格式自适应**（手机端 MediaCodec 两种输出都可能出现）
    //   · AnnexB（00 00 00 01 / 00 00 01 起始码）→ 原样喂
    //   · AVCC（4 字节大端长度前缀，CSD 配置帧常见）→ 长度前缀换成 4 字节起始码
    // 旧版一律"再垫一层起始码"，遇到 AVCC 的 SPS/PPS 会把整段解坏 → 永不出帧。
    size_t prefix = 0;
    const bool isAnnexB = (len >= 4 && au[0] == 0 && au[1] == 0 &&
                           ((au[2] == 1) || (au[2] == 0 && au[3] == 1)));
    const bool isAvcc = !isAnnexB && len >= 5 &&
                        (static_cast<uint32_t>(au[0]) << 24 | static_cast<uint32_t>(au[1]) << 16 |
                         static_cast<uint32_t>(au[2]) << 8 | au[3]) == len - 4;
    const size_t payload = isAvcc ? len - 4 : len;
    if (!isAnnexB) prefix = 4;

    ComPtr<IMFSample> sample;
    if (FAILED(MFCreateSample(&sample))) { lastError_ = "MFCreateSample 失败"; return false; }
    ComPtr<IMFMediaBuffer> buf;
    if (FAILED(MFCreateMemoryBuffer(static_cast<DWORD>(payload + prefix), &buf))) {
        lastError_ = "MFCreateMemoryBuffer 失败";
        return false;
    }
    BYTE* p = nullptr;
    if (FAILED(buf->Lock(&p, nullptr, nullptr))) return false;
    static const BYTE sc[4] = {0, 0, 0, 1};
    if (prefix) std::memcpy(p, sc, 4);
    std::memcpy(p + prefix, au + (isAvcc ? 4 : 0), payload);
    buf->Unlock();
    buf->SetCurrentLength(static_cast<DWORD>(payload + prefix));
    if (isAvcc) ++avccFrames_;
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
        outTypeSet_ = trySetOutputType(t);
        if (outTypeSet_) {
            // 官方消息顺序：类型齐 → BEGIN_STREAMING → START_OF_STREAM
            t->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
            t->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
        }
    }

    // 循环取输出（可能先 NEED_MORE_INPUT，SPS/PPS+IDR 一起喂完后出帧）
    bool produced = false;
    while (true) {
        MFT_OUTPUT_STREAM_INFO sinfo{};
        t->GetOutputStreamInfo(0, &sinfo);
        // **不要用 GetOutputStatus 当门禁**（真机踩坑）：微软 H264 软解 MFT 是同步
        // MFT，GetOutputStatus 返回 S_OK 但状态位恒为 0 —— 按它判断会一次都不调
        // ProcessOutput，结果"输入收满、输出为零、HRESULT 还是 0"（正是黑屏现象）。
        // 正确做法：直接调 ProcessOutput，NEED_MORE_INPUT 会自然返回。
        DWORD status = 0;
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
        const HRESULT hr = t->ProcessOutput(0, 1, &odb, &status);
        ++poCalls_;
        if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) { ++poNeedMore_; break; }   // 正常：等下一帧输入
        if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
            lastHr_ = static_cast<uint32_t>(hr);
            // 分辨率变化：MFT 已把输出类型作废，必须重新枚举设置 NV12，
            // 否则下一次 ProcessOutput 继续报 STREAM_CHANGE → 死循环永无帧。
            width_ = 0; height_ = 0;
            bgra_.clear();
            outTypeSet_ = trySetOutputType(t);
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
