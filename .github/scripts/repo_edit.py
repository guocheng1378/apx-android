#!/usr/bin/env python3
"""repo-edit：#6 长按连发 v2（run 13e40dc9 失败重跑：E3/E4 改 KeyButtonImpl 真签名锚点 +
""""
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

EDITS = [
    {
        "id": "E1 连发组件抽离（带接收器类）",
        "file": "android/app/src/main/java/com/allperiph/touchpad/TouchpadActivity.kt",
        "kind": "replace",
        "anchor": "    private var repeatOn = false\n    private var repeatFired = false\n    val tick = object : Runnable {",
        "new": "// ApxRepeat 组件——长按连发（issue #52 建议 7）：400ms 起 55ms 一拍，松开吞尾点击\nApxRepeat() {
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

    private var repeatOn = false\n    private var repeatFired = false\n    val tick = object : Runnable {"
    },
    {
        "id": "E2 遥控键盘走组件",
        "file": "android/app/src/main/java/com/allperiph/remoteinput/RemoteKeyboard.kt",
        "kind": "replace",
        "anchor": "    private fun onMediaKeyDown(key: Int) {\n        if (repeatOn) return\n        repeatOn = true\n        volumeDown()\n        val handler = Handler(Looper.getMainLooper())\n        handler.postDelayed({\n            if (repeatOn) {\n                volumeDown()\n                handler.postDelayed(it, 150)\n            }\n        }, 150)\n    }",
        "new": "    private fun onMediaKeyDown(key: Int) {\n        if (repeatOn) return\n        repeatOn = true\n        volumeDown()\n        val handler = Handler(Looper.getMainLooper())\n        handler.postDelayed({\n            if (repeatOn) {\n                volumeDown()\n                handler.postDelayed(it, 150)\n            }\n        }, 150)\n    }\n\n    @Suppress("UnusedReceiverParameter")\n    private fun ApxRepeatTick() {\n        if (!repeatOn) return\n        repeatFired = true\n        volumeDown()\n        handler.postDelayed(this, 150)\n    }\n\n    private var repeatOn = false\n    private var repeatFired = false\n    private var tick: Runnable? = null\n"""
        + "\n" + COMP + "\n" + "\n" + "    @Suppress(\"UnusedReceiverParameter\")\n    private fun ApxRepeatTick() {\n        if (!repeatOn) return\n        repeatFired = true\n        volumeDown()\n        handler.postDelayed(this, 150)\n    }\n" + "\n" + "    private var repeatOn = false\n    private var repeatFired = false\n    private var tick: Runnable? = null\n"
    },
    {
        "id": "E3 编辑键排长按连发",
        "file": "android/app/src/main/java/com/allperiph/controlled/RemoteInputActivity.kt",
        "kind": "replace",
        "anchor": "                    onClick = onClick\n                },\n        )\n    }\n\n    private fun KeyButtonImpl(...) {",
        "new": "                    onClick = onClick\n                },\n        )\n    }\n\n    // 长按连发版\n    private fun KeyButtonLong(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp)\n                .onLongClick {\n                    startRepeat()\n                    true\n                },\n        )\n    }\n\n    private fun startRepeat() {\n        tick?.let { handler.removeCallbacks(it); tick = null }\n        repeatOn = false\n        repeatFired = false\n        tick = object : Runnable {\n            override fun run() {\n                if (!repeatOn) return\n                repeatFired = true\n                sendSpecial(mk.first, mk.second)\n                handler.postDelayed(this, 55L)\n            }\n        }\n        handler.postDelayed(tick!!, 400L)\n    }\n\n    private fun stopRepeat() {\n        tick?.let { handler.removeCallbacks(it); tick = null }\n        repeatOn = false\n    }\n\n    private fun KeyButtonImpl(...) {"
    },
    {
        "id": "E4 键盘页编辑键排长按连发",
        "file": "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt",
        "kind": "replace",
        "anchor": "                    onClick = onClick\n                },\n        )\n    }\n\n    // 编辑键排（⌫ / 方向键 / Home/End / PageUp/Down）",
        "new": "                    onClick = onClick\n                },\n        )\n    }\n\n    // 长按连发版\n    private fun KeyButtonLong(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp)\n                .onLongClick {\n                    startRepeat()\n                    true\n                },\n        )\n    }\n\n    // 编辑键排（⌫ / 方向键 / Home/End / PageUp/Down）"
    },
    {
        "id": "E5 音量键注释归属",
        "file": "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt",
        "kind": "replace",
        "anchor": "        // 按住连发（PC 按位图边沿计数，须反复 按下→释放） -- 音量键长按连发",
        "new": "        // 按住连发（PC 按位图边沿计数，须反复 按下→释放） -- 音量键长按连发（E5 不动）"
    },
    {
        "id": "E6 触控板注释归属",
        "file": "android/app/src/main/java/com/allperiph/touchpad/TouchpadActivity.kt",
        "kind": "replace",
        "anchor": "        // ---- 长按连发（issue #52 建议 7）----",
        "new": "        // ---- 长按连发（issue #52 建议 7）：已抽成 ApxRepeat 组件（E1）----"
    },
    {
        "id": "E7 遥控键盘注释归属",
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
        base = pending.get(path)
        if base is None:
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
