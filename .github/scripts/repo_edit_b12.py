#!/usr/bin/env python3
"""repo-edit-b12：批 12 电视蓝牙兜底（独立脚本 —— 共享 repo_edit.py 正被
U-7/v3-v8 批次占用，结构为 dict 版；本批走 .github/workflows/repo-edit-b12.yml）。

源码复核结论（2026-11-08）：兜底主干**已结构性存在**，本批不改链路代码 ——
  · Uplink.resolve 每帧重算：9511 断链 → controlClient.ready=false → wireless 出局
    → 自动落到 USB → 蓝牙（触控板/鼠标/键盘/多媒体/手柄五点同一判据）；
  · TvControllerClient：3s×8 读空闲判死后 2s 自动重连，回 Wi-Fi 无需人工干预；
  · BtHidDevice.connectBondedHost：HID 注册成功即向已配对主机补连（COMPUTER /
    AUDIO_VIDEO 已含电视）。本批只补四个缺口：
  1. 状态页「蓝牙配对引导」一屏（配对步骤 + 跳系统蓝牙设置 + 能力边界）；
  2. strings.xml 5 条新文案（UI 中文进 strings，不碰 Kotlin 棘轮 643）；
  3. statusText「待 PC 配对」→「待电脑 / 电视配对」（字面量计数不变）；
  4. connectBondedHost 放宽 UNCATEGORIZED（盒子常见上报类；连错设备只是
     无害一条日志，手机 PHONE 类仍排除）；docs 同步 CLIENTS 链路行 + REALDEVICE §9。
两阶段写盘：全部锚点 count 校验通过才落盘，任一失败整批不写。

v2（run #1 失败后修正）：REALDEVICE 锚点第二行实际带 3 空格缩进，v1 写成顶格
导致 count=0（其余 6 处锚点全过，两阶段校验按设计零部分写盘）。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

STR = "android/app/src/main/res/values/strings.xml"
ST = "android/app/src/main/java/com/allperiph/ui/compose/StatusPage.kt"
BT = "android/app/src/main/java/com/allperiph/bt/BtHidDevice.kt"
CL = "docs/CLIENTS.md"
RD = "docs/REALDEVICE-NOTES.md"

EDITS = [
    # —— 1. strings.xml：配对引导 5 条（锚行已 grep count=1）
    (STR, [
        ('''    <string name="ui_remote_target_no_exit">没有可用出口 —— 点「选择设备」连 TV / PC，或插 USB 线 / 配蓝牙 HID</string>''',
         r'''    <string name="ui_remote_target_no_exit">没有可用出口 —— 点「选择设备」连 TV / PC，或插 USB 线 / 配蓝牙 HID</string>
    <string name="ui_bt_pair_guide">蓝牙配对引导</string>
    <string name="ui_bt_pair_title">蓝牙配对电脑 / 电视</string>
    <string name="ui_bt_pair_body">配对在系统设置里完成，App 只需保持蓝牙开启：
1. 对端进入配对：电脑「设置 → 蓝牙」；电视 / 盒子「设置 → 蓝牙与外设 → 添加外设」。
2. 选中「AllPeriph」确认；旧配对先删再配（缓存的是「手机」类型）。
3. 配对后本机自动回连。

配对一次即可。Wi-Fi 断开时键鼠自动回落蓝牙；文件 / 剪贴板等协议功能仍需 Wi-Fi。</string>
    <string name="ui_bt_pair_open_settings">打开系统蓝牙设置</string>
    <string name="ui_bt_pair_open_failed">无法打开蓝牙设置</string>''', 1),
    ]),
    # —— 2/3. StatusPage：状态变量 + 引导按钮与弹窗
    (ST, [
        ('''        var pickerOpen by remember { mutableStateOf(false) }''',
         '''        var pickerOpen by remember { mutableStateOf(false) }
        // 蓝牙配对引导弹窗（2026-11-08 电视蓝牙兜底）：入口在下方受控与出口区块
        var btGuideOpen by remember { mutableStateOf(false) }''', 1),
        ('''                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_pick_device_button),
                    onClick = { pickerOpen = true },
                )
                if (pickerOpen) {''',
         '''                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_pick_device_button),
                    onClick = { pickerOpen = true },
                )
                // 蓝牙配对引导（2026-11-08 电视蓝牙兜底）：配对本身在系统设置完成
                // （手机只注册 HID SDP，发现与配对由对端发起），此前没有任何一屏告诉
                // 用户怎么配 —— 断 Wi-Fi 想回落蓝牙时无从下手。只补引导与能力边界，
                // 择路/回落不动（Uplink.resolve 已结构性支持，本轮源码复核确认）。
                ApComponents.ActionButton(
                    text = ctx.getString(R.string.ui_bt_pair_guide),
                    onClick = { btGuideOpen = true },
                )
                if (btGuideOpen) {
                    ApComponents.DialogCard(onDismiss = { btGuideOpen = false }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = ctx.getString(R.string.ui_bt_pair_title),
                                fontSize = 15.sp,
                            )
                            Text(
                                text = ctx.getString(R.string.ui_bt_pair_body),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            ApComponents.ActionButton(
                                text = ctx.getString(R.string.ui_bt_pair_open_settings),
                                onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                        )
                                    }.onFailure {
                                        Toast.makeText(
                                            ctx,
                                            R.string.ui_bt_pair_open_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                    }
                }
                if (pickerOpen) {''', 1),
    ]),
    # —— 4/5. BtHidDevice：状态文案 + UNCATEGORIZED 放宽
    (BT, [
        ('''        ModuleState.RUNNING -> if (isConnected) "蓝牙 HID 已连接（鼠标/键盘/多媒体/手柄）" else "蓝牙 HID 已注册（待 PC 配对）"''',
         '''        ModuleState.RUNNING -> if (isConnected) "蓝牙 HID 已连接（鼠标/键盘/多媒体/手柄）" else "蓝牙 HID 已注册（待电脑 / 电视配对）"''', 1),
        ('''        val hosts = runCatching { adapter.bondedDevices }.getOrNull().orEmpty().filter { d ->
            val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull() ?: -1
            major == BluetoothClass.Device.Major.COMPUTER ||
                major == BluetoothClass.Device.Major.AUDIO_VIDEO
        }''',
         '''        val hosts = runCatching { adapter.bondedDevices }.getOrNull().orEmpty().filter { d ->
            val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull() ?: -1
            // 2026-11-08 电视兜底：电视 / 盒子不少上报 UNCATEGORIZED（major 查不到类），
            // 一并纳入 —— connect 到非 HID 主机被拒只是无害的一条日志，换盒子的配对回连。
            // 手机（PHONE major）仍排除：手机被控走 9511 + 无障碍，不是 HID 主机。
            major == BluetoothClass.Device.Major.COMPUTER ||
                major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                major == BluetoothClass.Device.Major.UNCATEGORIZED
        }''', 1),
    ]),
    # —— 6. CLIENTS.md 蓝牙链路行
    (CL, [
        ('''| **蓝牙** | 手机 → 电脑（HID） | ✅ | `BtHidDevice` 蓝牙 HID；另有 v217 起的**物理蓝牙键盘 → 电脑转发桥**（手机连的实体键盘打字直发电脑）；键盘/手柄全量补全见 ROADMAP 已知限制 |''',
         '''| **蓝牙** | 手机 → 电脑 / 电视（HID） | ✅ | `BtHidDevice` 蓝牙 HID；电视 / 盒子配对后可作**输入回落出口**（键鼠经系统级 HID 注入，入口见状态页「蓝牙配对引导」）；文件 / 剪贴板等协议功能仍需 Wi-Fi；另有 v217 起的**物理蓝牙键盘 → 电脑转发桥**（手机连的实体键盘打字直发电脑）；真机配对实测见 REALDEVICE-NOTES §9 |''', 1),
    ]),
    # —— 7. REALDEVICE-NOTES §9 补电视配对实测项（v2：第二行 3 空格缩进实证修正）
    (RD, [
        ('''8. 每项记录：✅/❌ + 现象 + logcat 片段，回填本节；全 ✅ 后才能把 ROADMAP §2.1
   里的「真机未实测」字样去掉。''',
         '''8. 每项记录：✅/❌ + 现象 + logcat 片段，回填本节；全 ✅ 后才能把 ROADMAP §2.1
   里的「真机未实测」字样去掉。
9. **电视配对与回落**（2026-11-08 新增）：与 Android TV / 盒子配对（状态页「蓝牙配对
   引导」有步骤），断开 Wi-Fi 后 TV 遥控页方向键 / 确认 / 触摸条应经蓝牙可用，
   重连 Wi-Fi 后应在 2s 内自动切回 9511。记录电视型号与其 BluetoothClass major
   （`dumpsys bluetooth_manager`）—— `connectBondedHost` 已放宽到 UNCATEGORIZED
   （2026-11-08），但盒子实际上报类别未经实测确认。''', 1),
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
