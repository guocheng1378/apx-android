#include "apxpc/util/crc32.hpp"

#include "apxpc/format.hpp"

namespace apxpc {

// 运行时生成查找表，避免静态初始化顺序问题
static const uint32_t* crcTable() {
    static bool inited = false;
    static uint32_t table[256];
    if (!inited) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            table[i] = c;
        }
        inited = true;
    }
    return table;
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
