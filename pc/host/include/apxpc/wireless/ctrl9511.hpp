#pragma once
// ============================================================================
// 统一控制面（9511）—— 三端互通的单一真源
//
// 这是「手机控 PC / PC 控手机 / PC 控 TV / 手机控手机 / 手机控 TV」共用的控制
// 协议，替代原先手机做服务端、PC 做客户端的 9500 旧路径（wireless_link.cpp 已删除）。
//
// 设计原则：
//   * 受控端 = 服务端（监听端口），控制端 = 客户端（主动连入）。谁控制谁，谁是客户端。
//   * 帧格式与 android/tv/net/TcpControlServer.kt、android/app/.../TvControllerClient.kt
//     逐字节一致：APX1 16 字节头（streamId=3）+ body + u32 CRC32。
//   * 握手：客户端先发 u32 LE 令牌长度 + UTF-8 令牌（空令牌也发那 4 字节长度）。
//   * 控制子命令（body[0]）：
//       0x01 鼠标   [1]=buttons [2]=dx(i8) [3]=dy(i8) [4]=wheel(i8)
//       0x02 多媒体 [1..2]=u16 位图（LE），位序见 android CONSUMER_MAP
//       0x03 键盘   [1]=mod [2]=0 [3..8]=k1..k6（HID usage 页 0x07）
//       0x04 触摸   [1]=action(0 down/1 up/2 move) [2]=0
//                     [3..4]=x u16 LE  [5..6]=y u16 LE（归一化 0..65535）[7..8]=0
//       0x20 剪贴板 [1..2]=u16 len LE [3..]=UTF-8（控制端 → 受控端）
//       0x21 反向剪贴板（受控端 → 控制端，受控端系统剪贴板变化时回传）
//       'p'  ping（控制端 1s 心跳）；受控端回 'pong'
//   * 发现信标：受控端每 1.5s 向 255.255.255.255:9501 广播
//       "APX1TV <name> <port> <token>"（UTF-8），手机端 TvDiscovery 据此列出可控设备。
//
// 注入与剪贴板是**平台相关**部分，由 createPlatformInjector()/createPlatformClipboardWatcher()
// 按编译目标返回对应实现（Windows=SendInput、Linux=uinput、macOS=CGEvent）。
// ============================================================================
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>

#if defined(_WIN32)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#else
#include <sys/socket.h>
#endif

namespace apxpc::wireless {

// ---------------------------------------------------------- 跨平台输入注入 ----
/// 受控端把收到的控制帧注入到本机系统。所有方法在接收线程调用，实现需线程安全。
class InputInjector {
public:
    virtual ~InputInjector() = default;

    /// 鼠标相对位移（i8 范围）。wheel 上滚为正；buttons bit0=左 bit1=右 bit2=中。
    virtual void injectMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel) = 0;

    /// 绝对坐标触摸（归一化 0..65535）。action: 0=down 1=up 2=move；buttons 决定双指=右键。
    virtual void injectTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y) = 0;

    /// HID 键盘（页 0x07）：mod 修饰位图 + 最多 6 个 usage；实现按边沿注入（down/up）。
    virtual void injectKeyboard(uint8_t mod, const uint8_t* keys, size_t count) = 0;

    /// Consumer 多媒体位图（位序见 android TvControlServer CONSUMER_MAP）。
    virtual void injectConsumer(uint16_t bitmap) = 0;

    /// 把文本写入本机剪贴板（受控端收到 0x20 帧时调用）。
    virtual void setClipboard(const std::string& text) = 0;
};

/// 按编译目标创建对应平台的注入器（失败返回 nullptr）。
std::unique_ptr<InputInjector> createPlatformInjector();

// ---------------------------------------------------------- 剪贴板反向监听 ----
/// 监听本机剪贴板变化；变化时回调最新文本。受控端据此发送 0x21 反向剪贴板帧。
class ClipboardWatcher {
public:
    virtual ~ClipboardWatcher() = default;
    /// 开始监听（可能起后台线程）。成功返回 true。
    virtual bool start() = 0;
    virtual void stop() = 0;
};

/// 创建平台相关的剪贴板监听器。cb 在变化时回调（可能来自其它线程）。
std::unique_ptr<ClipboardWatcher> createPlatformClipboardWatcher(
    std::function<void(const std::string&)> cb);

// ---------------------------------------------------------- 9511 受控服务端 ----
struct Ctrl9511ServerStatus {
    bool listening = false;
    bool connected = false;
    std::string peer;        // 对端 host:port
    std::string error;
    uint64_t mouse = 0;
    uint64_t touch = 0;
    uint64_t keyboard = 0;
    uint64_t consumer = 0;
    uint64_t clipboard = 0;
    uint64_t reverseClipboard = 0;
    uint64_t dropped = 0;
};

class Ctrl9511Server {
public:
    Ctrl9511Server();
    ~Ctrl9511Server();

    /// 启动监听 + 信标广播。name 用于信标与展示；token 为空表示不鉴权。
    bool start(uint16_t port, const std::string& token, const std::string& name);
    void stop();
    Ctrl9511ServerStatus status() const;

    /// 注册反向剪贴板回调：本机剪贴板变化时，自动回传给控制端。
    void setReverseClipboardEnabled(bool on) { reverseClipboard_ = on; }

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    bool reverseClipboard_ = true;
};

// ---------------------------------------------------------- 9511 控制客户端 ----
/// 客户端状态快照（供 UI 展示链路质量与注入计数）
struct Ctrl9511ClientStatus {
    bool connected = false;
    std::string peer;          // 对端 host:port
    double rttMs = -1;         // 心跳 RTT；-1 = 未测得
    uint64_t mouse = 0;
    uint64_t touch = 0;
    uint64_t keyboard = 0;
    uint64_t consumer = 0;
    uint64_t dropped = 0;
};

class Ctrl9511Client {
public:
    Ctrl9511Client();
    ~Ctrl9511Client();

    /// 主动连入受控端（手机/TV/PC 的 9511 服务端）。握手同手机 TvControllerClient。
    bool connect(const std::string& host, uint16_t port, const std::string& token);
    void disconnect();

    bool ready() const;

    // —— 输入发送（供本地采集/UI 调用）；与手机 TvControllerClient 镜像 ——
    void sendMouse(uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel = 0);
    void sendKeyboard(uint8_t mod, const uint8_t* keys, size_t count);
    void sendConsumer(uint16_t bitmap);
    void sendTouch(uint8_t action, uint8_t buttons, uint16_t x, uint16_t y);
    /// 发送剪贴板文本到受控端（body=[0x20,lenLo,lenHi,utf8]）。空文本忽略。
    bool sendClipboard(const std::string& text);

    /// 发送任意控制帧本体（含 cmd 字节）；用于扩展帧（如副屏 0x06 关键帧请求）。
    /// 受控端不识别的 cmd 会忽略，调用方无需关心。
    bool sendControl(const std::vector<uint8_t>& body);

    /// 线程安全状态快照（计数 / RTT / 连接）
    Ctrl9511ClientStatus status() const;

    /// 反向剪贴板回调：受控端剪贴板变化时回传（写入本机剪贴板由调用方决定）。
    std::function<void(const std::string&)> onReverseClipboard;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace apxpc::wireless
