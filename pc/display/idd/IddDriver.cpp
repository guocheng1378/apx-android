// ============================================================================
// AllPeriph 虚拟显示器驱动（IddCx / UMDF2）
//
// 职责：让 Windows 里存在一块**可被桌面投屏的虚拟显示器**。仅此而已。
//   抓屏由 pc/display/capture/（Desktop Duplication）负责，
//   编码由 encode/ 负责，传输由 transport/ 负责 —— **不要写进驱动**，
//   驱动的工作线程被拖住会直接卡死投屏（DPC/工作项饥饿）。
//
// 状态：**骨架，未经编译验证**。本机无 WDK / Windows SDK。
//       方案对比与签名流程见同目录 README.md。
//
// 构建：见 build.ps1（需要 VS2022 + WDK 10.0.22621+）
// ============================================================================

#include <windows.h>
#include <wdf.h>
#include <iddcx.h>
#include <dxgi1_2.h>

#include <vector>

namespace {

// 虚拟显示器支持的模式列表（分辨率 / 刷新率）。
// 手机端分辨率应与其中之一完全一致，否则要缩放，白白增加 5~10ms 延迟。
struct DisplayMode {
    UINT32 width;
    UINT32 height;
    UINT32 refreshHz;
};

const DisplayMode kSupportedModes[] = {
    {1920, 1080, 60},
    {1920, 1080, 90},
    {1920, 1080, 120},
    {2340, 1080, 60},   // 常见手机竖屏原生宽度
    {2400, 1080, 60},
    {2560, 1440, 60},
    {3840, 2160, 60},
};
constexpr size_t kModeCount = sizeof(kSupportedModes) / sizeof(kSupportedModes[0]);

// ---------------------------------------------------------------- 设备上下文 --
struct DeviceContext {
    WDFDEVICE              device   = nullptr;
    IDDCX_ADAPTER          adapter  = nullptr;
    IDDCX_MONITOR          monitor  = nullptr;
    // 投屏处理线程句柄，在 AssignSwapChain / UnassignSwapChain 之间存活
    HANDLE                 processThread = nullptr;
    volatile LONG          stopThread    = 0;
};

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(DeviceContext, GetDeviceContext)

// 关闭投屏线程并等待退出。Acquire/Release 必须成对，线程退出前要确保
// 没有"已 acquire 未 release"的帧，否则系统会拒绝后续投屏。
void StopProcessThread(DeviceContext* ctx) {
    if (ctx->processThread == nullptr) return;
    InterlockedExchange(&ctx->stopThread, 1);
    WaitForSingleObject(ctx->processThread, 5000);
    CloseHandle(ctx->processThread);
    ctx->processThread = nullptr;
    InterlockedExchange(&ctx->stopThread, 0);
}

// ============================ IddCx 回调 =====================================

// 系统开始投屏：这里启动处理线程，循环取帧并交给上层链路。
//
// 关键节奏（本驱动唯一的性能敏感点）：
//   AcquireBuffer → 处理/转交 → FinishedProcessingFrame（=释放）
// **处理不过来时必须尽快 release 并丢帧**，绝不能把 buffer 攒着 ——
// 攒帧会让端到端延迟无上限增长，而丢帧只是掉几帧画面。
NTSTATUS EvtMonitorAssignSwapChain(
    IDDCX_MONITOR Monitor,
    const IDARG_IN_SET_SWAPCHAIN* pInArgs,
    IDARG_OUT_SET_SWAPCHAIN* pOutArgs) {
    UNREFERENCED_PARAMETER(pInArgs);
    pOutArgs->ProcessingStatus = IDDCX_SWAPCHAIN_PROCESSING_STATUS_OK;

    WDFDEVICE device = WdfObjectGet_WdfDeviceForMonitor(Monitor);  // 示意：实际用上下文取
    DeviceContext* ctx = GetDeviceContext(device);

    // 示意性骨架：真实实现需创建线程，在线程内循环：
    //
    //   IDARG_IN_RELEASE_AND_ACQUIRE_BUFFERS inArgs{};
    //   inArgs.SwapChain = pInArgs->SwapChain;
    //   IDARG_OUT_RELEASE_AND_ACQUIRE_BUFFERS outArgs{};
    //   while (!ctx->stopThread) {
    //       NTSTATUS st = IddCxSwapChainReleaseAndAcquireBuffer(
    //           pInArgs->SwapChain, &inArgs, &outArgs);
    //       if (st == STATUS_PENDING) continue;          // 暂无新帧
    //       if (!NT_SUCCESS(st))    break;
    //       // outArgs.MetaData 里有 DirtyRect / MoveRect —— 只编码变化区域
    //       // 这里把帧交出去（共享纹理 / 拷贝到环形缓冲），**不要做编码**
    //       IddCxSwapChainFinishedProcessingFrame(pInArgs->SwapChain);
    //   }
    //
    // 注意 IDARG_IN_RELEASE_AND_ACQUIRE_BUFFERS 的一次调用同时完成"还旧帧 + 取新帧"，
    // 上一帧的处理必须在这次调用之前结束（或已把数据拷走）。

    UNREFERENCED_PARAMETER(ctx);
    return STATUS_SUCCESS;
}

// 系统停止投屏：必须干净收尾，否则下次投屏会失败。
NTSTATUS EvtMonitorUnassignSwapChain(IDDCX_MONITOR Monitor) {
    WDFDEVICE device = WdfObjectGet_WdfDeviceForMonitor(Monitor);  // 示意
    DeviceContext* ctx = GetDeviceContext(device);
    StopProcessThread(ctx);
    return STATUS_SUCCESS;
}

// 上报支持的模式列表。手机端分辨率必须能在这里找到，否则会触发缩放。
NTSTATUS EvtMonitorQueryTargetModes(
    IDDCX_MONITOR Monitor,
    const IDARG_IN_QUERY_TARGET_MODES* pInArgs,
    IDARG_OUT_QUERY_TARGET_MODES* pOutArgs) {
    UNREFERENCED_PARAMETER(Monitor);

    const UINT32 capacity = pInArgs->ModeBufferSize / sizeof(IDDCX_TARGET_MODE);
    const UINT32 n = static_cast<UINT32>(kModeCount < capacity ? kModeCount : capacity);

    for (UINT32 i = 0; i < n; ++i) {
        IDDCX_TARGET_MODE& m = pInArgs->pModeBuffer[i];
        RtlZeroMemory(&m, sizeof(m));
        m.Size = sizeof(IDDCX_TARGET_MODE);
        m.TargetVideoSignalInfo.activeSize.cx = kSupportedModes[i].width;
        m.TargetVideoSignalInfo.activeSize.cy = kSupportedModes[i].height;
        // 总尺寸需留有消隐区；实际实现常用 width + 160 / height + 45 的经验值
        m.TargetVideoSignalInfo.totalSize.cx = kSupportedModes[i].width + 160;
        m.TargetVideoSignalInfo.totalSize.cy = kSupportedModes[i].height + 45;
        m.TargetVideoSignalInfo.vSyncFreq.Numerator   = kSupportedModes[i].refreshHz;
        m.TargetVideoSignalInfo.vSyncFreq.Denominator = 1;
        m.TargetVideoSignalInfo.hSyncFreq.Numerator   = kSupportedModes[i].refreshHz * 1000;
        m.TargetVideoSignalInfo.hSyncFreq.Denominator = 1;
        m.TargetVideoSignalInfo.pixelRate = kSupportedModes[i].width * kSupportedModes[i].height *
                                            kSupportedModes[i].refreshHz;
        m.TargetVideoSignalInfo.vSyncFreqDivider = 1;
    }
    pOutArgs->ModeCount = n;
    return STATUS_SUCCESS;
}

// 显示器默认描述（系统"显示设置"里看到的名字）
NTSTATUS EvtMonitorGetDefaultDescription(
    IDDCX_MONITOR Monitor,
    const IDARG_IN_GETDEFAULTDESCRIPTION* pInArgs,
    IDARG_OUT_GETDEFAULTDESCRIPTION* pOutArgs) {
    UNREFERENCED_PARAMETER(Monitor);
    UNREFERENCED_PARAMETER(pInArgs);

    // 实际实现需用 WdfStringCreate + 监视器 EDID（构造 EDID 才能让系统
    // 正确识别分辨率上限与类型；不提供 EDID 时系统会当作通用 PnP 显示器）
    RtlZeroMemory(&pOutArgs->MonitorMode, sizeof(pOutArgs->MonitorMode));
    pOutArgs->MonitorMode.Size = sizeof(pOutArgs->MonitorMode);
    pOutArgs->MonitorMode.ModeInfo.MonitorDescription.Size =
        sizeof(pOutArgs->MonitorMode.ModeInfo.MonitorDescription);
    return STATUS_SUCCESS;
}

// Adapter 初始化完成 → 创建并"插入"显示器
NTSTATUS EvtAdapterInitFinished(
    IDDCX_ADAPTER Adapter,
    const IDARG_IN_ADAPTER_INIT_FINISHED* pInArgs,
    IDARG_OUT_ADAPTER_INIT_FINISHED* pOutArgs) {
    UNREFERENCED_PARAMETER(Adapter);
    UNREFERENCED_PARAMETER(pOutArgs);
    if (pInArgs->AdapterInitStatus != STATUS_SUCCESS) return pInArgs->AdapterInitStatus;

    // 骨架：真实实现里
    //   IDDCX_MONITOR_CREATE_PARAMS p{};
    //   p.Size = sizeof(p);
    //   p.MonitorType = IDDCX_MONITOR_TYPE_INDIRECT_WIRED;
    //   p.pMonitorInfo = &monitorInfo;      // 含 EDID / 连接器类型
    //   IddCxMonitorCreate(Adapter, &p, &ctx->monitor);
    //   IddCxMonitorArrival(ctx->monitor, &arrivalArgs);
    return STATUS_SUCCESS;
}

// ------------------------------------------------------------------ 设备创建 --
NTSTATUS EvtDriverDeviceAdd(WDFDRIVER Driver, PWDFDEVICE_INIT DeviceInit) {
    UNREFERENCED_PARAMETER(Driver);

    IDD_CX_CLIENT_CONFIG iddConfig;
    IDD_CX_CLIENT_CONFIG_INIT(&iddConfig);
    iddConfig.EvtIddCxAdapterInitFinished          = EvtAdapterInitFinished;
    iddConfig.EvtIddCxMonitorGetDefaultDescription = EvtMonitorGetDefaultDescription;
    iddConfig.EvtIddCxMonitorQueryTargetModes      = EvtMonitorQueryTargetModes;
    iddConfig.EvtIddCxMonitorAssignSwapChain       = EvtMonitorAssignSwapChain;
    iddConfig.EvtIddCxMonitorUnassignSwapChain     = EvtMonitorUnassignSwapChain;

    NTSTATUS status = IddCxDeviceInitConfig(DeviceInit, &iddConfig);
    if (!NT_SUCCESS(status)) return status;

    WDF_OBJECT_ATTRIBUTES attr;
    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attr, DeviceContext);
    // IddCx 要求设备支持这些：清除"意外移除"等默认策略
    WdfFdoInitSetFilter(DeviceInit);   // 视实际 PnP 形态而定

    WDFDEVICE device = nullptr;
    status = WdfDeviceCreate(&DeviceInit, &attr, &device);
    if (!NT_SUCCESS(status)) return status;

    status = IddCxDeviceInitialize(device);
    if (!NT_SUCCESS(status)) return status;

    DeviceContext* ctx = GetDeviceContext(device);
    ctx->device = device;

    IDDCX_ADAPTER_CREATE_PARAMS adapterParams;
    IDDCX_ADAPTER_CREATE_PARAMS_INIT(&adapterParams);
    adapterParams.Size = sizeof(adapterParams);
    // 适配器名（显示适配器列表里显示的名字）
    // adapterParams.pDriverName / pAdapterName 需用 WdfString 设置

    IDARG_IN_ADAPTER_INIT adapterInit{};
    return IddCxAdapterInitAsync(&adapterParams, &adapterInit);
}

}  // namespace

// ------------------------------------------------------------------- WDF 入口 --
extern "C" NTSTATUS DriverEntry(PDRIVER_OBJECT DriverObject, PUNICODE_STRING RegistryPath) {
    WDF_DRIVER_CONFIG config;
    WDF_DRIVER_CONFIG_INIT(&config, EvtDriverDeviceAdd);
    return WdfDriverCreate(DriverObject, RegistryPath, WDF_NO_OBJECT_ATTRIBUTES, &config,
                           WDF_NO_HANDLE);
}
