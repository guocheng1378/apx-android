#pragma once
// 控制面帧编解码：PROTOCOL §3.3（streamId=3 Control）+ §4（握手状态机）。
//
// 载荷格式：TLV 序列 + 尾部 u32 crc32，crc 计入 payloadLen（PROTOCOL §3 明文要求）。
// 采用 TLV 而非 JSON：零第三方依赖、可前向兼容（未知 tag 直接跳过），
// 与 §5“未知扩展头按 headerExtWords 跳过”的兼容精神一致。
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include <apx/frame.h>

#include "apxpc/status.hpp"

namespace apxpc::ctrl {

// ---------------------------------------------------------------- 消息类型
enum class MsgType : uint8_t {
    Unknown   = 0,
    Hello     = 1,   // PC -> 手机
    HelloAck  = 2,   // 手机 -> PC
    Config    = 3,   // PC -> 手机
    ConfigAck = 4,   // 手机 -> PC
    Bye       = 5,   // 任意方向
    Ping      = 6,   // 心跳（§4 间隔 1s）
    Pong      = 7,   // 心跳回应
    Status    = 8,   // 手机主动状态上报
};

inline const char* msgTypeName(MsgType t) noexcept {
    switch (t) {
        case MsgType::Hello:     return "HELLO";
        case MsgType::HelloAck:  return "HELLO_ACK";
        case MsgType::Config:    return "CONFIG";
        case MsgType::ConfigAck: return "CONFIG_ACK";
        case MsgType::Bye:       return "BYE";
        case MsgType::Ping:      return "PING";
        case MsgType::Pong:      return "PONG";
        case MsgType::Status:    return "STATUS";
        default:                 return "UNKNOWN";
    }
}

// ---------------------------------------------------------------- TLV 标签
enum class CtrlTag : uint16_t {
    ProtocolVersion = 0x0001,  // u16   major<<8|minor
    PcClockNs       = 0x0002,  // u64   PC 单调时基（§1）
    PhoneClockNs    = 0x0003,  // u64   手机 elapsedRealtimeNanos
    Capabilities    = 0x0004,  // u64   能力位图
    UdcSpeed        = 0x0005,  // u8    §2.7 linkSpeed 取值
    SensorList      = 0x0006,  // 变长  N * {u8 id, u16 maxRateHz, u8 state}
    EnabledModules  = 0x0007,  // u8    §2.7 moduleMask
    SensorMask      = 0x0008,  // u64   全局传感器编号位图（sensors_id.h）
    SampleRate      = 0x0009,  // 变长  N * {u8 id, u32 rateMilliHz}
    DisplayMode     = 0x000A,  // u8
    VideoParams     = 0x000B,  // 变长  {u16 w, u16 h, u16 fpsX100, u16 codec, u32 bitrateKbps}
    HeartbeatSeq    = 0x000C,  // u32
    HeartbeatTsNs   = 0x000D,  // u64   手机收到 ping 时的手机时基
    Reason          = 0x000E,  // 字符串（BYE 原因等）
    ErrorCode       = 0x000F,  // u32
    RunStatus       = 0x0010,  // u8    §2.7 status
    UptimeMs        = 0x0011,  // u64
};

// ---------------------------------------------------------------- 能力位
enum CapabilityBits : uint64_t {
    kCapSensor  = 1ull << 0,
    kCapTouch   = 1ull << 1,
    kCapKey     = 1ull << 2,
    kCapBattery = 1ull << 3,
    kCapGps     = 1ull << 4,
    kCapDisplay = 1ull << 5,

    kCapAudio   = 1ull << 7,
    kCapBulk    = 1ull << 8,   // 支持 bulk 控制面（否则只能走 HID Feature）
};

// ---------------------------------------------------------------- 数据结构
struct Tlv {
    CtrlTag              tag{CtrlTag::ProtocolVersion};
    std::vector<uint8_t> value;
};

struct CtrlMessage {
    MsgType       type{MsgType::Unknown};
    uint32_t      seq{0};
    std::vector<Tlv> tlvs;
};

struct SensorCap {
    uint8_t  id{0};         // 全局传感器编号（apx::SensorId）
    uint16_t maxRateHz{0};  // 手机端承诺的最大采样率
    uint8_t  state{0};      // §2.4 state 语义：0 未知 1 就绪 2 不可用 3 错误
};

struct SampleRate {
    uint8_t  sensorId{0};
    uint32_t rateMilliHz{0};  // 毫赫兹，避免浮点；200Hz -> 200000
};

struct VideoParams {
    uint16_t width{0};
    uint16_t height{0};
    uint16_t frameRateX100{0};
    uint16_t codecId{0};      // §3.1：0=H264 1=HEVC 2=AV1 3=MJPEG 4=RAW_LZ4
    uint32_t bitrateKbps{0};
};

struct HelloAckInfo {
    uint16_t            protocolVersion{0};
    uint64_t            phoneClockNs{0};
    uint8_t             udcSpeed{0};
    uint64_t            capabilities{0};
    std::vector<SensorCap> sensorList;
    uint8_t             enabledModules{0};
    bool                valid{false};
};

struct ConfigAckInfo {
    uint8_t  runStatus{0};
    uint64_t appliedSensorMask{0};
    uint32_t errorCode{0};
    bool     valid{false};
};

// ---------------------------------------------------------------- TLV 读写
void putU8(std::vector<uint8_t>& b, uint8_t v);
void putU16(std::vector<uint8_t>& b, uint16_t v);   // 小端
void putU32(std::vector<uint8_t>& b, uint32_t v);
void putU64(std::vector<uint8_t>& b, uint64_t v);

const Tlv*      findTlv(const CtrlMessage& m, CtrlTag tag);
StatusEx        tlvU8(const CtrlMessage& m, CtrlTag tag, uint8_t& out);
StatusEx        tlvU16(const CtrlMessage& m, CtrlTag tag, uint16_t& out);
StatusEx        tlvU32(const CtrlMessage& m, CtrlTag tag, uint32_t& out);
StatusEx        tlvU64(const CtrlMessage& m, CtrlTag tag, uint64_t& out);
StatusEx        tlvString(const CtrlMessage& m, CtrlTag tag, std::string& out);

// ---------------------------------------------------------------- 帧编解码
// encodeFrame：Magic=APX1 / streamId=3 / payload = {msgType, seq, TLVs} + crc32
std::vector<uint8_t> encodeFrame(MsgType type, uint32_t seq, const std::vector<Tlv>& tlvs);
// decodeFrame：校验 magic / payloadLen / crc32，失败返回 Protocol 错误
StatusEx decodeFrame(const uint8_t* data, size_t len, CtrlMessage& out);

// ---------------------------------------------------------------- 语义构造
std::vector<uint8_t> buildHello(uint32_t seq, uint16_t protocolVersion, uint64_t pcClockNs,
                                uint64_t capabilities);
std::vector<uint8_t> buildConfig(uint32_t seq, uint64_t sensorMask,
                                 const std::vector<SampleRate>& rates,
                                 uint8_t displayMode, const VideoParams& video);
std::vector<uint8_t> buildBye(uint32_t seq, const std::string& reason);
std::vector<uint8_t> buildPing(uint32_t seq, uint64_t pcClockNs);

StatusEx parseHelloAck(const CtrlMessage& m, HelloAckInfo& out);
StatusEx parseConfigAck(const CtrlMessage& m, ConfigAckInfo& out);
StatusEx parsePong(const CtrlMessage& m, uint32_t& seq, uint64_t& phoneTsNs, uint64_t& uptimeMs);

// ---------------------------------------------------------------- Vendor Report 5（§2.7）
// OUT（PC -> 手机）：{reportId=5, cmd, seq, reserved, u32 payloadLen, payload[]}
std::vector<uint8_t> buildVendorCommand(uint8_t cmd, uint8_t seq,
                                        const std::vector<uint8_t>& payload);

struct VendorStatusInfo {
    uint8_t  status{0};
    uint8_t  linkSpeed{0};
    uint8_t  moduleMask{0};
    uint32_t errorCode{0};
    uint64_t uptimeMs{0};
};
// FEATURE（手机 -> PC）
StatusEx parseVendorStatus(const uint8_t* data, size_t len, VendorStatusInfo& out);

}  // namespace apxpc::ctrl
