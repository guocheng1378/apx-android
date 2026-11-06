#!/usr/bin/env python3
"""repo-edit：单文件锚点替换（两阶段：先全量校验命中次数，全过才写盘）。

批 6（2026-11-06）：蓝牙补全收口 —— 源码复核四点发送全落地，欠账只剩真机配对。
  · ROADMAP §2.1 过期头「目前已有鼠标/多媒体，待补：」→ 2026-11-06 复核结论
    + 剩余两条欠账（真机配对清单指针、手柄 TCP 出口归阶段二）
  · REALDEVICE-NOTES 新增 §9 蓝牙真机配对实测清单（8 步，行为可观测）
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

EDITS = [
    ("docs/ROADMAP.md", [
        ('''`bt/BtHidDevice` 目前已有 **鼠标**（`reportMouse`，rid 1）与 **多媒体键**（`reportConsumer`，rid 3）。
待补：''',
         '''**2026-11-06 源码复核**：四类报告（鼠标 rid1 / 多媒体 rid3 / 键盘 rid2 / 手柄 rid4）与
四点发送接入全部落地，择路判据唯一 = `core/Uplink.resolve`（无线 → USB → 蓝牙 → NONE）。
下表保留 2026-10-23 的核对明细。**剩余欠账两条**：① 真机配对实测（按
[REALDEVICE-NOTES §9](./REALDEVICE-NOTES.md) 清单执行）；② 手柄 TCP 出口（ViGEmBus
第三方驱动，归入架构 §2.2「阶段二 借力第三方」，非本轮）。''', 1),
    ]),
    ("docs/REALDEVICE-NOTES.md", [
        ('''8. **卸载时绝不删除 gadget 本体**，只解绑 UDC（§3.1 对策）。删了就再也建不回来，
   只能重启手机。''',
         '''8. **卸载时绝不删除 gadget 本体**，只解绑 UDC（§3.1 对策）。删了就再也建不回来，
   只能重启手机。

## 9. 蓝牙真机配对实测清单（2026-11-06——代码收口，欠的只剩这步）

2026-11-06 源码复核结论：键盘（`hid/HidKeys.send`，真值 `bt?.isConnected` + BLUETOOTH
分支）、手柄（`ui/GamepadController.send` 蓝牙优先 `reportGamepad`）、多媒体键
（`ui/HotkeyController`，Consumer TLC）、鼠标（`touchpad/TouchpadModule.dispatch`，
USB 停用自动回退蓝牙）四点全部经 `core/Uplink.resolve` 择路（无线 → USB → 蓝牙 →
NONE，判据唯一）；native 蓝牙描述符四 TLC + Report ID 声明齐（v186）。**代码不再欠账，
以下为必须按序执行的物理验证（PC 需带蓝牙适配器）：**

1. **配对**：手机 设置 → 蓝牙 → 与 PC 配对；`adb logcat` 确认 `BtHidDevice.registerApp`
   成功且取到 native 描述符（`ApxNative.btReportDescriptor()` 非空）。
2. **状态真值**：界面蓝牙状态应显示已连接；若写死/不刷新，按 §6 口径记为未解决项。
3. **键盘**：PC 打开文本编辑器输入字符；**重点测组合键 Ctrl+C / Ctrl+V / Alt+Tab**——
   历史 bug（写死 `btConnected=false`）正是让组合键发不出去。
4. **鼠标/触控板**：光标移动、左/右键、滚轮；随后在设置里**停用 USB 触摸板**，
   验证自动回退蓝牙仍可用（`TouchpadModule` 的回落逻辑）。
5. **多媒体键**：音量加减、播放/暂停 → PC 实时响应。
6. **手柄**：`GamepadController` 发出 → PC 蓝牙侧识别为手柄并有按键/摇杆响应。
   注意：Windows 蓝牙直连**不需要 ViGEmBus**——那只为 TCP 出口服务（归阶段二）。
7. **断开诚实性**：关掉 PC 蓝牙后，四点均应转入 `Uplink.NONE` 的降级文案，
   不得静默失败（硬性原则：不可用则如实标注）。
8. 每项记录：✅/❌ + 现象 + logcat 片段，回填本节；全 ✅ 后才能把 ROADMAP §2.1
   里的「真机未实测」字样去掉。''', 1),
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
