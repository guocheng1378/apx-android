# 远程输入（Remote Input）实现规划

> 任何设备的输入框获焦 → 对端设备自动弹出系统键盘 → 打字实时注入。
> 手机↔手机、手机↔TV、手机↔PC 统一架构。

---

## 一、架构总览

```
设备 A（输入框在哪）                    设备 B（键盘在哪）
┌──────────────────┐                  ┌──────────────────┐
│ EditText 获焦     │    0x22 帧       │                  │
│ AccessibilitySvc  │ ─────────────→  │ 收到 REQUEST_INPUT│
│ 检测到焦点        │                  │ 弹 EditText      │
│                  │    0x23 帧       │ 系统键盘自动弹出   │
│ ApxImeService    │ ←─────────────  │ 用户打字          │
│ .commitText()    │   实时字符       │ TextWatcher      │
└──────────────────┘                  └──────────────────┘
```

**关键原则：不造键盘，用系统键盘。**

---

## 二、协议新增

### 2.1 新增帧类型

| 帧类型 | ID | 方向 | Payload | 说明 |
|--------|-----|------|---------|------|
| `REQUEST_INPUT` | `0x22` | A→B | `[hint_len:u8, hint:utf8]` | 请求 B 设备输入 |
| `INPUT_TEXT` | `0x23` | B→A | `[flags:u8, text_len:u16, text:utf8]` | 实时输入文本 |
| `INPUT_DONE` | `0x24` | B→A | 无 | 输入完成 |

### 2.2 INPUT_TEXT flags

| bit | 含义 |
|-----|------|
| 0 | `INCREMENTAL` = 增量字符（默认） |
| 1 | `BACKSPACE` = 退格 |
| 2 | `COMMIT` = 完整文本 |
| 3 | `CANCEL` = 用户取消 |

---

## 三、模块改动清单

### 线 1：协议 + 帧处理
- `shared/` 加帧类型常量
- `ApxFrame.kt` 加 pack/parse
- `TcpCtrlBridge` 加 requestInput/sendInputText/sendInputDone
- `TcpControlServer` (TV) 加 0x22/0x23 帧处理
- `TcpControlChannel` (手机) 加 0x22/0x23 帧处理

### 线 2：TV/手机端输入覆盖层
- TV/手机 MainActivity 新增 RemoteInputOverlay
- EditText + 发送/取消按钮
- TextWatcher → 实时发 INPUT_TEXT

### 线 3：AccessibilityService 焦点检测
- `ApxAccessibilityService.onAccessibilityEvent` 加焦点检测
- 检测 EditText 获焦 → 发 REQUEST_INPUT
- 防环：remoteInputMode 标记

### 线 4：手机端横屏简化
- 删除 PAGE_KEYBOARD 和 8 套键盘布局
- 横屏 ViewFlipper 只保留触控板页

---

## 四、测试矩阵

| 场景 | A（输入框） | B（键盘） | 验证点 |
|------|------------|----------|--------|
| 手机→手机 | 手机 A EditText | 手机 B 系统键盘 | 实时注入、退格、中文 |
| 手机→TV | 手机 A EditText | TV 系统键盘 | 同上 |
| TV→手机 | TV EditText | 手机系统键盘 | 同上 |
| PC→手机 | PC 搜索框 | 手机系统键盘 | Ctrl+` 触发 |
| PC→TV | PC 搜索框 | TV 系统键盘 | 同上 |
| 防环 | A 输入 → B → A 不回推 | — | 不形成死循环 |
| 横屏触控板 | 手机横屏 | PC 光标 | 触控板功能不受影响 |
