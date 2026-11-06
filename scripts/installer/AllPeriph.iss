; ============================================================================
; 全能外设 - Inno Setup 安装脚本
; ----------------------------------------------------------------------------
; 产出：单文件 AllPeriph-<VER>-Setup.exe
; 特性：
;   • 现代 Windows 11 风格安装向导（欢迎 → 功能选择 → 进度 → 完成）
;   • 勾选创建桌面图标 / 开机自启
;   • 默认装到 %LOCALAPPDATA%\Programs\全能外设（免管理员；与 apxsetup.exe 同目录）
;   • 自动检测 WebView2 Runtime，缺失时后台静默装（用户无感知）
;   • 卸载自动进「应用和功能」、自动停旧实例
;
; CI 调用：iscc /DMyAppVersion=v0.4.3 /DArtifactsDir=dist/pc-exes-webview2
;              /DIconDir=pc/host/res AllPeriph.iss
; ============================================================================

#define MyAppName      "全能外设"
#define MyAppExeName   "apxdesktop.exe"

[Setup]
AppId={{3E8F9F2D-7A4B-4C91-A8E5-123456789012}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} v{#MyAppVersion}
AppPublisher=guocheng1378
AppPublisherURL=https://github.com/guocheng1378/apx-android
AppSupportURL=https://github.com/guocheng1378/apx-android/issues
DefaultDirName={localappdata}\Programs\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=AllPeriph-{#MyAppVersion}-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile={#IconDir}\apx.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "创建图标"; Flags: unchecked
Name: "autostart";   Description: "开机自启（后台常驻托盘）"; GroupDescription: "启动选项"; Flags: unchecked

[InstallDelete]
; 旧版装在 %USERPROFILE%\全能外设 —— 升级时清掉里面残留的文件（只删文件、留空壳目录），
; 免得用户资源管理器里出现两个「全能外设」文件夹、点开却是旧版。
; 目录口径与自研 apxsetup.exe 统一（pc/host/src/setup_main.cpp 的 installDir()）——
; 2026-11-06 实锤过「两套安装器各装各的目录」→ 本机三个副本、快捷方式还各指一处。
Type: files; Name: "{userpf}\{#MyAppName}\*"

[Files]
; —— 所有文件都压进这个 Setup.exe 里，用户只拿到一个 exe ——
Source: "{#ArtifactsDir}\apxdesktop.exe";     DestDir: "{app}";     Flags: ignoreversion
Source: "{#ArtifactsDir}\WebView2Loader.dll"; DestDir: "{app}";     Flags: ignoreversion
Source: "{#ArtifactsDir}\ViGEmClient.dll";  DestDir: "{app}";     Flags: ignoreversion
Source: "{#ArtifactsDir}\apxhost.exe";         DestDir: "{app}";     Flags: ignoreversion
Source: "{#ArtifactsDir}\web\*";               DestDir: "{app}\web"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{group}\开源主页";  Filename: "https://github.com/guocheng1378/apx-android"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; \
  ValueName: "{#MyAppName}"; ValueData: """{app}\{#MyAppExeName}"""; Tasks: autostart
