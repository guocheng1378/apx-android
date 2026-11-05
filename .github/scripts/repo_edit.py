#!/usr/bin/env python3
"""repo-edit：批 M7-c 1/8 修复（rev3）：恢复 web_panel.cpp + 摄像头接线。

背景：1/8 的一次锚点事故把 pc/host/src/ui/web_panel.cpp 掏空到 33 行
（diff @@ -30,1487 +30,4，只剩一行 #include <shobjidl.h>），Windows pc job
编译必挂（Build #1232 failure）。本脚本：
  ① 检测到残缺（无 buildState）→ 从 raw.githubusercontent 拉 pinned commit
     1799136（合 M7-b 后的 master）原版作底稿，并做完整性探针；
  ② 底稿上做 4 组接线锚点（include / 单例 / state.camera 状态块 / camera.toggle+camera.vcam 动作）；
  ③ 两阶段：按期望次数全量校验，全过才写盘；已接线过（幂等）则直接退出。
前端契约：pc/web/src/api/bridge.ts 的 state.camera + camera.toggle / camera.vcam。
"""
import sys
import urllib.request

PATH = "pc/host/src/ui/web_panel.cpp"
BASE_URL = (
    "https://raw.githubusercontent.com/guocheng1378/apx-android/"
    "1799136ec60adab98fe90b8fb62e570649e27d7d/pc/host/src/ui/web_panel.cpp"
)


def T(*lines):
    return "\n".join(lines) + "\n"


MIC_STATE = T(
    '    if (b.micBridge) {',
    '        auto s = b.micBridge->status();',
    '        Json m = Json::makeObject();',
    '        m["running"] = s.running;',
    '        m["device"] = s.device;',
    '        media["mic"] = m;',
    '    }',
)

MIC_TOGGLE = T(
    '    if (name == "mic.toggle" && b.micBridge) {',
    '        if (b.micBridge->running()) { b.micBridge->stop(); return postResponse(p, id, okResp()); }',
    '        std::string devId = payload.find("deviceId") ? payload.find("deviceId")->asString() : "";',
    '        std::string err;',
    '        const bool ok = b.micBridge->start(devId, &err);',
    '        return postResponse(p, id, ok ? okResp() : failResp(err));',
    '    }',
)

EDITS = [
    # ① include
    (T('#include "apxpc/media/mic_bridge.hpp"'),
     T('#include "apxpc/media/mic_bridge.hpp"',
       '#include "apxpc/media/camera_view.hpp"  // M7-c 1/8：面板接摄像头（预览窗 + 虚拟摄像头出口）'), 1),
    # ② 文件级单例（放在 buildState 前，同一实例供状态与动作共用；s6 收流 feed 后续批接）
    (T('void buildState(const HostBundle& b, Json& out) {'),
     T('// M7-c 1/8：摄像头出口单例——本批只接面板动作/状态；s6 收流 feed 在后续批接线。',
       'namespace {',
       'apxpc::media::CameraView& cameraView() {',
       '    static apxpc::media::CameraView v;',
       '    return v;',
       '}',
       '}  // namespace',
       '',
       'void buildState(const HostBundle& b, Json& out) {'), 1),
    # ③ state.camera（root 键，字段 = CameraView::Status 逐字段，对齐 bridge.ts）
    (MIC_STATE,
     MIC_STATE + T('',
       '    // M7-c 1/8：摄像头状态（root 键 camera = CameraView::Status 逐字段，对齐 bridge.ts 契约）',
       '    {',
       '        auto s = cameraView().status();',
       '        Json c = Json::makeObject();',
       '        c["running"] = s.running;',
       '        c["previewOpen"] = s.previewOpen;',
       '        c["virtualCam"] = s.virtualCam;',
       '        c["virtualCamTested"] = s.virtualCamTested;',
       '        c["frames"] = static_cast<double>(s.frames);',
       '        c["bytes"] = static_cast<double>(s.bytes);',
       '        c["lastFrameMs"] = static_cast<double>(s.lastFrameMs);',
       '        c["width"] = static_cast<double>(s.width);',
       '        c["height"] = static_cast<double>(s.height);',
       '        c["stale"] = s.stale;',
       '        c["error"] = s.error;',
       '        out["camera"] = c;',
       '    }'), 1),
    # ④ 两个面板动作（CameraCard 契约）
    (MIC_TOGGLE,
     MIC_TOGGLE + T('',
       '    // —— M7-c 1/8：摄像头（预览窗 / 虚拟摄像头；虚拟摄像头未真机实测，错误如实回在 Status.error）——',
       '    if (name == "camera.toggle") {',
       '        auto& cv = cameraView();',
       '        cv.setPreview(!cv.status().previewOpen);',
       '        return postResponse(p, id, okResp());',
       '    }',
       '    if (name == "camera.vcam") {',
       '        auto& cv = cameraView();',
       '        cv.setVirtualCam(!cv.status().virtualCam);',
       '        return postResponse(p, id, okResp());',
       '    }'), 1),
]


def main() -> int:
    try:
        cur = open(PATH, encoding="utf-8").read()
    except OSError as e:
        print(f"open failed: {e}")
        return 1
    if 'name == "camera.vcam"' in cur and "camera_view.hpp" in cur:
        print("already wired, nothing to do")
        return 0
    if "void buildState" in cur and 'name == "mic.toggle"' in cur:
        base = cur
        print("base: current file (full) -> apply wiring edits")
    else:
        print("base: gutted detected -> fetch pinned master 1799136")
        try:
            with urllib.request.urlopen(BASE_URL, timeout=30) as r:
                base = r.read().decode("utf-8")
        except Exception as e:  # noqa: BLE001
            print(f"raw fetch failed: {e}")
            return 1
        for probe in ("void buildState", 'name == "mic.toggle"', "}  // namespace apxpc::ui",
                      '#include "apxpc/media/mic_bridge.hpp"'):
            if probe not in base:
                print(f"BASE PROBE MISSING: {probe}")
                return 1
    errors = []
    for i, (old, _new, expected) in enumerate(EDITS, 1):
        n = base.count(old)
        if n != expected:
            errors.append(f"edit#{i}: count={n} expect={expected}\n---anchor---\n{old[:250]}")
    if errors:
        print("VALIDATION FAILED：")
        for e in errors:
            print(e)
        return 1
    for old, new, _e in EDITS:
        base = base.replace(old, new)
    with open(PATH, "w", encoding="utf-8") as f:
        f.write(base)
    print(f"OK {PATH}: restored+4 edits, {len(base)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
