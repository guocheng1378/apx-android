#pragma once
// 手机摄像头 → PC 预览窗 / 虚拟摄像头 的**图像出口**（M7-c）。
//
// 数据流（镜像 mic_bridge 的模式，帧来自收流线程）：
//   手机 JPEG（streamId=6，s6 协议）→ feed() 只入队（禁止在回调里解码/渲染）
//   → 消费线程：GDI+ 解码 → ① 独立顶层显示窗（置顶 + 可拖 + 关闭即停）
//                    → ② NV12 写入共享内存（供虚拟摄像头 DLL 跨进程取帧）
//
// 虚拟摄像头（c-2）按进程拆两半，因为微信/会议端是把 Media Source
// CoCreateInstance 到**它们自己的进程**里跑的：
//   - 主程序（本文件的实现）：注册/移除虚拟摄像头 + 把帧写进共享内存；
//   - `vcam_source.cpp`（独立 DLL，CLSID 见 kVcamClsid）：实现 IMFMediaSource，
//     在对方进程内从共享内存读帧喂 MF 管线。
// 注册走 MFCreateVirtualCamera（mfvirtualcamera.h，Win11 build 22000+）：
// access=CurrentUser 免管理员、lifetime=Session（开关即移除，不跨重启）。
//
// ⚠️ 未真机实测（云端无 Win11 摄像头/微信端）：预览窗路径靠 CI 编译验证；
// 虚拟摄像头路径连真机都没跑过，面板上如实显示「未实测」，不假装能用。

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace apxpc::media {

// 虚拟摄像头 Media Source 的 COM CLSID（"{...}" 格式）。
// 固定值：注册表（HKCU\Software\Classes\CLSID，免管理员）与 MFCreateVirtualCamera
// 的 sourceId 都指向它，改 GUID 会留孤儿注册项。
inline constexpr wchar_t kVcamClsid[] =
    L"{7A3C9C1E-5B42-4F8A-9D61-2E8F0B4A6C11}";

// 虚拟摄像头输出的固定规格（producer 侧负责缩放/转换）。
// 定死 1280x720 NV12：手机分辨率随档位变，DLL 侧报固定格式才好被应用接受。
inline constexpr int kVcamWidth = 1280;
inline constexpr int kVcamHeight = 720;

struct CameraViewOptions {
    bool enablePreview = true;   // 是否拉起显示窗
    bool enableVirtualCam = false; // 是否注册虚拟摄像头（Win11 才可能成功）
    int monitorIndex = -1;       // -1 = 主显示器
};

class CameraView {
public:
    CameraView();
    ~CameraView();
    CameraView(const CameraView&) = delete;
    CameraView& operator=(const CameraView&) = delete;

    // FrameHandler 回调进来（收流线程）：只入队，不解码不渲染。
    // 队列有界（默认 8 帧），满则丢最旧——预览/虚拟摄像头只要最新帧。
    void feed(const uint8_t* jpeg, size_t len);

    // 面板 camera.toggle：开/关预览窗与虚拟摄像头，可重复调用。
    void setPreview(bool on);
    void setVirtualCam(bool on);

    struct Status {
        bool running = false;        // 消费线程在跑（有预览窗或虚拟摄像头任一开着）
        bool previewOpen = false;    // 显示窗存在
        bool virtualCam = false;     // 虚拟摄像头已注册且在位
        bool virtualCamTested = false; // 注册流程是否已实际执行过（没开过 = 未实测）
        uint64_t frames = 0;         // 累计解码帧
        uint64_t bytes = 0;          // 累计 JPEG 字节
        uint64_t lastFrameMs = 0;    // 最近一帧的 steady_clock 毫秒（0 = 还没收到）
        uint32_t width = 0;          // 解码出的画面尺寸
        uint32_t height = 0;
        bool stale = false;          // 3s 无帧 → 显示占位
        std::string error;           // 虚拟摄像头注册失败原因（如实展示，不吞）
    };
    Status status() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace apxpc::media
