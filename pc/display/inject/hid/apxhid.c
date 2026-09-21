/*++

apxhid.c —— KMDF HID minidriver 骨架（档 3 触控注入）

状态：骨架。可编译为「能被 hidclass 加载、能回答描述符查询」的最小驱动，
      但尚未实现完整的报告队列并发保护与电源管理，请勿直接上生产环境。

构建：WDK 10.0.22621+ / VS2022，msbuild apxhid.vcxproj /p:Configuration=Release /p:Platform=x64
签名：EV 证书 + Windows HDC（正式）或 Test Signing（开发机，见 README.md）

--*/

#include <ntddk.h>
#include <wdf.h>
#include <hidport.h>
#include <hidpddi.h>

#include "apxhid_ioctl.h"

/* ------------------------------------------------------------------ 常量 --*/
#define APXHID_POOL_TAG 'dihA'
#define APXHID_MAX_REPORT 64
#define APXHID_QUEUE_DEPTH 64

/* ------------------------------------------------------- 报告描述符（占位）--
 * 覆盖 Digitizer Touch Screen(0x0D/0x04) 与 Pen(0x0D/0x02) 两个 TLC。
 * 坐标系：X/Y 逻辑范围 0..32767（PROTOCOL §2.5 归一化后换算），压力 0..32767。
 * 最终字节流应由 shared/ 的描述符生成器产出（ARCHITECTURE §4），此处为占位实现。
 *----------------------------------------------------------------------------*/
static const UCHAR g_ApxHidReportDescriptor[] = {
    0x05, 0x0D,                    /* Usage Page (Digitizer)                */
    0x09, 0x04,                    /* Usage (Touch Screen)                  */
    0xA1, 0x01,                    /* Collection (Application)              */
    0x85, 0x03,                    /*   Report ID (3)                       */
    0x09, 0x22,                    /*   Usage (Finger)                      */
    0xA1, 0x02,                    /*   Collection (Logical)                */
    0x09, 0x42,                    /*     Usage (Tip Switch)                */
    0x15, 0x00,                    /*     Logical Minimum (0)               */
    0x25, 0x01,                    /*     Logical Maximum (1)               */
    0x75, 0x01,                    /*     Report Size (1)                   */
    0x95, 0x01,                    /*     Report Count (1)                  */
    0x81, 0x02,                    /*     Input (Data,Var,Abs)              */
    0x09, 0x32,                    /*     Usage (In Range)                  */
    0x81, 0x02,                    /*     Input                             */
    0x09, 0x51,                    /*     Usage (Contact Identifier)        */
    0x25, 0x0F,                    /*     Logical Maximum (15)              */
    0x75, 0x04,                    /*     Report Size (4)                   */
    0x95, 0x01,                    /*     Report Count (1)                  */
    0x81, 0x02,                    /*     Input                             */
    0x05, 0x01,                    /*     Usage Page (Generic Desktop)      */
    0x09, 0x30,                    /*     Usage (X)                         */
    0x55, 0x00,                    /*     Unit Exponent (0)                 */
    0x65, 0x00,                    /*     Unit (None)                       */
    0x35, 0x00,                    /*     Physical Minimum (0)              */
    0x46, 0xFF, 0x7F,              /*     Physical Maximum (32767)          */
    0x26, 0xFF, 0x7F,              /*     Logical Maximum (32767)           */
    0x75, 0x10,                    /*     Report Size (16)                  */
    0x95, 0x01,                    /*     Report Count (1)                  */
    0x81, 0x02,                    /*     Input                             */
    0x09, 0x31,                    /*     Usage (Y)                         */
    0x46, 0xFF, 0x7F,              /*     Physical Maximum (32767)          */
    0x26, 0xFF, 0x7F,              /*     Logical Maximum (32767)           */
    0x81, 0x02,                    /*     Input                             */
    0x05, 0x0D,                    /*     Usage Page (Digitizer)            */
    0x09, 0x30,                    /*     Usage (Pressure)                  */
    0x26, 0xFF, 0x7F,              /*     Logical Maximum (32767)           */
    0x81, 0x02,                    /*     Input                             */
    0xC0,                          /*   End Collection                      */
    0x09, 0x55,                    /*   Usage (Contact Count Maximum)       */
    0x25, 0x0A,                    /*   Logical Maximum (10)                */
    0x75, 0x08,                    /*   Report Size (8)                     */
    0x95, 0x01,                    /*   Report Count (1)                    */
    0xB1, 0x02,                    /*   Feature (Data,Var,Abs)              */
    0xC0,                          /* End Collection                        */

    0x09, 0x02,                    /* Usage (Pen)                           */
    0xA1, 0x01,                    /* Collection (Application)              */
    0x85, 0x07,                    /*   Report ID (7)                       */
    0x09, 0x42,                    /*   Usage (Tip Switch)                  */
    0x09, 0x44,                    /*   Usage (Barrel Switch)               */
    0x09, 0x3C,                    /*   Usage (Invert / Eraser)             */
    0x09, 0x45,                    /*   Usage (Eraser)                      */
    0x09, 0x32,                    /*   Usage (In Range)                    */
    0x15, 0x00,                    /*   Logical Minimum (0)                 */
    0x25, 0x01,                    /*   Logical Maximum (1)                 */
    0x75, 0x01,                    /*   Report Size (1)                     */
    0x95, 0x05,                    /*   Report Count (5)                    */
    0x81, 0x02,                    /*   Input                               */
    0x75, 0x03,                    /*   Report Size (3)                     */
    0x95, 0x01,                    /*   Report Count (1)                    */
    0x81, 0x03,                    /*   Input (Const) padding               */
    0x05, 0x01,                    /*   Usage Page (Generic Desktop)        */
    0x09, 0x30,                    /*   Usage (X)                           */
    0x26, 0xFF, 0x7F,              /*   Logical Maximum (32767)             */
    0x75, 0x10,                    /*   Report Size (16)                    */
    0x95, 0x01,                    /*   Report Count (1)                    */
    0x81, 0x02,                    /*   Input                               */
    0x09, 0x31,                    /*   Usage (Y)                           */
    0x81, 0x02,                    /*   Input                               */
    0x05, 0x0D,                    /*   Usage Page (Digitizer)              */
    0x09, 0x30,                    /*   Usage (Pressure)                    */
    0x81, 0x02,                    /*   Input                               */
    0x09, 0x3D,                    /*   Usage (X Tilt Orientation)          */
    0x15, 0xE1,                    /*   Logical Minimum (-31)               */
    0x25, 0x1F,                    /*   Logical Maximum (31)                */
    0x75, 0x08,                    /*   Report Size (8)                     */
    0x81, 0x02,                    /*   Input                               */
    0x09, 0x3E,                    /*   Usage (Y Tilt Orientation)          */
    0x81, 0x02,                    /*   Input                               */
    0xC0                           /* End Collection                        */
};

static const HID_DESCRIPTOR g_ApxHidDescriptor = {
    sizeof(HID_DESCRIPTOR),        /* bLength                     */
    HID_HID_DESCRIPTOR_TYPE,       /* bDescriptorType             */
    HID_REVISION,                  /* bcdHID                      */
    0,                             /* bCountry                    */
    1,                             /* bNumDescriptors             */
    {
        {
            HID_REPORT_DESCRIPTOR_TYPE,
            (USHORT)sizeof(g_ApxHidReportDescriptor)
        }
    }
};

static const HID_DEVICE_ATTRIBUTES g_ApxHidAttributes = {
    sizeof(HID_DEVICE_ATTRIBUTES),
    0x18D1,                        /* VendorID（AOA 复用，实际应为自有 VID）*/
    APXHID_PID,                    /* ProductID（见 apxhid_ioctl.h）       */
    0x0100                         /* VersionNumber                        */
};

/* ------------------------------------------------------------ 设备上下文 --*/
typedef struct _APXHID_DEVICE_CONTEXT {
    WDFQUEUE  ReportQueue;         /* 用户态注入的报告队列             */
    WDFSPINLOCK QueueLock;
    UCHAR     Reports[APXHID_QUEUE_DEPTH][APXHID_MAX_REPORT];
    USHORT    ReportLength[APXHID_QUEUE_DEPTH];
    ULONG     Head;
    ULONG     Tail;
    ULONG     Count;
} APXHID_DEVICE_CONTEXT, *PAPXHID_DEVICE_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(APXHID_DEVICE_CONTEXT, ApxHidGetDeviceContext)

/* ------------------------------------------------------------ 队列操作 ----*/
static VOID ApxHidEnqueue(PAPXHID_DEVICE_CONTEXT ctx, PUCHAR report, USHORT len)
{
    WdfSpinLockAcquire(ctx->QueueLock);
    if (ctx->Count < APXHID_QUEUE_DEPTH && len <= APXHID_MAX_REPORT) {
        RtlCopyMemory(ctx->Reports[ctx->Tail], report, len);
        ctx->ReportLength[ctx->Tail] = len;
        ctx->Tail = (ctx->Tail + 1) % APXHID_QUEUE_DEPTH;
        ctx->Count++;
    }
    WdfSpinLockRelease(ctx->QueueLock);
}

static BOOLEAN ApxHidDequeue(PAPXHID_DEVICE_CONTEXT ctx, PUCHAR out, PUSHORT outLen)
{
    BOOLEAN ok = FALSE;
    WdfSpinLockAcquire(ctx->QueueLock);
    if (ctx->Count > 0) {
        *outLen = ctx->ReportLength[ctx->Head];
        RtlCopyMemory(out, ctx->Reports[ctx->Head], *outLen);
        ctx->Head = (ctx->Head + 1) % APXHID_QUEUE_DEPTH;
        ctx->Count--;
        ok = TRUE;
    }
    WdfSpinLockRelease(ctx->QueueLock);
    return ok;
}

/* --------------------------------------------- HID 内部 IOCTL 处理回调 ----*/
static VOID ApxHidEvtInternalDeviceControl(
    _In_ WDFQUEUE Queue,
    _In_ WDFREQUEST Request,
    _In_ size_t OutputBufferLength,
    _In_ size_t InputBufferLength,
    _In_ ULONG IoControlCode)
{
    NTSTATUS status = STATUS_NOT_SUPPORTED;
    WDFDEVICE device = WdfIoQueueGetDevice(Queue);
    PAPXHID_DEVICE_CONTEXT ctx = ApxHidGetDeviceContext(device);
    WDFMEMORY memory;
    PVOID buffer = NULL;
    size_t bytesReturned = 0;

    UNREFERENCED_PARAMETER(InputBufferLength);

    switch (IoControlCode) {
    case IOCTL_HID_GET_DEVICE_DESCRIPTOR:
        status = WdfRequestRetrieveOutputMemory(Request, &memory);
        if (NT_SUCCESS(status)) {
            status = WdfMemoryCopyFromBuffer(memory, 0, (PVOID)&g_ApxHidDescriptor,
                                             min(sizeof(g_ApxHidDescriptor), OutputBufferLength));
            bytesReturned = sizeof(g_ApxHidDescriptor);
        }
        break;

    case IOCTL_HID_GET_REPORT_DESCRIPTOR:
        status = WdfRequestRetrieveOutputMemory(Request, &memory);
        if (NT_SUCCESS(status)) {
            status = WdfMemoryCopyFromBuffer(memory, 0, (PVOID)g_ApxHidReportDescriptor,
                                             sizeof(g_ApxHidReportDescriptor));
            bytesReturned = sizeof(g_ApxHidReportDescriptor);
        }
        break;

    case IOCTL_HID_GET_DEVICE_ATTRIBUTES:
        status = WdfRequestRetrieveOutputMemory(Request, &memory);
        if (NT_SUCCESS(status)) {
            status = WdfMemoryCopyFromBuffer(memory, 0, (PVOID)&g_ApxHidAttributes,
                                             sizeof(g_ApxHidAttributes));
            bytesReturned = sizeof(g_ApxHidAttributes);
        }
        break;

    case IOCTL_HID_READ_REPORT:
    case IOCTL_HID_GET_INPUT_REPORT:
        status = WdfRequestRetrieveOutputBuffer(Request, APXHID_MAX_REPORT, &buffer, NULL);
        if (NT_SUCCESS(status) && buffer != NULL) {
            USHORT len = 0;
            /* TODO: 无数据时应把 Request 转发到手动队列挂起，等注入时再完成（骨架从简：直接返回空）*/
            if (ApxHidDequeue(ctx, (PUCHAR)buffer, &len)) {
                bytesReturned = len;
            } else {
                bytesReturned = 0;
                status = STATUS_DEVICE_DATA_ERROR;  /* 骨架：无数据立即失败，避免栈空转 */
            }
        }
        break;

    case IOCTL_HID_SET_FEATURE:
    case IOCTL_HID_GET_FEATURE:
        /* 骨架：不支持 Feature 报告 */
        status = STATUS_NOT_SUPPORTED;
        break;

    default:
        status = STATUS_NOT_SUPPORTED;
        break;
    }

    WdfRequestCompleteWithInformation(Request, status, bytesReturned);
}

/* --------------------------------------------- 用户态注入 IOCTL 处理回调 ----*/
static VOID ApxHidEvtIoDeviceControl(
    _In_ WDFQUEUE Queue,
    _In_ WDFREQUEST Request,
    _In_ size_t OutputBufferLength,
    _In_ size_t InputBufferLength,
    _In_ ULONG IoControlCode)
{
    NTSTATUS status = STATUS_INVALID_DEVICE_REQUEST;
    PAPXHID_DEVICE_CONTEXT ctx = ApxHidGetDeviceContext(WdfIoQueueGetDevice(Queue));
    PVOID buffer = NULL;
    size_t bufLen = 0;

    UNREFERENCED_PARAMETER(OutputBufferLength);

    if (IoControlCode == IOCTL_APXHID_INJECT_REPORT) {
        if (InputBufferLength == 0 || InputBufferLength > APXHID_MAX_REPORT) {
            status = STATUS_INVALID_BUFFER_SIZE;
        } else if (NT_SUCCESS(WdfRequestRetrieveInputBuffer(Request, InputBufferLength, &buffer, &bufLen)) &&
                   buffer != NULL) {
            ApxHidEnqueue(ctx, (PUCHAR)buffer, (USHORT)bufLen);
            status = STATUS_SUCCESS;
        }
    }
    WdfRequestCompleteWithInformation(Request, status, 0);
}

/* ------------------------------------------------------------ 设备添加 ----*/
static NTSTATUS ApxHidEvtDeviceAdd(_In_ WDFDRIVER Driver, _Inout_ PWDFDEVICE_INIT DeviceInit)
{
    NTSTATUS status;
    WDFDEVICE device;
    WDF_OBJECT_ATTRIBUTES attrs;
    WDF_PNPPOWER_EVENT_CALLBACKS pnpPower;
    WDF_IO_QUEUE_CONFIG queueConfig;
    PAPXHID_DEVICE_CONTEXT ctx;
    WDFQUEUE queue;

    UNREFERENCED_PARAMETER(Driver);

    /* HID minidriver 必须要把设备挂到 hidclass：设置设备类型与特征 */
    WdfDeviceInitSetDeviceType(DeviceInit, FILE_DEVICE_UNKNOWN);
    WdfDeviceInitSetExclusive(DeviceInit, FALSE);

    WDF_PNPPOWER_EVENT_CALLBACKS_INIT(&pnpPower);
    WdfDeviceInitSetPnpPowerEventCallbacks(DeviceInit, &pnpPower);

    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attrs, APXHID_DEVICE_CONTEXT);
    status = WdfDeviceCreate(&DeviceInit, &attrs, &device);
    if (!NT_SUCCESS(status)) return status;

    ctx = ApxHidGetDeviceContext(device);
    RtlZeroMemory(ctx, sizeof(*ctx));
    status = WdfSpinLockCreate(WDF_NO_OBJECT_ATTRIBUTES, &ctx->QueueLock);
    if (!NT_SUCCESS(status)) return status;

    /* 内部 IOCTL 队列（HID class 走 EvtIoInternalDeviceControl） */
    WDF_IO_QUEUE_CONFIG_INIT_DEFAULT_QUEUE(&queueConfig, WdfIoQueueDispatchParallel);
    queueConfig.EvtIoInternalDeviceControl = ApxHidEvtInternalDeviceControl;
    queueConfig.EvtIoDeviceControl = ApxHidEvtIoDeviceControl;
    status = WdfIoQueueCreate(device, &queueConfig, WDF_NO_OBJECT_ATTRIBUTES, &queue);
    if (!NT_SUCCESS(status)) return status;

    /* 交给 hidclass：此后由 HID class driver 负责向系统上报 */
    status = HidRegisterMinidriver(device);
    if (!NT_SUCCESS(status)) return status;

    return STATUS_SUCCESS;
}

/* ------------------------------------------------------------ 驱动入口 ----*/
NTSTATUS DriverEntry(_In_ PDRIVER_OBJECT DriverObject, _In_ PUNICODE_STRING RegistryPath)
{
    WDF_DRIVER_CONFIG config;
    NTSTATUS status;

    WDF_DRIVER_CONFIG_INIT(&config, ApxHidEvtDeviceAdd);
    status = WdfDriverCreate(DriverObject, RegistryPath, WDF_NO_OBJECT_ATTRIBUTES, &config, WDF_NO_HANDLE);
    return status;
}
