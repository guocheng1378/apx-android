#!/usr/bin/env python3
"""repo_edit_b12.py —— b14 内容（复用已验证通道：repo-edit-b12.yml）。

b14（2026-11-08）语音键：用户要「蓝牙控制带语音键」。链路复核结论：
  · Consumer 位图 9511 帧是 u16，任意位随时可带，缺的只是**各端对 bit9 的认领**；
  · bit7/8 已被 zoom（v1.7c）占用，语音用 bit9；
  · **描述符两处**（USB buildTlcConsumer / BT buildBtTlcConsumer）目前 bit7..15 全是
    padding —— zoom 从不进 HID 描述符（只走 9511 受控翻译），语音要进描述符才
    能被对端 HID 栈识别，故 padBits(9) 切成 padBits(2)+usage+padBits(6)，报告
    总长 16bit+1B 不变（非 breaking）；
  · 受控端唯一落点 = TvInjector.consumer（shared ControlServer 的重复注入圈已删），
    TV/手机被控一份改动两端生效；TvControlServer（legacy）的 CONSUMER_MAP 界面
    分发同步补 9→231 保持一致；
  · UI：遥控页功能行（返回/Home/菜单）加第 4 键「语音」——addRow 等权重自适应，
    4 键安全；label 走 strings.xml（中文棘轮 643 不动）。
能力边界：语音键只是「唤起」，音频采集走对端本机；PC 受控端无对应 VK 忽略 bit9；
被控端注入需 evdev/root 通道（无障碍无 231 分支，如实打日志）。
两阶段写盘：全部锚点 count 命中才落盘（含 hid_descriptor.cpp 双处 want=2）。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

DH = "shared/include/apx/hid_descriptor.h"
LH = "shared/include/apx/hid_layout.h"
LC = "shared/src/hid_layout.cpp"
DC = "shared/src/hid_descriptor.cpp"
HK = "android/app/src/main/java/com/allperiph/ui/HotkeyController.kt"
TI = "android/shared/src/main/java/com/allperiph/shared/inject/TvInjector.kt"
TCS = "android/app/src/main/java/com/allperiph/controlled/TvControlServer.kt"
RA = "android/app/src/main/java/com/allperiph/ui/RemoteActivity.kt"
STR = "android/app/src/main/res/values/strings.xml"

EDITS = [
    # —— 1. hid_descriptor.h：Consumer Usage 枚举 + Voice Command
    (DH, [
        ('''    kUsageZoomIn          = 0x0227,
    kUsageZoomOut         = 0x0228,
};''',
         '''    kUsageZoomIn          = 0x0227,
    kUsageZoomOut         = 0x0228,
    kUsageVoiceCommand    = 0x0203,  // b14 语音键（HID Consumer Page 标准 usage）
};''', 1),
    ]),
    # —— 2. hid_layout.h：位图枚举 + kBitVoice = 9
    (LH, [
        ('''    kBitZoomIn     = 7,  // 0x0C:0x0227（v1.7c 捏合放大）
    kBitZoomOut    = 8,  // 0x0C:0x0228（v1.7c 捏合缩小）
};''',
         '''    kBitZoomIn     = 7,  // 0x0C:0x0227（v1.7c 捏合放大）
    kBitZoomOut    = 8,  // 0x0C:0x0228（v1.7c 捏合缩小）
    kBitVoice      = 9,  // 0x0C:0x0203（b14 语音键，进 HID 描述符）
};''', 1),
    ]),
    # —— 3. hid_layout.cpp：位→usage 单点映射
    (LC, [
        ('''        case kBitZoomIn:     return kUsageZoomIn;    // v1.7c
        case kBitZoomOut:    return kUsageZoomOut;   // v1.7c
        default:             return 0;''',
         '''        case kBitZoomIn:     return kUsageZoomIn;    // v1.7c
        case kBitZoomOut:    return kUsageZoomOut;   // v1.7c
        case kBitVoice:      return kUsageVoiceCommand;  // b14
        default:             return 0;''', 1),
    ]),
    # —— 4. hid_descriptor.cpp：USB / BT 两处 Consumer TLC 同改（want=2）
    (DC, [
        ('''    b.usage(kUsageScanNextTrack); b.input(kDataVar);
    b.padBits(9); b.padBytes(1);''',
         '''    b.usage(kUsageScanNextTrack); b.input(kDataVar);
    // b14 语音键：bit9 = Voice Command(0x0203)。bit7/8（zoom）维持 padding —— v1.7c
    // 起 zoom 只走 9511 受控端翻译，不进 HID 描述符（勿动）。报告总长 16bit+1B 不变。
    b.padBits(2);
    b.usage(kUsageVoiceCommand); b.input(kDataVar);
    b.padBits(6);
    b.padBytes(1);''', 2),
    ]),
    # —— 5. HotkeyController：BIT_VOICE + KDoc 位定义
    (HK, [
        (''' *   bit0=音量+ bit1=音量- bit2=静音 bit3=电源 bit4=播放/暂停 bit5=上一曲 bit6=下一曲''',
         ''' *   bit0=音量+ bit1=音量- bit2=静音 bit3=电源 bit4=播放/暂停 bit5=上一曲 bit6=下一曲
 *   bit9=语音（b14，0x0203 Voice Command；bit7/8 为 zoom 已占用，见 hid_layout.h）''', 1),
        ('''    const val BIT_NEXT_TRACK = 6''',
         '''    const val BIT_NEXT_TRACK = 6
    const val BIT_VOICE = 9        // b14：Consumer 0x0203 Voice Command''', 1),
    ]),
    # —— 6. TvInjector.consumer：bit9 → KEYCODE_VOICE_ASSIST（TV/手机被控共用）
    (TI, [
        ('''        if (bitmap and (1 shl 6) != 0) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }''',
         '''        if (bitmap and (1 shl 6) != 0) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        // b14 语音键（bit9 → KEYCODE_VOICE_ASSIST 231）：对端（TV/手机）按系统默认路由
        // 拉起语音助手。与 bit3 电源同款：置位帧发一次 down+up（释放帧位清零不触发）。
        // 注入需 evdev/root 通道；无障碍无 231 分支会走 else 打日志（如实降级不装死）。
        if (bitmap and (1 shl 9) != 0) {
            key(KeyEvent.KEYCODE_VOICE_ASSIST, true)
            key(KeyEvent.KEYCODE_VOICE_ASSIST, false)
        }
    }''', 1),
    ]),
    # —— 7. TvControlServer（legacy）：界面分发表同步
    (TCS, [
        ('''        private val CONSUMER_MAP: Map<Int, Int> = mapOf(0 to 24, 1 to 25, 2 to 164, 3 to 26, 4 to 85, 5 to 88, 6 to 87)''',
         '''        private val CONSUMER_MAP: Map<Int, Int> = mapOf(0 to 24, 1 to 25, 2 to 164, 3 to 26, 4 to 85, 5 to 88, 6 to 87, 9 to 231)''', 1),
    ]),
    # —— 8. RemoteActivity：功能行加语音键（返回/Home/菜单/语音）
    (RA, [
        ('''            getString(R.string.ui_remote_item_menu) to { HidKeys.tap(U_MENU) },
        ),''',
         '''            getString(R.string.ui_remote_item_menu) to { HidKeys.tap(U_MENU) },
            getString(R.string.ui_remote_item_voice) to { media(HotkeyController.BIT_VOICE) },
        ),''', 1),
    ]),
    # —— 9. strings.xml：语音键 label（XML 不计中文棘轮）
    (STR, [
        ('''    <string name="ui_remote_item_menu">菜单</string>''',
         '''    <string name="ui_remote_item_menu">菜单</string>
    <string name="ui_remote_item_voice">语音</string>''', 1),
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
