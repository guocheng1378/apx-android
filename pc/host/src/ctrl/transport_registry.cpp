// 传输工厂：hid / bulk / auto（先 hid 再 bulk）。
#include <algorithm>
#include <cctype>
#include <memory>
#include <string>

#include "apxpc/ctrl/transport.hpp"
#include "apxpc/log.hpp"

namespace apxpc::ctrl {

std::unique_ptr<ICtrlTransport> createTransport(const std::string& kind,
                                                const std::string& devicePath) {
    std::string k = kind;
    std::transform(k.begin(), k.end(), k.begin(), [](unsigned char c) { return (char)::tolower(c); });

    if (k.empty() || k == "auto") {
        // PROTOCOL §3.3：优先 bulk（可靠顺序），失败退回 HID Report 5
        if (auto t = createBulkTransport(devicePath)) {
            if (t->open().ok()) return t;
        }
        if (auto t = createHidTransport(devicePath)) {
            if (t->open().ok()) return t;
        }
        APX_LOGW("无可用传输通道: {}", devicePath);
        return nullptr;
    }
    if (k == "hid")  return createHidTransport(devicePath);
    if (k == "bulk") return createBulkTransport(devicePath);
    APX_LOGW("未知传输类型: {}", kind);
    return nullptr;
}

}  // namespace apxpc::ctrl
