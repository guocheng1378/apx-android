#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器（无参执行；EDITS 自包含；两阶段：先全量校验命中次数，全过才写盘）。\n\n批 3（2026-11-06）：WebView2 主架构收流接线 + 摄像头计数。\n  · media_session.hpp — Counters 加 cameraFrames/cameraBytes + 原子成员\n  · media_session.cpp — connect 重置、counters() 读取、readerLoop kStreamCamera 分支\n  · web_panel.cpp — runWebPanel 注册 FrameHandler（camera→CameraView.feed / mic→MicBridge.feed）\n    此前面板从未 setHandler，handler_ 恒空，摄像头 JPEG 与手机麦克风 PCM 到 PC 后被丢弃\n  · web_panel.cpp — buildState media 块暴露 cameraFrames\n"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

# (相对路径, [(old, new, 期望命中次数), ...])
EDITS = [
    ("pc/host/include/apxpc/media/media_session.hpp", [
        ("        uint64_t micFrames = 0, micBytes = 0;        // 麦克风：本端收到",
         "        uint64_t micFrames = 0, micBytes = 0;        // 麦克风：本端收到\n"
         "        uint64_t cameraFrames = 0, cameraBytes = 0; // 摄像头：本端收到（JPEG，M7-c）", 1),
        ("    std::atomic<uint64_t> cMicFrames_{0}, cMicBytes_{0};",
         "    std::atomic<uint64_t> cMicFrames_{0}, cMicBytes_{0};\n"
         "    std::atomic<uint64_t> cCameraFrames_{0}, cCameraBytes_{0};", 1),
    ]),
    ("pc/host/src/media/media_session.cpp", [
        ("    cMicFrames_ = 0;   cMicBytes_ = 0;",
         "    cMicFrames_ = 0;   cMicBytes_ = 0;\n    cCameraFrames_ = 0; cCameraBytes_ = 0;", 1),
        ("    c.micFrames = cMicFrames_.load(std::memory_order_relaxed);\n"
         "    c.micBytes = cMicBytes_.load(std::memory_order_relaxed);",
         "    c.micFrames = cMicFrames_.load(std::memory_order_relaxed);\n"
         "    c.micBytes = cMicBytes_.load(std::memory_order_relaxed);\n"
         "    c.cameraFrames = cCameraFrames_.load(std::memory_order_relaxed);\n"
         "    c.cameraBytes = cCameraBytes_.load(std::memory_order_relaxed);", 1),
        ("                case kStreamMic:    bump(bodyLen, cMicFrames_, cMicBytes_); break;",
         "                case kStreamMic:    bump(bodyLen, cMicFrames_, cMicBytes_); break;\n"
         "                // M7-c 批 3：摄像头 JPEG 上行也计数 —— 与 handler 投递解耦，\n"
         "                // 即便面板没注册回调，「链路到底走没走」也有客观证据可看。\n"
         "                case kStreamCamera: bump(bodyLen, cCameraFrames_, cCameraBytes_); break;", 1),
    ]),
    ("pc/host/src/ui/web_panel.cpp", [
        ("    g_panel = &panel;\n    panel.running.store(true);",
         "    g_panel = &panel;\n    panel.running.store(true);\n"
         "    // M7-c 批 3：收流回调注册。此前 WebView2 面板从未 setHandler（handler_ 恒空）——\n"
         "    // 摄像头 JPEG 与手机麦克风 PCM 到 PC 后被直接丢弃：CameraCard 只能切开关、\n"
         "    // 一帧收不到，micBridge 同样吃不到数据。契约见 media_session.hpp FrameHandler：\n"
         "    // 回调在收流线程上跑，只做投递、不碰 UI 不阻塞；runWebPanel 退出会把 g_panel\n"
         "    // 置空，晚到的回调判空即返（与互传接收端同一约定）。\n"
         "    if (panel.bundle.media) {\n"
         "        panel.bundle.media->setHandler([](uint8_t streamId, uint8_t /*flags*/,\n"
         "                                          uint32_t /*seq*/, const uint8_t* body, size_t len) {\n"
         "            Panel* gp = g_panel;\n"
         "            if (!gp || !gp->running.load()) return;\n"
         "            if (streamId == apxpc::media::kStreamCamera) {\n"
         "                apxpc::media::cameraView().feed(body, len);   // 只入队，解码/渲染在消费线程\n"
         "            } else if (streamId == apxpc::media::kStreamMic) {\n"
         "                if (gp->bundle.micBridge) gp->bundle.micBridge->feed(body, len);\n"
         "            }\n"
         "        });\n"
         "        APX_LOGI(\"媒体收流回调已注册（camera→CameraView / mic→MicBridge）\");\n"
         "    }", 1),
        ("        media[\"micFrames\"] = static_cast<double>(cc.micFrames);\n"
         "        media[\"dropped\"] = static_cast<double>(cc.dropped);",
         "        media[\"micFrames\"] = static_cast<double>(cc.micFrames);\n"
         "        media[\"cameraFrames\"] = static_cast<double>(cc.cameraFrames);\n"
         "        media[\"dropped\"] = static_cast<double>(cc.dropped);", 1),
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
