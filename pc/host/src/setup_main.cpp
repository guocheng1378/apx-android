// 安装包（apxsetup.exe）：自包含、免第三方工具链。
//
// 为什么自己写：仓库坚持「不引第三方依赖」，而 NSIS / Inno / WiX 都不在目标机器上。
// 做法是把桌面端可执行文件整个塞进本程序资源（RCDATA，见 CMake 生成的
// setup_payload.rc），运行时释放到 %LOCALAPPDATA%\AllPeriph —— **单文件即可分发**。
//
// 三态：
//   （无参）/ --install      安装：释放文件 + 开始菜单快捷方式 + 卸载项 + 自启迁移
//   --uninstall              卸载：停进程 → 删快捷方式/注册表 → 交给 cmd 延时删目录
//   --silent-install/--silent-uninstall   无界面（供自动化验证）
//
// 为什么用 %LOCALAPPDATA% 而不是 Program Files：这是**单用户常驻工具**（要写
// HKCU 自启、跑托盘），装到用户目录可以全程免 UAC，体验更顺；卸载项写在 HKCU，
// 同样出现在「应用和功能」里。
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <tlhelp32.h>

#include <apxpc/version.hpp>

#include <string>

#pragma comment(lib, "shell32")
#pragma comment(lib, "ole32")
#pragma comment(lib, "shlwapi")

namespace {

// 与 CMake 生成的 setup_payload.rc 里的 IDR_PAYLOAD 对应
constexpr int kPayloadResId = 200;

constexpr wchar_t kAppName[]    = L"全能外设";
constexpr wchar_t kAppDesc[]    = L"手机当外设：无线触控板 / 键盘 / 多媒体";
constexpr wchar_t kPublisher[]  = L"guocheng1378";
constexpr wchar_t kHomePage[]   = L"https://github.com/guocheng1378/apx-android";

constexpr wchar_t kAppId[]      = L"AllPeriph";
constexpr wchar_t kAppExe[]     = L"apxdesktop.exe";
constexpr wchar_t kUninstExe[]  = L"uninstall.exe";
constexpr wchar_t kLnkName[]    = L"全能外设.lnk";

constexpr wchar_t kRunKey[]     = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
constexpr wchar_t kUninstRoot[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

// ——————————————————————————— 小工具 ———————————————————————————

std::wstring modulePath() {
    wchar_t buf[MAX_PATH * 2] = {0};
    GetModuleFileNameW(nullptr, buf, MAX_PATH * 2);
    return buf;
}

std::wstring dirOf(const std::wstring& p) {
    const size_t i = p.find_last_of(L'\\');
    return i == std::wstring::npos ? std::wstring() : p.substr(0, i);
}

std::wstring knownFolder(REFKNOWNFOLDERID id) {
    PWSTR p = nullptr;
    if (FAILED(SHGetKnownFolderPath(id, 0, nullptr, &p)) || !p) return {};
    std::wstring s(p);
    CoTaskMemFree(p);
    return s;
}

/// 窄 → 宽（版本号等宏是窄字符字面量）
std::wstring wide(const char* s) {
    if (!s || !*s) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, w.data(), n);
    w.pop_back();
    return w;
}

std::wstring localAppData() { return knownFolder(FOLDERID_LocalAppData); }
std::wstring programsDir() { return knownFolder(FOLDERID_Programs); }

/// 程序装到 %LOCALAPPDATA%\Programs\AllPeriph（Windows 免管理员安装的惯例位置），
/// 用户配置留在 %LOCALAPPDATA%\AllPeriph —— 两者分开，卸载才不会顺手清掉设置。
std::wstring installDir() { return localAppData() + L"\\Programs\\" + kAppId; }
std::wstring startMenuLnk() { return programsDir() + L"\\" + kLnkName; }
std::wstring uninstallKey() { return std::wstring(kUninstRoot) + L"\\" + kAppId; }

bool pathExists(const std::wstring& p) {
    return !p.empty() && GetFileAttributesW(p.c_str()) != INVALID_FILE_ATTRIBUTES;
}
bool dirExists(const std::wstring& p) {
    const DWORD a = GetFileAttributesW(p.c_str());
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

// ——————————————————————————— 释放载荷 ———————————————————————————

bool extractPayload(const std::wstring& dst) {
    // RT_RCDATA 宏是 MAKEINTRESOURCE(10)，在本文件里会展开成窄指针，故显式用 W 版
    HRSRC res = FindResourceW(nullptr, MAKEINTRESOURCEW(kPayloadResId),
                              MAKEINTRESOURCEW(10));
    if (!res) return false;
    HGLOBAL h = LoadResource(nullptr, res);
    if (!h) return false;
    const DWORD size = SizeofResource(nullptr, res);
    const void* data = LockResource(h);
    if (!data || size == 0) return false;

    HANDLE f = CreateFileW(dst.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    const bool ok = WriteFile(f, data, size, &written, nullptr) && written == size;
    CloseHandle(f);
    if (!ok) DeleteFileW(dst.c_str());
    return ok;
}

// ——————————————————————————— 注册表 ———————————————————————————

bool regSetString(HKEY root, const std::wstring& sub, const wchar_t* name,
                  const std::wstring& val) {
    HKEY k = nullptr;
    if (RegCreateKeyExW(root, sub.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k,
                        nullptr) != ERROR_SUCCESS) {
        return false;
    }
    const LSTATUS r = RegSetValueExW(
        k, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(val.c_str()),
        static_cast<DWORD>((val.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(k);
    return r == ERROR_SUCCESS;
}

bool regSetDword(HKEY root, const std::wstring& sub, const wchar_t* name, DWORD val) {
    HKEY k = nullptr;
    if (RegCreateKeyExW(root, sub.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k,
                        nullptr) != ERROR_SUCCESS) {
        return false;
    }
    const LSTATUS r = RegSetValueExW(k, name, 0, REG_DWORD,
                                     reinterpret_cast<const BYTE*>(&val), sizeof(val));
    RegCloseKey(k);
    return r == ERROR_SUCCESS;
}

bool regGetString(HKEY root, const std::wstring& sub, const wchar_t* name,
                  std::wstring& out) {
    HKEY k = nullptr;
    if (RegOpenKeyExW(root, sub.c_str(), 0, KEY_QUERY_VALUE, &k) != ERROR_SUCCESS) {
        return false;
    }
    wchar_t buf[1024] = {0};
    DWORD sz = sizeof(buf);
    DWORD type = 0;
    const LSTATUS r = RegQueryValueExW(k, name, nullptr, &type,
                                       reinterpret_cast<BYTE*>(buf), &sz);
    RegCloseKey(k);
    if (r != ERROR_SUCCESS || type != REG_SZ) return false;
    out = buf;
    return true;
}

bool regDeleteValue(HKEY root, const std::wstring& sub, const wchar_t* name) {
    HKEY k = nullptr;
    if (RegOpenKeyExW(root, sub.c_str(), 0, KEY_SET_VALUE, &k) != ERROR_SUCCESS) {
        return false;
    }
    const LSTATUS r = RegDeleteValueW(k, name);
    RegCloseKey(k);
    return r == ERROR_SUCCESS;
}

bool regDeleteKey(HKEY root, const std::wstring& sub) {
    return RegDeleteTreeW(root, sub.c_str()) == ERROR_SUCCESS;
}

// ——————————————————————————— 进程 / 文件 ———————————————————————————

/// 结束所有从指定目录启动的进程（安装覆盖 / 卸载前都要先停掉，否则文件被占用）
int killProcessesUnder(const std::wstring& dir) {
    if (dir.empty()) return 0;
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    PROCESSENTRY32W pe{};
    pe.dwSize = sizeof(pe);
    int killed = 0;
    if (Process32FirstW(snap, &pe)) {
        do {
            // 绝不能把自己也算进去：卸载器 uninstall.exe 就住在安装目录里，
            // 早先版本因此自杀，卸载流程一步都没跑（真机踩过）
            if (pe.th32ProcessID == GetCurrentProcessId()) continue;
            HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_TERMINATE,
                                   FALSE, pe.th32ProcessID);
            if (!h) continue;
            wchar_t buf[MAX_PATH * 2] = {0};
            DWORD n = MAX_PATH * 2;
            if (QueryFullProcessImageNameW(h, 0, buf, &n)) {
                const std::wstring path(buf);
                const bool under =
                    path.size() > dir.size() + 1 &&
                    _wcsnicmp(path.c_str(), dir.c_str(), dir.size()) == 0 &&
                    (path[dir.size()] == L'\\');
                if (under && TerminateProcess(h, 0)) ++killed;
            }
            CloseHandle(h);
        } while (Process32NextW(snap, &pe));
    }
    CloseHandle(snap);
    return killed;
}

/// 递归删除目录；正在运行的自身会删不掉，由调用方负责收尾
bool removeDirRecursive(const std::wstring& dir) {
    WIN32_FIND_DATAW fd{};
    HANDLE h = FindFirstFileW((dir + L"\\*").c_str(), &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            const std::wstring name = fd.cFileName;
            if (name == L"." || name == L"..") continue;
            const std::wstring p = dir + L"\\" + name;
            if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                removeDirRecursive(p);
            } else {
                SetFileAttributesW(p.c_str(), FILE_ATTRIBUTE_NORMAL);
                DeleteFileW(p.c_str());
            }
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    return RemoveDirectoryW(dir.c_str()) != FALSE;
}

/// 交回 cmd「等两秒再删目录」—— 本进程（可能就是待删目录里的 uninstall.exe）必须
/// 先退出，文件才不被占用。路径带引号即可安全传递（首 token 是 timeout，不受影响）。
void spawnDelayedRemove(const std::wstring& dir) {
    if (dir.empty()) return;
    std::wstring line = L"cmd.exe /c timeout /t 2 /nobreak >nul & rmdir /s /q \"" + dir + L"\"";
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    if (CreateProcessW(nullptr, line.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
                       nullptr, nullptr, &si, &pi)) {
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    }
}

/// 开始菜单快捷方式（IShellLink + IPersistFile）
bool createShortcut(const std::wstring& lnk, const std::wstring& target,
                    const std::wstring& workDir) {
    IShellLinkW* link = nullptr;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
                               IID_IShellLinkW, reinterpret_cast<void**>(&link)))) {
        return false;
    }
    link->SetPath(target.c_str());
    link->SetWorkingDirectory(workDir.c_str());
    link->SetDescription(kAppDesc);
    link->SetIconLocation(target.c_str(), 0);
    bool ok = false;
    IPersistFile* pf = nullptr;
    if (SUCCEEDED(link->QueryInterface(IID_IPersistFile, reinterpret_cast<void**>(&pf)))) {
        ok = SUCCEEDED(pf->Save(lnk.c_str(), TRUE));
        pf->Release();
    }
    link->Release();
    return ok;
}

// ——————————————————————————— 安装 / 卸载 ———————————————————————————

bool doInstall(bool silent, std::wstring& err) {
    const std::wstring dir = installDir();
    if (dir.empty()) {
        err = L"无法定位 %LOCALAPPDATA%";
        return false;
    }
    // 用 SHCreateDirectoryEx 一次建好多级（%LOCALAPPDATA%\Programs 未必存在）
    if (!dirExists(dir) && SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr) != ERROR_SUCCESS) {
        err = L"创建安装目录失败：" + dir;
        return false;
    }

    // 覆盖安装前先停掉旧实例，否则 exe 被占用写不进去
    killProcessesUnder(dir);
    Sleep(300);

    const std::wstring appExe = dir + L"\\" + kAppExe;
    if (!extractPayload(appExe)) {
        err = L"释放主程序失败（载荷资源缺失？）";
        return false;
    }
    // 卸载器 = 本程序自身的一份拷贝
    if (!CopyFileW(modulePath().c_str(), (dir + L"\\" + kUninstExe).c_str(), FALSE)) {
        err = L"写入卸载器失败";
        return false;
    }

    // 开始菜单快捷方式
    if (!createShortcut(startMenuLnk(), appExe, dir)) {
        err = L"创建开始菜单快捷方式失败";
        return false;
    }

    // 自启迁移：原本开着就改指到安装位置；原本没开就不擅自开（尊重用户现状）
    std::wstring oldRun;
    if (regGetString(HKEY_CURRENT_USER, kRunKey, kAppId, oldRun)) {
        regSetString(HKEY_CURRENT_USER, kRunKey, kAppId, L"\"" + appExe + L"\"");
    }

    // 卸载项（HKCU，出现在「应用和功能」里）
    const std::wstring key = uninstallKey();
    regSetString(HKEY_CURRENT_USER, key, L"DisplayName", kAppName);
    regSetString(HKEY_CURRENT_USER, key, L"DisplayVersion", wide(APXPC_VERSION_STRING));
    regSetString(HKEY_CURRENT_USER, key, L"Publisher", kPublisher);
    regSetString(HKEY_CURRENT_USER, key, L"URLInfoAbout", kHomePage);
    regSetString(HKEY_CURRENT_USER, key, L"InstallLocation", dir);
    regSetString(HKEY_CURRENT_USER, key, L"DisplayIcon", appExe);
    regSetString(HKEY_CURRENT_USER, key, L"UninstallString",
                 L"\"" + dir + L"\\" + kUninstExe + L"\" --uninstall");
    regSetString(HKEY_CURRENT_USER, key, L"QuietUninstallString",
                 L"\"" + dir + L"\\" + kUninstExe + L"\" --silent-uninstall");
    regSetDword(HKEY_CURRENT_USER, key, L"NoModify", 1);
    regSetDword(HKEY_CURRENT_USER, key, L"NoRepair", 1);
    regSetDword(HKEY_CURRENT_USER, key, L"EstimatedSize", 1024);   // KB

    if (!silent) {
        const std::wstring msg = L"「" + std::wstring(kAppName) + L"」已安装到：\n" + dir +
                                 L"\n\n开始菜单里可直接启动；托盘常驻，关窗口不退出。";
        MessageBoxW(nullptr, msg.c_str(), kAppName, MB_OK | MB_ICONINFORMATION);
        if (MessageBoxW(nullptr, L"现在启动吗？", kAppName,
                        MB_YESNO | MB_ICONQUESTION) == IDYES) {
            ShellExecuteW(nullptr, L"open", appExe.c_str(), nullptr, dir.c_str(),
                          SW_SHOWNORMAL);
        }
    }
    return true;
}

bool doUninstall(bool silent, std::wstring& err) {
    const std::wstring dir = installDir();
    (void)err;

    // 1) 先停掉安装目录里的进程（主程序还在托盘里的话文件删不掉）
    killProcessesUnder(dir);
    Sleep(300);

    // 2) 快捷方式 / 注册表
    DeleteFileW(startMenuLnk().c_str());
    std::wstring runVal;
    if (regGetString(HKEY_CURRENT_USER, kRunKey, kAppId, runVal) &&
        runVal.find(kAppId) != std::wstring::npos) {
        regDeleteValue(HKEY_CURRENT_USER, kRunKey, kAppId);
    }
    regDeleteKey(HKEY_CURRENT_USER, uninstallKey());

    // 3) 目录：能删的先删掉，剩下的（含正在运行的自己）交给 cmd 延时收尾
    if (dirExists(dir)) {
        removeDirRecursive(dir);
        if (dirExists(dir)) spawnDelayedRemove(dir);
    }

    if (!silent) {
        MessageBoxW(nullptr, L"「全能外设」已卸载。", kAppName,
                    MB_OK | MB_ICONINFORMATION);
    }
    return true;
}

/// 中断安装时把已改的注册表清掉（自启 / 卸载项）
void rollbackPartial() {
    regDeleteKey(HKEY_CURRENT_USER, uninstallKey());
}

int usage() {
    MessageBoxW(nullptr,
                L"用法：\n  apxsetup.exe                 安装\n  apxsetup.exe --uninstall     卸载\n"
                L"  apxsetup.exe --silent-install / --silent-uninstall   无界面\n",
                kAppName, MB_OK | MB_ICONINFORMATION);
    return 0;
}

}  // namespace

int main(int argc, char** /*argv*/) {
    // 用宽字符取命令行，避免中文路径在窄字符里丢失
    int wargc = 0;
    LPWSTR* wargv = CommandLineToArgvW(GetCommandLineW(), &wargc);
    std::wstring mode = L"";
    if (wargv && wargc > 1) mode = wargv[1];
    if (wargv) LocalFree(wargv);
    (void)argc;

    const bool silent = (mode == L"--silent-install" || mode == L"--silent-uninstall");
    const bool uninstall = (mode == L"--uninstall" || mode == L"--silent-uninstall");
    const bool install = (mode.empty() || mode == L"--install" || mode == L"--silent-install");

    if (mode == L"--help" || mode == L"-h") return usage();
    if (!install && !uninstall) return usage();

    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool comOk = SUCCEEDED(hr);

    if (uninstall) {
        if (!silent) {
            const std::wstring msg =
                L"确定卸载「" + std::wstring(kAppName) + L"」？\n\n会删除程序目录、开始菜单"
                L"快捷方式与卸载项。";
            if (MessageBoxW(nullptr, msg.c_str(), kAppName,
                            MB_OKCANCEL | MB_ICONWARNING) != IDOK) {
                if (comOk) CoUninitialize();
                return 0;
            }
        }
        std::wstring err;
        const bool ok = doUninstall(silent, err);
        if (comOk) CoUninitialize();
        return ok ? 0 : 1;
    }

    if (!silent) {
        const std::wstring msg = L"将把「" + std::wstring(kAppName) + L"」安装到：\n" +
                                 installDir() +
                                 L"\n\n并创建开始菜单快捷方式与卸载项。";
        if (MessageBoxW(nullptr, msg.c_str(), kAppName,
                        MB_OKCANCEL | MB_ICONQUESTION) != IDOK) {
            if (comOk) CoUninitialize();
            return 0;
        }
    }

    std::wstring err;
    const bool ok = doInstall(silent, err);
    if (!ok) {
        rollbackPartial();
        if (!silent) {
            MessageBoxW(nullptr, (L"安装失败：" + err).c_str(), kAppName,
                        MB_OK | MB_ICONERROR);
        }
    }
    if (comOk) CoUninitialize();
    return ok ? 0 : 1;
}
