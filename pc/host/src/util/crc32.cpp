#include "apxpc/util/crc32.hpp"

#include "apxpc/format.hpp"

#include <array>

namespace apxpc {

// 运行时生成查找表，避免静态初始化顺序问题
static const uint32_t* crcTable() {
    // C++11 保证 static 局部变量的初始化线程安全 —— lambda + std::array 一次性构建整张表
    static const std::array<uint32_t, 256> table = [] {
        std::array<uint32_t, 256> t{};
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            t[i] = c;
        }
        return t;
    }();
    return table.data();
}

uint32_t crc32Combine(uint32_t crc, const void* data, size_t len) noexcept {
    const uint32_t* t = crcTable();
    const uint8_t* p  = static_cast<const uint8_t*>(data);
    uint32_t c = crc ^ 0xFFFFFFFFu;
    for (size_t i = 0; i < len; ++i) c = t[(c ^ p[i]) & 0xFFu] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

uint32_t crc32(const void* data, size_t len) noexcept { return crc32Combine(0, data, len); }

}  // namespace apxpc

// hexDump 实现放这里（同属 util）
#include "apxpc/format.hpp"
namespace apxpc {
std::string hexDump(const void* data, size_t len, size_t bytesPerLine) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    std::string out;
    char buf[16];
    for (size_t i = 0; i < len; ++i) {
        if (i % bytesPerLine == 0) {
            if (i) out += '\n';
            std::snprintf(buf, sizeof(buf), "%04zx: ", i);
            out += buf;
        } else if (i % 8 == 0) {
            out += ' ';
        }
        std::snprintf(buf, sizeof(buf), "%02x ", p[i]);
        out += buf;
    }
    return out;
}
}  // namespace apxpc
