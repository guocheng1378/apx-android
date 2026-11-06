#!/usr/bin/env python3
"""repo-edit：批 D-1 v2（9511 心跳哑火三角定位诊断，2026-11-07；v1 run#81 失败修复）。

v1 失败原因：E1 锚点错写成 `private var rightUpPending = false`（布尔），
ControlServer 真身是 `private var rightUpPending: Runnable? = null` —— count=0 校验即败。
本版已修；其余四锚点（pump magic/payloadLen 块、verify 行、28 空格 ping 派发行、
ctrl9511 pong 清零三行）经 search_code 逐字节复核无误。

解读矩阵（装包跑 60 秒）：
  手机日志「诊断:收到ping」 + PC 导出「9511 收到 pong」 → 链路通
  手机「丢帧#N CRC不符」 → 字节级失配直接现形
  手机无「收到ping」 → PC→手机段未送达（查 PC 侧 send）
  手机有 ping、无 pong 发送失败日志、PC 无 pong → 手机 pump 没派发（丢帧计数会报原因）
"""
import sys

EDITS = [
    {
        "id": "E1 ControlServer 字段 frameDrops",
        "file": "android/shared/src/main/java/com/allperiph/shared/net/ControlServer.kt",
        "kind": "append_after",
        "marker": "private var frameDrops = 0",
        "anchor": "    private var rightUpPending: Runnable? = null",
        "insert": (
            "\n    // D-1 诊断：静默丢帧计数（magic/payloadLen/CRC 三类，每 50 次记一条）"
            "\n    private var frameDrops = 0"
        ),
    },
    {
        "id": "E2 ControlServer pump magic/payloadLen 丢帧日志",
        "file": "android/shared/src/main/java/com/allperiph/shared/net/ControlServer.kt",
        "kind": "replace",
        "anchor": (
            "                if (!ApxFrame.isMagic(rxBuf, off, rxLen)) { off++; continue }\n"
            "                val payloadLen = ApxFrame.payloadLenAt(rxBuf, off)\n"
            "                if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) { off++; continue }\n"
            "                val total = ApxFrame.totalSize(payloadLen)\n"
            "                if (rxLen - off < total) break"
        ),
        "new": (
            "                if (!ApxFrame.isMagic(rxBuf, off, rxLen)) {\n"
            "                    if (frameDrops++ % 50 == 0) Log.w(\"控制面\", \"诊断:丢帧#${frameDrops} magic不符 off=$off rxLen=$rxLen\")\n"
            "                    off++; continue\n"
            "                }\n"
            "                val payloadLen = ApxFrame.payloadLenAt(rxBuf, off)\n"
            "                if (payloadLen < 0 || payloadLen > ApxFrame.MAX_PAYLOAD) {\n"
            "                    if (frameDrops++ % 50 == 0) Log.w(\"控制面\", \"诊断:丢帧#${frameDrops} payloadLen=$payloadLen\")\n"
            "                    off++; continue\n"
            "                }\n"
            "                val total = ApxFrame.totalSize(payloadLen)\n"
            "                if (rxLen - off < total) break"
        ),
    },
    {
        "id": "E3 ControlServer CRC 丢帧日志",
        "file": "android/shared/src/main/java/com/allperiph/shared/net/ControlServer.kt",
        "kind": "replace",
        "anchor": "                if (!ApxFrame.verify(rxBuf, off, payloadLen)) { off += total; continue }",
        "new": (
            "                if (!ApxFrame.verify(rxBuf, off, payloadLen)) {\n"
            "                    if (frameDrops++ % 50 == 0) Log.w(\"控制面\", \"诊断:丢帧#${frameDrops} CRC不符 off=$off len=$payloadLen\")\n"
            "                    off += total; continue\n"
            "                }"
        ),
    },
    {
        "id": "E4 ControlServer 收到ping/pong发送失败日志",
        "file": "android/shared/src/main/java/com/allperiph/shared/net/ControlServer.kt",
        "kind": "replace",
        "anchor": "                            'p'.code -> actions.add { sendControl(PONG) }",
        "new": (
            "                            'p'.code -> {\n"
            "                                Log.i(\"控制面\", \"诊断:收到ping→回pong\")\n"
            "                                actions.add {\n"
            "                                    if (!sendControl(PONG)) Log.w(\"控制面\", \"诊断:pong发送失败 ready=$ready\")\n"
            "                                }\n"
            "                            }"
        ),
    },
    {
        "id": "E5 ctrl9511 收到 pong 落 INFO",
        "file": "pc/host/src/wireless/ctrl9511.cpp",
        "kind": "append_after",
        "marker": "9511 收到 pong（心跳恢复）",
        "anchor": (
            "                    im->pingPending_.store(false);\n"
            "                    im->missedPongs_.store(0);   // v184：收到 pong，失败计数清零\n"
            "                    const auto sent = im->pingSentMs_.load();"
        ),
        "insert": (
            "\n                    APX_LOGI(\"9511 收到 pong（心跳恢复）\");"
        ),
    },
]


def main() -> int:
    files = {}
    for e in EDITS:
        files.setdefault(e["file"], []).append(e)

    pending = {}
    for path, edits in files.items():
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as ex:
            print(f"open failed {path}: {ex}")
            return 1

        for e in edits:
            if e["kind"] == "replace":
                if e["new"] in text:
                    print(f"already applied, skip: {e['id']}")
                    continue
                n = text.count(e["anchor"])
                if n != 1:
                    print(f"VALIDATION FAILED: {e['id']} anchor count={n} expect=1\n---anchor---\n{e['anchor'][:200]}")
                    return 1
                text = text.replace(e["anchor"], e["new"], 1)
            elif e["kind"] == "append_after":
                if e["marker"] in text:
                    print(f"already applied, skip: {e['id']}")
                    continue
                n = text.count(e["anchor"])
                if n != 1:
                    print(f"VALIDATION FAILED: {e['id']} anchor count={n} expect=1\n---anchor---\n{e['anchor'][:200]}")
                    return 1
                text = text.replace(e["anchor"], e["anchor"] + e["insert"], 1)
            else:
                print(f"VALIDATION FAILED: unknown kind {e['kind']}")
                return 1
        pending[path] = text

    if not pending:
        print("no changes")
        return 0
    changed = []
    for path, content in pending.items():
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        except OSError as ex:
            print(f"write failed {path}: {ex}")
            return 1
        changed.append(path)
    print("OK: " + ", ".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
