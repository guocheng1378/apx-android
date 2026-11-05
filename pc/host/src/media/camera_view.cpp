// 手机摄像头 → PC 预览窗 / 虚拟摄像头 的**生产端**（M7-c c-1 + c-2）。
//
// 线程模型（镜像投屏/文件面板的现有习惯，本文件自包含）：
//   · feed()：收流线程调，只入队（有界 8，丢最旧）→ 唤醒帧事件；
//   · worker 线程：**窗口线程**（CreateWindow + 消息泵都在这）—— 帧解码、
//     绘制缓冲、NV12 转换、shm 写入全部同线程，不跨线程碰 GDI 对象；
//   · 面板线程：setPreview/setVirtualCam 只改 atomic 标志 + 注册表/MF 对象，
//     join 都在不持 status 锁时做（避免 worker 等锁死锁）。
//
// c-2 注册链：写 HKCU\Software\Classes\CLSID\{kVcamClsid}\InprocServer32（免管理员）
//   → LoadLibrary(mfsensorgroup.dll) 动态解析 MFCreateVirtualCamera（Win10 无此导出
//     → 诚实报错降级，不崩）→ Start() 注册 → 关闭时 Remove() + Shutdown() + 删注册项。
//   枚举值已核（learn.microsoft.com 2026-11-06）：Session=0、CurrentUser=0、
//   SoftwareCameraSource=0 —— 用官方头 mfvirtualcamera.h（无头的旧 SDK 编译期禁用）。
//
// ⚠️ 未真机实测：预览窗路径有 CI 编译验证但无真机；虚拟摄像头链路云端完全无法验。

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <ole2.h>
#include <gdiplus.h>

#include "apxpc/media/camera_view.hpp"
#include "apxpc/media/vcam_shm.hpp"

#if defined(__has_include)
#if __has_include(<mfvirtualcamera.h>)
#define APXPC_HAVE_MFVCAM 1
#include <mfvirtualcamera.h>
#endif
#endif

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")

namespace apxpc::media {
namespace {

constexpr wchar_t kWndClass[] = L"AllPeriphCameraViewWnd";
constexpr UINT WM_APP_CLOSEWIN = WM_APP + 21; // 面板线程 → worker：销毁预览窗
constexpr int kCloseBtn = 28;                 // 右上角关闭钮边长（像素）
constexpr int kMaxPreviewDim = 1600;          // 解码上限：手机可能推 1080p+，预览缩到 1600 内

// ------------------------------------------------------------- GDI+ 进程级启动
void gdiplusOnce() {
    static std::once_flag once;
    static Gdiplus::GdiplusStartupInput input;
    static ULONG_PTR token = 0;
    std::call_once(once, [] {
        Gdiplus::GdiplusStartup(&token, &input, nullptr);
    });
}

inline int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

// JPEG → BGRA（原尺寸，超 kMaxPreviewDim 先等比缩小——预览不需要 4K）
bool decodeJpeg(const uint8_t* data, size_t len, std::vector<uint8_t>& bgra,
                uint32_t& w, uint32_t& h) {
    gdiplusOnce();
    IStream* stm = nullptr;
    if (FAILED(CreateStreamOnHGlobal(nullptr, TRUE, &stm)) || !stm) return false;
    ULONG written = 0;
    stm->Write(data, static_cast<ULONG>(len), &written);
    LARGE_INTEGER zero{};
    stm->Seek(zero, STREAM_SEEK_SET, nullptr);

    Gdiplus::Bitmap src(stm, nullptr);
    bool ok = src.GetLastStatus() == Gdiplus::Ok;
    stm->Release();
    if (!ok) return false;

    int sw = static_cast<int>(src.GetWidth());
    int sh = static_cast<int>(src.GetHeight());
    if (sw <= 0 || sh <= 0) return false;
    int dw = sw, dh = sh;
    if (dw > kMaxPreviewDim || dh > kMaxPreviewDim) {
        const double s = static_cast<double>(kMaxPreviewDim) / (dw > dh ? dw : dh);
        dw = static_cast<int>(dw * s);
        dh = static_cast<int>(dh * s);
    }

    // 预览尺寸重采样（DrawImage 走 GDI+ 质量默认，够用）
    Gdiplus::Bitmap dst(dw, dh, PixelFormat32bppARGB);
    if (dst.GetLastStatus() != Gdiplus::Ok) return false;
    {
        Gdiplus::Graphics g(&dst);
        if (g.DrawImage(&src, Gdiplus::Rect(0, 0, dw, dh)) != Gdiplus::Ok) return false;
    }
    Gdiplus::BitmapData bd{};
    Gdiplus::Rect rect(0, 0, dw, dh);
    if (dst.LockBits(&rect, Gdiplus::ImageLockModeRead, PixelFormat32bppARGB, &bd) !=
        Gdiplus::Ok)
        return false;
    bgra.resize(static_cast<size_t>(dw) * dh * 4);
    const uint8_t* srcRow = static_cast<const uint8_t*>(bd.Scan0);
    for (int y = 0; y < dh; ++y) {
        std::memcpy(bgra.data() + static_cast<size_t>(y) * dw * 4,
                    srcRow + static_cast<size_t>(y) * bd.Stride,
                    static_cast<size_t>(dw) * 4);
    }
    dst.UnlockBits(&bd);
    w = static_cast<uint32_t>(dw);
    h = static_cast<uint32_t>(dh);
    return true;
}

// BGRA(1280x720) → NV12（BT.601 limited，2x2 色度均值）—— 只在虚拟摄像头开启时跑
void bgraToNv12(const uint8_t* src, uint8_t* dst) {
    constexpr int W = vcam::kWidth;
    constexpr int H = vcam::kHeight;
    uint8_t* yPlane = dst;
    uint8_t* uvPlane = dst + static_cast<size_t>(W) * H;
    for (int y = 0; y < H; ++y) {
        const uint8_t* row = src + static_cast<size_t>(y) * W * 4;
        uint8_t* yOut = yPlane + static_cast<size_t>(y) * W;
        for (int x = 0; x < W; ++x) {
            const uint8_t* p = row + x * 4; // BGRA
            const int r = p[2], g = p[1], b = p[0];
            yOut[x] = static_cast<uint8_t>(clamp255(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
        }
    }
    for (int y = 0; y < H; y += 2) {
        const uint8_t* r0 = src + static_cast<size_t>(y) * W * 4;
        const uint8_t* r1 = src + static_cast<size_t>(y + 1) * W * 4;
        uint8_t* uv = uvPlane + static_cast<size_t>(y / 2) * W;
        for (int x = 0; x < W; x += 2) {
            int sumU = 0, sumV = 0;
            for (int j = 0; j < 2; ++j) {
                const uint8_t* rr = j ? r1 : r0;
                for (int i = 0; i < 2; ++i) {
                    const uint8_t* p = rr + (x + i) * 4;
                    const int r = p[2], g = p[1], b = p[0];
                    sumU += ((-38 * r - 74 * g + 112 * b + 128) >> 8);
                    sumV += ((112 * r - 94 * g - 18 * b + 128) >> 8);
                }
            }
            uv[x] = static_cast<uint8_t>(clamp255(sumU / 4 + 128));
            uv[x + 1] = static_cast<uint8_t>(clamp255(sumV / 4 + 128));
        }
    }
}

} // namespace

// ===========================================================================
struct CameraView::Impl {
    // ---- 状态（面板 400ms 轮询读；worker 写）
    mutable std::mutex stMx;
    CameraView::Status st;

    // ---- 帧队列（feed 入，worker 出）
    std::mutex qMx;
    std::deque<std::vector<uint8_t>> q;
    static constexpr size_t kQLen = 8;
    HANDLE frameEvt = nullptr; // auto-reset：只作唤醒

    // ---- 生命周期（atomic：面板置位，worker 观察）
    std::atomic<bool> wantPreview{false};
    std::atomic<bool> wantVcam{false};
    std::atomic<bool> workerOn{false};
    std::thread worker;

    // ---- worker 线程私有（窗口/缓冲/shm，不跨线程）
    HWND hwnd = nullptr;
    int winW = 960, winH = 540;
    std::vector<uint8_t> back; // 当前帧 BGRA（worker 线程访问）
    uint32_t bbW = 0, bbH = 0;
    bool haveFrame = false;
    HANDLE shmMap = nullptr;
    void* shmView = nullptr;
    HANDLE readyEvt = nullptr;
    HFONT font = nullptr;

    // ---- 虚拟摄像头对象（面板线程独占）
    HMODULE mfH = nullptr;
#ifdef APXPC_HAVE_MFVCAM
    IMFVirtualCamera* cam = nullptr;
#endif

    ~Impl() {
        stopWorker();
        releaseVcam();
        if (frameEvt) CloseHandle(frameEvt);
        if (font) DeleteObject(font);
    }

    // ------------------------------------------------------------ 面板侧 API
    void feed(const uint8_t* jpeg, size_t len) {
        if (!jpeg || !len) return;
        {
            std::lock_guard<std::mutex> lk(qMx);
            if (q.size() >= kQLen) q.pop_front(); // 丢最旧：预览只要最新帧
            q.emplace_back(jpeg, jpeg + len);
        }
        {
            std::lock_guard<std::mutex> lk(stMx);
            st.bytes += len;
        }
        if (frameEvt) SetEvent(frameEvt);
    }

    void setPreview(bool on) {
        if (on) {
            wantPreview = true;
            startWorker();
            return;
        }
        wantPreview = false;
        if (hwnd) PostMessageW(hwnd, WM_APP_CLOSEWIN, 0, 0); // 从任何线程都安全
        if (!wantVcam) stopWorker();
    }

    void setVirtualCam(bool on) {
        bool needStop = false;
        {
            std::lock_guard<std::mutex> lk(stMx);
            if (on) {
                if (st.virtualCam) return;
                std::string err;
                if (!registerVcam(err)) {
                    st.error = err;
                    st.virtualCamTested = true;
                    return;
                }
                st.error.clear();
                st.virtualCam = true;
                st.virtualCamTested = true;
                wantVcam = true;
            } else {
                if (!st.virtualCam) return;
                st.virtualCam = false;
                wantVcam = false;
                needStop = !wantPreview;
            }
        }
        if (on) startWorker();
        else if (needStop) stopWorker();
        if (!on) releaseVcam(); // 锁外做 COM/注册表清理（join 已完成或不需）
    }

    CameraView::Status status() const {
        std::lock_guard<std::mutex> lk(stMx);
        CameraView::Status s = st;
        s.running = workerOn.load();
        s.previewOpen = hwnd != nullptr;
        const auto now = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch())
                .count());
        s.stale = s.running && (s.frames == 0 || now - s.lastFrameMs > vcam::kStaleMs);
        return s;
    }

    // ------------------------------------------------------------ worker 生命周期
    void startWorker() {
        if (workerOn) return;
        if (worker.joinable()) worker.join(); // 上一轮残留
        workerOn = true;
        worker = std::thread([this] { workerMain(); });
    }
    void stopWorker() {
        wantPreview = false;
        wantVcam = false;
        if (hwnd) PostMessageW(hwnd, WM_APP_CLOSEWIN, 0, 0);
        if (frameEvt) SetEvent(frameEvt);
        if (worker.joinable()) worker.join();
        workerOn = false;
    }

    // ------------------------------------------------------------ c-2 注册与释放
    static std::wstring dllPath() {
        wchar_t mod[MAX_PATH] = {};
        GetModuleFileNameW(nullptr, mod, MAX_PATH);
        std::wstring p(mod);
        const size_t slash = p.find_last_of(L"\\/");
        if (slash != std::wstring::npos) p.resize(slash + 1);
        else p.clear();
        return p + L"apxvcam.dll";
    }

    bool registerVcam(std::string& err) {
        // ① CLSID → apxvcam.dll（HKCU，免管理员；每次开启都重写，防路径漂移）
        const std::wstring dll = dllPath();
        if (GetFileAttributesW(dll.c_str()) == INVALID_FILE_ATTRIBUTES) {
            err = "未找到 apxvcam.dll（与主程序同目录）——虚拟摄像头不可用";
            return false;
        }
        const std::wstring keyPath =
            std::wstring(L"Software\\Classes\\CLSID\\") + kVcamClsid;
        HKEY key = nullptr;
        if (RegCreateKeyExW(HKEY_CURRENT_USER, keyPath.c_str(), 0, nullptr, 0,
                            KEY_WRITE, nullptr, &key, nullptr) != ERROR_SUCCESS) {
            err = "写 HKCU CLSID 注册表失败";
            return false;
        }
        std::wstring sub = L"InprocServer32";
        HKEY subKey = nullptr;
        bool ok = RegCreateKeyExW(key, sub.c_str(), 0, nullptr, 0, KEY_WRITE, nullptr,
                                  &subKey, nullptr) == ERROR_SUCCESS;
        if (ok) {
            const std::wstring model = L"Both";
            ok = RegSetValueExW(subKey, nullptr, 0, REG_SZ,
                                reinterpret_cast<const BYTE*>(dll.c_str()),
                                static_cast<DWORD>((dll.size() + 1) * sizeof(wchar_t)) ==
                                        0
                                    ? 0
                                    : static_cast<DWORD>((dll.size() + 1) * sizeof(wchar_t))) ==
                 ERROR_SUCCESS;
            ok = RegSetValueExW(subKey, L"ThreadingModel", 0, REG_SZ,
                                reinterpret_cast<const BYTE*>(model.c_str()),
                                static_cast<DWORD>((model.size() + 1) * sizeof(wchar_t))) ==
                 ERROR_SUCCESS &&
                 ok;
            RegCloseKey(subKey);
        }
        RegCloseKey(key);
        if (!ok) {
            err = "写 InprocServer32 失败";
            return false;
        }

#ifdef APXPC_HAVE_MFVCAM
        // ② 动态解析 MFCreateVirtualCamera（Win10 无此导出 → 降级而非链接失败）
        if (!mfH) mfH = LoadLibraryW(L"mfsensorgroup.dll");
        if (!mfH) {
            err = "本机无 mfsensorgroup.dll —— 需要 Windows 11 (build 22000+)";
            return false;
        }
        using PFN = HRESULT(WINAPI*)(MFVirtualCameraType, MFVirtualCameraLifetime,
                                     MFVirtualCameraAccess, LPCWSTR, LPCWSTR,
                                     const GUID*, ULONG, IMFVirtualCamera**);
        auto fn = reinterpret_cast<PFN>(
            GetProcAddress(mfH, "MFCreateVirtualCamera"));
        if (!fn) {
            err = "导出 MFCreateVirtualCamera 不存在 —— 需要 Windows 11 (build 22000+)";
            return false;
        }
        MFStartup(MF_VERSION, MFSTARTUP_FULL); // 幂等；MF 对象管理要求
        HRESULT hr = fn(MFVirtualCameraType_SoftwareCameraSource,
                        MFVirtualCameraLifetime_Session, // 进程/会话级，不跨重启
                        MFVirtualCameraAccess_CurrentUser, // 免管理员
                        L"AllPeriph", kVcamClsid, nullptr, 0, &cam);
        if (FAILED(hr) || !cam) {
            wchar_t buf[64];
            swprintf_s(buf, L"MFCreateVirtualCamera 失败 0x%08lX", static_cast<unsigned long>(hr));
            char cbuf[96];
            wcstombs(cbuf, buf, sizeof(cbuf));
            err = cbuf; // 隐私控制拒绝摄像头/参数非法都落这里，如实展示
            return false;
        }
        hr = cam->Start(); // 真正创建并注册到系统
        if (FAILED(hr)) {
            wchar_t buf[64];
            swprintf_s(buf, L"IMFVirtualCamera::Start 失败 0x%08lX", static_cast<unsigned long>(hr));
            char cbuf[96];
            wcstombs(cbuf, buf, sizeof(cbuf));
            err = cbuf;
            cam->Release();
            cam = nullptr;
            return false;
        }
        return true;
#else
        err = "当前 Windows SDK 缺 mfvirtualcamera.h（需 Win11 SDK）——虚拟摄像头未编入";
        return false;
#endif
    }

    void releaseVcam() {
#ifdef APXPC_HAVE_MFVCAM
        if (cam) {
            cam->Remove();    // 从系统注销（Session 生命周期其实也会随释放消失，显式更干净）
            cam->Shutdown();  // 释放内部资源
            cam->Release();
            cam = nullptr;
        }
#endif
        // CLSID 注册项删干净（下次开启重写）
        const std::wstring keyPath =
            std::wstring(L"Software\\Classes\\CLSID\\") + kVcamClsid;
        RegDeleteTreeW(HKEY_CURRENT_USER, keyPath.c_str());
        // mfH 不 Free：进程内反复开关，留着幂等
    }

    // ------------------------------------------------------------ worker 主体
    void workerMain() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        static std::atomic<bool> classRegistered{false};
        if (!classRegistered) {
            WNDCLASSEXW wc{};
            wc.cbSize = sizeof(wc);
            wc.style = CS_HREDRAW | CS_VREDRAW;
            wc.lpfnWndProc = &Impl::wndProc;
            wc.hInstance = GetModuleHandleW(nullptr);
            wc.hCursor = LoadCursor(nullptr, IDC_ARROW); // 泛型宏：随项目字符集配对（ball 同款修法）
            wc.hbrBackground = nullptr;                  // 自绘
            wc.lpszClassName = kWndClass;
            if (RegisterClassExW(&wc)) classRegistered = true;
        }
        if (wantPreview && classRegistered) {
            hwnd = CreateWindowExW(
                WS_EX_TOPMOST | WS_EX_TOOLWINDOW, kWndClass, L"手机摄像头",
                WS_POPUP | WS_VISIBLE, CW_USEDEFAULT, CW_USEDEFAULT, winW, winH,
                nullptr, nullptr, GetModuleHandleW(nullptr), this);
            SetTimer(hwnd, 1, 100, nullptr); // stale 检测 + 重绘节流
            {
                std::lock_guard<std::mutex> lk(stMx);
                st.previewOpen = hwnd != nullptr;
            }
        }

        // 消息泵 + 帧事件：同线程处理消息与帧，无跨线程 GDI
        while (wantVcam || wantPreview) {
            const DWORD r = MsgWaitForMultipleObjects(1, &frameEvt, FALSE, INFINITE,
                                                      QS_ALLINPUT);
            bool pump = (r == WAIT_OBJECT_0 + 1);
            bool frame = (r == WAIT_OBJECT_0);
            MSG msg;
            while (pump && PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
                if (msg.message == WM_QUIT) { wantPreview = false; break; }
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
            if (frame) drainFrame();
            // 事件与消息可能合并醒来：各查一次不丢（peek 循环已空）
            if (r == WAIT_OBJECT_0) { /* 已处理 */ }
        }

        if (hwnd) {
            DestroyWindow(hwnd);
            hwnd = nullptr;
        }
        closeShm();
        {
            std::lock_guard<std::mutex> lk(stMx);
            st.previewOpen = false;
        }
        workerOn = false;
        CoUninitialize();
    }

    // 只取最新一帧（中途的直接丢——预览/虚拟摄像头都是实时语义）
    void drainFrame() {
        std::vector<uint8_t> frame;
        {
            std::lock_guard<std::mutex> lk(qMx);
            if (q.empty()) return;
            frame = std::move(q.back());
            q.clear();
        }
        std::vector<uint8_t> bgra;
        uint32_t w = 0, h = 0;
        if (!decodeJpeg(frame.data(), frame.size(), bgra, w, h)) {
            std::lock_guard<std::mutex> lk(stMx);
            st.error = "JPEG 解码失败";
            return;
        }
        back.swap(bgra);
        bbW = w;
        bbH = h;
        haveFrame = true;
        {
            std::lock_guard<std::mutex> lk(stMx);
            st.frames++;
            st.width = w;
            st.height = h;
            st.lastFrameMs = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch())
                    .count());
        }
        if (hwnd) InvalidateRect(hwnd, nullptr, FALSE);

        if (wantVcam) writeToShm();
    }

    // 虚拟摄像头写入：等比缩到 1280x720 → NV12 → shm（seq 递增 + 唤醒事件）
    void writeToShm() {
        if (!openShm()) return;
        gdiplusOnce();
        // 需要 1280x720 的 BGRA：用 GDI+ 缩（back 可能不是 16:9，直接拉伸——
        // 虚拟摄像头侧画面形变不可接受，做等比 + 黑边）
        static std::vector<uint8_t> scratch;
        scratch.resize(static_cast<size_t>(vcam::kWidth) * vcam::kHeight * 4);
        {
            Gdiplus::Bitmap src(bbW, bbH, PixelFormat32bppARGB);
            Gdiplus::BitmapData bd{};
            Gdiplus::Rect r(0, 0, static_cast<int>(bbW), static_cast<int>(bbH));
            if (src.LockBits(&r, Gdiplus::ImageLockModeWrite, PixelFormat32bppARGB, &bd)
                != Gdiplus::Ok)
                return;
            const size_t rowBytes = static_cast<size_t>(bbW) * 4;
            for (uint32_t y = 0; y < bbH; ++y) {
                std::memcpy(static_cast<uint8_t*>(bd.Scan0) + static_cast<size_t>(y) * bd.Stride,
                            back.data() + static_cast<size_t>(y) * rowBytes, rowBytes);
            }
            src.UnlockBits(&bd);

            Gdiplus::Bitmap dst(vcam::kWidth, vcam::kHeight, PixelFormat32bppARGB);
            Gdiplus::Graphics g(&dst);
            g.SetCompositingMode(Gdiplus::CompositingModeSourceCopy);
            // 等比 + 黑边（letterbox）
            const double sa = static_cast<double>(bbW) / bbH;
            const double da = static_cast<double>(vcam::kWidth) / vcam::kHeight;
            int dw = vcam::kWidth, dh = vcam::kHeight, ox = 0, oy = 0;
            if (sa > da) { dh = static_cast<int>(vcam::kWidth / sa); oy = (vcam::kHeight - dh) / 2; }
            else         { dw = static_cast<int>(vcam::kHeight * sa); ox = (vcam::kWidth - dw) / 2; }
            Gdiplus::SolidBrush black(Gdiplus::Color(255, 0, 0, 0));
            g.FillRectangle(&black, 0, 0, vcam::kWidth, vcam::kHeight);
            if (g.DrawImage(&src, Gdiplus::Rect(ox, oy, dw, dh)) != Gdiplus::Ok) return;

            Gdiplus::BitmapData dd{};
            Gdiplus::Rect dr(0, 0, vcam::kWidth, vcam::kHeight);
            if (dst.LockBits(&dr, Gdiplus::ImageLockModeRead, PixelFormat32bppARGB, &dd)
                != Gdiplus::Ok)
                return;
            static std::vector<uint8_t> scaled;
            scaled.resize(static_cast<size_t>(vcam::kWidth) * vcam::kHeight * 4);
            for (int y = 0; y < vcam::kHeight; ++y) {
                std::memcpy(scaled.data() + static_cast<size_t>(y) * vcam::kWidth * 4,
                            static_cast<const uint8_t*>(dd.Scan0) +
                                static_cast<size_t>(y) * dd.Stride,
                            static_cast<size_t>(vcam::kWidth) * 4);
            }
            dst.UnlockBits(&dd);
        }

        auto* hdr = static_cast<vcam::VcamShmHeader*>(shmView);
        uint8_t* payload = reinterpret_cast<uint8_t*>(hdr) + sizeof(vcam::VcamShmHeader);
        bgraToNv12(scaled.data(), payload); // 先填像素再递增 seq：消费端 seq 校验防撕裂
        hdr->seq++;
        hdr->frameCount++;
        hdr->ptsMs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch())
                .count());
        if (readyEvt) SetEvent(readyEvt);
    }

    bool openShm() {
        if (shmView) return true;
        const size_t total = sizeof(vcam::VcamShmHeader) + vcam::kNv12Size;
        shmMap = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE,
                                    0, static_cast<DWORD>(total), vcam::kShmName);
        if (!shmMap) return false;
        shmView = MapViewOfFile(shmMap, FILE_MAP_ALL_ACCESS, 0, 0, total);
        if (!shmView) {
            CloseHandle(shmMap);
            shmMap = nullptr;
            return false;
        }
        auto* hdr = static_cast<vcam::VcamShmHeader*>(shmView);
        if (hdr->magic != vcam::kShmMagic) {
            std::memset(hdr, 0, total);
            hdr->magic = vcam::kShmMagic;
            hdr->width = vcam::kWidth;
            hdr->height = vcam::kHeight;
            hdr->stride = vcam::kStride;
            hdr->nv12Size = vcam::kNv12Size;
            hdr->seq = 0; // 0 = 还没写过帧（消费端出黑帧）
        }
        readyEvt = CreateEventW(nullptr, FALSE, FALSE, vcam::kReadyEventName);
        return true;
    }
    void closeShm() {
        if (shmView) { UnmapViewOfFile(shmView); shmView = nullptr; }
        if (shmMap) { CloseHandle(shmMap); shmMap = nullptr; }
        if (readyEvt) { CloseHandle(readyEvt); readyEvt = nullptr; }
    }

    // ------------------------------------------------------------ 窗口过程
    static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
        Impl* self = nullptr;
        if (msg == WM_NCCREATE) {
            auto* cs = reinterpret_cast<CREATESTRUCTW*>(lp);
            self = static_cast<Impl*>(cs->lpCreateParams);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
            self->hwnd = hwnd;
        } else {
            self = reinterpret_cast<Impl*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        }
        switch (msg) {
        case WM_APP_CLOSEWIN:
            DestroyWindow(hwnd);
            if (self) self->hwnd = nullptr;
            return 0;
        case WM_TIMER:
            if (self) InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_NCHITTEST: {
            // 无边框可拖：客户区 = HTCAPTION；右上角留给关闭钮（返回 HTCLIENT）
            const POINT pt = { GET_X_LPARAM(lp), GET_Y_LPARAM(lp) };
            RECT rc{};
            GetWindowRect(hwnd, &rc);
            if (pt.x >= rc.right - rc.left - kCloseBtn && pt.y - rc.top < kCloseBtn)
                return HTCLIENT;
            return HTCAPTION;
        }
        case WM_LBUTTONDOWN: {
            if (!self) break;
            const int x = GET_X_LPARAM(wp == 0 ? 0 : 0); // placeholder，见下行实际取参
            (void)x;
            const POINT pt = { GET_X_LPARAM(lp), GET_Y_LPARAM(lp) };
            RECT rc{};
            GetClientRect(hwnd, &rc);
            if (pt.x >= rc.right - kCloseBtn && pt.y < kCloseBtn) {
                // 用户关预览：置标志 + 销窗；worker 是否退出由 wantVcam 决定
                self->wantPreview = false;
                DestroyWindow(hwnd);
                self->hwnd = nullptr;
                return 0;
            }
            break;
        }
        case WM_PAINT:
            if (self) self->paint(hwnd);
            return 0;
        case WM_ERASEBKGND:
            return 1; // 自绘，擦除在 paint 里做（防闪）
        case WM_DESTROY:
            if (self) {
                KillTimer(hwnd, 1);
                self->hwnd = nullptr;
                std::lock_guard<std::mutex> lk(self->stMx);
                self->st.previewOpen = false;
            }
            return 0;
        default:
            break;
        }
        return DefWindowProcW(hwnd, msg, wp, lp);
    }

    void paint(HWND hwnd) {
        PAINTSTRUCT ps{};
        HDC dc = BeginPaint(hwnd, &ps);
        RECT rc{};
        GetClientRect(hwnd, &rc);
        const int cw = rc.right - rc.left;
        const int ch = rc.bottom - rc.top;

        // 双缓冲：mem DC + DIB（GDI 对象全部本线程创建，无跨线程问题）
        HDC mem = CreateCompatibleDC(dc);
        HBITMAP bmp = CreateCompatibleBitmap(dc, cw, ch);
        HGDIOBJ old = SelectObject(mem, bmp);

        HBRUSH bg = CreateSolidBrush(RGB(17, 17, 20)); // 对齐面板暗底色
        FillRect(mem, &rc, bg);
        DeleteObject(bg);

        CameraView::Status s;
        { std::lock_guard<std::mutex> lk(stMx); s = st; }

        if (haveFrame && bbW && bbH) {
            // 等比 letterbox 画帧
            const double sa = static_cast<double>(bbW) / bbH;
            const double da = static_cast<double>(cw) / ch;
            int dw = cw, dh = ch, ox = 0, oy = 0;
            if (sa > da) { dh = static_cast<int>(cw / sa); oy = (ch - dh) / 2; }
            else         { dw = static_cast<int>(ch * sa); ox = (cw - dw) / 2; }
            BITMAPINFO bi{};
            bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
            bi.bmiHeader.biWidth = static_cast<LONG>(bbW);
            bi.bmiHeader.biHeight = -static_cast<LONG>(bbH); // top-down
            bi.bmiHeader.biPlanes = 1;
            bi.bmiHeader.biBitCount = 32;
            bi.bmiHeader.biCompression = BI_RGB;
            SetStretchBltMode(mem, HALFTONE);
            StretchDIBits(mem, ox, oy, dw, dh, 0, 0, static_cast<int>(bbW),
                          static_cast<int>(bbH), back.data(), &bi, DIB_RGB_COLORS,
                          SRCCOPY);
        } else {
            SetBkMode(mem, TRANSPARENT);
            RECT c = rc;
            if (!font) {
                font = CreateFontW(-18, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                                   DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                   CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                                   L"Segoe UI");
            }
            HGDIOBJ oldFont = SelectObject(mem, font);
            SetTextColor(mem, RGB(150, 150, 160));
            const wchar_t* msg = s.frames == 0 ? L"等待手机画面…（打开手机端摄像头采集页）"
                                               : L"画面中断 —— 等待下一帧";
            DrawTextW(mem, msg, -1, &c, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
            SelectObject(mem, oldFont);
        }

        // 底部状态条（30fps 帧率更新快，这里靠 WM_TIMER 100ms 刷新已够）
        {
            SetBkMode(mem, TRANSPARENT);
            if (!font) {
                font = CreateFontW(-18, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                                   DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                   CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                                   L"Segoe UI");
            }
            HGDIOBJ oldFont = SelectObject(mem, font);
            SetTextColor(mem, RGB(220, 220, 230));
            wchar_t line[128];
            swprintf_s(line, L"AllPeriph 手机摄像头 · %ux%u%s%s",
                       s.width, s.height,
                       s.virtualCam ? L" · 虚拟摄像头" : L"",
                       s.stale ? L" · 画面停滞" : L"");
            RECT tr{ 8, ch - 30, cw - 8, ch - 6 };
            DrawTextW(mem, line, -1, &tr, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
            SelectObject(mem, oldFont);
        }

        // 右上角关闭钮（×）
        {
            HPEN pen = CreatePen(PS_SOLID, 2, RGB(220, 220, 230));
            HGDIOBJ oldPen = SelectObject(mem, pen);
            const int m = 8;
            MoveToEx(mem, cw - kCloseBtn + m, m, nullptr);
            LineTo(mem, cw - m, kCloseBtn - m);
            MoveToEx(mem, cw - m, m, nullptr);
            LineTo(mem, cw - kCloseBtn + m, kCloseBtn - m);
            SelectObject(mem, oldPen);
            DeleteObject(pen);
        }

        BitBlt(dc, 0, 0, cw, ch, mem, 0, 0, SRCCOPY);
        SelectObject(mem, old);
        DeleteObject(bmp);
        DeleteDC(mem);
        EndPaint(hwnd, &ps);
    }
};

// ================================================================= 对外接口
CameraView::CameraView() : impl_(new Impl) { impl_->frameEvt = CreateEventW(nullptr, FALSE, FALSE, nullptr); }
CameraView::~CameraView() = default;

void CameraView::feed(const uint8_t* jpeg, size_t len) { impl_->feed(jpeg, len); }
void CameraView::setPreview(bool on) { impl_->setPreview(on); }
void CameraView::setVirtualCam(bool on) { impl_->setVirtualCam(on); }
CameraView::Status CameraView::status() const { return impl_->status(); }

} // namespace apxpc::media
