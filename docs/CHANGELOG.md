# 全能外设 更新日志

> 详细用法见 [`docs/使用说明.md`](./使用说明.md)；发版流程见 [`docs/RELEASE.md`](./RELEASE.md)。
> **版本线**：v0.4.0 起变更明细随 **GitHub Releases** 随 tag 自动发布（tag 为真源）；
> 本文件维护 Unreleased 与重要节点（v0.3.x 历史见下）。

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
  无 `.app` 包与托盘（终端运行，`Ctrl+C` 退出），TCC 授权归属待真机验证

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
