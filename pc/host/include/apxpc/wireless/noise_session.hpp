#pragma once
// ============================================================================
// R2 通道加密 —— Noise 会话封装（docs/REQ-无线安全配对.md §4.1–§4.3）
//
// 协议：NoisePSK_IK_25519_ChaChaPoly_SHA256（Noise 协议名，两端参考实现）：
//   · PC 端 = third_party/noise-c（rhassnoise/noise-c，MIT，源码随库内 vendor）；
//   · Android 端 = com.southernstorm.noise（noise-java，同款行为，已逐行对拍）。
// 关键参数（与 Android shared/.../net/NoiseSession.kt 保持一致，改动必须两边同步）：
//   · prologue = "APX1-9511-v1"（定死；防跨协议混淆）；
//   · PSK = SHA-256(UTF-8(pairingToken)) 恒 32 字节（token 本身不定长，哈希定长）；
//   · 模式 IK：双方静态 Curve25519 密钥（信标第 5 段携带公钥，TOFU 升白名单），
//     前向保密由每个连接的临时密钥提供；PSK 提供「持有 token 才能完成握手」。
// 握手帧 0x35 恒明文：Initiator 发 msg1（write），Responder 收 msg1 后回 msg2；
// split() 之后进入传输态，encrypt/decrypt 在**帧 payload 整体**（含 CRC 尾）上做
// AD 为空的 AEAD，帧头（APX1 16 字节 + 长度）保持明文。
// 长度限制：noise-c 要求单条传输消息明文 ≤ NOISE_MAX_PAYLOAD_LEN(65535) - 16((mac))
// = 65519 字节，超限加密会失败 —— 发送侧超限按丢帧计数处理（见 ctrl9511.cpp 注释）。
// ============================================================================
#include <cstddef>
#include <cstdint>
#include <string>

namespace apxpc::wireless {

/// 握手 prologue（两端同源，见类注释）：9511 控制面 / 9512 文件 / 9502 媒体各一条，
/// 防跨协议混淆与重放（9512/9502 子批，2026-11-17）。
static constexpr char kNoisePrologue[] = "APX1-9511-v1";
static constexpr char kNoisePrologue9512[] = "APX1-9512-v1";
static constexpr char kNoisePrologue9502[] = "APX1-9502-v1";
/// PSK 长度（SHA-256 输出）与静态密钥长度（Curve25519）。
static constexpr size_t kNoisePskLen = 32;
static constexpr size_t kNoiseStaticKeyLen = 32;
/// 传输态单条消息明文上限（NOISE_MAX_PAYLOAD_LEN 65535 - 16 AEAD 标签）。
static constexpr size_t kNoiseMaxPlain = 65519;

/// Noise 传输会话。一个实例只服务一条连接；跨连接请重建实例。
/// 非线程安全 —— 调用方保证写侧（sendCmd/enqueue）与读侧各自持锁。
class NoiseSession {
public:
    /// prologue 按通道区分（默认 9511 兼容既有调用点）。
    explicit NoiseSession(const char* prologue = kNoisePrologue);
    ~NoiseSession();
    NoiseSession(const NoiseSession&) = delete;
    NoiseSession& operator=(const NoiseSession&) = delete;

    /// 发起方：准备 msg1。token=对端校验用的配对令牌（PSK 源）；peerPub=对端静态
    /// 公钥（信标第 5 段）；ownPriv=本端静态私钥。成功返回 true。
    bool beginInitiator(const uint8_t* token, size_t tokenLen,
                        const uint8_t* peerPub,
                        const uint8_t* ownPriv);
    /// 应答方：等待 msg1。ownPriv=本端静态私钥。成功返回 true。
    bool beginResponder(const uint8_t* token, size_t tokenLen,
                        const uint8_t* ownPriv);

    /// 生成下一条握手消息（Initiator 的 msg1 / Responder 的 msg2）。
    /// 返回写入 out 的长度；-1 = 状态机不在可写消息状态（重复调用等）。
    int writeHandshakeMsg(uint8_t* out, size_t outCap);
    /// 消费一条收到的握手消息（Initiator 收 msg2 / Responder 收 msg1）。
    /// 返回 0 成功（内部推进状态机）；-1 = 解析失败（密钥不匹配等）。
    int readHandshakeMsg(const uint8_t* data, size_t len);

    /// 握手完成后切出传输态密钥（两端都各消费/产出一条消息后调用）。
    bool split();

    /// 对端静态公钥（32 字节）：split 时从握手态摘出（Initiator 是配置进去的、
    /// Responder 从 msg1 解出）。9512/9502 子批新增 —— TOFU 持久化与文件反向
    /// 发送的预知公钥共用。split 前调用返回 false。
    bool peerStaticKey(uint8_t out[kNoiseStaticKeyLen]);

    /// 传输态：加密 buf[0..payloadLen)（原位），返回密文长度（payloadLen+16）；
    /// 0 = 失败（未 split 或超限 —— 上限见 kNoiseMaxPlain）。
    size_t encrypt(uint8_t* buf, size_t payloadLen, size_t cap);
    /// 传输态：解密 buf[0..dataLen)（原位，密文含 16 字节标签），返回明文长度；
    /// 0 = 失败（标签校验不过 = 帧被篡改/密钥不对，调用方按丢帧计数处理）。
    size_t decrypt(uint8_t* buf, size_t dataLen);

private:
    struct Impl;
    Impl* impl_;
};

/// 由静态私钥派生 Curve25519 公钥（不落任何状态）。成功返回 true。
bool noisePublicFromPrivate(const uint8_t priv[kNoiseStaticKeyLen],
                            uint8_t pub_[kNoiseStaticKeyLen]);
/// 生成一对新的 Curve25519 静态密钥。成功返回 true。
bool noiseGenerateKeyPair(uint8_t priv[kNoiseStaticKeyLen],
                          uint8_t pub_[kNoiseStaticKeyLen]);

/// 进程级懒加载本端 Noise 静态密钥对：读 defaultConfigPath() 的 noiseKeyPriv
///（64 hex）→ 有则派生公钥；无则生成并 saveConfig 落盘。出参为 32 字节裸值。
/// 线程安全（内部 once 防重）；失败返回 false（调用方回退明文链路）。
bool ensureNoiseKeyPair(uint8_t priv[kNoiseStaticKeyLen],
                        uint8_t pub_[kNoiseStaticKeyLen]);

// ---------------------------------------------------------------------------
// R2 进程级链路上下文（9512/9502 子批，2026-11-17）：
// 9511 控制面握手成功时写入当前链路的 token 与对端静态公钥，文件/媒体通道
// （file_sender / file_receiver / media_session）从这里取加密参数 —— 与
// Android NoiseSession.linkToken / loadPeerPub 同款「9511 学、多通道用」。
// 全部线程安全（内部 mutex statics）；token 为空串 = 链路无令牌，明文路径。
// ---------------------------------------------------------------------------

/// 9511 握手成功后写入当前链路被控端令牌（PSK 源）；空串 = 明文。
void r2SetToken(const std::string& token);
/// 9511 握手成功后写入对端静态公钥（32 字节裸值）。
void r2SetPeerStaticKey(const uint8_t pub_[kNoiseStaticKeyLen]);
/// 读当前链路被控端令牌。
std::string r2Token();
/// 读最近 TOFU 的对端静态公钥。成功返回 true（out 为 32 字节裸值）。
bool r2PeerStaticKey(uint8_t out[kNoiseStaticKeyLen]);

}  // namespace apxpc::wireless
