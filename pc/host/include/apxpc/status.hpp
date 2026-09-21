#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace apxpc {

// 统一错误码，跨 discovery / ctrl / sensors / tools
enum class Status : int {
    Ok            =  0,
    NotFound      = -1,   // 设备/资源未找到
    Timeout       = -2,
    Io            = -3,   // 读写失败
    NotConnected  = -4,
    InvalidArg    = -5,
    NotSupported  = -6,   // 平台或设备不支持
    Protocol      = -7,   // 协议版本/格式不匹配
    Busy          = -8,
    AccessDenied  = -9,
    Internal      = -10,
};

inline const char* statusName(Status s) noexcept {
    switch (s) {
        case Status::Ok:           return "ok";
        case Status::NotFound:     return "not_found";
        case Status::Timeout:      return "timeout";
        case Status::Io:           return "io_error";
        case Status::NotConnected: return "not_connected";
        case Status::InvalidArg:   return "invalid_arg";
        case Status::NotSupported: return "not_supported";
        case Status::Protocol:     return "protocol_error";
        case Status::Busy:         return "busy";
        case Status::AccessDenied: return "access_denied";
        case Status::Internal:     return "internal";
    }
    return "?";
}

struct StatusEx {
    Status      code{Status::Ok};
    std::string message;

    bool ok() const noexcept { return code == Status::Ok; }
    explicit operator bool() const noexcept { return ok(); }
};

inline StatusEx ok() { return StatusEx{Status::Ok, {}}; }
inline StatusEx err(Status c, std::string msg = {}) { return StatusEx{c, std::move(msg)}; }

inline std::string statusToString(const StatusEx& s) {
    if (s.ok()) return "ok";
    return std::string(statusName(s.code)) + (s.message.empty() ? "" : (": " + s.message));
}

}  // namespace apxpc
