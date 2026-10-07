# v197 Changelog (2026-10-07)

## Bug 修复
- 蓝牙 HID 报告格式：BtHidDescriptor fallback 补全 Report ID 声明；reportMouse/reportKeyboard/reportGamepad 去掉 data 中重复的 Report ID 字节
- 光标浮层边界约束：TvOverlay.move() 加 coerceAtMost 防止光标飞出屏幕
- 被控设备真实名称：TvInjector 从固定 'APX1TV' 改为读取 Build.MODEL

## 新功能
- 剪贴板同步：手机复制→PC/TV 粘贴，通过 TCP 通道自动发送
- 桌面小组件：AllPeriphWidget 显示连接状态+设备名
- 快捷设置 Tile：下拉通知栏查看连接状态
- 通知同步：手机通知推送到 TV 浮窗

## UI/UX 改进
- TV 光标放大 10dp→24dp，4K 电视远距离可见
- 快捷键按钮 pressed 态反馈动画
- 文件传输进度条
- 页面切换 liquid 过渡动画
- TV 文件列表图标+大小格式化
- 焦点描述符补充

## 架构重构
- MainActivity 108KB 拆为 5 个 Fragment（Status/Settings/Touchpad/Keyboard/File）
- ConnectHistory 持久化（SharedPreferences）