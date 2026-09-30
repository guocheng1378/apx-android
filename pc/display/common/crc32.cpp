#include "common/crc32.hpp"

#include <array>

namespace apxdisp {
namespace {

const uint32_t kCrcPoly = 0xEDB88320u;

uint32_t* makeTable() {
    // C++11 保证 static 局部变量初始化线程安全，lambda + std::array 一次性构建整张表
    static const std::array<uint32_t, 256> table = [] {
        std::array<uint32_t, 256> t{};
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) {
                c = (c & 1u) ? (kCrcPoly ^ (c >> 1)) : (c >> 1);
            }
            t[i] = c;
        }
        return t;
    }();
    return const_cast<uint32_t*>(table.data());
}

}  // namespace

uint32_t crc32Init() { return 0xFFFFFFFFu; }

uint32_t crc32Update(uint32_t crc, const void* data, size_t len) {
    const uint32_t* table = makeTable();
    const uint8_t* p = static_cast<const uint8_t*>(data);
    for (size_t i = 0; i < len; ++i) {
        crc = table[(crc ^ p[i]) & 0xFFu] ^ (crc >> 8);
    }
    return crc;
}

uint32_t crc32Final(uint32_t crc) { return crc ^ 0xFFFFFFFFu; }

uint32_t crc32Of(const void* data, size_t len) {
    return crc32Final(crc32Update(crc32Init(), data, len));
}

}  // namespace apxdisp
