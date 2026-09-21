# core/ — 协议绑定、时钟、日志、事件总线、运行时契约

L2（android-devices）归属。只依赖 Android framework 与 `shared/`（经 `ApxNative`），不引入任何第三方库。

> **服务编排不在本目录**：唯一前台服务为 `ui.AgentForegroundService`（编排逻辑在 `ui.AgentController`），
> 原 `core/AgentService.kt` 已按 main 裁决废弃（见 `reports/MAIN-INTERVENTIONS.md` 2026-09-20 · 裁决：
> UDC/Gadget 只能有一个所有者，重复的前台服务会互相抢占 USB 配置）。

## 文件职责

| 文件 | 职责 |
|---|---|
| `Module.kt` | 模块生命周期契约 `Module`/`ModuleState`/`ModuleContext`，以及按注册顺序启动、逆序停止的 `ModuleRegistry` |
| `AgentRuntime.kt` | `ModuleContext` 实现：持有注册表 + 可热替换传输口（`attachHid`/`attachSerial`），链路自愈时业务模块引用无需重建 |
| `EventBus.kt` | 极简同步事件总线（发布者线程回调，可传 Handler 切主线程）；**数据面不走总线** |
| `Events.kt` | 状态/控制事件定义（Gadget/Sensor/Gps/Vibe/Agent 状态、Vendor 命令、降级告警） |
| `Log.kt` | 统一日志：logcat + 512 条环形缓冲（供 UI）+ 事件广播 |
| `Transport.kt` | `HidTransport` / `SerialSink` 抽象与 `Null`/`Delegating` 实现（架构 §2 路线 B 只保留该接口） |
| `ApxIds.kt` | PROTOCOL 中**所有**枚举与魔数：ReportId / SensorId / VendorCmd / LinkSpeed / ModuleMask / 心跳常量 |
| `AppPaths.kt` | 所有 sysfs / ConfigFS / 设备节点 / USB 标识常量，其它模块禁止内联字面量路径 |
| `ClockSync.kt` | 时基与偏移估计：`elapsedRealtimeNanos()` + 最小 RTT + 指数平滑（协议 §1） |
| `VendorCommand.kt` | §2.7 Vendor OUT 命令的 Kotlin 侧表示与解码 |
| `ApxNative.kt` | `shared/` 的唯一 Kotlin 入口（JNI），native 缺失时整体降级为 `isAvailable=false` |

## 关键约束与落点

- **时间戳**：统一 `SystemClock.elapsedRealtimeNanos()`（`ClockSync.nowNs()`），与 PC QPC 通过最小 RTT 估计偏移；链路中断后 `markResyncNeeded()` → §2.3 flags bit0。
- **二进制布局**：报告打包/描述符生成**全部**在 `shared/` 完成，Kotlin 只传物理量原始值与元数据，禁止手写字节序（ARCHITECTURE §4）。
- **JNI 符号契约**：见 `ApxNative.kt` 文件头注释（动态库 `libapx.so`，10 个符号）。
- **降级不崩溃**：root 缺失、描述符缺失、设备节点打不开，一律 `ModuleState.ERROR` + 事件广播，不抛异常。

## 已知限制 / 待补

1. Report 4（Consumer 按键）与 Report 6（Battery）尚未实现：按键采集属 UI/输入侧（L3），电池需 L1 提供 `packBattery` JNI。

## 验证

```bash
adb shell dumpsys activity services com.allperiph/.ui.AgentForegroundService
adb logcat -s AgentForegroundService AgentController ModuleRegistry ApxNative
```
