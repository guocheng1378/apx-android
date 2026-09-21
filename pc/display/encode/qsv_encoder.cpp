// Intel Quick Sync 编码器（oneVPL / MediaSDK，动态加载）
//
// 依次尝试 libvpl.dll（oneVPL，Intel 新栈）→ libmfx64-gen.dll（新驱动）→ libmfx64.dll（旧 MSDK），
// 任一可用即 MFXInit(MFX_IMPL_HARDWARE_ANY) 建立会话。
// 需要 Intel oneVPL 头文件：-DAPXDISP_HAVE_VPL_SDK=ON 且 <vpl/mfxvideo.h> 可见；
// 否则本文件编译为「不可用」桩。
#include "encode/i_encoder.hpp"

#include <cstring>
#include <string>
#include <vector>

#include "common/log.hpp"
#include "encode/dynlib.hpp"

#ifdef APXDISP_HAVE_VPL_SDK
#include <vpl/mfxstructures.h>
#include <vpl/mfxvideo.h>
#endif

namespace apxdisp {

#ifdef APXDISP_HAVE_VPL_SDK

class QsvEncoder : public IEncoder {
public:
    ~QsvEncoder() override { shutdown(); }

    EncoderBackend backend() const override { return EncoderBackend::QuickSync; }

    bool configure(const VideoParams& params) override {
        params_ = params;
        const char* candidates[] = {"libvpl.dll", "libmfx64-gen.dll", "libmfx64.dll"};
        bool loaded = false;
        for (const char* name : candidates) {
            if (lib_.load(name)) { loaded = true; APX_LOG_D("QuickSync: 加载 %s", name); break; }
        }
        if (!loaded) {
            lastError_ = "未找到 oneVPL/MSDK 运行时（libvpl.dll / libmfx64-gen.dll / libmfx64.dll）";
            return false;
        }

        mfxVersion ver{};
        ver.Major = 1;
        ver.Minor = 0;
        if (MFXInit(MFX_IMPL_HARDWARE_ANY, &ver, &session_) != MFX_ERR_NONE || !session_) {
            lastError_ = "MFXInit 失败（无 Intel 核显或驱动过旧）";
            return false;
        }

        mfxVideoParam par{};
        par.mfx.CodecID = (params_.codec == CodecId::AV1) ? MFX_CODEC_AV1
                        : (params_.codec == CodecId::H264 ? MFX_CODEC_AVC : MFX_CODEC_HEVC);
        par.mfx.TargetUsage = MFX_TARGETUSAGE_1;                 // 最快档
        par.mfx.GopPicSize = params_.gop;                        // = 1 -> 全 I
        par.mfx.GopRefDist = 1;                                  // 无 B 帧
        par.mfx.GopOptFlag = MFX_GOP_CLOSED;
        par.mfx.RateControlMethod = MFX_RATECONTROL_CBR;
        par.mfx.TargetKbps = static_cast<mfxU16>(params_.bitrateKbps);
        par.mfx.MaxKbps = static_cast<mfxU16>(params_.bitrateKbps);
        par.mfx.FrameInfo.FourCC = MFX_FOURCC_NV12;
        par.mfx.FrameInfo.ChromaFormat = MFX_CHROMAFORMAT_YUV420;
        par.mfx.FrameInfo.Width = static_cast<mfxU16>((params_.width + 15) & ~15u);
        par.mfx.FrameInfo.Height = static_cast<mfxU16>((params_.height + 15) & ~15u);
        par.mfx.FrameInfo.CropW = static_cast<mfxU16>(params_.width);
        par.mfx.FrameInfo.CropH = static_cast<mfxU16>(params_.height);
        par.mfx.FrameInfo.PicStruct = MFX_PICSTRUCT_PROGRESSIVE;
        par.mfx.FrameInfo.FrameRateExtN = params_.frameRateX100 / 100;
        par.mfx.FrameInfo.FrameRateExtD = 1;
        par.AsyncDepth = 1;                                      // 最低延迟
        par.IOPattern = MFX_IOPATTERN_IN_SYSTEM_MEMORY;

        mfxStatus st = MFXVideoENCODE_Init(session_, &par);
        if (st < MFX_ERR_NONE) {
            lastError_ = "MFXVideoENCODE_Init 失败 st=" + std::to_string(st);
            MFXClose(session_);
            session_ = nullptr;
            return false;
        }

        surfaceInfo_ = par.mfx.FrameInfo;
        bs_.resize(static_cast<size_t>(params_.bitrateKbps) * 1024 / 8);
        memset(&bitstream_, 0, sizeof(bitstream_));
        bitstream_.MaxLength = static_cast<mfxU32>(bs_.size());
        bitstream_.Data = bs_.data();

        ready_ = true;
        APX_LOG_I("QuickSync 就绪：%s %ux%u GOP=%u", codecName(params_.codec), params_.width,
                  params_.height, params_.gop);
        return true;
    }

    bool encode(const RawFrame& in, EncodedPacket& out) override {
        if (!ready_) { lastError_ = "未 configure"; return false; }
        if (!in.cpuData) { lastError_ = "QuickSync 当前仅支持系统内存输入"; return false; }

        mfxFrameSurface1 surf{};
        memset(&surf, 0, sizeof(surf));
        surf.Info = surfaceInfo_;
        surf.Data.Y = const_cast<mfxU8*>(in.cpuData);
        surf.Data.U = surf.Data.Y + static_cast<mfxU32>(in.width) * in.height;
        surf.Data.V = surf.Data.U + 1;
        surf.Data.Pitch = static_cast<mfxU16>(in.width);

        mfxSyncPoint sync{};
        mfxStatus st = MFX_ERR_NONE;
        for (int attempt = 0; attempt < 4; ++attempt) {
            st = MFXVideoENCODE_EncodeFrameAsync(session_, nullptr, &surf, &bitstream_, &sync);
            if (st == MFX_ERR_MORE_DATA) { ++attemptsDrained_; continue; }
            if (st == MFX_WRN_DEVICE_BUSY) { continue; }
            if (st < MFX_ERR_NONE) { lastError_ = "EncodeFrameAsync 失败 st=" + std::to_string(st); return false; }
            break;
        }
        if (st == MFX_ERR_MORE_DATA) return false;
        if (MFXVideoCORE_SyncOperation(session_, sync, 60000) != MFX_ERR_NONE) {
            lastError_ = "SyncOperation 超时"; return false;
        }

        out.bytes.assign(bitstream_.Data + bitstream_.DataOffset,
                         bitstream_.Data + bitstream_.DataOffset + bitstream_.DataLength);
        out.keyFrame = (bitstream_.FrameType & MFX_FRAMETYPE_I) != 0;
        out.ptsNs = in.ptsNs;
        out.codec = params_.codec;
        out.width = in.width;
        out.height = in.height;
        out.frameRateX100 = params_.frameRateX100;
        out.dirty = in.dirty;
        bitstream_.DataLength = 0;
        bitstream_.DataOffset = 0;
        return true;
    }

    bool forceKeyFrame() override { return MFXVideoENCODE_Reset(session_, nullptr) == MFX_ERR_NONE; }

    void shutdown() override {
        if (session_) {
            MFXVideoENCODE_Close(session_);
            MFXClose(session_);
        }
        session_ = nullptr;
        lib_.unload();
        ready_ = false;
    }

    std::string lastError() const override { return lastError_; }

private:
    DynLib lib_;
    mfxSession session_ = nullptr;
    mfxBitstream bitstream_{};
    mfxFrameInfo surfaceInfo_{};
    std::vector<mfxU8> bs_;
    int attemptsDrained_ = 0;
    bool ready_ = false;
    VideoParams params_{};
    std::string lastError_;
};

#else  // !APXDISP_HAVE_VPL_SDK

class QsvEncoder : public IEncoder {
public:
    EncoderBackend backend() const override { return EncoderBackend::QuickSync; }
    bool configure(const VideoParams&) override {
        lastError_ = "未启用 QuickSync：缺少 oneVPL 头文件（<vpl/mfxvideo.h>），"
                     "以 -DAPXDISP_HAVE_VPL_SDK=ON 重新配置";
        return false;
    }
    bool encode(const RawFrame&, EncodedPacket&) override { return false; }
    bool forceKeyFrame() override { return false; }
    void shutdown() override {}
    std::string lastError() const override { return lastError_; }

private:
    std::string lastError_;
};

#endif  // APXDISP_HAVE_VPL_SDK

std::unique_ptr<IEncoder> createQsvEncoder() { return std::make_unique<QsvEncoder>(); }

}  // namespace apxdisp
