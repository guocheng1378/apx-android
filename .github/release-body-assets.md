<!--
  Release 正文的「资产说明」模板（build.yml 的 release job 使用）。
  正文结构 = docs/CHANGELOG.md 里对应版本的段（改了什么）+ 本模板（怎么用）。
  {{TAG}} 会在构建时替换成真实 tag（如 v223.9）。
  规则：每个 Release 的正文顶部必须写清「改了什么」—— 明细的唯一真源是
  docs/CHANGELOG.md 里对应版本的段；发版前没写那段，CI 会打 error 并降级为占位。
-->

### 推荐：单文件安装包（Windows）
下载 **AllPeriph-{{TAG}}-Setup.exe** —— 一个 exe 搞定一切：
- 双击进入现代 Windows 安装向导（欢迎 → 功能勾选 → 进度 → 完成）
- 默认装到 `%LOCALAPPDATA%\Programs\AllPeriph`（免管理员）
- 向导里可勾选「创建桌面图标」「开机自启」
- 自动检测 WebView2 Runtime，缺失时后台静默装（Win10 旧版无感知）
- 卸载自动进「应用和功能」
- 安装包自动压缩了 apxdesktop + apxhost + WebView2Loader + web/* 全部资源
- 内置 **UsbScan 高拍仪**（GPL-3.0 独立第三方，装到 `third-party\UsbScan`，开始菜单有入口）：接上任意 USB 摄像头或本软件的手机虚拟摄像头，即可拍文件、自动裁边、透视校正、离线 OCR 出 PDF

### 便携版（Windows zip）
解压到任意目录直接双击 apxdesktop.exe 运行，绿色免安装。

便携版同样内置 UsbScan（`third-party\UsbScan\usbscan.exe`，原样未改）。

### macOS（arm64）
下载 **AllPeriph-{{TAG}}-macos-arm64.zip**（由 macos.yml 独立上传）：
- 解压后 `./apxdesktop` 桌面壳 或 `./apxhost panel` 浏览器面板（自动开浏览器，自带 9511 受控端）
- **未签名**：首次打开需 系统设置 → 隐私与安全性 → 仍要打开（README 内有 xattr 一行命令）
- 音箱首次使用需授权「系统音频录制」（macOS 14.2+ 走系统音频采集）；副屏暂不支持（虚拟显示器仅 Windows）

### Android
- **AllPeriph-{{TAG}}-app.apk** —— 手机端（主控 + 被控）
- **AllPeriph-{{TAG}}-tv.apk** —— 电视端（被控 / 接收）
