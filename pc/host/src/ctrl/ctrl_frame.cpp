#include "apxpc/ctrl/ctrl_frame.hpp"

#include <cstring>

#include "apxpc/util/crc32.hpp"
#include "apxpc/version.hpp"
#include "apx/hid_layout.h"  // apx::kReportVendor（Report 5 控制与状态）

namespace apxpc::ctrl {

// ------------------------------------------------------------ 小端写入
void putU8(std::vector<uint8_t>& b, uint8_t v) { b.push_back(v); }

void putU16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xFF));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}

void putU32(std::vector<uint8_t>& b, uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
}

void putU64(std::vector<uint8_t>& b, uint64_t v) {
    for (int i = 0; i < 8; ++i) b.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
}

static uint16_t rdU16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (uint16_t(p[1]) << 8));
}
static uint32_t rdU32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
static uint64_t rdU64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v |= (uint64_t(p[i]) << (8 * i));
    return v;
}

// ------------------------------------------------------------ TLV 查询
const Tlv* findTlv(const CtrlMessage& m, CtrlTag tag) {
    for (const auto& t : m.tlvs)
        if (t.tag == tag) return &t;
    return nullptr;
}

StatusEx tlvU8(const CtrlMessage& m, CtrlTag tag, uint8_t& out) {
    const Tlv* t = findTlv(m, tag);
    if (!t || t->value.size() < 1) return err(Status::Protocol, "missing tlv u8");
    out = t->value[0];
    return ok();
}

StatusEx tlvU16(const CtrlMessage& m, CtrlTag tag, uint16_t& out) {
    const Tlv* t = findTlv(m, tag);
    if (!t || t->value.size() < 2) return err(Status::Protocol, "missing tlv u16");
    out = rdU16(t->value.data());
    return ok();
}

StatusEx tlvU32(const CtrlMessage& m, CtrlTag tag, uint32_t& out) {
    const Tlv* t = findTlv(m, tag);
    if (!t || t->value.size() < 4) return err(Status::Protocol, "missing tlv u32");
    out = rdU32(t->value.data());
    return ok();
}

StatusEx tlvU64(const CtrlMessage& m, CtrlTag tag, uint64_t& out) {
    const Tlv* t = findTlv(m, tag);
    if (!t || t->value.size() < 8) return err(Status::Protocol, "missing tlv u64");
    out = rdU64(t->value.data());
    return ok();
}

StatusEx tlvString(const CtrlMessage& m, CtrlTag tag, std::string& out) {
    const Tlv* t = findTlv(m, tag);
    if (!t) return err(Status::Protocol, "missing tlv string");
    out.assign(t->value.begin(), t->value.end());
    return ok();
}

// ------------------------------------------------------------ 帧编解码
std::vector<uint8_t> encodeFrame(MsgType type, uint32_t seq, const std::vector<Tlv>& tlvs) {
    std::vector<uint8_t> payload;
    payload.reserve(64);
    payload.push_back(static_cast<uint8_t>(type));
    putU32(payload, seq);
    for (const auto& t : tlvs) {
        putU16(payload, static_cast<uint16_t>(t.tag));
        putU16(payload, static_cast<uint16_t>(t.value.size()));
        payload.insert(payload.end(), t.value.begin(), t.value.end());
    }
    putU32(payload, crc32(payload.data(), payload.size()));  // §3：crc 计入 payloadLen

    std::vector<uint8_t> frame(sizeof(apx::ApxFrameHeader) + payload.size());
    auto* h = reinterpret_cast<apx::ApxFrameHeader*>(frame.data());
    h->magic[0] = 'A';
    h->magic[1] = 'P';
    h->magic[2] = 'X';
    h->magic[3] = '1';
    h->streamId       = apx::kStreamControl;
    h->flags          = 0;
    h->headerExtWords = 0;
    h->payloadLen     = static_cast<uint32_t>(payload.size());
    h->seq            = seq;
    std::memcpy(frame.data() + sizeof(apx::ApxFrameHeader), payload.data(), payload.size());
    return frame;
}

StatusEx decodeFrame(const uint8_t* data, size_t len, CtrlMessage& out) {
    if (!data || len < sizeof(apx::ApxFrameHeader) + 4 + 5)
        return err(Status::Protocol, "frame too short");

    apx::ApxFrameHeader h{};
    std::memcpy(&h, data, sizeof(h));
    if (h.magic[0] != 'A' || h.magic[1] != 'P' || h.magic[2] != 'X' || h.magic[3] != '1')
        return err(Status::Protocol, "bad magic");
    if (h.streamId != apx::kStreamControl)
        return err(Status::Protocol, "not a control frame");
    if (h.payloadLen != len - sizeof(apx::ApxFrameHeader))
        return err(Status::Protocol, "payloadLen mismatch");

    const uint8_t* payload = data + sizeof(apx::ApxFrameHeader);
    const size_t   payLen  = h.payloadLen;
    const uint32_t want = rdU32(payload + payLen - 4);
    const uint32_t got  = crc32(payload, payLen - 4);
    if (want != got) return err(Status::Protocol, "crc32 mismatch");

    // 扩展头按 headerExtWords 跳过（§5 前向兼容）
    size_t off = static_cast<size_t>(h.headerExtWords) * 4;
    const size_t bodyEnd = payLen - 4;
    if (off + 5 > bodyEnd) return err(Status::Protocol, "truncated body");

    out.tlvs.clear();
    out.type = static_cast<MsgType>(payload[off]);
    off += 1;
    out.seq = rdU32(payload + off);
    off += 4;

    while (off + 4 <= bodyEnd) {
        CtrlTag tag = static_cast<CtrlTag>(rdU16(payload + off));
        uint16_t vlen = rdU16(payload + off + 2);
        off += 4;
        if (off + vlen > bodyEnd) return err(Status::Protocol, "tlv overflow");
        Tlv t;
        t.tag = tag;
        t.value.assign(payload + off, payload + off + vlen);
        off += vlen;
        out.tlvs.push_back(std::move(t));
    }
    return ok();
}

// ------------------------------------------------------------ 语义构造
std::vector<uint8_t> buildHello(uint32_t seq, uint16_t protocolVersion, uint64_t pcClockNs,
                                uint64_t capabilities) {
    std::vector<Tlv> tlvs;
    { Tlv t; t.tag = CtrlTag::ProtocolVersion; putU16(t.value, protocolVersion); tlvs.push_back(t); }
    { Tlv t; t.tag = CtrlTag::PcClockNs;       putU64(t.value, pcClockNs);       tlvs.push_back(t); }
    { Tlv t; t.tag = CtrlTag::Capabilities;    putU64(t.value, capabilities);    tlvs.push_back(t); }
    return encodeFrame(MsgType::Hello, seq, tlvs);
}

std::vector<uint8_t> buildConfig(uint32_t seq, uint64_t sensorMask,
                                 const std::vector<SampleRate>& rates,
                                 uint8_t displayMode, const VideoParams& video) {
    std::vector<Tlv> tlvs;
    { Tlv t; t.tag = CtrlTag::SensorMask;  putU64(t.value, sensorMask); tlvs.push_back(t); }
    if (!rates.empty()) {
        Tlv t;
        t.tag = CtrlTag::SampleRate;
        for (const auto& r : rates) { putU8(t.value, r.sensorId); putU32(t.value, r.rateMilliHz); }
        tlvs.push_back(std::move(t));
    }
    { Tlv t; t.tag = CtrlTag::DisplayMode; putU8(t.value, displayMode); tlvs.push_back(std::move(t)); }
    {
        Tlv t;
        t.tag = CtrlTag::VideoParams;
        putU16(t.value, video.width);
        putU16(t.value, video.height);
        putU16(t.value, video.frameRateX100);
        putU16(t.value, video.codecId);
        putU32(t.value, video.bitrateKbps);
        tlvs.push_back(std::move(t));
    }
    return encodeFrame(MsgType::Config, seq, tlvs);
}

std::vector<uint8_t> buildBye(uint32_t seq, const std::string& reason) {
    std::vector<Tlv> tlvs;
    Tlv t;
    t.tag = CtrlTag::Reason;
    t.value.assign(reason.begin(), reason.end());
    tlvs.push_back(std::move(t));
    return encodeFrame(MsgType::Bye, seq, tlvs);
}

std::vector<uint8_t> buildPing(uint32_t seq, uint64_t pcClockNs) {
    std::vector<Tlv> tlvs;
    { Tlv t; t.tag = CtrlTag::HeartbeatSeq;  putU32(t.value, seq);       tlvs.push_back(t); }
    { Tlv t; t.tag = CtrlTag::PcClockNs;     putU64(t.value, pcClockNs); tlvs.push_back(t); }
    return encodeFrame(MsgType::Ping, seq, tlvs);
}

StatusEx parseHelloAck(const CtrlMessage& m, HelloAckInfo& out) {
    if (m.type != MsgType::HelloAck) return err(Status::Protocol, "not HELLO_ACK");
    HelloAckInfo r;
    uint16_t ver = 0;
    if (!tlvU16(m, CtrlTag::ProtocolVersion, ver).ok()) return err(Status::Protocol, "no version");
    r.protocolVersion = ver;
    (void)tlvU64(m, CtrlTag::PhoneClockNs, r.phoneClockNs);
    (void)tlvU8(m, CtrlTag::UdcSpeed, r.udcSpeed);
    (void)tlvU64(m, CtrlTag::Capabilities, r.capabilities);
    (void)tlvU8(m, CtrlTag::EnabledModules, r.enabledModules);

    if (const Tlv* t = findTlv(m, CtrlTag::SensorList)) {
        const size_t stride = 4;  // u8 id + u16 maxRateHz + u8 state
        for (size_t i = 0; i + stride <= t->value.size(); i += stride) {
            SensorCap c;
            c.id        = t->value[i];
            c.maxRateHz = rdU16(t->value.data() + i + 1);
            c.state     = t->value[i + 3];
            r.sensorList.push_back(c);
        }
    }
    r.valid = true;
    out = std::move(r);
    return ok();
}

StatusEx parseConfigAck(const CtrlMessage& m, ConfigAckInfo& out) {
    if (m.type != MsgType::ConfigAck) return err(Status::Protocol, "not CONFIG_ACK");
    ConfigAckInfo r;
    (void)tlvU8(m, CtrlTag::RunStatus, r.runStatus);
    (void)tlvU64(m, CtrlTag::SensorMask, r.appliedSensorMask);
    (void)tlvU32(m, CtrlTag::ErrorCode, r.errorCode);
    r.valid = true;
    out = std::move(r);
    return ok();
}

StatusEx parsePong(const CtrlMessage& m, uint32_t& seq, uint64_t& phoneTsNs, uint64_t& uptimeMs) {
    if (m.type != MsgType::Pong) return err(Status::Protocol, "not PONG");
    seq = m.seq;
    if (!tlvU64(m, CtrlTag::HeartbeatTsNs, phoneTsNs).ok())
        return err(Status::Protocol, "no heartbeat ts");
    (void)tlvU64(m, CtrlTag::UptimeMs, uptimeMs);
    return ok();
}

// ------------------------------------------------------------ Vendor Report 5
std::vector<uint8_t> buildVendorCommand(uint8_t cmd, uint8_t seq,
                                        const std::vector<uint8_t>& payload) {
    std::vector<uint8_t> out;
    out.reserve(8 + payload.size());
    out.push_back(apx::kReportVendor);
    out.push_back(cmd);
    out.push_back(seq);
    out.push_back(0);  // reserved
    putU32(out, static_cast<uint32_t>(payload.size()));
    out.insert(out.end(), payload.begin(), payload.end());
    return out;
}

StatusEx parseVendorStatus(const uint8_t* data, size_t len, VendorStatusInfo& out) {
    if (!data || len < 16) return err(Status::Protocol, "vendor status too short");
    if (data[0] != apx::kReportVendor) return err(Status::Protocol, "not report 5");
    VendorStatusInfo r;
    r.status     = data[1];
    r.linkSpeed  = data[2];
    r.moduleMask = data[3];
    r.errorCode  = rdU32(data + 4);
    r.uptimeMs   = rdU64(data + 8);
    out = r;
    return ok();
}

}  // namespace apxpc::ctrl
