#!/usr/bin/env python3
"""repo-edit：互传超时修复 —— E1/E2 重跑版。

run #97 教训：同一文件多条 EDITS 必须**累积替换**（pending 里拿上次结果继续改），
否则每条都从原文重算、后写覆盖前写，sender 只落了最后一条 E3。本版修成累积式，
全部 5 条重列（已落的自动 skip，幂等）。本次目标：E1 register 30s + E2 prepare 30s。
"""
import os
import sys

ERRLOG = "docs/edit_error.log"

EDITS = [
    {
        "id": "E1 register 30s",
        "file": "pc/host/src/localsend/local_send_sender.cpp",
        "kind": "replace",
        "anchor": "    reg.body = &regBody;",
        "new": (
            "    reg.body = &regBody;\n"
            "    // 10s 默认值在慢机（息屏首包 / 高负载）上过紧 —— register 超时误报在这里拦过一次\n"
            "    reg.timeoutMs = 30'000;"
        ),
    },
    {
        "id": "E2 prepare 30s",
        "file": "pc/host/src/localsend/local_send_sender.cpp",
        "kind": "replace",
        "anchor": "    prep.body = &prepBody;",
        "new": (
            "    prep.body = &prepBody;\n"
            "    // 同 register：对端可能弹「是否接收」等人工点，10s 必超 —— 放宽到 30s\n"
            "    prep.timeoutMs = 30'000;"
        ),
    },
    {
        "id": "E3 upload 响应宽限重读",
        "file": "pc/host/src/localsend/local_send_sender.cpp",
        "kind": "replace",
        "anchor": (
            "    bool bodyDone = false;\n"
            "    char buf[16 * 1024];\n"
            "    while (!bodyDone) {\n"
            "        const int rd = waitReadable(s, req.timeoutMs);\n"
            "        if (rd == 0) { resp.error = \"等待响应超时\"; break; }"
        ),
        "new": (
            "    bool bodyDone = false;\n"
            "    bool graceUsed = false;\n"
            "    char buf[16 * 1024];\n"
            "    while (!bodyDone) {\n"
            "        int rd = waitReadable(s, req.timeoutMs);\n"
            "        if (rd == 0 && req.stream && !graceUsed) {\n"
            "            // upload 的字节已全部写出：对端要收全才落盘回 204，慢机 / 息屏会晚到 ——\n"
            "            // 首超不判死，再宽限 30s 重读一次（根治「手机已完整收到、PC 报等待响应超时」）\n"
            "            graceUsed = true;\n"
            "            rd = waitReadable(s, 30'000);\n"
            "        }\n"
            "        if (rd == 0) { resp.error = \"等待响应超时\"; break; }"
        ),
    },
    {
        "id": "E4 web_panel 进度事件",
        "file": "pc/host/src/ui/web_panel.cpp",
        "kind": "replace",
        "anchor": "            const auto res = tx.send(target, self, files);",
        "new": """            // 进度节流：sender 的 onWrite 每 64KB 一拍，直接转事件会淹没面板消息泵 ——
            // 与 file.send 同口径：每 10% 推一拍（postEvent 线程安全，发送线程可调）
            int lastPct = -1;
            const auto onProgress = [p, &lastPct](const apxpc::localsend::SendProgress& pr) {
                const int pct = pr.grandTotal > 0
                    ? static_cast<int>(pr.totalBytes * 100 / pr.grandTotal) : 100;
                if (pct >= 100 || pct - lastPct >= 10) {
                    lastPct = pct;
                    Json d = Json::makeObject();
                    d["percent"] = pct;
                    d["fileName"] = pr.fileName;
                    d["filesDone"] = static_cast<double>(pr.filesDone);
                    d["filesTotal"] = static_cast<double>(pr.filesTotal);
                    d["bytes"] = static_cast<double>(pr.totalBytes);
                    d["total"] = static_cast<double>(pr.grandTotal);
                    postEvent(p, std::string("on-localsend-progress"), d);
                }
                return true;
            };
            const auto res = tx.send(target, self, files, onProgress);""",
    },
    {
        "id": "E5 Transfer 订阅进度",
        "file": "pc/web/src/pages/Transfer.tsx",
        "kind": "replace",
        "anchor": "      bridge.onEvent('on-file-received', (evt: any) => {",
        "new": """      // 宿主每 10% 推一拍（web_panel.cpp localsend.send 分支节流）：大文件互传
      // 从「正在发送…」干等变成带百分比 —— 也是区分「在传」与「卡死」的唯一信号
      bridge.onEvent('on-localsend-progress', (evt: any) => {
        const d = evt?.data || {};
        if (typeof d.percent !== 'number') return;
        const nm = d.fileName ? fileNameOf(String(d.fileName)) : '';
        setLsStatus(
          `正在发送${nm ? ` ${nm}` : ''} · ${d.percent}%` +
            (d.filesTotal > 1 ? ` · ${d.filesDone + 1}/${d.filesTotal}` : ''),
        );
      }),
      bridge.onEvent('on-file-received', (evt: any) => {""",
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
        # 累积替换：同文件后一条基于前一条的结果继续改（run #97 覆盖 bug 的修正）
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
