#!/usr/bin/env python3
"""repo-edit：单文件锚点替换（两阶段：先校验命中次数，全过才写盘）。

批 5 尾巴（2026-11-06）：ROADMAP §2.4 补「浏览器面板摄像头」行（M7-c 已收口，与 §2.3 对齐）。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

EDITS = [
    ("docs/ROADMAP.md", [
        ('''| 媒体通道 9502 | ✅ | `media_session.cpp` 跨平台（connect/收发流/5s 拥塞重试，6 处等价变换保留 Win 语义）；面板媒体泵自动建链 |''',
         '''| 媒体通道 9502 | ✅ | `media_session.cpp` 跨平台（connect/收发流/5s 拥塞重试，6 处等价变换保留 Win 语义）；面板媒体泵自动建链 |''', 1),
        ('''| 麦克风桥 | ✅ | `mic_bridge.cpp` `__APPLE__` 分支：CoreAudio **AudioQueue**（回调驱动、缺数据补静音、400ms 环形上限）；接线 = panel_http `setHandler`（`kStreamMic → feed`） |''',
         '''| 麦克风桥 | ✅ | `mic_bridge.cpp` `__APPLE__` 分支：CoreAudio **AudioQueue**（回调驱动、缺数据补静音、400ms 环形上限）；接线 = panel_http `setHandler`（`kStreamMic → feed`） |''', 1),
        ('''| 音箱（系统声音） | ✅ | `audio_capture_mac.mm`：**CoreAudio Process Tap**（`CATapDescription` 全局立体声 + 聚合设备 + IOProc），**macOS 14.2+**，需「系统音频录制」TCC 授权；DRM 内容可能静音（平台限制）；部署目标压 12.0 使新 API 弱链接可降级 |''',
         '''| 音箱（系统声音） | ✅ | `audio_capture_mac.mm`：**CoreAudio Process Tap**（`CATapDescription` 全局立体声 + 聚合设备 + IOProc），**macOS 14.2+**，需「系统音频录制」TCC 授权；DRM 内容可能静音（平台限制）；部署目标压 12.0 使新 API 弱链接可降级 |''', 1),
        ('''| 副屏 | ❌ | 虚拟显示器仅 Windows；`screenPush=nullptr`，面板显示未初始化形态 + `screen.toggle` 明确拒绝 |''',
         '''| 副屏 | ❌ | 虚拟显示器仅 Windows；`screenPush=nullptr`，面板显示未初始化形态 + `screen.toggle` 明确拒绝 |''', 1),
        ('''| 全局热键 | ❌ | 热键 Win-only（mac 侧 `HotkeyManager` 是空实现，面板如实显示不可用） |''',
         '''| 全局热键 | ❌ | 热键 Win-only（mac 侧 `HotkeyManager` 是空实现，面板如实显示不可用） |''', 1),
        ('''| USB 有线（枚举） | ◑ | v223.7 起 mac 走 **IOKit**（`usb_enum_iokit.cpp`）：VID/PID/名称/序列号/bcdUSB/接口类码齐（速度留 unknown，理由见文件头），面板 USB 状态不再恒 false；**端点/HID 集合/CDC 串口名未做**（见文件头注释），且只有编译级验证 |''',
         '''| USB 有线（枚举） | ◑ | v223.7 起 mac 走 **IOKit**（`usb_enum_iokit.cpp`）：VID/PID/名称/序列号/bcdUSB/接口类码齐（速度留 unknown，理由见文件头），面板 USB 状态不再恒 false；**端点/HID 集合/CDC 串口名未做**（见文件头注释），且只有编译级验证 |''', 1),
        ('''| 开机自启 | ✅ | `~/Library/LaunchAgents/` plist（`autostart.cpp` 非 Win 分支） |''',
         '''| 开机自启 | ✅ | `~/Library/LaunchAgents/` plist（`autostart.cpp` 非 Win 分支） |''', 1),
        ('''| 检查更新 | ✅ | `update_check_mac.mm`（NSURLSession + gh-proxy 镜像兑底，与 Windows 同比对算法） |''',
         '''| 检查更新 | ✅ | `update_check_mac.mm`（NSURLSession + gh-proxy 镜像兑底，与 Windows 同比对算法） |''', 1),
        ('''| 发版产物 | ✅ | macos.yml `tags: ['v*']` → `AllPeriph-<tag>-macos-arm64.zip` 上传 Release（与 build.yml release job 双向幂等） |''',
         '''| 发版产物 | ✅ | macos.yml `tags: ['v*']` → `AllPeriph-<tag>-macos-arm64.zip` 上传 Release（与 build.yml release job 双向幂等） |''', 1),
        ('''| 浏览器面板摄像头 | ✅ | `panel_http.cpp` + `bridge.ts`：0x10「camera」拉起手机采集（跨平台，与 §2.3 同通道）；收流计数/尺寸/停滞实时上卡片；**无原生预览窗与虚拟摄像头**（camera_view 仅 Windows，CMake `if(WIN32)`，诚实标注） |''',
         '''| 浏览器面板摄像头 | ✅ | `panel_http.cpp` + `bridge.ts`：0x10「camera」拉起手机采集（跨平台，与 §2.3 同通道）；收流计数/尺寸/停滞实时上卡片；**无原生预览窗与虚拟摄像头**（camera_view 仅 Windows，CMake `if(WIN32)`，诚实标注） |''', 1),
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
