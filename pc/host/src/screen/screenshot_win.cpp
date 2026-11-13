// 手柄截图抓屏实现（issue#170 #7，2026-11-14）。单文件内含 Win32 真实现与其它平台空桩，
// 同 tray_win32 的「非 Windows 空桩」做法 —— 不用 CMake 按平台挑文件，跨端构建零配置。
// Windows 路径：GetSystemMetrics(SM_X/YVIRTUALSCREEN) 拿虚拟桌面全幅 → 内存 DC BitBlt
// 整幅 → GDI+ GetHBITMAP 进 GdipCreateBitmapFromHBITMAP → 存 PNG 到「图片\AllPeriph」。
#include "apxpc/screen/screenshot.hpp"

#if defined(_WIN32)

#include <windows.h>
// GDI+ 头依赖 IStream（GdiplusMetafile 等）—— 本仓 CMake 定义了 WIN32_LEAN_AND_MEAN
// （裁掉 OLE 系头，IStream 随之消失，CI 实锤 error C2065 'IStream'），按微软推荐
// 顺序自己补 <objidl.h> 再进 <gdiplus.h>。
#include <objidl.h>
#include <gdiplus.h>
#include <shlobj.h>

#include <cstdio>
#include <ctime>
#include <mutex>
#include <string>

namespace apxpc::screen {
namespace {

// GDI+ 进程只需 GdiplusStartup 一次；多线程并发抓屏时用 once 保证不重入。
std::once_flag gdiplus_once;

/// 确保只启动一次 GDI+（不关 —— 进程退出时系统回收，与球壳/托盘的常驻语义一致）。
void ensureGdiplus() {
    std::call_once(gdiplus_once, [] {
        Gdiplus::GdiplusStartupInput input;
        // 返回值忽略：失败时后续编码调用会继续失败，空串路径已兜底。
        Gdiplus::GdiplusStartup(nullptr, &input, nullptr);
    });
}

/// 「图片\AllPeriph」目录，缺目录时创建。失败返回空串。
std::string ensureShotDir() {
    PWSTR pictures = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_Pictures, 0, nullptr, &pictures))) return {};
    std::wstring dir = std::wstring(pictures) + L"\\AllPeriph";
    CoTaskMemFree(pictures);
    // SHCreateDirectoryEx 对已存在目录返回 ERROR_ALREADY_EXISTS，同样视为成功。
    SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
    int need = WideCharToMultiByte(CP_UTF8, 0, dir.c_str(), -1, nullptr, 0, nullptr, nullptr);
    std::string out(static_cast<size_t>(need) - 1, '\0');
    WideCharToMultiByte(CP_UTF8, 0, dir.c_str(), -1, out.data(), need, nullptr, nullptr);
    return out;
}

}  // namespace

std::string captureScreenToPng() {
    ensureGdiplus();

    // 虚拟桌面全幅：SM_X/YVIRTUALSCREEN 是所有显示器的最小外接矩形原点（可能是负数），
    // 宽高用 SM_CX/CYVIRTUALSCREEN —— 多屏/副显布局的游戏画面也能整幅存下。
    const int vx = GetSystemMetrics(SM_XVIRTUALSCREEN);
    const int vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
    const int vw = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    const int vh = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    if (vw <= 0 || vh <= 0) return {};

    HDC screenDc = GetDC(nullptr);
    if (!screenDc) return {};
    HDC memDc = CreateCompatibleDC(screenDc);
    HBITMAP bmp = CreateCompatibleBitmap(screenDc, vw, vh);
    std::string saved;
    if (memDc && bmp) {
        HGDIOBJ old = SelectObject(memDc, bmp);
        // BitBlt 抓屏不需要 CAPTUREBLT（那是带分层窗口的口径）—— 整幅复制足够。
        BitBlt(memDc, 0, 0, vw, vh, screenDc, vx, vy, SRCCOPY);
        SelectObject(memDc, old);

        Gdiplus::Bitmap image(bmp, nullptr);
        CLSID pngCls = {};
        // PNG 编码器 CLSID 稳定不变，但按规矩动态取一次（GetImageEncoders 口径）。
        UINT num = 0, size = 0;
        if (Gdiplus::GetImageEncodersSize(&num, &size) == Gdiplus::Ok && size > 0) {
            // 内存按字节数组分配，避免容器对 ImageCodecInfo 的对齐/构造假设。
            std::string buf(size, '\0');
            auto* info = reinterpret_cast<Gdiplus::ImageCodecInfo*>(buf.data());
            if (Gdiplus::GetImageEncoders(num, size, info) == Gdiplus::Ok) {
                for (UINT i = 0; i < num; ++i) {
                    if (wcscmp(info[i].MimeType, L"image/png") == 0) {
                        pngCls = info[i].Clsid;
                        break;
                    }
                }
            }
        }
        if (pngCls.Data1 != 0) {
            const std::string dir = ensureShotDir();
            if (!dir.empty()) {
                // 文件名：手柄截图-YYYYMMDD-HHMMSS.png（本地时间，秒级即可区分连拍）。
                std::time_t now = std::time(nullptr);
                std::tm tmv = {};
                localtime_s(&tmv, &now);
                wchar_t name[128];
                swprintf_s(name, L"\\手柄截图-%04d%02d%02d-%02d%02d%02d.png",
                           tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday,
                           tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
                int wneed = MultiByteToWideChar(CP_UTF8, 0, dir.c_str(), -1, nullptr, 0);
                std::wstring wdir(static_cast<size_t>(wneed) - 1, L'\0');
                MultiByteToWideChar(CP_UTF8, 0, dir.c_str(), -1, wdir.data(), wneed);
                const std::wstring path = wdir + name;
                if (image.Save(path.c_str(), &pngCls, nullptr) == Gdiplus::Ok) {
                    int need = WideCharToMultiByte(CP_UTF8, 0, path.c_str(), -1, nullptr, 0, nullptr, nullptr);
                    saved.assign(static_cast<size_t>(need) - 1, '\0');
                    WideCharToMultiByte(CP_UTF8, 0, path.c_str(), -1, saved.data(), need, nullptr, nullptr);
                }
            }
        }
    }
    if (bmp) DeleteObject(bmp);
    if (memDc) DeleteDC(memDc);
    ReleaseDC(nullptr, screenDc);
    return saved;
}

#else  // 非 Windows：本功能暂缺，返回空串（调用方按失败提示，不托气泡）。

namespace apxpc::screen {

std::string captureScreenToPng() { return {}; }

}  // namespace apxpc::screen

#endif  // _WIN32
