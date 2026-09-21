# gadget/ — M2 ConfigFS 复合设备管理器（架构 §2 路线 A）

## 文件职责

| 文件 | 职责 |
|---|---|
| `GadgetManager.kt` | 生命周期主流程：探测 → 抢占 → 挂载 → 开节点 → 看门狗/自愈 → 卸载；同时实现 `HidTransport` 注入 `AgentRuntime` |
| `UsbHalArbiter.kt` | UDC 抢占与归还（架构 §6.2）：保存并恢复 `sys.usb.config` |
| `UdcProbe.kt` | 枚举 `/sys/class/udc`、解析 `current_speed`、选最优控制器 |
| `ConfigFsLayout.kt` | 纯函数生成挂载/卸载/清场命令序列（与 `scripts/apx_gadget.sh` 同序，可单测） |
| `RootShell.kt` | root 操作唯一出口：常驻 su 进程 + 退出码标记，逐条取返回值 |
| `HidDevice.kt` | `/dev/hidg0` 非阻塞读写（IN 写 + OUT 轮询读） |
| `SerialDevice.kt` | `/dev/ttyGS0`（CDC ACM）非阻塞写入 |

## 挂载顺序（内核要求）

`gadget → strings → configs → functions → 软链 → 写 UDC`。
**写 UDC 是最后一步**：此前任何失败都不会破坏手机原有 USB 功能。

## 三条硬约束的落点

1. **速度门禁（§6.1）**：挂载前读 `/sys/class/udc/<name>/current_speed`；非 `super-speed`/`super-speed-plus` 时广播 `LinkSpeedDegradedEvent`，模块进入 `ModuleState.DEGRADED`，并按实测速度二选一声明 `bcdUSB`（0x0300 / 0x0200）。
2. **UDC 抢占（§6.2）**：写 UDC 前 `setprop sys.usb.config none` 并轮询等待 HAL 真正解绑；异常路径（挂载失败 / `stop()` / `onDestroy` / JVM shutdown hook）一律恢复原配置，原值为空时退回 `adb` 保证调试通道不丢。
3. **心跳自愈（协议 §4）**：看门狗每 1s 上报 §2.7 状态；`everConnected` 后连续 3 次（3s）收不到心跳即 `heal()`：关节点 → 清场 → 重挂 → 重新 attach 传输口。

## Function 组合

默认启用 `f_hid`（复合 HID，6 个 TLC）+ `f_acm`（GPS NMEA → COM 口）。
`f_uac2`（手机麦克风 + 扬声器）默认尝试挂载；`f_uvc` / `f_ncm` 仍在预留。

三者均标记为 `optional`：厂商内核常缺少对应 gadget function（实测 Xiaomi HyperOS 就**没有** `usb_f_uac2`），
此时会自动跳过并降级，不会导致整个复合设备挂载失败。**只有 HID 与 ACM 是必需项**。

> 注意：`enabledByDefault`（是否尝试挂载）与 `optional`（失败可否容忍）是**两个独立维度**，
> 不要把后者写成 `!enabledByDefault` —— UAC2 需要「尝试挂载但允许失败」这一组合。


## 已知限制

- **Report ID 5 需声明为 Input + Feature 双用途**，状态才能经中断 IN 端点上行；否则只能走 `Get_Report`（Windows 侧不会主动轮询）。
- `/dev/hidg0` 必须 `O_NONBLOCK`：f_hid 同时只允许一个 IN 请求在途，主机未取走时再写会阻塞；传感器是可丢数据面，宁可丢帧。
- SELinux enforcing 下 `chmod 666` 可能失败，属可选步骤；App 自身以 root 打开节点不受影响。
- SIGKILL（LMK 强杀）不会执行 shutdown hook，此时用 `scripts/apx_gadget.sh down` 人工恢复。

## 验证

```bash
adb push scripts/apx_gadget.sh /data/local/tmp/
adb shell su -c 'sh /data/local/tmp/apx_gadget.sh status'      # 看 UDC / speed / 节点
adb shell su -c 'sh /data/local/tmp/apx_gadget.sh speed'        # 速度门禁
adb shell su -c 'sh /data/local/tmp/apx_gadget.sh up /data/local/tmp/apx/hid_report_desc.bin'
adb shell su -c 'sh /data/local/tmp/apx_gadget.sh down'         # 必做：恢复手机 USB
adb logcat -s GadgetManager UdcProbe UsbHalArbiter RootShell
```
