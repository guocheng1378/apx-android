# 远程输入（Remote Input）实现规划

> 任何设备的输入框获焦 → 对端设备自动弹出系统键盘 → 打字实时注入。
> 手机↔手机、手机↔TV、手机↔PC 统一架构。

---

## 一、架构总览

```
设备 A（输入框在哪）                    设备 B（键盘在哪）
┌──────────────────┐                  ┌──────────────────┐
│ EditText 获焦     │    0x25 帧       │                  │
│ AccessibilitySvc  │ ─────────────→  │ 收到 REQUEST_INPUT│
│ 检测到焦点        │                  │ 弹 EditText      │
│                  │    0x26 帧       │ 系统键盘自动弹出   │
│ ApxImeService    │ ←─────────────  │ 用户打字          │
│ .commitText()    │   实时字符       │ TextWatcher      │
└──────────────────┘                  └──────────────────┘
```

**关键原则：不造键盘，用系统键盘。**

---

## 二、协议新增

### 2.1 新增帧类型（与 0x20 剪贴板/0x22 电源动作不冲突）

| 帧类型 | ID | 方向 | Payload | 说明 |
|--------|-----|------|---------|------|
| `REQUEST_INPUT` | `0x25` | A→B | `[hint_len:u8, hint:utf8]` | 请求 B 设备输入 |
| `INPUT_TEXT` | `0x26` | B→A | `[flags:u8, text_len:u16LE, text:utf8]` | 实时输入文本 |
| `INPUT_DONE` | `0x27` | B→A | 无 | 输入完成 |

### 2.2 INPUT_TEXT flags

| bit | 含义 |
|-----|------|
| 0 | `INCREMENTAL` = 增量字符（默认） |
| 1 | `BACKSPACE` = 退格 |
| 2 | `COMMIT` = 完整文本 |
| 3 | `CANCEL` = 用户取消 |

---

## 三、已实现的改动

### 手机端（App 模块）

| 文件 | 改动 | commit |
|------|------|--------|
| `ApxFrame.kt` | 新增 0x25/0x26/0x27 帧类型 + pack 方法 | `faa3e7e` |
| `TvControlServer.kt` | 处理 0x25/0x26/0x27 帧（被控模式） | 多个 |
| `TvControllerClient.kt` | requestInput/sendInputText/sendInputDone（控 TV 模式） | `2436e25` |
| `ApxAccessibilityService.kt` | 焦点检测 → 发 REQUEST_INPUT | `7ceb1db` |
| `RemoteInputActivity.kt` | 手机端远程输入覆盖层 UI | 原有 |
| `ControlledService.kt` | 接线 onRemoteInputRequest → 启动 RemoteInputActivity | 原有 |

### TV 端（TV 模块）

| 文件 | 改动 | commit |
|------|------|--------|
| `TcpControlServer.kt` | 处理 0x25/0x26/0x27 帧 | `eff266a` |
| `ApxAccessibilityService.kt` | 焦点检测 → 发 REQUEST_INPUT | `7ceb1db` |
| `RemoteInputOverlay.kt` | TV 端远程输入覆盖层 UI | `f986886` |
| `TvServerService.kt` | 新增 sendInputText/sendInputDone 转发 + 接线回调 | `561987a` |
| `MainActivity.kt`（TV） | RemoteInputOverlay 回调接线 + 首页 UI 补全 | `d4fdc46` / `be7dcfe` |

---

## 四、剩余工作

| 线 | 剩什么 | 难度 | 状态 |
|----|--------|------|------|
| 线 4 | 手机端删除 `PAGE_KEYBOARD` 和 8 套键盘布局 | 中 | 待做（85K 文件，需手动操作） |
| PC 端 | `ctrl_channel.cpp` + `panel_win32.cpp` 加远程输入 | 高 | 未开始 |

---

## 五、BUG 修复记录

| 问题 | 修复 | commit |
|------|------|--------|
| `TvControlServer.onRequestInput()` hintLen 偏移错误（body[5]→body[1]） | 与 `TvControllerClient.requestInput()` 格式对齐 | `6818074` |
