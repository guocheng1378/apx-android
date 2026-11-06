#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器（无参执行；EDITS 自包含；两阶段：先全量校验命中次数，全过才写盘）。

批 5（2026-11-06）：macOS 面板摄像头接线（把 M7-c 从 Win WebView2 对齐到浏览器面板）。
  · panel_http buildState — 出 out["camera"]（字段对齐 bridge.ts；previewOpen=已下发的
    0x10 采集开关、native=false 无原生预览窗/虚拟摄像头、frames/bytes=收流计数、
    width/height/stale=收流回调实测 JPEG 尺寸与停滞）+ jpegDims SOF 解析 + 静态开关态
  · panel_http handleAct — camera.toggle 下发 sendModuleToggle("camera")（失败如实回报
    不改开关态）；camera.vcam 如实拒绝（同 screen.toggle 口径）
  · panel_http setHandler — handler 注册条件由 micBridge 改为 media 必在（mic 判空），
    增 camera 分支记尺寸/时刻（无解码消费者，camera_view 仅 Windows 编译）
  · bridge.ts — camera 类型增 native?: boolean
  · CameraCard.tsx — native=false 时：虚拟摄像头行改「不支持」文案、预览开关说明改
    「手机采集回传（浏览器面板无本地预览窗）」；Windows 不发该字段 = 默认 true 零变化
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

# (相对路径, [(old, new, 期望命中次数), ...]) —— 文本用三引号逐字节对齐源文件
EDITS = [
    ("pc/host/src/ui/panel_http.cpp", [
        ('''// ——— 状态构建（与 web_panel.cpp buildState 逐字段一致；GetTickCount64 → steadyMs）———
void buildState(const HostBundle& b, Json& out, HttpPanel* p) {''',
         '''// ——— 摄像头（macOS 批5，2026-11-06）：camera_view（GDI+ 解码/原生预览窗/虚拟摄像头）
//      仅 Windows 编译（CMake if(WIN32)），本入口没有 CameraView —— 开关态与收流观测
//      在此自持：g_camOn = 已下发的 0x10 采集开关；W/H/LastMs = 收流回调实测。——
static std::atomic<bool> g_camOn{false};
static std::atomic<uint32_t> g_camW{0}, g_camH{0};
static std::atomic<int64_t> g_camLastMs{0};  // steady ms 时间戳（0 = 尚无帧）

// JPEG SOF 段尺寸（手机侧只出 SOF0 基线；逐段扫到 SOF 即止，失败留 0）
static bool jpegDims(const uint8_t* p, size_t n, int& w, int& h) {
    if (!p || n < 4 || p[0] != 0xFF || p[1] != 0xD8) return false;
    size_t i = 2;
    while (i + 3 < n) {
        if (p[i] != 0xFF) { ++i; continue; }
        const uint8_t m = p[i + 1];
        if (m == 0xFF || m == 0x00) { ++i; continue; }
        if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }
        if (m == 0xD9 || m == 0xDA) return false;   // 到尾/压缩数据起点仍无 SOF
        const size_t seg = (static_cast<size_t>(p[i + 2]) << 8) | p[i + 3];
        if (seg < 2) return false;
        if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
            if (i + 9 > n) return false;
            h = (p[i + 5] << 8) | p[i + 6];
            w = (p[i + 7] << 8) | p[i + 8];
            return w > 0 && h > 0;
        }
        i += 2 + seg;
    }
    return false;
}

// ——— 状态构建（与 web_panel.cpp buildState 逐字段一致；GetTickCount64 → steadyMs）———
void buildState(const HostBundle& b, Json& out, HttpPanel* p) {''', 1),
        ('''        media["micFrames"] = static_cast<double>(cc.micFrames);
        media["dropped"] = static_cast<double>(cc.dropped);
    }

    // screen：本入口不挂 ScreenPush（见文件头边界说明）——**刻意不读 b.screenPush**，''',
         '''        media["micFrames"] = static_cast<double>(cc.micFrames);
        media["dropped"] = static_cast<double>(cc.dropped);
    }

    // camera（macOS 批5）：字段对齐 bridge.ts 契约、语义按浏览器面板如实降级 ——
    // previewOpen = 已下发的手机采集开关；native=false → 前端标注无原生预览窗/虚拟摄像头；
    // frames/bytes = media_session 收流计数；width/height/stale = 收流回调实测。
    {
        Json c = Json::makeObject();
        const bool on = g_camOn.load(std::memory_order_relaxed);
        uint64_t cf = 0, cb = 0;
        if (b.media) {
            const auto mc = b.media->counters();
            cf = mc.cameraFrames;
            cb = mc.cameraBytes;
        }
        const auto nowMs = std::chrono::steady_clock::now().time_since_epoch() /
                           std::chrono::milliseconds(1);
        const int64_t last = g_camLastMs.load(std::memory_order_relaxed);
        c["running"] = on;
        c["previewOpen"] = on;
        c["virtualCam"] = false;
        c["native"] = false;
        c["virtualCamTested"] = false;
        c["frames"] = static_cast<double>(cf);
        c["bytes"] = static_cast<double>(cb);
        c["lastFrameMs"] = static_cast<double>(last > 0 ? nowMs - last : 0);
        c["width"] = static_cast<double>(g_camW.load(std::memory_order_relaxed));
        c["height"] = static_cast<double>(g_camH.load(std::memory_order_relaxed));
        c["stale"] = cf > 0 && last > 0 && (nowMs - last) > 2500;
        c["error"] = "";
        out["camera"] = c;
    }

    // screen：本入口不挂 ScreenPush（见文件头边界说明）——**刻意不读 b.screenPush**，''', 1),
        ('''        return postResponse(p, id, ok ? okResp()
                                      : failResp(err.empty() ? "麦克风桥启动失败" : err));
    }''',
         '''        return postResponse(p, id, ok ? okResp()
                                      : failResp(err.empty() ? "麦克风桥启动失败" : err));
    }

    // —— 摄像头（macOS 批5）：无 camera_view（GDI+/原生预览窗/虚拟摄像头仅 Windows）——
    //    camera.toggle = 0x10「camera」下发手机采集（跨平台）；下发失败如实报错、不改开关态。
    if (name == "camera.toggle") {
        const bool on = !g_camOn.load(std::memory_order_relaxed);
        const bool sent = b.session && b.session->sendModuleToggle("camera", on);
        if (!sent) return postResponse(p, id, failResp("未连接手机，无法下发采集开关"));
        g_camOn.store(on, std::memory_order_relaxed);
        return postResponse(p, id, okResp());
    }
    if (name == "camera.vcam") {
        return postResponse(p, id, failResp("macOS 不支持虚拟摄像头（camera_view 仅 Windows）"));
    }''', 1),
        ('''    if (panel.bundle.media && panel.bundle.micBridge) {
        apxpc::media::MediaSession* media = panel.bundle.media;
        apxpc::media::MicBridge* mic = panel.bundle.micBridge;
        media->setHandler([mic](uint8_t streamId, uint8_t /*flags*/, uint32_t /*seq*/,
                                const uint8_t* body, size_t len) {
            if (streamId == apxpc::media::kStreamMic && mic->running()) {
                mic->feed(body, len);
            }
        });
    }''',
         '''    if (panel.bundle.media) {
        apxpc::media::MediaSession* media = panel.bundle.media;
        apxpc::media::MicBridge* mic = panel.bundle.micBridge;
        media->setHandler([mic](uint8_t streamId, uint8_t /*flags*/, uint32_t /*seq*/,
                                const uint8_t* body, size_t len) {
            if (streamId == apxpc::media::kStreamMic && mic && mic->running()) {
                mic->feed(body, len);
            } else if (streamId == apxpc::media::kStreamCamera) {
                // 摄像头（macOS 批5）：无解码消费者（camera_view 仅 Windows）——
                // 只记 JPEG 尺寸与最近帧时刻，buildState 据此出 width/height/stale。
                int w = 0, h = 0;
                if (jpegDims(body, len, w, h)) {
                    g_camW.store(static_cast<uint32_t>(w), std::memory_order_relaxed);
                    g_camH.store(static_cast<uint32_t>(h), std::memory_order_relaxed);
                }
                g_camLastMs.store(
                    std::chrono::steady_clock::now().time_since_epoch() /
                        std::chrono::milliseconds(1),
                    std::memory_order_relaxed);
            }
        });
    }''', 1),
    ]),
    ("pc/web/src/api/bridge.ts", [
        ('''  camera?: {
    running: boolean;
    previewOpen: boolean;
    virtualCam: boolean;''',
         '''  camera?: {
    running: boolean;
    previewOpen: boolean;
    virtualCam: boolean;
    /** false = 浏览器面板（macOS 批5）：无原生预览窗/虚拟摄像头，收流仍可用；缺省 = Windows 旧行为 */
    native?: boolean;''', 1),
    ]),
    ("pc/web/src/components/CameraCard.tsx", [
        ('''  const cam = state.camera;
  const preview = cam?.previewOpen ?? false;''',
         '''  const cam = state.camera;
  const preview = cam?.previewOpen ?? false;
  // false = 浏览器面板（macOS 批5）：无原生预览窗/虚拟摄像头，收流计数仍实时
  const native = cam?.native ?? true;''', 1),
        ('''      <div className="flex items-center justify-between">
        <span className="caption-text">虚拟摄像头（供微信/会议端选）</span>
        <Switch on={vcam} onChange={toggleVcam} />
      </div>''',
         '''      <div className="flex items-center justify-between">
        <span className="caption-text">
          {native ? '虚拟摄像头（供微信/会议端选）' : '虚拟摄像头（macOS 不支持，仅 Windows）'}
        </span>
        {native ? (
          <Switch on={vcam} onChange={toggleVcam} />
        ) : (
          <span className="caption-text text-on-tertiary">不可用</span>
        )}
      </div>''', 1),
        ('''            <div className="caption-text">打开右上开关：手机画面开预览窗到本机</div>''',
         '''            <div className="caption-text">
              {native
                ? '打开右上开关：手机画面开预览窗到本机'
                : '打开右上开关：手机开始采集，画面经媒体连接回传（浏览器面板无本地预览窗）'}
            </div>''', 1),
    ]),
]


def main():
    plan = []
    failed = False
    for rel, reps in EDITS:
        full = os.path.join(ROOT, rel)
        if not os.path.exists(full):
            print("MISSING_FILE " + rel)
            failed = True
            continue
        with open(full, "r", encoding="utf-8") as f:
            text = f.read()
        new = text
        for old, repl, want in reps:
            got = new.count(old)
            if got != want:
                print("ANCHOR_FAIL " + rel + " count=" + str(got) + " want=" + str(want))
                print("  old[0:80]=" + old[:80].replace("\n", "\\n"))
                failed = True
            else:
                new = new.replace(old, repl)
        if new != text:
            plan.append((rel, text, new))
    if failed:
        print("VALIDATE_FAILED: 有锚点未命中，不写盘。")
        return 1
    if not plan:
        print("NO_CHANGES: 所有编辑的期望文本均已存在。")
        return 0
    for rel, text, new in plan:
        full = os.path.join(ROOT, rel)
        with open(full, "w", encoding="utf-8") as f:
            f.write(new)
        print("WROTE " + rel)
        diff = "".join(difflib.unified_diff(
            text.splitlines(keepends=True), new.splitlines(keepends=True),
            fromfile="a/" + rel, tofile="b/" + rel, n=3))
        print(diff)
    print("OK: wrote " + str(len(plan)) + " file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
