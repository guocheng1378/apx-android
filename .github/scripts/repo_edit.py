#!/usr/bin/env python3
"""repo-edit：#7「手机打的字和电脑照不齐」双端修复（11 锚，4 文件）。

链路：RemoteInputActivity(TextWatcher 逐字增量) → 0x26 → wireless_session(注入 PC)。
纯单向增量、无 ACK —— 任一环丢帧即永久漂移。三修：
  1) 手机 sendInputText 改用真实返回（此前丢返回值恒报成功）；
  2) 手机失败帧入队 + 发送按钮前全量对账（0x04）；
  3) PC 实现 0x04=替换（按已注入计数退格清场再重打）+ 计数维护/会话清零。

同文件多编辑走累积式（run #97 覆盖 bug 已修）；锚点均唯一；幂等。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"

CS = "android/shared/src/main/java/com/allperiph/shared/net/ControlServer.kt"
TVC = "android/app/src/main/java/com/allperiph/wireless/TvControllerClient.kt"
RIA = "android/app/src/main/java/com/allperiph/controlled/RemoteInputActivity.kt"
WS = "pc/host/src/wireless/wireless_session.cpp"

EDITS = [
    {
        "id": "E1 ControlServer 返回真实结果",
        "file": CS,
        "anchor": (
            '    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL) {\n'
            "        // v-fix：组帧去重 —— 手写 u16 长度段与 ApxFrame.packInputText 完全同构，\n"
            "        // 收发两处各自维护一份就是隐患；统一走 packer（对齐线上布局与全部解析方）。\n"
            "        sendControl(ApxFrame.packInputText(flags, text))\n"
            "    }"
        ),
        "new": (
            '    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL): Boolean {\n'
            "        // v-fix：组帧去重 —— 手写 u16 长度段与 ApxFrame.packInputText 完全同构，\n"
            "        // 收发两处各自维护一份就是隐患；统一走 packer（对齐线上布局与全部解析方）。\n"
            "        // 返回真实发送结果：远程输入页据此失败入队重推 —— 此前丢弃返回值恒报成功，\n"
            "        // 写失败静默，对端就永久少这段字（「和电脑上照不齐」根因之一）。\n"
            "        return sendControl(ApxFrame.packInputText(flags, text))\n"
            "    }"
        ),
    },
    {
        "id": "E2 TvControllerClient 返回真实结果",
        "file": TVC,
        "anchor": (
            '    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL) {\n'
            "        // v-fix：同 requestInput —— 统一走 ApxFrame.packInputText，消除手写副本。\n"
            "        sendControl(ApxFrame.packInputText(flags, text))\n"
            "    }"
        ),
        "new": (
            '    fun sendInputText(text: String, flags: Int = ApxFrame.INPUT_FLAG_INCREMENTAL): Boolean {\n'
            "        // v-fix：同 requestInput —— 统一走 ApxFrame.packInputText，消除手写副本。\n"
            "        // 返回真实结果（断线/队列拒收如实 false）—— RemoteInputActivity 据此入队重推。\n"
            "        return sendControl(ApxFrame.packInputText(flags, text))\n"
            "    }"
        ),
    },
    {
        "id": "E3 sendText 真实返回 + 失败积压队列",
        "file": RIA,
        "anchor": (
            "    private fun sendText(text: String, flags: Int): Boolean {\n"
            "        if (viaClient) {\n"
            "            val c = com.allperiph.wireless.ControlTarget.controlClient ?: return false\n"
            "            c.sendInputText(text, flags); return true\n"
            "        }\n"
            "        return ControlledService.server?.let { it.sendInputText(text, flags); true } ?: false\n"
            "    }"
        ),
        "new": (
            "    private fun sendText(text: String, flags: Int): Boolean {\n"
            "        if (viaClient) {\n"
            "            val c = com.allperiph.wireless.ControlTarget.controlClient ?: return false\n"
            "            // 真实结果：此前恒 return true，写失败静默 —— 对端永久少这段字\n"
            "            return c.sendInputText(text, flags)\n"
            "        }\n"
            "        return ControlledService.server?.sendInputText(text, flags) ?: false\n"
            "    }\n"
            "\n"
            "    /** 失败帧积压（保序）：单向增量链路丢一帧对端就永久漂移 —— 发送失败的帧先排队，\n"
            "     *  下一次发送前按序重推，通道一恢复就自动补齐（不再要用户「恢复后重新打」）。 */\n"
            "    private val pendingFrames = ArrayDeque<Pair<String, Int>>()\n"
            "\n"
            "    private fun sendTextReliable(text: String, flags: Int): Boolean {\n"
            "        while (pendingFrames.isNotEmpty()) {\n"
            "            val (t, f) = pendingFrames.first()\n"
            "            if (!sendText(t, f)) return false\n"
            "            pendingFrames.removeFirst()\n"
            "        }\n"
            "        if (sendText(text, flags)) return true\n"
            "        pendingFrames.addLast(text to flags)\n"
            "        return false\n"
            "    }"
        ),
    },
    {
        "id": "E4 TextWatcher 增量走可靠发送",
        "file": RIA,
        "anchor": "                        val ok = sendText(text.substring(start, start + count), ApxFrame.INPUT_FLAG_INCREMENTAL)",
        "new": "                        val ok = sendTextReliable(text.substring(start, start + count), ApxFrame.INPUT_FLAG_INCREMENTAL)",
    },
    {
        "id": "E5 TextWatcher 退格走可靠发送",
        "file": RIA,
        "anchor": (
            "                    } else if (count < before) {\n"
            "                        sendText(\"\", ApxFrame.INPUT_FLAG_BACKSPACE)"
        ),
        "new": (
            "                    } else if (count < before) {\n"
            "                        sendTextReliable(\"\", ApxFrame.INPUT_FLAG_BACKSPACE)"
        ),
    },
    {
        "id": "E6 失败提示文案改口",
        "file": RIA,
        "anchor": "                                    \"没发出去 —— 连接断了？恢复后这段要重新打\",",
        "new": "                                    \"没发出去 —— 连接断了？恢复后会自动补发\",",
    },
    {
        "id": "E7 sendCurrent 全量对账 + 失败不清框",
        "file": RIA,
        "anchor": (
            "        sendDone()\n"
            "        suppressInputWatcher = true          // 清空动作绝不能被当成退格发给对端"
        ),
        "new": (
            "        // 全量对账（0x04=整段替换，PC 端退格清场重打）：增量丢帧在这一拍归零自愈。\n"
            "        // 对账发不出去就**不清空输入框** —— 字留在框里可重试，而不是凭空消失。\n"
            "        if (!sendTextReliable(t, ApxFrame.INPUT_FLAG_COMMIT)) {\n"
            "            Toast.makeText(this, \"没发出去 —— 连接断了？这段还在框里，恢复后再点发送\",\n"
            "                Toast.LENGTH_LONG).show()\n"
            "            return\n"
            "        }\n"
            "        sendDone()\n"
            "        suppressInputWatcher = true          // 清空动作绝不能被当成退格发给对端"
        ),
    },
    {
        "id": "E8 PC 注入计数 + UTF-8 字符数 helper",
        "file": WS,
        "anchor": "WirelessSession::WirelessSession() {",
        "new": (
            "namespace {\n"
            "// 远程输入注入净字符数：0x04 全量对账按它退格清场（只在 9511 reader 线程读写，无需锁）。\n"
            "// DONE(0x27)/CANCEL(0x08)/新会话(0x25) 清零 —— 上一句定格，遥控页 0x04 不受残留影响。\n"
            "size_t g_injectedLen = 0;\n"
            "size_t utf8Chars(const std::string& s) {\n"
            "    size_t n = 0;\n"
            "    for (unsigned char c : s) if ((c & 0xC0) != 0x80) ++n;\n"
            "    return n;\n"
            "}\n"
            "}  // namespace\n"
            "\n"
            "WirelessSession::WirelessSession() {"
        ),
    },
    {
        "id": "E9 PC 0x04 替换语义 + 计数",
        "file": WS,
        "anchor": (
            "    client_.onRemoteInputText = [](const std::string& text, uint8_t flags) {\n"
            "        // v184：BACKSPACE（0x02）此前被当作空文本直接吞掉 —— 手机端删除键失灵的根因\n"
            "        if (flags & 0x02) {   // INPUT_FLAG_BACKSPACE（ApxFrame.kt 定义，PC 侧无此常量）\n"
            "            // 0x08 = Windows VK_BACK：协议两侧按同一 VK 码空间解释（见 input_injector_*）\n"
            "            apxpc::wireless::injectSpecialKey(0, 0x08);\n"
            "            return;\n"
            "        }\n"
            "        if (!apxpc::wireless::injectSystemText(text))\n"
            "            APX_LOGW(\"远程输入文本注入失败（{} 字节）\", static_cast<unsigned>(text.size()));\n"
            "    };"
        ),
        "new": (
            "    client_.onRemoteInputText = [](const std::string& text, uint8_t flags) {\n"
            "        // v184：BACKSPACE（0x02）此前被当作空文本直接吞掉 —— 手机端删除键失灵的根因\n"
            "        if (flags & 0x02) {   // INPUT_FLAG_BACKSPACE（ApxFrame.kt 定义，PC 侧无此常量）\n"
            "            // 0x08 = Windows VK_BACK：协议两侧按同一 VK 码空间解释（见 input_injector_*）\n"
            "            apxpc::wireless::injectSpecialKey(0, 0x08);\n"
            "            if (g_injectedLen > 0) --g_injectedLen;\n"
            "            return;\n"
            "        }\n"
            "        if (flags & 0x08) { g_injectedLen = 0; return; }   // CANCEL：会话作废，计数归零\n"
            "        if (flags & 0x04) {\n"
            "            // 0x04 = 全量对账（替换语义）：手机端 TextWatcher 逐字增量是纯单向流，\n"
            "            // 丢一帧对端永久漂移（用户报的「手机打的字和电脑上照不齐」）。手机发送时\n"
            "            // 把整段快照发来，这里按已注入计数退格清场再重打，一次对账归零。\n"
            "            // 此前 0x04 未实现、整段被当追加注入（所以历史注释禁发 COMMIT）——现在禁令解除。\n"
            "            while (g_injectedLen > 0) { apxpc::wireless::injectSpecialKey(0, 0x08); --g_injectedLen; }\n"
            "        }\n"
            "        if (!apxpc::wireless::injectSystemText(text)) {\n"
            "            APX_LOGW(\"远程输入文本注入失败（{} 字节）\", static_cast<unsigned>(text.size()));\n"
            "            return;   // 注入失败不计数 —— 下一拍 0x04 对账会重打这段\n"
            "        }\n"
            "        g_injectedLen += utf8Chars(text);\n"
            "    };"
        ),
    },
    {
        "id": "E10 PC DONE 清零",
        "file": WS,
        "anchor": "    client_.onRemoteInputDone = [] { APX_LOGI(\"远程输入完成\"); };",
        "new": "    client_.onRemoteInputDone = [] { g_injectedLen = 0; APX_LOGI(\"远程输入完成\"); };",
    },
    {
        "id": "E11 PC 新会话清零",
        "file": WS,
        "anchor": "    client_.onRequestInput = [this](const std::string& hint) {",
        "new": (
            "    client_.onRequestInput = [this](const std::string& hint) {\n"
            "        g_injectedLen = 0;   // 新会话开窗：清掉上一轮可能残留的注入计数（DONE 丢失时的保险）"
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
