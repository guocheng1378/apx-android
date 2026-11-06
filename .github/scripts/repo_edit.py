#!/usr/bin/env python3
"""repo-edit：批 D-1（9511 心跳哑火三角定位诊断，2026-11-06）。

背景：无线控制面 6.1s 循环（PC 心跳 3 轮×2s 无 pong 判死 → 自愈重连）与
     手机侧 24s 空闲判死循环并存；帧布局/CRC/pong 实现两端静态对拍全等价，
     需运行时三角测量断点。

锚点全部经 search_code 逐字节核准（含 MAX_PAYLOAD/totalSize 真名修正）：
  ControlServer.kt —— pump 解析块（magic/payloadLen/verify 三类静默丢帧计数日志）、
                      'p' 派发行（收到 ping 日志 + pong 发送失败日志）、
                      字段挂点（frameDrops 计数器）
  ctrl9511.cpp     —— pong 清零块后落 INFO（进导出日志）

解读矩阵（装包跑 60 秒）：
  手机日志有「诊断:收到ping」+ PC 导出有「收到 pong」 → 链路自愈，问题在别处
  手机日志有「收到ping」但无「pong发送失败」也无 PC「收到 pong」 → 手机 pump 未派发（丢帧计数会报原因）
  手机日志出现「丢帧#N CRC不符」 → 直接锁定字节级失配
  手机日志无「收到ping」 → PC→手机段未送达（查 PC 侧 send）
"""
import sys

EDITS = [
    {
        "id": "E1 ControlServer 字段 frameDrops",
        "file": "android/shared/src/main/java/com/allperiph/shared/net/ControlServer.kt",
        "kind": "append_after",
        "marker": "private var frameDrops = 0",
        "anchor": "    private var rightUpPending = false",
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

    pending = {}  # path -> new content（全部校验通过才落盘）
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
