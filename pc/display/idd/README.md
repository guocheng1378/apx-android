# IddCx 虚拟显示器驱动（Indirect Display Driver）

> 状态：**骨架 + 方案文档**。本目录的源码**未经编译验证**（本机无 WDK / Windows SDK）。
> 交付目的是让接手者不必从零调研，并能**立即选一条能落地的路径**。

## 1. 为什么必须写驱动

扩展屏（把手机当 PC 的第二块屏）在 Windows 上**没有免驱路径**：

- Windows 不会因为你插了一个 USB 设备就凭空多出一块显示器
- 系统里必须存在一个**显示适配器**，桌面才能把画面投给它
- 唯一的正规接口是 **IddCx**（Indirect Display Driver，Win10 1607+）

对比一下其他方案的可行性：

| 方案 | 可行性 | 原因 |
|---|---|---|
| **IddCx 虚拟显示驱动** | ✅ **唯一正规路径** | 系统级虚拟适配器，桌面可投屏 |
| DisplayLink 协议 | ❌ | 专有协议，需授权，且需硬件/固件配合 |
| 伪装成 USB 显示器（UVC/DP Alt） | ❌ | Android 侧无 Display Gadget，USB 3.0 也没有 Display 类 |
| 纯用户态钩屏 + 手机本地显示 | ❌ | 无法让系统把桌面"投"到手机上 |

**结论：扩展屏 = IddCx 驱动 + 抓屏编码 + 传输 + 手机解码渲染。驱动是不可绕过的第一步。**

## 2. 三条落地路径（按推荐度排序）

### 路径 A：直接用现成的已签名驱动 ★ 推荐

**`itsmikethetech/Virtual-Display-Driver`**（开源、已签名）

- 直接安装即可获得一个可自定义分辨率/刷新率的虚拟显示器
- 我们的 `pc/display/capture/` 只需按显示器名/索引找到它，走 Desktop Duplication 抓屏
- **省掉整个驱动开发 + EV 签名环节**（后者是数周量级的流程）

**代价**：依赖第三方驱动，安装包需要引导用户先装它。对本项目而言这是**可接受的**——我们的价值在"手机变外设"，不在重复造一个虚拟显示器驱动。

**集成做法**：
1. 安装时检测是否已存在虚拟显示器（枚举 `EnumDisplayDevices`，看有没有我们期望的名字）
2. 没有则提示用户安装该驱动（或静默调它的安装脚本）
3. `capture/` 里按名称匹配到目标输出，开始 DDA 抓屏

### 路径 B：自研 IddCx 驱动

本目录的骨架就是给这条路准备的。**只在路径 A 不满足需求时走**，例如：
- 需要严格控制分辨率/刷新率列表（如 4K@60 + 手机原生分辨率）
- 需要与项目品牌一体化（显示适配器显示为 "AllPeriph Virtual Display"）
- 需要打包进单一安装程序

**代价**：WDK 开发 + **EV 代码签名证书**（约 $300/年，且需企业资质审核）+ 微软 Attestation 签名流程。**这是硬门槛，不是技术问题。**

### 路径 C：测试签名模式（仅开发自用）

自研驱动 + `bcdedit /set testsigning on`。**只适合自己调试**：
- 桌面右下角永久显示"测试模式"水印
- 部分游戏/反作弊会拒绝运行
- 不能分发给用户

## 3. 自研骨架的组成

```
idd/
  IddDriver.cpp      DriverEntry + DeviceAdd + Adapter/Monitor 建立流程
  IddDevice.hpp      IddCx 回调声明与设备结构
  build.ps1          WDK 构建脚本（MSBuild）
  README.md          本文件
```

**IddCx 的建立流程**（这是驱动的骨架脉络，`IddDriver.cpp` 里按此顺序实现）：

```
DriverEntry
  └─ WdfDriverCreate(config = { EvtDriverDeviceAdd })

EvtDriverDeviceAdd
  ├─ IDD_CX_CLIENT_CONFIG_INIT，挂上 5 个回调：
  │     EvtIddCxAdapterInitFinished
  │     EvtIddCxMonitorGetDefaultDescription
  │     EvtIddCxMonitorQueryTargetModes
  │     EvtIddCxMonitorAssignSwapChain
  │     EvtIddCxMonitorUnassignSwapChain
  ├─ IddCxDeviceInitConfig(DeviceInit, &config)
  ├─ WdfDeviceCreate()
  ├─ IddCxDeviceInitialize(device)
  └─ IddCxAdapterInitAsync()          ← 异步，完成后回调 ↓

EvtIddCxAdapterInitFinished
  ├─ IddCxMonitorCreate()             ← 每个显示器一个
  └─ IddCxMonitorArrival()            ← 通知系统"显示器已插入"

EvtIddCxMonitorQueryTargetModes        ← 上报支持的分辨率/刷新率列表
EvtIddCxMonitorAssignSwapChain         ← 系统开始投屏：启动处理线程
     循环：IddCxSwapChainReleaseAndAcquireBuffer
           → 拿到 IDXGIResource（桌面画面）
           → 交给 capture/encode 链路
           → IddCxSwapChainFinishedProcessingFrame
EvtIddCxMonitorUnassignSwapChain       ← 停止投屏
```

**关键约束**：

- **必须在 acquire/release 之间保持节奏**：拿到的 buffer 不还回去，系统会停止投递新帧。处理不过来的正确做法是**尽快 release + 丢帧**，而不是排队堆积（那会让端到端延迟无上限增长）
- 这一层只负责"提供一帧画面"，**不做编码也不做传输**——那些是 `capture/` `encode/` `transport/` 的事，驱动里做这些会把 DPC/工作线程拖死
- 手机分辨率与虚拟显示器分辨率**必须一致**（否则要缩放，白白增加延迟）

## 4. 构建与签名

```powershell
# 前置：Visual Studio 2022 + Windows Driver Kit (WDK) 10.0.22621+
# 构建
.\build.ps1 -Config Release

# 开发自测：开启测试签名模式（需重启，桌面会显示水印）
bcdedit /set testsigning on

# 安装（管理员）
pnputil /add-driver .\x64\Release\apxdisp_idd\apxdisp_idd.inf /install
```

**正式分发需要**：
1. **EV 代码签名证书**（Extended Validation，需企业主体审核）
2. 用 `signtool` 签 `.sys` 与 `.cat`
3. 提交微软 **Attestation Signing**（Partner Center），获得可被 Win10/11 直接安装的签名
4. 或走 **WHQL**（更严格，本项目不需要）

> **现实提醒**：EV 证书是**流程与费用门槛**，不是技术门槛。如果你没有企业主体，路径 A 是唯一可分发方案。

## 5. 与其余模块的接口约定

驱动与用户态之间**不直接通信**。约定是：

- 驱动只做一件事：**让系统里存在一块可投屏的显示器**
- 用户态 `capture/` 用 **Desktop Duplication API** 按输出索引找到它，拿到 `IDXGIOutputDuplication`
- 之后画面走 `capture/ → encode/ → transport/ → 手机`

**所以驱动是可独立替换的**：换用路径 A 的驱动，用户态代码一行都不用改——只要按"显示器名称/索引"匹配，而不是按驱动内部实现。

## 6. 当前状态与待办

| 项 | 状态 |
|---|---|
| 方案调研与路径选择 | ✅ 完成（本文档） |
| `IddDriver.cpp` 骨架 | ✅ 已交付（未编译验证） |
| `build.ps1` | ✅ 已交付 |
| `.inf` 安装文件 | ❌ **待补**（可参考 `pc/display/inject/` 下已有的 `.inf`） |
| WDK 编译验证 | ❌ 本机无 WDK |
| 签名与分发 | ❌ 需要 EV 证书（路径 A 可绕过） |

**建议的下一步**（按性价比排序）：

1. **先走路径 A**：把 `capture/` 与现成虚拟显示器驱动打通，**先让整条链路能跑起来**（抓屏 → 编码 → 传输 → 手机显示）
2. 链路跑通后，再决定是否需要自研驱动（通常到那时会发现"能自定义分辨率"才是刚需，而不是"驱动是谁写的"）
3. 若确实需要自研，用本骨架 + 测试签名模式先把功能调通，**最后再处理证书**
