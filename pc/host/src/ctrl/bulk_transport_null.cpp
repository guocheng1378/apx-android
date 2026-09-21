// 兜底实现：本平台既没有 WinUSB 也没有 libusb 时，bulk 通道不可用（控制面退回 HID）。
#include <memory>
#include <string>

#include "apxpc/ctrl/transport.hpp"
#include "apxpc/log.hpp"

namespace apxpc::ctrl {

std::unique_ptr<ICtrlTransport> createBulkTransport(const std::string&) {
    APX_LOGW("bulk 传输不可用：请安装 libusb-1.0 后重新配置 CMake（-DAPXPC_USE_LIBUSB=ON）");
    return nullptr;
}

}  // namespace apxpc::ctrl
