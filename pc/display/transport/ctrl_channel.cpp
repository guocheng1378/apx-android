#include "transport/ctrl_channel.hpp"

#include <cstring>
#include <vector>

#include "common/log.hpp"
#include "protocol/frame_format.hpp"
#include "transport/frame_writer.hpp"

namespace apxdisp {

std::vector<uint8_t> encodeTlv(uint16_t type, const void* payload, size_t len) {
    std::vector<uint8_t> out(4 + len, 0);
    putU16(out.data(), type);
    putU16(out.data() + 2, static_cast<uint16_t>(len));
    if (payload && len) std::memcpy(out.data() + 4, payload, len);
    return out;
}

bool decodeTlv(const uint8_t* data, size_t len, uint16_t& type, const uint8_t*& value,
               size_t& valueLen) {
    if (!data || len < 4) return false;
    type = getU16(data);
    const uint16_t l = getU16(data + 2);
    if (4u + l > len) return false;
    value = data + 4;
    valueLen = l;
    return true;
}

bool CtrlSession::send(CtrlMsg type, const void* payload, size_t len) {
    if (!channel_) { lastError_ = "控制通道为空"; return false; }
    auto tlv = encodeTlv(static_cast<uint16_t>(type), payload, len);
    // streamId=3 控制帧：不可丢包
    apx::ApxFrameHeader hdr{};
    std::memcpy(hdr.magic, "APX1", 4);
    hdr.streamId       = apx::kStreamControl;
    hdr.flags          = 0;
    hdr.headerExtWords = 0;
    hdr.payloadLen     = static_cast<uint32_t>(tlv.size() + kCrcLen);
    hdr.seq            = 0;

    std::vector<uint8_t> frame;
    const uint8_t* hb = reinterpret_cast<const uint8_t*>(&hdr);
    frame.insert(frame.end(), hb, hb + sizeof(hdr));
    frame.insert(frame.end(), tlv.begin(), tlv.end());
    // v1.11 修复：与 FrameWriter 统一为「帧头之后」。原先按整帧（含帧头）算，
    // 对端按「帧头之后」校验时这些控制帧一律被拒。
    const uint32_t crc = crc32Of(frame.data() + sizeof(hdr), frame.size() - sizeof(hdr));
    uint8_t cb[4];
    putU32(cb, crc);
    frame.insert(frame.end(), cb, cb + 4);

    const size_t n = channel_->write(frame.data(), frame.size(), 1000);
    if (n != frame.size()) {
        lastError_ = "控制帧写入不完整（" + std::to_string(n) + "/" + std::to_string(frame.size()) + "）";
        return false;
    }
    return true;
}

bool CtrlSession::sendHello(uint64_t pcClockNs, uint64_t capabilities) {
    HelloPayload p{};
    p.protocolVersion = 0x0100;
    p.pcClockNs = pcClockNs;
    p.capabilities = capabilities;
    APX_LOG_I("控制面 -> HELLO ver=1.0 caps=0x%llX",
              static_cast<unsigned long long>(capabilities));
    return send(CtrlMsg::Hello, &p, sizeof(p));
}

bool CtrlSession::waitHelloAck(HelloAckPayload& ack, uint32_t timeoutMs) {
    uint16_t type = 0;
    std::vector<uint8_t> value;
    for (uint32_t waited = 0; waited < timeoutMs; waited += 200) {
        if (recv(type, value, 200) && type == static_cast<uint16_t>(CtrlMsg::HelloAck)) {
            if (value.size() < sizeof(HelloAckPayload)) { lastError_ = "HELLO_ACK 长度不足"; return false; }
            std::memcpy(&ack, value.data(), sizeof(HelloAckPayload));
            APX_LOG_I("控制面 <- HELLO_ACK speed=%u modules=0x%02X sensors=%u",
                      ack.udcSpeed, ack.enabledModules, ack.sensorCount);
            return true;
        }
    }
    lastError_ = "等待 HELLO_ACK 超时";
    return false;
}

bool CtrlSession::sendConfig(const ConfigPayload& cfg) {
    APX_LOG_I("控制面 -> CONFIG %ux%u@%u.%02u codec=%u bitrate=%ukbps",
              cfg.displayWidth, cfg.displayHeight, cfg.refreshRateX100 / 100,
              cfg.refreshRateX100 % 100, cfg.codecId, cfg.bitrateKbps);
    return send(CtrlMsg::Config, &cfg, sizeof(cfg));
}

bool CtrlSession::waitConfigAck(uint32_t timeoutMs) {
    uint16_t type = 0;
    std::vector<uint8_t> value;
    for (uint32_t waited = 0; waited < timeoutMs; waited += 200) {
        if (recv(type, value, 200) && type == static_cast<uint16_t>(CtrlMsg::ConfigAck)) return true;
    }
    lastError_ = "等待 CONFIG_ACK 超时";
    return false;
}

bool CtrlSession::sendBye() { return send(CtrlMsg::Bye, nullptr, 0); }
bool CtrlSession::requestRemount() { return send(CtrlMsg::Remount, nullptr, 0); }

bool CtrlSession::sendPing(uint64_t t1Ns) {
    PingPayload p{};
    p.t1Ns = t1Ns;
    return send(CtrlMsg::Ping, &p, sizeof(p));
}

bool CtrlSession::recv(uint16_t& type, std::vector<uint8_t>& value, uint32_t timeoutMs) {
    if (!channel_) return false;
    std::vector<uint8_t> buf(4096);
    const size_t n = channel_->read(buf.data(), buf.size(), timeoutMs);
    if (n < sizeof(apx::ApxFrameHeader)) return false;

    ParsedFrame parsed{};
    if (!parseFrame(buf.data(), n, parsed)) {
        lastError_ = "控制帧校验失败（magic/CRC）";
        return false;
    }
    if (parsed.header.streamId != apx::kStreamControl) return false;

    const uint8_t* v = nullptr;
    size_t vlen = 0;
    if (!decodeTlv(parsed.payload, parsed.payloadLen, type, v, vlen)) return false;
    value.assign(v, v + vlen);
    return true;
}

}  // namespace apxdisp
