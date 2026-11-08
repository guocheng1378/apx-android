#!/usr/bin/env python3
"""repo-edit：#3 「让手机帮我输入」触控板接通用触摸板逻辑（用户 2026-11-08）。

通用 touchpad.kt 有、RIN 内嵌触控板缺的两块：
  · 双指长按 550ms = 右键拖拽（rightDragTask 同口径）
  · 双指点按 = 右键单击（右键按钮帧 0x02 下+抬）
另外把长按左拖 400ms 对齐通用的 550ms（v1.7d 口径）。
RIN 原有：单指移动/轻点左键/长按左键拖拽 —— 保留。

锚点全部来自 1cf6f806 实读 255-329 行；幂等；累积式。全 6 锚在 buildTouchpad 单函数内。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"
RIN = "android/app/src/main/java/com/allperiph/controlled/RemoteInputActivity.kt"

EDITS = [
    {
        "id": "N1 右键拖拽 arm + 状态",
        "file": RIN,
        "anchor": (
            "        val dragArm = Runnable {\n"
            "            if (tpDown && !tpDrag) { tpDrag = true; mouseFrame(0x01, 0, 0) }\n"
            "        }"
        ),
        "new": (
            "        val dragArm = Runnable {\n"
            "            if (tpDown && !tpDrag) { tpDrag = true; mouseFrame(0x01, 0, 0) }\n"
            "        }\n"
            "        // #3（用户 2026-11-08「触摸板没用通用触摸板的逻辑」）：与通用 touchpad.kt 同口径 ——\n"
            "        // 双指落下 550ms 锁定右键拖拽（rightDragTask）；期间随手指拖，帧按钮位 0x02。\n"
            "        var tpSecond = false; var tpSecondAt = 0L; var tpRightDrag = false\n"
            "        val rightDragArm = Runnable {\n"
            "            if (tpSecond && !tpRightDrag) { tpRightDrag = true; mouseFrame(0x02, 0, 0) }\n"
            "        }"
        ),
    },
    {
        "id": "N2 DOWN 550ms + 双指分支",
        "file": RIN,
        "anchor": (
            "                android.view.MotionEvent.ACTION_DOWN -> {\n"
            "                    tpDown = true; tpDrag = false; tpDownAt = android.os.SystemClock.uptimeMillis()\n"
            "                    tpLastX = e.x; tpLastY = e.y\n"
            "                    v.removeCallbacks(dragArm); v.postDelayed(dragArm, 400)\n"
            "                    true\n"
            "                }"
        ),
        "new": (
            "                android.view.MotionEvent.ACTION_DOWN -> {\n"
            "                    tpDown = true; tpDrag = false; tpDownAt = android.os.SystemClock.uptimeMillis()\n"
            "                    tpLastX = e.x; tpLastY = e.y\n"
            "                    v.removeCallbacks(dragArm); v.postDelayed(dragArm, 550)   // #3 对齐通用 550ms\n"
            "                    true\n"
            "                }\n"
            "                android.view.MotionEvent.ACTION_POINTER_DOWN ->\n"
            "                    // #3 第二指落下：双指长按 550ms = 右键拖拽（通用 rightDragTask 同口径）\n"
            "                    if (e.pointerCount == 2) {\n"
            "                        tpSecond = true; tpSecondAt = android.os.SystemClock.uptimeMillis()\n"
            "                        v.removeCallbacks(dragArm); tpDrag = false\n"
            "                        v.postDelayed(rightDragArm, 550)\n"
            "                        true\n"
            "                    } else false\n"
            "                android.view.MotionEvent.ACTION_POINTER_UP ->\n"
            "                    if (tpSecond) {\n"
            "                        tpSecond = false\n"
            "                        v.removeCallbacks(rightDragArm)\n"
            "                        val dur = android.os.SystemClock.uptimeMillis() - tpSecondAt\n"
            "                        if (!tpRightDrag) {\n"
            "                            if (dur < 300) {   // 双指点按 = 右键单击（短按未拖成）\n"
            "                                mouseFrame(0x02, 0, 0); mouseFrame(0x00, 0, 0)\n"
            "                                com.allperiph.ui.Feedback.haptic(v)\n"
            "                            }\n"
            "                        } else {\n"
            "                            mouseFrame(0x00, 0, 0)   // 右键拖拽结束：松开右键\n"
            "                            tpRightDrag = false\n"
            "                        }\n"
            "                        tpLastX = e.x; tpLastY = e.y   // 单指继续：基点归位防跳变\n"
            "                        true\n"
            "                    } else false"
        ),
    },
    {
        "id": "N3 MOVE 按钮位跟拖拽态",
        "file": RIN,
        "anchor": "                        mouseFrame(if (tpDrag) 0x01 else 0x00, sx, sy)",
        "new": "                        mouseFrame(when { tpRightDrag -> 0x02; tpDrag -> 0x01; else -> 0x00 }, sx, sy)",
    },
    {
        "id": "N4a UP 取消右键 arm",
        "file": RIN,
        "anchor": (
            "                    val cancelled = e.actionMasked == android.view.MotionEvent.ACTION_CANCEL\n"
            "                    v.removeCallbacks(dragArm)"
        ),
        "new": (
            "                    val cancelled = e.actionMasked == android.view.MotionEvent.ACTION_CANCEL\n"
            "                    v.removeCallbacks(dragArm); v.removeCallbacks(rightDragArm); tpSecond = false"
        ),
    },
    {
        "id": "N4b UP 状态归零",
        "file": RIN,
        "anchor": "                    tpDown = false; tpDrag = false",
        "new": "                    tpDown = false; tpDrag = false; tpRightDrag = false",
    },
    {
        "id": "N5 文档注释同步",
        "file": RIN,
        "anchor": "    /** 内嵌触控板（互操作保留）：滑动=移动光标、轻点=左键、长按 400ms 进拖拽 */",
        "new": "    /** 内嵌触控板（互操作保留）：滑动=光标、轻点=左键、长按 550ms 左键拖拽；\n"
            "     *  #3 接通用逻辑：双指点按=右键、双指长按 550ms=右键拖拽（与 touchpad.kt 同口径）。 */"
        ,
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
