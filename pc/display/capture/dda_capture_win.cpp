// Desktop Duplication 抓屏（Windows）
//
// 关键点：
// 1) 通过 DXGI 枚举适配器/输出，挑出 IddCx 虚拟显示器（deviceName 含 \\.\DISPLAY 且非主显示）
// 2) IDXGIOutput5::DuplicateOutput1 支持 DXGI_FORMAT_B8G8R8A8_UNORM，拿到桌面纹理
// 3) AcquireNextFrame -> GetFrameDirtyRects/GetFrameMoveRects -> 只上报变化区域
// 4) 维护一份「影子缓冲」（CPU 侧上一帧），MoveRects 先在影子上做位移拷贝，
//    再把 DestRect 也标脏 —— 这样编码器拿到的像素始终与桌面一致
// 5) 同时给出 ID3D11Texture2D（零拷贝硬编路径）与 CPU 影子缓冲（软编/回退路径）
#ifdef _WIN32

#include "capture/i_capture.hpp"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include <d3d11.h>
#include <dxgi1_5.h>
#include <wrl/client.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <mutex>
#include <vector>

#include "common/log.hpp"
#include "protocol/frame_format.hpp"

using Microsoft::WRL::ComPtr;

namespace apxdisp {
namespace {

// DXGI 错误码转成可读文本
std::string hrText(HRESULT hr) {
    char buf[128];
    snprintf(buf, sizeof(buf), "HRESULT=0x%08lX", static_cast<unsigned long>(hr));
    return std::string(buf);
}

struct MoveRect {
    int32_t sx, sy;
    Rect    dst;
};

}  // namespace

class DdaCapture : public ICapture {
public:
    DdaCapture() = default;
    ~DdaCapture() override { close(); }

    bool open(const CaptureTarget& target) override {
        close();
        target_ = target;

        if (!createD3dDevice()) return false;
        if (!pickOutput()) return false;
        if (!duplicateOutput()) return false;

        info_.width  = static_cast<uint32_t>(outputDesc_.DesktopCoordinates.right -
                                             outputDesc_.DesktopCoordinates.left);
        info_.height = static_cast<uint32_t>(outputDesc_.DesktopCoordinates.bottom -
                                             outputDesc_.DesktopCoordinates.top);
        info_.stride = info_.width * 4;
        info_.deviceName = outputDesc_.DeviceName;
        info_.zeroCopyD3D11 = true;

        // CPU 影子缓冲（自顶向下 BGRA）+ 无光标的干净副本（光标恢复用）
        shadow_.assign(static_cast<size_t>(info_.stride) * info_.height, 0);
        cleanShadow_.assign(shadow_.size(), 0);
        dumpEnabled_ = ::GetEnvironmentVariableA("APX_DUMP_SHADOW", nullptr, 0) > 0;
        opened_ = true;
        APX_LOG_I("DDA 打开成功: %u x %u", info_.width, info_.height);
        return true;
    }

    void close() override {
        opened_ = false;
        dup_.Reset();
        output1_.Reset();
        output_.Reset();
        adapter_.Reset();
        staging_.Reset();
        device_.Reset();
        context_.Reset();
        shadow_.clear();
    }

    bool isOpen() const override { return opened_; }
    bool needsReopen() const override { return reopenNeeded_; }
    const CaptureInfo& info() const override { return info_; }
    std::string lastError() const override { return lastError_; }

    bool acquireFrame(RawFrame& out, uint32_t timeoutMs) override {
        if (!opened_) {
            lastError_ = "未打开";
            return false;
        }

        ComPtr<IDXGIResource> resource;
        DXGI_OUTDUPL_FRAME_INFO frameInfo{};
        HRESULT hr = dup_->AcquireNextFrame(timeoutMs, &frameInfo, &resource);
        if (hr == DXGI_ERROR_WAIT_TIMEOUT) {
            return false;  // 桌面无变化，上层直接跳过（不占编码资源）
        }
        if (hr == DXGI_ERROR_ACCESS_LOST || hr == DXGI_ERROR_DEVICE_REMOVED ||
            hr == DXGI_ERROR_DEVICE_RESET || FAILED(hr)) {
            reopenNeeded_ = true;
            lastError_ = "AcquireNextFrame 失败 " + hrText(hr);
            APX_LOG_W("%s", lastError_.c_str());
            return false;
        }

        ComPtr<ID3D11Texture2D> frameTex;
        if (FAILED(resource.As(&frameTex)) || !frameTex) {
            dup_->ReleaseFrame();
            lastError_ = "IDXGIResource 不是 2D 纹理";
            return false;
        }

        // ---- 脏矩形 ----
        std::vector<Rect> dirty;
        UINT rectsNeeded = 0;
        std::vector<RECT> rawDirty;
        std::vector<DXGI_OUTDUPL_MOVE_RECT> rawMove;

        hr = dup_->GetFrameDirtyRects(0, nullptr, &rectsNeeded);
        if (SUCCEEDED(hr) && rectsNeeded > 0) {
            rawDirty.resize(rectsNeeded);
            hr = dup_->GetFrameDirtyRects(rectsNeeded, rawDirty.data(), &rectsNeeded);
            if (FAILED(hr)) rawDirty.clear();
        }
        UINT moveNeeded = 0;
        hr = dup_->GetFrameMoveRects(0, nullptr, &moveNeeded);
        if (SUCCEEDED(hr) && moveNeeded > 0) {
            rawMove.resize(moveNeeded);
            hr = dup_->GetFrameMoveRects(moveNeeded, rawMove.data(), &moveNeeded);
            if (FAILED(hr)) rawMove.clear();
        }

        // ---- 拷到 CPU 影子缓冲（软编/回退路径） ----
        bool cpuOk = copyToShadow(frameTex.Get());

        // MoveRects：先在影子上搬运，再把目标矩形标脏
        for (const auto& m : rawMove) {
            Rect r{};
            r.x = static_cast<uint16_t>(m.DestinationRect.left);
            r.y = static_cast<uint16_t>(m.DestinationRect.top);
            r.w = static_cast<uint16_t>(m.DestinationRect.right - m.DestinationRect.left);
            r.h = static_cast<uint16_t>(m.DestinationRect.bottom - m.DestinationRect.top);
            if (cpuOk) moveInShadow(m.SourcePoint, m.DestinationRect);
            dirty.push_back(r);
        }
        for (const RECT& rc : rawDirty) {
            Rect r{};
            r.x = static_cast<uint16_t>(rc.left);
            r.y = static_cast<uint16_t>(rc.top);
            r.w = static_cast<uint16_t>(rc.right - rc.left);
            r.h = static_cast<uint16_t>(rc.bottom - rc.top);
            dirty.push_back(r);
        }

        dup_->ReleaseFrame();

        // ---- 光标合成（DDA 帧不含指针层：扩展屏上"没有鼠标"的根因）----
        // 影子缓冲必须保持"干净桌面"，光标每次画上去前先恢复旧区域，画完把
        // 新旧两块区域并入脏矩形 —— 手机端才能看到会动的指针。
        if (cpuOk) {
            RECT oldRc{};
            const bool hadOld = takeLastCursorRect(oldRc);
            restoreCursorArea();
            RECT newRc{};
            const bool drewNow = drawCursor(newRc);
            auto pushRect = [&](const RECT& rc) {
                if (rc.right <= rc.left || rc.bottom <= rc.top) return;
                Rect r{};
                r.x = static_cast<uint16_t>(std::max<LONG>(0, rc.left));
                r.y = static_cast<uint16_t>(std::max<LONG>(0, rc.top));
                r.w = static_cast<uint16_t>(std::min<LONG>(rc.right, info_.width) - r.x);
                r.h = static_cast<uint16_t>(std::min<LONG>(rc.bottom, info_.height) - r.y);
                if (r.w > 0 && r.h > 0) dirty.push_back(r);
            };
            if (hadOld) pushRect(oldRc);
            if (drewNow) pushRect(newRc);
        }

        if (dirty.empty()) {
            // DDA 偶发返回空脏矩形集，按整帧处理一次，避免画面撕裂
            dirty.push_back(Rect{0, 0, static_cast<uint16_t>(info_.width),
                                 static_cast<uint16_t>(info_.height)});
        }

        const auto merged = mergeDirtyRects(dirty, info_.width, info_.height, kMaxDirtyRects);

        out.width  = info_.width;
        out.height = info_.height;
        out.stride = info_.stride;
        out.ptsNs  = 0;  // 由 pipeline 用 ClockSync 换算到手机时基后填写
        out.frameNumber = frameInfo.LastPresentTime.QuadPart;
        out.cpuData = cpuOk ? shadow_.data() : nullptr;
        out.d3d11Texture = frameTex.Get();  // 注意：ReleaseFrame 后仅在同一帧内有效
        out.dirty.rects = merged;
        out.dirty.full = false;

        // 诊断转储（APX_DUMP_SHADOW=1 启用）：每帧存 BMP（上限 60 张），排查推流闪烁
        if (dumpEnabled_ && dumpCount_ < 60) {
            dumpShadowBmp(dumpCount_++);
        }
        return true;
    }

private:
    bool createD3dDevice() {
        UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
#ifdef _DEBUG
        flags |= D3D11_CREATE_DEVICE_DEBUG;
#endif
        D3D_FEATURE_LEVEL level{};
        HRESULT hr = D3D11CreateDevice(
            nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, nullptr, 0,
            D3D11_SDK_VERSION, &device_, &level, &context_);
        if (FAILED(hr)) {
            // 虚拟机/无 GPU 环境退回 WARP，保证链路可跑通
            hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, flags, nullptr, 0,
                                   D3D11_SDK_VERSION, &device_, &level, &context_);
        }
        if (FAILED(hr)) {
            lastError_ = "D3D11CreateDevice 失败 " + hrText(hr);
            return false;
        }
        return true;
    }

    bool pickOutput() {
        ComPtr<IDXGIFactory1> factory;
        if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), &factory))) {
            lastError_ = "CreateDXGIFactory1 失败";
            return false;
        }

        int adapterIdx = 0;
        ComPtr<IDXGIAdapter1> fallback;
        bool found = false;

        while (true) {
            ComPtr<IDXGIAdapter1> adapter;
            if (factory->EnumAdapters1(static_cast<UINT>(adapterIdx), &adapter) == DXGI_ERROR_NOT_FOUND) break;

            int outputIdx = 0;
            while (true) {
                ComPtr<IDXGIOutput> output;
                if (adapter->EnumOutputs(static_cast<UINT>(outputIdx), &output) == DXGI_ERROR_NOT_FOUND) break;

                DXGI_OUTPUT_DESC desc{};
                if (SUCCEEDED(output->GetDesc(&desc))) {
                    const bool isPrimary = (desc.AttachedToDesktop && desc.DesktopCoordinates.left == 0 &&
                                            desc.DesktopCoordinates.top == 0);
                    if (!target_.deviceName.empty()) {
                        if (target_.deviceName == desc.DeviceName) {
                            adapter_ = adapter; output_ = output; outputDesc_ = desc; found = true; break;
                        }
                    } else if (target_.preferVirtual && !isPrimary) {
                        // 自动模式：优先非主显示器（IddCx 虚拟屏通常挂在这里）
                        adapter_ = adapter; output_ = output; outputDesc_ = desc; found = true; break;
                    } else if (!fallback) {
                        fallback = adapter;
                    }
                }
                ++outputIdx;
                if (found) break;
            }
            if (found) break;
            ++adapterIdx;
        }

        if (!found && fallback) {
            adapter_ = fallback;
            fallback->EnumOutputs(0, &output_);
            if (output_) {
                output_->GetDesc(&outputDesc_);
                found = true;
            }
        }
        if (!found) {
            lastError_ = "未找到可抓屏的 DXGI 输出（虚拟显示器未安装？）";
            return false;
        }
        return SUCCEEDED(output_.As(&output1_)) && output1_ != nullptr;
    }

    bool duplicateOutput() {
        ComPtr<IDXGIOutput5> output5;
        if (SUCCEEDED(output_.As(&output5)) && output5) {
            const DXGI_FORMAT formats[] = {DXGI_FORMAT_B8G8R8A8_UNORM};
            HRESULT hr = output5->DuplicateOutput1(
                device_.Get(), 0, ARRAYSIZE(formats), formats, &dup_);
            if (SUCCEEDED(hr)) return true;
            lastError_ = "DuplicateOutput1 失败 " + hrText(hr) + "，回退 DuplicateOutput";
        }
        ComPtr<IDXGIOutput1> out1;
        if (FAILED(output_.As(&out1)) || !out1) {
            lastError_ = "IDXGIOutput1 不可用";
            return false;
        }
        return SUCCEEDED(out1->DuplicateOutput(device_.Get(), &dup_));
    }

    bool ensureStaging(uint32_t w, uint32_t h) {
        if (staging_) {
            D3D11_TEXTURE2D_DESC d{};
            staging_->GetDesc(&d);
            if (d.Width == w && d.Height == h) return true;
            staging_.Reset();
        }
        D3D11_TEXTURE2D_DESC d{};
        d.Width = w; d.Height = h; d.MipLevels = 1; d.ArraySize = 1;
        d.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        d.SampleDesc.Count = 1;
        d.Usage = D3D11_USAGE_STAGING;
        d.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        return SUCCEEDED(device_->CreateTexture2D(&d, nullptr, &staging_));
    }

    bool copyToShadow(ID3D11Texture2D* src) {
        if (!src) return false;
        D3D11_TEXTURE2D_DESC sd{};
        src->GetDesc(&sd);
        if (!ensureStaging(sd.Width, sd.Height)) return false;
        context_->CopyResource(staging_.Get(), src);

        ComPtr<IDXGISurface> surface;
        if (FAILED(staging_.As(&surface)) || !surface) return false;
        DXGI_MAPPED_RECT mapped{};
        if (FAILED(surface->Map(&mapped, DXGI_MAP_READ))) return false;

        const uint32_t h = std::min<uint32_t>(info_.height, sd.Height);
        const uint32_t rowBytes = std::min<uint32_t>(info_.stride, static_cast<uint32_t>(mapped.Pitch));
        for (uint32_t y = 0; y < h; ++y) {
            std::memcpy(shadow_.data() + static_cast<size_t>(y) * info_.stride,
                        mapped.pBits + static_cast<size_t>(y) * mapped.Pitch, rowBytes);
        }
        surface->Unmap();
        // 干净影子同步（无光标版本），供光标区域恢复用
        std::memcpy(cleanShadow_.data(), shadow_.data(), shadow_.size());
        return true;
    }

    // ——————————————————— 光标合成（扩展屏"看得见的指针"） ———————————————————

    /// 影子缓冲转储为 24bpp BMP（诊断用）
    void dumpShadowBmp(uint32_t idx) {
        char path[MAX_PATH]{};
        if (!::GetTempPathA(MAX_PATH, path)) return;
        char file[MAX_PATH]{};
        ::snprintf(file, sizeof(file), "apx_shadow_%03u.bmp", idx);
        const std::string full = std::string(path) + file;
        const int32_t w = static_cast<int32_t>(info_.width);
        const int32_t h = static_cast<int32_t>(info_.height);
        const int32_t rowSize = ((w * 3 + 3) / 4) * 4;
        const uint32_t dataSize = static_cast<uint32_t>(rowSize) * h;
        std::ofstream f(full.c_str(), std::ios::binary);
        if (!f) return;
        uint8_t hdr[54]{};
        const uint32_t off = 54;
        hdr[0]='B'; hdr[1]='M';
        auto put32 = [&hdr](uint32_t o, uint32_t v) {
            hdr[o] = v & 0xFF; hdr[o+1] = (v>>8)&0xFF; hdr[o+2] = (v>>16)&0xFF; hdr[o+3] = (v>>24)&0xFF;
        };
        put32(2, off + dataSize);
        put32(10, off); put32(14, 40);
        put32(18, w); put32(22, h);
        hdr[26]=1; hdr[28]=24;
        put32(34, dataSize);
        f.write(reinterpret_cast<char*>(hdr), 54);
        std::vector<uint8_t> row(rowSize, 0);
        for (int32_t y = h - 1; y >= 0; --y) {   // BMP 自底向上
            const auto* src = shadow_.data() + static_cast<size_t>(y) * info_.stride;
            for (int32_t x = 0; x < w; ++x) {
                row[x*3+0] = src[x*4+0]; row[x*3+1] = src[x*4+1]; row[x*3+2] = src[x*4+2];
            }
            f.write(reinterpret_cast<char*>(row.data()), rowSize);
        }
    }

    /// 用干净影子恢复上一次光标画过的区域（防叠画污染）
    void restoreCursorArea() {
        if (!lastCursorValid_) return;
        const int32_t l = std::max<int32_t>(0, lastCursorRect_.left);
        const int32_t t = std::max<int32_t>(0, lastCursorRect_.top);
        const int32_t r = std::min<int32_t>(static_cast<int32_t>(info_.width), lastCursorRect_.right);
        const int32_t b = std::min<int32_t>(static_cast<int32_t>(info_.height), lastCursorRect_.bottom);
        for (int32_t y = t; y < b; ++y) {
            std::memcpy(shadow_.data() + static_cast<size_t>(y) * info_.stride + static_cast<size_t>(l) * 4,
                        cleanShadow_.data() + static_cast<size_t>(y) * info_.stride + static_cast<size_t>(l) * 4,
                        static_cast<size_t>(r - l) * 4);
        }
        lastCursorValid_ = false;
    }

    bool takeLastCursorRect(RECT& rc) {
        rc = lastCursorRect_;
        const bool v = lastCursorValid_;
        return v;
    }

    /// 把系统光标（简绘白色箭头）画到影子缓冲。光标不在本输出区域内则不动。
    /// @param rcOut 实际写过的像素区域（帧内坐标）
    bool drawCursor(RECT& rcOut) {
        CURSORINFO ci{};
        ci.cbSize = sizeof(ci);
        if (!::GetCursorInfo(&ci) || !(ci.flags & CURSOR_SHOWING)) return false;
        const LONG px = ci.ptScreenPos.x - outputDesc_.DesktopCoordinates.left;
        const LONG py = ci.ptScreenPos.y - outputDesc_.DesktopCoordinates.top;
        if (px + kCurW <= 0 || py + kCurH <= 0 ||
            px >= static_cast<LONG>(info_.width) || py >= static_cast<LONG>(info_.height)) {
            return false;
        }
        for (int cy = 0; cy < kCurH; ++cy) {
            const LONG y = py + cy;
            if (y < 0 || y >= static_cast<LONG>(info_.height)) continue;
            auto* row = reinterpret_cast<uint32_t*>(shadow_.data() + static_cast<size_t>(y) * info_.stride);
            for (int cx = 0; cx < kCurW; ++cx) {
                const LONG x = px + cx;
                if (x < 0 || x >= static_cast<LONG>(info_.width)) continue;
                const char c = kArrow[cy][cx];
                if (c == '.') continue;
                const uint32_t a = (c == 'X') ? 220u : 235u;
                const uint32_t src = (c == 'X') ? 0u : 0xFFFFFFFFu;   // BGRA：黑边/白身
                const uint32_t dst = row[x];
                const uint32_t ia = 255u - a;
                const uint32_t dr = dst & 0xFF, dg = (dst >> 8) & 0xFF, db = (dst >> 16) & 0xFF;
                const uint32_t sr = src & 0xFF, sg = (src >> 8) & 0xFF, sb = (src >> 16) & 0xFF;
                const uint32_t b = (sb * a + db * ia) / 255;
                const uint32_t gg = (sg * a + dg * ia) / 255;
                const uint32_t rr = (sr * a + dr * ia) / 255;
                row[x] = b | (gg << 8) | (rr << 16) | 0xFF000000u;
            }
        }
        rcOut.left = px;
        rcOut.top = py;
        rcOut.right = std::min<LONG>(px + kCurW, static_cast<LONG>(info_.width));
        rcOut.bottom = std::min<LONG>(py + kCurH, static_cast<LONG>(info_.height));
        lastCursorRect_ = rcOut;
        lastCursorValid_ = true;
        return true;
    }

    void moveInShadow(POINT src, RECT dst) {
        const int32_t w = dst.right - dst.left;
        const int32_t h = dst.bottom - dst.top;
        if (w <= 0 || h <= 0) return;
        std::vector<uint8_t> tmp(static_cast<size_t>(w) * 4 * h);
        for (int32_t row = 0; row < h; ++row) {
            const size_t sOff = (static_cast<size_t>(src.y + row) * info_.stride) + static_cast<size_t>(src.x) * 4;
            std::memcpy(tmp.data() + static_cast<size_t>(row) * w * 4,
                        shadow_.data() + sOff, static_cast<size_t>(w) * 4);
        }
        for (int32_t row = 0; row < h; ++row) {
            const size_t dOff = (static_cast<size_t>(dst.top + row) * info_.stride) + static_cast<size_t>(dst.left) * 4;
            std::memcpy(shadow_.data() + dOff, tmp.data() + static_cast<size_t>(row) * w * 4,
                        static_cast<size_t>(w) * 4);
        }
    }

    CaptureTarget target_;
    CaptureInfo   info_{};
    std::string   lastError_;
    bool opened_ = false;
    bool reopenNeeded_ = false;

    ComPtr<ID3D11Device>        device_;
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<IDXGIAdapter1>       adapter_;
    ComPtr<IDXGIOutput>         output_;
    ComPtr<IDXGIOutput1>        output1_;
    ComPtr<IDXGIOutputDuplication> dup_;
    ComPtr<ID3D11Texture2D>     staging_;
    DXGI_OUTPUT_DESC            outputDesc_{};
    std::vector<uint8_t>        shadow_;        // CPU 侧上一帧（BGRA，自顶向下；含已画光标）
    std::vector<uint8_t>        cleanShadow_;   // 同尺寸"无光标"影子（光标区域恢复用）

    RECT lastCursorRect_{};      // 上一次画光标的区域（帧内坐标）
    bool lastCursorValid_ = false;

    // 诊断转储（APX_DUMP_SHADOW=1 启用）
    bool dumpEnabled_ = false;
    uint32_t dumpCounter_ = 0;
    uint32_t dumpCount_ = 0;

    // 简绘箭头指针（'X'=黑描边 'o'=白填充 '.'=透明）
    static constexpr int kCurW = 12, kCurH = 18;
    static constexpr const char* kArrow[kCurH] = {
        "X...........",
        "XX..........",
        "XoX.........",
        "XooX........",
        "XoooX.......",
        "XooooX......",
        "XoooooX.....",
        "XooooooX....",
        "XoooooooX...",
        "XooooooooX..",
        "XoooooooooX.",
        "XooooXooooX.",
        "XooX.XooooX.",
        "XoX..XooooX.",
        "XX....XoooX.",
        "X......XooX.",
        ".......XooX.",
        "........XX..",
    };
};

std::unique_ptr<ICapture> createDdaCapture() { return std::make_unique<DdaCapture>(); }

}  // namespace apxdisp

#endif  // _WIN32
