#include "apxpc/ui/file_panel_win32.hpp"

#if defined(_WIN32)

#include "apxpc/log.hpp"
#include "apxpc/wireless/file_receiver.hpp"
#include "apxpc/wireless/file_sender.hpp"

#include <commctrl.h>    // ListView
#include <commdlg.h>     // GetOpenFileNameW
#include <shellapi.h>    // ShellExecuteW（打开落盘目录）
#include <windowsx.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cwchar>
#include <filesystem>
#include <mutex>
#include <string>
#include <system_error>
#include <thread>
#include <vector>

#pragma comment(lib, "comctl32")
#pragma comment(lib, "comdlg32")
#pragma comment(lib, "shell32")
#pragma comment(lib, "user32")

namespace apxpc::ui {
namespace {

constexpr wchar_t kCls[] = L"AllPeriphFilePanel";
constexpr int kIdList = 2101;
constexpr int kIdSend = 2102;      // 发送本机文件到手机
constexpr int kIdSendBack = 2103;  // 把选中的收到的文件发回对端
constexpr int kIdOpenDir = 2104;   // 打开落盘目录
constexpr int kIdRefresh = 2105;
constexpr UINT kMsgRefresh = WM_APP + 1;
constexpr UINT kMsgStatus = WM_APP + 2;
constexpr UINT_PTR kTimer = 1;

struct Row {
    std::wstring name;
    std::wstring path;
    uint64_t size = 0;
    std::wstring time;
};

// 单实例窗口：状态放静态（进程级），这样**后台发送线程**永远不会访问已销毁的对象
// （发送走 detach 线程——connect 可能阻塞，不能在关闭时 join，否则卡死 UI）。
HWND gHwnd = nullptr;
HWND gList = nullptr;
HWND gStatus = nullptr;
int gScale = 1;
std::vector<Row> gRows;                                              // 只在 UI 线程读写
std::wstring gDir;                                                   // 落盘目录（宽字符）
std::function<std::string()> gPeerIp;
std::function<void(const std::string&, const std::string&)> gNotify;
std::atomic<bool> gBusy{false};
std::atomic<int> gProg{-1};                                          // -1 = 空闲
std::mutex gMsgMu;
std::string gMsg;                                                    // 最后一次发送结果
int gMsgKind = 0;                                                    // 1=成功 2=失败

std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::string wideToUtf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = ::WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()),
                                        nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(static_cast<size_t>(n), '\0');
    ::WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), s.data(), n,
                          nullptr, nullptr);
    return s;
}

std::wstring sizeText(uint64_t b) {
    wchar_t buf[32]{};
    if (b >= 1024ull * 1024 * 1024) std::swprintf(buf, 32, L"%.2f GB", b / 1024.0 / 1024 / 1024);
    else if (b >= 1024ull * 1024) std::swprintf(buf, 32, L"%.1f MB", b / 1024.0 / 1024);
    else if (b >= 1024) std::swprintf(buf, 32, L"%.1f KB", b / 1024.0);
    else std::swprintf(buf, 32, L"%llu B", static_cast<unsigned long long>(b));
    return buf;
}

/// 文件时间 → 本地时间文本（C++17 没有 file_clock::to_time_t，用 system_clock 对齐的老办法）
std::wstring timeText(const std::filesystem::path& p) {
    std::error_code ec;
    const auto ft = std::filesystem::last_write_time(p, ec);
    if (ec) return L"";
    const auto nowSys = std::chrono::system_clock::now();
    const auto nowFile = std::filesystem::file_time_type::clock::now();
    const auto sys = std::chrono::time_point_cast<std::chrono::system_clock::duration>(
        ft - nowFile + nowSys);
    const std::time_t tt = std::chrono::system_clock::to_time_t(sys);
    std::tm tm{};
    if (::localtime_s(&tm, &tt) != 0) return L"";
    wchar_t buf[32]{};
    std::wcsftime(buf, 32, L"%Y-%m-%d %H:%M", &tm);
    return buf;
}

void setStatus(const std::wstring& text) {
    if (gStatus) ::SetWindowTextW(gStatus, text.c_str());
}

/// 扫描落盘目录（与手机 / TV 端一致：按时间倒序）
void scan() {
    gRows.clear();
    std::error_code ec;
    if (gDir.empty() || !std::filesystem::exists(gDir, ec)) return;
    std::vector<std::pair<std::filesystem::path, std::filesystem::file_time_type>> tmp;
    for (const auto& e : std::filesystem::directory_iterator(gDir, ec)) {
        if (ec) break;
        if (!e.is_regular_file(ec)) continue;
        tmp.emplace_back(e.path(), std::filesystem::last_write_time(e.path(), ec));
    }
    std::sort(tmp.begin(), tmp.end(),
              [](const auto& a, const auto& b) { return a.second > b.second; });
    for (const auto& pr : tmp) {
        const auto& p = pr.first;
        Row r;
        r.path = p.wstring();
        r.name = p.filename().wstring();
        std::error_code ec2;
        r.size = std::filesystem::file_size(p, ec2);
        r.time = timeText(p);
        gRows.push_back(std::move(r));
    }
}

void setCell(int row, int sub, const std::wstring& text) {
    LVITEMW it{};
    it.mask = LVIF_TEXT;
    it.iItem = row;
    it.iSubItem = sub;
    it.pszText = const_cast<LPWSTR>(text.c_str());
    ::SendMessageW(gList, LVM_SETITEMTEXTW, static_cast<WPARAM>(row),
                   reinterpret_cast<LPARAM>(&it));
}

void fillList() {
    if (!gList) return;
    ::SendMessageW(gList, LVM_DELETEALLITEMS, 0, 0);
    for (int i = 0; i < static_cast<int>(gRows.size()); ++i) {
        LVITEMW it{};
        it.mask = LVIF_TEXT;
        it.iItem = i;
        it.pszText = const_cast<LPWSTR>(gRows[i].name.c_str());
        ::SendMessageW(gList, LVM_INSERTITEMW, 0, reinterpret_cast<LPARAM>(&it));
        setCell(i, 1, sizeText(gRows[i].size));
        setCell(i, 2, gRows[i].time);
    }
}

int selectedRow() {
    if (!gList) return -1;
    const int i = static_cast<int>(::SendMessageW(gList, LVM_GETNEXTITEM, -1, LVNI_SELECTED));
    return (i >= 0 && i < static_cast<int>(gRows.size())) ? i : -1;
}

/// 后台发送：detach 线程 + 静态状态，窗口关掉也不会踩悬空对象
void startSend(const std::wstring& path) {
    const std::string host = gPeerIp ? gPeerIp() : std::string();
    if (host.empty()) {
        if (gNotify) gNotify("还没连上手机", "先在面板打开「无线」并等显示已连接，再发送文件");
        setStatus(L"未连接：先连上对端再发送");
        return;
    }
    if (gBusy.exchange(true)) {
        setStatus(L"正在发送中，请稍候…");
        return;
    }
    std::thread([path, host] {
        gProg.store(0);
        const std::string p8 = wideToUtf8(path);
        const bool ok = apxpc::wireless::sendFile(host, 9512, p8,
                                                  [](int p) { gProg.store(p); });
        {
            std::lock_guard<std::mutex> lk(gMsgMu);
            gMsg = ok ? ("已发送：" + p8 + "  →  " + host)
                      : ("发送失败：" + apxpc::wireless::lastSendError());
            gMsgKind = ok ? 1 : 2;
        }
        gProg.store(-1);
        gBusy.store(false);
        if (gHwnd) ::PostMessageW(gHwnd, kMsgStatus, 0, 0);
    }).detach();
}

void pickAndSend() {
    wchar_t path[MAX_PATH * 4] = {0};
    OPENFILENAMEW ofn{};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = gHwnd;
    ofn.lpstrFilter = L"所有文件\0*.*\0\0";
    ofn.lpstrFile = path;
    ofn.nMaxFile = MAX_PATH * 4;
    ofn.Flags = OFN_EXPLORER | OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST;
    if (!::GetOpenFileNameW(&ofn)) return;
    startSend(path);
}

void layout(int w, int h) {
    const int m = 12 * gScale;
    const int btnW = 150 * gScale, btnH = 30 * gScale;
    const int statusH = 22 * gScale;
    if (gStatus) ::MoveWindow(gStatus, m, h - m - statusH, w - 2 * m, statusH, TRUE);
    const int listH = h - 2 * m - statusH - btnH - 8 * gScale;
    if (gList) ::MoveWindow(gList, m, m, w - 2 * m, (listH > 40) ? listH : 40, TRUE);
    const int y = h - m - statusH - btnH - 4 * gScale;
    int x = m;
    for (int id : {kIdSend, kIdSendBack, kIdOpenDir, kIdRefresh}) {
        HWND b = ::GetDlgItem(gHwnd, id);
        if (b) { ::MoveWindow(b, x, y, btnW, btnH, TRUE); x += btnW + 8 * gScale; }
    }
}

LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);

bool registerCls(HINSTANCE inst) {
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = wndProc;
    wc.hInstance = inst;
    wc.hCursor = ::LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    wc.lpszClassName = kCls;
    wc.hIcon = ::LoadIconW(nullptr, IDI_APPLICATION);
    wc.hIconSm = wc.hIcon;
    return ::RegisterClassExW(&wc) != 0 || ::GetLastError() == ERROR_CLASS_ALREADY_EXISTS;
}

LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_CREATE: {
            INITCOMMONCONTROLSEX ic{sizeof(ic), ICC_LISTVIEW_CLASSES};
            ::InitCommonControlsEx(&ic);
            gHwnd = hwnd;
            const HINSTANCE inst = reinterpret_cast<HINSTANCE>(
                ::GetWindowLongPtrW(hwnd, GWLP_HINSTANCE));
            const DWORD listStyle = WS_CHILD | WS_VISIBLE | WS_BORDER | LVS_REPORT |
                                    LVS_SINGLESEL | LVS_SHOWSELALWAYS;
            gList = ::CreateWindowExW(WS_EX_CLIENTEDGE, WC_LISTVIEWW, L"", listStyle,
                                      0, 0, 10, 10, hwnd, reinterpret_cast<HMENU>(kIdList),
                                      inst, nullptr);
            if (gList) {
                ::SendMessageW(gList, LVM_SETEXTENDEDLISTVIEWSTYLE, 0,
                               LVS_EX_FULLROWSELECT | LVS_EX_DOUBLEBUFFER);
                LVCOLUMNW c{};
                c.mask = LVCF_TEXT | LVCF_WIDTH;
                c.pszText = const_cast<LPWSTR>(L"收到的文件（双击发回对端）");
                c.cx = 340 * gScale;
                ::SendMessageW(gList, LVM_INSERTCOLUMNW, 0, reinterpret_cast<LPARAM>(&c));
                c.pszText = const_cast<LPWSTR>(L"大小");
                c.cx = 90 * gScale;
                ::SendMessageW(gList, LVM_INSERTCOLUMNW, 1, reinterpret_cast<LPARAM>(&c));
                c.pszText = const_cast<LPWSTR>(L"时间");
                c.cx = 130 * gScale;
                ::SendMessageW(gList, LVM_INSERTCOLUMNW, 2, reinterpret_cast<LPARAM>(&c));
            }
            const DWORD btn = WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON;
            auto mk = [&](int id, const wchar_t* t) {
                ::CreateWindowExW(0, L"BUTTON", t, btn, 0, 0, 10, 10, hwnd,
                                  reinterpret_cast<HMENU>(id), inst, nullptr);
            };
            mk(kIdSend, L"发送本机文件到手机…");
            mk(kIdSendBack, L"把选中项发回对端");
            mk(kIdOpenDir, L"打开文件夹");
            mk(kIdRefresh, L"刷新");
            gStatus = ::CreateWindowExW(0, L"STATIC", L"", WS_CHILD | WS_VISIBLE, 0, 0, 10, 10,
                                        hwnd, nullptr, inst, nullptr);
            ::SetTimer(hwnd, kTimer, 250, nullptr);
            scan();
            fillList();
            setStatus(std::wstring(L"落盘目录：") + gDir);
            return 0;
        }

        case WM_SIZE:
            layout(LOWORD(lp), HIWORD(lp));
            return 0;

        case WM_COMMAND:
            switch (LOWORD(wp)) {
                case kIdSend: pickAndSend(); return 0;
                case kIdSendBack: {
                    const int i = selectedRow();
                    if (i < 0) { setStatus(L"先选中一行"); return 0; }
                    startSend(gRows[i].path);
                    return 0;
                }
                case kIdOpenDir:
                    if (!gDir.empty())
                        ::ShellExecuteW(hwnd, L"explore", gDir.c_str(), nullptr, nullptr, SW_SHOW);
                    return 0;
                case kIdRefresh: scan(); fillList(); setStatus(L"已刷新"); return 0;
            }
            return 0;

        case WM_NOTIFY: {
            const auto* nm = reinterpret_cast<NMHDR*>(lp);
            if (nm && nm->idFrom == kIdList && nm->code == NM_DBLCLK) {
                const int i = selectedRow();
                if (i >= 0) startSend(gRows[i].path);
            }
            return 0;
        }

        case kMsgRefresh:
            scan();
            fillList();
            return 0;

        case kMsgStatus: {
            std::string m;
            int kind = 0;
            {
                std::lock_guard<std::mutex> lk(gMsgMu);
                m = gMsg;
                kind = gMsgKind;
            }
            setStatus(utf8ToWide(m));
            if (kind && gNotify)
                gNotify(kind == 1 ? "文件已发送" : "发送失败", m);
            return 0;
        }

        case WM_TIMER: {
            const int p = gProg.load();
            if (p >= 0) setStatus(L"发送中 " + std::to_wstring(p) + L"%");
            return 0;
        }

        case WM_CLOSE:
            ::DestroyWindow(hwnd);
            return 0;

        case WM_DESTROY:
            ::KillTimer(hwnd, kTimer);
            gHwnd = nullptr;
            gList = nullptr;
            gStatus = nullptr;
            return 0;
    }
    return ::DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

void FilePanel::show(HWND owner,
                     std::function<std::string()> peerIp,
                     std::function<void(const std::string&, const std::string&)> notify) {
    gPeerIp = std::move(peerIp);
    gNotify = std::move(notify);
    gDir = utf8ToWide(apxpc::wireless::FileReceiver::defaultDir());

    HDC hdc = ::GetDC(nullptr);
    if (hdc) {
        const int dpi = ::GetDeviceCaps(hdc, LOGPIXELSY);
        ::ReleaseDC(nullptr, hdc);
        gScale = (dpi >= 144) ? 2 : 1;
    }

    if (gHwnd) {                       // 已打开：提到前台 + 刷新
        scan();
        fillList();
        ::ShowWindow(gHwnd, SW_SHOW);
        ::SetForegroundWindow(gHwnd);
        return;
    }

    const HINSTANCE inst = reinterpret_cast<HINSTANCE>(::GetModuleHandleW(nullptr));
    if (!registerCls(inst)) return;

    const int w = 660 * gScale, h = 420 * gScale;
    HWND hwnd = ::CreateWindowExW(0, kCls, L"文件传输 · 收到的 / 发出去的",
                                  WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX |
                                      WS_SIZEBOX,
                                  CW_USEDEFAULT, CW_USEDEFAULT, w, h,
                                  owner, nullptr, inst, nullptr);
    if (!hwnd) return;
    ::ShowWindow(hwnd, SW_SHOW);
    ::SetForegroundWindow(hwnd);
}

void FilePanel::refresh() {
    if (gHwnd) ::PostMessageW(gHwnd, kMsgRefresh, 0, 0);
}

}  // namespace apxpc::ui

#else   // 非 Windows：接口保留，行为为空（与托盘一致的做法）

namespace apxpc::ui {
void FilePanel::show(HWND, std::function<std::string()>,
                     std::function<void(const std::string&, const std::string&)> ) {}
void FilePanel::refresh() {}
}  // namespace apxpc::ui

#endif
