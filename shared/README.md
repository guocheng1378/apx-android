# shared/ —— AllPeriph 公共协议库（L1）

C++17、零第三方依赖，Android NDK 与 PC（MSVC / GCC）两侧共用同一份实现。
真源：`docs/PROTOCOL.md`（线格式）、`docs/ARCHITECTURE.md`（结构约束）。

## 1. 目录与职责

| 文件 | 职责 | 协议落点 |
|---|---|---|
| `include/apx/sensors_id.h` | 传感器编号、掩码、模块位图（唯一真源，禁止魔法数字） | §2.3 / §2.4 / §2.7 |
| `include/apx/frame.h` | Bulk 通道统一帧头 + CRC32 + 显式小端 + 分片/组装 | §3 |
| `include/apx/units.h` | 单位换算、定标（i16/i32）、轴向对齐 | §2.2 |
| `include/apx/hid_layout.h` | 6 个 HID 报告 POD + 长度 + 打包/解析 | §2.3–§2.8 |
| `include/apx/hid_descriptor.h` | HID 报告描述符生成 + Usage 常量 | §2.1 / §4 |
| `include/apx/clock.h` | 单调时基 + 两端偏移/漂移估计 | §1 / §6.4 |

约定：所有结构体 `#pragma pack(1)` + `static_assert` 尺寸；多字节字段一律显式小端读写（不依赖主机字节序）；常量集中在 `sensors_id.h` / `hid_descriptor.h`。

## 2. 构建与自测

```bash
# 单独构建（PC 端验证；Windows 用 VS 生成器或直接 cmake -S shared -B shared/build）
cmake -S shared -B shared/build
cmake --build shared/build

# 自测程序（退出码 0 = 全通过）
./shared/build/apx_selftest                    # Windows: shared\build\Debug\apx_selftest.exe
# 或等价的 ctest
ctest --test-dir shared/build --output-on-failure
```

被别人以 `add_subdirectory` 引入时只产出静态库目标 `apx_shared`（默认不构建自测程序）：

```cmake
add_subdirectory(<root>/shared ${CMAKE_BINARY_DIR}/apx_shared)
target_link_libraries(your_target PRIVATE apx_shared)   # 自动带上 include/ 与 cxx_std_17
```

Android 侧由 `android/app/src/main/cpp/CMakeLists.txt` 引入，产出 `libapx.so`（target `apx`）。

## 3. 六个 HID 报告

| ID | TLC（Page / Usage） | 长度 | 内容 |
|---|---|---|---|
| 1 | 传感器 IMU `0x20 / 0x0073` | 113B | 16B 头（sensorId/sampleCount/flags/periodNs/baseTsNs）+ X/Y/Z 各 16 个 i16 样本 + accuracy |
| 2 | 传感器低频 `0x20 / 0x0001` | 24B | sensorId/state/event + tsNs(u64) + v0..v2(i32) |
| 3 | Digitizer `0x0D / 0x0004` | 102B | flags 位图 + contactCount + tsNs + 10×8B 触点 + 10B 笔附加 |
| 4 | Consumer `0x0C / 0x0001` | 3B | keyCode + state |
| 5 | Vendor `0xFF00 / 0x0001` | IN 24B / OUT 264B / FEATURE 24B | 状态（u64 掩码 + lastSeq）/ 命令 / Feature |
| 6 | Battery `0x85 / 0x0001` | 13B | chargePct + state + voltageMv + currentMa + tempCx10 + remainingMin |

- 首字节恒为 Report ID；单报告 ≤ 1024B（`kMaxReportSize`）。
- `maxReportLength()` = **264B**，即 ConfigFS `f_hid` 的 `report_length` 取值。
- IMU 报告**定长** 113B：不足 16 个样本时尾部填 0，有效样本数看 `sampleCount`。
- `reportSizeById(id)` 返回含 Report ID 的完整长度；未知 ID 返回 0。

## 4. 单位与指数（`units.h`）

Android 原始值 → HID 声明单位 → 定标整数，一张表决定，手机与 PC 共用：

| 传感器 | 换算 | HID UNIT | EXP | 示例 |
|---|---|---|---|---|
| 加速度 / 未校准加速度 | m/s² ÷ 9.80665 → g | G | -3 | 9.80665 → 1000 |
| 陀螺仪 / 未校准陀螺仪 | rad/s × 180/π → °/s | deg/s | -2 | 1 rad/s → 5730 |
| 磁力计 / 未校准磁力计 | µT × 0.01 → G | Gauss | -4 | 50 µT → 5000 |
| 光 | 原值 | lux | -2 | 1000 → 100000 |
| 接近 | 原值 | cm | 0 | 5 → 5 |
| 气压 | hPa × 100 → Pa | Pa | +2 | 1013 → 1013 |
| 方向 / 倾角 | 原值 | deg | -2 | 45 → 4500 |
| 设备/电池温度 | °C + 273.15 → K | K | -2 | 20°C → 29315 |
| 湿度 | 原值 | % | -2 | 55.5 → 5550 |
| 计步 / 心率 | 原值 | 无 | 0 | 1234 → 1234 |

- 定标：`raw = round(物理值 / 10^EXP)`，越界**饱和**而非回绕。
- 解码：`dequantize()` 是严格逆运算。
- 轴向：默认 `AxisRotation::kNone`（Android 坐标系 X 右 / Y 上 / Z 出屏）；设备安装方向不同时用 `alignAxes()` 旋转。

## 5. Bulk 帧（§3）

```
+--------+---------+--------+-------------+-------------+-------------+
| magic  |streamId | flags  |headerExt    | payloadLen  | seq         |  16B
| 'APX1' | (u8)    | (u8)   |Words (u16)  | (u32,含CRC) | (u32)       |
+--------+---------+--------+-------------+-------------+-------------+
| payload ...                                  | crc32(u32)          |
```

- `flags`：bit0=关键帧、bit1=末片、bit2=可丢弃。
- 分片：每个分片是一个**完整帧**（自带 CRC），同一 `seq`；接收端按到达顺序拼接，逐片校验 CRC，任一片出错即整帧丢弃（不处理乱序/重传，依赖顺序可靠通道）。
- 单分片可承载 `mtu - 16 - 4` 字节载荷。

## 6. 时钟（§1）

- 手机端时基：`SystemClock.elapsedRealtimeNanos()`（单调，含深睡）。
- PC 端时基：`QueryPerformanceCounter()` / `CLOCK_MONOTONIC`。
- `ClockSync::addSample(pcSend, phoneTs, pcRecv)`：`offset = (pcSend + rtt/2) - phoneTs`，`pc ≈ phone + offset`；保留最小 RTT 样本，并对 offset 做指数平滑；漂移由 offset 对手机时间的变化率估计（ppm）。

## 7. 已知限制（真机待确认）

1. **低频 Report 2 复用 10 种传感器**：单位随 `sensorId` 变化，描述符无法静态声明 UNIT，v0..v2 声明为厂商自定义（`0xFF00`），语义由 `units.h` 与 PC 侧按偏移解析。Windows 不会把它原生识别为光/接近/气压等具体传感器；若需原生枚举，须给每个低频传感器分配独立 Report ID。
2. **Consumer Report 4 用「键编号 + 状态」编码**（非 usage 位图），Windows 不能原生识别多媒体键；需 PC 宿主翻译或改为位图编码。
3. **Report 5 描述符声明为 Input + Output + Feature 三用途**：状态经中断 IN 端点上行（协议文本只写了 FEATURE）。
4. **Digitizer 压力**声明为厂商自定义高精度字段（坐标 X/Y 声明在 Generic Desktop 页）；标准 Tip Pressure usage 待真机确认后改回。
5. **Battery TLC** 仅电量百分比用标准百分比 usage，其余字段由 PC 侧按 §2.8 偏移解析。
6. **HID UNIT 常量**（G / deg/s / Gauss / Pascal / Kelvin / lux）取自 HID Sensor Usage Tables，量级需在 Windows 传感器面板上核对一次；偏差时只改 `units.h` 的 exponent，不动结构。

## 8. 自测覆盖（`tests/apx_selftest.cpp`）

结构体尺寸（编译期 `static_assert`）、CRC32 标准向量 `crc32("123456789") = 0xCBF43926`、帧头小端往返、载荷 CRC 校验、分片→组装往返与坏片丢弃、单位换算与往返/饱和、六个报告打包与解析往返、描述符按 Report ID 统计位数并与长度常量比对、时钟偏移与漂移。
