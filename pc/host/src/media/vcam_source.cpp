// 虚拟摄像头 Media Source（M7-c c-2 的 consumer 侧）—— 独立 in-proc DLL。
//
// 进程模型（为什么必须拆 DLL）：MFCreateVirtualCamera(sourceId = CLSID) 注册后，
// 微信/会议端打开这台摄像头时，是在**它们自己的进程**里 CoCreateInstance 本 DLL；
// 主程序内存它摸不到 → 帧只能走 vcam_shm.hpp 定义的共享内存。
//
// 本 DLL 只干一件事：把共享内存里的最新 NV12 帧（或超时黑帧）喂成 MF 样本。
// 生产端（创建/移除注册 + 写帧）在 camera_view.cpp。
//
// ⚠️ 未真机实测：云端无 Win11 + 无微信/会议端，CI 只验编译。
// MF 源按规范实现了事件序列（MESourceStarted → MEStreamSample…），但真实
// 应用的格式协商/拉流行为没跑过——面板上标「未实测」，不假装可用。

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mfidl.h>

#include "apxpc/media/vcam_shm.hpp"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>

// 固定 CLSID：必须与 camera_view.hpp 的 kVcamClsid 逐字一致（字符串形式由
// 主程序写注册表 / 传 MFCreateVirtualCamera；这里 C++ 侧比较用）。
static const CLSID kClsidApxVcamSource =
    { 0x7A3C9C1E, 0x5B42, 0x4F8A, { 0x9D, 0x61, 0x2E, 0x8F, 0x0B, 0x4A, 0x6C, 0x11 } };

namespace {

long g_dllRef = 0; // DLL 级引用计数（DllCanUnloadNow）

inline void Dbg(const char* msg) {
    OutputDebugStringA("[apxvcam] ");
    OutputDebugStringA(msg);
    OutputDebugStringA("\n");
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
    void* handle_ = nullptr; // HANDLE（避免引入 windows 句柄类型到头文件）
    void* map_ = nullptr;
    apxpc::media::vcam::VcamShmHeader* hdr_ = nullptr;
};

// ------------------------------------------------------------ 事件生成器公共件
class EventGen {
protected:
    HRESULT ensureQueue() {
        std::lock_guard<std::mutex> lk(m_);
        if (!queue_) return MFCreateEventQueue(&queue_);
        return S_OK;
    }
    ~EventGen() {
        if (queue_) { queue_->Shutdown(); queue_->Release(); queue_ = nullptr; }
    }
public:
    // IMFMediaEventGenerator
    HRESULT GetEvent(DWORD dwFlags, IMFMediaEvent** ppEvent) {
        if (!queue_) return MF_E_NOT_INITIALIZED;
        return queue_->GetEvent(dwFlags, ppEvent);
    }
    HRESULT QueueEvent(REFGUID guidExtendedType, REFGUID guidSubType, HRESULT hrStatus,
                       const PROPVARIANT* pv) {
        if (!queue_) return MF_E_NOT_INITIALIZED;
        return queue_->QueueEventParamVar(guidExtendedType, guidSubType, hrStatus, pv);
    }
    HRESULT BeginGetEvent(IMFAsyncCallback* pCallback, IUnknown* punkState) {
        if (!queue_) return MF_E_NOT_INITIALIZED;
        return queue_->BeginGetEvent(pCallback, punkState);
    }
    HRESULT EndGetEvent(IMFAsyncResult* pResult, IMFMediaEvent** ppEvent) {
        if (!queue_) return MF_E_NOT_INITIALIZED;
        return queue_->EndGetEvent(pResult, ppEvent);
    }
private:
    std::mutex m_;
    IMFMediaEventQueue* queue_ = nullptr;
};

class MediaSource;

// ------------------------------------------------------------------- 媒体流
class MediaStream : public IMFMediaStream, public EventGen {
public:
    MediaStream(MediaSource* src, IMFPresentationDescriptor* pd, IMFStreamDescriptor* sd)
        : src_(src), pd_(pd), sd_(sd) {
        g_dllRef++;
        MFCreateMediaType(&firstType_);
        if (firstType_) {
            firstType_->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            firstType_->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
            firstType_->SetUINT32(MF_MT_FRAME_SIZE,
                                  (vcam::kWidth << 16) | vcam::kHeight);
            firstType_->SetUINT32(MF_MT_DEFAULT_STRIDE, vcam::kStride);
            firstType_->SetRATIO(MF_MT_FRAME_RATE, 30, 1);
            firstType_->SetRATIO(MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
            firstType_->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
            firstType_->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
        }
    }

    // IMFUnknown
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IMFMediaStream || riid == IID_IMFMediaEventGenerator) {
            *ppv = static_cast<IMFMediaStream*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&ref_); }
    ULONG STDMETHODCALLTYPE Release() override {
        const long r = InterlockedDecrement(&ref_);
        if (r == 0) { delete this; }
        return r;
    }

    // IMFMediaStream
    HRESULT STDMETHODCALLTYPE GetMediaSource(IMFMediaSource** ppSource) override {
        if (!ppSource) return E_POINTER;
        if (!src_) return MF_E_SHUTDOWN;
        *ppSource = src_; (*src_)->AddRef();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetStreamDescriptor(IMFStreamDescriptor** ppSD) override {
        if (!ppSD) return E_POINTER;
        *ppSD = sd_; if (sd_) sd_->AddRef();
        return sd_ ? S_OK : E_POINTER;
    }
    HRESULT STDMETHODCALLTYPE RequestSample(IUnknown* punkToken) override {
        if (!src_) return MF_E_SHUTDOWN;
        { std::lock_guard<std::mutex> lk(mu_);
          if (closed_) return MF_E_SHUTDOWN;
          pending_++; }
        token_.reset(); // 只透传一个 in-flight token 简化（live 源无 seek 语义）
        if (punkToken) { punkToken->AddRef(); token_.reset(punkToken); }
        cv_.notify_all();
        return S_OK;
    }

    // IMFMediaEventGenerator（转 EventGen）
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

    // ---- producer 侧调用（source 管线线程）
    HRESULT start() {
        HRESULT hr = ensureQueue();
        if (FAILED(hr)) return hr;
        std::lock_guard<std::mutex> lk(mu_);
        closed_ = false;
        return S_OK;
    }
    void stopSignals() {
        { std::lock_guard<std::mutex> lk(mu_); closed_ = true; }
        cv_.notify_all();
    }
    // 推线程调用：有 pending request 就交一帧（无帧时交黑帧，不卡宿主）
    bool deliverOne(ShmReader& shm) {
        { std::lock_guard<std::mutex> lk(mu_);
          if (pending_ == 0 || closed_) return false; }

        static thread_local uint8_t buf[vcam::kNv12Size];
        bool got = shm.readLatest(buf);
        if (!got) {
            // 黑帧：Y=16（limited range 黑）、UV=128（中性色差）
            std::memset(buf, 16, static_cast<size_t>(vcam::kWidth) * vcam::kHeight);
            std::memset(buf + static_cast<size_t>(vcam::kWidth) * vcam::kHeight, 128,
                        static_cast<size_t>(vcam::kWidth) * vcam::kHeight / 2);
        }

        IMFMediaBuffer* mb = nullptr;
        HRESULT hr = MFCreateMemoryBuffer(vcam::kNv12Size, &mb);
        if (FAILED(hr)) return false;
        BYTE* dst = nullptr; DWORD max = 0, cur = 0;
        if (SUCCEEDED(mb->Lock(&dst, &max, &cur))) {
            std::memcpy(dst, buf, vcam::kNv12Size);
            mb->Unlock();
            mb->SetCurrentLength(vcam::kNv12Size);
        }
        IMFSample* sample = nullptr;
        hr = MFCreateSample(&sample);
        if (SUCCEEDED(hr) && sample) {
            sample->AddBuffer(mb);
            const LONGLONG t = static_cast<LONGLONG>(frameIdx_++) * 333333; // 30fps 100ns
            sample->SetSampleTime(t);
            sample->SetSampleDuration(333333);
            PROPVARIANT v;
            PropVariantInit(&v);
            v.vt = VT_UNKNOWN;
            v.punkVal = sample; sample->AddRef();
            QueueEvent(MEStreamSample, GUID_NULL, S_OK, &v);
            PropVariantClear(&v);
            { std::lock_guard<std::mutex> lk(mu_);
              if (pending_ > 0) pending_--; }
            sample->Release();
        }
        mb->Release();
        return true;
    }

    IMFStreamDescriptor* descriptor() const { return sd_; }
    IMFMediaType* firstType() const { return firstType_; }
    void wake() { cv_.notify_all(); }
    // 等待新 request（带超时返回，方便定期检查 source 状态）
    bool waitForRequest(DWORD ms) {
        std::unique_lock<std::mutex> lk(mu_);
        if (pending_ > 0 || closed_) return true;
        cv_.wait_for(lk, std::chrono::milliseconds(ms));
        return true;
    }

private:
    ~MediaStream() override {
        if (firstType_) firstType_->Release();
        if (sd_) sd_->Release();
        if (pd_) pd_->Release();
        g_dllRef--;
    }
    std::atomic<long> ref_{1};
    MediaSource* src_;        // 非 owning（source 生存期 >= stream）
    IMFPresentationDescriptor* pd_ = nullptr;
    IMFStreamDescriptor* sd_ = nullptr;
    IMFMediaType* firstType_ = nullptr;
    std::mutex mu_;
    std::condition_variable cv_;
    uint32_t pending_ = 0;    // 未完成的 RequestSample 数
    bool closed_ = false;
    IUnknown* rawToken_ = nullptr;
    Microsoft::WRL::ComPtr<IUnknown> token_; // 占位——见下方注：不用 WRL
    uint64_t frameIdx_ = 0;
};

} // namespace
