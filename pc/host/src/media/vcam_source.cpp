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
    ~EventGen() {
        if (queue_) { queue_->Shutdown(); queue_->Release(); queue_ = nullptr; }
    }
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
        if (!shm.readLatest(buf)) {
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

} // namespace
