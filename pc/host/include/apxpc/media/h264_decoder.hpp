#pragma once
// 摄像头预览 H264 解码（MF 裸 MFT）：手机端硬编出的 AnnexB AccessUnit 喂进来，
// 解出 BGRA 像素供面板 GDI 直绘。单实例单线程使用（媒体收流线程）。
//
// 细节：MediaCodec 出的是 AnnexB（00 00 00 01 起始码）byte stream，MFT H264 解码器
// 原生接受；SPS/PPS 作为独立 AU 先行喂入后 MFT 即可探测出分辨率。

#include <string>
#include <vector>

#ifdef _WIN32

namespace apxpc::media {

class H264Decoder {
public:
    H264Decoder();
    ~H264Decoder();
    H264Decoder(const H264Decoder&) = delete;
    H264Decoder& operator=(const H264Decoder&) = delete;

    /// 解一帧（一个 AccessUnit）。成功且产出画面时返回 true，bgra 里是
    /// 自顶向下 BGRA（stride = width*4）。
    bool decode(const uint8_t* au, size_t len);

    const std::vector<uint8_t>& bgra() const { return bgra_; }
    uint32_t width() const { return width_; }
    uint32_t height() const { return height_; }
    bool ok() const { return inited_; }
    bool outTypeSet() const { return outTypeSet_; }
    std::string lastError() const { return lastError_; }
    // 诊断探针：输入帧数 / 解出帧数 / 最近一次失败的 HRESULT
    uint64_t inFrames() const { return inFrames_; }
    uint64_t outFrames() const { return outFrames_; }
    uint32_t lastHr() const { return lastHr_; }

private:
    bool init();

    bool inited_ = false;
    void* mft_ = nullptr;              // IMFTransform*
    unsigned long evts_ = 0;           // IMFMediaEventGenerator*（异步 MFT 才有，同步为空）
    std::vector<uint8_t> bgra_;
    uint32_t width_ = 0, height_ = 0;
    bool gotInputType_ = false;
    bool outTypeSet_ = false;          // RGB32 输出类型是否已设（需首帧探测出分辨率后再设）
    std::string lastError_;
    uint64_t inFrames_ = 0, outFrames_ = 0;
    uint32_t lastHr_ = 0;
};

}  // namespace apxpc::media

#endif  // _WIN32
