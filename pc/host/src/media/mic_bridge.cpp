#include "apxpc/media/mic_bridge.hpp"

#include "apxpc/log.hpp"

#include <audioclient.h>
#include <mmdeviceapi.h>
#include <functiondiscoverykeys_devpkey.h>
#include <mutex>
#include <deque>
#include <thread>
#include <atomic>
#include <chrono>
#include <memory>

#if defined(_WIN32)

namespace apxpc::media {
namespace {

// 手机上行 PCM 的固定格式（与 WirelessAudioModule 上行一致）
constexpr WORD      kSrcChannels = 2;
constexpr DWORD     kSrcRate     = 48000;
constexpr WORD      kSrcBits     = 16;

constexpr uint32_t kBufferMs  = 300;   // WASAPI 缓冲
constexpr uint32_t kMaxQueueMs = 400;  // 环形上限，超出丢最旧（保时效）

}  // namespace

struct MicBridge::Impl {
    std::mutex mu;
    std::deque<int16_t> q;             // interleaved int16 样本
    std::atomic<bool> running{false};
    std::thread th;

    // WASAPI（仅渲染线程触碰）
    IAudioClient* client = nullptr;
    IAudioRenderClient* render = nullptr;
    UINT32 bufFrames = 0;

    // 状态（mu 保护；running 用原子）
    std::string device;
    std::string error;
    uint64_t fed = 0, played = 0, dropped = 0;
    std::string renderDeviceId;
};

bool MicBridge::start(const std::string& renderDeviceId, std::string* err) {
    stop();
    std::lock_guard<std::mutex> lk(impl_->mu);
    impl_->renderDeviceId = renderDeviceId;
    impl_->running.store(true);
    impl_->th = std::thread([this] { renderLoop(); });
    (void)err;   // 真正的失败在渲染线程里探明，进 status().error
    return true;
}

void MicBridge::stop() {
    if (!impl_) { impl_ = new Impl(); return; }
    if (impl_->running.exchange(false)) {
        if (impl_->th.joinable()) impl_->th.join();
    }
}

bool MicBridge::running() const {
    return impl_ && impl_->running.load();
}

void MicBridge::feed(const void* data, size_t bytes) {
    if (!impl_ || !impl_->running.load() || !data || bytes == 0) return;
    const int16_t* s = static_cast<const int16_t*>(data);
    const size_t n = bytes / sizeof(int16_t);
    std::lock_guard<std::mutex> lk(impl_->mu);
    impl_->fed += n;
    const size_t maxQ = static_cast<size_t>(kSrcRate) * kSrcChannels * kMaxQueueMs / 1000;
    if (impl_->q.size() + n > maxQ) {
        const size_t over = impl_->q.size() + n - maxQ;
        if (over >= impl_->q.size()) impl_->q.clear();
        else impl_->q.erase(impl_->q.begin(), impl_->q.begin() + static_cast<long>(over));
        impl_->dropped += over;
    }
    impl_->q.insert(impl_->q.end(), s, s + n);
}

MicBridge::Status MicBridge::status() const {
    Status s;
    if (!impl_) return s;
    std::lock_guard<std::mutex> lk(impl_->mu);
    s.running = impl_->running.load();
    s.device = impl_->device;
    s.error = impl_->error;
    s.fed = impl_->fed;
    s.played = impl_->played;
    s.dropped = impl_->dropped;
    return s;
}

void MicBridge::shutdownLocked() {
    if (impl_->render) { impl_->render->Release(); impl_->render = nullptr; }
    if (impl_->client) { impl_->client->Stop(); impl_->client->Release(); impl_->client = nullptr; }
}

void MicBridge::renderLoop() {
    ::CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    auto coGuard = [](void*) { ::CoUninitialize(); };
    std::unique_ptr<void, decltype(coGuard)> coGuardian(nullptr, coGuard);

    {
        std::lock_guard<std::mutex> lk(impl_->mu);
        impl_->error.clear();
        impl_->device.clear();
        impl_->played = 0;
        impl_->dropped = 0;
        impl_->q.clear();
    }

    IMMDeviceEnumerator* enumr = nullptr;
    IMMDevice* dev = nullptr;
    HRESULT hr = ::CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                    __uuidof(IMMDeviceEnumerator), reinterpret_cast<void**>(&enumr));
    if (SUCCEEDED(hr)) {
        if (!impl_->renderDeviceId.empty()) {
            wchar_t idW[256]{};
            ::MultiByteToWideChar(CP_UTF8, 0, impl_->renderDeviceId.c_str(), -1, idW, 256);
            hr = enumr->GetDevice(idW, &dev);
            if (FAILED(hr)) {
                // 设备没了（拔了虚拟声卡）：退回默认，别让开关看起来坏了
                hr = enumr->GetDefaultAudioEndpoint(eRender, eMultimedia, &dev);
            }
        } else {
            hr = enumr->GetDefaultAudioEndpoint(eRender, eMultimedia, &dev);
        }
    }
    if (SUCCEEDED(hr) && dev) {
        hr = dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                           reinterpret_cast<void**>(&impl_->client));
    }
    if (SUCCEEDED(hr) && impl_->client) {
        // 源格式固定 48k/16/2，AUTOCONVERTPCM 让系统负责转到混音格式（Win8.1+）
        WAVEFORMATEX wf{};
        wf.wFormatTag = WAVE_FORMAT_PCM;
        wf.nChannels = kSrcChannels;
        wf.nSamplesPerSec = kSrcRate;
        wf.wBitsPerSample = kSrcBits;
        wf.nBlockAlign = kSrcChannels * kSrcBits / 8;
        wf.nAvgBytesPerSec = kSrcRate * wf.nBlockAlign;
        const REFERENCE_TIME buf = static_cast<REFERENCE_TIME>(kBufferMs) * 10000;
        hr = impl_->client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                       AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                                           AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                                       buf, 0, &wf, nullptr);
    }
    if (SUCCEEDED(hr) && impl_->client) {
        hr = impl_->client->GetBufferSize(&impl_->bufFrames);
        if (SUCCEEDED(hr))
            hr = impl_->client->GetService(__uuidof(IAudioRenderClient),
                                           reinterpret_cast<void**>(&impl_->render));
    }
    if (SUCCEEDED(hr) && impl_->render) {
        hr = impl_->client->Start();
    }

    // 设备名（给面板显示）
    if (SUCCEEDED(hr) && dev) {
        IPropertyStore* ps = nullptr;
        if (SUCCEEDED(dev->OpenPropertyStore(STGM_READ, &ps))) {
            PROPVARIANT v{};
            PropVariantInit(&v);
            if (SUCCEEDED(ps->GetValue(PKEY_Device_FriendlyName, &v)) && v.vt == VT_LPWSTR) {
                char name[256]{};
                ::WideCharToMultiByte(CP_UTF8, 0, v.pwszVal, -1, name, 256, nullptr, nullptr);
                std::lock_guard<std::mutex> lk(impl_->mu);
                impl_->device = name;
            }
            PropVariantClear(&v);
            ps->Release();
        }
    }
    if (dev) dev->Release();
    if (enumr) enumr->Release();

    if (FAILED(hr)) {
        std::lock_guard<std::mutex> lk(impl_->mu);
        char msg[128];
        std::snprintf(msg, sizeof(msg), "WASAPI 渲染初始化失败：0x%08lX", static_cast<unsigned long>(hr));
        impl_->error = msg;
        impl_->running.store(false);
        shutdownLocked();
        return;
    }

    // 渲染循环：轮询填充；缓冲空闲帧按混音通道折算成源样本数
    WAVEFORMATEX* mix = nullptr;
    UINT32 mixCh = 2;
    if (SUCCEEDED(impl_->client->GetMixFormat(&mix)) && mix) {
        mixCh = mix->nChannels;
        ::CoTaskMemFree(mix);
    }

    while (impl_->running.load()) {
        ::Sleep(5);
        UINT32 padding = 0;
        if (FAILED(impl_->client->GetCurrentPadding(&padding))) break;
        const UINT32 availFrames = impl_->bufFrames - padding;
        if (availFrames == 0) continue;

        BYTE* dst = nullptr;
        if (FAILED(impl_->render->GetBuffer(availFrames, &dst))) break;

        // 取出可用的源帧（每帧 2 样本）；不足补静音
        size_t srcFrames = 0;
        std::vector<int16_t> src;
        {
            std::lock_guard<std::mutex> lk(impl_->mu);
            srcFrames = std::min<size_t>(impl_->q.size() / kSrcChannels, availFrames);
            src.assign(impl_->q.begin(), impl_->q.begin() + static_cast<long>(srcFrames * kSrcChannels));
            impl_->q.erase(impl_->q.begin(), impl_->q.begin() + static_cast<long>(srcFrames * kSrcChannels));
            impl_->played += srcFrames * kSrcChannels;
        }
        int16_t* d = reinterpret_cast<int16_t*>(dst);
        if (mixCh == kSrcChannels) {
            std::memcpy(d, src.data(), src.size() * sizeof(int16_t));
        } else {
            for (size_t f = 0; f < srcFrames; ++f) {
                const int16_t l = src[f * 2], r = src[f * 2 + 1];
                for (UINT32 c = 0; c < mixCh; ++c) d[f * mixCh + c] = (c % 2 == 0) ? l : r;
            }
        }
        const size_t need = static_cast<size_t>(availFrames) * mixCh;
        const size_t filled = srcFrames * mixCh;
        if (filled < need) std::memset(d + filled, 0, (need - filled) * sizeof(int16_t));
        impl_->render->ReleaseBuffer(availFrames, 0);
    }

    {
        std::lock_guard<std::mutex> lk(impl_->mu);
        shutdownLocked();
        impl_->running.store(false);
    }
}

}  // namespace apxpc::media

#else  // 非 Windows

namespace apxpc::media {
struct MicBridge::Impl {};
bool MicBridge::start(const std::string&, std::string*) { return false; }
void MicBridge::stop() {}
bool MicBridge::running() const { return false; }
void MicBridge::feed(const void*, size_t) {}
MicBridge::Status MicBridge::status() const { return {}; }
}  // namespace apxpc::media

#endif
