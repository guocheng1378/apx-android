#include "apxpc/localsend/sha256.hpp"

// v-oss（2026-11-26）：实现委托给 vendored 的 PicoSHA2（third_party/picosha2/picosha2.h，
// MIT，okdshin/PicoSHA2）。本文件只保留原有 Sha256 接口的薄适配——reset/update/hex
// 语义与手搓版一致：hex() 收尾取摘要后自动复位，实例可继续复用。正确性由
// localsend_protocol_test 的标准测试向量兜底（空串 / "abc" / 长串）。
//
// 为什么经适配层而不是直接包含 picosha2.h：把 13KB 头文件挡在单个 .cpp 里，
// 避免拖慢所有包含 sha256.hpp 的翻译单元；调用点（localsend 收发两端）零改动。
#include "picosha2.h"

#include <iterator>
#include <string>

namespace apxpc::localsend {

// picosha2::hash256_one_by_one 的流式接口：process 吃迭代器对，finish 收尾，
// get_hash_bytes 取 32 字节摘要。这里用 unsigned char 迭代器包一层 void*。
struct Sha256::Impl {
    picosha2::hash256_one_by_one hasher;
};

Sha256::~Sha256() { delete impl_; }

void Sha256::reset() {
    if (!impl_) impl_ = new Impl();
    impl_->hasher.init();
}

void Sha256::update(const void* data, size_t n) {
    const auto* p = static_cast<const unsigned char*>(data);
    impl_->hasher.process(p, p + n);
}

std::string Sha256::hex() {
    impl_->hasher.finish();
    unsigned char digest[picosha2::k_digest_size];
    impl_->hasher.get_hash_bytes(digest, digest + picosha2::k_digest_size);
    // 取完自动复位（与手搓版语义一致），调用方可继续复用同一实例
    impl_->hasher.init();
    // 32 字节 → 小写 64 字符十六进制
    static const char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(picosha2::k_digest_size * 2);
    for (unsigned char b : digest) {
        out.push_back(kHex[b >> 4]);
        out.push_back(kHex[b & 0xF]);
    }
    return out;
}

std::string sha256Hex(const void* data, size_t n) {
    Sha256 sha;
    sha.update(data, n);
    return sha.hex();
}

}  // namespace apxpc::localsend
