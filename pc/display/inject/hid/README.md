# 档 3 · KMDF HID minidriver（设计 + 骨架）

> 状态：**设计 + 骨架**，未编译、未签名。本档不是 MS4 的必需路径 —— 档 1（`SendInput`）即可闭环，
> 档 2（`InjectSyntheticPointerInput`）已提供真多点触控。档 3 的价值是「副屏变成本机数位板」：
> 支持 hover、压感、倾斜、橡皮擦，且应用层无需改造（走标准 HID Digitizer Usage）。

## 1. 为什么必须内核驱动

Windows 的 HID 触控栈（`hidclass` + `hidparse`）只接受来自 **HID minidriver** 的输入报告。
用户态无法伪造一个「即插即用的触摸设备」，因此这是 `ARCHITECTURE.md §5` 里唯一无法免驱 + 无法用户态实现的一档。

代价：

| 项 | 要求 |
|---|---|
| 签名 | **EV 证书 + Windows Hardware Dev Center 认证**（或 WHQL）；测试期可 Test Signing |
| 运行环境 | Windows 10 1809+ x64 |
| 安装 | `pnputil /add-driver apxhid.inf /install`（需管理员 + 测试签名或正式签名） |
| 失败影响 | 驱动 Bug 直接 **蓝屏**，必须独立开发环境 + 双机调试 |

## 2. 总体结构

```
用户态（apxdisp）                       内核态（apxhid.sys，KMDF）
┌──────────────────────┐               ┌──────────────────────────────┐
│ 触控上行解析          │               │ HID minidriver                │
│ (PROTOCOL §2.5/§3.2) │  IOCTL        │  - 注册到 hidclass             │
│        ↓             │ ────────────► │  - 报告描述符（Digitizer TLC） │
│ IOCTL_APXHID_INJECT  │  输入报告     │  - 把注入的报告排队            │
└──────────────────────┘               │  - 响应 IOCTL_HID_READ_REPORT  │
                                       └──────────────────────────────┘
                                                    │
                                                    ▼
                                        Windows 触摸栈 → 应用收到 WM_POINTER*
```

关键点：

1. `DriverEntry` 里 `WdfDriverCreate` + `EvtDeviceAdd`；
2. `EvtDeviceAdd` 中设备对象创建后调用 **`HidRegisterMinidriver`**（把 WDFDEVICE 挂到 `hidclass`），
   并为 `EvtIoInternalDeviceControl` 注册回调处理 HID 内部 IOCTL；
3. 必须处理的 IOCTL（缺一不可）：
   - `IOCTL_HID_GET_DEVICE_DESCRIPTOR` → `HID_DESCRIPTOR`
   - `IOCTL_HID_GET_REPORT_DESCRIPTOR` → 报告描述符字节流
   - `IOCTL_HID_GET_DEVICE_ATTRIBUTES` → `HID_DEVICE_ATTRIBUTES`
   - `IOCTL_HID_READ_REPORT` / `IOCTL_HID_GET_INPUT_REPORT` → 返回的输入报告
   - `IOCTL_HID_SET_FEATURE` / `GET_FEATURE` → 可选
4. 用户态通过自定义 IOCTL（`IOCTL_APXHID_INJECT`）把 PROTOCOL §2.5 的触点结构直接喂给驱动，
   驱动维护一个环形队列，`READ_REPORT` 到来时取出一个报告返回（无数据时挂起 IRP 等待）。

## 3. 报告描述符

骨架里的 `g_ApxHidReportDescriptor` 覆盖：

- TLC 1：Touch Screen（`0x0D/0x04`），含 Tip Switch / In Range / Contact ID / X / Y / Pressure / Scan Time；
- TLC 2：Pen（`0x0D/0x02`），含 Tip Switch / Barrel Switch / Invert(Eraser) / In Range / X / Y / Pressure / Tilt X / Tilt Y。

坐标范围与 PROTOCOL §2.5 对齐：X/Y 归一化到 **0..32767**（HID 常用 15 位 + 1 符号位），
PC 端注入时按虚拟屏分辨率换算；压力 0..32767。

> **与 L1 的接口约定**：报告描述符字节流最终应由 `shared/` 的描述符生成器产出（ARCHITECTURE §4
> 「禁止各模块手写魔数」）。本骨架里的数组是**占位**，L1 交付后应替换为生成结果，
> 见 `reports/L4.json` 的 `contractDeviations`。

## 4. 构建与签名

```powershell
# 1) 安装 WDK 10.0.22621+（与 Windows SDK 版本一致）
# 2) 打开 "x64 Native Tools Command Prompt for VS 2022"
cd pc\display\inject\hid
msbuild apxhid.vcxproj /p:Configuration=Release /p:Platform=x64
# 3) 测试签名（开发机）
signtool sign /fd sha256 /td sha256 /a /n "AllPeriph Test" apxhid.sys
bcdedit /set testsigning on        # 重启生效
# 4) 安装
pnputil /add-driver apxhid.inf /install
# 5) 卸载
pnputil /delete-driver apxhid.inf /uninstall /force
```

正式发布必须走 **EV 签名 + Windows HDC 认证**（否则 Secure Boot 机器拒绝加载）。

## 5. 验收

- 设备管理器出现「HID-compliant touch screen」与「HID-compliant pen」；
- `设置 → 蓝牙和其他设备 → 触摸` 中可看到该设备；
- 用 `pc/tools` 的触控回环工具发一帧 2 触点，Windows  Ink / 画图能同时画出两条线；
- 笔的压感在 OneNote / Krita 中随压力变化。

## 6. 风险

| 风险 | 缓解 |
|---|---|
| 驱动 Bug 蓝屏 | 只在独立开发机调试；开启 Driver Verifier；不做首发必需路径 |
| EV 签名周期长（数周） | MS4 先用档 1/档 2 闭环，档 3 并行送签 |
| 报告描述符与手机端不一致 | 由 `shared/` 统一生成，两端共用同一份字节流 |
