#pragma once
// SHA-256（FIPS 180-4）—— LocalSend 接收端校验用。
//
// v-oss（2026-11-26）：实现从手搓换成第三方单头文件库 PicoSHA2（MIT，okdshin/PicoSHA2，
// 源码随仓 vendored 在 third_party/picosha2/）。手搓加密原语是最该避免的事——实现瑕疵
// 极难靠肉眼发现；PicoSHA2 经 Thunderbird / FreeBSD ports 等大量项目使用，且 SHA-256
// 是规格冻结算法，实现无需跟进更新。对外接口（本类与 sha256Hex）保持不变，调用点零改动；
// 正确性由 localsend_protocol_test 的标准测试向量（空串 / "abc" / 长串）兜底。
//
// 只用标准库整数运算，跨 Windows / macOS / Linux 一致；无外部链接依赖。
#include <array>
#include <cstddef>
#include <cstdint>
#include <string>

namespace apxpc::localsend {

class Sha256 {
public:
    Sha256() { reset(); }
    ~Sha256();
    // 内部持堆上哈希器指针，禁止拷贝/赋值（现有调用点均为局部实例 + 引用传参，不受影响）
    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;

    void reset();
    void update(const void* data, size_t n);
    /// 收尾并取十六进制摘要（小写 64 字符）。取完自动 reset，实例可继续复用。
    std::string hex();

private:
    // PicoSHA2 的流式哈希器（头文件内定义，此处持指针以避免把 13KB 头文件
    // 泄漏进所有包含本头的翻译单元；实现在 sha256.cpp 内完整类型可见）。
    struct Impl;
    Impl* impl_ = nullptr;
};

/// 一次性便捷接口
std::string sha256Hex(const void* data, size_t n);

}  // namespace apxpc::localsend
