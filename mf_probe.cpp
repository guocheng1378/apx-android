// mf_probe —— 独立复现：微软 H264 软编 MFT 的最小调用序列
#include <windows.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <wmcodecdsp.h>
#include <wrl/client.h>
#include <cstdio>
using namespace Microsoft::WRL;

static const CLSID kH264MFT = {0x6CA50344,0x051A,0x4DED,{0x97,0x79,0xA4,0x33,0x05,0x16,0x5E,0x35}};

int wmain() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    HRESULT hr = MFStartup(MF_VERSION, 0);
    printf("MFStartup: 0x%08lX\n", static_cast<unsigned long>(hr));

    ComPtr<IMFTransform> t;
    hr = CoCreateInstance(kH264MFT, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&t));
    printf("CoCreate: 0x%08lX\n", static_cast<unsigned long>(hr));
    if (FAILED(hr)) return 1;

    // 输出类型（H264）
    ComPtr<IMFMediaType> out;
    MFCreateMediaType(&out);
    out->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    out->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    MFSetAttributeSize(out.Get(), MF_MT_FRAME_SIZE, 320, 240);
    MFSetAttributeRatio(out.Get(), MF_MT_FRAME_RATE, 30, 1);
    out->SetUINT32(MF_MT_AVG_BITRATE, 2000000);
    hr = t->SetOutputType(0, out.Get(), 0);
    printf("SetOutputType: 0x%08lX\n", static_cast<unsigned long>(hr));

    // 输入类型（NV12）
    ComPtr<IMFMediaType> in;
    MFCreateMediaType(&in);
    in->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    in->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
    MFSetAttributeSize(in.Get(), MF_MT_FRAME_SIZE, 320, 240);
    MFSetAttributeRatio(in.Get(), MF_MT_FRAME_RATE, 30, 1);
    hr = t->SetInputType(0, in.Get(), 0);
    printf("SetInputType: 0x%08lX\n", static_cast<unsigned long>(hr));

    MFT_OUTPUT_STREAM_INFO sinfo{};
    t->GetOutputStreamInfo(0, &sinfo);
    printf("OutStreamInfo: flags=0x%08lX cbSize=%lu\n",
           static_cast<unsigned long>(sinfo.dwFlags), sinfo.cbSize);

    t->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    t->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    printf("streaming msgs sent\n");

    // 喂一帧 NV12
    const DWORD nv12Size = 320 * 240 * 3 / 2;
    ComPtr<IMFMediaBuffer> ib;
    MFCreateMemoryBuffer(nv12Size, &ib);
    BYTE* p = nullptr;
    ib->Lock(&p, nullptr, nullptr);
    memset(p, 0x80, nv12Size);
    ib->SetCurrentLength(nv12Size);
    ib->Unlock();
    ComPtr<IMFSample> s;
    MFCreateSample(&s);
    s->AddBuffer(ib.Get());
    s->SetSampleTime(0);
    s->SetSampleDuration(333333);
    hr = t->ProcessInput(0, s.Get(), 0);
    printf("ProcessInput#1: 0x%08lX\n", static_cast<unsigned long>(hr));

    // 取输出
    MFT_OUTPUT_DATA_BUFFER ob{};
    ComPtr<IMFSample> os;
    ComPtr<IMFMediaBuffer> obuf;
    bool provide = (sinfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;
    printf("provide=%d\n", provide ? 1 : 0);
    if (provide) {
        MFCreateMemoryBuffer(sinfo.cbSize ? sinfo.cbSize : nv12Size, &obuf);
        MFCreateSample(&os);
        os->AddBuffer(obuf.Get());
        ob.pSample = os.Get();
    }
    DWORD st = 0;
    hr = t->ProcessOutput(0, 1, &ob, &st);
    printf("ProcessOutput#1: 0x%08lX\n", static_cast<unsigned long>(hr));
    if (ob.pEvents) ob.pEvents->Release();
    return 0;
}
