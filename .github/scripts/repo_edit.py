#!/usr/bin/env python3
"""repo-edit：批 REL-11（补 docs/CHANGELOG.md 的 v223.29.11 段，2026-11-06）。

发版门禁：Release 正文的“本版本明细缺失”提示指向本文件。当前 CHANGELOG 最新
段为 v223.29.10，缺 v223.29.11 → 在 `## v223.29.10（2026-11-05）` 锚点前插入。
两阶段：先按期望次数全量校验，全过才写盘；已存在则幂等退出。
"""
import sys

PATH = "docs/CHANGELOG.md"
ANCHOR = "## v223.29.10（2026-11-05）\n"

SECTION = (
    "## v223.29.11（2026-11-06）\n\n"
    "摄像头真流（M7-c 批 2）+ ViGEm 手柄 + Sunshine 托管 + 悬浮球热键。\n\n"
    "### PC 端\n\n"
    "- **摄像头全链路（M7-c 主线）**：`camera_view.cpp/hpp`（GDI+ 解码 → 顶层预览窗 + NV12\n"
    "  写共享内存）；`vcam_source.cpp` 虚拟摄像头 DLL（apxvcam，微信/会议跨进程取帧）；\n"
    "  `media_session` kStreamCamera=6 收流计数；`web_panel` camera.toggle/vcam 动作 + 状态 JSON；\n"
    "  `panel_http` 0x10 camera 模块开关；CMake 仅 WIN32 编入 camera_view + apxvcam 安装随装。\n"
    "- **手柄真注入（ViGEmBus）**：本地编 ViGEmClient DLL，运行时探测 → 虚拟 X360，缺依赖\n"
    "  降级键鼠并记日志；portable/CI 产物新增 ViGEmClient.dll。\n"
    "- **Sunshine 子进程托管（M6）**：`sunshine_launch` 起/停/查 + `apxhost sunshine-spike`\n"
    "  子命令 + 面板 screen.sunshineStart/Stop（childAlive/exe 回包）。\n"
    "- **悬浮球（M4-c(b) 后半）**：全局热键切扇形（PostMessageW 投递球线程）；Windows 托盘\n"
    "  连接角标从空桩变真现。\n\n"
    "### mac 端\n\n"
    "- `ball_mac.mm` ObjC 作用域修复（补挂进构建后首编修复，Build #584）+ `<algorithm>` 头。\n\n"
    "### 手机端\n\n"
    "- `CameraPushActivity`（CameraX→JPEG 出流）、`MediaOut.camera()`、`ApxFrame.STREAM_CAMERA=6`、\n"
    "  面板 camera 开关手机侧接收。\n\n"
    "> ⚠️ 摄像头链路 CI 编译全绿但真机未实测（Win11 虚拟摄像头/微信出画未跑过），ROADMAP 已标注。\n\n"
)


def main() -> int:
    try:
        with open(PATH, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print(f"open failed: {e}")
        return 1
    if "## v223.29.11" in text:
        print("already exists, nothing to do")
        return 0
    n = text.count(ANCHOR)
    if n != 1:
        print(f"VALIDATION FAILED：anchor count={n} expect=1\n---anchor---\n{ANCHOR}")
        return 1
    text = text.replace(ANCHOR, SECTION + ANCHOR)
    with open(PATH, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"OK {PATH}: inserted v223.29.11 section")
    return 0


if __name__ == "__main__":
    sys.exit(main())
