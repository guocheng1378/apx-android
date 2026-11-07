#!/usr/bin/env python3
"""repo_edit_b12.py —— b13 内容（复用 b12 通道：脚本与工作流 repo-edit-b12.yml
已验证可跑，b13 不另建工作流；本文件按仓内惯例按批次整体替换 EDITS）。

b13（2026-11-08）手机控手机蓝牙兜底：b12 电视版落地且 Build #1368 全绿后，
用户指出手机同样有蓝牙开关——复核确认与电视同一条机制（Android 手机系统级
HID 主机能力与 TV 同源），b13 补齐：
  1. connectBondedHost 纳入 PHONE 类（b12 写死排除的反转；注释同步改，
     原注释“不往另一部手机上乱连”已与代码矛盾）；
  2. statusText / hosts 日志 / KDoc → “电脑 / 电视 / 手机”（字面量计数不变，
     中文棘轮 643 不动）；
  3. 配对引导 title/body 加手机步骤 + 亮屏解锁能力边界（XML 不计数）；
  4. CLIENTS.md 蓝牙行与手机互控行；REALDEVICE §9 补第 10 条手机配对实测。
能力边界不变：蓝牙只承载输入回落，协议功能（剪贴板/文件/浮层）仍需 Wi-Fi；
锁屏会吞 HID 输入，被控手机需亮屏解锁。
两阶段写盘：全部锚点 count=1 才落盘，任一失败整批不写（b12 run#1 已实战验证）。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

STR = "android/app/src/main/res/values/strings.xml"
BT = "android/app/src/main/java/com/allperiph/bt/BtHidDevice.kt"
CL = "docs/CLIENTS.md"
RD = "docs/REALDEVICE-NOTES.md"

EDITS = [
    # —— 1. BtHidDevice：状态文案 / 过滤反转 / KDoc / 空列表日志（4 锚点）
    (BT, [
        ('''        ModuleState.RUNNING -> if (isConnected) "蓝牙 HID 已连接（鼠标/键盘/多媒体/手柄）" else "蓝牙 HID 已注册（待电脑 / 电视配对）"''',
         '''        ModuleState.RUNNING -> if (isConnected) "蓝牙 HID 已连接（鼠标/键盘/多媒体/手柄）" else "蓝牙 HID 已注册（待电脑 / 电视 / 手机配对）"''', 1),
        ('''            // 2026-11-08 电视兜底：电视 / 盒子不少上报 UNCATEGORIZED（major 查不到类），
            // 一并纳入 —— connect 到非 HID 主机被拒只是无害的一条日志，换盒子的配对回连。
            // 手机（PHONE major）仍排除：手机被控走 9511 + 无障碍，不是 HID 主机。
            major == BluetoothClass.Device.Major.COMPUTER ||
                major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                major == BluetoothClass.Device.Major.UNCATEGORIZED
        }''',
         '''            // 2026-11-08 电视兜底：电视 / 盒子不少上报 UNCATEGORIZED（major 查不到类），
            // 一并纳入 —— connect 到非 HID 主机被拒只是无害的一条日志，换盒子的配对回连。
            // 2026-11-08 手机控手机（b13）：PHONE 类同样纳入 —— Android 手机的系统级 HID
            // 主机能力与 TV 同源，被控机亮屏解锁即可收键鼠。锁屏会吞输入，那是能力边界
            // 不是代码障碍；9511 协议通道仍是手机互控正道，蓝牙只作断 Wi-Fi 的输入兜底。
            major == BluetoothClass.Device.Major.COMPUTER ||
                major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                major == BluetoothClass.Device.Major.UNCATEGORIZED ||
                major == BluetoothClass.Device.Major.PHONE
        }''', 1),
        ('''     * 向「已配对的电脑 / 电视」自动补一次 HID 连接（注册成功后、蓝牙重新打开后各调一次）。''',
         '''     * 向「已配对的电脑 / 电视 / 手机」自动补一次 HID 连接（注册成功后、蓝牙重新打开后各调一次）。''', 1),
        ('''     * 只认 majorDeviceClass = COMPUTER 或 AUDIO_VIDEO：不往耳机 / 车机 / 另一部手机上乱连。''',
         '''     * 认 COMPUTER / AUDIO_VIDEO / UNCATEGORIZED / PHONE（b13 起 PHONE 纳入：手机互控的
     * 断 Wi-Fi 输入兜底）；不往耳机 / 车机上连 —— 连错设备只是一条被拒日志。''', 1),
        ('''            Log.i(TAG, "没有已配对的电脑/电视主机；若主机只显示「手机」，说明旧配对早于 HID 注册，需删掉重配")''',
         '''            Log.i(TAG, "没有已配对的电脑 / 电视 / 手机主机；若主机只显示「手机」，说明旧配对早于 HID 注册，需删掉重配")''', 1),
    ]),
    # —— 2. strings.xml：引导 title/body 加手机步骤与亮屏边界（3 锚点，XML 不计数）
    (STR, [
        ('''    <string name="ui_bt_pair_title">蓝牙配对电脑 / 电视</string>''',
         '''    <string name="ui_bt_pair_title">蓝牙配对电脑 / 电视 / 手机</string>''', 1),
        ('''1. 对端进入配对：电脑「设置 → 蓝牙」；电视 / 盒子「设置 → 蓝牙与外设 → 添加外设」。''',
         '''1. 对端进入配对：电脑「设置 → 蓝牙」；电视 / 盒子「设置 → 蓝牙与外设 → 添加外设」；手机「设置 → 蓝牙 → 配对新设备」。''', 1),
        ('''3. 配对后本机自动回连。''',
         '''3. 配对后本机自动回连。被控手机需亮屏解锁（锁屏会吞掉 HID 输入）。''', 1),
    ]),
    # —— 3. CLIENTS.md：蓝牙链路行 + 手机互控行（2 锚点）
    (CL, [
        ('''| **蓝牙** | 手机 → 电脑 / 电视（HID） | ✅ | `BtHidDevice` 蓝牙 HID；电视 / 盒子配对后可作**输入回落出口**（键鼠经系统级 HID 注入，入口见状态页「蓝牙配对引导」）；文件 / 剪贴板等协议功能仍需 Wi-Fi；另有 v217 起的**物理蓝牙键盘 → 电脑转发桥**（手机连的实体键盘打字直发电脑）；真机配对实测见 REALDEVICE-NOTES §9 |''',
         '''| **蓝牙** | 手机 → 电脑 / 电视 / 另一台手机（HID） | ✅ | `BtHidDevice` 蓝牙 HID；电视 / 盒子 / 被控手机配对后可作**输入回落出口**（键鼠经系统级 HID 注入，入口见状态页「蓝牙配对引导」；被控手机须亮屏解锁，锁屏会吞输入）；文件 / 剪贴板等协议功能仍需 Wi-Fi；另有 v217 起的**物理蓝牙键盘 → 电脑转发桥**（手机连的实体键盘打字直发电脑）；真机配对实测见 REALDEVICE-NOTES §9 |''', 1),
        ('''| 📱 ↔ 📱 另一台手机 | 同 `app.apk`，9511 互控 | **互控** | 两台手机双向实时控制 + 文件互传 | 同一局域网，双方都要在线 |''',
         '''| 📱 ↔ 📱 另一台手机 | 同 `app.apk`，9511 互控 | **互控** | 两台手机双向实时控制 + 文件互传 | 同一局域网，双方都要在线；断 Wi-Fi 时键鼠可蓝牙兜底（b13，被控机需亮屏解锁） |''', 1),
    ]),
    # —— 4. REALDEVICE §9：补第 10 条手机配对实测（1 锚点；第二行 3 空格缩进——v2 教训）
    (RD, [
        ('''   （`dumpsys bluetooth_manager`）—— `connectBondedHost` 已放宽到 UNCATEGORIZED
   （2026-11-08），但盒子实际上报类别未经实测确认。''',
         '''   （`dumpsys bluetooth_manager`）—— `connectBondedHost` 已放宽到 UNCATEGORIZED
   （2026-11-08），但盒子实际上报类别未经实测确认。
10. **手机配对与回落**（2026-11-08 b13 新增）：两台手机互配（PHONE 类已纳入
    `connectBondedHost`），被控手机亮屏解锁，断开 Wi-Fi 后主控触控板 / 键盘应经蓝牙
    进被控机系统输入；锁屏状态记录行为（预期被吞 —— 能力边界）。9511 重连后 2s 内
    应自动切回 Wi-Fi。记录被控机 BluetoothClass major（`dumpsys bluetooth_manager`）
    确认上报为 PHONE（0x0200）。''', 1),
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
