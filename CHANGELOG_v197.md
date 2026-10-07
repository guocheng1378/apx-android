# v197 Changelog (2026-10-07)

## 🔧 Bug 修复
- **蓝牙 HID 报告格式**：BtHidDescriptor fallback 补全 Mouse/Keyboard/Consumer 三个 TLC 的 Report ID 声明；reportMouse/reportKeyboard/reportGamepad 去掉 data 中重复的 Report ID 字节（sendReport 第二个参数已负责）
- **光标浮层边界约束**：TvOverlay.move() 加 coerceAtMost 防止光标飞出屏幕
- **被控设备真实名称**：TvInjector 从固定 'APX1TV' 改为读取 Build.MODEL

## ✨ 新功能
- **剪贴板同步**：手机复制→PC/TV 粘贴，ClipboardSync 监听剪贴板变化，通过 TCP 通道自动发送
- **桌面小组件**：AllPeriphWidget 显示连接状态+设备名，点击跳转 MainActivity
- **快捷设置 Tile**：下拉通知栏一键查看全能外设连接状态
- **通知同步**：手机通知推送到 TV 浮窗，NotificationListenerService + 通知队列

## 🎨 UI/UX 改进
- **TV 光标放大**：10dp → 24dp，4K 电视远距离可见
- **快捷键按下反馈**：HotkeyBoard 按钮 pressed 态背景色+缩放动画
- **文件传输进度条**：TvFileActivity 新增可视化 ProgressBar
- **页面切换动画**：startActivity 后添加 liquid_in/liquid_out 过渡
- **TV 文件列表增强**：根据扩展名显示图标（🖼️/🎬/🎵/📄/📦），大小格式化为 KB/MB/GB
- **焦点描述符补充**：TV 端所有可交互元素添加 contentDescription

## 🏗️ 架构重构
- **MainActivity 拆分**：108KB 单文件拆为 5 个独立 Fragment
  - StatusFragment：状态页（连接信息/设备列表/轮询）
  - SettingsFragment：设置页（模板/主题/FIX/TV-PC 控制）
  - TouchpadFragment：触控板页（手势/光标/快捷键网格）
  - KeyboardFragment：键盘页（三套键位面板）
  - FileFragment：文件面板页
- **ConnectHistory 持久化**：从内存改为 SharedPreferences + JSON

## 📦 依赖
- 共享模块（:shared）无变更

## ⚠️ 注意
- 剪贴板同步需要在 MainActivity 中手动集成 ClipboardSync.register/unregister（见注释）
- 快捷设置 Tile 需要 Android 7.0+ (API 24)