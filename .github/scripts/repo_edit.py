#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器（无参执行；EDITS 自包含；两阶段：先全量校验命中次数，全过才写盘）。\n\n批2（2026-11-06 修正方案）：\n  · media_session.hpp — 补 kStreamCamera = 6（frame.h 已有 6，镜像枚举缺）\n  · camera_view.hpp/cpp — 单例 cameraView() 定义挪进 apxpc_media 库并声明\n  · web_panel.cpp — 删匿名层单例，改 using 引入同一实例\n  · panel_win32.cpp — include + setHandler 加 s6→cameraView().feed() 分支\n  · panel_http.cpp — 不改（非 Win 分支无 CameraView 实现，加了必链接失败）\n"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

# (相对路径, [(old, new, 期望命中次数), ...])
EDITS = [
    ("pc/host/include/apxpc/media/media_session.hpp", [
        ("    kStreamMic     = 5,\n",
         "    kStreamMic     = 5,\n    kStreamCamera  = 6,\n", 1),
    ]),
    ("pc/host/include/apxpc/media/camera_view.hpp", [
        ("    std::unique_ptr<Impl> impl_;\n};\n\n} // namespace apxpc::media",
         "    std::unique_ptr<Impl> impl_;\n};\n\n"
         "// M7-c 批 2：进程内单例 —— panel_win32 的 setHandler 喂帧、web_panel 的状态/动作\n"
         "// 必须命中同一个实例；定义放 camera_view.cpp（进 apxpc_media 库，Win 各目标都链）。\n"
         "CameraView& cameraView();\n\n} // namespace apxpc::media", 1),
    ]),
    ("pc/host/src/media/camera_view.cpp", [
        ("CameraView::Status CameraView::status() const { return impl_->status(); }\n\n} // namespace apxpc::media",
         "CameraView::Status CameraView::status() const { return impl_->status(); }\n\n"
         "// M7-c 批 2：单例从 web_panel.cpp 匿名命名空间挪到这里 —— 匿名层只有 web_panel.cpp\n"
         "// 自己可见，panel_win32.cpp（apxpc_ui）链不到 web_panel.cpp（apxpc_webpanel），\n"
         "// 放 apxpc_media 两边拿同一实例。\n"
         "CameraView& cameraView() {\n    static CameraView instance;\n    return instance;\n}\n\n"
         "} // namespace apxpc::media", 1),
    ]),
    ("pc/host/src/ui/web_panel.cpp", [
        ("// M7-c 1/8：摄像头出口单例——本批只接面板动作/状态；s6 收流 feed 在后续批接线。\n"
         "namespace {\n"
         "apxpc::media::CameraView& cameraView() {\n"
         "    static apxpc::media::CameraView v;\n"
         "    return v;\n"
         "}\n"
         "}  // namespace",
         "// M7-c 批 2：单例已挪进 camera_view.cpp（apxpc_media 库，Win 各目标都链）——\n"
         "// 匿名层单例 panel_win32.cpp 链不到，只会各喂各的。这里仅做引入，\n"
         "// 保证状态/动作与 s6 收流 feed 命中同一实例。\n"
         "using apxpc::media::cameraView;", 1),
    ]),
    ("pc/host/src/ui/panel_win32.cpp", [
        ("#include \"apxpc/media/audio_capture.hpp\"\n\n#include \"apxpc/media/media_session.hpp\"",
         "#include \"apxpc/media/audio_capture.hpp\"\n"
         "#include \"apxpc/media/camera_view.hpp\"   // M7-c 批 2：s6 收流 feed 进程内单例\n"
         "\n"
         "#include \"apxpc/media/media_session.hpp\"", 1),
        ("            panel.micBridge->feed(body, len);\n        }\n    });",
         "            panel.micBridge->feed(body, len);\n"
         "        } else if (streamId == apxpc::media::kStreamCamera) {\n"
         "            // M7-c 批 2：手机 JPEG 单帧（streamId=6）→ 摄像头出口单例\n"
         "            // （只入队，GDI+ 解码/渲染在消费线程 —— 见 camera_view.hpp 头注释）\n"
         "            apxpc::media::cameraView().feed(body, len);\n"
         "        }\n"
         "    });", 1),
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
