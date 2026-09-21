// 零依赖 LZ4（标准 LZ4 块格式）压缩/解压，用于 codecId=4（RAW_LZ4）兜底与离线自测。
// 块格式：token(字面量长度<<4 | 匹配长度-4) [字面量] [offset u16 LE] [匹配长度扩展]
#pragma once

#include <cstddef>
#include <cstdint>

namespace apxdisp {

size_t lz4CompressBound(size_t srcSize);
// 返回压缩后字节数；0 表示失败（含目标缓冲区不足）
size_t lz4Compress(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCap);
// 返回解压后字节数；0 表示失败
size_t lz4Decompress(const uint8_t* src, size_t srcSize, uint8_t* dst, size_t dstCap);

}  // namespace apxdisp
