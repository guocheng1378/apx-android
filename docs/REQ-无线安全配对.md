# REQ 无线安全配对（2026-11-15 方案稿 —— token 全链路已随 2026-11-15 批落地）

> ⚠️ **§4 加密（R2）已整体作废（2026-11-18 用户拍板「去除加密」）**：两端 Noise、
> 白名单、首连确认 UI、0x35 握手、CAP_ENCRYPT、信标第 5 段均已删除（见 31dc685），
> 现状恒明文直连，仅保留令牌校验。下文 §4 及相关「落地标注」按历史设计稿阅读，
> 不再反映当前实现。

> 状态：**已拍板并部分落地**。本文档来自 2026-11-15 无线控制面安全审查（R1–R4，见
> `egm_review/wireless_security_review.md`）。其中小改动已随本批直接落地（见 §2），
> token 分发链路（TOFU 版）也已随 2026-11-15 批落地（见 §3 标注）；§4 加密（R2）
> 已完成选型调研（§4.1，2026-11-17，推荐 Noise 方案 C，待拍板），落地与首连确认 UI
> 仍为后续批次。

## 1. 背景与现状

三端共用 9511 控制面（PC `ctrl9511.cpp` / 手机 `TvControlServer.kt` + `ControlServer.kt`）、
9512 文件通道（`FileReceiver` / `FileSender`）、9502 媒体通道（`MediaChannel`）。审查结论：

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
| R4 余项确认 | 9512/9502 通道 | ~~本批不动~~ → **已随 2026-11-17 R2 子批接通**：文件（9512）与媒体（9502）token 链路已接（PSK = 9511 链路被控端令牌，对端静态公钥沿用 9511 TOFU），加密态认证即 §5 所求；§5 已收口（见该节标注） |

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
> —— 限制③更新（2026-11-17 第二批）：R2 白名单已强制（§4.4 落地标注），「拿到
> 信标 token 即握手通过」的窗口已关 —— 手机端未收录公钥会弹确认，PC 端白名单
> 非空即拒绝；首连确认 UI 已在手机端落地，PC 端为白名单 + 托盘气泡。

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

token 解决「谁在连」，不解决「链路上有什么」。输入流、剪贴板、通知正文都值得加密。

> **2026-11-17 更新：已按方案 C（Noise）落地 9511 控制面子批 —— NoisePSK_IK_25519_ChaChaPoly_SHA256，
> prologue `"APX1-9511-v1"`，PSK = SHA-256(UTF-8(token))；0x35 握手帧 + `CAP_ENCRYPT`(bit8) +
> 信标第 5 段（Noise 静态公钥）已进协议（docs/PROTOCOL.md §3.3，v1.10）。
> 两端实现：PC `noise_session.cpp`（noise-c）、Android `NoiseSession.kt`（noise-java）。
> TV / 旧版对端 / 手动连接无信标公钥时走明文兼容路径。9512 / 9502 通道加密
> **已于 2026-11-17 子批落地**（见 §4.3）。**

### 4.1 选型调研结论（2026-11-17）

四类候选对比：

| 方案 | PC 端集成点 | Android 端集成点 | 与 token 衔接 | 主要风险 |
|---|---|---|---|---|
| **A. 系统栈 TLS+PSK**（原首选） | SChannel；PSK server 场景支持弱（未获正面证据）；OpenSSL 则引新依赖 | `PskKeyManager`（API 21+）存在，但 Conscrypt 对 PSK 套件在近版本的实际支持未证实；Android 10 起 TLS 套件不可定制 | token 兼作 PSK | 三平台三后端各自踩坑，实际成本最高 |
| **B. mbedtls 统一 TLS-PSK** | CMake 可集成（FetchContent 有先例），Apache-2.0，TLS 1.2 PSK 有官方最小配置（`config-ccm-psk-tls1_2.h`） | 需 NDK 跨 JNI（项目有 libapx JNI 先例） | token 兼作 PSK | 体量大；JNI 桥接层工作量 |
| **C. Noise 双端参考实现**（推荐） | noise-c（rweather，纯 C 零依赖，MIT，可编 C++） | noise-java（同作者，纯 Java/JCE，无需 NDK） | token 作 PSK；若选 IKpsk2 可将对端 static 公钥白名单一并预置 | 两端各引一库（同规范同作者，语义一致）；握手状态机自管 |
| **D. 帧级 AEAD 自实现**（原退路） | XChaCha20-Poly1305 + ECDH 自组合 | 同左 | 配对时 ECDH 协商 | 自造协议状态机，违背「不造自己的密码学」，不推荐 |

**推荐方案 C**，理由：

1. 两端用同作者（rweather）按同一 Noise 规范做的参考实现，PC 纯 C 零依赖、
   Android 纯 JVM 无需 NDK，MIT 许可，与「允许第三方依赖但先核许可」的规矩兼容。
2. `Noise_IKpsk2`（WireGuard 同款模式）与现网形态天然匹配：对端 static 公钥认证 +
   PSK 混合 + 前向保密。配对确认通过后随配对帧交换对端 static 公钥，TOFU 即从
   「拿到信标 token 就能握手」升级为「static 公钥白名单」——token 不再是唯一凭据。
3. 0x33 能力协商位图有现成挂点（`CAP_PAIRING` 已预留，2026-11-11 先例），协商位
   启用加密即可兼容 TV（TV 维持无认证明文，与 §3.2 取舍一致）。
4. 相比之下方案 A 经核验：SChannel 对 PSK server 的支持未获正面证据、Conscrypt 对
   PSK 套件近版本支持不明——「两端系统栈」的预设不成立，反而成本最高。

### 4.2 需拍板项

- **Noise pattern 选型**：
  - `IKpsk2`（倾向）：必选预置对端 static 公钥——首连确认通过后随配对帧下发，
    重连握手即完成认证；TV 等无认证对端不参与加密协商（能力位关闭，维持明文）。
  - `XX+psk`：无预置公钥也可握手，认证弱于 IKpsk2；仅当公钥下发链路嫌复杂时退选。
- **信标 token 保留**：继续明文广播（TOFU 过渡形态不变，见 §3.3）；加密启用后其
  意义退化为「发现提示」，不作为凭据；明确不依赖「信标 token」做密钥分发。

### 4.3 落地切分

沿用 §6 第三批：握手帧新增 + 9511/9512/9502 三通道统一加密封装 + 0x33 能力协商位
（`CAP_ENCRYPT`）。协议帧改动四处同源（见 §7）。

> **2026-11-17 落地进度：9511 / 9512 / 9502 三个子批均已完成**。9511 子批（0x35 +
> CAP_ENCRYPT + 信标第 5 段 + 两端 NoiseSession 封装与接入，见 §4 头注）；
> **9512 / 9502 子批**：文件与媒体通道沿用同一 Noise 协议栈与同款「明文帧头 +
> 密文 payload」封装接入（9512 连接后首 u32=0 分流 + 段流、9502 令牌握手后 PC
> 延迟探测），PSK 源均为 9511 链路的被控端令牌，对端静态公钥沿用 9511 TOFU；
> 明文兼容路径不变（TV / 旧版对端 / 手动连接照常），加密仅两端同步升级后生效。

### 4.4 残余风险核查（2026-11-17，v223.31.0 发版后复查）：Responder 侧 static 白名单未强制

> **落地标注（2026-11-17 第二批，用户拍板「按建议修」后随 v223.33.x 落地）**：
> 上述「白名单未强制」已修复，要点与取舍：
> - **白名单集合化**：手机端 prefs `apx_noise`/`trustedPeerPubs`（Base64 集合，含旧
>   `peerPubLast` 单值迁移，老用户升级不丢信任）；PC 端 config.json
>   `trustedPeerPubs`（64 hex 数组）。多台电脑控一台手机合法，9512 发起端预知
>   对端公钥改为按对端 host 取（`NoiseSession.loadPeerPubForHost`）。
> - **首连确认 UI（手机端）**：未收录公钥 → `PubConfirmDialogActivity` 弹确认
>   （允许=信任落盘，拒绝/15s 超时=断开，拒绝后 60s 静默）；信任落盘统一收口在
>   裁决放行时（`NoiseSession.pubAuthorizer` → app 模块 `PeerPubGate`，9511/9512/9502
>   三通道 Responder 与 9511 Initiator 共用）。
> - **PC 侧限制（如实记录）**：PC 无跨平台阻塞确认 UI（mac 无窗口环境），裁决收敛为
>   「白名单 + 托盘气泡」：命中放行；空表 TOFU 放行 + 气泡告知；非空陌生拒绝 +
>   60s 节流气泡。配对管理 UI（列表/撤销）为后续。
> - **明文帧门控（§4.4 同根，必做）**：被控端具备加密条件但 0x35 未握上时，除
>   0x33/0x35/ping 外控制帧一律丢弃（Android `ControlServer` / PC `ctrl9511.dispatch`
>   同款）——否则攻击者「不发 0x35 直接发明文」即可绕过白名单。
> - **顺带修复 PC 既有 bug**：`Ctrl9511Server::onNoiseHandshakeMsg1` 此前漏调
>   `split()`，csSend/csRecv 从未切出，PC 被控方向加密链路实际是死的（加密帧恒
>   被丢弃）；本批随白名单重排补上。
> - 协议帧无改动（0x35 语义不变），PROTOCOL.md 不动；**两端同步升级才生效**口径不变。

**结论：设计意图（§4.2「token 不再是唯一凭据」）在当前实现中未成立。** 局域网内
任何读到信标的设备仍可凭广播 token 完成加密握手并建立完整控制会话；加密只挡住了
被动嗅探（前向保密有效），主动连接门槛与 R1 修复前等效（都只验 token，而 token
就在信标里）。

核查依据（代码事实，两端对称）：

1. **信标仍明文广播 token**（`WirelessBeacon.kt` 第 4 段，每 1.5s 全网广播）——
   PSK 源（SHA-256(token)）对全网可见，§4.2「信标 token 保留」按计划保留。
2. **Responder 不校验发起方 static 公钥**：`NoiseSession.beginResponder(token, ownPriv)`
   不预设期望对端公钥（Noise IK 模式下发起方公钥由 msg1 传入，任何持 PSK 者均能
   完成握手）；握手成功后仅 `ControlServer.onPeerStaticPub → savePeerPub` 记录
   「最近一次」对端公钥（`peerPubLast` 单值，供 9512 发起端预知用），**只记录、
   不拒绝**。PC 侧 `ctrl9511.onNoiseHandshakeMsg1` 同构，无白名单门槛。
3. **攻击路径**：嗅探信标得 token → 以自造 static 密钥发起 0x35 → Responder 因
   PSK 匹配握手成功 → 获得与合法控制端等权的加密会话（输入注入 / 文件 / 通知等）。
   v223.33 进行中的「双设备接管确认弹窗」只挡活动会话被顶掉，空闲无会话时仍直连。

**修复需要设计取舍，本轮未擅自动代码**，待拍板：

- 白名单须存**集合**而非现单值 `peerPubLast`（多台电脑控一台手机是合法场景，
  单值门槛会把第二台合法设备锁在门外）；9512 发起端「预知对端公钥」逻辑需同步
  改为按对端取值。—— **已按此落地**（见本节顶部落地标注）。
- 首连确认 UI（§3.2 已知限制③，当时刻意推迟）与本门槛强耦合：无确认 UI 时
  「白名单为空 → TOFU 首连」窗口内，局域网攻击者仍可抢注首用记录。
  —— **手机端已随本批落地**（PubConfirmDialogActivity）；PC 端为气泡 + 白名单。
- 备选缓解：token 退出信标（改为扫码/手输分发）可先拔掉「全网可见 PSK」根，
  但改发现与配对交互，成本高于白名单门槛。—— **未选，维持信标 token**。

**建议路线**：下一批先做「白名单集合 + 空表 TOFU 首连 + 非匹配拒绝（拒绝时被控端
弹通知提示）」，随后再评估首连确认 UI 与 token 退信标的顺序。—— **已按建议路线
落地（2026-11-17 第二批）**，后续余项：PC 配对管理 UI、token 退信标评估。

## 5. 9512 文件通道认证（R4 余项）—— **已收口（2026-11-17）**

> **收口结论**：R2 加密子批落地后，9512 连接即 Noise_IKpsk2 握手（PSK = 配对令牌，
> 对端 static 公钥按 TOFU 记录），**认证先于任何文件数据**，R4 所求「校验通过才收
> 文件头」在加密态天然成立，无需再实现 §5 原拟的明文 token 握手（该握手会破坏旧版
> 互通，且新对端之间永远走加密态；明文态仅剩旧版对端 / 未配对两端，维持 §3.2 兼容
> 取舍）。真机回验项已并入回验清单（加密握手连通 + legacy 对端照常收发）。
> —— 勘误（2026-11-17 复查）：此处原写「对端 static 公钥白名单」认证，实际白名单
> 未强制（见 §4.4），加密态认证强度以 §4.4 为准。
> —— 勘误更新（2026-11-17 第二批）：白名单已强制（§4.4 落地标注），本节认证语义
> 与实现一致：9512 加密态 = Noise IK 对端静态公钥白名单 + PSK，陌生公钥拒绝收文件。

- 与 9511 共用配对 token：连接时先走同款「u32 长度 + token」握手（复用 `FileSender`
  协议头前面追加），服务端校验通过才收文件头；拒绝已在 §2 落地的 size 上限之前。
  —— **以上为原方案稿，已被 R2 加密握手取代（见上方收口结论），不再实施。**
- 跨版本兼容与 §3.2 同策略（`CAP_PAIRING` 协商）。

## 6. 实施切分（拍板后）

1. **第一批（无 UI）**：`0x34` 配对帧 + token 生成/存储（PC config、手机
   SharedPreferences）+ 发现层保留 token 字段（`TvDevice`、`parseBeacon`）+
   `CAP_PAIRING` 能力位协商，两端同版发布。
   —— **2026-11-15 批已完成其中 token 生成/存储与发现层保留（TOFU 版，无 0x34、
   无能力协商）；剩余 0x34 配对帧与 `CAP_PAIRING` 协商为可选后续（现方案不依赖）。**
2. **第二批（UI）**：首连确认弹窗（PC 面板 + 手机）、配对设备管理（列表/撤销）。
3. **第三批（加密）**：TLS/PSK 落地，9511/9512/9502 全链路。

## 7. 关联

- 审查报告：`egm_review/wireless_security_review.md`（R1–R4 原始发现）
- 协议帧新增需同步：`ApxFrame`（Android）、`ctrl9511.hpp`、`docs/PROTOCOL.md`、
  `scripts/check_protocol.py` 四处同源（0x41 先例）。
