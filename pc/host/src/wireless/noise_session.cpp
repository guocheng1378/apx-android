// ============================================================================
// R2 通道加密 —— NoiseSession 实现（noise-c 参考实现封装）
// 设计与参数见头文件注释；Android 侧镜像在
// android/shared/src/main/java/com/allperiph/shared/net/NoiseSession.kt。
// ============================================================================
#include "apxpc/wireless/noise_session.hpp"
#include "apxpc/config/app_config.hpp"
#include "apxpc/log.hpp"

#include <noise/protocol/handshakestate.h>
#include <noise/protocol/cipherstate.h>
#include <noise/protocol/hashstate.h>
#include <cstring>
#include <mutex>
#include <string>

namespace apxpc::wireless {
namespace {

// Noise 协议名（noise-c names.c 与 noise-java HandshakeState 均按此解析）。
constexpr char kNoiseProtoName[] = "NoisePSK_IK_25519_ChaChaPoly_SHA256";

/// PSK 派生：SHA-256(UTF-8(token))，恒 32 字节。两端同式 —— token 不定长，
/// 哈希定长，避免握手参数随 token 长度变化。用 noise-c 自带的 SHA-256 实现，
/// 不引 apxpc_localsend 的 sha256（那是 LocalSend 库的内部实现）。
void derivePsk(const uint8_t* token, size_t tokenLen, uint8_t out[kNoisePskLen]) {
    NoiseHashState* hs = nullptr;
    if (noise_hashstate_new_by_name(&hs, "SHA256") != NOISE_ERROR_NONE) {
        // 理论不可达：协议名里就带 SHA256，解析失败说明 vendor 断链。
        APX_LOGE("[noise] SHA256 hashstate 创建失败");
        return;
    }
    noise_hashstate_hash_one(hs, token, tokenLen, out, kNoisePskLen);
    noise_hashstate_free(hs);
}

}  // namespace

// ---------------------------------------------------------- NoiseSession ----
struct NoiseSession::Impl {
    NoiseHandshakeState* hs = nullptr;   // 握手态（split 后释放）
    NoiseCipherState* csSend = nullptr;  // 传输态发送密钥（split 后持有）
    NoiseCipherState* csRecv = nullptr;  // 传输态接收密钥（split 后持有）
};

NoiseSession::NoiseSession() : impl_(new Impl) {}
NoiseSession::~NoiseSession() {
    if (impl_->csRecv) noise_cipherstate_free(impl_->csRecv);
    if (impl_->csSend) noise_cipherstate_free(impl_->csSend);
    if (impl_->hs) noise_handshakestate_free(impl_->hs);
    delete impl_;
}

bool NoiseSession::beginInitiator(const uint8_t* token, size_t tokenLen,
                                  const uint8_t* peerPub, const uint8_t* ownPriv) {
    Impl* p = impl_;
    int err = noise_handshakestate_new_by_name(&p->hs, kNoiseProtoName,
                                               NOISE_ROLE_INITIATOR);
    if (err != NOISE_ERROR_NONE) return false;
    // prologue / PSK 必须在 start() 前设置；顺序在 noise-c 内部有校验。
    if (noise_handshakestate_set_prologue(p->hs, kNoisePrologue,
                                          sizeof(kNoisePrologue) - 1) != NOISE_ERROR_NONE)
        return false;
    uint8_t psk[kNoisePskLen];
    derivePsk(token, tokenLen, psk);
    if (noise_handshakestate_set_pre_shared_key(p->hs, psk, kNoisePskLen) != NOISE_ERROR_NONE)
        return false;
    // IK 模式：双方静态密钥都进握手 —— 本端私钥 + 对端公钥（信标第 5 段）。
    NoiseDHState* local = noise_handshakestate_get_local_keypair_dh(p->hs);
    if (!local || noise_dhstate_set_keypair_private(local, ownPriv, kNoiseStaticKeyLen) != NOISE_ERROR_NONE)
        return false;
    NoiseDHState* remote = noise_handshakestate_get_remote_public_key_dh(p->hs);
    if (!remote || noise_dhstate_set_public_key(remote, peerPub, kNoiseStaticKeyLen) != NOISE_ERROR_NONE)
        return false;
    if (noise_handshakestate_start(p->hs) != NOISE_ERROR_NONE) return false;
    // IK 发起方 start 后即处于 WRITE_MESSAGE：writeHandshakeMsg 产出 msg1。
    return noise_handshakestate_get_action(p->hs) == NOISE_ACTION_WRITE_MESSAGE;
}

bool NoiseSession::beginResponder(const uint8_t* token, size_t tokenLen,
                                  const uint8_t* ownPriv) {
    Impl* p = impl_;
    if (noise_handshakestate_new_by_name(&p->hs, kNoiseProtoName,
                                         NOISE_ROLE_RESPONDER) != NOISE_ERROR_NONE)
        return false;
    if (noise_handshakestate_set_prologue(p->hs, kNoisePrologue,
                                          sizeof(kNoisePrologue) - 1) != NOISE_ERROR_NONE)
        return false;
    uint8_t psk[kNoisePskLen];
    derivePsk(token, tokenLen, psk);
    if (noise_handshakestate_set_pre_shared_key(p->hs, psk, kNoisePskLen) != NOISE_ERROR_NONE)
        return false;
    NoiseDHState* local = noise_handshakestate_get_local_keypair_dh(p->hs);
    if (!local || noise_dhstate_set_keypair_private(local, ownPriv, kNoiseStaticKeyLen) != NOISE_ERROR_NONE)
        return false;
    if (noise_handshakestate_start(p->hs) != NOISE_ERROR_NONE) return false;
    // 应答方 start 后处于 READ_MESSAGE：等 Initiator 的 msg1（0x35 帧 payload）。
    return noise_handshakestate_get_action(p->hs) == NOISE_ACTION_READ_MESSAGE;
}

int NoiseSession::writeHandshakeMsg(uint8_t* out, size_t outCap) {
    Impl* p = impl_;
    if (!p->hs) return -1;
    NoiseBuffer message;
    noise_buffer_set_output(message, out, outCap);
    // 握手帧 payload 恒空 —— 握手只交换 Noise 自身的公钥交换段，业务帧不搭车
    //（明/密分界只有一条：0x35 恒明文，之后全密文）。
    NoiseBuffer payload;
    noise_buffer_set_input(payload, out, 0);  // data 指针只做占位，size=0 不读
    int err = noise_handshakestate_write_message(p->hs, &message, &payload);
    if (err != NOISE_ERROR_NONE) {
        APX_LOGE("[noise] write_message 失败 err={}", err);
        return -1;
    }
    return static_cast<int>(message.size);
}

int NoiseSession::readHandshakeMsg(const uint8_t* data, size_t len) {
    Impl* p = impl_;
    if (!p->hs) return -1;
    NoiseBuffer message;
    noise_buffer_set_input(message, const_cast<uint8_t*>(data), len);
    // payload 输出区：握手 payload 恒空，给 0 容量让库按需拒绝（IK 里 payload 只可能是空）。
    uint8_t scratch[1];
    NoiseBuffer payload;
    noise_buffer_set_output(payload, scratch, sizeof(scratch));
    int err = noise_handshakestate_read_message(p->hs, &message, &payload);
    if (err != NOISE_ERROR_NONE) {
        // MAC 失败 = 对端不是持同一 token 的设备（或握手帧被改过），握手作废。
        APX_LOGW("[noise] read_message 失败 err={}", err);
        return -1;
    }
    return 0;
}

bool NoiseSession::split() {
    Impl* p = impl_;
    if (!p->hs) return false;
    if (noise_handshakestate_get_action(p->hs) != NOISE_ACTION_SPLIT) return false;
    if (noise_handshakestate_split(p->hs, &p->csSend, &p->csRecv) != NOISE_ERROR_NONE)
        return false;
    noise_handshakestate_free(p->hs);
    p->hs = nullptr;
    return true;
}

size_t NoiseSession::encrypt(uint8_t* buf, size_t payloadLen, size_t cap) {
    Impl* p = impl_;
    if (!p->csSend) return 0;
    // noise-c 强校验：明文超上限直接报错（noise-java 无此检查）。发送侧按丢帧处理。
    if (payloadLen > kNoiseMaxPlain || payloadLen + noise_cipherstate_get_mac_length(p->csSend) > cap)
        return 0;
    NoiseBuffer b;
    noise_buffer_set_inout(b, buf, payloadLen, cap);
    if (noise_cipherstate_encrypt_with_ad(p->csSend, nullptr, 0, &b) != NOISE_ERROR_NONE)
        return 0;
    return b.size;
}

size_t NoiseSession::decrypt(uint8_t* buf, size_t dataLen) {
    Impl* p = impl_;
    if (!p->csRecv) return 0;
    NoiseBuffer b;
    noise_buffer_set_inout(b, buf, dataLen, dataLen);
    if (noise_cipherstate_decrypt_with_ad(p->csRecv, nullptr, 0, &b) != NOISE_ERROR_NONE)
        return 0;  // 标签不过 = 篡改/密钥不符：调用方丢帧计数，不断链
    return b.size;
}

// ---------------------------------------------------------- 密钥派生/生成 ----
bool noisePublicFromPrivate(const uint8_t priv[kNoiseStaticKeyLen],
                            uint8_t pub_[kNoiseStaticKeyLen]) {
    // 借一个临时握手态拿 Curve25519 的 DH 实现（不 start、不参与任何会话）。
    NoiseHandshakeState* hs = nullptr;
    if (noise_handshakestate_new_by_name(&hs, kNoiseProtoName, NOISE_ROLE_INITIATOR) != NOISE_ERROR_NONE)
        return false;
    NoiseDHState* dh = noise_handshakestate_get_local_keypair_dh(hs);
    int err = noise_dhstate_set_keypair_private(dh, priv, kNoiseStaticKeyLen);
    if (err == NOISE_ERROR_NONE)
        err = noise_dhstate_get_public_key(dh, pub_, kNoiseStaticKeyLen);
    noise_handshakestate_free(hs);
    return err == NOISE_ERROR_NONE;
}

bool noiseGenerateKeyPair(uint8_t priv[kNoiseStaticKeyLen],
                          uint8_t pub_[kNoiseStaticKeyLen]) {
    // generate_keypair 内部走 noise-c 的 OS 随机源（rand_os.c），Windows/Linux/mac 全覆盖。
    NoiseHandshakeState* hs = nullptr;
    if (noise_handshakestate_new_by_name(&hs, kNoiseProtoName, NOISE_ROLE_INITIATOR) != NOISE_ERROR_NONE)
        return false;
    NoiseDHState* dh = noise_handshakestate_get_local_keypair_dh(hs);
    int err = noise_dhstate_generate_keypair(dh);
    if (err == NOISE_ERROR_NONE)
        err = noise_dhstate_get_keypair(dh, priv, kNoiseStaticKeyLen,
                                        pub_, kNoiseStaticKeyLen);
    noise_handshakestate_free(hs);
    return err == NOISE_ERROR_NONE;
}

// ---------------------------------------------------------- 进程级密钥懒加载 ----
namespace {

std::string toHex(const uint8_t* raw, size_t len) {
    static const char* kHex = "0123456789abcdef";
    std::string out;
    out.reserve(len * 2);
    for (size_t i = 0; i < len; ++i) {
        out.push_back(kHex[raw[i] >> 4]);
        out.push_back(kHex[raw[i] & 0x0F]);
    }
    return out;
}

/// hex → 裸字节；非法输入返回 false（配置文件手改坏的情形）。
bool fromHex(const std::string& hex, uint8_t* out, size_t len) {
    if (hex.size() != len * 2) return false;
    auto nib = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (size_t i = 0; i < len; ++i) {
        int hi = nib(hex[2 * i]), lo = nib(hex[2 * i + 1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    return true;
}

}  // namespace

bool ensureNoiseKeyPair(uint8_t priv[kNoiseStaticKeyLen],
                        uint8_t pub_[kNoiseStaticKeyLen]) {
    // 进程内只算一次：每次调用都 load+save config 会让 start() 调用点（托盘/面板/
    // 服务多处）反复落盘。once 标志 + 进程内缓存。
    static std::once_flag once;
    static bool ok = false;
    static uint8_t cachedPriv[kNoiseStaticKeyLen] = {};
    static uint8_t cachedPub[kNoiseStaticKeyLen] = {};
    std::call_once(once, [] {
        const std::string path = config::defaultConfigPath();
        config::AppConfig cfg = config::loadConfig(path);
        if (!cfg.noiseKeyPriv.empty()) {
            // 已有私钥：派生公钥即可（公钥不单独落盘，防两头不一致）。
            if (fromHex(cfg.noiseKeyPriv, cachedPriv, kNoiseStaticKeyLen) &&
                noisePublicFromPrivate(cachedPriv, cachedPub)) {
                ok = true;
                return;
            }
            APX_LOGW("[noise] 配置里的 noiseKeyPriv 非法，重新生成");
        }
        // 首次（或配置损坏）：生成新密钥对并落盘 —— 与 pairingToken 的
        // ensurePairingToken 同款「load → generate → save」节奏。
        if (!noiseGenerateKeyPair(cachedPriv, cachedPub)) return;
        cfg.noiseKeyPriv = toHex(cachedPriv, kNoiseStaticKeyLen);
        config::saveConfig(path, cfg);
        APX_LOGI("首次生成 R2 通道加密静态密钥对并写入配置");
        ok = true;
    });
    if (!ok) return false;
    memcpy(priv, cachedPriv, kNoiseStaticKeyLen);
    memcpy(pub_, cachedPub, kNoiseStaticKeyLen);
    return true;
}

}  // namespace apxpc::wireless
