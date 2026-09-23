# 真机调试记录

> 记录人：main　日期：2026-09-20
> 这是**实测结论**，不是推测。每条都附了可复现的现象或内核证据。
> 目的：避免后续 lane 重复踩坑，也避免把「已证伪的做法」再写一遍。

## 1. 测试环境

| 项 | 值 |
|---|---|
| 设备 | Xiaomi / HyperOS（Android 16），UDC = `a600000.dwc3` |
| 链路速度 | **high-speed（非 SuperSpeed）** → 高带宽流受限，需降级提示（文案见 `GadgetManager`） |
| root | KernelSU |
| PC 侧工具 | adb、JDK 17、Gradle 8.9、Android SDK 35、`python -m ziglang`（编译 `shared/`） |

## 2. 已确认可用（这些不必再排查）

- `su -c sh` 能拿到 root shell：`RootShell: root shell up via su -c sh`
- `setenforce 0` 可切 permissive：`SELinux Enforcing -> permissive：code=0`
- `libapx.so` 加载正常：`ApxNative: shared lib loaded, init=true`
- **自建 gadget 的创建阶段可全部成功**：`idVendor` / `idProduct` / `bcdUSB` / `strings/*` / `configs/*` / `functions/*` 的属性写入均无错误

## 3. 内核级障碍（4 个，均有实测证据）

### 3.1 gadget「名额」是一次性的 —— 最致命

- 内核创建 gadget 时会注册 `/sys/devices/virtual/android_usb/androidN`
- **失败时内核返回 `-EEXIST`，却被报成 `ENOMEM`**：
  ```
  mkdir: '/dev/apx_cfg/usb_gadget/apx': Out of memory
  kobject: kobject_add_internal failed for android2 with -EEXIST,
           don't try to register things with the same name in the same directory
  ```
  那个 `Out of memory` 完全误导，真因是**名字冲突**。
- **`rm -rf` 对 configfs 完全无效** —— 这条是后来才意识到的关键：configfs 里目录中的属性文件无法 unlink，`rm -rf` 第一步就报错退出，**gadget 目录原样留在那里**。所以此前"已经删掉 gadget 了、重建仍报 EEXIST"的推断**前提不成立**：它根本没被删掉。清理 configfs 必须**从最内层逐级 `rmdir`**（先 `rm -f` 软链，再 `rmdir` functions/*，最后逐级向上）。
- **实测（2026-09-20）：即使 configfs 里的 gadget 目录被正确删除（逐级 `rmdir` 全部成功，
  `ls /config/usb_gadget` 只剩 g1/g2），内核对象依然不释放。**
  所以这不是"清理没做干净"的问题，而是内核行为本身。
- 后果：**同一开机周期内只能成功创建一次 gadget**，卸载后无法重建
- 唯一确定可靠的恢复方式：**重启**
- 工程含义：**失败路径绝不能重试**，任何无效尝试都在烧掉机会
- **对策：gadget 常驻** —— 卸载时**只解绑 UDC，不删除 gadget 本体**。只要目录还在，
  下次挂载的 `mkdir -p` 会 stat 命中并直接跳过，**不调用 `mkdir(2)`**，也就不触发内核
  注册，因此可以反复挂载/卸载。代价：改描述符等配置必须走「解绑 UDC → 改属性 → 重新绑定」，
  而不是重建 gadget（`mount` 的属性写入本就是幂等的，软链已改为幂等写法）。

### 3.2 HAL 不释放 UDC

- 执行 `setprop sys.usb.config none` 后，Xiaomi 的 HAL **不会**把 `g1/UDC` 清空
- 现象：`UsbHalArbiter: UDC still busy after 800ms` 持续出现；我们写自己的 UDC 时报
  `printf: write: Device or resource busy`
- 对策：**主动替它解绑** —— `echo '' > /config/usb_gadget/g1/UDC`

### 3.3 读 configfs 目录会把内核挂住（且症状极具误导性）

- `ls /config/usb_gadget`、`ls /sys/devices/virtual/android_usb/` 会**阻塞**
- 阻塞吃满 RootShell 超时 → shell 标记 `broken` → **此后所有命令静默返回空**
- 表现为 `mkdir ... code=-1`、诊断输出全空 —— 曾在多轮里把我引向「SELinux 拒绝」「目录不存在」等错误结论
- 对策：
  - 判断 UDC 是否空闲改用 `/sys/class/udc/*/state`（不阻塞，`not attached` 即空闲）
  - 失败诊断只读 `dmesg`（不阻塞），**绝不 `ls` 那两个目录**

### 3.4 往系统 `g1` 链 function 恒被拒

- `ln -s` 到 `/config/usb_gadget/g1/configs/b.1/` **恒返回 `EINVAL`**
- 三种写法全试过，全失败：绝对路径、`../../functions/x` 相对路径、`cd` 到 b.1 后再相对路径
- **`setenforce 0` 后同样失败** → **不是 SELinux 的问题**，是该内核的限制
- 结论：**「复用系统 g1」这条路已证伪，不要再用**

### 3.5 私有 configfs 挂载点是死路（**最隐蔽的坑**）

- 曾用 `mount -t configfs none /dev/apx_cfg` 自建挂载点，理由是"绕开 Android 管理的 `/config`"
- **代价是致命的**：configfs 挂载**按 mount namespace 生效**，挂在 `/dev/apx_cfg` 只有
  **App 自己的 namespace** 看得见。它不在全局挂载表里 —— 用 `adb shell mount | grep configfs`
  只能看到 `/config`，看不到 `/dev/apx_cfg`，这就是证据。
- 于是 App 一旦被杀（`adb install -r`、LMK、崩溃）：
  1. namespace 销毁 → 挂载点**直接消失**
  2. 内核创建 gadget 时注册的 `androidN` **不会跟着释放**
  3. 残留名占位 → 此后创建 gadget 恒失败（报 ENOMEM，真身 EEXIST）
  4. **而清理入口已经不存在了**（挂载点没了），连 `rmdir` 都没得删
  5. 唯一出路：重启手机 —— 调试循环彻底锁死
- 正解：**只用全局挂载点 `/config/usb_gadget`**。它由 init 挂载，gadget 生命周期与
  App 进程解耦：进程重启后仍看得见残留、也删得掉。
- 附带说明：`/config/usb_gadget` 上的"读操作挂起"现象（§3.3）只在**内核已处于异常状态**
  时出现（例如 HAL 刚被 `sys.usb.config=none` 打断）；正常状态下读它并不阻塞。

### 3.6 UAC2 音频：内核侧是通的，坑全在裸 ALSA 的调用方式

**结论：手机侧确实会生成 ALSA 声卡。**（此前一度误判为"没有 UAC2Gadget"，
原因是那次是在**切回之后**才查的 —— 那时 gadget 已卸载、声卡已消失。必须在**挂载期间**查。）

```
== cards ==
 1 [UAC2Gadget     ]: UAC2_Gadget - UAC2_Gadget
== pcm ==
01-00: UAC2 PCM : UAC2 PCM : playback 1 : capture 1
== dev/snd ==
pcmC1D0p  pcmC1D0c
```

- 声卡号**运行时定位**：在 `/proc/asound/cards` 里搜 `UAC2Gadget`（禁止硬编码 `hw:0,0`）
- PCM 节点默认仅 `audio` 组可读写，App 属 `untrusted_app` 打不开 —— 挂载阶段需以 root
  `chmod 666 /dev/snd/pcmC*D*`（已加入 `ConfigFsLayout.mount`）
- `f_uac2` 的 `c_terminal` / `p_terminal` 在**本内核不存在**（实际属性名是
  `c_it_name`/`c_ot_name`/`c_it_ch_name` 等）；写失败**不影响功能**，只是终端名没设上

**裸 ALSA 的三个坑**（NDK 不提供 libasound，只能用 `<sound/asound.h>` 的 ioctl 自己拼）：

1. **`masks` 全 0 时 `HW_REFINE` 返回 EINVAL** —— 等于声明「access/format 一个都不允许」。
   必须先把 access/format/subformat 三个 mask 置为「任意」（全 1）。
2. **只 `memset(0)` 就调 `HW_PARAMS` 同样 EINVAL** —— 未显式赋值的 interval 保持
   `min=max=0`，于是 `SAMPLE_BITS`、`FRAME_BITS` 等被约束成「只能取 0」，逐个校验时
   判定不可满足。正解：先把**所有** interval 置为「任意」（`min=0, max=UINT_MAX`），
   **再**覆写目标值（等价 alsa-lib 的 `snd_pcm_hw_params_any_init`）。
3. **playback 流在空缓冲上显式 `START` 返回 `EPIPE(-32)` 是正常行为** ——
   playback 应在首次 `write()` 达到 `start_threshold` 时自动启动。
   把 START 的失败当致命错误，会导致 playback 永远打不开。

**排查利器**：系统自带 `tinyplay` / `tinycap` / `tinymix` / **`tinypcminfo`**。
`tinypcminfo -D <card> -d <dev>` 直接列出该 PCM 允许的 rate/format/channels/period
真实范围，是定位 `EINVAL` 最快的手段：

```
Rate:         min=48000  max=48000
Channels:     min=2      max=2
Sample bits:  min=16     max=16
Period size:  min=25     max=1024
Period count: min=4      max=16
```

**验收**：双向音频已打通 —— `AudioModule` 同时打开 `pcmC?D0p`(playback) 与
`pcmC?D0c`(capture)，PC 侧放歌从手机扬声器出声、录音能录到手机麦克风。

### 3.7 新增模块必须同时登记到 `AgentController.ORDER`

`AgentController.startEnabled()` 是**按 `ORDER` 列表**逐个启动模块的。只在 `build()` 里
`register` 而忘了加进 `ORDER`，模块会**静默地永不启动**（连一条日志都没有），
极易误判成"模块内部出错"。新增模块时两处都要改。

### 3.8 FunctionFS 在本机不可用（原为副屏 bulk 通道）

**结论：`f_fs` 能建、能挂，但 bind 阶段必失败 —— 本机无法新增 FFS 实例。**

证据链（2026-09-20，均为 dmesg / `/proc/mounts` 实测）：

```
ffs mount rc=0                      ← mount -t functionfs apx /dev/usb-ffs/apx 成功
ffs.apx exists: yes                 ← configfs 里 functions/ffs.apx 建成功
内核: Can't create any more FFS log contexts
      udc a600000.dwc3: failed to start apx: -19
```

- 系统已占用 **6 个** FunctionFS 实例：`adb` / `aoa` / `ctrl` / `ipcr` / `mtp` / `ptp`
  （`/proc/mounts` 可见；且 `/dev/usb-ffs/adb/` 里已有 `ep0/ep1/ep2`）
- 内核 **FFS 上下文数量有限**，被系统占满后，我们的 `ffs` function 在 bind 时建不出
  上下文 → 整个 gadget 的 `composite_bind` 失败 → **UDC 绑定被拖垮**
- **症状极具误导性**：写 UDC 只报 `Device or resource busy (EBUSY)`，
  真正原因藏在 dmesg 的 `Can't create any more FFS log contexts`
- 试过让出无关实例（`umount /dev/usb-ffs/{ipcr,aoa,ctrl}`）→ **全部失败**：
  系统进程持有其 `ep0`，`umount` 返回 EBUSY。**让不出去，也建不出来。**

**A/B 对照（决定性证据）**：同一份代码，仅把 `GadgetFeature.FFS` 移出挂载集合，
挂载立即完全成功（Windows 侧 18 个设备全部 Present，HID / CDC / UAC2 全 OK）。
→ **因果关系坐实：FFS 是唯一变量。**

**工程结论**：
- `GadgetFeature.FFS` **默认关闭**（`enabledByDefault = false`），代码保留，
  以便在 FFS 上下文充足的 ROM 上复用
- 原副屏 `usb://video` 计划改走 `f_ncm` 网卡 + TCP（PC 端免驱识别为网卡，带宽足够）。
  **⚠️ 这条计划随副屏终止一并作废**，且当时提到的 `TcpBulkTransport` **已从代码库中删除**——
  `docs/ARCHITECTURE.md` 与本文旧版对它的引用属历史记录，**不要照此查找该类**。
  当前真机在跑的无线通道是 `android/.../wireless/TcpControlChannel.kt` + `core/ApxFrame.kt`
  （APX1 组帧）与 PC 端 `pc/host/src/wireless/wireless_link.cpp`；
  `GadgetFeature.NCM` 保持默认关闭。
- **教训**：`ffs` function 一旦加入 gadget，失败会表现为「UDC EBUSY」这种与 FFS
  **毫无字面关联**的错误。**排查 gadget 挂载失败时必须同时看 dmesg**，
  否则会一直在 UDC 抢占上兜圈子。

---

## 4. 通用 shell 陷阱：`printf ''` 不会产生写入

```bash
printf '' > /config/usb_gadget/g1/UDC    # ❌ 无效
echo ''   > /config/usb_gadget/g1/UDC    # ✅ 正确
```

- `printf ''` 输出 **0 字节**，shell **根本不会调用 `write()`**
- configfs 是虚拟文件系统，**收不到数据就不会执行解绑动作**，且不报任何错
- 再叠加 `2>/dev/null`，问题会被彻底掩盖 —— 曾直接导致「绑 UDC 报 EBUSY」查不出原因
- 已修 3 处：`ConfigFsLayout.unmount` / `ConfigFsLayout.forceClean` / `UsbHalArbiter.acquire`
- **请自查 `scripts/apx_gadget.sh` 是否也有同样写法**

## 5. 当前挂载流程（已实现）

1. 取 root shell（`su -c sh`）
2. `setenforce 0`（挂载期间 permissive；Android 的 gadget 由 init 在 `u:r:init:s0` 管理，第三方进程访问 configfs 会被策略拦）
3. `setprop sys.usb.config none`
4. 等待 **1500ms**（HAL 释放是异步的，抢在前面会撞竞态）
5. `echo '' > /config/usb_gadget/g1/UDC`（逼 HAL 交出 UDC）
6. 在**全局 configfs `/config/usb_gadget`** 创建 gadget —— **不要自建私有挂载点**（原因见 §3.5，那会让残留无法清理）
7. 写 function 属性：`subclass` / `protocol` / `report_length` / `report_desc`（描述符只能来自 `shared/`）
8. 最后写入 `UDC` 绑定 —— **当前卡在这一步**
9. 打开 `/dev/hidg0`、`/dev/ttyGS0`

## 6. 未解决问题

**全流程已打通（2026-09-20 重启后首次完整验证）。**

- **挂载成功**：`HidDevice: opened /dev/hidg0`、`SerialDevice: opened /dev/ttyGS0`
- **Windows 枚举成功**（`VID_1D6B & PID_0104 \ APX00000001`）：
  - `USB Composite Device`（MI_00 HID + MI_01 CDC）
  - `HID 3D 加速度传感器`（COL01 = IMU TLC）
  - `HID 传感器集合 V2`（COL02 = 9 个低频传感器 TLC）
  - `符合 HID 标准的触摸屏`（COL03 = Digitizer）
  - `符合 HID 标准的用户控制设备`（COL04 = Consumer）
  - `符合 HID 标准的供应商定义设备`（COL05 = Vendor）
  - `HID-compliant device`（COL06 = Battery）
  - `USB 串行设备 (COM3)`（CDC ACM → GPS NMEA）
- **音频（UAC2）已打通**：`AudioModule` 同时打开 `pcmC?D0p` / `pcmC?D0c`，PC 放歌从手机扬声器
  出声、录音能录到手机麦克风。实现要点与踩坑见 **§3.6**。
- 传感器异常：8 个传感器 TLC 在 Windows 侧仍为 `problem=10 (CM_PROB_FAILED_START)`。
  已通过 `GADGET_HID_WRITE_GET_REPORT`（Linux 6.12+）为每个传感器 TLC 登记 Feature Report
  （登记本身 `10/10` 成功），驱动仍未起来，待继续排查。
  **验证前必须先清掉 Windows 上的旧设备实例**，否则 Windows 会按相同的
  VID/PID/序列号/描述符复用上次那个 Error 实例、不做重新初始化
  （判据：`Microsoft-Windows-Kernel-PnP/Configuration` 有无新的 400/411 事件）：

  ```powershell
  Get-PnpDevice | Where-Object { $_.InstanceId -like "*VID_1D6B*" } |
    ForEach-Object { pnputil /remove-device "$($_.InstanceId)" }
  ```

- （历史）副屏 `usb://video` → `/dev/usb-ffs/apx/ep1` 打不开（errno=2）：`GadgetFeature` 里
  **没有 FunctionFS 项**，属通道未落地，非环境问题。**副屏已终止，此条仅作追溯。**

## 7. 真机验证方法（每次都按此执行）

```bash
# 0) 前置：确认刚重启（名额充足）
adb shell "cat /proc/uptime"        # 秒数很小才说明刚重启
adb shell "getprop sys.usb.config"  # 应为 adb

# 1) 启动 App（用 LAUNCHER intent，am start -n 在 MIUI 上可能落到应用信息页）
adb shell "monkey -p com.allperiph -c android.intent.category.LAUNCHER 1"

# 2) 若出现 MIUI「Android 应用兼容性」弹窗，必须先点掉（否则后续点击全部落空）
adb shell "uiautomator dump /sdcard/ui.xml"

# 3) 点击主界面开关
#    resource-id: com.allperiph:id/btnUsbSwitch

# 4) 期望结果
adb logcat -d | grep -E "GadgetManager|UsbHalArbiter|ApxNative"
adb shell "ls -l /dev/hidg0 /dev/ttyGS0"
# Windows: 设备管理器出现 VID_1D6B 复合设备

# 5) 收尾（务必执行，否则手机 USB 功能残留异常）
#    通过 App 停止，或 adb shell "setprop sys.usb.config adb"
```

## 8. 给后续 lane 的硬性要求

1. **失败路径不要自动重试** —— 名额一次性，重试等于自杀
2. **不要自建私有 configfs 挂载点**，只用全局 `/config/usb_gadget`（§3.5）
3. **清理 configfs 只能逐级 `rmdir`，禁用 `rm -rf`**（§3.1）
4. **configfs 写属性一律用 `echo`，禁用 `printf ''`**（§4）
5. **不要再尝试复用系统 g1**（§3.4）
6. 判断 UDC 空闲**只看** `/sys/class/udc/*/state`；内核处于异常状态时（如刚被
   `sys.usb.config=none` 打断）**不要 `ls` configfs 目录或 `/sys/devices/virtual/android_usb/`**，
   会挂起并拖断 RootShell（§3.3）
7. **每次重装 App（`adb install -r`）都会强杀进程** —— 若当时正处于挂载状态，
   gadget 不会被卸载。必须保证"卸载可被下次启动的兜底清理完成"（这依赖第 2、3 条）。
8. **卸载时绝不删除 gadget 本体**，只解绑 UDC（§3.1 对策）。删了就再也建不回来，
   只能重启手机。
