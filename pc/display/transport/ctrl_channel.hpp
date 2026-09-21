// 控制面（PROTOCOL §3.3 / §4）
//
// streamId=3 的载荷用 **TLV**（不是 JSON，避免引入依赖）：
//   u16 type | u16 len | bytes[len]
// 类型见 CtrlMsg。控制面永远走可靠顺序通道，绝不丢包。
#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "transport/i_transport.hpp"

namespace apxdisp {

enum class CtrlMsg : uint16_t {
    Hello     = 1,
    HelloAck  = 2,
    Config    = 3,
    ConfigAck = 4,
    Bye       = 5,
    Ping      = 6,
    Pong      = 7,
    Status    = 8,
    Error     = 9,
    Remount   = 10,  // 请求手机端重新挂载 Gadget（自愈）
};

// 能力位（PC → 手机 HELLO）
enum : uint64_t {
    kCapH264   = 1ull << 0,
    kCapHevc   = 1ull << 1,
    kCapAv1    = 1ull << 2,
    kCapRawLz4 = 1ull << 3,
    kCapTouchBulk = 1ull << 4,
    kCapTouchHid  = 1ull << 5,
    kCapPen        = 1ull << 6,
    kCapAudio      = 1ull << 7,
};

// linkSpeed（PROTOCOL §2.7）：0=unknown 1=full 2=high 3=super 4=super_plus
enum : uint8_t {
    kSpeedUnknown = 0,
    kSpeedFull    = 1,
    kSpeedHigh    = 2,
    kSpeedSuper   = 3,
    kSpeedSuperPlus = 4,
};

#pragma pack(push, 1)

struct HelloPayload {
    uint16_t protocolVersion = 0x0100;  // 1.0
    uint64_t pcClockNs = 0;
    uint64_t capabilities = 0;
};

struct HelloAckPayload {
    uint16_t protocolVersion = 0x0100;
    uint64_t phoneClockNs = 0;
    uint8_t  udcSpeed = kSpeedUnknown;
    uint8_t  enabledModules = 0;
    uint8_t  reserved[2] = {0, 0};
    uint32_t sensorCount = 0;   // 后跟 sensorCount 个 u32 sensorId
};

// ConfigPayload 里不用 CodecId（enum class 底层为 int），用独立常量避免宽度歧义
enum : uint16_t { CodecIdUnused = 0xFFFF };

struct ConfigPayload {
    uint64_t sensorMask = 0;
    uint32_t displayWidth = 1080;
    uint32_t displayHeight = 2400;
    uint32_t refreshRateX100 = 6000;
    uint16_t codecId = static_cast<uint16_t>(CodecIdUnused);
    uint32_t bitrateKbps = 12000;
    uint32_t frameRateX100 = 6000;
    uint8_t  displayMode = 0;   // 0=副屏 1=扩展 2=镜像
    uint8_t  reserved[3] = {0, 0, 0};
};

struct PingPayload { uint64_t t1Ns = 0; };                   // 本端发送时刻
struct PongPayload { uint64_t t1Ns = 0, t2Ns = 0, t3Ns = 0; }; // 回显 t1 + 对端收/发时刻

struct StatusPayload {
    uint8_t  status = 0;        // 0=idle 1=running 2=error 3=降级
    uint8_t  linkSpeed = 0;
    uint8_t  moduleMask = 0;
    uint8_t  reserved = 0;
    uint32_t errorCode = 0;
    uint64_t uptimeMs = 0;
};

#pragma pack(pop)

// TLV 编解码
std::vector<uint8_t> encodeTlv(uint16_t type, const void* payload, size_t len);
bool decodeTlv(const uint8_t* data, size_t len, uint16_t& type, const uint8_t*& value,
               size_t& valueLen);

// 控制面会话：负责握手、配置下发、心跳发收、断线自愈请求
class CtrlSession {
public:
    explicit CtrlSession(IChannel* channel) : channel_(channel) {}

    bool sendHello(uint64_t pcClockNs, uint64_t capabilities);
    bool waitHelloAck(HelloAckPayload& ack, uint32_t timeoutMs = 3000);
    bool sendConfig(const ConfigPayload& cfg);
    bool waitConfigAck(uint32_t timeoutMs = 3000);
    bool sendBye();
    bool requestRemount();                     // 自愈：请手机重新挂载 Gadget
    bool sendPing(uint64_t t1Ns);

    // 读取一个控制帧并解析出 TLV（供 LinkMonitor 与主循环使用）
    bool recv(uint16_t& type, std::vector<uint8_t>& value, uint32_t timeoutMs = 1000);

    std::string lastError() const { return lastError_; }

private:
    bool send(CtrlMsg type, const void* payload, size_t len);

    IChannel* channel_ = nullptr;
    std::string lastError_;
};

}  // namespace apxdisp
