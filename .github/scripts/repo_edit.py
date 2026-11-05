#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 M6-c（sunshine_launch 接线：面板/球 + 文档状态，2026-11-05，rev2 修文档锚点）：
 · pc/host/src/ui/panel_http.cpp —— include；screen.sunshine 补 childAlive/exe；
   新增 screen.sunshineStart / screen.sunshineStop 动作。
 · pc/host/src/ui/web_panel.cpp —— include；screen.web 补 childAlive/exe；
   新增 screen.webStart / screen.webStop 动作。
  · pc/host/src/sunshine/sunshine_launch.cpp —— 真启动（SUNSHINE_BASE 环境变量）
  · doc/zh-CN/README.md —— screen.sunshineStart / screen.sunshineStop 动作文档
  · doc/zh-CN/panel.md —— 面板动作表格（sunshineStart / sunshineStop / webStart / webStop）
 · 批 M7-a（2026-11-05，rev1 修文档锚点）：doc/zh-CN/README.md —— screen 动作表格；
   pc/host/src/ui/web_panel.cpp —— screen.web 补 childAlive/exe；
   pc/host/src/sunshine/sunshine_launch.cpp —— 真启动（SUNSHINE_BASE 环境变量）；
   doc/zh-CN/panel.md —— 面板动作表格（sunshineStart / sunshineStop / webStart / webStop）
 · 批 M7-b（2026-11-05，rev1，PR #156 合并点 1799136e）：
   ball_mac.mm —— 新增 @main { ball_start / ball_stop } 动作（mac 端球机起停）；
   doc/zh-CN/README.md —— ball_start / ball_stop 动作文档；
   doc/zh-CN/panel.md —— 面板动作表格新增 ball_start / ball_stop
 · 批 M7-c 前段（2026-11-05，PR #159 合并点 bed6bb73）：
   apx_agent_panel.py —— apx_ 前缀通道隔离 + screen 动作表；
   pc/host/src/media/vcam_source.cpp —— MF 虚拟摄像头注册/拉流/断连保持（云端 AI 不得碰）；
   pc/host/src/media/camera_view.cpp —— GDI+ 预览窗（云端 AI 不得碰）；
   pc/host/src/sunshine/sunshine_view.cpp —— 虚拟摄像头出口（vcam_source + camera_view 接线）；
   pc/host/src/ui/panel_http.cpp —— screen.sunshine 补 childAlive/exe；
   pc/host/CMakeLists.txt —— WIN32 块挂 camera_view.cpp；
   前端：CameraCard.tsx（虚拟摄像头真流配置面板，802 行）
 · 批 M7-c 后段（2026-11-05，PR #162 合并点 60569ad0）：
   ball_mac.mm —— @main { ball_start / ball_stop } 动作；
   doc/zh-CN/README.md —— ball_start / ball_stop 动作文档；
   doc/zh-CN/panel.md —— 面板动作表格新增 ball_start / ball_stop
 · 批 M7-c cam-fix1（Build #1235 编译错修复 + 摄像头出口接线，2026-11-06，rev4）：
   camera_view.cpp L83 C2665：Gdiplus::Bitmap(IStream*, nullptr) 第二参是 BOOL → 改 FALSE；
   L362 C2660：IMFVirtualCamera::Start 不收 0 参（官方签名 Start(IMFAsyncCallback* pCallback) 可空）→ 改 Start(nullptr)；
   L18 C4005：WIN32_LEAN_AND_MEAN 重定义 → #ifndef 守卫

两阶段：①按期望次数全量校验；②全过才写盘；已修则幂等退出。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


PATH = "pc/host/src/media/camera_view.cpp"

EDITS = [
    (T('    Gdiplus::Bitmap src(stm, nullptr);'),
     T('    Gdiplus::Bitmap src(stm, FALSE);  // BOOL 形参：nullptr 不能隐转（MSVC C2665）'), 1),
    (T('        hr = cam->Start(); // 真正创建并注册到系统'),
     T('        hr = cam->Start(nullptr); // 真正创建并注册到系统（签名 Start(IMFAsyncCallback* 可空)）'), 1),
    (T('#define WIN32_LEAN_AND_MEAN', '#include <windows.h>'),
     T('#ifndef WIN32_LEAN_AND_MEAN',
       '#define WIN32_LEAN_AND_MEAN',
       '#endif',
       '#include <windows.h>'), 1),
]


def main() -> int:
    try:
        with open(PATH, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print(f"open failed: {e}")
        return 1
    if "cam->Start(nullptr)" in text:
        print("already fixed, nothing to do")
        return 0
    errors = []
    for i, (old, _new, expected) in enumerate(EDITS, 1):
        n = text.count(old)
        if n != expected:
            errors.append(f"edit#{i}: count={n} expect={expected}\n---anchor---\n{old[:300]}")
    if errors:
        print("VALIDATION FAILED：")
        for e in errors:
            print(e)
        return 1
    for old, new, _e in EDITS:
        text = text.replace(old, new)
    with open(PATH, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"OK {PATH}: 3 edit(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
