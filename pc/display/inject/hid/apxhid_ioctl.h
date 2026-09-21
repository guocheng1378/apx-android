/* apxhid_ioctl.h —— 用户态与 apxhid.sys 之间的自定义 IOCTL 定义（档 3 触控注入） */

#pragma once

#include <devioctl.h>

/* 设备接口 GUID（示例值，正式发布前必须替换为自有 GUID） */
/* {A7F3C1E2-5B4D-4A19-9C3E-8D2F6B1A4C77} */
DEFINE_GUID(GUID_DEVINTERFACE_APXHID,
    0xa7f3c1e2, 0x5b4d, 0x4a19, 0x9c, 0x3e, 0x8d, 0x2f, 0x6b, 0x1a, 0x4c, 0x77);

#define APXHID_PID 0x2D05

#define APXHID_DEVICE_TYPE 0x8000
#define APXHID_IOCTL_INDEX 0x800

/* 注入一条 HID 输入报告（输入缓冲 = 完整报告字节流，首字节为 Report ID） */
#define IOCTL_APXHID_INJECT_REPORT CTL_CODE(APXHID_DEVICE_TYPE, \
                                            APXHID_IOCTL_INDEX, \
                                            METHOD_BUFFERED,     \
                                            FILE_WRITE_ACCESS)
