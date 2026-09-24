// DXGI 输出枚举探针：DDA 视角下虚拟屏在不在
#include <windows.h>
#include <dxgi.h>
#include <cstdio>
#pragma comment(lib, "dxgi.lib")
int main() {
    IDXGIFactory1* f = nullptr;
    if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), (void**)&f))) { printf("factory FAIL\n"); return 1; }
    IDXGIAdapter1* a = nullptr;
    for (UINT i = 0; f->EnumAdapters1(i, &a) != DXGI_ERROR_NOT_FOUND; ++i) {
        DXGI_ADAPTER_DESC1 ad{};
        a->GetDesc1(&ad);
        wprintf(L"adapter[%u] %s\n", i, ad.Description);
        IDXGIOutput* o = nullptr;
        for (UINT j = 0; a->EnumOutputs(j, &o) != DXGI_ERROR_NOT_FOUND; ++j) {
            DXGI_OUTPUT_DESC d{};
            o->GetDesc(&d);
            wprintf(L"  output[%u] %s attached=%d rect=%ld,%ld-%ld,%ld\n", j, d.DeviceName,
                    d.AttachedToDesktop, d.DesktopCoordinates.left, d.DesktopCoordinates.top,
                    d.DesktopCoordinates.right, d.DesktopCoordinates.bottom);
            o->Release();
        }
        a->Release();
    }
    f->Release();
    return 0;
}
