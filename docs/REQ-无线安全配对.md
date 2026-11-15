# REQ 无线安全配对（2026-11-15 方案稿 —— token 全链路已随 2026-11-15 批落地）

> 状态：**已拍板并部分落地**。本文档来自 2026-11-15 无线控制面安全审查（R1–R4，见
> `egm_review/wireless_security_review.md`）。其中小改动已随本批直接落地（见 §2），
> token 分发链路（TOFU 版）也已随 2026-11-15 批落地（见 §3 标注）；§4 加密（R2）与
> 首连确认 UI 仍为后续批次。

## 1. 背景与现状

三端共用 9511 控制面（PC `ctrl9511.cpp` / 手机 `TvControlServer.kt` + `ControlServer.kt`）、
9512 文件通道（`FileReceiver` / `FileSender`）、9513 媒体通道（`MediaChannel`）。审查结论：

- **R1**：控制面默认零认证。两端装配处 token 恒传空串
  （PC `desktop_main.cpp` / `panel_http.cpp` `ctrlSrv.start(9511, "", …)`；手机
  `ControlledService` 构造 `TvControlServer` 不传 token），服务端「token 为空即跳过校验」
  —— 局域网内任何主机可连入注入输入、拉剪贴板、浏览文件。
- **R2**：APX 帧全程明文（输入 / 剪贴板 / 文件 / 通知），token 若有也在信标里明文广播
  （`"APX1PC <name> <port> <token>"`，UDP 9501）—— 明文广播的 token 对局域网攻击者
  没有保密性，只防「没听到信标」的场景，不能作为唯一防线。
- **R3**：mac 面板 HTTP API 无 Origin 校验 + `std::system(("open '" + url))` 拼接。
  **本批已修**（见 §2）。
- **R4**：9512 无鉴权且 size 无上限。上限**本批已加**；认证归入 §5。

### 1.1 关键调查结论（决定方案形态）

token 链路目前是**断的**，这正是不能「把 token 一填了事」的原因：

1. **两端连接方都不带 token**：PC 连手机 `parseBeacon()` 明确丢弃信标 token 字段
   （wireless_session.cpp「name/token 忽略」）；手机连 PC `TvDiscovery` 的 `TvDevice`
   同样不存 token，`TvControllerClient(ip, port)` 恒空 token。
2. **信标 token 明文广播**（R2）：把 token 放进信标等于发给全局域网，无认证价值。
3. 结论：token 要真正生效，必须先有**端到端的 token 分发 + 会话级加密**，即完整配对
   机制。单独生成 token / 单独启用校验都没有安全意义，还会破坏跨版本兼容。

## 2. 本批已落地（无跨端协议改动）

| 项 | 文件 | 改动 |
|---|---|---|
| R3 | `pc/host/src/ui/panel_http.cpp` | `app.openUrl` / `update.open` 白名单外加 shell 字符拒收（`'` `"` `` ` `` `\` `;` `$` `&` `\|` `<` `>` 换行）；`handleConnection` 入口 Origin 校验，非 `127.0.0.1` / `localhost` + 本端口一律 403 |
| R3 同模式 | `pc/host/src/api/action_router.cpp` | `update.open` mac 分支同款 `std::system` 拼接，同修法（shell 字符拒收）。`web_panel.cpp` 走 `ShellExecuteA` 不经 shell，无注入面，未动 |
| R4 | `pc/host/src/wireless/file_receiver.cpp` | 9512 声明 size 加 4 GiB 上限（与 `panel_http.cpp` 的 `kMaxUploadBytes` 同口径；合法大视频不误伤） |
| R1 铺垫 | `pc/host/src/wireless/ctrl9511.cpp` | ① 服务端配了 token 却收到空 token（tokLen=0）时显式拒绝（旧实现跳过校验直接放行，「配了也拦不住」；当前装配处 token 恒空，行为不变）；② `st.peer` 从 `"(peer)"` 占位改为 accept 时取真实对端 IP（sockaddr 现取） |
| R1 同模式 | `android/.../controlled/TvControlServer.kt` | 空令牌旁路同修（与 `ControlServer.kt` 的 v-fix 同款；`ControlServer.kt` 此前已有，未动，作为修法基准） |
| R1 同模式核对 | `android/shared/.../media/MediaChannel.kt` | **无需改动**：该文件无条件读令牌 buf，len=0 时空串必不过「不匹配」分支，无旁路；已留注释说明差异 |
| R1 主体 | `android/.../controlled/ControlledService.kt` | 手机被控端启用真实配对 token：`ControlServer` 构造传 token；信标第 4 段广播 token；`pairingToken()` 生成/存储（SecureRandom 16 字节 hex，SharedPreferences `apx_controlled`） |
| R1 主体 | `pc/host/include|src/config/app_config.*` | PC 配置加 `pairingToken` 字段（load/save）+ `ensurePairingToken()`（load→无则生成 128-bit hex→save） |
| R1 主体 | `pc/host/src/desktop_main.cpp`（Win）| 9511 受控端装配改传真实 token（`ctrlSrv.start(9511, token, host)`） |
| R1 主体 | `pc/host/src/ui/panel_http.cpp`（mac/Linux）| 同上，面板装配点改传真实 token |
| R1 主体 | `pc/host/src/wireless/wireless_session.cpp` + `.hpp` | `parseBeacon` 加 token 出参（信标第 4 段，旧信标兼容）；`onBeacon` 存 `beaconToken_`；worker 自动连接握手回带信标 token（手动连接无信标来源，保持空 token——限制见 §3.2） |
| R1 主体 | `android/.../wireless/TvDiscovery.kt` | `TvDevice` 加 `token` 字段并从信标解析（新/更新两分支均写） |
| R1 主体 | `android/.../ui/compose/ApShellActivity.kt`、`touchpad/TouchpadActivity.kt` | 控制端连接改传 token（`d.token`，空时按 IP 从发现表兜底查） |
| R1 同模式核对 | `android/tv/.../TvServerService.kt` | **无需改动**：TV 维持无认证（不传 token、信标传空），与 §3.2 取舍一致 |
| R4 余项确认 | 9512/9513 通道 | **本批不动**：文件（9512）与媒体（9513）token 链路未接（PC `MediaSession::connect` 传空、手机 `MediaChannel` 服务端 token 空），认证归 §5 |

> 空令牌拒绝是**为配对机制启用预留**：装配处仍传空 token（行为零变化），配对机制
> 落地并写入 token 后该防线即刻生效，无需再改握手代码。
>
> —— 上句仅描述前一批；**token 全链路已随 2026-11-15 批打通**（TOFU 版），见 §3
> 新增标注。

## 3. 配对机制（R1 主体：首连确认 + token 分发）

> **落地状态（2026-11-15 批）：token 分发链路已随本批打通（TOFU 版）** —— 两端被控
> 服务启用真实 token、信标第 4 段广播、发现层保留 token、控制端握手回带，全链路贯通。
> 已知限制（接受并注明）：① token 明文广播，对被动嗅探者无保密性（R2 加密为下一批）；
> ② 被控端启用 token 后，旧版控制端握手必拒——**跨版本需两端同步升级**（用户自用
> 设备可接受）；③ 首连确认 UI 未做，token 属「信任首用」（TOFU）：局域网内拿到信标
> token 的主机即可握手通过，保密性依赖 R2。

### 3.1 交互设计

- **首连确认**：新设备（IP 首次出现）连入 9511 时，被控端弹确认（PC 托盘气泡 + 面板
  弹窗；手机系统对话框），展示对端 IP + 主机名，用户选「允许 / 拒绝 / 总是允许」。
  拒绝即断开；允许则生成配对 token 下发。
- **token 生成与存储**：被控端生成 128-bit 随机 token（`SecureRandom` / CSPRNG），
  PC 存配置文件（沿用 `apxpc::config`，键 `wireless.pairingToken`），手机存
  `SharedPreferences`。只存哈希比对不必要（token 本身就是随机值，存储无明文密码风险）。
- **token 下发时机**：首连确认通过后，走**当前控制连接**下发（连接已建立但会话未激活，
  复用握手后的第一条帧，新增 `0x34` 配对帧），对端存住后用于重连握手。
- **重连**：已知设备（按 IP / 配对记录）直接带 token 握手，无感通过；换网 IP 变化时
  触发重新确认（可配置）。

### 3.2 跨版本兼容（必须评审）

> **2026-11-15 批实际取舍**：未加宽限期/能力协商位（方案 a/b 均未采用），直接启用
> token 校验——旧版控制端 × 新被控端握手必拒，**两端需同步升级**（用户自用设备，
> 接受此限制；发布时须同步发版两端）。

- 旧版对端不带 token：服务端启用 token 后空令牌一律拒绝（本批铺垫已生效）——
  **新被控端 × 旧控制端会连不上**。方案：
  - a) 信标加「已配对」标志位 + 宽限期（发布后 N 天内空令牌放行，仅记日志）；
  - b) 或 0x33 能力协商加 `CAP_PAIRING` 位：两端都报才启用 token 校验
    （推荐，与 0x41 通知回复的渐进启用先例一致）。
- TV 端（`TvControlServer` 同协议镜像）：**本批维持无认证**——`TvServerService`
  构造 `ControlServer(endpoint)` 不传 token、信标传空（无 UI 无存储），与新被控端
  token 校验不冲突（TV 信标无 token 段，控制端回带空 token，TV 服务端不校验）。

### 3.3 明确不做

> **2026-11-15 批修订**：原「不做信标带 token 认证」的结论随用户拍板调整——本批
> 实际采用**信标第 4 段广播 token + 握手回带**（TOFU 版）。明文广播的限制如实
> 写入 §3 落地状态：token 只防「没听到信标的被动连接」，对能收到广播的攻击者
> 无保密性，完整防线依赖 R2 加密。

- **信标带 token 认证**：明文广播（R2）使它对局域网攻击者无意义；最多作为「已配对」
  标志的载体，不作为凭据。（本批作为 TOFU 过渡已落地，见上方修订说明）
- **0x21 反向剪贴板按 caps 门控**：攻击者可协商 caps，门控无认证意义，已从范围内剔除。

## 4. 会话加密（R2）

token 解决「谁在连」，不解决「链路上有什么」。输入流、剪贴板、通知正文都值得加密：

- 首选 **TLS（两端系统栈：PC = 平台 SChannel/OpenSSL，Android = SSLSocket）**，
  配对确认后交换的 token 兼作 PSK，避免证书管理；9511/9512/9513 三通道同一套封装。
- 若引 TLS 体量超预算，退路为帧级 AEAD（XChaCha20-Poly1305，配对时经
  ECDH 协商会话密钥）——实现成本显著更高，仅在 TLS 不可行时启用。
- 明确不依赖「信标 token」做密钥分发（明文广播，见 §3.3）。

## 5. 9512 文件通道认证（R4 余项）

- 与 9511 共用配对 token：连接时先走同款「u32 长度 + token」握手（复用 `FileSender`
  协议头前面追加），服务端校验通过才收文件头；拒绝已在 §2 落地的 size 上限之前。
- 跨版本兼容与 §3.2 同策略（`CAP_PAIRING` 协商）。

## 6. 实施切分（拍板后）

1. **第一批（无 UI）**：`0x34` 配对帧 + token 生成/存储（PC config、手机
   SharedPreferences）+ 发现层保留 token 字段（`TvDevice`、`parseBeacon`）+
   `CAP_PAIRING` 能力位协商，两端同版发布。
   —— **2026-11-15 批已完成其中 token 生成/存储与发现层保留（TOFU 版，无 0x34、
   无能力协商）；剩余 0x34 配对帧与 `CAP_PAIRING` 协商为可选后续（现方案不依赖）。**
2. **第二批（UI）**：首连确认弹窗（PC 面板 + 手机）、配对设备管理（列表/撤销）。
3. **第三批（加密）**：TLS/PSK 落地，9511/9512/9513 全链路。

## 7. 关联

- 审查报告：`egm_review/wireless_security_review.md`（R1–R4 原始发现）
- 协议帧新增需同步：`ApxFrame`（Android）、`ctrl9511.hpp`、`docs/PROTOCOL.md`、
  `scripts/check_protocol.py` 四处同源（0x41 先例）。
