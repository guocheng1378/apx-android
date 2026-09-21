// mf_probe2 —— 采集微软软编 MFT 的真实输出字节，验证 SPS/PPS 是否在流内
#include <windows.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <wmcodecdsp.h>
#include <wrl/client.h>
#include <cstdio>
#include <vector>
using namespace Microsoft::WRL;

static const CLSID kH264MFT = {0x6CA50344,0x051A,0x4DED,{0x97,0x79,0xA4,0x33,0x05,0x16,0x5E,0x35}};

int wmain() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION, 0);
    ComPtr<IMFTransform> t;
    if (FAILED(CoCreateInstance(kH264MFT, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&t)))) { printf("create fail\n"); return 1; }

    ComPtr<IMFMediaType> out;
    MFCreateMediaType(&out);
    out->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    out->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    MFSetAttributeSize(out.Get(), MF_MT_FRAME_SIZE, 320, 240);
    MFSetAttributeRatio(out.Get(), MF_MT_FRAME_RATE, 30, 1);
    out->SetUINT32(MF_MT_AVG_BITRATE, 2000000);
    out->SetUINT32(MF_MT_MPEG2_PROFILE, 100);
    HRESULT hr = t->SetOutputType(0, out.Get(), 0);
    printf("SetOutputType: 0x%08lX\n", static_cast<unsigned long>(hr));

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
    printf("streamInfo flags=0x%08lX cb=%lu\n", static_cast<unsigned long>(sinfo.dwFlags), sinfo.cbSize);

    t->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    t->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);

    const DWORD nv = 320 * 240 * 3 / 2;
    FILE* f = fopen("probe_out.h264", "wb");
    // 喂 6 帧，尽量收集输出
    for (int i = 0; i < 6; ++i) {
        ComPtr<IMFMediaBuffer> ib;
        MFCreateMemoryBuffer(nv, &ib);
        BYTE* p = nullptr;
        ib->Lock(&p, nullptr, nullptr);
        memset(p, 0x80, nv);
        ib->SetCurrentLength(nv);
        ib->Unlock();
        ComPtr<IMFSample> s;
        MFCreateSample(&s);
        s->AddBuffer(ib.Get());
        s->SetSampleTime(i * 333333);
        s->SetSampleDuration(333333);
        hr = t->ProcessInput(0, s.Get(), 0);
        printf("in[%d]: 0x%08lX\n", i, static_cast<unsigned long>(hr));
        if (hr == MF_E_NOTACCEPTING) {
            // 抽输出
            MFT_OUTPUT_DATA_BUFFER ob{};
            ComPtr<IMFSample> os; ComPtr<IMFMediaBuffer> obuf;
            MFCreateMemoryBuffer(nv * 4, &obuf);
            MFCreateSample(&os); os->AddBuffer(obuf.Get()); ob.pSample = os.Get();
            DWORD st = 0;
            HRESULT ohr = t->ProcessOutput(0, 1, &ob, &st);
            printf("  out: 0x%08lX size=%lu\n", static_cast<unsigned long>(ohr),
                   ohr == S_OK ? static_cast<unsigned long>(ob.pSample ? 1 : 0) : 0);
            if (ohr == S_OK && ob.pSample) {
                ComPtr<IMFMediaBuffer> b2;
                if (SUCCEEDED(ob.pSample->ConvertToContiguousBuffer(&b2))) {
                    BYTE* d = nullptr; DWORD ln = 0;
                    b2->Lock(&d, nullptr, &ln);
                    printf("  -> %lu bytes, first16:", ln);
                    for (DWORD k = 0; k < ln && k < 16; ++k) printf(" %02X", d[k]);
                    printf("\n");
                    fwrite(d, 1, ln, f);
                    b2->Unlock();
                }
                ob.pSample->Release();
            }
        }
    }
    // 最后一轮抽干
    for (int i = 0; i < 3; ++i) {
        MFT_OUTPUT_DATA_BUFFER ob{};
        ComPtr<IMFSample> os; ComPtr<IMFMediaBuffer> obuf;
        MFCreateMemoryBuffer(nv * 4, &obuf);
        MFCreateSample(&os); os->AddBuffer(obuf.Get()); ob.pSample = os.Get();
        DWORD st = 0;
        HRESULT ohr = t->ProcessOutput(0, 1, &ob, &st);
        if (ohr == S_OK && ob.pSample) {
            ComPtr<IMFMediaBuffer> b2;
            if (SUCCEEDED(ob.pSample->ConvertToContiguousBuffer(&b2))) {
                BYTE* d = nullptr; DWORD ln = 0;
                b2->Lock(&d, nullptr, &ln);
                printf("drain %lu bytes, first16:", ln);
                for (DWORD k = 0; k < ln && k < 16; ++k) printf(" %02X", d[k]);
                printf("\n");
                fwrite(d, 1, ln, f);
                b2->Unlock();
            }
            ob.pSample->Release();
        } else {
            printf("drain: 0x%08lX\n", static_cast<unsigned long>(ohr));
        }
        if (ob.pEvents) ob.pEvents->Release();
    }
    fclose(f);
    printf("saved probe_out.h264\n");
    return 0;
}
