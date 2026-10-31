# 全能外设 更新日志

> 详细用法见 [`docs/使用说明.md`](./使用说明.md)；发版流程见 [`docs/RELEASE.md`](./RELEASE.md)。
> **版本线**：v0.4.0 起变更明细随 **GitHub Releases** 随 tag 自动发布（tag 为真源）；
> 本文件维护 Unreleased 与重要节点（v0.3.x 历史见下）。

## v223.16（2026-11-01）

本轮全部来自 issue #52 最新一轮实测反馈（2026-11-01 凌晨那批）。

### 1. 日志导出三端统一（第 3 条）

- **文件名带设备标识**：以前是 `log_1793466844855.txt`，pad + 手机 + 电脑三台的日志
  摆一起根本分不清是谁的。现在三端统一
  `apx-logs-<类型>@<设备标识>-<yyyyMMdd-HHmmss>.<ext>`；
  PC 端 `exportRange` 增 `note` 参数（用户可自定义标识，留空回落本机主机名），
  同名不再覆盖（自动 `-2` / `-3` 后缀）。
- **选目录弹窗**：新增 `log.pickDir`。Windows 走 `IFileOpenDialog`（`FOS_PICKFOLDERS`）
  真弹窗；**macOS / Linux 如实回 `canPickDir=false`**（面板主线程被 accept 循环占着，
  AppKit 模态框必须在主线程），前端据此把按钮换成「常用目录」下拉。不假装能弹。
- **Android / TV 共用**新增的 `shared/util/LogExport.kt`：手机写 `下载/AllPeriph/`，
  电视写 `<外部文件>/APX/`（「收到的文件」目录 —— 电视没文件管理器，只有那里
  能把文件发回手机/电脑）。
- **TV 之前完全没有日志导出入口**：主页新增「导出日志」，内容取应用环形缓冲
  （不读系统 logcat，电视多数没 root）。
- **修一个平台级缺口**：`web_panel.cpp`（Windows WebView2 壳）此前完全没有
  `log.list` / `log.export` —— Windows 是主发布平台，日志导出整块不可用，现补齐。

### 2. 界面（第 2 / 4 / 10 条）

- **按钮内文字没居中的根因**：framework `Button` 默认 `gravity=left|center_vertical`。
  `APButtonGhost` 补 `android:gravity=center` + `minHeight 48dp`；
  `CustomKeyboardDialog` 里代码 new 出来的按钮同样补 `gravity`。
- **自定义键盘编辑弹窗**是最后一个没用限高滚动的弹窗（横屏把标题和底部按钮顶出屏幕、
  编辑不了），套上 `DialogKit.scrollable`，与快捷键管理/编辑/新建同口径。

### 3. 无障碍转发（第 6 条，只加了可诊断性，没改转发逻辑）

- `accessibility_service_config` 补 `canRequestFilterKeyEvents` ——
  光在 accessibilityFlags 里写 `flagRequestFilterKeyEvents` 只声明「能过滤」，
  系统不会把按键路由过来；缺这个声明，真机表现正是「无障碍开了、开关也开了，
  退到后台却收不到按键」。
- `GlobalKeyForwarder` 在「无障碍收到按键但转发开关没开」时打一条日志，
  下次用日志就能区分「通道没通」和「开关没开」。
  **本轮真机日志显示按键到得了窗口、转发器没在路上，具体哪一环未定，不猜、不改。**

### 本版未做（下一批）

- mac Dock 图标 / 授权对象错位（第 1 条）：根因未定，且与现有设计方向相反，
  等用户确认后再动。
- 手柄 / 蓝牙设备类型主动上报（第 5 条）、手柄按钮自定义（第 6 条）
- 悬浮球 UI 换圆形图标（第 7 条）
- 无线快速输入误触系统键与 Shift 标点（第 11 条）
- 重复入口合并精简（第 12 条）、遥控器概率失效（第 13 条）

## v223.15（2026-11-01）

本版把 v223.14 之后 master 上的四批改动一起发出 —— 实测反馈里“改了没发 release”
那几项（Miuix 界面、issue#52 第三轮修复、模式状态同步）现在都在这个 tag 里。

### 1. 模式状态同步（PR #67，issue #52 第 8 条）

用户定的优先级：先修「各端模式状态显示不一致 + 模式识别循环」，再谈各模式功能。

- **关「无线」传输开关时没断开 9511 控制目标** —— 这是「模式循环」的真正来源。
  三个切换入口只写了 `transport.wifi=false`、不断开客户端，
  而 `Uplink.resolve` 判「无线可用」的依据正是客户端还活着 —— 于是
  切到 USB 后输入继续走无线、PC 面板继续显示「无线在用」。
  现收在 `AgentController.setTransportEnabled`（三处唯一公共写点）：关 wifi 即断开。
- **`Uplink.observed` 一旦为真永不回退**：切了模式但还没碰屏幕时，
  手机徽章、通知栏、上报给 PC 面板的「当前在用」全是过期值。
  新增 `Uplink.reset()`，切传输类、换 / 断开受控目标时调用。
- **PC 面板按累计计数判「有没有上报」**：`ctrl9511` 的 modeStatus 是历史累计、
  跨会话不清 —— 断链后仍把上一会话位图当当前状态显示。
  新增 `Ctrl9511ServerStatus::modeReported`（本次会话是否收过 0x31），握手与断链都清零。
- 切换后不再等那 2s：模式上报线程比对 `Uplink.generation`，一变即刻补发一帧。
- 顺带：**悬浮球开关与画面不一致**（第 7 条）按「意愿 + 实际」四态给文案；
  **mac 浏览器面板的「隐藏」按钮**（第 9 条）按通道隐掉（HTTP 无宿主窗口，
  后端只能是 no-op）。

### 2. 界面层迁移到 Compose + Miuix（PR #64，阶段 1–5）

状态页、触控板页、设置页核心分组、电视端主屏都换成 Compose + Miuix 嬉皮外壳；
新增 `ui/miuix/`（设计系统骨架：强调色、组件、边距、动效）与 `ui/compose/`（四张页）。

### 3. issue #52 第三轮反馈（PR #65）

- **USB 提交设备类型 / 手柄不可用**：USB 描述符把 Gamepad 顶层 collection 提到首位
  —— macOS 用描述符里第一个 TLC 定主用途，排第四时 GameController 不认手柄。
- **无线音频下拉「本机不支持枚举设备」**：按平台分叉，macOS / Linux 换成说明态
  （那个列表是宿主的播放端点，无线音频是应用内 PCM 直连、不注册成系统设备）。
- **无线模式长按只出 2 个字符**：连发节奏短于自动释放窗口，同一帧被反复重发。
  改为按下 hold、连发前先释放，保证每轮都是真实的按下 → 抬起。

### 4. TV 主屏编译修复（PR #66）

焦点描边色 `Color(if (…) accent else Color.Transparent)` 两分支公共类型退化成 Any 导致编不过，已修。

### 本版未做（下一批）

- mac Dock 图标（第 1 条）：现有设计是「只在菜单栏 + 浏览器面板」，与反馈「未在 Dock 显示」
  方向相反，已在 issue 里请用户确认后再动。
- 日志导出多端同步与自定义命名 / 路径选择弹窗（第 3 条）
- 横竖屏排版、按键变形拉伸（第 4 条）与按屏幕比例切方案
- 手柄 / 蓝牙设备类型主动上报（第 5 条）、手柄按钮自定义（第 6 条）
- 悬浮球 UI 按提供的圆形图标改（第 7 条，本版只修了开关与画面不一致）
- 按钮文字居中（第 10 条）、无线快速输入误触系统键（第 11 条）
- 重复入口合并精简（第 12 条）、遥控器概率失效（第 13 条）

## v223.14（2026-10-31）

本版补上 **issue #52 建议 6**（「app 设为输入法/无障碍 → 采集输入映射到电脑，
不必把 app 挂前台」）—— v223.13 时该建议标为待定，现两条腿都已落地（PR #54、#56），
另含 #56 的真机修正（PR #57）。

### 建议 6 · 不把 app 挂前台也能用键鼠

该能力在 Android 上分两条腿，各自独立开关、独立门槛：

- **键盘腿（无需 root，默认关）**——PR #54 已合入
  - 走**无障碍 `onKeyEvent`** 全局转发：新增 `ui/GlobalKeyForwarder.kt`（不持有
    Activity，进程起点恢复开关，SharedPreferences 持久化）与 `ui/HidKeyMap.kt`
    （keyCode → HID usage 的**单一真源**，`PhysKeyBridge` 改调它，两边不再各写一份码表）。
  - `ApxAccessibilityService` 加 `onKeyEvent` 与静态钩子（:shared 不能反依赖 :app，
    故用钩子接）：界面不在前台时按键照样转发。
  - 需要系统「无障碍」里启用本应用；未启用时设置页**直接带到无障碍设置页**，
    不给假「已开启」。
  - **隐私边界**：只转发物理按键本身 —— **不读取输入内容、不读剪贴板、不落盘**。
- **触控板腿（需 root，默认关）**——PR #56 合入，#57 修正
  - 触控板位移没有对应的无障碍事件：外接指针的原始位移被系统当**光标**消费，
    App 不在前台时窗口根本收不到 `MotionEvent`。故直接读硬件：
    `EvdevPointerReader` 从 `/proc/bus/input/devices` 选指针设备，用
    **`su -c cat <event 节点>`** 读裸 `input_event`（拿到 root 授权 ≠ 有权直接
    `open` 该字符设备），只解析 `EV_REL`（X/Y/WHEEL/HWHEEL）与
    `BTN_LEFT/RIGHT/MIDDLE`，在 `EV_SYN` 处成帧。
  - `RootPointerBridge` 把位移喂 `TouchpadModule.feedPointer`，与屏上触摸、
    窗口 hover **同一出口**；按钮位图持久保留（否则「按住拖动」会在第二帧断）；
    接管时令窗口 hover 通路让位，避免位移双计。
  - 拿不到 root 或没接指针设备时**如实提示**，仍走原窗口通路，不显示假的「已开启」。

### 修正（PR #57）· 真机内核无 mousedev 时指针设备发现必空

真机复验（小米 15 / Android 15）发现 #56 的设备发现逻辑在该内核上**静默失效**：

- `cat /proc/bus/input/handlers` 只注册了 `leds / evdev / input-boost / xiaomi-fp /
  xiaomi-touch / kgsl` —— **没有 mousedev**；`/dev/input/` 下只有 `eventN`、
  没有 `mouseN` 节点，全机设备 `mouseN` 出现 **0 次**。
- 原实现只认 Handlers 里的 `mouseN`，于是恒返回空、开关开了也不接管
  （与问题 5「状态显示与实际不符」同类）。

修法：判据改为「`mouseN` **或** 能力位图（`EV` 含 `EV_REL` 且 `KEY` 含 `BTN_LEFT`）」
取或，并改用 `eventN` 定位节点。触摸屏是 `EV_ABS`、键盘无 `EV_REL`、手柄无 `REL`，
据此都不入选；自家虚拟设备按名字继续排除以防自激。

### 真机验证（本机内核）

- **位图分组顺序**：用真机 `gpio-keys` 的 `KEY=8000000000000 0` 核对 —— 位 115
  （`KEY_VOLUMEUP`，真机音量键）命中，确认首组为高位组、`gi = size-1-(b/64)`，
  与内核 `input_print_bitmap` 的逆序输出一致。
- **解析层端到端**：用 `/dev/uinput` 造真指针设备（该内核 legacy write 返 `EINVAL`，
  须走 `UI_DEV_SETUP`），发 `EV_REL(7,-3)` / `BTN_LEFT` 按下再松开 / `WHEEL +1`，
  用与 Kotlin 端**逐行等价**的解析器读 `eventN`：得 `[(7,-3)]` / `[1,0]` / `[1]`,
  24 字节/帧、`type@16`/`code@18`/`value@20`、`EV_SYN` 成帧、按钮位图全部正确。
  该设备 Handlers 无 `mouseN`：**新判据认出、旧判据漏掉**。
- **实际读取路径**：走 App 真正用的 `su -c cat <eventN>`（subprocess stdout 读裸二进制）
  复验，位移 `[(12,-5)]` / 按钮 `[1,0]` / 滚轮 `[-2]` 全部正确，stderr 未污染 stdout。
- `com.allperiph` 已在本机 KernelSU 授权列表内（`/data/adb/ksu/.allowlist`），
  应用侧确实能拿到 root。

### 验证状态

- CI：Android 编译 + lint、PC 三平台、协议校验、mac 构建。
- 触控板腿已验证到**解析层与 `su -c cat` 读取层**（真机合成设备）；
  **接真鼠标/触控板的实操端到端仍待真机回验**。
- v223.13 遗留的待回验项（建议 3 莫奈取色、问题 3/5/6）不变。

---

## v223.13（2026-10-31）

本版是 **issue #52**（v223.12 真机实测）的集中落地：**6 个问题全部修复**，
7 条功能建议中 **6 条已实现**（建议 6 待定，见文末）。PR #53，12 个提交。

### 问题修复

- **问题 1 · macOS 授权改名与自救出口**（macOS 26/27 把「辅助功能」改叫
  「设备控制与数据访问」）
  - 权限面板显示名改由后端按系统版本下发，前端不再写死旧名
  - 授权横幅改三个按钮：**打开系统设置** / **重新检测** / **重启应用**，说明如实化：
    TCC 拒绝过一次就不再弹窗，`input.recheck` 只在「未决定」时能拉起系统弹窗；
    改回决定的唯一入口是系统设置，且**授权后必须重启应用**才生效（系统只在进程启动时
    读一次授权状态）。这解释了报障者观察到的「app 壳里点了没用、终端启动却会消失」
  - 新增 `input.openSettings`（深链 + AX prompt 兜底）与 `app.relaunch` 两个面板 act
  - 注入进程身份随状态下发（`.app` 双击启动 = AllPeriph；终端启动 = 终端 App），
    用户能对上系统设置里该勾哪一项
- **问题 2 · 横屏页面/弹窗显示不全**
  - **弹窗**：快捷键管理、快捷键编辑、设置页模板新建、主页模板新建四处长内容对话框
    套**限高滚动**（新增 `ui/kit/DialogScroll.kt`）。此前是 `setView(自绘 LinearLayout)`，
    平台不加滚动 —— 竖屏装得下，横屏可视高度只剩一半多，标题与底部按钮被顶出屏幕。
    用带硬上限的 ScrollView 子类在 `onMeasure` 里把上限压到屏高 62%（下限 140dp），
    内容没超就贴合、超了就滚，按钮始终可见
  - **页面**：横屏底部留白此前被 `layout-land` 覆盖为 16dp，而悬浮底栏占 96dp ——
    设置页右栏分类列表因此被底栏压住、末项重叠。`layout-land` 已删，横竖屏统一 96dp
    （详见建议 4）
- **问题 3 · 无线模式按键映射错 / 无反应**
  - `input_injector_mac` 方向键左右写反修正（0x4F/0x50 的 kVK 互换）
  - 补齐标点、F1–F12、编辑键、小键盘、CapsLock 码表 —— 此前这些全部落 `default`
    返回 -1 被**静默丢弃**（「标点键无反应」的根因）；未映射键现在会打日志，不再无声
  - `HidKeys` 补反斜杠与反引号的 usage
- **问题 4 · 双指滚动被误判为双指右键**
  - `GestureEngine` 加**位移守卫**：双指质心累计位移 ≥ 阈值即锁存「已进入滚动」，
    550ms 长按定时器判定时不再注入右键；缩放跨阈后重建基线；滚动亚像素累加（慢滚不丢帧）
- **问题 5 · USB 模式状态显示与实际不符**
  - 触控板显示「已停止」实则可用：`StatusFragment` 双组模块（触控板 = wifi+usb）
    此前只把第一组放进刷新表，USB 卡那行是构建时快照、永不更新 —— 改为该模块
    **所有分组**的行一起刷新
  - gadget 显示「降级运行」实则正常：`GadgetManager` 不再因**绑定 UDC 之前**的链路速度
    快照判 DEGRADED（该读数常取到 adb/MTP 的旧速度）；速度差异降为文案说明
    （「USB 2.0（输入全功能，仅音频带宽受限）」）
- **问题 6 · 全屏操控面实体键盘/触控板不可用**
  - `TouchpadActivity`（全屏操控面是独立 Activity）接入 `PhysKeyBridge` ——
    此前全屏页从没接过这条链路，主页触控板页的桥管不到独立 Activity，实体键盘按键、
    实体触控板 hover/指针事件全落进系统默认路径，用户看到的就是「实体键盘无法使用、
    实体触控板单指失效」

### 功能建议落地

- **建议 1 · 被控端居中蓝点**：根因是首次取到屏幕尺寸时把光标预置到屏幕正中，随后
  `setConnected(true)` 无条件画点 —— 连接成功的一瞬屏幕上就多一颗静止蓝点。改为落点
  右上角内缩一格 + 新增「对端真的下发过移动帧」才画点的标志；静止的居中蓝点消失，
  一动鼠标点照旧跟手。浮层本体（大小/颜色/显隐开关 0x32）不变
- **建议 2 · 悬浮球手势与功能菜单**：单击球 = 展开/收起功能菜单（连接模式
  无线/蓝牙/USB 三颗 + 触控板/扫描切换设备/文件互传/退出 app），菜单与触控板面板互斥；
  拖动抬手后贴近左右边缘 < 48dp 自动**吸附侧边停靠**并降到 0.42 透明度，停靠态单击恢复；
  菜单是独立 overlay 窗口（球在哪侧就贴另一侧，纵向夹回屏内）。退出顺序：
  `ControlTarget.clear → TvDiscovery.stop → AgentController.stopAll → 停前台服务 → 停悬浮球`
- **建议 3 · 莫奈取色**：强调色原本是 `@color/md_*` 编译期常量，必须加一层主题属性间接
  才能跟随系统。新增 `apPrimary/apPrimaryPressed/apPrimaryContainer/apOnPrimary` 四个属性
  + `APMonetOverlay`（映射 `@android:color/system_accent1_*`，色阶照 androidx material3
  values-v31 核过）；**只叠加不替换**（surface/outline/error 仍走 md_* 令牌）；对话框单开
  `.Monet` 变体，34 处 `AlertDialog.Builder` 统一走 `ThemeSkin.dialogStyle()`；设置页新增
  「取色方案」（品牌蓝/跟随系统取色，默认品牌蓝 —— 默认行为与改动前逐像素一致）。
  XML 11 处 + Kotlin 26 处取色点改走属性读取
- **建议 4 · 横屏保持竖屏顶栏/底栏**：`headerBar`/`tabBar` 两种朝向恒可见；右上角 pill
  按钮组整条下线（此前为躲它做的三处让位写回原值）；横屏不再盖掉状态栏（顶栏回来了，
  否则标题压到时间/电量上）；删除 `res/layout-land/activity_main.xml`（否则冷启动即横屏
  时会加载沉浸版布局，与旋屏走同一棵竖屏树的结果不一致），触控板页左右分栏继续由
  `TouchpadSplit` 在运行时重排
- **建议 5 · 日志分文件 + 按日期时间范围导出**
  - 日志行前缀补日期 `[YYYY-MM-DD HH:MM:SS.mmm]`（原只有时分秒，跨天后按日期筛无从下手）
  - 落盘文件名改 `apxdesktop-YYYYMMDD-HHMMSS.log`（每次启动一份，按最旧淘汰保留 10 份）；
    原来是固定名 + 超 2MB 先删，等于只有最后一次运行的日志，想挑一段历史挑不到
  - 新增 `apxpc::logexport`（三端共用，只依赖标准库）：`list` 列类型与文件、
    `exportRange` 按行首时间戳做窗口过滤后合并写出；兼容旧格式行（用文件 mtime 补日期）
  - 面板新增 `log.list` / `log.export` 两个 act；web 端新增 `LogExportModal`：类型下拉、
    起止时间（`datetime-local step=1`，精确到秒）、快捷「近 1 小时/近 24 小时/全部」、
    导出目录可改、结果显示实际行数与导出路径；底栏加「日志」入口
- **建议 7 · 虚拟键盘长按连续输入**：按住 400ms 后每 55ms 重复发送同一个键，松开即停；
  松开时吞掉多出来的那一次 click（否则长按退格会「连发 N 次 + 抬手 1 次」多删一个字符）；
  修饰键不参与连发；`ACTION_CANCEL`（手指滑出键帽）当场清标志

### 待定

- **建议 6**（「app 设为输入法/无障碍 → 采集输入映射到电脑，不必把 app 挂前台」）：
  **本版未实现**。该能力在 Android 上分两条腿 —— 键盘可经
  `InputMethodService.onKeyDown` 转发（无需 root，仓库已有转发桥），但**外接触控板的
  原始位移被系统当光标消费**，普通 IME/无障碍拿不到，需读 `/dev/input/event*`（evdev，root）。
  采集范围与隐私边界（是否落盘、默认开关）需先确认，故不在本版

### 验证状态

- 三端 CI 全绿：Android 编译 + lint、PC 三平台（win/mac/lin）、协议校验、mac 构建，共 6 项
- **建议 3（莫奈取色）仅编译级验证，待真机回验**
- 问题 2 页面部分（横屏设置页底栏压底）与问题 3/5/6 的修复，均对着真机反馈与代码核实，
  **待报障者真机回验**

---

## v223.12（2026-10-29）

本版为键盘 UX 修缮 + 机械键帽（PR #50，五问评估后的 P0+P1 落地）。
v223.11 之后 master 唯一增量，此前未随版发布。

### UX 修复（P0）
- **编辑器选键缺口**：`KEY_CHOICES` 补反引号（0x35 —— 模板「终端运维」的
  Ctrl+` 此前选不出）、PrSc/ScrLk/Pause（0x46–0x48）；`KEY_GROUPS` 同步。
- **自定义布局误删防护**：行内键从「点一下即删」改为**长按删除**，
  顶部加操作提示 —— 误触不再直接删键。
- **拖拽排序跨行修正**：`HotkeyBoard.reorder` 从只按中心 X 匹配改为
  **二维匹配**（先 Y 找最近行，行内再按 X），跨行拖动不再插错行；
  `ComboChip` 回调补 `dropCenterY`。

### 机械键帽（P1）
- `KeyPref` 新增**键帽材质**维度（平面/机械）；机械档 = 三层立体键帽
  （底座 + 侧壁 + 顶面高光）+ 按压下沉动画 + 三段波形震动
  （按下冲击 → 触点停顿 → 回弹）。
- 新增**机械轴音**：SoundPool 播 `res/raw/mech_click.wav`
  （青轴式双响短噪声脉冲），不跟随系统触摸音效开关。
- 音效档位加「机械轴」；设置页键盘外观段加「键帽材质」行。

### 快捷键更新（P1）
- 新增 `ROW3` 默认集：重开标签（Ctrl+Shift+T）、任务视图（Win+Tab）、
  截图（Win+Shift+S）、通知中心（Win+N）、最小化（Win+M）。
- 管理列表内嵌**自动排序开关**（原埋在设置页深处）。
- 长按 chip「按住」态加视觉高亮（与轻点按下区分）。

### 其他
- 键帽 `contentDescription`（无障碍：「发送 X」/「X 修饰键」）。
- 自定义键盘对话框顶部操作提示行。

---

## v223.11（2026-10-29）

本版集中处理 mac 端四件报障事（互传「提示不支持」、辅助功能检测、托盘状态、
连接卡模式显示）与 Win 壳连接卡回填；同时把 PR #49 的 mac 双击闪退修复
正式带上发布线（该修复已在 master，自 v223.10 起未发过版）。

### 修复（mac）：双击 AllPeriph.app 闪退（PR #49，issue #47）
两层根因，都已修，并加了防回归：

- **bundle 封印破损**：打包管线 `codesign --deep` 签完后又把 `web/` 拷回
  `Contents/MacOS/web`，`CodeResources` 里没有它的记录 —— Apple Silicon 对
  「签名后被改动」的 .app 直接 SIGKILL（崩溃报告只在
  `~/Library/Logs/DiagnosticReports`，界面无任何提示）。修法：`web/` 在签名
  **前**放进 bundle、改放 `Contents/Resources/web`；签名不用 `--deep`；
  新增 `codesign --verify --strict` 自证，seal 不完整直接红，不再出带病包。
  v223.7 起 mac 包全部中招，本版起恢复。
- **AppKit 必须拥有主线程**：托盘（NSStatusItem）在非主线程初始化抛
  `NSInternalInconsistencyException`，主线程的 `catch(...)` 接不住别的线程的
  异常。修法：tray 的 AppKit 初始化回到主线程并 `@try/@catch` 兜底
  （headless SSH 下无托盘、应用继续跑）；HTTP 面板挪到后台线程，主线程跑
  托盘事件泵。
- **CI smoke test**：用 LaunchServices `open` 走真实双击路径启动 .app，
  封印破损或线程崩溃当场红 —— 这是能抓住这两类问题的「双击模拟」。
- 顺手：mac zip 的 README 补退出方式说明（托盘菜单已存在）。

### 修复（mac）：浏览器面板互传「提示不支持」—— file.send 真实现
报障者点的是**文件面板**（FilePanelModal）：它此前没有 HTTP 面板管线适配，
后端 `file.send` 也只是个桩，点了直接回「本机不支持」。现已接真：

- `file.send` 走 `sendFile`(9512) 直发手机，每 10% 推一次进度事件
  （`on-file-send-progress` / `on-file-send-done`），FilePanelModal 按 HTTP
  语义适配：act-response = 已入队，等 done 事件收尾；主按钮补 `label[for]`。
- 发送白名单与 `file.delete` 同一接收目录，`weakly_canonical` 防路径穿越。
- 「上传到手机」：`/api/upload` 支持 `?target=file`；未连手机时只落盘并回
  warn，不假装已发。

### 修复（mac）：辅助功能检测体验
- 横幅写明**卡住的是哪个进程**、需要哪个权限；解释包里三个 TCC 身份
  （AllPeriph.app / 裸 apxdesktop / apxhost，各自 ad-hoc 签名）—— 在系统设置
  里勾错对象就会出现「明明开了还是不行」。
- 横幅上加「重新检测」（发 `input.recheck`，实时预检授权状态）与
  「弹出授权引导」（直达系统设置对应开关）两个按钮；勾选后 1 秒内自动消失。
- 授权预检的 stderr 输出改走 APX_LOG 落盘（`~/Library/Logs/AllPeriph/`），
  报障可回查。

### 修复（mac+win）：托盘状态回传 + 连接卡如实显示
- 托盘创建失败的具体原因随 `/api/state` 的 `tray` 字段回传面板（是否创建、
  是否激活、失败原因），不再只能靠猜；托盘通知改为跨线程排队投递。
- **Dock 没有图标是设计使然**：纯托盘应用 `setActivationPolicy:Accessory`，
  README 已注明，不是 bug。

### 修复（win）：连接卡三行永远「未上报」
- Win 壳此前没把 Ctrl9511Server 的状态喂给面板 buildState，「手机上报 /
  输入信任 / 受控服务」三行恒为未上报。HostBundle 增加 ctrlSrv 指针，
  desktop_main 在 Win 下注入，三行恢复真实数据（手机 0x31 解码、
  `platformInputTrusted()` 实时查询、受控端连接状态）。
- 新增 `phone.shared` 字段：受控端由**另一实例**提供（ctrlShared）时，
  本面板读不到手机上报属正常 —— 面板如实显示「受控端由另一实例提供
  （不是故障）」并加「经另一实例」标记，不再误报为手机未上报。

### 其他
- CI 抓到 `TrayStatus` 嵌套类型在类外定义返回值需全限定
  （`TrayIcon::TrayStatus`），mac / Win 三处已修。

---

## v223.10（2026-10-27）

### 新增（遥控器页：被控端**光标浮层开关**）
手机控手机 / 手机控 TV 时，被控端会把控制端光标画成全局浮层（蓝点跟随）。
有的场景嫌它碍事 —— 控制端给个开关：

- **新协议命令 `0x32`**（控制端 → 被控端，1 字节：0x00 隐藏 / 0x01 显示）。
  被控端两端（手机 `TvControlServer` / TV `:shared` 的 `ControlServer`）都接，
  落到 `TvInjector.setOverlayEnabled()` 统一门控 —— 注入器的**全部三个** move 落点
  （cursorMove / nudge / setConnected）都走同一个 `paintOverlay()`，关掉后不再画、
  且保证收起一次（不做这个门，开关就是假的）。
- 遥控器页顶栏加「光标 开/关」chip：切换即发帧 + 记偏好；偏好持久化，
  重进本页、自动重连后都会自动重发（`TvControllerClient.lastOverlayCursorEnabled`）——
  被控端的状态是会话级的，不重发的话上次关掉的浮层又会冒出来。
- 用户没动过开关时什么都不发（老行为不变）；被控端是旧版时帧被忽略，无副作用。
- **边界如实说**：浮层是被控端**全局**的 —— PC 控手机时同一颗点也走它，
  关掉对所有控制端生效，不是「只对我的会话」。

### 文档（主页 README 更新）
「能做什么」表补齐互传 / 手柄 / 被控 / 浏览器面板四行、副屏行写明 mac 出路；
已知限制三条过时项更正（蓝牙键盘手柄已交付、被控显示名已是真实机型、
macOS 边界补 Sunshine 出路与浏览器发文件口径）；新增「最近更新」小节。

---

## v223.9（2026-10-27）

本版是**外部 patch 的落地与复核**（用户提供 `eb652332….patch`，要求「看看有能修的不」）。
逐块核对事实后全量应用，其中两处按仓库现状修正；修出的结论如下。

### 音频（Android）：UAC2 gadget 的 PCM **设备号**也改为运行时解析
`AudioModule` 此前只解析卡号（`locateCard`），设备号写死 `D0`。`/proc/asound/pcm` 每行
`卡号-设备号: 名称 : … : playback N : capture M`，两个号都随内核枚举顺序变化 —— 换机型
写死 0 会打到别的设备上。新增 `locateDevice(card, playback)`：按卡号 + 方向关键字解析，
解析不到**如实降级 0 并记日志**（UAC2 gadget 通常只暴露一个 PCM 设备，不会静默假装成功）。
路径拼装收敛到 `AudioConst.pcmNode()`，`/proc/asound/{cards,pcm}` 两个路径进常量。

### 面板（web）：「音箱 / 麦克风」设备下拉接上**真枚举**
后端早就支持选设备（`speaker.toggle`/`mic.toggle` 都吃 `deviceId`，`AudioCapture` 的
`opt.deviceId` 留空跟随系统默认、失效明确报错），但 web 面板的下拉是写死的
「（暂未枚举播放设备）」—— 能力在，入口没接。本版补齐：

- 两个面板入口（`web_panel.cpp` / `panel_http.cpp`）新增 query `audioDevices`，
  返回 `{ devices: [{id,name,isDefault}], defaultName }`，与 Win32 原生面板
  （`refreshSpeakerCombo`）同源：Windows 走 IMMDeviceEnumerator 有真实列表；
  **macOS 的 Process Tap 采全局混音、不挂具体端点，Linux 未实现 —— 都返回空表**，
  前端据此如实显示「本机不支持枚举播放设备」，不编造条目。
- `bridge.audioDevices()` + `useAudioDevices()`（查询失败按「枚举不到」处理）；
  Speaker/Mic 两卡的下拉改真实数据，选中后 `toggle` 带 `deviceId`（留空 = 跟随默认）。

### 快捷键模板色（Android）：内联 ARGB → 颜色令牌
模板色此前内联在 `HotkeyTemplates.kt`（`0xFF3482FF…`），深色下偏暗的两档
（终端石墨 / 表格墨绿）靠 `tint()` 无差别向白提亮 45% —— 鲜艳色也被漂白。
改为 `R.color.hotkey_tpl_*` 颜色资源（values-night 覆盖同组暗色档），
取色统一走 `resolveColor()`；`tint()` 只提亮「最亮通道 < 170」的暗色，
鲜艳色保持原样。设置页与自建模板色板同步换令牌。

### 文档纠错（ROADMAP / 注释）：三条「待办」实际早已交付
- 「桌面壳 apxdesktop：**托盘仍无**」→ v223.7 已交付 `tray_mac.mm`（本版核对时发现
  v223.7 只改了待办段、漏改了表行）。
- 「接入键盘出口 = 本 PR」→ 键盘出口已接入（`HidKeys.send` → `Uplink.BLUETOOTH`，
  已对源码核实：`BtHidDevice.reportKeyboard` 在，`Uplink.BLUETOOTH` 分支在）。
- 「蓝牙键盘/手柄仍缺失（`BtHidDevice` 无 reportKeyboard/reportGamepad）」→ 两者
  自 v184 起都有，缺的是**真机配对与实测**。`pipeline.hpp`/`CMakeLists` 里的同类
  过时注释一并更正。

### 诚实说明
- Android 侧改动（PCM 设备号解析、模板色令牌）**只有 CI 编译级验证**——本机无法
  构建安卓，PCM 行为要真机（插 USB 后看 `AudioModule` 日志是否出现「按 0 降级」）。
- 设备下拉在 mac/Linux 上依然是空表（平台能力边界，不是回归）；Windows 上选设备
  的路径此前已有后端与原生面板验证，本次只是把 web 入口接上。

---

## v223.8（2026-10-27）

### 新增（mac 副屏出路：面板里做 Sunshine 检测 + 指引）#47 建议落点
ROADMAP §2.5 核实过的事实：mac 缺虚拟显示器驱动（Windows 有 IddCx，mac 没有对等物），
不自研副屏；出路是用户自装 Sunshine（本机）+ Moonlight（手机）。而 Moonlight 客户端是
GPLv3，与本项目 MIT 冲突，**不能内嵌** —— 所以面板能做的是检测 + 指引：

- 新模块 `apxpc::screen::sunshineStatus()`（`include/apxpc/screen/sunshine_detect.hpp`，
  只读）：**已安装** = `~/.config/sunshine/` 存在（mac 另认 `/Applications/Sunshine.app`）；
  **在跑** = 默认 https 端口 47990 有人监听（复用 v223.7 的 `tcp_probe`）。
  两条证据**分开存**：本工程托盘的默认端口恰好也是 47990，所以「有人在听」单独不构成
  已安装证据（可能是本工程另一个实例），不合并成一句含糊的「已就绪」。
- 面板「副屏」卡 mac 侧三态：已装且在跑 → 显示端口 + **打开 Sunshine 设置页**的链接
  （浏览器会提示自签证书，写清「高级 → 继续访问」）；装了没跑 → 提示打开应用后刷新；
  没装 → 指引下载地址 + 说明为什么不内置客户端。
- 新动作 `screen.sunshine` 返回 `{ installed, running, httpsPort, url }`（url 在跑才给，
  不猜端口）；Windows 有真副屏（自研已交付），这些字段缺省、整个块不渲染，不受影响。

### 诚实说明
- 端口探测只能说「这个端口有人在听」，说不清是谁 —— 这是 `tcp_probe` 的语义边界；
  所以「在跑」的判定可能与「本工程另一个实例在监听」混淆，已用「配置存在」这条独立
  证据把它压住，但**没有真机验证过**（本机无 mac，编译级交付）。
- Linux 上包管理器的安装位置太散（/usr/bin、flatpak…），**不猜**：Linux 只认配置目录，
  检测不到就按没装处理，面板文案里写清楚。

---

## v223.7（2026-10-27）

### 修复（`apxhost serve` + 面板同时存在时，9511 每天一堆「已在运行」告警）
面板（`panel_http`）与常驻服务（`apxhost serve`）都会去起 9511 受控端 —— 先起的占住端口，
后起的 bind 失败，于是每次启动都在日志里刷一条告警。用户看到的「9511 已在运行」其实
是**正常状态**，不是故障。现在：

- 新模块 `apxpc::net::tcpPortResponds()` / `tcpPortRespondsLocal()`
  （`include/apxpc/net/tcp_probe.hpp`，POSIX + Winsock 双实现）：非阻塞 connect + select，
  200ms 超时。语义写清楚——**它只能说「这个端口有人在听」，说不清是谁**。
- 面板启动前先探一次 9511：有人在听 → 标记 `ctrlShared`、**不再重复起受控端、不打告警**，
  日志改成「9511 已有实例在跑，本面板共用」；没人听 → 正常起，bind 仍失败时再探一次，
  以区分「被别人抢先」与「真起不来」（后者才 `APX_LOGW`，并带上错误原因）。
- 状态里新增 `transports.ctrlServer = { listening, shared, connected, error }`，
  面板「连接」卡多三行归属说明：**本面板提供 / 由另一个实例提供 / 未启动 + 原因**。
  以前这类事只能翻日志，现在打开面板就能看到。

### 新增（mac 托盘：菜单栏图标，终于有「退出」入口）
mac 的 `.app` 此前**没有任何退出入口** —— 只能 `pkill -x apxdesktop` 或活动监视器。
`src/tray/tray_mac.mm`（新文件，ARC）用 `NSStatusItem` + `NSMenu` 补上：
`NSApplicationActivationPolicyAccessory`（只在菜单栏，不进 Dock）、SF Symbol `computermouse`
（拿不到就退文字图标）、菜单项**谁注册才出谁** + 「退出全能外设」（⌘Q）。

配套两个导出（`apxpc::ui`）：`requestPanelQuit()`（只置原子标志，面板线程 ≤1s 内自行收尾，
托盘线程不碰面板状态）与 `panelUrl()`（端口是 `bind(0)` 运行时分配的，所以只能点击那刻取）。
`desktop_main` 的 mac 分支接上这两个回调；「打开面板」用 `posix_spawn` 直接 exec
`/usr/bin/open`，不经 shell（URL 里的 `?`、`&` 会被 shell 当元字符）。

### 新增（mac USB 走 IOKit 枚举）
非 Windows 侧本来只有 libusb（默认 OFF）与 sysfs（读 `/sys`）两条腿，mac 两条都够不着
→ 枚举恒返回空，面板「USB」状态永远 false，看不出是「没插」还是「没实现」。
新增 `src/discovery/usb_enum_iokit.cpp`：`IOServiceMatching("IOUSBHostDevice")` +
IORegistry 属性（`idVendor`/`idProduct`/`USB Product Name`/`USB Vendor Name`/
`USB Serial Number`/`bcdUSB`/`Device Speed`）+ 子 `IOUSBHostInterface` 节点的
`bInterface*` 拼出与其它三端同构的 `usb::Device`。**速度（`Device Speed`）故意留 Unknown**：这个属性的
值域有两套互不兼容的说法，能定音的 `tIOUSBHostConnectionSpeed` 不在公开 SDK 里
（CI 实测头文件不存在）。猜错会把 480Mbps 的线标成 super-speed，而本工程的
super-speed 门禁恰好靠这个字段 —— 标错比标 unknown 更坏。原始值写 debug 日志，
有真机后 `ioreg -p IOUSB -l` 一眼就能补上映射。

**诚实说明（三点没做，不假装做了）**：① 端点（Endpoint）恒为空 —— 要逐接口走
`IOUSBHostPipe`，属性名随 SDK 漂移，没真机无法验证；② HID 集合（usagePage）恒为空 ——
要开 `IOHIDDevice` 读元素，涉及 TCC 输入监控授权；③ CDC ACM 串口名恒为空（走
`IOSerialBSDClient` 另需父子回溯）。角色判定（HID/UVC/UAC/NCM/CDC/Bulk）只用接口类码，
不受影响。**本文件只做到编译级验证**（本机没有 mac），真实取值仍需真机回验。

### 修复（Edge / Chromium 里面板「发送」点了没反应）#47 第 3 条
浏览器模式下「发送」触发系统文件选择器靠的是对 `display:none`（`sr-only`）的
`<input type=file>` 调 `click()` —— 这条路在 Safari 上踩过一次（v223.5 已改），
Edge 上则卡在另一层：脚本内的 `click()` 缺少用户手势上下文时被静默忽略。
这次不再跟引擎博弈：浏览器模式直接把「发送」做成**原生 `<label for=input>`**，
点它触发的是浏览器自己的默认行为（天然带用户手势），JS 完全不介入；桌面壳仍是
`<button>` + `window.chrome.webview` 的 `openFile`。另外补一条诚实分支：
地址里没有 `?host=1`（= 不是从面板入口打开的静态部署页）时直接提示，而不是静默无反应。

### 测试
- 新增 `tcp_probe_test`（真起 socket 监听 → 探到 → 关 → 探不到 → 连续两次一致 →
  边界：端口 0 / 空 host / 非法 IP），已进 `build.yml` 与 `macos.yml` 的 target 列表；
- 本地 Linux 全量构建 + ctest 通过（`tcp_probe_test` 7 条断言全过）；
- `pc/web`：`tsc --noEmit` 与 `vite build` 通过。

### 仍待真机回验（只有编译级证据的部分）
- mac 托盘：图标能否出现在菜单栏、菜单能否点开 —— 托盘跑在独立 NSThread 上跑
  `[NSApp run]`（主线程被面板的 accept 循环占着）。若真机发现图标不出现或点了没反应，
  正解是把主线程让给 AppKit、面板移进后台线程，那是另一件事，不在本版打补丁。
- mac USB 枚举：插上手机后本机「系统信息 → USB」与面板 USB 状态是否一致。

---

## v223.6（2026-10-27）

### 修复（无线模式下 macOS 端**所有输入都失效**）#47 第 6 条后半段
用户报告：无线模式下 pad 实体键盘、实体触控板、app 内虚拟键盘、虚拟触控板全不能用，
且「之前实体键盘能用」。查到两件各自独立的事：

- **mac 手柄注入此前是空实现**：`injectGamepad` 只打一行告警就返回 —— 手柄接 mac
  完全没反应的直接原因之一。现改为**免驱降级**：按钮 → 键盘（A→Return、B→Esc、
  X→E、Y→Q、L1/R1→1/2、Select→Tab、Start→Space、Mode→Option、双摇杆按下→Shift/Ctrl），
  左摇杆 → 鼠标位移，右摇杆 → 滚轮（带死区、缩放与限速）。算术抽到
  `pc/host/include/apxpc/wireless/gamepad_fallback.hpp`，三端共用，Windows 侧同时删掉
  自己那份硬编码（`kDead` / `x/4` / `/8`）。
- **mac 上没有任何一口输入能落地时，先看 TCC「辅助功能」授权**：
  `CGEventPost` 在未授权时被系统**静默丢弃** —— 连得上、帧也到、日志干净、屏幕不动，
  与用户描述的现象完全一致。现在 `platformInputTrusted()`
  （`CGPreflightPostEventAccess()`）会经 9511 状态回给面板，未授权时
  ① 面板顶部显红色横幅（指引 系统设置 → 隐私与安全性 → 辅助功能），
  ② 首帧输入前弹一次系统授权引导（`CGRequestPostEventAccess()`）。
  Windows / Linux 无此门槛，恒为已授权。

### 新增（web 面板「无线 / 蓝牙 / USB」三个开关改为**真实状态**）#47 第 6 条
蓝牙与 USB 的真开关在**手机端**（系统配对 / root 配 gadget），PC 只是被动 host，
摆一个拨不动的开关只会让人以为坏了（用户原话：面板显示三个模式无效，建议实装可用功能）。
改为手机上报、面板照实显示「可用 / 当前在用」：

- **新协议命令 `0x31`**（手机 → PC，1 字节 flags）：bit0 无线可用 bit1 蓝牙可用
  bit2 USB 可用 bit3 无线在用 bit4 蓝牙在用 bit5 USB 在用；**可用 ≠ 在用**。
  手机连上后立即报一次、之后每 2s 一次（`TvControllerClient.sendModeStatus`）。
- 面板侧 `ConnectionCard` 三行：灰＝不可用或未上报、绿＝可用未在用、蓝＝当前在用。
  无线行右侧保留原来的**真开关**（本机发现 / 断开），其余两行只读。
- 两端各有一份位布局实现（Android `ModeStatus.kt` / PC `phone_mode.hpp`），
  各有单测用**同一组期望值**钉住（`ModeStatusTest` / `phone_mode_test`）——
  位序错不会崩，只会「面板说蓝牙在用、实际走的无线」。
- 「无线可用」按有没有可用局域网判（Wi-Fi / 以太网），不是照抄「开关拨上去了」。

### 修复（Android：`EventBus.onMain` 并不在主线程）
`onMain` 的 reified 重载转调时把 handler 传成了 `null`，而 `null` 走的是
「发布者线程直接回调」那条分支 —— 于是名字叫 onMain、实际在任意线程跑。已改为转调
带 `MAIN_HANDLER` 的那个重载。手柄页的状态行订阅就吃了这一条。

### 新增（手柄页不再只报「没反应」）
手柄页顶部加出口状态行 + 指引行（`GamepadExit`）：绿＝免驱真手柄（蓝牙 / USB），
橙＝降级或没有出口；按钮加按下 / 抬起两态反馈。文案按出口给，并如实写明
mac 端真手柄需要 Apple 受限 entitlement（`com.apple.developer.hid.virtual.device`），
第三方拿不到 —— 所以免驱真手柄只走蓝牙 / USB。

### 测试
- 新增 `gamepad_fallback_test`（死区 / 缩放 / 滚轮限速 / 边沿，含 i8 极值与同时按抬）、
  `phone_mode_test`（位序 / 打包 / 优先级 / 可用 ≠ 在用），已进 `build.yml` 与 `macos.yml`；
- Android 新增 `ModeStatusTest`、`GamepadExitTest`（纯 JVM，后者含一条防幻觉断言：
  手柄文案不得出现心率 / 血氧这类与手柄无关的能力）；
- 本地 Linux 全量构建通过（103 步，含两个新 target），逐二进制跑单测通过；
  `pc/web` `tsc --noEmit` 通过；`scripts/check_protocol.py` 49 项一致（`0x31` 有发有收）。

### 说明
- 浏览器面板的文件互传（macOS Edge 拉不起文件选择）**本轮按用户要求不动** —— 用户
  明确说「先不用修了」；已记下现象与浏览器版本（Edge ARM64 156），等有环境再查。
- 心率 / 血氧与功能键映射**从排期移除**：用户说明那不是他提的诉求。

---

## v223.5（2026-10-27）

### 修复（v223.4 里「发送」在 Safari 上点了没反应）
v223.4 的浏览器上传给 `<input type=file>` 用了 `hidden`（`display:none`）。
**WebKit/Safari 上 `display:none` 的 file input 不响应程序化 `click()`** ——
点「发送」不会弹选择器，按钮等于死的。mac 上 `apxhost panel` 用 `open` 起默认浏览器，
默认浏览器就是 Safari，所以这条路必须自己绕开：
改用 `sr-only`（`position:absolute` + 1px + `clip`）—— 视觉上一样看不见，
但元素仍处于渲染状态，WebKit 认它。
（顺带 `tabIndex={-1}`，免得那个 1px 的输入框占一个 Tab 停位。）

其余与 v223.4 相同；v223.4 可忽略，直接用 v223.5。

---

## v223.4（2026-10-27）

### 新增（浏览器面板：互传发送不再只有桌面壳能用）
- **macOS / 浏览器面板里「互传 → 发送」现在能用了** —— #47 第 6 条的后备项。
  此前这条路的实现是「系统文件对话框选路径 → `act localsend.send {paths}`」，
  而系统文件对话框只有 Windows 的 WebView2 桌面壳有；mac 与浏览器页面点「发送」
  只会得到一句「不支持系统文件对话框」，等于该模式下无法把本机文件传出去。
  - 前端：`bridge.uploadFile()` —— 用页面自带的 `<input type=file>` 选文件，
    把字节 `POST /api/upload?ip=&port=` 交给宿主；文件名走 `X-File-Name` 头
    （percent-encoded UTF-8，中文名不会丢）。桌面壳仍走原来的 `openFile`，
    两条路在 `LocalSendCard` 里按 `bridge.mode` 分派，发送结果同源
    （都等 `on-localsend-sent` 事件）。
  - 后端：`panel_http.cpp` 新增 `POST /api/upload`。body 是**流式**收的
    ——不整段进内存，也不受一问一答那条 512KB body 闸门限制（单文件上限 4 GiB）：
    先写 `<名字>.part`，收齐再改名落进互传目录、交 `localsend.send` 同一套发送队列。
    中途断线只留临时文件，且会被删掉 —— 不会出现半截文件顶着正式名字被别的程序读到。
  - 同名文件落盘自动让路（`名字 (2).ext`），不覆盖互传目录里已有的文件。
  - 落盘目录与接收端同一处（`下载/AllPeriph`），发出去的文件在「文件」面板里也看得见。

### 测试
- 端到端真机验证（Linux 本地跑 `apxhost panel`，直接打 HTTP 接口）：
  2 MiB 中文名二进制上传 → 落盘 sha256 与源完全一致、文件名原样还原；
  同名再传 → `测试 文件 (2).bin`；目标缺 ip/port → HTTP 400；
  声明 1 MiB 只发 4 KiB 就断开 → 临时文件被丢弃、目录无 `.part` 残留。
- 本地 `ctest` 8/8 通过；web 前端 `tsc -b && vite build` 通过。

---

## v223.3（2026-10-27）

### 修复（macOS 触控板完全没反应）
- **相对位移在 macOS 上是空操作，光标纹丝不动**。#47 第 6 条反馈
  「无线模式下实体键盘触摸板、app 内虚拟触控板都不可用，但键盘输入可用」——
  键盘走 `CGEventCreateKeyboardEvent`，触控板走 `injectMouse`，差别就在后者：
  旧实现把增量写进 `kCGMouseEventDeltaX/Y`，**落点却仍传当前坐标**
  （`CGEventGetLocation(CGEventCreate(nullptr))`）。投递事件的落点由 location 决定，
  增量字段只是事件自带的信息、不驱动光标 —— 于是每一帧都等价于「把光标移到它现在
  所在的位置」，看起来就是触控板完全没反应。
  现在先读当前坐标、加上增量算出目标坐标，再按目标坐标投递。
- 同一处顺带修掉四件事：
  - 每次移动都漏一个 `CGEventCreate(nullptr)` 造出的事件对象（连续滑动时是无上限泄漏）；
  - 按住键时的移动原来投递 `kCGEventMouseMoved`，改为 `kCGEvent(Left|Right|Other)MouseDragged`
    —— 否则部分应用收不到拖拽（选区、拖文件、拖窗口）；
  - 右键 / 中键一律传 `kCGMouseButtonLeft`，改为各自的 `CGMouseButton`；
  - 顺序改为「先结算按键状态、再移动、最后滚轮」：反过来的话「按下 + 同帧位移」
    会在键还没按下时先发拖动，「抬起 + 同帧位移」会把拖拽尾巴判成普通移动。
- 落点夹取到**全部活动显示器的并集**（副屏在主屏左侧时 origin 为负，不能只夹主屏）；
  取不到显示器列表时不夹，避免把光标吸到 (0, 0)。

### 测试
- 新增 `relative_move_test`（第 8 条用例）：相对位移换算、显示区域夹取、
  按住键时的拖动类型判定。换算与判定抽到
  `pc/host/include/apxpc/wireless/relative_move.hpp`，纯头文件算术，三平台都能跑。

---

## v223.2（2026-10-27）

### 新增
- **全屏触控板（Android 操控面）** —— #47 第 1 条。用户原话是「虚拟触控板的页能不能加个
  全屏模式，把页内其它控件都隐藏」，起因是「光标锁定」在 Android 上根本做不到：应用层
  没有「把系统光标限制在某区域」的 API（光标归 InputReader + SurfaceFlinger 管，App 只能把
  本窗口光标图标设成 `TYPE_NULL` —— 就是用户看到的「只有隐藏光标生效」）。所以让光标即使
  存在也没有可误触的控件，是唯一不依赖 root 的路径。
  - 入口：操控面标题头右侧 chips 新增「全屏」（蓝色，与「遥控」同级）。
  - 进入后收起标题头、页签栏、常用快捷键网格（含标题行）、方向键叠层、页内提示行，
    手势面去掉页边距与卡片圆角、整块屏幕就是一片触控板；系统导航栏一并隐藏
    （`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 保留边缘滑动临时唤出），所以底部那 40dp
    也不再被占。全屏期间**关闭左右边缘翻页** —— 不动的触控板上边缘跟别处没区别，
    留着只会变成「滑到边上页面跑了」。
  - 退出三条路：**返回键**（不再弹退出确认框）、右下角「退出全屏」小胶囊
    （3s 后自动降到 25% 不透明度，不抢视线）；外加「屏幕上沿下滑」这条附加路径
    （部分机型有效 —— 手势导航会把上沿下滑拿去拉通知栏，所以**不写进提示文案**，
    免得给用户一个按了没反应的操作）。
  - 进入时弹一次说明条（2.6s 淡出）：全屏里没有标题头可看，必须先告诉人怎么出去。
    这段说明与退出胶囊都挂在新增的外层 `FrameLayout` 上，非全屏时 `GONE`、不吃触摸。

### 修复（Linux 构建）
- `pc/display/pipeline/pipeline.cpp` 的 `captureDeviceName()` 直接调 Win32 的
  `::WideCharToMultiByte(CP_UTF8, …)` —— Linux/macOS 上既没有这个函数也没有 `CP_UTF8`，
  整个 `pc/display` 在本平台编不过（连带 `pc/host` 的 `apxdisp_in_host` 目标一起挂）。
  改成一个小 `wideToUtf8()`：Windows 仍走 `WideCharToMultiByte`（wchar_t 是 UTF-16，
  要处理代理对），POSIX 自行编 UTF-8（wchar_t 是 4 字节 UTF-32）。本机现已全量编过、
  7/7 测试通过。

### 修复（文档）
- `docs/ROADMAP.md` 的 `0x01` 载荷表补第 6 字节 `pan`（原表停在 v223 的 5 字节形态，
  照着改客户端会丢横向滚动），并加一段 v223.1 双向兼容说明。
- 同一文件里「只有手柄没有 Wi-Fi 出口（仅 USB）」是过期快照 —— `GamepadController.send()`
  早已是蓝牙优先 → `Uplink.resolve`（无线 9511 → USB），三出口都在；加订正注记。

---

## v223.1（2026-10-27）

macOS 桌面壳（`apxdesktop`）**启动即崩 + 退出不干净**的热修，外加触控板横向滚动与崩溃取证。
Tag `v223.1`，只含 `pc/host` 与 Android 客户端改动，设置页 UI 未动。

### 修复
- **macOS `apxdesktop` 启动即崩（`EXC_CRASH` / SIGABRT / `std::terminate` 无活跃异常）** —— #47 的主因。
  逐帧反解栈（`+378972` / `0x5c838` … `+27144` / `0x6550` `runWebPanelHttp` …）确认调用链：
  `main` → `runWebPanelHttp` → `Ctrl9511Server::start` 里 bind 失败分支 `delete im` →
  `Impl` 析构 → `MacClipboardWatcher` 析构 → **joinable `std::thread` 析构 → `std::terminate` → `abort()`**。
  而 bind 之所以必失败，是 mac 上 9511 受控端被创建了两次（`desktop_main` 的 `main` 先起一个，
  `runWebPanelHttp`（panel_http.cpp）里 `HttpPanel::ctrlSrv` 又起一个）；Windows 的 `runWebPanel`
  不创建 9511，所以只有 mac 崩。三层一起修：
  - `input_injector_mac.mm`：`MacClipboardWatcher` 补析构 `~MacClipboardWatcher() { stop(); }`；
    `start()` 若已有 joinable 线程直接返回 false（幂等）；采集线程改捕获 `shared_ptr<atomic<bool>>` +
    回调副本，不再裸用 `this`（对象先死、线程后醒也不会 UAF）；`stop()` 先置位再 join。
  - `ctrl9511.cpp`：`start()` 重写为「**先** socket/bind/listen 占端口，**再**起线程」—— 端口占用时
    直接如实返回 false，不再先建线程再去删对象；统一 `cleanup` lambda（停剪贴板、join accept/握手/
    收发/信标线程、删注入器），整函数 try/catch 兜底（对象已自助登记时走 `stop()`，否则 `cleanup`）。
  - `desktop_main.cpp`：**mac 不再创建 9511**，受控端只由面板入口创建（`#if _WIN32` 分支保留原行为）。
  - 回归用例 `testCtrl9511StartFailIsSafe()`（`pc/host/tests/wireless_session_lifecycle_test.cpp`）：
    「端口被占时 `start()` 必须返回 false 且进程不 abort」（前提由用例自带的 `PortBlocker` 制造：
    自己开监听 socket 占住端口，Windows 侧补 `SO_EXCLUSIVEADDRUSE`。**不能**靠「两个 server 实例
    互相撞端口」—— Windows 下双方都设 `SO_REUSEADDR` 时同一 addr:port 允许重复 bind，那条断言
    在 Windows 上不成立，首版就在 CI 上红了）、未启动实例 `stop()` 幂等、同对象重复 `start()`
    必须 false、`stop()` 两次后还能重启。修前用真实 `ctrl9511.cpp` + v223 语义平台层存根做的复现
    harness 稳定 `exit 134`，修后连续 5 次 `ALL PASS`。
- **macOS 退出后进程不消失（只能 `pkill -9`）**：`Ctrl9511Server` 停服时只用 `close(listenFd)` 去唤醒
  阻塞在 `accept()` 的接收线程，而 POSIX 下 `close()` **不会**让正阻塞的 `accept()` 返回 —— 最小 C 复现
  验证：只 `close` 时 accept 线程继续睡，`shutdown(SHUT_RDWR)`+`close` 后 accept 立刻返回 `EINVAL` 退出。
  于是 `join(acceptThread)` 永久等不到，`stop()` 卡死、进程走到退出流程前就挂住。新增 `closeListenSock()`
  （POSIX：`shutdown`+`close`；Windows：`shutdown(SD_BOTH)`+`closesocket`），`cleanup` 与 `stop()`
  两条路径统一改用它，并置 `listenFd = kBadSock` 防重入。
- **macOS 配置路径错落**：`defaultConfigPath()` 在 `__APPLE__` 下改为
  `~/Library/Application Support/AllPeriph/config.json`（原先落在 `LOCALAPPDATA` 这种不成规矩的路径上）。
  Linux/Windows 路径**刻意不动**，避免老用户配置看起来「消失」。

### 修复（Linux 目标）
- **`input_injector_lin.cpp` 长期编不过**（12 处 `struct uinput_setup has no member named 'absmin'`）：
  轴范围是老 `uinput_user_dev` 时代的写法，现代内核头里 `struct uinput_setup` 只有
  `id / name / ff_effects_max`。改为**逐轴 `UI_ABS_SETUP`**（`struct uinput_abs_setup`）设范围，
  顺序：EV/KEY/REL/ABS 位（`UI_SET_*BIT`）→ 逐轴 `UI_ABS_SETUP` → `UI_DEV_SETUP` → `UI_DEV_CREATE`；
  并补 `REL_HWHEEL` 相对轴位（横向滚动能落地的前提）。此前 Linux 目标整块编不过 ——
  不只是没产物，连 `wireless_session_lifecycle_test` 都到不了「编得过」这一步（ctest 里一直是 Not Run）。
- **补齐该文件缺失的四个自由函数**（此前只有 Win/macOS 两份实现，Linux 链接必 undefined）：
  `setSystemClipboard` / `readSystemClipboard`（xclip 优先、Wayland 回退 wl-clipboard，
  两个都没有就如实返回 false，不假装成功）；`injectSpecialKey`（协议 VK 码 → HID usage →
  同一张 Linux 键码表）；`injectSystemText`（纯 ASCII 短文本按 US 布局逐键键入，不碰剪贴板；
  含中文/换行/长文本走「写剪贴板 + Ctrl+V」并把用户原来的剪贴板还回去，与 Win/mac 同策略）。
- **`LinClipboardWatcher` 同型隐患对齐**：补析构 `stop()`、`start()` 幂等（已有 joinable 线程即拒绝）、
  采集线程改捕获 `shared_ptr<atomic<bool>>` + 回调副本（不再裸用 `this`），并加 600ms 回环抑制
  （自己刚写过剪贴板时 watcher 不回传）。macOS 侧同一处正是 v223 崩溃的根因，Linux 这份当时没跟着改。
- 本地 `ctest` 结果：Linux 上 **7/7 通过**（修前 6/7，`wireless_session_lifecycle_test` 因编译失败 Not Run）。

### 新增
- **触控板横向滚动（四端打通）**：0x01 控制载荷补第 6 字节 `pan`（i8，可选）。
  `ctrl9511` 注入接口 `injectMouse(..., int8_t pan = 0)`；macOS 用
  `CGEventCreateScrollWheelEvent(..., 2, wheel, pan)`；Windows 补 `MOUSEEVENTF_HWHEEL` INPUT；
  Linux 补 `EV_REL`/`REL_HWHEEL`；Android 侧 `TvControllerClient.mouse()` 发 6 字节、
  `TouchpadModule` 无线出口不再丢 `pan`。旧客户端少发该字节仍照常收（`bodyLen >= 6` 才读），双向兼容。
  契约同步进 `docs/PROTOCOL.md`。
- **macOS 崩溃取证与符号表**：`apxdesktop` 现在把现场落盘 —— `crash.txt`（信号、原因、
  `_dyld_get_image_header(0)` 基准下的逐帧 imageOffset、`backtrace()`）与滚动日志 `apxdesktop.log`
  （>2MB 自动清空，逐行 flush）；信号处理器只走 `open`/`write`（不用 stdio/malloc），记录后恢复
  `SIG_DFL` 并重发，`std::terminate` 也挂了处理器，未捕获异常另有 stderr + 退出码 2 兜底。
  同时 mac CI 新增 `Save symbols (for crash-offset lookup)` 步（**在 strip 之前** `cp` 未 strip 二进制 +
  `nm -n` + `c++filt`，并断言符号行数 >100 防上游去符号），产物附 `apxdesktop.symbols`/
  `apxdesktop.symbols.demangled`/`apxhost.symbols`/`apxhost.unstripped` —— 以后崩溃日志里的偏移
  可直接对到函数名，不必再手工反汇编。

---

## v223（2026-10-27）

### 优化
- 清理 `strings.xml` 里 20 条已无引用的字符串（排版改动后残留的旧文案、旧 tab 标题等），仅动资源、不改任何界面文案。
- **设置页右栏「分类列表」行：标题 + 12sp 说明 + 行尾箭头**（同一处反馈的第二半）。
  首页五行分类原先把「标题\n说明」塞进同一个 15sp TextView —— 右栏可用宽 ≈176dp，最长那条
  「遥控 · 工具\nTV/PC 控制 · 诊断 · 文件 · 自检/日志/悬浮球」≈254dp，五行每行都折成三行，
  分类列表因此又长又碎。现在标题 15sp 单行（超长省略）、说明下沉 12sp `APRowHint` 整行排开
  （文案各拆一条 `*_hint`，长的压到一行放得下）、行尾补箭头（进下级统一），行间加 1dp 发丝线
  `APListDivider`；行容器 id（`catGeneral` / `catLook` / `catRemote` / `catAbout` / `catHelp`）
  不变，点击进二级页的逻辑照旧。
- **设置页右栏排版：分组标题进卡、行长标题拆成「标题 + 说明」**（真机反馈「右边的分组文字太长」）。
  右栏只占屏幕 2/3（竖屏 ≈190dp，去掉卡片内边距只剩 ≈159dp），原先两类问题都挤在这一条窄列里：
  - **分组标题浮在卡外**：13sp 主色小标题 + 卡片本身两段留白，把窄列切得很碎，一个分类要滚好几屏。
    现在标题进卡做表头（新增 `APGroupTitle` + 1dp 发丝线 `APGroupDivider`），一张卡 = 一个分组，
    纵向每组省约 28dp；设置页 19 处「卡外标题 + 卡」全改完，本页 `APSectionTitle` 清零。
  - **行长标题带括号**：「遥控器（方向 / OK / 音量 / 电源）」「全屏操控面（触控板 / 键盘 / 副屏）」
    「快捷键自动排序（最常用排前面；手动拖过自动关掉）」「屏幕方向锁定（关掉横竖自动切换，横持
    自动弹出键盘也暂停）」在 159dp 里都折成两三行。现在标题只留词组（单行、超长省略），解释下沉到
    12sp「说明行」（新增 `APRowHint`）整行排开 —— 说明行不再和右侧胶囊抢宽度。
  - **行尾信噪比**：进下级（打开弹窗 / 跳页）的行改用行尾箭头（新增 `ui_common_chevron`），
    取值行才用胶囊。「打开」「自检」「去选择」这类胶囊在窄列里各占 ≈60dp，正是标题被迫折行的
    另一半原因。设备行同样受益：设备名与 IP 原先挤在一行（`DESKTOP-HAPSCO2` 被断成三行），
    现在名字单行、IP 作说明行。
  - 顺带统一：开关行标题一律 15sp（原 14sp 与设置行不一致）；卡内小节标题（连接 / 工具 / 键盘外观）
    加粗，与「说明行」拉开层级；设置行不再另缩 4dp，标题、说明、表头三处对齐同一条竖线。

---

## v222（2026-10-26）

### 新增
- **LocalSend 互传的 PC 发送端（PC → 手机 / PC → 官方客户端）**：上一版只补了接收方向，
  反方向仍然只能走旧的 9512 私有通道（要求控制链路先连上、对方必须是本 App）。
  新增 `apxpc/localsend/local_send_sender`，与 Android 端 `LocalSendClient` 三步同构：
  `register` → `prepare-upload`（每文件带 sha256 + size + fileType）→ `upload` 流式推送。
  - 自带的极简 HTTP 客户端（select + 超时 + 非阻塞 connect），不进 libcurl 依赖；
    `upload` 用 POST —— 官方 dto_v2 同时收 POST/PUT，省掉「PUT 不支持再重试」这条分支。
  - 取消：进度回调返回 false 即中止，并**尽力**向对端发 `cancel`，免得对面把半截文件
    当完整文件留着。
  - 命令行：`apxhost localsend-send <对端IP>[:端口] <文件…>`，带百分比进度。
  - 面板：互传卡里每台设备右侧新增「发送」—— 系统文件对话框选文件 → 后台线程推送，
    结果用 `on-localsend-sent` 事件回推（大文件要传几十秒，不让前端干等）；
    浏览器面板（`apxhost panel`）没有系统文件对话框，会如实提示而不是点了没反应。
  - 测试 `pc/host/tests/localsend_sender_test.cpp`：纯函数（fileType 表、prepare 报文契约、
    应答解析）+ 真端口对假对端（只实现五个端点）验证发出去的字段 / query / Content-Length /
    内容摘要，以及取消真能中断并发出 cancel、目标不可达与文件不存在如实报错。已进 CTest 与
    Windows/macOS 两套 CI 门禁。

### 修复（发版链）
- `pc/host/include/apxpc/config/app_config.hpp` 补 `<cstdint>`：`uint32_t` 此前靠间接包含
  才可见，Linux/g++ 下编 `app_config.cpp` 会报未声明（Windows/MSVC 一直能过，故未暴露）。

---

## v221（2026-10-26）

### 新增
- **LocalSend 互传的 PC 接收端**：手机端 `com.allperiph.localsend` 早就完整（多播发现 +
  `info`/`register`/`prepare-upload`/`upload`/`cancel` 五个端点），但 `pc/host` 里一个字节都没有 ——
  结果「手机 ↔ 官方 LocalSend 桌面版」能通、「手机 ↔ 本工程 PC 端」不通（只能走旧的 9512 私有通道）。
  新增 `apxpc/localsend/local_send_receiver`（协议 v2.2，与官方及本工程 Android 端互通）：
  - UDP 加入 `224.0.0.167:53317`，启动突发 100/500/2000ms + 周期自广播，
    并在收到对方 announce 后回 `register`，手机端设备列表里能看见本机；
  - HTTP 默认 53317，**被占自动回退 53318/53319** 且实际端口由 announce 带出去（对端无需猜）；
  - 文件落盘「下载/AllPeriph」，重名不覆盖；对端声明了 `sha256` 就边写边算并如实记录是否一致
    （不一致保留文件但明确告警——对方可能自己算错，删掉反而丢数据）；
  - 半截文件一律删除，不留「看起来收到了」的残file；会话 30 分钟未动自动清理。
  - 自实现的 SHA-256（仓库此前只有 crc32），用 NIST 向量钉住（含 100 万字节那条）。
  - 用法：`apxhost localsend [端口] [秒数] [目录]`。
  - 单元测试 `pc/host/tests/localsend_protocol_test.cpp`（SHA-256 向量 + prepare-upload JSON 契约 +
    文件名清洗）与集成测试 `pc/host/tests/localsend_upload_test.cpp`（真开端口走完
    prepare-upload → upload → 落盘 → 摘要校验，外加重名、未知会话、缺 Content-Length、
    清单不合法、`stop()` 收得回来）都已进 CTest 与两端 CI 门禁。
  - 清单不合法（不是 JSON / 一个文件都没有）直接回 **400**，不再回 200 建一个空会话 ——
    那样对端以为清单已受理，下一步 upload 才 404，问题被藏到更晚才冒出来。
- **面板里的「互传」卡（本轮接线）**：接收端此前只能在命令行跑（`apxhost localsend`），
  开了桌面面板的手机却发现不了本机。现在三个入口全部接上：
  - `HostBundle` 加 `localSend`（裸指针，所有权仍在调用方；空 = 未初始化）；
    `host_main` 的 `panel` 分支与 `desktop_main` 各建一个实例挂进去；
  - 两个面板后端同构三个动作：`localsend.toggle`（可带 `enable`，不带则取反）/
    `localsend.status` / `localsend.openDir`，state 里多一段 `localsend`；
  - 随面板启动、退出前 `stop()`；启动失败（53317 被官方 localsend 占用等）只告警、
    不阻断面板，卡片上还能点开关重试；
  - 收到文件：托盘气泡「<发送方> 发来 <文件名>」+ `on-localsend-received` 事件
    （`{path,size,from,via:"localsend"}`）；
  - `openDir`：macOS/Linux `fork`+`execlp(open/xdg-open)`，Windows `ShellExecuteW`，都不经 shell；
  - 前端第四格（旧面板是 USB 卡）换成互传卡：开关 / 实际端口 / 附近设备 / 已收数 /
    落盘目录 + 「打开目录」；多播不可用时把 `warn` 如实显示（仍能收直连，只是设备列表空着）。
  - 状态字段契约由 `apxpc::localsend::statusJson()` 单点产出，`testStatusJson` 钉住字段名与
    类型，前端 `HostState.localsend` 照此声明 —— **改名要两边一起改**。
  - 仍未做：PC → 手机方向（用本工程 PC 端主动发文件，`LocalSendClient` 目前只有 Android 端有）。

### 修复
- **HID 上报重复（合并未合分支）**：`pc/display/inject/hid/apxhid.c` 的 `ApxHidEnqueue` 在有挂起读请求时，
  把同一份报告**既**交给挂起请求**又**写进环形缓冲区 —— 下一次 READ_REPORT 会把同一报告再报一次
  （表现为按键/鼠标重复、抖动）。改为二选一：先给挂起请求，没有挂起请求才入队；
  `ApxHidCompletePendingRead` 改为返回「是否已交付」。
- **USB 重连后 Use-After-Free（合并未合分支）**：`FrameWriter` / `CtrlSession` 持有的是
  `open()` 时创建的 Channel 裸指针，而 USB transport 每次 `open()` 都重建 Channel 对象 ——
  重连成功后写帧与控制面都在用已析构对象。新增 `setChannel()`，并在**两条**重连路径
  （LinkMonitor 自愈、encodeThread 连续 30 帧失败）重绑到新指针。
- **stop() 后进程退不掉（v198 无线会话死锁，合并未合分支）**：`worker()` 的 Auto 分支
  收完旧 beacon 线程后无条件 `beaconRun_.store(true)` 重建；与 `stop()` 在临界区交汇时，
  新 beacon 线程挂在 `recvfrom` 上、`stop()` 末尾 join 永不返回（只能 kill 进程）。
  收完旧线程后**重核 `running_`**，已停就不重建；并补压力测试
  `pc/host/tests/wireless_session_lifecycle_test.cpp`（400 轮 start/stop + 析构路径，已进 CTest）。
- **USB 模式锁 2 个大核全频满载（反馈#1）**：`apx_jni.cpp` ALSA 非阻塞读写遇 EAGAIN 自旋 ——
  `nativeRead` 主机不发流时空转钉死一核、`nativeWrite` 缓冲满时 `continue` 空转钉死另一核；
  改为 EAGAIN 立即返回 / 1ms 节流重试（100ms 预算，超时丢帧保 stop() 能收线程），
  Kotlin 搬运循环同步节流（0906918、1ba2001）
- **方向锁定仍绑陀螺仪、设置入口难达（反馈#2）**：锁定中布局钉死不再跟系统配置翻面、
  `onResume` 不使能方向传感器、`setOrientLock` 直接停物理检测；竖屏顶栏 + 横屏悬浮
  右上角常驻「设置」入口（f3da1d0）
- **横屏键盘页没有方向锁定 / 切换触摸板·键盘按钮（真机反馈）**：pill 的显隐原先写在
  `bindActions()` 里，而这个方法 onCreate 只跑一次 —— MainActivity 的 configChanges 含
  orientation、旋屏**不重建 Activity**，于是「竖屏启动 → 转横屏」这条路上 pill 永远是
  `GONE`。改由 `applyOrientationLayout()` 按朝向统一设，冷启动与旋屏同源。
- **横屏底栏隐藏后留白没收回（真机反馈）**：`applyOrientationLayout` 里无条件写死
  104dp / 90dp 底距，把 `layout-land` 给的值整个覆盖 —— 底栏都藏了，底下还压着一条百 dp
  死白带。改按朝向取 `inset_bottom_nav`(竖) / `inset_bottom_land`(横)，设置页同样跟着调。
- **横屏 pill 压住内容**：pill 悬浮在右上角，正盖在键盘页顶部那行布局页签的右端
  （点最后一个页签「自定义」命中的其实是 pill）。不再从 pager 顶部整块让出一条带子 —— 真机
  反馈「横屏时最上面的横条把所有界面都压下去了」：那样触控板 / 键盘 / 设置**每一页**都往下
  缩一截，而 pill 只压到两处的右上角。现在按「谁真被压到，谁让」分开处理：① 触控板页整页
  **不让高度**，只把右栏那一行（快捷键 / 编辑）改**竖向**让位
  （整行下移到 pill 之下）—— 初版照键盘页那样只加右内边距，真机复测这一栏只有 ~320dp 宽，
  扣掉 pill 宽度后「编辑」被挤成竖排两行，故改竖向让位，宽度不再被切；② 键盘页也不
  让高度，只把顶部布局页签（横滑容器）的右端让出 pill 宽度，页签滑到底也到不了 pill 底下；
  ③ 设置页只让右栏（分类列表，整行可点、在 pill 底下既点不到又易误触切页）顶部让出带子，
  左栏（状态 + 传输按钮）保持满高。竖屏每次写回各自布局的原值（旋屏不重建 Activity，
  改过的 padding 会留在同一棵视图树上）。
- **截屏掉不下来（FLAG_SECURE）**：MainActivity 与 TouchpadActivity 都挂了 FLAG_SECURE，
  截图 / 录屏 / 最近任务缩略图全黑，界面问题只能口述、没法对证。两处一并移除。

### 变更
- **设置页两栏「左 1 右 2」写进布局**：原先靠运行期 `applySettingsTwoPane()` 改 orientation
  与 LayoutParams（横屏才分栏、竖屏纵向堆叠）；按要求改为**任何朝向**都是左 1/3 + 右 2/3，
  且排布直接落在 `layout/page_settings.xml` 里 —— `settingsTwoPane` 恒为横排、权重 1:2、
  中缝 12dp，两栏各自 `match_parent` 高 + 独立滚动，运行期不再改 LayoutParams。
  真机反馈「设置页没有全屏」的修法：两栏都 `fillViewport` + 内容容器 `match_parent`，
  右栏首页分类卡片 `weight=1` 撑满整栏、左栏整栏一张卡（此前内容短的时候，右栏卡片
  停在半屏、左栏一小坨内容浮在灰底上）；左栏三个传输按钮由「三个 weight=1 横排」改
  竖排整列宽 —— 1/3 栏宽下每个只有 ~33dp，「无线 / 蓝牙 / USB」被单行 + ellipsize 切空，
  真机上只剩三个空描边胶囊。

### 变更（续）
- **左侧三个传输按钮改成「进对应分类」（真机反馈）**：原先点一下就地开/关，三类模块行挤在
  设置页「功能开关」卡里按开关状态显隐 —— 既进不去也看不清结构。现在按钮只负责导航
  （`StatusFragment.bindActions` → `Host.showCategory` → `SettingsFragment.showCategory`），
  右栏切到「无线 / 蓝牙 / 有线（USB）」分类页：第一张卡是这一类的**总开关**（勾选即开/关，
  生效路径仍是 `onTransportToggle`，模块热启 / 前台服务 / 双属模块退出开关都不变），
  第二张卡是这一类模块行；未开启时模块卡收起（与旧行为一致）。分类页的显隐交给设置页导航 ——
  `MainActivity.refresh()` 里那份按 `isTransportEnabled` 每秒置 `group*` 可见的旧逻辑
  已删（否则刚切过去就被下一秒刷回 GONE）。按钮本身仍用选中色显示当前开 / 关。
- **设置分组里的长文继续往帮助页收（真机反馈）**：`page_settings.xml` 里三处**跨行硬编码**
  的说明文案（触摸板光标锁定、互传 LocalSend、导出运行日志）改成只留一行标题，说明搬进
  帮助页新增的「开关与日志」卡；遥控 tab 的「遥控键映射」三段长文搬进帮助页新增的
  「遥控键位」卡（原文零改动），那一行改成「遥控键映射说明 → 帮助与说明」的跳转；
  另删掉四处 `visibility=gone` 的旧长文占位（内容早就在帮助页了）。
- **`check_ui_tokens.py` 补上跨行文案的漏洞**：原先逐行匹配 `android:text`，`android:text="A
 换行 B"` 这种写法整条躲过关卡（上面三处长文就是这么藏在布局里的）。改成整篇匹配 + 按偏移
 算行号，基线同时从 536 收到 530（本轮资源化掉 5 处中文文案、又加回 0 处）。

### 新增
- **两个日志对话框都能复制 / 导出了（真机反馈「两个日志都没有导出和复制的功能」）**：
  「运行日志」与「Logcat 系统日志」对话框加两个动作 —— 中立位「复制」（整段进系统剪贴板，
  顺带随「手机复制 → 电脑粘贴」的剪贴板同步带去电脑）、否定位「导出」（后台写
  `Download/AllPeriph/log_*.txt` / `logcat_*.txt`，完成后 Toast 路径）。Logcat 是后台抓取的，
  文本存字段里给按钮读，抓到之前两个动作先禁用（否则只会复制到「读取中…」）。落盘与文案
  与设置页那行「导出运行日志」共用同一份 `saveToDownloads` / `logHead`，MediaStore 写法只留一处。
- **Logcat 系统日志工具（反馈#4）**：设置页「工具」小节新增入口，root 抓全进程 logcat，
  无 root 降级本进程日志
- **各端平台客户端与功能总表**：新增 `docs/CLIENTS.md`（反馈#5），README 文档表已挂链
- **本文件回填 v212~v217**（反馈#6）；CI 新增常驻 `repo-edit` 大文件锚点编辑器
- **物理触摸板指针映射（反馈#8 Q1）**：蓝牙触摸板 → 电脑，hover 相对化 + 按键 + 滚轮
  直通三出口（PhysKeyBridge 增拦 dispatchGenericMotionEvent，TouchpadModule 增 feedPointer）
- **三指滑动手势（反馈#8 Q2）**：GestureEngine 三指阶梯判定 —— ≥60px 主轴明确为滑动
  （上=Alt+Tab、下=Win+D、左右=Ctrl+Win+←/→），[12,60) 保留中键按住、点按仍为中键
- **横屏底部分页栏常驻（反馈#9）**：横屏不再隐藏分页栏（原只能四指滑，从通知栏进来不便）；
  底部 104/90dp 留白两朝向都保留（消除 v217 横屏“留白没栏”白带），选中胶囊横屏也滑动，
  旋屏自动重算胶囊位置
- **悬浮球（反馈#7）**：独立 overlay 触控板窗口 —— 不依赖前台 Activity 焦点，
  HyperOS 小窗失焦不派发输入的绕行方案；小球单击展开/收起、拖动移位、长按关闭，
  底部 300dp 面板直接喂触控板链路（单/双/三指全支持），入口在设置页「工具」小节
- **状态页并入设置（反馈#10 批 A）**：4 页 → 触控板/键盘/设置 3 页；状态块（总开关/
  传输开关/链路诊断/运行摘要）平铺进设置页顶部，顶栏「状态↔设置」分段移除，
  四指循环/侧边限宽/页签映射/标题数组全链路收编（StatusFragment 寄生渲染零改动）
- **设置页分类列表化（反馈#10 批 B，方案 B）**：原「通用/外观/遥控/关于」顶部分段
  改为 5 行分类列表（新增「帮助与说明」）→ 点行进二级页 + 返回行回首屏；
  新增 groupHelp 帮助组（项目说明/链路与延迟/手势与操作/常见问题四卡），
  进设置页一律落回分类首页
- **长描述收进帮助页（反馈#10 批 C）**：状态页「功能说明」九行与「三步上手」三行
  原文搬进帮助组新卡（功能说明 / 三步上手）；boxIdleFeatures 保留透明空壳
  （Kotlin 整卡引用：找不到会 ensureViews 失败 / lateinit NPE），三步卡无 id 直接删；
  单行提示与运行时诊断语境文字保留
- **触摸板光标锁定（批 E · issue#44）**：设置页新增「触摸板光标锁定」开关
  （默认开启，pref=cursor_lock），Pad 上系统光标不再随触控板移动、防误触；
  仅关本地 pointer，PC 转发不受影响
- **光标锁定底层 gate（批 F · issue#44）**：连上电脑且开关开时本窗隐藏系统光标
  （PointerIcon.TYPE_NULL，状态边沿去重），断开/关开关自动恢复；连上时触摸板事件
  本就全消费、不落本 app 控件；窗口外（其他 app/桌面）的系统光标与点击为系统级
  行为，App 无权干预（根治需 root evdev grab，后续另立批次）
- **LocalSend 互传接入（批 G · issue#45 MVP1）**：新模块 localsend（协议参数层/
  极简 HTTP server/接收端前台服务），UDP 多播 224.0.0.167:53317 发现 + register 应答，
  与官方 LocalSend 各平台互通；设置→遥控·工具 新增「互传」开关（默认关），
  文件落系统 Download/LocalSend（MediaStore，免权限），完成/失败均有通知
- **LocalSend 发送端（批 H · MVP2）**：新增 LocalSendClient（三步推送 register→
  prepare-upload（预算 sha256）→ 逐文件流式 upload）与 SendActivity 发送页；
  互传行点行进发送页（设备列表来自 announce，2s 刷新，点设备选文件多选）；
  触控开关保留为接收开关——开关=接收、点行=发送
- **深色模式取色修复（批 K）**：设置页分类/行/帮助卡与互传发送页的硬编码黑色
  文字全部换主题 token（md_on_surface / md_on_surface_variant，项目按 4.5:1 对比度
  校准）——修深色下黑字看不清；悬浮球字体问题随球体样式批一并修
- **悬浮球实体化（批 M）**：球体 35% 黑半透明 → 95% 白实底 + 描边（深浅背景都醒目），
  48dp→56dp（触控目标更好点），中心点改深色；修实测「太小/像一个点」
- **诊断日志包（批 L · 临时，下版移除）**：键盘/指针/motion 三入口逐事件打点
  （keyCode/usage/mod、source/action/双指轴、焦点与分支结果）——用于定位实体键盘
  功能键、双指手势、触控板点击误触三类实测问题（logcat tag=ApxBridge）
- **手动导出日志（批 O · issue 建议）**：设置→关于与维护 新增「导出运行日志」，
  一键把本进程最近 5000 行 logcat + 设备头存为 Download/AllPeriph/log_时间戳.txt，
  反馈问题随 issue 附上即可，免接线 logcat
- **USB HID 背压修复（批 N · issue#46 日志实锤）**：host 未取走上一报告时旧实现
  直接丢帧（日志高频「写入被拒」）——现失败帧合并进 pending（位移/滚轮/按钮/媒体键，
  溢出保留），下帧自动并入 + 16ms 补发重试，连续 1s 被拒才放弃；实体触摸板位移、
  双指滚动、点击不再因背压丢失
- **横屏操作条 pill（批 P · issue 实测）**：撤回#9 底栏常驻（恢复对称隐藏，竖屏不受
  影响）；右上新增 pill 条——触控板/键盘显式切换 + 方向锁 + 设置，替代「四指滑是
  唯一换页方式」；原悬浮设置键并入 pill（永隐防重叠）
- **设置页长文收编（批 Q · issue 实测「文字说明太长」）**：通用/外观/工具组的 4 个
  说明 caption（模板/配色/背景/诊断提示）置为不可见并**原文搬进**帮助页对应卡
  （功能说明/手势/链路），分组页只留控件本体；诊断输出与内嵌选择器保留（功能不减）
- **虚拟手柄/摇杆修复（原生符号缺失 · issue#43 真机日志）**：`apx_uinput.c` 是 C 源文件，
  而 app 的 CMake 工程只 `LANGUAGES CXX` —— CMake 无法把 `.c` 映射到已启用语言，
  直接**静默跳过**：编译链接全绿、CI 全绿，但 `libapx.so` 里一个 UinputGamepad 的
  JNI 符号都没有（实测产物符号表为 0），运行期才报
  `No implementation found ... UinputGamepad.openGamepad() is the library loaded?`，
  摇杆与手柄按钮全走降级路径。修法：`project(apx_jni LANGUAGES C CXX)`（tv 工程本来就是 C，
  所以只有手机端中招）。并新增 CI 关卡「Verify native JNI symbols in APKs」——
  构建完直接拆 APK 验符号，这类"编译通过但产物缺东西"的问题以后拦在 CI 里
- **UI 一致性收尾 + 自检清单**：把仅剩的几处字面色值收进色板（主按钮按下态
  `md_primary_pressed` 此前写死 `#FF2B6BE0` 不跟主题；桌面小组件与副屏浮层的固定色
  也进 `values/` 并注明「刻意不随主题」的理由）；布局里 10 处硬编码中文文案移入
  `strings.xml`（横屏操作条复用既有 `ui_*` 资源，不重复造词）；悬浮球面板「收起」补到
  48dp 触控下限。新增 `docs/UI-CHECKLIST.md`（XML Views 版自检清单）与
  `scripts/check_ui_tokens.py`（CI 关卡：字面色值 / 硬编码文案，两类编译器都不报的问题）
- **悬浮球面板深色取色（issue#44）**：展开的触控板面板底色是 `bg_card`（= `md_surface`，
  深色下变暗），而标题/提示/收起三处文字写死 `#DD000000` / `#66000000` / `#88000000` ——
  深色模式下黑字压深底，整块面板看不清。改用 `md_on_surface` / `md_on_surface_variant` /
  `md_primary` 令牌
- **修 run#594 编译失败 + 失败可见性改进**：3 个文件（被控输入面板 / 磁贴 / 输入法面板）
  引用了 `R.string.*` 却没 `import com.allperiph.R` —— 本地没有 Android SDK，这类错误只有
  CI 编译才暴露。补 import，并在 `scripts/check_ui_tokens.py` 加关卡「用了 R.* 却没有
  import <namespace>.R」（按模块 namespace 判定，`android.R` 不算）。
  同时把构建失败的错误上报从 check-runs API 改成 **workflow command**（`::error::`）：
  原实现先用 gh api 查 check-run id 再 POST，实测在 job 进行中返回 404、annotation 一直没写上去；
  workflow command 由 GitHub 自动收集成 annotation，不需要任何 token 权限
- **修 run#598 编译失败 + 新增两道本地可查的关卡**：瘦身时删掉了 `boxRunning` 的
  lateinit 声明与绑定，但 `boxRunning.visibility = ...` 还在 → `Unresolved reference`
  （两处）。这类问题编译器能报，可**要等一整轮 CI**；现补两道检查提前到本地：
  ① `R.id.xxx` 必须在布局里有 `@+id/xxx`；② `小写字段.visibility/text/isChecked…`
  这类用法，其字段须在同一文件声明过（只对本文件已有 lateinit var 的文件生效，
  避免误报）。两条都做过负向测试
- **设置页横屏两栏 + manifest 注释对齐（实机反馈）**：横屏时设置页从「整宽堆叠」
  改为左 1/3（状态概览 + 传输按钮 + 分类/返回）、右 2/3（当前分类的功能分组），
  由 `MainActivity.applySettingsTwoPane()` 只改容器 orientation 与两个 pane 的
  LayoutParams（视图实例不变，绑定/滚动/分类跳转都不受影响）；竖屏退回纵向堆叠。
  另修正 manifest 里 4 处指向**并不存在**的 `layout-land/activity_*.xml` 的注释
  （只有 activity_main 真有横屏布局，其余页面的横屏适配都在代码里）
- **状态块瘦身 + 传输开关改按钮 + 手柄页强制横屏（实机反馈）**：
  ① 撤掉「运行摘要 / 连接状态 / 跟手程度」三张卡（信息并入顶部一行），
     首屏让给真正的操作入口；链路诊断五行搬进「通用」分组的 `rowsLinkHost`（逻辑一行没改）
  ② 无线 / 蓝牙 / USB 从「标签 + Switch」大卡片行改成三个胶囊按钮，点一下开关，
     开启 = 主色实底、关闭 = 中性描边；「开哪个弹哪个」的功能分组逻辑保持不动
     新状态从 `isTransportEnabled` **回读**（开 USB 可能失败，取反会骗人）
  ③ 手柄页 `screenOrientation` 由 `sensor` 改 `landscape` —— 它的坐标本来就是按
     800×360dp 横屏核算的，竖屏进来会挤成一团；manifest 里那条指向
     `layout-land/activity_gamepad.xml` 的注释也一并改对（该文件并不存在）
- **新增 R.id 引用关卡**：代码里 `R.id.xxx` 必须在布局里有 `@+id/xxx`。
  这类问题编译器不报，只在运行时 `findViewById` 返回 null 后炸/永不刷新 ——
  本次瘦身就真踩了一次（删了 rowsLink 字段却在 buildLinkRows 里继续用）
- **修旋屏崩溃：横屏转回竖屏必崩（真机复现）**：`TouchpadSplit.restore()` 里
  `page.removeAllViews()` 只摘直接子节点（left/divider/right），而 topRow/touchArea/
  chipSection/scroll 仍挂在 left/right **内部**，紧接着 `page.addView(topRow)` 抛
  `IllegalStateException: The specified child already has a parent` → 崩在
  `ViewTreeObserver.dispatchOnGlobalLayout` 里。修法：重挂前一律先 `detach()`，
  并把「任何 addView 之前该视图已无父」写成该类不变式（split/restore 两处对称处理）。
  影响面：横屏→竖屏第一次切换即崩，App 直接退出
- **间距令牌化（issue#42「排版混乱」）**：布局 padding/margin 此前 14 种取值，收敛为
  `values/dimens.xml` 的 8 级梯度（space_2/4/6/8/12/16/20/24），归一化 28 处（10→8、14→12、
  18→16、3→4，均不超过 2dp 且以减小为主）；给悬浮底栏留的底部空白此前三页分别是
  90/104/96dp（注释还写着 88），合并为语义 token `inset_bottom_nav`
- **Kotlin 侧色值令牌化**：被控输入面板 7 色、`md_error`（删除/失败红，深色镜下提亮）、
  色点选中描边（深色下改亮描边）、桌面小组件 3 色、磁贴与 OK 按钮 —— 全部进色板；
  并修掉两处作用域错误（widget 的形参是 `ctx` 不是 `context`；`dotBg` 里没有 `act`）
- **关卡升级**：新增「布局 dp 字面量间距」硬规则；中文文案存量改**棘轮**
  （基数 536，涨则 `::warning::`），并修正统计范围只算 main 源码
- **Kotlin 侧 UI 文案入资源**：对话框标题与按钮（快捷键管理 / 自定义键盘布局 / 删除文件 /
  恢复默认…）、字段标签、EditText hint、Toast 提示、被控输入面板说明、快捷设置磁贴标签
  共 55 处从硬编码中文改为 `strings.xml` 资源（按钮词复用既有 `ui_common_*`，百分号按
  Android 规则写 `%%`）。`scripts/check_ui_tokens.py` 改为分层统计：UI 层目标 0，
  数据层（core/wireless 等无 Context 的设备名与状态串）单独列为已知例外
- **深色模式取色修复（issue#44）**：副屏 / 手柄 / 文件 / 快捷 / 本地发送 / 被控输入
  6 个 Activity 与悬浮球此前**没接** `ThemePref`，用户选「深色」时它们仍按系统 uiMode
  取色（亮底亮字看不清）——现在全部接上同一套注入，悬浮球配色也令牌化
  （`ball_bg` / `ball_text` / `ball_stroke`，深色镜下自动反转），球体 56→60dp、图标 16→18sp
- **CI 失败可见性（tooling）**：Android 构建失败时把编译错误摘录写进 check-run annotation
  （公开仓库匿名可读，logs API 要 admin 权限），并上传 build-log-android artifact；
  repo-edit 推完提交后自动 `workflow_dispatch` 点一次 Build —— GITHUB_TOKEN 推的提交
  不触发 push 工作流，此前云端编辑的代码没人编译（批 S-1 错误即由此漏出）
- **CI 步骤抗抖动（tooling · run#589 实测）**：`sdkmanager` 偶发下载损坏
  （`Error reading Zip content from a SeekableByteChannel`）时退出码 1 会把整个 android job
  判失败、后续步骤全跳过（只差一个包却让整轮白跑）。改为每个包独立重试 3 次，
  最后以**目录存在性**判定（ubuntu-latest 镜像自带大部分组件，装不上≠用不了）
- **全能外设输入法（批 S · issue 实测失焦输入）**：新增 InputMethodService
  （ApxImeService）——切到本输入法后物理键盘在任意界面打字全量转发到电脑
  （forwardFromIme 跳过本地输入态放行），没连电脑自然降级为手机打字；
  manifest 声明与设置引导入口随批 S-2
- **Header 标题防挤竖排（issue#43 图1 · 批 D）**：顶栏标题/副说明加
  singleLine+ellipsize——右侧按钮拥挤时不再逐字竖排（v218 散落字符根因，
  v219 已删 segPages 分页钮大幅缓解，本条为防复发双保险）

---

- **三指手势遮蔽（root 动态；无 root 走手动兜底）**：触控板里三指滑动会先被系统当成截屏 ——
  手指还没抬起来，截图就已经存进相册了。真机核对：误触源是 system 的
  `three_gesture_down=screen_shot`（三指下拉截屏）与 `three_gesture_long_press=partial_screen_shot`
  （三指长按区域截屏），而 `three_gesture_screenshot` 本来就是 0，动它没用。新增
  `touchpad/GestureShield`：进触控板时（主页触控板页与独立操控面各自持有，内部引用计数）
  用 root 把这两个键临时写成 `none` 并**回读校验**，退出／切页／退到后台立刻还原原值；
  还原失败不静默——写入前先把原值落到 apx prefs 并置 active 标记，**进程启动时
  （App.onCreate）无条件自愈**一次，异常被杀也不会把用户手机的三指截屏永久改掉。
  开关在 设置 → 交互开关 → 三指手势遮蔽（默认关），点行看说明；无 root 写不进去时
  一次性提示走兜底：手动关掉系统三指下拉（设置 → 更多设置 → 快捷手势 → 截屏），
  截图改用音量下 + 电源键。

## v217（2026-10-25）

### 新增
- **物理蓝牙键盘接入（全仓库首次）**：`PhysKeyBridge` 用 `Window.Callback` 包装拦截 KeyEvent，
  经 `HidKeys` / `Uplink` 三出口上行；`ensureViews` 接线、`onDetach` 还原（e7cc350、fc3a71a）

---

## v216（2026-10-24）

### 新增
- **横屏触控板页运行时左右分栏**：`TouchpadSplit` 在 configChanges 下旋屏不重建，
  布局 pass 幂等重排；`ensureViews` 注册分栏监听（d8be865、297eda0）
- 修复转录遗漏的 SystemClock import（32efb56）

---

## v215（2026-10-24）

### 新增
- **横屏触控板页左右分栏**：左 2/3（顶部操作行 + 手势面撑满），右 1/3（快捷键），不放方向键（cb54bed）

---

## v214（2026-10-24）

### 内部
- 横竖屏改造（PR #41）合并后发版清理：删除一次性补丁脚本与工作流（83a4145、c8a402c）

---

## v213（2026-10-24）

### 新增
- **方向锁定按钮 + 横竖屏界面优化**：顶栏 / 设置双入口、锁定暂停轮询与物理检测、
  横屏状态 / 设置页限宽居中（ba5f46a，PR #40/#41）
- CI 新增一次性锚点编辑应用器（用完自毁；后常驻为 `repo-edit`，本条回填即由它完成）

---

## v212（2026-10-24）

### 修复
- **朝向循环事故止血（两核满频 / 10W）**：恢复被损坏的 MainActivity、`forcedOrient` /
  `lastOrientSwitchMs` 跨实例保持、`orientPoll` 3s re-request 冷却、被掰回 30s 放行重试（PR #39）
- **macOS 打包签名校验三连**：`codesign --deep` / `--strict` 对「exe 旁 web/」结构必误报，
  改为签名时 web 场外签完放回 + plutil 校验（06fbc7c、8e77b59、b7b27bf）

---

## v211（2026-10-24）

### 新增
- **macOS（arm64）测试版首次发布**：Release 自此带 `AllPeriph-<tag>-macos-arm64.zip`
  （含 `apxdesktop` 桌面壳 + `apxhost` + `web/` 前端），包内附未签名放行与授权指引
  - **浏览器控制面板（方案 B）**：`apxhost panel` / `./apxdesktop` 起本地 HTTP（仅 127.0.0.1）
    自动打开 `?host=1` 页面——协议与 Windows WebView2 面板同构（state/act/query/事件四通道），
    前端 `bridge.ts` 三态传输（webview / http / mock），Windows 路径零改动（PR #30）
  - **媒体通道 9502 跨平台**：connect/收发流/5s 拥塞重试去 Win 圈（6 处等价变换，Win 逐字保留），
    面板媒体泵自动建链；**麦克风桥 CoreAudio AudioQueue**；**音箱 CoreAudio Process Tap**
    （macOS 14.2+，首次需「系统音频录制」授权；DRM 保护内容可能采集为静音，系统侧行为）（PR #31/#32）
  - **9511 受控端 + APX1PC 信标**：`apxdesktop` / `apxhost panel` 自带受控端，手机「TV 控制」
    设备列表可发现这台 Mac（标为 `(PC)`）；9512 收文件 + 面板文件卡事件刷新（PR #33）
  - 开机自启（`~/Library/LaunchAgents/` plist）、媒体收流→麦克风接线、CI 纳入 apxdesktop 编译
- **发版链补 mac（PR #34）**：macos.yml 加 `tags: ['v*']` 触发，zip 改名
  `AllPeriph-<tag>-macos-arm64.zip` 上传 Release（与 build.yml release job **双向幂等**：
  谁先完成谁建 Release、后到者 `--clobber`）；build.yml Release 正文新增 macOS 章节
- **`.app` 壳（双击入口 + TCC 授权宿主）**：zip 内含 `AllPeriph.app`——strip 后统一
  ad-hoc 补签（arm64 执行要求），`plutil -lint` + `codesign --verify --strict --deep` 进 CI；
  Info.plist 挂 `NSMicrophoneUsageDescription` 与 `NSAudioCaptureUsageDescription`
  （macOS 14.2+，官方键），麦克风/系统音频的授权从此按 bundle 归属记录——此前 CLI 裸跑
  无 Info.plist 宿主的授权未知项关闭。壳 v1 无托盘菜单（退出 `pkill -x apxdesktop`），
  NSStatusItem 托盘随后续版本（需 NSApp 事件循环，运行时语义待实机）

### 文档（PR #35）
- README：产物表 `AllPeriph-vXXX-*` 五件套（替换过时 v186 手写表，`vXXX` 占位防再过时）、
  5 分钟上手加 mac 路线、源码构建加 mac 三步、已知限制加 macOS 边界条目
- `docs/使用说明.md`：8 处 mac 增量（三端表 / 安装小节 / 快速开始入口 / 被控电脑端信标 /
  文件·副屏·音箱边界 / FAQ×4 / 卸载 / 构建 / 九章入口拆 Win·mac 两节）；
  修正「Web 控制台 v117 起已下线」过时表述（Windows 桌面面板 / **macOS 另行采用浏览器面板**）

### macOS 已知边界（如实降级，非缺陷）
- 副屏不支持（虚拟显示器仅 Windows，面板显示不支持并明确报错）；发送本机文件不支持
  （浏览器无系统文件对话框；接收不受影响）；全局热键 / USB 有线暂不可用；
  无托盘菜单（`.app` 壳退出暂用 `pkill -x apxdesktop`）；ad-hoc 签名未公证；
  TCC 授权宿主已就位（Info.plist），实际弹窗归属待真机验证

---

## v210（2026-10-23）

### 修复
- **国内网络检查更新 / 下载（PR #25）**：Android `UpdateChecker.kt` 与 PC `apxhost check-update` 直连 GitHub 失败（超时/被拦）自动回退 `gh-proxy.com` 镜像（404 视为真实无 Release，不回退）；更新弹窗新增「国内高速下载」主按钮（gh-proxy 直链 `AllPeriph-<tag>-app.apk`，打不开自动退回原 Release 页），原「查看更新」页保留为次出口

---

## v209（2026-10-23）

### 新增
- **NCM 传输通道接入（Issue #14 代码侧收口）**：`TransportKind::Ncm` 工厂分支；Auto 链改为 WinUSB → NCM → Loopback（预检到网卡才尝试 NCM，无网卡不白等 accept 超时）；`apxdisp --transport ncm`（`--host` 客户端 / 省略监听，`--port` 可覆盖默认 9512）；自检新增 [7] NCM 段（PR #23）
- **Windows 真手柄注入（ViGEmBus 可选）**：0x07 手柄帧经虚拟 X360 设备注入游戏/Steam；运行时探测 `ViGEmClient.dll`，未装驱动自动回退原键盘降级，链接期零依赖；L2/R2 映射满按模拟扳机、摇杆 Y 轴方向对齐 XInput（PR #24）
- **PC 端检查更新**：`apxhost check-update` 走 WinHTTP 读 `releases/latest`，比对算法与手机端 `UpdateChecker.kt` 一致（去 v 前缀、按非数字切段、缺段补 0）；退出码 0=已是最新 / 3=发现新版本 / 1=检查失败（PR #24）
- **意见反馈 + 检查更新（关于分段）**：反馈按类型分流到 GitHub Issues（bug / 功能建议模板带 labels）；检查更新读仓库 `releases/latest`，与本机版本按数值段比较，有新版弹窗跳 Release 页下载（`ui/UpdateChecker.kt`）
- **发版流程同源**：PC 端版本号（面板关于页 / `apxhost --version` / 卸载项 `DisplayVersion`）改为发版 tag 构建时注入 `APXPC_VERSION_STRING`，与 App `versionName`、安装包版本、Release 标签完全一致；新增《发版手册》[`docs/RELEASE.md`](./RELEASE.md)

### 改进
- 设置页结构分组（通用 / 显示 / 触摸板 / 声音 / 触感 / 蓝牙 / 实验功能 / 关于），各项补说明文案；删除无后端接口的「删除账号」
- `docs/ROADMAP.md` 六处旧账对齐源码：待补表已实现（`reportKeyboard`/`reportGamepad` v184 即存在）、`TcpControlChannel`/`TcpCtrlBridge` 已移 `.attic`、`InputSink` 方案改判为已由 `core/Uplink.resolve` 满足

### 修复
- **蓝牙键盘出口**：`HidKeys.send` 长期写死 `btConnected=false` 且无 `Uplink.BLUETOOTH` 分支 —— 电脑配对蓝牙后触控板 / 多媒体 / 手柄正常，唯独按键与复制粘贴等组合键发不出去；现取 `BtHidDevice.isConnected` 真值并接入 `reportKeyboard`（rid 2）
- 主页 `isBtConnected()` 写死 `false` → 返回真实连接状态

---

## v0.3.2

维护性版本：移除「摄像头（UVC 免驱 webcam / Wi‑Fi JPEG 上行）」模块的**全部残留**代码、
UI 引用、权限与文档，并修正此前夸大该能力的说明。

### 移除
- **安卓端**：删除 `CAMERA` 权限、JNI UVC 实现、摄像头协议常量、模块标识、
  ConfigFS 布局、死代码目录 `src/disabled/camera/*`
- **PC 端**：清理面板/媒体层/信令中的摄像头注释残留
- **文档**：README 去除「四张媒体卡/实时预览」等不实描述（更正为三张：副屏/音箱/麦克风）

### 兼容性
- 无协议版本变更、无 breaking change，覆盖安装即可

---

## v0.3.1

在 v0.3.0 基础上的体验强化与问题修复。

### 新增
- **副屏/扩展屏面板直调**：投屏目标切换、分辨率四档热切换、码率三档实时调节、状态自动记忆
- **摄像头全面升级**：H264 硬编码（CPU 趋近零）、手机端参数调节、两端联动
- **手机端**：竖持=触控板、横持=键盘（物理朝向检测，系统方向锁定不影响）

### 修复
- 扩展屏运行中改分辨率花屏
- 扩展屏触摸不跟手
- 桌面镜像模式下触摸失效
- 手机端键盘入口在部分 MIUI 场景丢失
- 摄像头帧队列积压卡顿

---

## v0.3.0

副屏全面升级 + 手机端全新改版 + 电脑面板媒体全家桶。

### 亮点
- **触摸回传**：手机副屏画面上直接点、滑，电脑光标实时跟随；双指点按 = 右键
- **真·扩展屏**：配合虚拟显示器驱动（IddCx），电脑多出一块独立屏幕
- **扩展屏指针合成**：画面中实时合成鼠标指针
- **防闪烁**：修复虚拟屏静态时不出帧导致的画面闪烁，推流稳定 ~20fps
- **手机端全新改版**：状态页总开关升级为无线/蓝牙/USB 三个传输开关
- **电脑面板媒体全家桶**：副屏/音箱/麦克风/摄像头四张功能卡

### 已知限制
- 手机当会议软件麦克风需装虚拟声卡（如 VB-Cable）
- 摄像头当前为面板内预览，作为系统摄像头设备待后续版本
- 蓝牙键盘与手柄在后续版本补全
- USB 有线模式需要手机 root

---

*MIT 开源 · 零第三方依赖 · 所有通信仅限局域网，不上传任何数据*
