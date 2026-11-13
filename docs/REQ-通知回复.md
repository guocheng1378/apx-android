# REQ 通知回复（0x41，2026-11-14 方案稿，实施等用户点头）

## 背景与现状

- 通知同步已做推送/消除双帧（0x40/0x42，能力位 bit0）：手机通知 → PC 托盘气泡，验证码命中自动复制。
- PROTOCOL §3.3 与能力位表里 **bit1 = 通知回复（0x41）为预留**：帧格式未定义、两端未实现。本方案补齐它。
- 价值：PC 气泡点开后直接打字回复手机上的消息（微信 / QQ / 短信 / Telegram 等），不用摸手机——与「验证码自动复制」同属「PC 端不掏手机」这条线。

## 技术路线（无第三方依赖）

Android 平台标准 API：`Notification.Action` + `RemoteInput`。通知使用权服务
（`ApxNotificationListener`，默认关、双开关已就绪）在收通知时缓存
`key → 可回复 action 的 RemoteInput Bundle`（会话级，重连不恢复，与 0x40 同命）；
PC 发来 0x41 时按 key 找回 action，程序性填充 RemoteInput 并 `pendingIntent.send()`——
等效用户在通知栏点了「回复」并打字，对目标 App 完全透明。

先查同类开源参考：该路径是平台 API 直用（Android `RemoteInput` /
`NotificationCompat.Action.Builder#addRemoteInput`），无第三方库引入，许可核验不适用；
KDE Connect 的 reply 通道同为 RemoteInput 注入（Apache-2.0），仅借鉴「找不到 action 时
明确失败回执」的交互（REF-PROJECTS 无新增条目）。

## 协议设计（§3.3 追加）

- `0x41` 通知回复请求：`PC → 手机`（手机控 PC / PC 控手机均可，走当前链路）
  `[0]=0x41 [1..2]=keyLen(u16 LE) [..]=key [..2]=textLen(u16 LE) [..]=text`（UTF-8；key 上限 512B 与 0x40 一致；text 上限 1KB——消息回复场景足够，超长应走输入互控）。
- `0x43` 通知回复回执：`手机 → PC`，应答式（成功也发帧——与文件取回「成功不发帧」不同，
  回复成功与否用户在等，必须给终态）
  `[0]=0x43 [1]=err(u8)`：0=已提交（RemoteInput send 成功，App 是否真的发出由它自己负责——
  这层无法证实，文案用「已回复（通过系统通知通道）」）；1=未开启通知回复；2=通知已不在
  （key 失效）；3=该通知不支持回复（无 RemoteInput action）；4=发送失败。
- 能力位 bit1 上报口径：手机端 `ApxNotificationListener` **已开启**才报 bit1（回复能力
  依赖通知使用权 + action 缓存）；PC 端恒报 bit1（发送端永远存在）。门控与 0x40 同款：
  发送前核对对端位图，对端报过 0x33 才拦、未报照发由旧版静默忽略兜底。

## 实施要点（第一批，一次交付）

1. **协议帧**：Android `ApxFrame` + PC `ctrl9511.hpp kCaps9511*` + PROTOCOL.md 三处同源（0x41/0x43、bit1）。
2. **手机端**：`ApxNotificationListener` 增加 action 缓存（key → RemoteInput action，LRU 上限 64、
   只缓存 `getRemoteInputs()` 非空的通知）；app 模块 `NotificationReply.handle` 落三条收帧路径
   （`ControlServer` / `TvControlServer` / `TvControllerClient`），TV 不接（同查找手机先例）；
   成功路径 = 填充 Bundle → `action.actionIntent.send()`；0x43 回执全路径必发。
3. **PC 端**：托盘气泡（已有）追加点击行为——气泡带「回复」按钮（Windows 托盘 API 支持），
   点击弹轻量输入框（WebView2 面板或原生小窗，实施时按现有模式选），提交即发 0x41；
   0x43 失败码 → 气泡回执原因。验证码气泡不挂回复按钮（无意义场景）。
4. **设置页**：通知同步开关旁补一行「允许在电脑上回复」（默认关，随通知使用权开关联动——
   不开使用权就没有缓存，单独开关反而误导；如实施时确认无需独立开关则并入正文说明）。

## 边界与限制（汇报口径）

- 各 App 对通知回复的支持不一：IM 类（微信 / QQ / TG / 短信）普遍支持；纯展示通知无 action，
  回 0x43 err=3「该通知不支持回复」。
- App 进程被杀 / 通知已被划走 → key 失效 err=2。
- 「已提交」不等于「对方收到」——回复经系统通知通道进目标 App，送达态它自己不回执，
  这是 RemoteInput 通道的天然边界，不是本功能缺陷。

## 分批与验收

- 第一批 = 上文 1–4 全量（协议 + 两端 + 回执链）。
- 验收：手机开通知同步与回复 → 任意 IM 收消息 → PC 气泡点回复 → 打字 → 对方收到、
  手机通知栏对应通知显示已回复；失败三态（err=2/3/4）气泡各有原因文案。
- 真机回验：微信/QQ 各一（RemoteInput 兼容性实差）；悬浮球/触控板回归照旧。
- 第二批（另立，不在本批）：PC 端通知中心（0x42 记账的状态 + 回跳/历史）。

## 不做的事

- 不做「在 PC 上直接调起手机输入法互控打字」（那是互控通道，已有）；
- 不做群聊选择/会话列表（回复永远绑定通知本身，防止做歪成半个 IM 客户端）。
