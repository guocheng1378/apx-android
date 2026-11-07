#!/usr/bin/env python3
"""repo_edit_b12.py —— b15 内容（复用已验证通道：repo-edit-b12.yml）。

b15（2026-11-08）语音输入改道（用户指出 b14 方向错误）：「应该走手机的麦，
手机是输入端；PC/TV 本来就让手机输入，语音再唤起对端助手会重复」——
  · b14 让对端收 0x0203 唤起对端语音助手 = 用对端的麦，与对端自己的语音入口重复；
  · 协议早有现成通道：INPUT_REQUEST/INPUT_TEXT/DONE（0x25/26/27，远程输入体系）
    + TvControllerClient.sendInputText 封装（现成）+ INPUT_FLAG_COMMIT=整段粘贴；
  · b15：遥控页语音键不再发 HID bit9，改道 系统语音识别（手机的麦）→
    INPUT_TEXT(COMMIT) → 对端焦点框。RemoteActivity : Activity 标准
    startActivityForResult 干净无占用（已 grep 实证）。
b14 协议层（描述符 0x0203 / kBitVoice / TvInjector 231）保留为能力储备，UI 解绑
后无人发 bit9，对端不会被重复唤起；断 Wi-Fi 时语音如实提示不可用（文本通道依赖 9511）。
两阶段写盘：全部锚点 count=1 才落盘。
"""
import difflib
import os
import sys

ROOT = os.environ.get("GITHUB_WORKSPACE", os.getcwd())

RA = "android/app/src/main/java/com/allperiph/ui/RemoteActivity.kt"
STR = "android/app/src/main/res/values/strings.xml"

EDITS = [
    (RA, [
        ('''import android.os.Looper
import android.util.AttributeSet''',
         '''import android.os.Looper
import android.speech.RecognizerIntent
import android.util.AttributeSet''', 1),
        ('''import com.allperiph.hid.HidMouse
import com.allperiph.shared.ui.ApKit''',
         '''import com.allperiph.hid.HidMouse
import com.allperiph.shared.proto.ApxFrame
import com.allperiph.shared.ui.ApKit''', 1),
        ('''            getString(R.string.ui_remote_item_voice) to { media(HotkeyController.BIT_VOICE) },''',
         '''            getString(R.string.ui_remote_item_voice) to { startVoiceInput() },''', 1),
        ('''    /** 多媒体键：按下 + 60ms 释放（被控端按位图边沿识别，见 HotkeyController） */
    private fun media(bit: Int) {
        HotkeyController.press(bit)
        handler.postDelayed({ HotkeyController.release(bit) }, 60)
    }''',
         '''    /** 多媒体键：按下 + 60ms 释放（被控端按位图边沿识别，见 HotkeyController） */
    private fun media(bit: Int) {
        HotkeyController.press(bit)
        handler.postDelayed({ HotkeyController.release(bit) }, 60)
    }

    /** b15 语音输入：走手机的麦（手机是输入端）——识别结果经 9511 INPUT_TEXT(COMMIT)
     *  粘进对端焦点框。不发 HID bit9（0x0203 唤起的是对端助手、用对端的麦，会与对端
     *  自己的语音入口重复）。依赖 Wi-Fi 控制链；断链或无识别服务时如实提示。 */
    private fun startVoiceInput() {
        if (ControlTarget.controlClient == null) {
            Toast.makeText(this, getString(R.string.ui_remote_toast_device_connect_not), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.ui_remote_item_voice))
                },
                REQ_VOICE_INPUT,
            )
        }.onFailure {
            Toast.makeText(this, getString(R.string.ui_remote_voice_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE_INPUT || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (text.isEmpty()) return
        val c = ControlTarget.controlClient
        if (c == null) {
            Toast.makeText(this, getString(R.string.ui_remote_toast_device_connect_not), Toast.LENGTH_SHORT).show()
            return
        }
        c.sendInputText(text, ApxFrame.INPUT_FLAG_COMMIT)
    }''', 1),
        ('''        private const val KEY_CURSOR_OVERLAY = "cursor_overlay"''',
         '''        private const val KEY_CURSOR_OVERLAY = "cursor_overlay"
        // b15：语音识别 onActivityResult 请求码（仅本类内部使用）
        private const val REQ_VOICE_INPUT = 0x56''', 1),
    ]),
    (STR, [
        ('''    <string name="ui_remote_item_voice">语音</string>''',
         '''    <string name="ui_remote_item_voice">语音</string>
    <string name="ui_remote_voice_unavailable">语音识别不可用</string>''', 1),
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
