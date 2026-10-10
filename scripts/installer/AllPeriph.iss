; ============================================================================
; 全能外设 - Inno Setup 安装脚本
; ----------------------------------------------------------------------------
; 产出：单文件 AllPeriph-<VER>-Setup.exe
; 特性：
;   • 现代 Windows 11 风格安装向导（欢迎 → 功能选择 → 进度 → 完成）
;   • 勾选创建桌面图标 / 开机自启
;   • 默认装到 %LOCALAPPDATA%\Programs\AllPeriph（免管理员）
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
DefaultDirName={userpf}\{#MyAppName}
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

[Files]
; —— 所有文件都压进这个 Setup.exe 里，用户只拿到一个 exe ——
Source: "{#ArtifactsDir}\apxdesktop.exe";     DestDir: "{app}";     Flags: ignoreversion
Source: "{#ArtifactsDir}\WebView2Loader.dll"; DestDir: "{app}";     Flags: ignoreversion
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

[Code]
function StopExistingApp: Boolean;
var RC: Integer;
begin
  Exec('taskkill', '/F /IM apxdesktop.exe /IM apxhost.exe', '', SW_HIDE, ewWaitUntilTerminated, RC);
  Sleep(300);
end;

procedure EnsureWebView2Runtime;
var
  EdgePV: string;
  RC: Integer;
  InstallerPath: string;
begin
  try
    if RegQueryStringValue(HKLM, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',
                           'pv', EdgePV) and (EdgePV <> '') then Exit;
  except end;
  Log('WebView2 Runtime missing — installing...');
  InstallerPath := ExpandConstant('{tmp}') + '\WebView2RuntimeInstallerX64.exe';
  if DownloadTemporaryFile(
      'https://msedge.sf.dl.delivery.mp.microsoft.com/filestreamingservice/files/7c7c0e6f-8cb5-406a-8e51-df0c62011e55/MicrosoftEdgeWebView2RuntimeInstallerX64.exe') then begin
    Exec(InstallerPath, '/silent /install', '', SW_HIDE, ewWaitUntilTerminated, RC);
    Log('Runtime install exit=' + IntToStr(RC));
  end;
end;

function InitializeSetup: Boolean;
begin StopExistingApp; Result := True; end;

procedure CurStepChanged(CurStep: TSetupStep);
begin if CurStep = ssInstall then EnsureWebView2Runtime; end;
