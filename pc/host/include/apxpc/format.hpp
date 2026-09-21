#pragma once
// 极简格式化：只支持 "{}" 顺序占位，用 operator<< 序列化参数。
// 目的：避免依赖 <format>（部分 MSVC/SDK 组合不可用），保持零第三方依赖。
#include <array>
#include <sstream>
#include <string>
#include <string_view>

namespace apxpc {

namespace detail {
inline std::string toStrHelper() { return {}; }
template <class T>
inline std::string toStrOne(const T& v) {
    std::ostringstream os;
    os << v;
    return os.str();
}
}  // namespace detail

template <class... Args>
std::string format(std::string_view fmt, const Args&... args) {
    std::array<std::string, sizeof...(Args)> parts{detail::toStrOne(args)...};
    std::string out;
    out.reserve(fmt.size() + sizeof...(Args) * 8);
    size_t idx = 0;
    for (size_t i = 0; i < fmt.size(); ++i) {
        char c = fmt[i];
        if (c == '{' && i + 1 < fmt.size() && fmt[i + 1] == '}') {
            out += (idx < parts.size()) ? parts[idx++] : std::string("{}");
            ++i;
        } else if (c == '{' && i + 1 < fmt.size() && fmt[i + 1] == '{') {
            out += '{';
            ++i;
        } else if (c == '}' && i + 1 < fmt.size() && fmt[i + 1] == '}') {
            out += '}';
            ++i;
        } else {
            out += c;
        }
    }
    return out;
}

// 十六进制转储，便于排查 HID 报告字节流
std::string hexDump(const void* data, size_t len, size_t bytesPerLine = 16);

}  // namespace apxpc
