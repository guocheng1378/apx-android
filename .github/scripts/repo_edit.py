#!/usr/bin/env python3
"""repo-edit：Build #1455 编译修复 v2（v1 因 F2 注释嵌套引号转义少层→python 语法错秒挂；
本版注释不嵌英文双引号；三锚已在 c4d38b1b 实测全中）。

  F1 indication LocalIndication 解析不到 → null（键盘页 KeyBox 同款惯例）
  F2/F3 SpeechRecognizer 4 静态字段 + 连锁 putExtra → 公开字面量（与 AOSP 逐字一致）
"""
import os
import sys

ERRLOG = "docs/edit_error.log"
RIN = "android/app/src/main/java/com/allperiph/controlled/RemoteInputActivity.kt"

EDITS = [
    {
        "id": "F1 indication null",
        "file": RIN,
        "anchor": "                    indication = androidx.compose.ui.platform.LocalIndication.current,",
        "new": "                    indication = null,   // 键盘页 KeyBox 同款（视觉自绘/按下震补偿；platform.LocalIndication 本编译环境解析不到）",
    },
    {
        "id": "F2 EXTRA_RESULTS 字面量",
        "file": RIN,
        "anchor": "            val text = data?.getStringArrayListExtra(android.speech.SpeechRecognizer.EXTRA_RESULTS)",
        "new": (
            "            // 字面量值即 SpeechRecognizer.EXTRA_RESULTS（android.speech.extra.RESULTS）——\n"
            "            // 本编译环境解析不到 SpeechRecognizer 静态字段，公开协议常量直接用字面值\n"
            "            val text = data?.getStringArrayListExtra(\"android.speech.extra.RESULTS\")"
        ),
    },
    {
        "id": "F3 语音 Intent 字面量",
        "file": RIN,
        "anchor": (
            "        val intent = Intent(android.speech.SpeechRecognizer.ACTION_RECOGNIZE_SPEECH)\n"
            "            .putExtra(android.speech.SpeechRecognizer.EXTRA_LANGUAGE_MODEL,\n"
            "                android.speech.SpeechRecognizer.LANGUAGE_MODEL_FREE_FORM)"
        ),
        "new": (
            "        // 字面量与 SpeechRecognizer 源码逐字一致（ACTION / EXTRA_LANGUAGE_MODEL /\n"
            "        // LANGUAGE_MODEL_FREE_FORM）—— 本编译环境解析不到该类静态字段，\n"
            "        // 公开协议常量直接用字面值，零符号依赖。\n"
            "        val intent = Intent(\"android.speech.action.RECOGNIZE_SPEECH\")\n"
            "            .putExtra(\"android.speech.extra.LANGUAGE_MODEL\", \"free_form\")"
        ),
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
