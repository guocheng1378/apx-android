# M6 · App UI 与服务编排（`android/.../ui/`）

> 依赖：`docs/ARCHITECTURE.md` §1/§6、`docs/PROTOCOL.md`；被编排的模块属 L2（`core/ sensor/ gadget/ gps/ vibe/`）与 L3（`screen/`）。

## 目录

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 主控制面：总开关、链路状态、副屏卡片、模块开关、环境诊断、延迟预算 |
| `ScreenActivity.kt` | 副屏全屏：沉浸式 + 常亮 + 触控/笔采集 + 双指长按退出 |
| `AgentForegroundService.kt` | 唯一声明的前台服务：编排启停、常驻通知、进程兜底 |
| `AgentController.kt` | 编排中枢：模块注册顺序、开关持久化、环境自检、链路速度注入 |
| `EnvChecks.kt` | root / USB 速度 / 电池白名单 / 通知权限自检 |
| `NotificationChannels.kt` | 通知渠道（状态 LOW、告警 DEFAULT） |
| `StatusRows.kt` | 主界面状态行的代码化构建（语义色圆点） |

## 唯一前台服务

**Manifest 中只声明 `ui.AgentForegroundService`**，编排逻辑全在本路的 `AgentController`。
（原 `core/AgentService`（L2）已按 main 裁决废弃并删除：UDC/Gadget 只能有一个所有者，
两个前台服务会互相抢占 USB 配置 —— 架构 §6.2。见 `reports/MAIN-INTERVENTIONS.md`。）

## 编排顺序

启动：`gadget → sensor → gps → vibe → screen`（gadget 先提供 HID/ACM 出口）
停止：**逆序**（下游先释放设备节点，架构 §6.2）

模块开关持久化在 `SharedPreferences("apx_ui")` 的 `enable.<moduleId>`；
默认：gadget/sensor/vibe/screen 开，**GPS 关**（涉位置权限与耗电）。

## 线程约定

- 主线程：只做视图刷新与事件订阅；`ScreenActivity` 的触控回调**只填对象池字段**；
- `apx-ui-io`：root shell（su）、sysfs 读取等阻塞操作；
- 模块内部各自有专属线程（见 `screen/README.md`）。

## 环境门禁与引导（架构 §6.1 / §6.2）

| 项 | 判定 | UI 表现 |
|---|---|---|
| Root | `RootShell.open()` 成功 | 红字告警：ConfigFS 无法挂载，全部功能不可用 |
| USB 速度 | `/sys/class/udc/*/current_speed` | 非 `super-speed` → 橙色告警并置 `ModuleState.DEGRADED` |
| 电池优化 | `PowerManager.isIgnoringBatteryOptimizations` | 引导跳转系统白名单申请页 |
| 通知权限 | `NotificationManager.areNotificationsEnabled()` + `POST_NOTIFICATIONS` | 提示通知不可见（不影响功能） |

> 降分辨率 / 降帧率的决策在 **PC 侧（L4）**；手机端只标记降级，不擅自改分辨率。

## UI 规范

- 全中文文案，Material 3 配色（`res/values/colors.xml`）与排版层级（`res/values/styles.xml`）；
- **不引入任何第三方依赖**（与 `app/build.gradle.kts` 的零依赖约定一致），
  因此使用 framework 原生控件（`Toolbar` / `Switch` / `Button`），卡片用 shape drawable 模拟 M3 Outlined Card；
  如需使用 Material Components，需由 main 修改 `app/build.gradle.kts` 增加依赖（已记入 L3 回报的 `contractDeviations`）。

## 已知限制

- 主界面「链路延迟/丢包」的丢包率用「重同步次数 / 已收帧数」粗估，精确 RTT 需控制面（L1/L2）心跳支持。
- `ScreenActivity` 进入时若副屏模块未启动会自动拉起；若 Gadget 未挂载，通道打开失败会按退避重试（日志可见）。
- 通知动作图标使用单色矢量（Android 5.0+ 要求），`ic_stat_peripheral.xml` 已固定为白色剪影。
