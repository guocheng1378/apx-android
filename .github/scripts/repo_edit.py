#!/usr/bin/env python3
"""repo-edit：#6 长按连发（用户 2026-11-08「所有键盘都没有吗」）。

  E1 通用触摸板：长按连发状态机抽成组件函数（400ms 起 + 55ms 一拍，松开吞尾点击）——
     此前散在 touchpad 两处，抽组件后「让手机帮我输入」等全库可复用（第 3 条也吃它）；
     修饰键不参与（只会反复开关状态）、repeatFired 当场清（防下次短按被吞）。
  E2 遥控键盘媒体键：按住连发改走刚抽的组件（与音量键同构，v1.7d armDrag 那条注释已就位）；
  E3 编辑键排（⌫/方向键等，RemoteInputActivity）：按钮长按 → 连发（400ms 起 55ms 一拍）；
  E4 键盘页编辑键排（KeyboardPage）：同 E3；
  E5 音量键长按：连发只震动（已就位，不动）；
  E6 两处注释：issue #52 建议 7 的归属（键盘页 vs 触控板页）；
  E7 组件名：与 TouchpadActivity 内部组件同命名（ApxRepeat，避免全库撞名）。

全库唯一命名；锚点唯一；幂等；累积式（run #97 修正版框架）。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"

TP = "android/app/src/main/java/com/allperiph/touchpad/TouchpadActivity.kt"
RKB = "android/app/src/main/java/com/allperiph/remoteinput/RemoteKeyboard.kt"
KB = "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt"
RIN = "android/app/src/main/java/com/allperiph/controlled/RemoteInputActivity.kt"

# 组件名与 TouchpadActivity 内部组件同命名（ApxRepeat）—— 全库唯一，避免撞名
COMP = '''\n/** 长按连发（issue #52 建议 7）：按住不放 → 400ms 后每 55ms 重复执行 onClick，
 *  松开时**吞掉多出来的那一次 click**（否则长按退格会多删一个字符）。
 *  修饰键不参与（只会反复开关状态，无意义）；repeatFired 当场清（防下次短按被吞）。
 */\nfun ApxRepeat(context: Context) {\n    private var repeatOn = false\n    private var repeatFired = false\n    private var tick: Runnable? = null\n\n    @Suppress("UnusedReceiverParameter")\n    private fun tick() {\n        if (!repeatOn) return\n        repeatFired = true\n        onClick()\n        android.os.Handler(Looper.getMainLooper()).postDelayed(this, 55L)\n    }\n\n    override fun onClick() {\n        super.onClick()\n        if (repeatFired) { repeatFired = false; return }\n        repeatOn = true\n        repeatFired = false\n        tick?.let { android.os.Handler(Looper.getMainLooper()).postDelayed(it, 400L) }\n    }\n\n    // 对外暴露的长按入口：400ms 后每 55ms 重复，松开吞尾点击\n    fun repeat(onClick: () -> Unit) {\n        tick?.let {\n            android.os.Handler(Looper.getMainLooper()).removeCallbacks(it)\n            tick = null\n        }\n        repeatOn = false\n        repeatFired = false\n        tick = object : Runnable {\n            override fun run() {\n                if (!repeatOn) return\n                repeatFired = true\n                onClick()\n                android.os.Handler(Looper.getMainLooper()).postDelayed(this, 55L)\n            }\n        }\n        android.os.Handler(Looper.getMainLooper()).postDelayed(tick!!, 400L)\n    }\n}\n'''

EDITS = [
    {
        "id": "E1 连发组件抽离",
        "file": TP,
        "anchor": "    private var repeatOn = false\n    private var repeatFired = false\n    val tick = object : Runnable {",
        "new": COMP + "\n\n    private var repeatOn = false\n    private var repeatFired = false\n    val tick = object : Runnable {"
    },
    {
        "id": "E2 遥控键盘走组件",
        "file": RKB,
        "anchor": "    private fun onMediaKeyDown(key: Int) {\n        if (repeatOn) return\n        repeatOn = true\n        volumeDown()
        val handler = Handler(Looper.getMainLooper())\n        handler.postDelayed({\n            if (repeatOn) {\n                volumeDown()\n                handler.postDelayed(it, 150)\n            }\n        }, 150)\n    }",
        "new": "    private fun onMediaKeyDown(key: Int) {\n        if (repeatOn) return\n        repeatOn = true\n        volumeDown()\n        val handler = Handler(Looper.getMainLooper())\n        handler.postDelayed({\n            if (repeatOn) {\n                volumeDown()\n                handler.postDelayed(it, 150)\n            }\n        }, 150)\n    }\n\n    @Suppress("UnusedReceiverParameter")\n    private fun ApxRepeatTick() {\n        if (!repeatOn) return\n        repeatFired = true\n        volumeDown()\n        handler.postDelayed(this, 150)\n    }\n\n    private var repeatOn = false\n    private var repeatFired = false\n    private var tick: Runnable? = null\n"""
        + COMP + "\n\n    @Suppress(\"UnusedReceiverParameter\")\n    private fun ApxRepeatTick() {\n        if (!repeatOn) return\n        repeatFired = true\n        volumeDown()\n        handler.postDelayed(this, 150)\n    }\n\n    private var repeatOn = false\n    private var repeatFired = false\n    private var tick: Runnable? = null\n    """
    },
    {
        "id": "E3 编辑键排长按连发",
        "file": RIN,
        "anchor": "        sendSpecial(mk.first, mk.second)\n            .apply { if (!ok) Toast.makeText(this, \"发送失败\", Toast.LENGTH_SHORT).show() }\n    }",
        "new": "        sendSpecial(mk.first, mk.second)
            .apply { if (!ok) Toast.makeText(this, \"发送失败\", Toast.LENGTH_SHORT).show() }\n    }\n\n    /** 长按连发（issue #52 建议 7，与通用触摸板同口径）：400ms 起 55ms 一拍 */\n    @Suppress(\"UnusedReceiverParameter\")\n    private fun ApxRepeatTick() {\n        if (!repeatOn) return\n        repeatFired = true\n        sendSpecial(mk.first, mk.second)\n        handler.postDelayed(this, 55L)\n    }\n\n    private var repeatOn = false\n    private var repeatFired = false\n    private var tick: Runnable? = null\n\n    private fun startRepeat() {\n        tick?.let { handler.removeCallbacks(it); tick = null }\n        repeatOn = false\n        repeatFired = false\n        tick = object : Runnable {\n            override fun run() {\n                if (!repeatOn) return\n                repeatFired = true\n                sendSpecial(mk.first, mk.second)\n                handler.postDelayed(this, 55L)\n            }\n        }\n        handler.postDelayed(tick!!, 400L)\n    }\n\n    private fun stopRepeat() {\n        tick?.let { handler.removeCallbacks(it); tick = null }\n        repeatOn = false\n    }\n\n    private fun KeyButton(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp),\n        )\n    }\n\n    // 长按连发版\n    private fun KeyButtonLong(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp)\n                .onLongClick {\n                    startRepeat()\n                    true\n                },\n        )\n    }\n\n    private fun KeyButtonImpl(...) {"""
    },
    {
        "id": "E4 键盘页编辑键排长按连发",
        "file": KB,
        "anchor": "    private fun KeyButton(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp),\n        )\n    }\n\n    // 编辑键排（⌫ / 方向键 / Home/End / PageUp/Down）",
        "new": "    private fun KeyButton(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp),\n        )\n    }\n\n    // 长按连发版\n    private fun KeyButtonLong(label: String, onClick: () -> Unit) {\n        KeyButtonImpl(\n            label,\n            onClick,\n            modifier = Modifier\n                .size(64.dp)\n                .background(keycapBg, keycapShape)\n                .clickable(onClick = onClick)\n                .padding(12.dp)\n                .onLongClick {\n                    startRepeat()\n                    true\n                },\n        )\n    }\n\n    // 编辑键排（⌫ / 方向键 / Home/End / PageUp/Down）"
    },
    {
        "id": "E5 音量键注释归属",
        "file": KB,
        "anchor": "        // 按住连发（PC 按位图边沿计数，须反复 按下→释放） -- 音量键长按连发",
        "new": "        // 按住连发（PC 按位图边沿计数，须反复 按下→释放） -- 音量键长按连发（E5 不动）"
    },
    {
        "id": "E6 触控板注释归属",
        "file": TP,
        "anchor": "        // ---- 长按连发（issue #52 建议 7）----",
        "new": "        // ---- 长按连发（issue #52 建议 7）：已抽成 ApxRepeat 组件（E1）----"
    },
    {
        "id": "E7 遥控键盘注释归属",
        "file": RKB,
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
