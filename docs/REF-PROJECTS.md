# 同类开源项目参考（2026-11-10 成宇约定：动大改前先看已验证实现）

> 用途：大改动前先翻这里，找已验证的架构与方案借鉴，不闭门造车。
> 许可证注意：`pc/` 是 GPL-3.0，可吸收 GPL 代码；`android/`、`shared/` 是 MIT，**只吸收宽松许可证的实现**（如 scrcpy Apache-2.0、KDE Connect GPL-2+ 需谨慎——只能借鉴思路不能抄代码进 MIT 目录）。

## 一览

| 项目 | 做什么 | 与本项目的对应 | 许可证 |
|---|---|---|---|
| [scrcpy](https://github.com/Genymobile/scrcpy) | 电脑控制/显示手机画面 | 反向（我们做手机当外设），但**输入注入、虚拟 HID、编码推流**全对应 | Apache-2.0 |
| [InputLeap](https://github.com/input-leap/input-leap)（Barrier 活跃分支） | 软件 KVM，一套键鼠控多机 | PC 端输入注入与**连接安全**参考 | GPL-2.0 |
| [Deskreen](https://github.com/pavlobu/deskreen) | 浏览器当电脑副屏（WebRTC） | 副屏/投屏链路参考 | AGPL-3.0（只借鉴思路） |
| [KDE Connect](https://invent.kde.org/network/kdeconnect-kde) | 手机↔电脑互通全家桶 | 发现/配对/**插件化功能拆分**参考 | GPL-2.0+ |
| Virtual Display Driver（GitHub 搜 `Virtual-Display-Driver`，IddCx 系） | 用户态虚拟显示器驱动 | 副屏虚拟显示器（现有自研方案的备选已验证路线） | 以仓库为准 |
| [Sunshine / Moonlight](https://github.com/LizardByte/Sunshine) | 游戏串流（PC→客户端） | 已在用：mac 副屏出路、音频采集参考 | GPL-3.0 |
| [NectarLink](https://github.com/thtbee/nectarlink) | Android↔Windows/Linux 互通全家桶（通知/剪贴板/文件/相册投影/投屏/摄像头） | **重叠最高**的同类：能力表与缺口直接对齐（见下） | GPL-3.0（app）/ MPL-2.0（core） |
| [Vosk](https://github.com/alphacephei/vosk-api) | 开源离线语音识别（Kaldi 封装，中文模型 small-cn） | 语音键的**已验证依赖**（2026-11-11）：国内 ROM 无系统识别引擎，内置离线识别兜底（库+模型均 Apache-2.0） | Apache-2.0 |

## 逐项分析：已验证、值得借鉴的点

### scrcpy（Apache-2.0，与 pc/ 许可兼容，代码可直接吸收）

- **双进程模型**：客户端（C，电脑）+ 服务端（Java，手机），三个 TCP 通道（视频 / 音频 / 控制）各自独立。我们 9511/9502/9512 的分离口径一致，已验证：**控制与媒体分通道是对的**。
- **控制消息派发表**：`Controller.handleEvent()` 按 `TYPE_*` switch 分发到 `injectKeycode/injectTouch/injectScroll/...`，每种消息一个处理函数，读端 `ControlMessageReader` 严格按大端反序列化、带协议异常。**值得借鉴**：我们 0x07/0x32 等命令的收发散在多处，若再扩协议，可向「一个派发表 + 一个 Reader 类」靠拢（暂不动，扩协议时再议）。
- **UhidManager**：写 `/dev/uhid` 造虚拟键鼠手柄、HID 报告直写、`UHID_OUTPUT` 事件回传给客户端（LED 状态同步用）。我们 PC 端用 uinput 造设备是同思路；若日后做「手机端虚拟 HID」，这是现成参考。
- **CleanUp 子进程**：起一个 `app_process` 子进程盯着父进程的 stdout，父进程一死子进程恢复系统设置。**值得借鉴**：我们 root 改过 ConfigFS/系统设置后的恢复路径，目前靠编排层逆序 stop——进程被杀（ANR 之外的场景）时可参考这个思路加兜底。
- **OTG/AOA 模式**：不走 app_process，直接 libusb + AOA 协议做 HID 注入。对应我们「免 root USB」的潜在路线，暂未验证。

### InputLeap / Barrier（GPL-2.0，吸收思路；若抄代码只能进 pc/）

- **连接安全演进路线**（3.0 系列）：应用层握手失败必须关连接（防 fd 泄漏）→ 输入消息**强制最大长度**（防超长包耗资源）→ 客户端身份验证（防猜屏幕名改剪贴板）→ SHA256 指纹校验。**值得借鉴**：我们 9511 目前「局域网即信任」，若做鉴权，按这个顺序补：先堵「异常连接不崩」，再上「只认验证过的连接」。
- **剪贴板修复史**：UTF-16 代理对损坏、传输大小限制防拖垮连接。我们的「增量输入」同样要走剪贴板，**单次传输长度上限 + 代理对处理**是现成教训。

### Deskreen（AGPL-3.0，只借鉴思路，不抄代码进 MIT 目录）

- WebRTC P2P + 信令服务的分工：发现/协商走信令，媒体走 P2P 直连。对应我们 UDP 信标（9501）+ 媒体通道（9502）的分工，已验证一致。
- **自适应画质**：实时帧率分析 + 带宽检测 + 网络差时自动降分辨率。**值得借鉴**：我们副屏/投屏目前是「PC 端定分辨率」，手机端只报降级——若加自适应，按「降码率先于降分辨率」的次序。

### KDE Connect（GPL-2.0+，借鉴思路）

- **插件化功能拆分**：每个能力（剪贴板/通知/多媒体控制/文件共享/虚拟输入）是一个独立插件，各自声明权限与开关。我们 AgentController 的模块注册（gadget→audio→touchpad→bthid→wireless）已接近这个形态；新功能若要「各自可开关」，优先沿用模块注册制而不是散开关。
- **发现端口段固定（1714–1764）+ 防火墙文档**：排错体验好。我们 9511/9501/9502/9512 已固定，README 端口表同理。

### Virtual Display Driver（IddCx）

- 用户态 IddCx 间接显示驱动：可批量建虚拟显示器、自定义分辨率/刷新率/HDR、可指定 GPU、用户态运行蓝屏概率低。**结论**：我们副屏自研方案（pc/display）与之相比，IddCx 是更「正统」的已验证路线；若日后重做副屏层，优先评估 IddCx（引入前先核该仓库许可并记进本文件）。

### NectarLink（GPL-3.0 / MPL-2.0，且为 Rust 实现——只借鉴思路，代码不可直接进任何目录）

- **能力对照（2026-11-11 调研）**：重叠项——互传（对端兼容 LocalSend v2，断点续传 + BLAKE3 校验）、虚拟摄像头、PC→手机剪贴板、桌面快捷面板（对应我们悬浮球）。**它有我们没有**：① 通知同步（PC 弹手机通知、直接回复、同步消除、验证码一键复制、密码管理器内容过滤）；② 相册投影（MediaStore 相册投影到 PC：按天分组缩略图流式加载、原图按需取、拖拽导出、新照片 PC 通知）；③ 通话横幅与呼叫控制；④ 查找手机（静音也响铃）；⑤ App 窗口化（PC 上独立窗口开手机 App，走无线调试）。
- **值得借鉴的三点**：① 通知走独立 NotificationListenerService + 按「通知 key」记账实现双端同步消除，验证码单独通道；② 相册投影**按需取图**（元数据全量、缩略图懒加载、原图只在查看/保存时传，PC 端缓存带过期清理）——不整库上传的口径与隐私叙事一致；③ 每个能力一个独立开关（18 个按设备权限位），沿用我们模块注册制即可。
- **不追求的**：QUIC+TLS1.3/设备密钥体系（与我们「局域网即信任」的现状差距大，若日后做鉴权按 InputLeap 小节次序）；App 窗口化依赖无线调试，门槛高、与本项目「免开发者选项」定位不符，暂不跟。
- **连接栈（供对照）**：iroh 的 QUIC + TLS 1.3 端到端加密，Ed25519 设备密钥配对（二维码 / 6 位数字码 / USB 线三种），Windows DPAPI 与 Android Keystore 落盘；断网、休眠、网络切换后自动重连；可选移动数据中继（中继只见密文）。两端 UI：Windows 原生 Qt Quick，Android Compose（与我们同栈）。
- **架构（供对照，ADR 0002~0006）**：两端共用一个 Rust 核心 crate（协议/配对/会话/信任存储全在一处），经 `nectarlink-ffi` 统一暴露给 Qt Quick 与 Compose 两层薄 UI；线上格式 CBOR；每个功能一份协议文档 + **能力协商**（连接时交换能力表，功能按需启用）。**对照我们**：同一条协议在 Kotlin（逻辑层）与 C++（PC 端）各写一份实现，靠 `check_protocol.py` 常量校验兜底——正是共享核心要根治的漂移结构；但我们跨 Kotlin/C++ 无法整体抽核心，务实折衷是 ① shared/ 继续当「协议唯一真源」，② 新功能落地时把可协商字段加进握手（能力表），老版本新命令如实降级，不猜着发。

## 本项目已验证、不必重造的部分

- 控制面/媒体面/文件面分端口 + UDP 信标发现（与 scrcpy、Deskreen 同构）。
- 出站帧「队列 + 专用 writer 线程」（ui/README 线程约定，真机踩过 NetworkOnMainThread）。
- PC 端 uinput 造内核级真实设备（与 scrcpy UhidManager 同思路的 PC 侧实现）。

## 维护约定

- 引入新参考项目：先核许可证，再补「对应关系 + 可借鉴点」两行；抄代码只能进许可兼容的目录。
- 方案验证后（真正落地），在对应小节补一行「已采用：见 <文件/模块>」。
