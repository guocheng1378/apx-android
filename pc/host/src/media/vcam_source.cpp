// 虚拟摄像头 Media Source（M7-c c-2 的 consumer 侧）—— 独立 in-proc DLL。
//
// 进程模型（为什么必须拆 DLL）：MFCreateVirtualCamera(sourceId = CLSID) 注册后，
// 微信/会议端打开这台摄像头时，是在**它们自己的进程**里 CoCreateInstance 本 DLL；
// 主程序内存它摸不到 → 帧只能走 vcam_shm.hpp 定义的共享内存。
//
// 本 DLL 只干一件事：把共享内存里的最新 NV12 帧（或无数据时的黑帧）喂成 MF 样本。
// 生产端（创建/移除注册 + 写帧）在 camera_view.cpp。注册表（HKCU，免管理员）由
// 主程序在开启虚拟摄像头前写入 InprocServer32 → 本 DLL 绝对路径。
//
// 实现依据（均已逐项核对 learn.microsoft.com，2026-11-06）：
//   · IMFMediaSource::Start(pd, pguidTimeFormat, pvarStartPosition) —— 3 参无 callback；
//     首次启动事件序列 MENewStream → MESourceStarted → MEStreamStarted；
//   · RequestSample(pToken)：token FIFO，交付时给 sample 打 MFSampleExtension_Token，
//     事件是 MEMediaSample（VT_UNKNOWN = IMFSample*），交付后 Release token；
//   · 停止状态再收 RequestSample → 返回 MF_E_MEDIA_SOURCE_WRONGSTATE（管线不视为错误）。
//
// ⚠️ 未真机实测：云端无 Win11 + 无微信/会议端，CI 只验编译；面板上标「未实测」。

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>

#include "apxpc/media/vcam_shm.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>

// 固定 CLSID：必须与 camera_view.hpp 的 kVcamClsid 逐字一致（那边是字符串形式，
// 供注册表与 MFCreateVirtualCamera 用；这里 C++ 侧比较用）。
static const CLSID kClsidApxVcamSource =
    { 0x7A3C9C1E, 0x5B42, 0x4F8A, { 0x9D, 0x61, 0x2E, 0x8F, 0x0B, 0x4A, 0x6C, 0x11 } };

namespace {

long g_dllRef = 0; // DLL 级引用计数（DllCanUnloadNow）

inline void Dbg(const char* msg) {
    OutputDebugStringA("[apxvcam] ");
    OutputDebugStringA(msg);
    OutputDebugStringA("\n");
}

// MF helper 函数（MFCreateEventQueue 等）要求进程做过 MFStartup；宿主（微信/会议端）
// 通常已调，但不保证——LITE 级幂等补一次，配对的 Shutdown 不做（进程退出自然回收）。
void ensureMfStartup() {
    static std::once_flag once;
    std::call_once(once, [] { MFStartup(MF_VERSION, MFSTARTUP_LITE); });
}

// ---------------------------------------------------------------- 共享内存读取
// 消费端永远只要最新帧：seq 单调递增判断新旧，事件只是唤醒（丢了不补）。
class ShmReader {
public:
    bool open() {
        if (map_) return true;
        handle_ = OpenFileMappingW(FILE_MAP_READ, FALSE, apxpc::media::vcam::kShmName);
        if (!handle_) return false;
        map_ = MapViewOfFile(handle_, FILE_MAP_READ, 0, 0,
                             sizeof(apxpc::media::vcam::VcamShmHeader) +
                                 apxpc::media::vcam::kNv12Size);
        if (!map_) {
            CloseHandle(handle_);
            handle_ = nullptr;
            return false;
        }
        hdr_ = static_cast<apxpc::media::vcam::VcamShmHeader*>(map_);
        return true;
    }
    void close() {
        if (map_) { UnmapViewOfFile(map_); map_ = nullptr; hdr_ = nullptr; }
        if (handle_) { CloseHandle(handle_); handle_ = nullptr; }
    }
    ~ShmReader() { close(); }

    // dst 至少 kNv12Size 字节；返回 false = 还没有任何帧（producer 没写过/没映射）。
    bool readLatest(uint8_t* dst) {
        if (!hdr_) return false;
        const uint64_t seq = hdr_->seq;
        if (seq == 0 || hdr_->magic != apxpc::media::vcam::kShmMagic) return false;
        std::memcpy(dst, reinterpret_cast<const uint8_t*>(hdr_) +
                             sizeof(apxpc::media::vcam::VcamShmHeader),
                    apxpc::media::vcam::kNv12Size);
        return hdr_->seq == seq; // 读到一半被覆写：放弃这帧，下一拍再来
    }

private:
    HANDLE handle_ = nullptr;
    void* map_ = nullptr;
    apxpc::media::vcam::VcamShmHeader* hdr_ = nullptr;
};

// ------------------------------------------------------------ 事件生成器公共件
class EventGen {
protected:
    HRESULT ensureQueue() {
        std::lock_guard<std::mutex> lk(qm_);
        if (!queue_) return MFCreateEventQueue(&queue_);
        return S_OK;
    }
    void shutdownQueue() {
        std::lock_guard<std::mutex> lk(qm_);
        if (queue_) { queue_->Shutdown(); queue_->Release(); queue_ = nullptr; }
    }
    ~EventGen() { shutdownQueue(); }
    HRESULT queueEvent(REFGUID ext, REFGUID sub, HRESULT st, const PROPVARIANT* pv) {
        std::lock_guard<std::mutex> lk(qm_);
        if (!queue_) return MF_E_NOT_INITIALIZED;
        return queue_->QueueEventParamVar(ext, sub, st, pv);
    }
public:
    // IMFMediaEventGenerator
    HRESULT GetEvent(DWORD dwFlags, IMFMediaEvent** ppEvent) {
        std::lock_guard<std::mutex> lk(qm_);
        if (!queue_) return MF_E_NOT_INITIALIZED;
        return queue_->GetEvent(dwFlags, ppEvent);
    }
    HRESULT QueueEvent(REFGUID ext, REFGUID sub, HRESULT st, const PROPVARIANT* pv) {
        return queueEvent(ext, sub, st, pv);
    }
    HRESULT BeginGetEvent(IMFAsyncCallback* pCallback, IUnknown* punkState) {
        std::lock_guard<std::mutex> lk(qm_);
        if (!queue_) return MF_E_NOT_INITIALIZED;
        return queue_->BeginGetEvent(pCallback, punkState);
    }
    HRESULT EndGetEvent(IMFAsyncResult* pResult, IMFMediaEvent** ppEvent) {
        std::lock_guard<std::mutex> lk(qm_);
        if (!queue_) return MF_E_NOT_INITIALIZED;
        return queue_->EndGetEvent(pResult, ppEvent);
    }
private:
    std::mutex qm_;
    IMFMediaEventQueue* queue_ = nullptr;
};

inline PROPVARIANT emptyVar() {
    PROPVARIANT v;
    PropVariantInit(&v);
    v.vt = VT_EMPTY;
    return v;
}

class MediaSource;

// ------------------------------------------------------------------- 媒体流
class MediaStream : public IMFMediaStream, public EventGen {
public:
    // pd/sd 以已 AddRef 的形式接管一份引用（dtor 里 Release）
    MediaStream(MediaSource* src, IMFPresentationDescriptor* pd, IMFStreamDescriptor* sd)
        : src_(src), pd_(pd), sd_(sd) {
        InterlockedIncrement(&g_dllRef);
    }

    // IMFUnknown
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IMFMediaStream ||
            riid == IID_IMFMediaEventGenerator) {
            *ppv = static_cast<IMFMediaStream*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&ref_); }
    ULONG STDMETHODCALLTYPE Release() override {
        if (InterlockedDecrement(&ref_) == 0) { delete this; return 0; }
        return static_cast<ULONG>(ref_);
    }

    // IMFMediaStream
    HRESULT STDMETHODCALLTYPE GetMediaSource(IMFMediaSource** ppSource) override {
        if (!ppSource) return E_POINTER;
        if (!src_) return MF_E_SHUTDOWN;
        *ppSource = static_cast<IMFMediaSource*>(src_);
        src_->AddRef();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetStreamDescriptor(IMFStreamDescriptor** ppSD) override {
        if (!ppSD) return E_POINTER;
        *ppSD = sd_;
        if (sd_) { sd_->AddRef(); return S_OK; }
        return E_POINTER;
    }
    HRESULT STDMETHODCALLTYPE RequestSample(IUnknown* punkToken) override {
        if (closed_) return MF_E_SHUTDOWN;
        if (stopped_) return MF_E_MEDIA_SOURCE_WRONGSTATE; // 停止态再要样本：管线不视为错误
        {
            std::lock_guard<std::mutex> lk(mu_);
            pending_++;
            if (punkToken) { punkToken->AddRef(); tokens_.push_back(punkToken); }
        }
        cv_.notify_all();
        return S_OK;
    }

    // IMFMediaEventGenerator → EventGen
    HRESULT GetEvent(DWORD f, IMFMediaEvent** e) override { return EventGen::GetEvent(f, e); }
    HRESULT QueueEvent(REFGUID a, REFGUID b, HRESULT c, const PROPVARIANT* d) override {
        return EventGen::QueueEvent(a, b, c, d);
    }
    HRESULT BeginGetEvent(IMFAsyncCallback* c, IUnknown* s) override {
        return EventGen::BeginGetEvent(c, s);
    }
    HRESULT EndGetEvent(IMFAsyncResult* r, IMFMediaEvent** e) override {
        return EventGen::EndGetEvent(r, e);
    }

    // ---- source 的状态机调用
    HRESULT startSignals() {
        HRESULT hr = ensureQueue();
        if (FAILED(hr)) return hr;
        stopped_ = false;
        closed_ = false;
        return S_OK;
    }
    void pausedSignals() { stopped_ = false; } // 暂停态仍可收请求，只是不交付
    void stopSignals() {
        stopped_ = true;
        std::lock_guard<std::mutex> lk(mu_);
        releaseTokensLocked();
        pending_ = 0;
    }
    void shutdownSignals() {
        closed_ = true;
        stopped_ = true;
        std::lock_guard<std::mutex> lk(mu_);
        releaseTokensLocked();
        pending_ = 0;
        cv_.notify_all();
    }

    // 推线程调用：有 pending request 且源在跑就交一帧（无帧交黑帧，不卡宿主）
    bool deliverOne(ShmReader& shm) {
        IUnknown* token = nullptr;
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (pending_ == 0 || stopped_ || closed_) return false;
            pending_--;
            if (!tokens_.empty()) { token = tokens_.front(); tokens_.pop_front(); }
        }

        static thread_local uint8_t buf[apxpc::media::vcam::kNv12Size];
        if (!shm.open() || !shm.readLatest(buf)) {
            // 黑帧：Y=16（limited range 黑）、UV=128（中性色差）——比纯 0 更标准
            const size_t y = static_cast<size_t>(apxpc::media::vcam::kWidth) *
                             apxpc::media::vcam::kHeight;
            std::memset(buf, 16, y);
            std::memset(buf + y, 128, y / 2);
        }

        IMFMediaBuffer* mb = nullptr;
        if (FAILED(MFCreateMemoryBuffer(apxpc::media::vcam::kNv12Size, &mb))) {
            if (token) token->Release();
            return false;
        }
        BYTE* dst = nullptr; DWORD max = 0, cur = 0;
        if (SUCCEEDED(mb->Lock(&dst, &max, &cur)) && dst) {
            std::memcpy(dst, buf, apxpc::media::vcam::kNv12Size);
            mb->Unlock();
            mb->SetCurrentLength(apxpc::media::vcam::kNv12Size);
        }

        IMFSample* sample = nullptr;
        if (FAILED(MFCreateSample(&sample)) || !sample) {
            mb->Release();
            if (token) token->Release();
            return false;
        }
        sample->AddBuffer(mb);
        mb->Release();
        const LONGLONG t = static_cast<LONGLONG>(frameIdx_++) * 333333; // 30fps，100ns 单位
        sample->SetSampleTime(t);
        sample->SetSampleDuration(333333);
        if (token) {
            // 规范：token 指针打成 blob 属性，事件交付后 Release（见 RequestSample 文档）
            sample->SetBlob(MFSampleExtension_Token, reinterpret_cast<const UINT8*>(&token),
                            sizeof(token));
        }
        PROPVARIANT v = emptyVar();
        v.vt = VT_UNKNOWN;
        v.punkVal = sample;
        sample->AddRef();
        queueEvent(MEMediaSample, GUID_NULL, S_OK, &v); // 事件值 = IMFSample*（VT_UNKNOWN）
        PropVariantClear(&v);
        sample->Release();
        if (token) token->Release();
        return true;
    }

    IMFStreamDescriptor* descriptor() const { return sd_; }
    void wake() { cv_.notify_all(); }
    void waitForRequest(DWORD ms) {
        std::unique_lock<std::mutex> lk(mu_);
        if (pending_ > 0 || stopped_ || closed_) return;
        cv_.wait_for(lk, std::chrono::milliseconds(ms));
    }

private:
    ~MediaStream() {
        {
            std::lock_guard<std::mutex> lk(mu_);
            releaseTokensLocked();
        }
        if (sd_) sd_->Release();
        if (pd_) pd_->Release();
        InterlockedDecrement(&g_dllRef);
    }
    void releaseTokensLocked() {
        for (IUnknown* t : tokens_) if (t) t->Release();
        tokens_.clear();
    }

    std::atomic<long> ref_{1};
    MediaSource* src_; // 非 owning（source 生存期 ≥ stream）
    IMFPresentationDescriptor* pd_ = nullptr;
    IMFStreamDescriptor* sd_ = nullptr;
    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::deque<IUnknown*> tokens_; // RequestSample 的 token FIFO
    uint32_t pending_ = 0;
    bool stopped_ = false; // 停止态：拒绝新请求
    bool closed_ = false;  // shutdown：永久拒绝
    uint64_t frameIdx_ = 0;
};

// ------------------------------------------------------------------- 媒体源
class MediaSource : public IMFMediaSource, public EventGen {
public:
    MediaSource() {
        InterlockedIncrement(&g_dllRef);
        ensureMfStartup();
        // 单流描述：NV12 1280x720@30（固定格式，producer 负责把手机画面缩进来）
        IMFMediaType* type = nullptr;
        if (SUCCEEDED(MFCreateMediaType(&type)) && type) {
            type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
            type->SetUINT32(MF_MT_FRAME_SIZE,
                            (apxpc::media::vcam::kWidth << 16) | apxpc::media::vcam::kHeight);
            type->SetUINT32(MF_MT_DEFAULT_STRIDE, apxpc::media::vcam::kStride);
            type->SetRATIO(MF_MT_FRAME_RATE, 30, 1);
            type->SetRATIO(MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
            type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
            MFCreateStreamDescriptor(0 /*stream id*/, 1 /*count*/, &type, &sd_);
            type->Release();
        }
        if (sd_) {
            MFCreatePresentationDescriptor(1, &sd_, &pd_);
            if (pd_) pd_->SelectStream(0);
        }
        if (pd_ && sd_) {
            pd_->AddRef();
            sd_->AddRef(); // MediaStream 持有自己的一份
            stream_ = new MediaStream(this, pd_, sd_);
        }
        if (SUCCEEDED(ensureQueue())) {
            stopFlag_ = false;
            pushThread_ = std::thread([this] { pushLoop(); });
        }
    }

    // IMFUnknown
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IMFMediaSource ||
            riid == IID_IMFMediaEventGenerator) {
            *ppv = static_cast<IMFMediaSource*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&ref_); }
    ULONG STDMETHODCALLTYPE Release() override {
        if (InterlockedDecrement(&ref_) == 0) { delete this; return 0; }
        return static_cast<ULONG>(ref_);
    }

    // IMFMediaSource
    HRESULT STDMETHODCALLTYPE GetCharacteristics(DWORD* pdwCharacteristics) override {
        if (!pdwCharacteristics) return E_POINTER;
        *pdwCharacteristics = MEDIASOURCE_IS_LIVE; // 不可 seek（未声明 CAN_SEEK）
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE CreatePresentationDescriptor(
        IMFPresentationDescriptor** ppPresentationDescriptor) override {
        if (!ppPresentationDescriptor) return E_POINTER;
        if (!pd_) return MF_E_NOT_INITIALIZED;
        *ppPresentationDescriptor = pd_;
        pd_->AddRef();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Start(IMFPresentationDescriptor* pPresentationDescriptor,
                                    const GUID* pguidTimeFormat,
                                    const PROPVARIANT* pvarStartPosition) override {
        (void)pPresentationDescriptor; // 单流恒选中；宿主可往 PD 里塞私有属性（不检查）
        if (state_ == kShutdown) return MF_E_SHUTDOWN;
        const GUID fmt = pguidTimeFormat ? *pguidTimeFormat : GUID_NULL;
        if (fmt != GUID_NULL) return MF_E_UNSUPPORTED_TIME_FORMAT;
        // live 源不 seek：非 VT_EMPTY 的起始位直接拒（未声明 CAN_SEEK，合法返回）
        if (pvarStartPosition && pvarStartPosition->vt != VT_EMPTY)
            return MF_E_INVALIDREQUEST;
        if (!stream_) return MF_E_NOT_INITIALIZED;
        HRESULT hr = ensureQueue();
        if (FAILED(hr)) return hr;
        hr = stream_->startSignals();
        if (FAILED(hr)) return hr;
        const bool firstStart = firstStart_;
        if (firstStart) {
            firstStart_ = false;
            PROPVARIANT unk = emptyVar();
            unk.vt = VT_UNKNOWN;
            unk.punkVal = stream_;
            stream_->AddRef();
            queueEvent(MENewStream, GUID_NULL, S_OK, &unk); // 首次：事件值 = IMFMediaStream*
            PropVariantClear(&unk);
        }
        state_ = kStarted;
        PROPVARIANT e = emptyVar();
        queueEvent(MESourceStarted, GUID_NULL, S_OK, &e);
        stream_->QueueEvent(MEStreamStarted, GUID_NULL, S_OK, &e);
        stream_->wake();
        Dbg(firstStart ? "source started (first)" : "source resumed");
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Stop(IMFPresentationDescriptor* pPresentationDescriptor) override {
        (void)pPresentationDescriptor;
        if (state_ == kShutdown) return MF_E_SHUTDOWN;
        state_ = kStopped;
        if (stream_) {
            stream_->stopSignals();
            PROPVARIANT e = emptyVar();
            stream_->QueueEvent(MEStreamStopped, GUID_NULL, S_OK, &e);
            queueEvent(MESourceStopped, GUID_NULL, S_OK, &e);
        }
        Dbg("source stopped");
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Pause() override {
        if (state_ == kShutdown) return MF_E_SHUTDOWN;
        if (state_ == kStopped) return MF_E_INVALIDREQUEST;
        state_ = kPaused;
        if (stream_) {
            stream_->pausedSignals();
            PROPVARIANT e = emptyVar();
            stream_->QueueEvent(MEStreamPaused, GUID_NULL, S_OK, &e);
            queueEvent(MESourcePaused, GUID_NULL, S_OK, &e);
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Shutdown() override {
        if (state_ == kShutdown) return S_OK;
        state_ = kShutdown;
        stopFlag_ = true;
        if (stream_) stream_->wake();
        if (pushThread_.joinable()) pushThread_.join(); // 先停线程，再拆对象
        if (stream_) {
            stream_->shutdownSignals();
            stream_->shutdownQueue(); // Shutdown 后不再发事件
            stream_->Release();
            stream_ = nullptr;
        }
        shutdownQueue();
        if (pd_) { pd_->Release(); pd_ = nullptr; }
        if (sd_) { sd_->Release(); sd_ = nullptr; }
        Dbg("source shutdown");
        return S_OK;
    }

private:
    ~MediaSource() {
        Shutdown();
        InterlockedDecrement(&g_dllRef);
    }
    // 常驻推线程：只有 started 状态且有 RequestSample 在等才交付
    void pushLoop() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        ShmReader shm;
        while (!stopFlag_) {
            if (state_ != kStarted || !stream_) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                continue;
            }
            stream_->waitForRequest(60);
            if (state_ == kStarted) stream_->deliverOne(shm);
        }
        CoUninitialize();
    }

    enum State { kStopped, kStarted, kPaused, kShutdown };
    std::atomic<long> ref_{1};
    State state_ = kStopped;
    bool firstStart_ = true; // 首次 Start 才发 MENewStream
    IMFPresentationDescriptor* pd_ = nullptr;
    IMFStreamDescriptor* sd_ = nullptr;
    MediaStream* stream_ = nullptr;
    std::atomic<bool> stopFlag_{true};
    std::thread pushThread_;
};

// ------------------------------------------------------------------ COM 类工厂
class ClassFactory : public IClassFactory {
public:
    ClassFactory() { InterlockedIncrement(&g_dllRef); }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&ref_); }
    ULONG STDMETHODCALLTYPE Release() override {
        if (InterlockedDecrement(&ref_) == 0) { delete this; return 0; }
        return static_cast<ULONG>(ref_);
    }
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (pUnkOuter) return CLASS_E_NOAGGREGATION; // 聚合不支持（本源无自由线程承诺）
        MediaSource* src = new MediaSource();
        const HRESULT hr = src->QueryInterface(riid, ppv);
        src->Release();
        return hr;
    }
    HRESULT STDMETHODCALLTYPE LockServer(BOOL fLock) override {
        if (fLock) InterlockedIncrement(&g_dllRef);
        else InterlockedDecrement(&g_dllRef);
        return S_OK;
    }
private:
    ~ClassFactory() { InterlockedDecrement(&g_dllRef); }
    std::atomic<long> ref_{1};
};

} // namespace

// ------------------------------------------------------------------ DLL 导出
extern "C" __declspec(dllexport) HRESULT STDMETHODCALLTYPE DllGetClassObject(
    REFCLSID rclsid, REFIID riid, LPVOID* ppv) {
    if (!ppv) return E_POINTER;
    *ppv = nullptr;
    if (!IsEqualCLSID(rclsid, kClsidApxVcamSource)) return CLASS_E_CLASSNOTAVAILABLE;
    ClassFactory* f = new ClassFactory();
    const HRESULT hr = f->QueryInterface(riid, ppv);
    f->Release();
    return hr;
}

extern "C" __declspec(dllexport) HRESULT STDMETHODCALLTYPE DllCanUnloadNow() {
    return g_dllRef == 0 ? S_OK : S_FALSE;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hModule);
        Dbg("loaded");
    }
    return TRUE;
}
