# 五路并行开发汇报汇总

- 生成时间：2026-09-20T12:28:37+08:00
- 规范依据：`docs/REQ-五路回报.md`（REQ-REPORT-001）

## 状态矩阵

| laneId | 工作线 | agent | 状态 | 交付数 | 验证通过 | 阻塞 | 协议偏离(breaking) | 越权 |
|---|---|---|---|---|---|---|---|---|
| L1 | 协议与公共库 | core-proto | **UNKNOWN** | 0 | 0/0 | 0 | 0(0) | 0 |
| L2 | Android 设备桥与 Gadget | android-devices | **PARTIAL** | 37 | 4/7 | 0 | 6(0) | 0 |
| L3 | Android 副屏与 App UI | android-screen-ui | **IN_PROGRESS** | 0 | 0/1 | 0 | 0(0) | 1 |
| L4 | PC 副屏驱动与推流 | pc-display | **IN_PROGRESS** | 1 | 0/1 | 0 | 0(0) | 3 |
| L5 | PC 宿主服务与工具 | pc-host | **INVALID** | 0 | 0/0 | 0 | 0(0) | 0 |

## 缺口清单

- **[L1] REPORT_ERROR**：未找到回报文件
- **[L2] DEVIATION**：缺失 L2 六个运行时权限；且 <service> 指向 .ui.AgentForegroundService，与 core/AgentService 类名不一致
- **[L2] DEVIATION**：libapx.so 与 10 个 native 入口尚未交付，ApxNative.isAvailable=false，GadgetManager 会以「shared/ 未提供 HID 报告描述符」进入 ERROR（不挂半个设备）
- **[L2] DEVIATION**：状态上报（手机→PC）需经中断 IN 端点上行，描述符必须把 Report ID 5 声明为 Input + Feature 双用途；协议文本只写了 FEATURE
- **[L2] DEVIATION**：u64 掩码位域与采样率 payload 中「bit7=1 表示低频通道」为我方实现约定，协议未定义
- **[L2] DEVIATION**：Report 6（Battery System）与 Report 4（Consumer 按键）本轮未实现：电池需 packBattery，按键采集属 UI/输入侧
- **[L2] DEVIATION**：AgentService 需要 agent_notify_title / agent_notify_text / agent_channel_name 三个文案
- **[L2] UNVERIFIED**：declared 仅 6 项（FOREGROUND_SERVICE、FOREGROUND_SERVICE_CONNECTED_DEVICE、POST_NOTIFICATIONS、WAKE_LOCK、REQUEST_IGNORE_BATTERY_OPTIMIZATIONS、INTERNET）；missing: HIGH_SAMPLING_RATE_SENSORS、BODY_SENSORS、ACCESS_FINE_LOCATION、ACCESS_COARSE_LOCATION、VIBRATE、CAMERA —— 六项仍在 TODO 注释里。AndroidManifest.xml 归 L3，L2 不得越权修改，已记入 contractDeviations（neededFrom=L3）
- **[L2] UNVERIFIED**：未执行：本机无 java / gradle / git，也无 Android SDK（PowerShell 检测均报 CommandNotFoundException）。改为逐文件人工复核，本轮已修复 4 处编译期错误：① EventBus.onMain 把 lambda 传给 handler 形参（改为 on(T::class.java, null, block)）；② NmeaSource.requestLocationUpdates 传 Handler 但该重载收 Looper（改传 handler.looper）；③ NMEA SAM lambda 的带标签 return 改为 if 判断；④ AgentService 引用尚不存在的 R.string.agent_*（改为按名查找 + 兜底文案）
- **[L2] UNVERIFIED**：未执行：当前无已 root 的真机与 USB 3.0 线缆，且 L1 尚未交付 libapx.so（描述符与打包接口缺失）。完整 MS1 端到端步骤见 reports/L2.md
- **[L3] UNVERIFIED**：待执行（开工回报，尚未运行）
- **[L4] UNVERIFIED**：待执行（本条为开工占位，落地完成后补齐真实输出）
- **[L5] REPORT_ERROR**：verification 至少需 1 条记录

## 合规问题

- **[L3]** ownedDirs 越权：android/app/src/main/AndroidManifest.xml
- **[L4]** touchedFiles 超出归属目录：reports/L4.json
- **[L4]** touchedFiles 超出归属目录：reports/L4.md
- **[L4]** deliverables 越权写到 main 区域：reports/L4.json

## 里程碑判定

| 里程碑 | 内容 | 结论 | 依据 |
|---|---|---|---|
| MS1 | 全链路 Hello World（加速度计 → HID → 传感器面板） | 未达成 | 依赖 L1 协议与 L2 传感器/Gadget；需 L1 已验证通过且 L2 有交付物 |
| MS2 | 传感器全家桶 + 电池 + 按键 | 未达成 | 依赖 L2 全部完成且验证全通过 |
| MS3 | GPS over CDC ACM → Location API / gpsd | 未达成 | 依赖 L2 的 GPS 模块（PC 端 OS 原生识别，无额外依赖） |
| MS4 | 副屏闭环（虚拟显示器 + 推流 + 触控上行） | 未达成 | 依赖 L3 渲染/触控与 L4 驱动/推流两端同时可用 |
| MS5 | 相机 / 音频（优先复用 Android 14+ DeviceAsWebcam） | 未达成 | 依赖厂商内核是否启用 UVC / UAC2 |
| MS6 | 产品化（控制面板、自启、自愈、SDK） | 未达成 | 依赖五路全部完成 |

## 下一步建议

- 要求 L5 按 §5 schema 重新生成回报（禁止用自由文本替代）
- 向 L1 点名索要回报；超时仍无响应则标注「需人工介入」
- 补齐未验证项后才能提升里程碑判定：L2, L3, L4

