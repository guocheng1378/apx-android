#!/usr/bin/env python3
"""跨端协议一致性校验 —— 把"应该一致"的帧常量与命令号从四处源码里抽出来逐项比对。

为什么需要这个脚本
------------------
项目里同一份 APX1 协议被**手写了四遍**：C++（shared/include/apx/frame.h）、
手机端 Kotlin（core/ApxFrame.kt）、TV 端 Kotlin（tv/core/ApxFrame.kt）、
以及 PC 控制面分发（pc/host/src/wireless/ctrl9511.cpp）。
v184 之前修的若干"某方向偶尔不工作"类缺陷，根因都是这几份实现悄悄漂移：
  · 键盘帧键码起始偏移（发送端 b[3+i] vs 接收端 p+3）
  · CRC 覆盖范围与"接收侧从不校验"
  · magic/长度失配后的重同步策略（整体丢弃 vs 逐字节前进）
  · 连接接管时序
手工 review 抓不全，所以固化成脚本：CI 里每次提交都跑一遍。

判定分三档
----------
  [错误] 帧常量不一致（头长/CRC 长/载荷上限/streamId/flag）—— 必须完全相同
  [错误] 某个命令号"有人发但没人收"—— 典型漏实现
  [警告] 某个命令号"有人收但没人发"—— 死代码或发送端未接
退出码：有 [错误] 则非零。

用法
----
    python3 scripts/check_protocol.py [--repo <仓库根>]
"""

from __future__ import annotations

import argparse
import os
import re
import sys

# ——————————————————————————— 期望值（唯一真源）———————————————————————————
# 这些值同时写在 docs/PROTOCOL.md §3 与 shared/include/apx/frame.h；此处是校验用的副本。
EXPECT_FRAME = {
    "header": 16,
    "crc": 4,
    "max_payload": 4 * 1024 * 1024,
}
EXPECT_STREAMS = {
    "video": 0,
    "audio": 1,
    "touch": 2,
    "control": 3,
    "telemetry": 4,
    "mic": 5,
}
EXPECT_FLAGS = {
    "key_frame": 1,
    "last_fragment": 2,
    "dropable": 4,
}

# 控制面（9511, streamId=3）命令号全集。见 docs/PROTOCOL.md §3.3。
CMD_NAMES = {
    0x01: "鼠标",
    0x02: "多媒体按键",
    0x03: "键盘",
    0x05: "开副屏请求（已废弃，被控端忽略）",
    0x04: "副屏触摸",
    0x07: "游戏手柄",
    0x10: "模块开关（挂起/恢复被控）",
    0x20: "剪贴板 正向（PC → 对端）",
    0x21: "剪贴板 反向（对端 → PC）",
    0x22: "电源动作",
    0x25: "请求远程输入",
    0x26: "远程输入文本",
    0x27: "远程输入结束",
    0x28: "特殊键/组合键",
}


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self.rows: list[tuple[str, str, str]] = []   # (项, 结果, 状态)

    def error(self, msg: str) -> None:
        self.errors.append(msg)

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)

    def row(self, item: str, found: str, ok: bool) -> None:
        self.rows.append((item, found, "OK" if ok else "不一致"))


def read_text(path: str) -> str | None:
    if not os.path.isfile(path):
        return None
    for enc in ("utf-8", "utf-8-sig", "gbk"):
        try:
            with open(path, "r", encoding=enc) as f:
                return f.read()
        except UnicodeDecodeError:
            continue
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def eval_kotlin_int(expr: str) -> int | None:
    """把 Kotlin 常量表达式求值：支持 `4 * 1024 * 1024`、`1 shl 2`、`0x10`。"""
    e = expr.strip().rstrip("L")
    m = re.fullmatch(r"1\s+shl\s+(\d+)", e)
    if m:
        return 1 << int(m.group(1))
    e = e.replace("shl", "<<")
    if not re.fullmatch(r"[0-9a-fA-FxX\s*+()<>]+", e):
        return None
    try:
        return int(eval(e, {"__builtins__": {}}, {}))  # noqa: S307 - 输入已被字符白名单限制
    except Exception:
        return None


def parse_kotlin(path: str) -> dict[str, int]:
    """抽取 Kotlin ApxFrame 的 `const val NAME = expr`。"""
    out: dict[str, int] = {}
    text = read_text(path)
    if text is None:
        return out
    for m in re.finditer(r"const\s+val\s+([A-Z_0-9]+)\s*=\s*([^\n/]+)", text):
        name, expr = m.group(1), m.group(2).split("//")[0]
        v = eval_kotlin_int(expr)
        if v is not None:
            out[name] = v
    return out


def parse_cpp_frame(path: str) -> dict[str, int]:
    """抽取 shared/include/apx/frame.h 的 kFrame*/kStream*/kFlag* 常量与枚举。"""
    out: dict[str, int] = {}
    text = read_text(path)
    if text is None:
        return out
    for name, pat in (
        ("header", r"kFrameHeaderSize\s*=\s*(\d+)"),
        ("crc", r"kFrameCrcSize\s*=\s*(\d+)"),
        ("max_payload", r"kMaxFramePayload\s*=\s*([0-9uU\s*]+)"),
    ):
        m = re.search(pat, text)
        if m:
            digits = re.sub(r"[uU\s]", "", m.group(1))
            try:
                out[name] = int(eval(digits, {"__builtins__": {}}, {}))  # noqa: S307
            except Exception:
                pass
    for m in re.finditer(r"(kStream\w+|kFlag\w+)\s*=\s*(0x[0-9a-fA-F]+|\d+)", text):
        raw = m.group(2)
        out[m.group(1)] = int(raw, 16) if raw.lower().startswith("0x") else int(raw)
    return out


def cmd_tokens_kotlin_dispatch(path: str) -> set[int]:
    """Kotlin `when (cmd) { 0xNN -> ... }` 形式的命令号。"""
    text = read_text(path) or ""
    return {int(m.group(1), 16) for m in re.finditer(r"0x([0-9A-Fa-f]{2})\s*->", text)}


def cmd_tokens_kotlin_send(path: str) -> set[int]:
    """Kotlin 发送侧的命令号两种常见写法：
      · sendControl(byteArrayOf(0xNN.toByte(), ...))
      · 先建数组再填 body[0] = 0xNN.toByte()   （剪贴板这类变长载荷用这种）
    """
    text = read_text(path) or ""
    out = {int(m.group(1), 16) for m in re.finditer(r"byteArrayOf\(\s*0x([0-9A-Fa-f]{2})", text)}
    out |= {int(m.group(1), 16) for m in re.finditer(r"\[\s*0\s*\]\s*=\s*0x([0-9A-Fa-f]{2})", text)}
    return out


def cmd_tokens_cpp_dispatch(path: str) -> set[int]:
    """C++ 受控端 `cmd == 0xNN` 形式的命令号。"""
    text = read_text(path) or ""
    return {int(m.group(1), 16) for m in re.finditer(r"cmd\s*==\s*0x([0-9A-Fa-f]{2})", text)}


def cmd_tokens_cpp_send(path: str) -> set[int]:
    """C++ 控制端 `sendCmd(0xNN, ...)` 形式的命令号。"""
    text = read_text(path) or ""
    return {int(m.group(1), 16) for m in re.finditer(r"sendCmd\(\s*0x([0-9A-Fa-f]{2})", text)}


def cmd_tokens_cpp_client_reader(path: str) -> set[int]:
    """C++ **控制端读取器**处理对端回帧的写法：`payload[0] == 0xNN`。
    远程输入回传（0x25/0x26/0x27/0x28）与反向剪贴板（0x21）都只在这里处理 ——
    它们的方向是「对端 → 控制端」，不会出现在受控端的 dispatch 里。
    """
    text = read_text(path) or ""
    return {int(m.group(1), 16) for m in re.finditer(r"payload\[0\]\s*==\s*0x([0-9A-Fa-f]{2})", text)}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()
    root = os.path.abspath(args.repo)

    def p(*parts: str) -> str:
        return os.path.join(root, *parts)

    rep = Report()

    # v184 起帧定义收敛到 :shared 模块，app / tv 共用同一份 ApxFrame.kt
    # （结构上已不可能漂移，此处两侧指向同一文件，保留标签便于报告可读）
    shared_pkg = p("android", "shared", "src", "main", "java", "com", "allperiph", "shared")
    kotlin_files = {
        "app": os.path.join(shared_pkg, "proto", "ApxFrame.kt"),
        "tv": os.path.join(shared_pkg, "proto", "ApxFrame.kt"),
    }
    cpp_frame = p("shared", "include", "apx", "frame.h")
    ctrl_cpp = p("pc", "host", "src", "wireless", "ctrl9511.cpp")

    print("=" * 68)
    print("跨端协议一致性校验")
    print("=" * 68)

    # ---------- 1. 帧常量 ----------
    print("\n[1] 帧常量")
    cpp = parse_cpp_frame(cpp_frame)
    if not cpp:
        rep.warn(f"读不到 C++ 帧头（{os.path.relpath(cpp_frame, root)}）"
                 f"—— 子模块未初始化时属正常，本次跳过 C++ 侧校验")

    for label, path in kotlin_files.items():
        k = parse_kotlin(path)
        rel = os.path.relpath(path, root)
        if not k:
            rep.error(f"{label}: 读不到 {rel}")
            continue
        # 头长 / CRC / 载荷上限
        for key, kname, cname in (("header", "HEADER_SIZE", "header"),
                                  ("crc", "CRC_SIZE", "crc"),
                                  ("max_payload", "MAX_PAYLOAD", "max_payload")):
            got = k.get(kname)
            exp = EXPECT_FRAME[key]
            ok = got == exp and (cname not in cpp or cpp[cname] == exp)
            rep.row(f"{label}.{kname}", str(got), ok)
            if got != exp:
                rep.error(f"{label}.{kname} = {got}，期望 {exp}（{rel}）")
            elif cname in cpp and cpp[cname] != exp:
                rep.error(f"C++ 侧 {cname} = {cpp[cname]}，期望 {exp}（shared/include/apx/frame.h）")
        # streamId
        for name, exp in EXPECT_STREAMS.items():
            got = k.get(f"STREAM_{name.upper()}")
            ok = got == exp
            rep.row(f"{label}.STREAM_{name.upper()}", str(got), ok)
            if not ok:
                rep.error(f"{label}.STREAM_{name.upper()} = {got}，期望 {exp}（{rel}）")
        # flags
        for name, exp in EXPECT_FLAGS.items():
            got = k.get(f"FLAG_{name.upper()}")
            ok = got == exp
            rep.row(f"{label}.FLAG_{name.upper()}", str(got), ok)
            if not ok:
                rep.error(f"{label}.FLAG_{name.upper()} = {got}，期望 {exp}（{rel}）")

    if cpp:
        for name, exp in EXPECT_STREAMS.items():
            got = cpp.get(f"kStream{name.capitalize()}")
            ok = got == exp
            rep.row(f"cpp.kStream{name.capitalize()}", str(got), ok)
            if not ok:
                rep.error(f"C++ kStream{name.capitalize()} = {got}，期望 {exp}"
                          f"（shared/include/apx/frame.h）")

    # ---------- 2. 帧内偏移（写死的 8/12 必须两端一致）----------
    print("\n[2] 帧内偏移")
    for label, path in kotlin_files.items():
        text = read_text(path) or ""
        checks = (
            ("payloadLen@8", r"payloadLenAt\(buf: ByteArray, off: Int\): Int = getU32\(buf, off \+ 8\)"),
            ("seq@12", r"seqAt\(buf: ByteArray, off: Int\): Int = getU32\(buf, off \+ 12\)"),
        )
        for name, pat in checks:
            ok = re.search(pat, text) is not None
            rep.row(f"{label}.{name}", "写死正确" if ok else "偏移不同", ok)
            if not ok:
                rep.error(f"{label} 的 {name} 与协议不符（{os.path.relpath(path, root)}）")

    # ---------- 3. 控制面命令号：有人发就必须有人收 ----------
    print("\n[3] 控制面命令号（9511）")
    senders: dict[str, set[int]] = {}
    receivers: dict[str, set[int]] = {}
    app_pkg = p("android", "app", "src", "main", "java", "com", "allperiph")

    senders["app 控制端"] = cmd_tokens_kotlin_send(
        os.path.join(app_pkg, "wireless", "TvControllerClient.kt"))
    senders["app 被控应答"] = cmd_tokens_kotlin_send(
        os.path.join(app_pkg, "controlled", "TvControlServer.kt"))
    senders["pc 控制端"] = cmd_tokens_cpp_send(ctrl_cpp)
    receivers["app 被控"] = cmd_tokens_kotlin_dispatch(
        os.path.join(app_pkg, "controlled", "TvControlServer.kt"))
    # TV 被控端接收在 :shared 的 ControlServer（viAZL0 起 app/tv 共用该实现）
    receivers["tv 被控"] = cmd_tokens_kotlin_dispatch(
        os.path.join(shared_pkg, "net", "ControlServer.kt"))
    receivers["pc 被控"] = cmd_tokens_cpp_dispatch(ctrl_cpp)
    # 「对端 → 控制端」方向：回传帧只出现在控制端的读取器里
    receivers["pc 控制端(收)"] = cmd_tokens_cpp_client_reader(ctrl_cpp)
    receivers["app 控制端(收)"] = cmd_tokens_kotlin_dispatch(
        os.path.join(app_pkg, "wireless", "TvControllerClient.kt"))

    all_sent: set[int] = set()
    for name, cmds in senders.items():
        print(f"  发送 {name:12s}: {sorted(f'0x{c:02X}' for c in cmds)}")
        all_sent |= cmds
    all_recv: set[int] = set()
    for name, cmds in receivers.items():
        print(f"  接收 {name:12s}: {sorted(f'0x{c:02X}' for c in cmds)}")
        all_recv |= cmds

    for cmd in sorted(all_sent - all_recv):
        rep.error(f"命令 0x{cmd:02X}（{CMD_NAMES.get(cmd, '未知')}）有人发送，但三端都没有接收实现")
    for cmd in sorted(all_recv - all_sent):
        rep.warn(f"命令 0x{cmd:02X}（{CMD_NAMES.get(cmd, '未知')}）有接收实现，但脚本没找到发送方"
                 f"—— 可能是死代码，或发送用的是本脚本未覆盖的写法")
    for cmd in sorted(all_sent & all_recv):
        rep.row(f"cmd 0x{cmd:02X}", CMD_NAMES.get(cmd, "?"), True)

    # ---------- 4. 心跳 ----------
    print("\n[4] 心跳")
    ctrl_text = read_text(ctrl_cpp) or ""
    app_srv = read_text(os.path.join(app_pkg, "controlled", "TvControlServer.kt")) or ""
    ping_ok = "'p'.code" in app_srv and "sendCmd('p'" in ctrl_text
    rep.row("ping 'p'", "两端都在" if ping_ok else "缺失", ping_ok)
    if not ping_ok:
        rep.error("心跳命令 'p' 未在两端同时实现（PC 发 ping / 被控回 pong）")

    # ---------- 汇总 ----------
    print("\n" + "=" * 68)
    bad = sum(1 for r in rep.rows if r[2] != "OK")
    print(f"已校验 {len(rep.rows)} 项（帧常量 / 帧内偏移 / 命令号），其中不一致 {bad} 项")
    for item, found, status in rep.rows:
        if status != "OK":
            print(f"  x {item} = {found}")
    if rep.warnings:
        print(f"警告 {len(rep.warnings)} 条：")
        for w in rep.warnings:
            print(f"  ! {w}")
    if rep.errors:
        print(f"\n错误 {len(rep.errors)} 条：")
        for e in rep.errors:
            print(f"  x {e}")
        print("\n结果：不一致 —— 请让三端与 docs/PROTOCOL.md §3 对齐后重跑。")
        return 1
    print(f"结果：一致（检查项 {len(rep.rows)} 项，警告 {len(rep.warnings)} 条）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
