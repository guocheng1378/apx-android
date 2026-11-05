#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 REL-1（发版门禁）：docs/CHANGELOG.md 1 处。
根因：v223.29.10 段只写了 N-1 通知修复，但昨夜 P-1~P-4/单实例锁 + 今日
批 B/D1/M4-a/M4-b/O-1 均已入 master 未发版——tag 打上去会漏写大段改动（门禁）。
修法：在 N-1 小节末尾、v223.29.9 标题之前，补三个小节（P 系夜批 / 批 B+D1 / M4+O-1），
逐条写清改了什么、为什么、真机回验状态（三雷如实标待真机）。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("docs/CHANGELOG.md", [
        (T(
            " `ACTION_STOP` 仍由这三处的派发链路使用，只是不再挂在通知点击上。",
            "",
            "## v223.29.9（2026-11-05）",
        ), T(
            " `ACTION_STOP` 仍由这三处的派发链路使用，只是不再挂在通知点击上。",
            "",
            "### PC 桌面悬浮球初装与稳定化（P-1~P-4 + 单实例锁，昨夜入库）",
            "",
            "- 球接入 web 面板（点击唤回面板）、单实例互斥锁（双击/自启不再出双窗双托盘）、",
            "  CMake 目标修正（球源入 apxpc_webpanel）、LoadCursorW/GetMonitorInfoW 字符集编译修正",
            "  —— 四批 repo-edit 两阶段锚点补丁；Build #1138/#1170 绿。",
            "",
            "### PC 悬浮球真正跑起来（批 B 接线 + D1 重交互分流）",
            "",
            "- 此前球代码在仓但全库无 installDefaultActions/createBall 调用方：能编译、跑起来没球、",
            "  扇形空盘。批 B 在 runWebPanel 装配注册表四项钩子（开面板/发剪贴板/音箱/退出），",
            "  发剪贴板=连接前置+空回 false 灰显，退出与托盘同源 running=false+WM_CLOSE 真退出，",
            "  退出路径补 destroyBall（与 legacy 同生命周期）。PR #159，Build #1176 绿。",
            "- D1：bridge.ts 动作白名单 + open_panel 直开 apxhost panel 浏览器页——重交互",
            "  （文件浏览/日志/设置）不进球，扇形只放轻项。PR #160，Build #1180 绿。",
            "",
            "### macOS 悬浮球与菜单栏连接角标（M4 全套）",
            "",
            "- 新增 ball_mac.mm：AppKit 透明窗同窗渲染球+扇形（registry/fx 共享层驱动，16ms 主钟，",
            "  拖拽/按压脉冲），hpp #else 最小声明、CMake if(APPLE) 源列表、desktop 装配与收尾四件套。",
            "- 菜单栏连接角标：TrayIcon::setBadge 跨平台 API——mac 未连接亮红点（原子量入队+泵内",
            "  主线程上色，AppKit 线程红线不破）；Windows 托盘角标为内联空桩，二期实现。",
            "- 验证：分支 Build #1183 + macOS #559 双绿；**桌面球三雷（WebView2 焦点/托盘共存/",
            "  全屏置顶）待真机回验**。PR #161。",
            "",
            "### 手机球旋转重算 + 动效（批 O-1，真机已验）",
            "",
            "- 悬浮球拖动后的旋转重算与动效（8 处锚点，随 repo-edit 入库）；Build #1187 绿，",
            "  2026-11-05 真机验证通过。",
            "",
            "## v223.29.9（2026-11-05）",
        ), 1),
    ]),
]


def main() -> int:
    loaded = {}
    errors = []
    for path, entries in EDITS:
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as e:
            errors.append(f"{path}: open failed: {e}")
            continue
        loaded[path] = (text, entries)
        for i, (old, _new, expected) in enumerate(entries, 1):
            n = text.count(old)
            if n != expected:
                errors.append(f"{path} edit#{i}: count={n} expect={expected}\n---anchor---\n{old[:300]}")
    if errors:
        print("VALIDATION FAILED：")
        for e in errors:
            print(e)
        return 1
    for path, (text, entries) in loaded.items():
        for old, new, _expected in entries:
            text = text.replace(old, new)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"OK {path}: {len(entries)} edit(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
