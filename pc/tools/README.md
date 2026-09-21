# pc/tools —— 验证工具

这三个工具是**里程碑验收的直接判据**，不是辅助脚本。每个工具对应一个里程碑，
退出码即结论。

## 三件套

| 工具 | 验收目标 | 判据 |
|---|---|---|
| **`apx_sensor_dump`** | **MS1**：手机传感器已作为 PC 本机传感器工作 | `--expect-accel` 退出码 0 |
| `apx_gps_probe` | MS3：GPS 的 NMEA 从 CDC ACM 的 COM 口读得到 | 解析出有效经纬度 |
| `apx_link_bench` | USB 3.0 是否真跑在 SuperSpeed | 实测吞吐 ≥ 200 MB/s |

## apx_sensor_dump（MS1 验收）

**为什么这个工具重要**：本项目的核心主张是「手机元器件变成 PC 的本机设备，
且 **PC 端零自研驱动**」。

这个主张成立的**唯一证据**就是：我们能通过**操作系统自己的接口**读到手机传感器。
如果还得自己解析 HID 报告，说明免驱集成没成功，主张就不成立。

所以 `sensor_dump` **只调用 OS 的传感器 API**：

- **Windows**：`ISensorManager`（经典 Sensor API，COM）或 `Windows.Devices.Sensors`（C++/WinRT）
- **Linux**：`/sys/bus/iio/devices/iio:deviceX`（`hid-sensors` 驱动映射）

### 用法

```bash
# 列出所有系统集成传感器并读一次
apx_sensor_dump

# 持续观察（默认 500ms 间隔）
apx_sensor_dump --watch --interval 200

# MS1 判定模式：只关心加速度计，退出码即结论
apx_sensor_dump --expect-accel
```

### 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功（`--expect-accel` 时表示加速度计可用**且能读出有效值**） |
| 1 | 参数错误 |
| 2 | 未发现任何传感器 → Gadget 没挂上，或系统驱动没接管 |
| 3 | `--expect-accel` 模式下加速度计不可用或读不出值 |

### MS1 排查路径（退出码非 0 时）

```
1) 手机端 App 是否点了"切换到外设模式"？
       ↓ 否 → 先挂载（注意：该内核 gadget「名额」一次性，失败需重启手机，
              见 docs/REALDEVICE-NOTES.md）
2) Windows 设备管理器是否出现 VID_1D6B 复合设备？
       ↓ 否 → 看手机端 logcat 的 GadgetManager 日志
3) 是否有 "HID 传感器集合 V2"？
       ↓ 否 → HID 描述符的 Sensor TLC 有问题，检查 shared/ 的 buildTlcImu
4) "HID 3D 加速度传感器" 是否存在但状态为 Error（Code 10）？
       ↓ 是 → **描述符缺 Feature Report**。Windows 的 SensorsHIDClassDriver
              需要每个传感器 TLC 自带 Report State / Change Sensitivity /
              Report Interval，缺了就无法完成驱动初始化。
              已实测复现，见 docs/REALDEVICE-NOTES.md
```

### 已知的真机事实（2026-09-20 实测）

在 Xiaomi HyperOS 上，复合设备**已经能被完整枚举**：

```
OK     Sensor   HID 传感器集合 V2                   ← 传感器集合识别成功
OK     HIDClass 符合 HID 标准的触摸屏                 ← Digitizer
OK     HIDClass 符合 HID 标准的用户控制设备             ← Consumer
OK     HIDClass 符合 HID 标准的供应商定义设备            ← Vendor
Error  Sensor   HID 3D 加速度传感器                  ← 当时唯一未通过的
OK     Ports    USB 串行设备 (COM3)                  ← CDC ACM
```

`Error` 的原因已定位并派修（Feature Report）。**修好后这个工具应该给出 PASS**。

## 当前状态

| 工具 | 状态 |
|---|---|
| `apx_sensor_dump` | ✅ 已交付（`main.cpp` + CMake） |
| `apx_gps_probe` | ❌ **未交付**（CMake 里已做「存在才构建」的保护） |
| `apx_link_bench` | ❌ **未交付**（同上） |

## 构建

```bash
cmake -S pc/tools -B pc/tools/build -G "Visual Studio 17 2022" -A x64
cmake --build pc/tools/build --config Release
pc\tools\build\host\Release\apx_sensor_dump.exe --expect-accel
```

> 注意：本 CMake 通过 `add_subdirectory(../host)` 复用 `pc/host` 的库，
> 不重复编译底层实现。若 `pc/host` 缺少某些 `.cpp`（见其 README 的当前状态），
> 需先补齐才能构建。
