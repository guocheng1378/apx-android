#pragma once
// 控制面传输抽象：PROTOCOL §3.3 要求控制面永远走可靠顺序通道。
//   Windows：HID Report 5（OUT 写命令 / FEATURE 读状态）或 WinUSB bulk（AOA/NCM）
//   Linux  ：hidraw 或 libusb bulk
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "apxpc/status.hpp"

namespace apxpc::ctrl {

class ICtrlTransport {
public:
    virtual ~ICtrlTransport() = default;

    virtual StatusEx     open() = 0;
    virtual void         close() = 0;
    virtual bool         isOpen() const = 0;

    // 发送一帧（encodeFrame 的结果）。实现必须保证不丢不重排。
    virtual StatusEx     send(const uint8_t* data, size_t len) = 0;
    // 接收一帧；timeoutMs 内无数据返回 Timeout
    virtual StatusEx     recv(std::vector<uint8_t>& out, unsigned timeoutMs) = 0;

    virtual std::string  name() const = 0;
};

// kind: "" / "auto" / "hid" / "bulk"
std::unique_ptr<ICtrlTransport> createHidTransport(const std::string& devicePath);
std::unique_ptr<ICtrlTransport> createBulkTransport(const std::string& devicePath);
std::unique_ptr<ICtrlTransport> createTransport(const std::string& kind,
                                                const std::string& devicePath);

}  // namespace apxpc::ctrl
