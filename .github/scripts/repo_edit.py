#!/usr/bin/env python3
"""repo-edit：#6 长按连发 + 批B 之 #4语音/#5动效震动（文件级已验证锚点，RemoteKeyboard 不存在已剔除）。

  K1 KeyBox（键盘页）：连发开火后吞抬手那次 onClick + repeatFired 状态
  K2 KeyBox：按住 400ms 起每 55ms 重发（delay 走全限定名免 import；修饰键/遥控图块不连发）
  R1 RemoteInputActivity：补 compose runtime/interaction imports（remember/LaunchedEffect 等）
  R2 KeyButton 编辑键排：同 K1/K2 连发语义 + 按下即震（Feedback.tap）
  R3 ActionPill（取消/发送）：按下即震（#5）
  R4 底部加「语音」按钮（#4）
  R5 startVoiceInput + onActivityResult：系统语音识别 → append 进输入框（自动进增量上屏/对账链）
  R6 内嵌触控板轻点：补震感（#5，与操控面同口径）

四次失败教训：RemoteKeyboard.kt 不存在（打开失败→整跑中止）；KeyButton/KeyBox 曾拿错名。
本版锚点全部来自 1cf6f806 实文件 read；幂等；累积式。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"

KB = "android/app/src/main/java/com/allperiph/ui/compose/KeyboardPage.kt"
RIN = "android/app/src/main/java/com/allperiph/controlled/RemoteInputActivity.kt"

EDITS = [
    {
        "id": "K1 KeyBox 吞尾+状态",
        "file": KB,
        "anchor": (
            "    val host = LocalView.current\n"
            "    // 震动在按下那一刻由 [apKeyFeel] 给过了，这里只出声（见 Feedback.release 的说明）\n"
            "    val press: () -> Unit = { Feedback.release(host); onClick() }"
        ),
        "new": (
            "    val host = LocalView.current\n"
            "    // 震动在按下那一刻由 [apKeyFeel] 给过了，这里只出声（见 Feedback.release 的说明）\n"
            "    // #6 长按连发（用户 2026-11-08「长按按键的连发功能所有键盘都没有吗」）：\n"
            "    // 连发开火后抬手那次自然 onClick 吞掉 —— 否则长按结尾会多发一下（与操控面同语义）。\n"
            "    var repeatFired by remember { mutableStateOf(false) }\n"
            "    val press: () -> Unit = {\n"
            "        if (repeatFired) { repeatFired = false } else { Feedback.release(host); onClick() }\n"
            "    }"
        ),
    },
    {
        "id": "K2 KeyBox 连发主体",
        "file": KB,
        "anchor": (
            "    LaunchedEffect(pressed) {\n"
            "        if (pressed && rgbOn && rgb.mode == KeyPref.RGB_TOUCH) TouchFx.add(rgbC.x, rgbC.y)\n"
            "    }"
        ),
        "new": (
            "    LaunchedEffect(pressed) {\n"
            "        if (pressed && rgbOn && rgb.mode == KeyPref.RGB_TOUCH) TouchFx.add(rgbC.x, rgbC.y)\n"
            "    }\n"
            "    // —— #6 长按连发：按住 400ms 起、每 55ms 一拍重发，松手即停；连发只送键不出声\n"
            "    //（声/震在按下那一下已给）。修饰键走 ModKey、遥控走 MediaTile，均不连发。\n"
            "    LaunchedEffect(pressed) {\n"
            "        if (pressed) {\n"
            "            kotlinx.coroutines.delay(400)\n"
            "            while (true) {\n"
            "                repeatFired = true\n"
            "                onClick()\n"
            "                kotlinx.coroutines.delay(55)\n"
            "            }\n"
            "        }\n"
            "    }"
        ),
    },
    {
        "id": "R1 imports",
        "file": RIN,
        "anchor": "import androidx.compose.runtime.Composable",
        "new": (
            "import androidx.compose.runtime.LaunchedEffect\n"
            "import androidx.compose.runtime.Composable\n"
            "import androidx.compose.runtime.getValue\n"
            "import androidx.compose.runtime.mutableStateOf\n"
            "import androidx.compose.runtime.remember\n"
            "import androidx.compose.runtime.setValue\n"
            "import androidx.compose.foundation.interaction.MutableInteractionSource\n"
            "import androidx.compose.foundation.interaction.collectIsPressedAsState"
        ),
    },
    {
        "id": "R2 KeyButton 连发+震动",
        "file": RIN,
        "anchor": (
            "    /** 编辑键排的一个键 */\n"
            "    @Composable\n"
            "    private fun KeyButton(label: String, onClick: () -> Unit) {\n"
            "        Box(\n"
            "            modifier = Modifier\n"
            "                .padding(end = 6.dp)\n"
            "                .clip(RoundedCornerShape(8.dp))\n"
            "                .background(androidx.compose.ui.graphics.Color(getColor(R.color.remote_panel_field)))\n"
            "                .clickable { onClick() }\n"
            "                .padding(horizontal = 14.dp, vertical = 10.dp),\n"
            "        ) {"
        ),
        "new": (
            "    /** 编辑键排的一个键（#6 按住 400ms 起每 55ms 连发、抬手吞尾；#5 按下即震） */\n"
            "    @Composable\n"
            "    private fun KeyButton(label: String, onClick: () -> Unit) {\n"
            "        var repeatFired by remember { mutableStateOf(false) }\n"
            "        val interaction = remember { MutableInteractionSource() }\n"
            "        val pressed by interaction.collectIsPressedAsState()\n"
            "        LaunchedEffect(pressed) {\n"
            "            if (pressed) {\n"
            "                kotlinx.coroutines.delay(400)\n"
            "                while (true) {\n"
            "                    repeatFired = true\n"
            "                    onClick()\n"
            "                    kotlinx.coroutines.delay(55)\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "        val host = androidx.compose.ui.platform.LocalView.current\n"
            "        Box(\n"
            "            modifier = Modifier\n"
            "                .padding(end = 6.dp)\n"
            "                .clip(RoundedCornerShape(8.dp))\n"
            "                .background(androidx.compose.ui.graphics.Color(getColor(R.color.remote_panel_field)))\n"
            "                .clickable(\n"
            "                    interactionSource = interaction,\n"
            "                    indication = androidx.compose.ui.platform.LocalIndication.current,\n"
            "                ) {\n"
            "                    if (repeatFired) { repeatFired = false }\n"
            "                    else { com.allperiph.ui.Feedback.tap(host); onClick() }\n"
            "                }\n"
            "                .padding(horizontal = 14.dp, vertical = 10.dp),\n"
            "        ) {"
        ),
    },
    {
        "id": "R3 ActionPill 震动",
        "file": RIN,
        "anchor": (
            "    /** 底部按钮（取消 / 发送） */\n"
            "    @Composable\n"
            "    private fun ActionPill(label: String, bg: Int, onClick: () -> Unit) {\n"
            "        Box(\n"
            "            modifier = Modifier\n"
            "                .clip(RoundedCornerShape(8.dp))\n"
            "                .background(androidx.compose.ui.graphics.Color(bg))\n"
            "                .clickable { onClick() }"
        ),
        "new": (
            "    /** 底部按钮（取消 / 发送）（#5 按下即震） */\n"
            "    @Composable\n"
            "    private fun ActionPill(label: String, bg: Int, onClick: () -> Unit) {\n"
            "        val host = androidx.compose.ui.platform.LocalView.current\n"
            "        Box(\n"
            "            modifier = Modifier\n"
            "                .clip(RoundedCornerShape(8.dp))\n"
            "                .background(androidx.compose.ui.graphics.Color(bg))\n"
            "                .clickable { com.allperiph.ui.Feedback.tap(host); onClick() }"
        ),
    },
    {
        "id": "R4 语音按钮",
        "file": RIN,
        "anchor": "                            ActionPill(getString(R.string.ui_common_cancel), neutral) { cancelAndFinish() }",
        "new": (
            "                            ActionPill(\"语音\", neutral) { startVoiceInput() }\n"
            "                            Spacer(Modifier.width(12.dp))\n"
            "                            ActionPill(getString(R.string.ui_common_cancel), neutral) { cancelAndFinish() }"
        ),
    },
    {
        "id": "R5 语音识别实现",
        "file": RIN,
        "anchor": "    override fun onDestroy() {",
        "new": (
            "    /** #4 语音输入：系统语音识别 → 文字 append 进输入框（自动走 TextWatcher 增量上屏/对账链） */\n"
            "    @Deprecated(\"Deprecated in Java\")\n"
            "    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {\n"
            "        super.onActivityResult(requestCode, resultCode, data)\n"
            "        if (requestCode == 726 && resultCode == RESULT_OK) {\n"
            "            val text = data?.getStringArrayListExtra(android.speech.SpeechRecognizer.EXTRA_RESULTS)\n"
            "                ?.firstOrNull() ?: return\n"
            "            editText?.append(text)\n"
            "        }\n"
            "    }\n"
            "\n"
            "    private fun startVoiceInput() {\n"
            "        val intent = Intent(android.speech.SpeechRecognizer.ACTION_RECOGNIZE_SPEECH)\n"
            "            .putExtra(android.speech.SpeechRecognizer.EXTRA_LANGUAGE_MODEL,\n"
            "                android.speech.SpeechRecognizer.LANGUAGE_MODEL_FREE_FORM)\n"
            "        try {\n"
            "            @Suppress(\"DEPRECATION\")\n"
            "            startActivityForResult(intent, 726)\n"
            "        } catch (e: Exception) {\n"
            "            Toast.makeText(this, \"系统语音识别不可用\", Toast.LENGTH_SHORT).show()\n"
            "        }\n"
            "    }\n"
            "\n"
            "    override fun onDestroy() {"
        ),
    },
    {
        "id": "R6 触控板轻点震动",
        "file": RIN,
        "anchor": (
            "                        if (!tpDrag && !cancelled && moved && dur < 300) {\n"
            "                            mouseFrame(0x01, 0, 0); mouseFrame(0x00, 0, 0)   // 轻点 = 左键点击"
        ),
        "new": (
            "                        if (!tpDrag && !cancelled && moved && dur < 300) {\n"
            "                            mouseFrame(0x01, 0, 0); mouseFrame(0x00, 0, 0)   // 轻点 = 左键点击\n"
            "                            com.allperiph.ui.Feedback.haptic(v)               // #5 轻点给震感"
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
