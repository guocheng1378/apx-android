# -*- coding: utf-8 -*-
"""
APX HID 传感器/触摸板描述符解析探针（main 自有工具，不进构建）。

ctypes 直调 hid.dll：SetupAPI 枚举 HID 设备 → CreateFile →
HidD_GetPreparsedData → HidP_GetCaps + HidP_GetValueCaps，逐字段 dump。

用法：python scripts/hid_sensor_probe.py            # 全部 HID 设备
      python scripts/hid_sensor_probe.py 1d6b       # 只看 VID 匹配
"""
import ctypes
import ctypes.wintypes as wt
import sys

setupapi = ctypes.windll.setupapi
hid = ctypes.windll.hid
kernel32 = ctypes.windll.kernel32

setupapi.SetupDiGetClassDevsW.restype = ctypes.c_void_p
setupapi.SetupDiGetClassDevsW.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, wt.DWORD]
setupapi.SetupDiEnumDeviceInterfaces.restype = ctypes.c_int
setupapi.SetupDiEnumDeviceInterfaces.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                                 ctypes.c_void_p, wt.DWORD, ctypes.c_void_p]
setupapi.SetupDiGetDeviceInterfaceDetailW.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                                      ctypes.c_void_p, wt.DWORD,
                                                      ctypes.c_void_p, ctypes.c_void_p]
setupapi.SetupDiDestroyDeviceInfoList.restype = ctypes.c_int
setupapi.SetupDiDestroyDeviceInfoList.argtypes = [ctypes.c_void_p]
kernel32.CreateFileW.restype = ctypes.c_void_p
kernel32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.c_void_p,
                                 wt.DWORD, wt.DWORD, ctypes.c_void_p]
kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
hid.HidD_GetPreparsedData.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
hid.HidD_FreePreparsedData.argtypes = [ctypes.c_void_p]
hid.HidP_GetCaps.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
hid.HidP_GetValueCaps.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

DIGCF_PRESENT = 0x02
DIGCF_DEVICEINTERFACE = 0x10
HIDP_STATUS_SUCCESS = 0x00110000

class GUID(ctypes.Structure):
    _fields_ = [("d0", wt.DWORD), ("d1", wt.USHORT), ("d2", wt.USHORT), ("d3", ctypes.c_ubyte * 8)]


# GUID_DEVINTERFACE_HID {4D1E55B2-F16F-11CF-88CB-001111000030}
GUID_DEVINTERFACE_HID = GUID(
    0x4D1E55B2, 0xF16F, 0x11CF,
    (ctypes.c_ubyte * 8)(0x88, 0xCB, 0x00, 0x11, 0x11, 0x00, 0x00, 0x30),
)


class SP_DEVICE_INTERFACE_DATA(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("InterfaceClassGuid", GUID),
                ("Flags", wt.DWORD), ("Reserved", ctypes.POINTER(wt.ULONG))]


class HIDP_CAPS(ctypes.Structure):
    _fields_ = [("UsagePage", wt.USHORT), ("Usage", wt.USHORT),
                ("InputReportByteLength", wt.USHORT), ("OutputReportByteLength", wt.USHORT),
                ("FeatureReportByteLength", wt.USHORT), ("Reserved", wt.USHORT * 17),
                ("NumberLinkCollectionNodes", wt.USHORT), ("NumberInputButtonCaps", wt.USHORT),
                ("NumberInputValueCaps", wt.USHORT), ("NumberOutputButtonCaps", wt.USHORT),
                ("NumberOutputValueCaps", wt.USHORT), ("NumberFeatureButtonCaps", wt.USHORT),
                ("NumberFeatureValueCaps", wt.USHORT)]


class HIDP_VALUE_CAPS(ctypes.Structure):
    class _Ranges(ctypes.Structure):
        _fields_ = [("UsageMin", wt.USHORT), ("UsageMax", wt.USHORT),
                    ("StringMin", wt.USHORT), ("StringMax", wt.USHORT),
                    ("DesignatorMin", wt.USHORT), ("DesignatorMax", wt.USHORT),
                    ("DataIndexMin", wt.USHORT), ("DataIndexMax", wt.USHORT)]

    class _One(ctypes.Structure):
        _fields_ = [("Usage", wt.USHORT), ("Reserved1", wt.USHORT),
                    ("StringIndex", wt.USHORT), ("DesignatorIndex", wt.USHORT),
                    ("DataIndex", wt.USHORT)]

    _anonymous_ = ("Ranges", "One")
    _fields_ = [("UsagePage", wt.USHORT), ("ReportID", wt.BYTE),
                ("IsAlias", wt.BYTE), ("BitField", wt.USHORT), ("LinkCollection", wt.USHORT),
                ("LinkUsagePage", wt.USHORT), ("LinkUsage", wt.USHORT),
                ("IsMultipleItems", wt.BYTE), ("Ranges", _Ranges), ("One", _One),
                ("HasNull", wt.BYTE), ("Reserved", wt.BYTE * 3),
                ("BitSize", wt.ULONG), ("ReportCount", wt.ULONG),
                ("Reserved2", wt.ULONG * 5), ("BitsPersistent", wt.ULONG),
                ("HasData", wt.BYTE)]


def enum_devices(vid_filter):
    did = SP_DEVICE_INTERFACE_DATA()
    did.cbSize = ctypes.sizeof(did)
    ctypes.memmove(ctypes.byref(did.InterfaceClassGuid), ctypes.byref(GUID_DEVINTERFACE_HID), 16)
    setupapi.SetupDiGetClassDevsW.restype = ctypes.c_void_p
    hset = setupapi.SetupDiGetClassDevsW(ctypes.byref(GUID_DEVINTERFACE_HID), None, None,
                                         DIGCF_PRESENT | DIGCF_DEVICEINTERFACE)
    if hset in (0, None):
        return []
    out = []
    i = 0
    while True:
        di = SP_DEVICE_INTERFACE_DATA()
        di.cbSize = ctypes.sizeof(di)
        ctypes.memmove(ctypes.byref(di.InterfaceClassGuid), ctypes.byref(GUID_DEVINTERFACE_HID), 16)
        ok = setupapi.SetupDiEnumDeviceInterfaces(hset, None,
                                                  ctypes.byref(GUID_DEVINTERFACE_HID),
                                                  i, ctypes.byref(di))
        if not ok:
            break
        need = wt.DWORD(0)
        setupapi.SetupDiGetDeviceInterfaceDetailW(hset, ctypes.byref(di), None, 0, ctypes.byref(need), None)
        size = need.value
        if size > 0:
            buf = ctypes.create_string_buffer(size)
            # SP_DEVICE_INTERFACE_DETAIL_DATA: cbSize(x64=8) + path[]
            ctypes.memmove(buf, (8).to_bytes(2, 'little'), 2)
            if setupapi.SetupDiGetDeviceInterfaceDetailW(hset, ctypes.byref(di), buf, size, None, None):
                # x64 detail data：cbSize(4B)+path 自 offset 4 起（x64 官方 cbSize 值=8）
                path = ctypes.wstring_at(ctypes.addressof(buf) + 4)
                if vid_filter is None or vid_filter in path.lower():
                    out.append(path)
        i += 1
    setupapi.SetupDiDestroyDeviceInfoList(hset)
    return out


def probe(path):
    GENERIC_READ_WRITE = 0xC0000000
    print('  repr:', repr(path[:12]), 'len:', len(path))
    h = kernel32.CreateFileW(path, GENERIC_READ_WRITE, 3, None, 3, 0, None)
    if not h or h == 0xFFFFFFFFFFFFFFFF:
        print(f'  open failed err={kernel32.GetLastError()}')
        return
    try:
        pp = ctypes.c_void_p()
        if not hid.HidD_GetPreparsedData(h, ctypes.byref(pp)):
            print('  GetPreparsedData failed')
            return
        try:
            caps = HIDP_CAPS()
            st = hid.HidP_GetCaps(pp, ctypes.byref(caps))
            if st != HIDP_STATUS_SUCCESS:
                print(f'  HidP_GetCaps status={st:#x}  <== 解析失败点')
                return
            print(f'  TLC UsagePage={caps.UsagePage:#06x} Usage={caps.Usage:#06x} '
                  f'inLen={caps.InputReportByteLength} outLen={caps.OutputReportByteLength} '
                  f'featLen={caps.FeatureReportByteLength} collections={caps.NumberLinkCollectionNodes}')
            for kind, mode, cnt in (('feature', 2, caps.NumberFeatureValueCaps),
                                    ('input', 1, caps.NumberInputValueCaps)):
                if cnt == 0:
                    continue
                arr = (HIDP_VALUE_CAPS * cnt)()
                got = wt.ULONG(cnt)
                st = hid.HidP_GetValueCaps(mode, arr, ctypes.byref(got), pp)
                if st != HIDP_STATUS_SUCCESS:
                    print(f'  {kind} GetValueCaps status={st:#x}')
                    continue
                for k in range(got.value):
                    c = arr[k]
                    lo, hi = (c.Ranges.UsageMin, c.Ranges.UsageMax) if c.IsMultipleItems else (c.One.Usage, c.One.Usage)
                    print(f'  {kind}[{k}] rid={c.ReportID} usage={lo:#06x}..{hi:#06x} '
                          f'bits={c.BitSize} count={c.ReportCount}')
        finally:
            hid.HidD_FreePreparsedData(pp)
    finally:
        kernel32.CloseHandle(h)


if __name__ == '__main__':
    vf = sys.argv[1].lower() if len(sys.argv) > 1 else None
    devs = enum_devices(vf)
    print(f'{len(devs)} HID device(s)')
    for p in devs:
        print('==', p)
        probe(p)
