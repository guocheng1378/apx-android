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
| `INPUT_TEXT` | `0x26` | B→A | `[flags:u8, text_len:u16, text:utf8]` | 实时输入文本 |
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

| 文件 | 改动 | commit |
|------|------|--------|
| `ApxFrame.kt` | 新增 0x25/0x26/0x27 帧类型 + pack 方法 | `faa3e7e` |
| `TcpControlServer.kt`（TV） | 处理 0x25/0x26/0x27 帧 | `eff266a` |
| `TvControlServer.kt`（手机被控） | 处理 0x25/0x26/0x27 帧 | `0fe8dd2` |
| `TvControllerClient.kt`（手机控TV） | 新增 requestInput/sendInputText/sendInputDone + 接收回调 | `2436e25` |
| `ApxAccessibilityService.kt`（TV） | 焦点检测 → 发 REQUEST_INPUT | `7ceb1db` |
| `RemoteInputOverlay.kt`（TV） | 远程输入覆盖层 UI | `f986886` |

---

## 四、剩余工作

| 线 | 剩什么 | 难度 |
|----|--------|------|
| 线 2 | 手机端 `MainActivity` 复用 `RemoteInputOverlay` | 低 |
| 线 3 | 手机端 `ApxAccessibilityService` 同样加焦点检测 | 低 |
| 线 4 | 手机端删除 `PAGE_KEYBOARD` 和 8 套键盘布局 | 中 |
| PC 端 | `ctrl_channel.cpp` + `panel_win32.cpp` 加远程输入 | 高 |
