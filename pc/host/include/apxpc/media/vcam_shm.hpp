#pragma once
// 虚拟摄像头跨进程帧协议（M7-c c-2）：**producer = 主程序，consumer = 微信进程里的 DLL**。
//
// 为什么要跨进程：MFCreateVirtualCamera 的 sourceId 指向 in-proc CLSID，
// 应用打开摄像头时是把 vcam_source DLL CoCreateInstance 到**它自己的进程**，
// 帧拿不到主程序内存 → 必须共享内存。
//
// 布局：命名 File Mapping = VcamShmHeader + 紧随其后的 NV12 像素（定死 1280x720）。
// 同步：seq 单调递增 + 命名事件（只作唤醒，消费者永远重读最新 seq，丢帧无所谓——
// 预览/虚拟摄像头只要最新帧，与投屏的可靠传输不是一回事）。

#include <cstdint>

namespace apxpc::media::vcam {

inline constexpr uint32_t kShmMagic = 0x50584156u; // 'APXV' 小端
inline constexpr int kWidth = 1280;
inline constexpr int kHeight = 720;
inline constexpr int kStride = kWidth;                 // NV12 无对齐填充
inline constexpr uint32_t kNv12Size =
    static_cast<uint32_t>(kStride) * kHeight * 3 / 2;  // Y + UV 交错

// 对象名：不加 Global\ —— 同一用户 session 内（微信/会议端）访问即可，
// 避免 Global 命名空间的 SeCreateGlobalPrivilege 权限问题。
inline constexpr wchar_t kShmName[] = L"Local\\AllPeriphVcamShm";
inline constexpr wchar_t kReadyEventName[] = L"Local\\AllPeriphVcamReady";
inline constexpr uint32_t kStaleMs = 3000; // 超时无新帧 → 消费端重复旧帧（保持画面）

#pragma pack(push, 8)
struct VcamShmHeader {
    uint32_t magic = kShmMagic;
    uint32_t width = kWidth;
    uint32_t height = kHeight;
    uint32_t stride = kStride;
    uint32_t nv12Size = kNv12Size;
    uint32_t reserved = 0;
    uint64_t seq = 0;      // 每写一帧 +1（从 1 起；0 = producer 还没写过）
    uint64_t ptsMs = 0;    // producer 的 steady_clock 毫秒（供消费端判 stale）
    uint64_t frameCount = 0; // 累计写入帧（诊断用）
};
#pragma pack(pop)

static_assert(sizeof(VcamShmHeader) == 48, "header 布局是跨进程 ABI，改字段必须同步两侧");

} // namespace apxpc::media::vcam
