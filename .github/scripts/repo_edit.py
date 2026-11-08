#!/usr/bin/env python3
"""repo-edit：#6 长按连发 v3（文件级验证：先拉 master 原文再替换，锚点以实测文件为准）。

  E1 连发组件 ApxRepeat（TouchpadActivity 抽离——已落，本次只补遥控键盘）
  E2 遥控键盘媒体键走组件（RemoteKeyboard，404 → 以 master 实测为准）
  E3 编辑键排长按连发（RemoteInputActivity，文件级）
  E4 键盘页编辑键排长按连发（KeyboardPage，文件级）
  E5 音量键注释归属（KeyboardPage）
  E6 触控板注释归属（TouchpadActivity）
  E7 遥控键盘注释归属（RemoteKeyboard）

文件级 = 先 read 原文 → 找唯一锚 → 替换 → write，避免 run #97/104 的覆盖/失配。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"

COMP = r'''
/** 长按连发（issue #52 建议 7）：按住不放 → 400ms 后每 55ms 重复执行 onClick，
 *  松开时**吞掉多出来的那一次 click**（否则长按退格会多删一个字符）。
 *  修饰键不参与（只会反复开关状态，无意义）；repeatFired 当场清（防下次短按被吞）。
 */
fun ApxRepeat() {
    private var repeatOn = false
    private var repeatFired = false
    private var tick: Runnable? = null

    @Suppress("UnusedReceiverParameter")
    private fun tick() {
        if (!repeatOn) return
        repeatFired = true
        onClick()
        android.os.Handler(Looper.getMainLooper()).postDelayed(this, 55L)
    }

    override fun onClick() {
        super.onClick()
        if (repeatFired) { repeatFired = false; return }
        repeatOn = true
        repeatFired = false
        tick?.let { android.os.Handler(Looper.getMainLooper()).postDelayed(it, 400L) }
    }

    fun repeat(onClick: () -> Unit) {
        tick?.let {
            android.os.Handler(Looper.getMainLooper()).removeCallbacks(it)
            tick = null
        }
        repeatOn = false
        repeatFired = false
        tick = object : Runnable {
            override fun run() {
                if (!repeatOn) return
                repeatFired = true
                onClick()
                android.os.Handler(Looper.getMainLooper()).postDelayed(this, 55L)
            }
        }
        android.os.Handler(Looper.getMainLooper()).postDelayed(tick!!, 400L)
    }
}
'''

RE_KB_ANCHOR = '''    private fun onMediaKeyDown(key: Int) {
        if (repeatOn) return
        repeatOn = true
        volumeDown()
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            if (repeatOn) {
                volumeDown()
                handler.postDelayed(it, 150)
            }
        }, 150)
    }'''

RE_KB_NEW = RE_KB_ANCHOR + '''

    @Suppress("UnusedReceiverParameter")
    private fun ApxRepeatTick() {
        if (!repeatOn) return
        repeatFired = true
        volumeDown()
        handler.postDelayed(this, 150)
    }

    private var repeatOn = false
    private var repeatFired = false
    private var tick: Runnable? = null
'''

EDITS = [
    {
        "id": "E1 遥控键盘 ApxRepeat 组件",
        "file": "android/app/src/main/java/com/allperiph/remoteinput/RemoteKeyboard.kt",
        "kind": "replace",
        "anchor": RE_KB_ANCHOR,
        "new": RE_KB_ANCHOR + COMP + '''

    @Suppress("UnusedReceiverParameter")
    private fun ApxRepeatTick() {
        if (!repeatOn) return
        repeatFired = true
        volumeDown()
        handler.postDelayed(this, 150)
    }

    private var repeatOn = false
    private var repeatFired = false
    private var tick: Runnable? = null
''',
    },
    {
        "id": "E2 KeyboardPage KeyButtonLong",
        "file": "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt",
        "kind": "replace",
        "anchor": "                .clickable(onClick = onClick)\n                .padding(12.dp),\n        )\n    }\n\n    // 编辑键排（⌫ / 方向键 / Home/End / PageUp/Down）",
        "new": "                .clickable(onClick = onClick)\n                .padding(12.dp),\n        )\n    }\n\n    // 长按连发版\n    private fun KeyButtonLong(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp)\n                .onLongClick {\n                    startRepeat()\n                    true\n                },\n        )\n    }\n\n    // 编辑键排（⌫ / 方向键 / Home/End / PageUp/Down）"
    },
    {
        "id": "E3 RemoteInputActivity KeyButtonLong",
        "file": "android/app/src/main/java/com/allperiph/controlled/RemoteInputActivity.kt",
        "kind": "replace",
        "anchor": "                .clickable(onClick = onClick)\n                .padding(12.dp),\n        )\n    }\n\n    private fun KeyButtonImpl(...) {",
        "new": "                .clickable(onClick = onClick)\n                .padding(12.dp),\n        )\n    }\n\n    // 长按连发版\n    private fun KeyButtonLong(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp)\n                .onLongClick {\n                    startRepeat()\n                    true\n                },\n        )\n    }\n\n    private fun startRepeat() {\n        tick?.let { handler.removeCallbacks(it); tick = null }\n        repeatOn = false\n        repeatFired = false\n        tick = object : Runnable {\n            override fun run() {\n                if (!repeatOn) return\n                repeatFired = true\n                sendSpecial(mk.first, mk.second)\n                handler.postDelayed(this, 55L)\n            }\n        }\n        handler.postDelayed(tick!!, 400L)\n    }\n\n    private fun stopRepeat() {\n        tick?.let { handler.removeCallbacks(it); tick = null }\n        repeatOn = false\n    }\n\n    private fun KeyButtonImpl(...) {"
    },
    {
        "id": "E4 KeyboardPage 注释归属",
        "file": "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt",
        "kind": "replace",
        "anchor": "        // 按住连发（PC 按位图边沿计数，须反复 按下→释放） -- 音量键长按连发",
        "new": "        // 按住连发（PC 按位图边沿计数，须反复 按下→释放） -- 音量键长按连发（E5 不动）"
    },
    {
        "id": "E5 触控板注释归属",
        "file": "android/app/src/main/java/com/allperiph/touchpad/TouchpadActivity.kt",
        "kind": "replace",
        "anchor": "        // ---- 长按连发（issue #52 建议 7）----",
        "new": "        // ---- 长按连发（issue #52 建议 7）：已抽成 ApxRepeat 组件（E1）----"
    },
    {
        "id": "E6 遥控键盘注释归属",
        "file": "android/app/src/main/java/com/allperiph/remoteinput/RemoteKeyboard.kt",
        "kind": "replace",
        "anchor": "    // ---- 长按连发（issue #52 建议 7）----",
        "new": "    // ---- 长按连发（issue #52 建议 7）：已抽成 ApxRepeat 组件（E2）----"
    },
]


def main() -> int:
    errors = []
    pending = {}
    for e in EDITS:
        path = e["file"]
        try:
            with open(path, encoding="utf-8") as f:
                base = f.read()
        except OSError as ex:
            errors.append("open failed " + path + ": " + str(ex))
            continue
        if e["new"] in base:
            print("already applied, skip: " + e["id"])
            continue
        n = base.count(e["anchor"])
        if n != 1:
            errors.append(
                e["id"] + ": anchor count=" + str(n) + " expect=1 file=" + path
                + "\n---anchor---\n" + e["anchor"][:200]
            )
            continue
        pending[path] = base.replace(e["anchor"], e["new"], 1)

    if errors:
        body = "\n\n".join(errors)
        try:
            with open(ERRLOG, "w", encoding="utf-8") as f:
                f.write(body)
        except OSError as ex:
            print("write errlog failed: " + str(ex))
        print("ERRORS:\n" + body)
        return 1

    try:
        os.remove(ERRLOG)
    except OSError:
        pass

    if not pending:
        print("no changes")
        return 0

    changed = []
    for path, content in pending.items():
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            changed.append(path)
        except OSError as ex:
            print("write failed " + path + ": " + str(ex))
            return 1

    print("OK: " + ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
