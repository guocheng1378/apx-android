# REQ-PTP：Windows Precision Touchpad 协议升级（v1.8 专项）

> 目标：把手机触控板从「手搓手势 + HID 鼠标模拟」升级为 **Windows Precision Touchpad（PTP）**，
> 手势全部交由 Windows 系统合成（双指滚/捏合/三指任务视图/四指桌面/掌压抑制/惯性物理原生获得），
> 并在 Windows 设置中出现「精确式触控板」标志与原生手势设置页。

## 一、架构变更

- 手机端只上报**原始多指触摸数据**（每指 id/x/y/tip/w/h），不再本地合成手势
- 保留现有 Mouse TLC（Report ID 2）做兼容回退；PTP TLC 新增 Report ID 16
- Windows inbox 驱动（HidUsb + PTP）自动接管：无需改 PC 端任何代码

## 二、Touch Pad TLC 结构（Report ID 16，微软 PTP 规范）

Usage Page (Digitizer 0x0D) → Usage (Touch Pad 0x05) → Collection (Application)：

| 字段 | Usage | 布局 |
|---|---|---|
| Report ID | — | 1B = 16 |
| Contact Count | 0x0D:0x54 | 1B，本帧触点数 |
| Scan Time | 0x0D:0x56 | 2B，**单位 100µs**，允许回绕（必需） |
| （每指 ×5 槽） | | |
| ├ Contact ID | 0x0D:0x51 | 1B，触点生命周期追踪 |
| ├ Tip Switch | 0x0D:0x42 | 1 bit |
| ├ In Range | 0x0D:0x32 | 1 bit |
| ├ Confidence | 0x0D:0x47 | 1 bit（触点 >25mm 置 0 = 掌压） |
| ├ Contact Valid | 0x0D:0x52 | 1 bit + 4 bit pad |
| ├ X | 0x01:0x30（跨页） | 2B，Logical 0..W-1，**分辨率 ≥300 DPI** |
| ├ Y | 0x01:0x31 | 2B，Logical 0..H-1 |
| ├ Width | 0x0D:0x48 | 1B（抬起帧须为 0） |
| ├ Height | 0x0D:0x49 | 1B |
| └ Pressure | 0x0D:0x30 跨页? | 可选（用 0x0D:0x30 会与 X 冲突——**Pressure usage = 0x0D:0x30 是错误记忆，以官方 sample 为准**） |
| Button 1 | 0x09:0x01 | 1 bit（Clickpad 集成键，恒 0 也须声明） |

Feature（同 TLC 内）：
- **Input Mode**（0x0D:0xC5）：1B Feature，Windows 切 0=鼠标 / 1=触控板模式
- **Contact Count Maximum**（0x0D:0x55）：1B Feature，声明 5
- **Button Type**（0x0D:0x59）：1B Feature，0=Depressible（Clickpad）
- **Latency Mode**（0x0D:0x60）：1B Feature（可选）
- **认证 Blob**（厂商页 0xFF:0xC5）：256B Feature——页面上有完整默认 blob 十六进制
  （以 `0xfc,0x28,0xfe,0x84,0x40…` 开头 `0x43,0xa5,0xe5,0x24,0xc2` 结尾），
  **下一步抓取官方 sample 页填入**（web_fetch 本轮故障未取到）

## 三、手机端

- TouchpadModule 新增 PTP 模式（Gadget 挂载后探测/默认启用）：
  MotionEvent → 每指 {id, x, y, tip, w, h} → 打包 Report 16 → sendInputReport
- 坐标：直接用手机屏幕像素（X Logical Max = width-1）
- Scan Time：SystemClock.uptimeMillis()/100 → 100µs 单位
- Confidence：触点 width>25mm（按 dpi 换算像素）置 0
- 保留现有鼠标帧路径（PTP 识别失败时回退），UI 加模式指示

## 四、验收

1. 设备管理器/设置 → 触控板显示「**精确式触控板**」
2. Windows 设置出现原生手势页（三/四指手势、滚动方向、灵敏度）
3. 双指滚动、捏合缩放、三指任务视图、四指桌面全部系统原生
4. 掌压抑制生效

## 五、风险与依赖

- 描述符必须逐 item 对齐官方 sample（差一个 usage/feature 即不认）——
  **前置：抓取 learn.microsoft.com「touchpad-sample-report-descriptors」页**
  （本轮 web_fetch 工具连续丢参故障未取到；官方页 URL 已知）
- 认证 blob 缺失时 Windows 可能标记非认证 PTP（功能可用但设置页有提示）——先不带 blob 上线验证
- f_hid 描述符上限 4096B：现有 1666B + PTP ~300B，余量充足
