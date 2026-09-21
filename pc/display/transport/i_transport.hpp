// 传输抽象（PROTOCOL §3 / §4）
//
// 通道模型：
//   - 视频（streamId=0）走 **可丢包** 通道：USB bulk 本身不丢包，所谓「可丢」体现在
//     写入前的策略层（帧过旧/队列过深就丢弃，见 pipeline），并在帧头打 kFlagDropable。
//   - 控制面（streamId=3）走 **可靠有序** 通道：控制帧绝不丢弃，且视频与控制共用同一条
//     bulk OUT 时，控制帧优先插队（见 UsbTransportWin::write 的实现）。
//   - 触控上行（streamId=2）走 bulk IN，读取线程优先处理。
//
// AOA / NCM 预留：UsbFilter.kind 决定走 AOA bulk 还是 NCM（TCP over USB-ECM）。
// 本轮实现 AOA/WinUSB bulk；NCM 仅留接口与 TODO（见 README「未来工作」）。
#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace apxdisp {

// Google AOA（Android Open Accessory）VID/PID
constexpr uint16_t kAoaVid       = 0x18D1;
constexpr uint16_t kAoaPidAcc    = 0x2D00;  // accessory
constexpr uint16_t kAoaPidAccAdb = 0x2D01;  // accessory + adb

enum class UsbKind {
    Auto    = 0,
    AoaBulk = 1,  // AOA bulk（本轮实现）
    Ncm     = 2,  // NCM / RNDIS（预留）
};

struct UsbFilter {
    uint16_t vid        = kAoaVid;
    uint16_t pid        = kAoaPidAccAdb;
    std::string serial;      // 可选
    std::string devicePath;  // 可选，直接指定（Windows 设备接口路径）
    uint8_t  bulkOutEp  = 0x01;
    uint8_t  bulkInEp   = 0x81;
    UsbKind  kind       = UsbKind::Auto;
};

enum class TransportKind { Auto = 0, WinUsb = 1, LibUsb = 2, Loopback = 3, Tcp = 4 };

// 统一传输描述：USB 用 usb，TCP 用 host/port，二者互斥。
// 把「怎么连」从「连什么」里解耦，使同一 ITransport 接口能承载 USB / TCP / 无线调试。
struct TransportSpec {
    TransportKind kind = TransportKind::Auto;
    UsbFilter     usb{};        // kind 为 USB 系列时有效
    std::string   host;         // Tcp：非空=客户端连接该主机；空=本端作服务端监听
    uint16_t      port = 0;     // Tcp：端口
    std::string   token;        // 无线模式令牌（配对阶段校验；为空表示不校验）

    static TransportSpec usbSpec(const UsbFilter& f) {
        TransportSpec s; s.kind = TransportKind::Auto; s.usb = f; return s;
    }
    static TransportSpec tcpClient(const std::string& h, uint16_t p, std::string tok = {}) {
        TransportSpec s; s.kind = TransportKind::Tcp; s.host = h; s.port = p; s.token = std::move(tok); return s;
    }
    static TransportSpec tcpServer(uint16_t p, std::string tok = {}) {
        TransportSpec s; s.kind = TransportKind::Tcp; s.port = p; s.token = std::move(tok); return s;
    }
};

// 单条通道（一个 bulk 端点方向的抽象）
class IChannel {
public:
    virtual ~IChannel() = default;

    // 返回写入字节数；0 表示失败或被丢弃
    virtual size_t write(const uint8_t* data, size_t len, uint32_t timeoutMs) = 0;
    virtual size_t read(uint8_t* buf, size_t cap, uint32_t timeoutMs) = 0;

    // 控制面 = 可靠；视频 = 可丢包
    virtual bool reliable() const = 0;

    // 取消挂起的 I/O（用于断线自愈）
    virtual void cancel() = 0;

    virtual std::string lastError() const = 0;
};

class ITransport {
public:
    virtual ~ITransport() = default;

    // 泛化打开：USB / TCP / 无线调试统一入口
    virtual bool open(const TransportSpec& spec) = 0;

    // 兼容旧调用点：以 USB 过滤器打开（等价于 open(TransportSpec::usbSpec(f))）
    bool open(const UsbFilter& filter) { return open(TransportSpec::usbSpec(filter)); }

    virtual void close() = 0;
    virtual bool isOpen() const = 0;

    virtual IChannel* videoChannel()   = 0;  // 下行视频（可丢包）
    virtual IChannel* controlChannel() = 0;  // 控制面（可靠有序）
    virtual IChannel* touchChannel()   = 0;  // 上行触控（bulk IN）

    // 自愈：清 stall / 复位管道；返回 true 表示可继续使用
    virtual bool reset() = 0;

    virtual std::string lastError() const = 0;
    virtual TransportKind kind() const = 0;
};

std::unique_ptr<ITransport> createTransport(const TransportSpec& spec);
std::unique_ptr<ITransport> createTransport(TransportKind kind, const TransportSpec& spec);

// 兼容旧调用点：以 USB 过滤器创建（等价于 TransportSpec{kind, filter}）
inline std::unique_ptr<ITransport> createTransport(TransportKind kind, const UsbFilter& f) {
    TransportSpec s; s.kind = kind; s.usb = f;
    return createTransport(kind, s);
}

std::unique_ptr<ITransport> createWinUsbTransport();
std::unique_ptr<ITransport> createLoopbackTransport();
std::unique_ptr<ITransport> createTcpTransport();

}  // namespace apxdisp
