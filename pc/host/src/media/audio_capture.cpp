#include "apxpc/media/audio_capture.hpp"

#include "apxpc/log.hpp"
#include "apxpc/media/media_session.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include <audioclient.h>
#include <mmdeviceapi.h>
#include <mmreg.h>
// DEFINE_PROPERTYKEY 在 propkeydef.h 里，而 functiondiscoverykeys_devpkey.h
// 只用不定义 —— 少这一行会报一堆「DEFINE_PROPERTYKEY 重定义」。
#include <propkeydef.h>
#include <functiondiscoverykeys_devpkey.h>   // PKEY_Device_FriendlyName
#endif

namespace apxpc::media {
namespace {

/// RAII：让 COM 指针在任意退出路径上都被 Release
template <typename T>
void safeRelease(T*& p) {
    if (p) {
        p->Release();
        p = nullptr;
    }
}

int16_t clampToS16(double v) {
    if (v > 32767.0) return 32767;
    if (v < -32768.0) return -32768;
    return static_cast<int16_t>(v);
}

void putS16(std::vector<uint8_t>& out, int16_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}

std::string hrText(long hr) {
    char b[24];
    std::snprintf(b, sizeof b, "0x%08lX", static_cast<unsigned long>(hr));
    return b;
}

#if defined(_WIN32)
std::string wideToUtf8(const wchar_t* w) {
    if (!w || !*w) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string out(static_cast<size_t>(n - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, out.data(), n, nullptr, nullptr);
    return out;
}

std::wstring utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring out(static_cast<size_t>(n - 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, out.data(), n);
    return out;
}

/// 端点 ID（形如 `{0.0.0.00000000}.{guid}`），跨进程稳定，适合持久化与比对
std::string deviceIdOf(IMMDevice* d) {
    if (!d) return {};
    LPWSTR id = nullptr;
    if (FAILED(d->GetId(&id)) || !id) return {};
    std::string out = wideToUtf8(id);
    CoTaskMemFree(id);
    return out;
}

/// 端点友好名（"扬声器 (Realtek(R) Audio)" 这种），只用于展示
std::string endpointName(IMMDevice* dev) {
    if (!dev) return {};
    IPropertyStore* store = nullptr;
    if (FAILED(dev->OpenPropertyStore(STGM_READ, &store)) || !store) return {};
    PROPVARIANT pv;
    PropVariantInit(&pv);
    std::string out;
    if (SUCCEEDED(store->GetValue(PKEY_Device_FriendlyName, &pv)) && pv.vt == VT_LPWSTR) {
        out = wideToUtf8(pv.pwszVal);
    }
    PropVariantClear(&pv);
    store->Release();
    return out;
}
#endif

}  // namespace

// ============================================================================
// 实现
//
// 线程模型：start() 在调用线程建好 COM 对象并 Start()，随后起一条采集线程跑
// captureLoop()。**采集线程绝不碰 mu_**（计数器全是 atomic）—— 这样 stop() 就能
// 在持锁状态下安全 join，不会出现"持锁等线程、线程等锁"的死锁。
// ============================================================================
struct AudioCapture::Impl {
    std::mutex mu;                 // 只保护 start/stop 与 device/deviceId/error/rate/channels
    std::string error;
    std::string device;
    std::string deviceId;
    bool followingDefault = true;
    uint32_t rate = 0;
    uint32_t channels = 0;

    std::atomic<bool> running{false};
    std::atomic<uint64_t> framesSent{0};
    std::atomic<uint64_t> bytesSent{0};
    std::atomic<uint64_t> dropped{0};
    std::atomic<double> peak{0.0};   // 最近一片的峰值（0..1）

    MediaSession* session = nullptr;
    uint32_t frameBytes = 0;
    std::thread thread;

#if defined(_WIN32)
    HANDLE evt = nullptr;
    IAudioClient* client = nullptr;
    IAudioCaptureClient* capture = nullptr;

    // 仅当 WASAPI 不接受目标格式时才启用：按混音格式采 + 自己转
    bool needConvert = false;
    bool srcFloat = false;
    uint32_t srcRate = 0;
    uint32_t srcChannels = 0;
    double srcPos = 0.0;          // 重采样读位置（以源采样帧计，跨块保留小数部分）
    std::vector<float> stage;     // 源速率下的立体声 float 中间量
    std::vector<uint8_t> conv;    // 转换后的 s16 字节
    std::vector<uint8_t> staging; // 待发送累积（凑满 frameBytes 才发）

    void captureLoop();
    void handleBlock(const uint8_t* data, uint32_t frames, bool silent);
    void convertBlock(const uint8_t* data, uint32_t frames);
    void appendAndSend(const uint8_t* p, size_t bytes);
#endif
};

AudioCapture::AudioCapture() : impl_(std::make_unique<Impl>()) {}
AudioCapture::~AudioCapture() { stop(); }

// ------------------------------------------------------------------ 端点枚举 ----

std::vector<AudioDeviceInfo> AudioCapture::listRenderDevices() {
#if defined(_WIN32)
    const HRESULT hrInit = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool ownCom = SUCCEEDED(hrInit);

    std::vector<AudioDeviceInfo> out;
    IMMDeviceEnumerator* en = nullptr;
    IMMDeviceCollection* coll = nullptr;
    IMMDevice* def = nullptr;
    if (SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                   __uuidof(IMMDeviceEnumerator),
                                   reinterpret_cast<void**>(&en))) &&
        en) {
        // 先拿默认设备 ID，以便给它打标记并排在最前
        std::string defId;
        if (SUCCEEDED(en->GetDefaultAudioEndpoint(eRender, eConsole, &def)) && def) {
            defId = deviceIdOf(def);
        }
        if (SUCCEEDED(en->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &coll)) && coll) {
            UINT32 n = 0;
            if (SUCCEEDED(coll->GetCount(&n))) {
                for (UINT32 i = 0; i < n; ++i) {
                    IMMDevice* d = nullptr;
                    if (FAILED(coll->Item(i, &d)) || !d) continue;
                    AudioDeviceInfo info;
                    info.id = deviceIdOf(d);
                    info.name = endpointName(d);
                    info.isDefault = (!info.id.empty() && info.id == defId);
                    if (!info.id.empty()) out.push_back(std::move(info));
                    d->Release();
                }
            }
        }
    }
    safeRelease(coll);
    safeRelease(def);
    safeRelease(en);
    if (ownCom) CoUninitialize();

    // 默认设备排最前，其余保持系统给的顺序
    std::stable_sort(out.begin(), out.end(),
                     [](const AudioDeviceInfo& a, const AudioDeviceInfo& b) {
                         return static_cast<int>(a.isDefault) > static_cast<int>(b.isDefault);
                     });
    return out;
#else
    return {};
#endif
}

std::string AudioCapture::defaultRenderDeviceId() {
#if defined(_WIN32)
    const HRESULT hrInit = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool ownCom = SUCCEEDED(hrInit);
    IMMDeviceEnumerator* en = nullptr;
    IMMDevice* dev = nullptr;
    std::string id;
    if (SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                   __uuidof(IMMDeviceEnumerator),
                                   reinterpret_cast<void**>(&en))) &&
        en) {
        if (SUCCEEDED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev)) && dev) {
            id = deviceIdOf(dev);
        }
    }
    safeRelease(dev);
    safeRelease(en);
    if (ownCom) CoUninitialize();
    return id;
#else
    return {};
#endif
}

std::string AudioCapture::defaultRenderDeviceName() {
#if defined(_WIN32)
    const HRESULT hrInit = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool ownCom = SUCCEEDED(hrInit);
    IMMDeviceEnumerator* en = nullptr;
    IMMDevice* dev = nullptr;
    std::string name;
    if (SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                   __uuidof(IMMDeviceEnumerator),
                                   reinterpret_cast<void**>(&en))) &&
        en) {
        if (SUCCEEDED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev)) && dev) {
            name = endpointName(dev);
        }
    }
    safeRelease(dev);
    safeRelease(en);
    if (ownCom) CoUninitialize();
    return name;
#else
    return {};
#endif
}

#if defined(_WIN32)
// ------------------------------------------------------------------ 采集线程 ----

void AudioCapture::Impl::captureLoop() {
    while (running.load()) {
        // 超时 200ms：系统静音时 loopback 可能长时间没有数据，靠超时保持可退出
        if (WaitForSingleObject(evt, 200) != WAIT_OBJECT_0) continue;

        UINT32 packet = 0;
        if (FAILED(capture->GetNextPacketSize(&packet))) break;
        while (packet != 0 && running.load()) {
            BYTE* data = nullptr;
            UINT32 frames = 0;
            DWORD flags = 0;
            if (FAILED(capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) break;
            if (frames > 0) {
                handleBlock(data, frames, (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0);
            }
            capture->ReleaseBuffer(frames);
            if (FAILED(capture->GetNextPacketSize(&packet))) {
                packet = 0;
                break;
            }
        }
    }
}

void AudioCapture::Impl::handleBlock(const uint8_t* data, uint32_t frames, bool silent) {
    if (needConvert) {
        if (silent || !data) {
            // 静音也要**照常补零**：保持时间轴连续，否则手机侧 AudioTrack 会断音
            conv.assign(static_cast<size_t>(frames) * 4, 0);
            appendAndSend(conv.data(), conv.size());
            return;
        }
        convertBlock(data, frames);
        return;
    }
    // 快路径：WASAPI 已按 48k/s16/立体声给到，直接攒
    const size_t bytes = static_cast<size_t>(frames) * 4;
    if (silent || !data) {
        static const std::vector<uint8_t> kZeros(4096, 0);
        size_t left = bytes;
        while (left > 0) {
            const size_t n = std::min(left, kZeros.size());
            appendAndSend(kZeros.data(), n);
            left -= n;
        }
        return;
    }
    appendAndSend(data, bytes);
}

void AudioCapture::Impl::convertBlock(const uint8_t* data, uint32_t frames) {
    // 1) 解成"源速率、立体声、float"
    stage.clear();
    stage.reserve(static_cast<size_t>(frames) * 2);
    for (uint32_t i = 0; i < frames; ++i) {
        float l;
        float r;
        if (srcFloat) {
            const float* f = reinterpret_cast<const float*>(data) +
                             static_cast<size_t>(i) * srcChannels;
            l = f[0];
            r = srcChannels > 1 ? f[1] : f[0];
        } else {
            const int16_t* s = reinterpret_cast<const int16_t*>(data) +
                               static_cast<size_t>(i) * srcChannels;
            l = static_cast<float>(s[0] / 32768.0);
            r = srcChannels > 1 ? static_cast<float>(s[1] / 32768.0) : l;
        }
        stage.push_back(l);
        stage.push_back(r);
    }

    // 2) 线性重采样到 48k，输出 s16 立体声
    const size_t n = stage.size() / 2;
    const double step = static_cast<double>(srcRate) / static_cast<double>(kAudioSampleRate);
    conv.clear();
    conv.reserve(static_cast<size_t>(static_cast<double>(n) / (step > 0 ? step : 1.0)) * 4 + 8);
    while (srcPos + 1.0 < static_cast<double>(n)) {
        const size_t i = static_cast<size_t>(srcPos);
        const double f = srcPos - static_cast<double>(i);
        for (int ch = 0; ch < 2; ++ch) {
            const double v = stage[i * 2 + ch] * (1.0 - f) + stage[(i + 1) * 2 + ch] * f;
            putS16(conv, clampToS16(v * 32767.0));
        }
        srcPos += step;
    }
    // 整块消费掉，只留小数偏移给下一块（保持相位连续）
    srcPos = std::max(0.0, srcPos - static_cast<double>(n));
    appendAndSend(conv.data(), conv.size());
}

void AudioCapture::Impl::appendAndSend(const uint8_t* p, size_t bytes) {
    if (!p || bytes == 0 || frameBytes == 0) return;
    staging.insert(staging.end(), p, p + bytes);

    // 峰值：让用户能区分「链路在跑但系统本身静音」与「根本没数据」
    int peakAbs = 0;
    const size_t whole = staging.size() / 2 * 2;
    for (size_t i = 0; i + 1 < whole; i += 2) {
        const int v = static_cast<int16_t>(static_cast<uint16_t>(staging[i]) |
                                           (static_cast<uint16_t>(staging[i + 1]) << 8));
        peakAbs = std::max(peakAbs, v < 0 ? -v : v);
    }
    peak.store(peakAbs / 32768.0);

    while (staging.size() >= frameBytes) {
        if (session && session->sendFrame(kStreamAudio, staging.data(), frameBytes, 0)) {
            framesSent.fetch_add(1);
            bytesSent.fetch_add(frameBytes);
        } else {
            dropped.fetch_add(1);
        }
        staging.erase(staging.begin(), staging.begin() + frameBytes);
    }
}
#endif  // _WIN32

// ------------------------------------------------------------------ 启停 ----

void AudioCapture::stop() {
    std::lock_guard<std::mutex> lk(impl_->mu);
    if (!impl_->running.exchange(false)) {
#if defined(_WIN32)
        safeRelease(impl_->capture);
        safeRelease(impl_->client);
        if (impl_->evt) {
            CloseHandle(impl_->evt);
            impl_->evt = nullptr;
        }
        impl_->staging.clear();
#endif
        return;
    }
#if defined(_WIN32)
    // 先唤醒采集线程，再 join —— 否则要白等一个 200ms 超时
    if (impl_->evt) SetEvent(impl_->evt);
    if (impl_->thread.joinable() && impl_->thread.get_id() != std::this_thread::get_id()) {
        impl_->thread.join();
    }
    // 停流必须早于 Release，否则引擎还在往已释放的客户端投递
    if (impl_->client) impl_->client->Stop();
    safeRelease(impl_->capture);
    safeRelease(impl_->client);
    if (impl_->evt) {
        CloseHandle(impl_->evt);
        impl_->evt = nullptr;
    }
    impl_->staging.clear();
    impl_->peak.store(0.0);
#endif
    APX_LOGI("音箱采集已停止");
}

bool AudioCapture::start(MediaSession* session, const AudioCaptureOptions& opt, std::string* err) {
    stop();
    std::lock_guard<std::mutex> lk(impl_->mu);

    auto fail = [&](const std::string& msg) {
        impl_->error = msg;
        // 失败时把端点信息清干净：否则"跟随默认"的持有方会拿一个陈旧 ID 去比对，
        // 从此再也不重试（也免得面板上显示一个其实没采到的设备）
        impl_->device.clear();
        impl_->deviceId.clear();
        if (err) *err = msg;
        APX_LOGW("音箱采集启动失败：{}", msg);
        return false;
    };

    if (!session) return fail("媒体会话不存在");
    if (!session->status().connected) return fail("媒体连接未建立（先让面板连上手机）");
    if (opt.frameMs == 0 || opt.frameMs > 100) return fail("分片时长非法（应为 1–100 ms）");

#if !defined(_WIN32)
    return fail("系统声音采集目前仅实现 Windows");
#else
    const HRESULT hrInit = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool ownCom = SUCCEEDED(hrInit);   // RPC_E_CHANGED_MODE 时继续用当前套间

    impl_->session = session;
    impl_->frameBytes = static_cast<uint32_t>(kAudioBytesPerMs) * opt.frameMs;
    impl_->error.clear();
    impl_->device.clear();
    impl_->deviceId.clear();
    impl_->followingDefault = opt.deviceId.empty();
    impl_->framesSent.store(0);
    impl_->bytesSent.store(0);
    impl_->dropped.store(0);
    impl_->peak.store(0.0);
    impl_->needConvert = false;
    impl_->srcPos = 0.0;
    impl_->staging.clear();

    IMMDeviceEnumerator* en = nullptr;
    IMMDevice* dev = nullptr;
    auto cleanupLocal = [&] {
        safeRelease(dev);
        safeRelease(en);
        safeRelease(impl_->capture);
        safeRelease(impl_->client);
        if (impl_->evt) {
            CloseHandle(impl_->evt);
            impl_->evt = nullptr;
        }
        if (ownCom) CoUninitialize();
    };

    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                __uuidof(IMMDeviceEnumerator),
                                reinterpret_cast<void**>(&en))) ||
        !en) {
        cleanupLocal();
        return fail("无法访问音频设备（MMDeviceEnumerator 创建失败）");
    }

    // 选端点：指定了就按 ID 找（找不到必须**明确报错**，不能悄悄回落到默认 ——
    // 那会让用户以为在听耳机、其实采的是显示器），没指定才用系统默认。
    if (impl_->followingDefault) {
        if (FAILED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev)) || !dev) {
            cleanupLocal();
            return fail("找不到默认播放设备（系统没有可用的扬声器 / 耳机？）");
        }
    } else {
        const std::wstring wid = utf8ToWide(opt.deviceId);
        if (wid.empty() || FAILED(en->GetDevice(wid.c_str(), &dev)) || !dev) {
            cleanupLocal();
            return fail("所选的播放设备已不可用（拔掉了？被禁用了？）—— 请重新选一块");
        }
    }
    impl_->device = endpointName(dev);
    impl_->deviceId = deviceIdOf(dev);

    if (FAILED(dev->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                             reinterpret_cast<void**>(&impl_->client))) ||
        !impl_->client) {
        cleanupLocal();
        return fail("无法激活音频客户端（IAudioClient）");
    }

    // 目标格式：48k / 16bit / 立体声（两端约定，见 media_session.hpp）
    WAVEFORMATEX want{};
    want.wFormatTag = WAVE_FORMAT_PCM;
    want.nChannels = static_cast<WORD>(kAudioChannels);
    want.nSamplesPerSec = static_cast<DWORD>(kAudioSampleRate);
    want.wBitsPerSample = 16;
    want.nBlockAlign = static_cast<WORD>(want.nChannels * want.wBitsPerSample / 8);
    want.nAvgBytesPerSec = want.nSamplesPerSec * want.nBlockAlign;

    // 缓冲给 1 秒：loopback 在系统静音时不推进，缓冲过小配合事件模式容易空转
    const REFERENCE_TIME dur = 10'000'000;
    // AUTOCONVERTPCM + SRC_DEFAULT_QUALITY：让音频引擎把设备混音格式直接转成我们要的
    // 格式，采集循环里一次转换都不用做（常见设备是 48k float32，本来也得转）。
    const DWORD flags = AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK |
                        AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                        AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
    HRESULT hr = impl_->client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, dur, 0, &want,
                                           nullptr);

    if (SUCCEEDED(hr)) {
        impl_->rate = kAudioSampleRate;
        impl_->channels = kAudioChannels;
    } else {
        // 退回：按设备混音格式采，自己转
        WAVEFORMATEX* mix = nullptr;
        if (FAILED(impl_->client->GetMixFormat(&mix)) || !mix) {
            const std::string detail = hrText(hr);
            cleanupLocal();
            return fail("AUTOCONVERTPCM 被拒（" + detail + "），且取混音格式失败");
        }
        const DWORD f2 = AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
        const HRESULT hr2 = impl_->client->Initialize(AUDCLNT_SHAREMODE_SHARED, f2, dur, 0, mix,
                                                      nullptr);
        if (FAILED(hr2)) {
            const std::string detail = hrText(hr2);
            CoTaskMemFree(mix);
            cleanupLocal();
            return fail("音频客户端初始化失败（" + detail + "）");
        }
        impl_->srcRate = mix->nSamplesPerSec;
        impl_->srcChannels = mix->nChannels;
        impl_->srcFloat = (mix->wFormatTag == WAVE_FORMAT_IEEE_FLOAT);
        const uint16_t srcBits = mix->wBitsPerSample;
        const bool srcOk = impl_->srcFloat || srcBits == 16;
        APX_LOGI("音箱采集退回混音格式：{}Hz {}ch {}bit{}", impl_->srcRate, impl_->srcChannels,
                 srcBits, impl_->srcFloat ? " float" : "");
        CoTaskMemFree(mix);
        if (!srcOk) {
            cleanupLocal();
            return fail("设备混音格式为 " + std::to_string(srcBits) + "bit 非 float，暂不支持");
        }
        impl_->needConvert = !(impl_->srcRate == kAudioSampleRate &&
                               impl_->srcChannels == kAudioChannels && !impl_->srcFloat &&
                               srcBits == 16);
        // 送给手机的一律是 48k/立体声
        impl_->rate = kAudioSampleRate;
        impl_->channels = kAudioChannels;
    }

    if (FAILED(impl_->client->GetService(__uuidof(IAudioCaptureClient),
                                         reinterpret_cast<void**>(&impl_->capture))) ||
        !impl_->capture) {
        cleanupLocal();
        return fail("取音频采集客户端（IAudioCaptureClient）失败");
    }

    impl_->evt = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!impl_->evt || FAILED(impl_->client->SetEventHandle(impl_->evt))) {
        cleanupLocal();
        return fail("创建采集事件失败");
    }
    if (FAILED(impl_->client->Start())) {
        cleanupLocal();
        return fail("启动音频采集失败");
    }

    safeRelease(dev);
    safeRelease(en);

    impl_->running.store(true);
    impl_->thread = std::thread([this] {
        const HRESULT hrTh = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        impl_->captureLoop();
        if (SUCCEEDED(hrTh)) CoUninitialize();
    });
    APX_LOGI("音箱采集已启动：{}（{}Hz {}ch，每片 {}ms{}{}）", impl_->device, impl_->rate,
             impl_->channels, opt.frameMs, impl_->needConvert ? "，含格式转换" : "",
             impl_->followingDefault ? "，跟随系统默认" : "");
    return true;
#endif
}

bool AudioCapture::running() const { return impl_->running.load(); }

AudioCapture::Status AudioCapture::status() const {
    std::lock_guard<std::mutex> lk(impl_->mu);
    Status s;
    s.running = impl_->running.load();
    s.error = impl_->error;
    s.device = impl_->device;
    s.deviceId = impl_->deviceId;
    s.followingDefault = impl_->followingDefault;
    s.sampleRate = impl_->rate;
    s.channels = impl_->channels;
    s.framesSent = impl_->framesSent.load();
    s.bytesSent = impl_->bytesSent.load();
    s.dropped = impl_->dropped.load();
    s.peak = impl_->peak.load();
    return s;
}

void AudioCapture::playTestTone(MediaSession* session, int seconds,
                                std::atomic<uint64_t>* outSent) {
    if (!session || !session->status().connected) return;
    if (seconds <= 0) seconds = 2;
    if (seconds > 10) seconds = 10;

    const uint32_t rate = kAudioSampleRate;
    const uint32_t ch = kAudioChannels;
    const int frameMs = 10;
    const size_t frameBytes = static_cast<size_t>(kAudioBytesPerMs) * frameMs;  // 1920
    const size_t samplesPerFrame = frameBytes / (ch * 2);                        // 480
    const int framesTotal = (seconds * 1000) / frameMs;
    const uint64_t totalSamples =
        static_cast<uint64_t>(framesTotal) * samplesPerFrame;
    constexpr double kPi = 3.14159265358979323846;
    const double freq = 1000.0;                   // 1kHz，手机扬声器容易出声
    const double inc = 2.0 * kPi * freq / static_cast<double>(rate);
    // 入/出 5ms 淡变，避免咔哒声
    const uint64_t fade = static_cast<uint64_t>(rate) * 5 / 1000;

    std::vector<uint8_t> buf(frameBytes, 0);
    double phase = 0.0;
    for (int i = 0; i < framesTotal; ++i) {
        for (size_t s = 0; s < samplesPerFrame; ++s) {
            const uint64_t idx = static_cast<uint64_t>(i) * samplesPerFrame + s;
            double env = 1.0;
            if (idx < fade) env = static_cast<double>(idx) / static_cast<double>(fade);
            const uint64_t tail = totalSamples - idx;
            if (tail < fade) env = std::min(env, static_cast<double>(tail) / static_cast<double>(fade));
            const int16_t v = clampToS16(0.3 * std::sin(phase) * env * 32767.0);
            const size_t o = s * ch * 2;
            buf[o] = static_cast<uint8_t>(v & 0xFF);
            buf[o + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            buf[o + 2] = buf[o];
            buf[o + 3] = buf[o + 1];
            phase += inc;
        }
        if (session->sendFrame(kStreamAudio, buf.data(), frameBytes, 0)) {
            if (outSent) outSent->fetch_add(1);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(frameMs));
    }
}

}  // namespace apxpc::media
