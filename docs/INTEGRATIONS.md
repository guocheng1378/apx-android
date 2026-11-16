# 四项外部集成方案评估

> 范围：Sunshine（含内网穿透）、scrcpy、LocalSend、bada。
> 结论先行：**LocalSend 已全量落地（Android + PC 端收发均完成，见 §3）**，scrcpy 与 Sunshine 适合「托管 + 引导」而不是重写协议，bada 只值得做一次可行性验证。
> 本文只做评估，不含实施；工作量按「熟悉本仓库的一个人」估算，单位为工作日。

---

## 0. 仓库现状（先给证据，再谈方案）

| 集成项 | 仓库内证据 | 判定 |
|---|---|---|
| Sunshine / Moonlight | 全仓 0 处引用 | 未开始 |
| scrcpy | 仅 `pc/display/transport/tcp_transport_win.cpp` 两处注释（对照其「多 socket」设计取舍与发送缓冲策略），无任何代码依赖 | 未接入，只是借鉴过思路 |
| LocalSend | **两端收发均已完成**：Android 端 5 个文件（`LocalSendProtocol` / `LocalSendHttpServer` / `LocalSendClient` / `LocalSendService` / `SendActivity`，协议按官方 v2.2 实现：多播 224.0.0.167:53317、`/info` `/register` `/prepare-upload` `/upload` `/cancel`，设置页有开关，issue#45）；PC 端收发 + 面板入口 + `apxhost localsend` / `localsend-send` 子命令（`pc/host/src/localsend/`，明细见 §3） | **已完成（Android + PC 收发、双向互通）** |
| bada | 全仓 0 处引用 | 未开始 |

也就是说，四项里唯一动真格的 LocalSend 已经全链路打通：手机 ↔ 本工程 PC 端互通，也与官方 LocalSend 桌面版互通。

---

## 1. Sunshine（副屏 / 远程串流）

**它是什么**：自建 Moonlight 兼容串流主机，用 NVENC / AMF / QSV / VAAPI 硬编码，Moonlight 客户端解码。成熟、延迟低、有现成的内网穿透方案（基地版自带，通用做法是 frp / Tailscale / Zerotier）。

**和本项目的关系**：本工程的副屏走自研链路（手机 `VideoReceiver` 单 transport + 按 streamId 分发，PC 侧 `FrameWriter` 丢旧保新、64KB 发送缓冲），已经为延迟做过取舍。Sunshine 是另一条完全独立的通路，不共享这套帧协议。

**两条路**

1. **托管 + 引导（推荐）**：PC 端不做协议，只做「检测是否安装 → 一键启动 → 给出配对 PIN / 二维码 → 手机端跳转 Moonlight 或内置引导页」。
   - 工作量 1–2 天（进程管理、防火墙规则、配置目录读写、二维码）。
   - 代价：体验是「外挂」，两套 UI 并存；但零协议风险。
2. **手机端内置 Moonlight 客户端**：接配对、RTSP 握手、视频解码、输入回传。
   - 工作量 15 天以上，且与现有自研推流重复造轮子。
   - 不建议，除非目标就是「用 Sunshine 取代自研副屏链路」。

**内网穿透**：可以做，但**不要默认开**。公网串流意味着视频要过中继，带宽成本、延迟抖动和隐私面都会变差；做成显式开关 + 明确提示，默认只走局域网。

**结论**：做档 1；档 2 不做。

---

## 2. scrcpy（手机 / TV 互控）

**它是什么**：独立可执行程序，把 `server.jar` 推到设备、走 adb 通道传输，**没有稳定的嵌入式 API**（协议无正式文档，server 版本与客户端强绑定）。因此「接入」在工程上只有两种含义：外挂托管，或者自己重写客户端。

**和本项目的关系**：本工程已有 USB / 无线 HID 注入与自研触控、键盘通道，不需要 adb、不需要开发者选项，这是相对 scrcpy 的优势；scrcpy 的优势是「免 root 看画面」「手机不用装 App」。

**三条路**

1. **外挂托管（推荐）**：PC 端检测 adb / scrcpy，把本项目已发现的设备序列号传给它，一键拉起独立窗口（可带 `--turn-screen-off`、`--stay-awake` 等参数），只管进程与参数，不碰协议。
   - 工作量约 1 天；风险是两套窗口并存，需要说清「投屏用 scrcpy、操控用本工程」。
2. **内嵌画面**：复用其 server + 自己实现视频/控制协议，等于重写客户端；server 升级即可能失效。工作量 10 天以上，不建议。
3. **只借无线 adb 配对**：用 `adb pair` / `adb connect` 做零配置引导，之后仍交给 scrcpy 或本工程。可作为档 1 的增强（多 1 天）。

**结论**：做档 1（可叠加档 3）；档 2 不做。

---

## 3. LocalSend（文件互传）

**现状最接近可用**：Android 端协议层完整，`LocalSendHttpServer` 已经能收（`prepare-upload` → `upload` → `cancel`），`LocalSendClient` 已经能发，`SendActivity` 是发送界面，`LocalSendService` 是前台接收服务。也就是说手机侧不需要再动协议。

**PC 端 + 面板入口均已补齐**（`pc/host/src/localsend/local_send_receiver.cpp` + `sha256.cpp`）：
多播收发、五个端点、落盘「下载/AllPeriph」、sha256 校验、`apxhost localsend` 子命令；
协议层单测 + 真开端口的集成测试（prepare-upload → upload → 落盘 → 摘要校验）都已在 CI 门禁里。

**面板里的「互传」卡**（本轮接线，三个入口同构）：

| 入口 | 后端 | 前端 |
|---|---|---|
| `apxhost panel`（macOS / Linux / Windows HTTP） | `pc/host/src/ui/panel_http.cpp` 的 `localsend` 段 + `localsend.toggle/status/openDir` | `pc/web` 同一份 dist |
| `apxdesktop`（Windows，WebView2） | `pc/host/src/ui/web_panel.cpp` 同上三个动作 | 同上 |
| `apxdesktop`（macOS，走 HTTP 入口） | 同 `apxhost panel` | 同上 |

- 状态字段契约由 `apxpc::localsend::statusJson()` 单点产出，`testStatusJson` 钉住字段名/类型；
  前端 `HostState.localsend` 照此声明 —— **改名要两边一起改**。
- 起停：`HostBundle.localSend` 非空时随面板启动（失败只告警、不阻断面板，卡片上能重试），
  退出前 `stop()`；未接线时 state 里给完整的「已关闭」形态，避免前端读到 `undefined`。
- 收到文件：托盘气泡 + `on-localsend-received` 事件（`{path,size,from,via:"localsend"}`），
  前端卡片据此显示「X 发来文件」。
- 落盘目录显式取 `FileReceiver::defaultDir()`，与「文件传输」面板同一目录 —— 互传来的文件
  在那里直接可见、也删得掉，不各写一份默认值。
- `openDir` 打开目录：macOS/Linux 用 `fork`+`execlp("open"/"xdg-open")`，Windows 用
  `ShellExecuteW`，都不经 shell。

**PC → 手机 / PC → 官方客户端（本轮补齐发送端）**：`apxpc/localsend/local_send_sender`，
与 Android 端 `LocalSendClient` 三步同构（`register` → `prepare-upload`（带 sha256）→ `upload`），
`upload` 用 POST（官方 dto_v2 同时收 POST/PUT，省掉重试分支）。

| 用法 | 入口 |
|---|---|
| 命令行 | `apxhost localsend-send <对端IP>[:端口] <文件…>`（带百分比进度） |
| 面板 | 互传卡里每台设备右侧的「发送」→ 系统文件对话框 → 后台线程推送，结果走 `on-localsend-sent` 事件 |

- 发送端**不依赖本机接收端在跑**，也不需要先「发现」对方：目标用 `ip:port` 直接给，
  唯一前提是对端在听 53317（官方 LocalSend 或本 App）。
- 取消语义：进度回调返回 false 即中止，并**尽力**向对端发 `cancel`，免得对面把半截文件
  当完整文件留着。
- 测试 `pc/host/tests/localsend_sender_test.cpp`：纯函数（fileType 表、prepare 报文契约、
  应答解析）+ 真端口跑一遍假对端（只实现五个端点），验证发出去的字段、query、
  Content-Length 与内容摘要，以及取消真能中断且发出 cancel、目标不可达/文件不存在如实报错。

**三条路**（历史评估，档 1 **已落地**——上文「本轮接线」即其实现）

1. **PC 端实现接收端（推荐）**：UDP 多播收发 + `HttpServer`（同样五个端点）+ 落盘与进度 UI。协议已经由 Android 端验证过，照同一套端点写即可。
   - 工作量 3–5 天（网络 + Win 防火墙 + 端口 53317 占用处理 + 中文文件名/大文件）。
   - 收益：一次投入，与官方 LocalSend 全平台互通（协议是开放的）。
2. **内嵌 / 调用官方桌面版**：打包省事，但端口 53317 会冲突、两套进程割裂，且分发体积变大。不推荐。
3. **维持现状 + 文档说明**：0 工作量，但「本工程 PC 端」这条链路永远不通。

**结论**：做档 1。这是四项里**投入产出比最高**的一项，因为协议侧的工作已经付过了。（已按档 1 落地，见本节开头。）

---

## 4. bada（近场分享 / AirDrop 兼容）

**现状**：0 引用，且目标本身需要先定义清楚 —— 这类项目是对 Google 近场分享（Quick Share / Nearby Share）的兼容实现，并声称兼容 AirDrop。

**必须先接受的两个硬约束**

- **Nearby Share 的发现与配对依赖 GMS**。非 GMS 设备（很多国内 ROM、部分 TV）不可用，不能当通用能力承诺。
- **AirDrop 兼容属逆向实现**，Apple 侧协议一变就可能失效；对外只能描述为「尽力兼容」，不能写进功能列表当承诺。
- 许可证要先看：GPL / AGPL 会传染到闭源分发，需在动手前确认。

**两条路**

1. **入口级接入（先验证）**：确认对方是否导出可被 `Intent` 唤起的分享 Activity，能唤起就只做「分享入口 + 结果回传」。
   - 验证成本 0.5 天（真机装一次、`dumpsys package` 看 exported Activity、试 `ACTION_SEND`）。
   - 若能唤起，接入 1–2 天。
2. **fork 核心协议**：工作量与合规风险都高（逆向代码 + GPL），不建议排进近期里程碑。

**结论**：只做档 1 的可行性验证；验证不通过就明确记为「不支持」，不要悬着。

---

## 5. 排序与理由

| 顺序 | 项目 | 档位 | 工作量 | 主要风险 |
|---|---|---|---|---|
| 1 | LocalSend | PC 端接收/发送 | 3–5 天 | 端口 53317 占用、防火墙、大文件与中文名 |
| 2 | scrcpy | 外挂托管（+ 无线 adb 引导） | 1–2 天 | 两套 UI 并存、adb 版本差异 |
| 3 | Sunshine | 托管 + 配对引导 | 1–2 天 | 安装检测与配置目录差异；穿透默认关 |
| 4 | bada | 仅入口可行性验证 | 0.5–1 天 | GMS 依赖、逆向协议不稳定、许可证 |

**为什么不按「看起来最酷」排序**：LocalSend 的协议成本已经在 Android 端付完了，PC 端是纯增量；scrcpy 与 Sunshine 都只需进程级托管就能交付可用体验，重写协议则会把维护成本永久绑到别人的内部协议上；bada 的可行性还没验证，先花半天确认「能不能唤起」，比直接排期更省事。

**明确不做的**：手机端内置 Moonlight 客户端、内嵌 scrcpy 协议、fork bada 核心。三者都属于「把别人的私有协议变成自己的长期维护负担」。

---

## 6. 需要决策的点

1. LocalSend 的 PC 端要不要顺带做「发送」（不只是接收）？只做接收约 3 天，双向约 5 天。
2. scrcpy 托管是否需要把 adb 一起打包（免用户装 platform-tools）？打包会让分发体积 +8MB 左右，但能省掉一大半支持成本。
3. Sunshine 的内网穿透要不要做「一键开启」？默认关、显式开是底线，但要不要提供托管的中继配置需要单独评估成本。
4. bada 验收标准：能唤起分享面板算通过，还是要做到「实际传完一个文件」？后者依赖对方实现，不保证。
