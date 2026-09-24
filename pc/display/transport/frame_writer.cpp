#include "transport/frame_writer.hpp"

#include <cstring>
#include <vector>

#include "common/log.hpp"

namespace apxdisp {

std::vector<uint8_t> FrameWriter::buildVideoFrame(const EncodedPacket& pkt, uint32_t seq,
                                                  size_t fragmentOffset, size_t fragmentLen,
                                                  bool lastFragment, bool dropable) {
    const uint16_t rectCount = static_cast<uint16_t>(std::min<size_t>(pkt.dirty.rects.size(), kMaxDirtyRects));

    VideoExtHeader ext{};
    ext.ptsNs          = pkt.ptsNs;
    ext.width          = static_cast<uint16_t>(pkt.width);
    ext.height         = static_cast<uint16_t>(pkt.height);
    ext.frameRateX100  = static_cast<uint16_t>(pkt.frameRateX100);
    ext.dirtyRectCount = rectCount;
    ext.codecId        = static_cast<uint16_t>(pkt.codec);
    ext.reserved       = 0;

    std::vector<uint8_t> rects(static_cast<size_t>(rectCount) * sizeof(ApxDirtyRect), 0);
    for (uint16_t i = 0; i < rectCount; ++i) {
        ApxDirtyRect r{};
        r.x = pkt.dirty.rects[i].x;
        r.y = pkt.dirty.rects[i].y;
        r.w = pkt.dirty.rects[i].w;
        r.h = pkt.dirty.rects[i].h;
        std::memcpy(rects.data() + static_cast<size_t>(i) * sizeof(ApxDirtyRect), &r, sizeof(r));
    }

    apx::ApxFrameHeader hdr{};
    std::memcpy(hdr.magic, "APX1", 4);
    hdr.streamId       = apx::kStreamVideo;
    static bool diagPrinted = false;
    if (!diagPrinted) {
        diagPrinted = true;
        APX_LOG_D("诊断: ext w=%u h=%u fps=%u codec=%u rects=%u fragLen=%zu pktKey=%d",
                  static_cast<unsigned>(ext.width), static_cast<unsigned>(ext.height),
                  static_cast<unsigned>(ext.frameRateX100), static_cast<unsigned>(ext.codecId),
                  static_cast<unsigned>(rectCount), fragmentLen, pkt.keyFrame ? 1 : 0);
    }
    hdr.flags          = 0;
    if (pkt.keyFrame)                         hdr.flags |= apx::kFlagKeyFrame;
    if (lastFragment)                         hdr.flags |= apx::kFlagLastFragment;
    if (dropable)                             hdr.flags |= apx::kFlagDropable;
    hdr.headerExtWords = kVideoExtWords;
    hdr.payloadLen     = static_cast<uint32_t>(sizeof(VideoExtHeader) + rects.size() +
                                               fragmentLen + kCrcLen);
    hdr.seq            = seq;

    std::vector<uint8_t> frame;
    frame.reserve(sizeof(hdr) + hdr.payloadLen);
    const uint8_t* hb = reinterpret_cast<const uint8_t*>(&hdr);
    frame.insert(frame.end(), hb, hb + sizeof(hdr));
    const uint8_t* eb = reinterpret_cast<const uint8_t*>(&ext);
    frame.insert(frame.end(), eb, eb + sizeof(ext));
    frame.insert(frame.end(), rects.begin(), rects.end());
    frame.insert(frame.end(), pkt.bytes.begin() + fragmentOffset,
                 pkt.bytes.begin() + fragmentOffset + fragmentLen);

    // v1.10 修复：CRC 覆盖范围统一为「帧头之后（扩展头+数据）」——此前从帧头
    // 开始算，手机端 FrameReader 按「16 字节帧头之后」校验，两端不一致导致
    // CRC 全挂、视频帧被静默丢弃（真机 2026-09-21 WiFi 直连实测）。
    const uint32_t crc = crc32Of(frame.data() + sizeof(hdr), frame.size() - sizeof(hdr));
    uint8_t crcBytes[4];
    putU32(crcBytes, crc);
    frame.insert(frame.end(), crcBytes, crcBytes + 4);
    return frame;
}

std::vector<uint8_t> FrameWriter::buildControlFrame(const uint8_t* payload, size_t len, uint32_t seq) {
    apx::ApxFrameHeader hdr{};
    std::memcpy(hdr.magic, "APX1", 4);
    hdr.streamId       = apx::kStreamControl;
    hdr.flags          = 0;                  // 控制面不可丢
    hdr.headerExtWords = 0;
    hdr.payloadLen     = static_cast<uint32_t>(len + kCrcLen);
    hdr.seq            = seq;

    std::vector<uint8_t> frame;
    frame.reserve(sizeof(hdr) + hdr.payloadLen);
    const uint8_t* hb = reinterpret_cast<const uint8_t*>(&hdr);
    frame.insert(frame.end(), hb, hb + sizeof(hdr));
    if (payload && len) frame.insert(frame.end(), payload, payload + len);

    // v1.10：与 buildVideoFrame 同修——CRC 覆盖「帧头之后」
    const uint32_t crc = crc32Of(frame.data() + sizeof(hdr), frame.size() - sizeof(hdr));
    uint8_t crcBytes[4];
    putU32(crcBytes, crc);
    frame.insert(frame.end(), crcBytes, crcBytes + 4);
    return frame;
}

size_t FrameWriter::writeVideo(const EncodedPacket& pkt, uint32_t timeoutMs) {
    if (!channel_ || pkt.bytes.empty()) return 0;

    const size_t total = pkt.bytes.size();
    const size_t frag  = maxFragment_ ? maxFragment_ : total;
    size_t written = 0;

    for (size_t off = 0; off < total; off += frag) {
        const size_t len = std::min(frag, total - off);
        const bool last = (off + len >= total);
        auto frame = buildVideoFrame(pkt, seq_++, off, len, last, true /*dropable*/);
        if (channel_->write(frame.data(), frame.size(), timeoutMs) != frame.size()) {
            // v1.10：连续失败几乎只可能是「手机端副屏没在监听」（adb forward 是
            // 延迟连接——PC 端 connect 必然成功，首帧写入才暴露对端缺失）。
            // 逐帧刷同一句警告会刷爆窗口，这里只提示一次并给出可操作指引。
            static std::atomic<int> s_failHint{0};
            if (s_failHint.fetch_add(1) == 0) {
                APX_LOG_W("推流写入失败——手机端副屏未就绪？请在手机上打开「副屏」页面，"
                          "本端会自动重连（后续失败不再逐帧提示）");
            }
            return written;
        }
        ++written;
    }
    return written;
}

bool FrameWriter::writeControl(const uint8_t* payload, size_t len, uint32_t timeoutMs) {
    if (!channel_) return false;
    auto frame = buildControlFrame(payload, len, seq_++);
    return channel_->write(frame.data(), frame.size(), timeoutMs) == frame.size();
}

bool FrameWriter::writeRawFrame(const uint8_t* frame, size_t len, uint32_t timeoutMs) {
    if (!channel_) return false;
    return channel_->write(frame, len, timeoutMs) == len;
}

// ------------------------------------------------------------------ 解析 ----
bool parseFrame(const uint8_t* data, size_t len, ParsedFrame& out) {
    out = ParsedFrame{};
    if (!validateFrame(data, len)) return false;

    apx::ApxFrameHeader h{};
    std::memcpy(&h, data, sizeof(h));
    out.header = h;
    out.crc    = getU32(data + len - kCrcLen);

    const size_t extBytes = static_cast<size_t>(h.headerExtWords) * 4;
    const uint8_t* payload = data + sizeof(h);
    const size_t crcIncludedPayload = h.payloadLen - kCrcLen;

    if (h.streamId == apx::kStreamVideo && extBytes >= sizeof(VideoExtHeader)) {
        std::memcpy(&out.video, payload, sizeof(VideoExtHeader));
        const uint16_t n = std::min<uint16_t>(out.video.dirtyRectCount, kMaxDirtyRects);
        const size_t rectBytes = static_cast<size_t>(n) * sizeof(ApxDirtyRect);
        if (sizeof(VideoExtHeader) + rectBytes > crcIncludedPayload) return false;
        out.rects.resize(n);
        for (uint16_t i = 0; i < n; ++i) {
            std::memcpy(&out.rects[i], payload + sizeof(VideoExtHeader) + static_cast<size_t>(i) * sizeof(ApxDirtyRect),
                        sizeof(ApxDirtyRect));
        }
        const size_t offset = extBytes + static_cast<size_t>(out.video.dirtyRectCount) * sizeof(ApxDirtyRect);
        if (offset > crcIncludedPayload) return false;
        out.payload    = payload + offset;
        out.payloadLen = crcIncludedPayload - offset;
    } else {
        out.payload    = payload;
        out.payloadLen = crcIncludedPayload;
    }
    out.valid = true;
    return true;
}

void FrameSplitter::feed(const uint8_t* data, size_t len) {
    // 丢弃已消费部分，避免缓冲无限增长
    if (consumed_ > 0 && consumed_ <= buf_.size()) {
        buf_.erase(buf_.begin(), buf_.begin() + static_cast<long>(consumed_));
        consumed_ = 0;
    }
    if (data && len) buf_.insert(buf_.end(), data, data + len);
}

bool FrameSplitter::next(std::vector<uint8_t>& frameOut) {
    // 防 OOM：累积缓冲超过「单帧上限 + 余量」即视为在等待一个不可能合法的超大帧，
    // 直接清空，绝不继续缓冲（A）。
    if (buf_.size() - consumed_ > apx::kMaxFramePayload + apx::kFrameHeaderSize + (1u << 16)) {
        buf_.clear();
        consumed_ = 0;
        return false;
    }
    while (buf_.size() - consumed_ >= sizeof(apx::ApxFrameHeader)) {
        const uint8_t* p = buf_.data() + consumed_;
        if (std::memcmp(p, "APX1", 4) != 0) {
            ++consumed_;  // 跳过一个字节重新同步
            continue;
        }
        apx::ApxFrameHeader h{};
        std::memcpy(&h, p, sizeof(h));
        // A：取帧处立即校验载荷上下界，坏帧（过大/过小）直接丢弃，绝不先缓冲
        if (!apx::isValidPayloadLen(h.payloadLen)) {
            buf_.clear();
            consumed_ = 0;
            return false;
        }
        const size_t total = sizeof(h) + static_cast<size_t>(h.headerExtWords) * 4 + h.payloadLen;
        if (total > buf_.size() - consumed_) return false;  // 数据未收全
        frameOut.assign(p, p + total);
        consumed_ += total;
        return true;
    }
    return false;
}

}  // namespace apxdisp
