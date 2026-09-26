# M6 · App UI 与服务编排（`android/.../ui/`）

> 依赖：`docs/ARCHITECTURE.md` §1/§6、`docs/PROTOCOL.md`、`docs/ROADMAP.md`。

## 目录

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 主控制面：总开关、链路状态、模块开关、环境诊断、延迟预算、触控板页与快捷键 |
| `AgentForegroundService.kt` | 唯一声明的前台服务：编排启停、常驻通知、进程兜底 |
| `AgentController.kt` | 编排中枢：模块注册顺序、开关持久化、环境自检、链路速度注入 |
| `EnvChecks.kt` | root / USB 速度 / 电池白名单 / 通知权限自检 |
| `NotificationChannels.kt` | 通知渠道（状态 LOW、告警 DEFAULT） |
| `StatusRows.kt` | 主界面状态行的代码化构建（语义色圆点） |
| `HotkeyController.kt` / `HotkeyBoard.kt` / `KeyLayouts.kt` | 常用快捷键：发送、面板、布局 |
| `TouchCursorView.kt` | 触控板光标视图（手势事件入口） |
| `GamepadActivity.kt` / `GamepadController.kt` / `JoystickView.kt` | 手柄页（**仅 USB 出口**） |

> 副屏能力现由 `screen/` 包承担：`screen/ScreenActivity.kt` 在 `AndroidManifest.xml` 中声明，
> 由 `MainActivity` / `TouchpadActivity` / `AgentForegroundService` 拉起。
> 早先放在 `app/src/disabled/screen/` 的废止副本（从不参与编译）已删除。

## 唯一前台服务

**Manifest 中只声明 `ui.AgentForegroundService`**，编排逻辑全在本路的 `AgentController`。
（原 `core/AgentService`（L2）已按 main 裁决废弃并删除：UDC/Gadget 只能有一个所有者，
两个前台服务会互相抢占 USB 配置 —— 架构 §6.2。见 `reports/MAIN-INTERVENTIONS.md`。）

## 编排顺序

启动：`gadget → audio → touchpad → bthid → wireless`
停止：**逆序**（下游先释放设备节点，架构 §6.2）

`wireless` 排在最后启动、最先停止：它是「输入出口」，不依赖也不阻塞前面的设备类模块。

模块开关持久化在 `SharedPreferences("apx_ui")` 的 `enable.<moduleId>`；
**当前全部模块默认开启**（`AgentController.defaultEnabled()` 恒返回 `true`）。

## 线程约定

- `apx-ui-io`（单线程）：模块 `start`/`stop` 以及 root shell、sysfs 读取等阻塞操作。
  同步在主线程跑会卡死 >5s → ANR → 系统杀进程 → 前台服务反复 `onCreate`/`onDestroy`
  （真机已复现，见 `AgentController` 注释）；
- 主线程：只做视图刷新与事件订阅；
- 模块内部各有专属线程（参见各模块 README 与类头注释）；
- **出站帧一律走队列 + 专用 writer 线程**：手势帧的生产者是 UI 线程，直接 `socket.write`
  会抛 `NetworkOnMainThreadException` 被 catch 吞掉，表现为「链路在线、光标纹丝不动」
  （真机踩过，见 `docs/ROADMAP.md` §2.2.2）。

## 环境门禁与引导（架构 §6.1 / §6.2）

| 项 | 判定 | UI 表现 |
|---|---|---|
| Root | `RootShell.open()` 成功 | 红字告警：ConfigFS 无法挂载，全部功能不可用 |
| USB 速度 | `/sys/class/udc/*/current_speed` | 非 `super-speed` → 橙色告警并置 `ModuleState.DEGRADED` |
| 电池优化 | `PowerManager.isIgnoringBatteryOptimizations` | 引导跳转系统白名单申请页 |
| 通知权限 | `NotificationManager.areNotificationsEnabled()` + `POST_NOTIFICATIONS` | 提示通知不可见（不影响功能） |

> 降分辨率 / 降帧率的决策在 **PC 侧**；手机端只标记降级，不擅自改分辨率。

## 通知口径（`AgentForegroundService`）

通知正文为「上行通道：%s」，其判据**必须与真实择路一致**，优先级同
`TouchpadModule.dispatch`：

| 档 | 判据 |
|---|---|
| `USB <档位>` | `runtime.hid.isReady()` |
| `蓝牙 HID` | `BtHidDevice.isConnected` —— **不是** `state`；模块处于「已注册（待 PC 配对）」时 `state` 也是 RUNNING，只看 state 会把一条没人连的链路报成当前通道 |
| `Wi‑Fi 控制` | `TcpCtrlBridge.ready()` |
| `未连接` | 以上皆否 |

## UI 规范

- 全中文文案，Material 3 配色（`res/values/colors.xml`）与排版层级（`res/values/styles.xml`）；
- **不引入任何第三方依赖**（与 `app/build.gradle.kts` 的零依赖约定一致），使用 framework
  原生控件，卡片用 shape drawable 模拟 M3 Outlined Card；
- **文案与真实能力必须对齐**：不可用的功能不得写进状态页的「已实现」清单
  （两组清单见 `res/layout/page_status.xml`）。

## 已知限制

- 「链路延迟/丢包」的丢包率用「重同步次数 / 已收帧数」粗估，精确 RTT 需控制面心跳支持。
- 通知动作图标使用单色矢量（Android 5.0+ 要求），`ic_stat_peripheral.xml` 固定为白色剪影。
- `GamepadController` 有**两个出口**：USB HID（连 PC）与**网络帧**（连 TV/PC 目标时经 9511 发 0x07 手柄帧，
  见 `GamepadController.send()` 里的 `ControlTarget.isControlling()` 分支）。被控端再用 uinput 造虚拟手柄，
  因此摇杆也是内核级真实输入 —— 本条旧描述已不准确。
