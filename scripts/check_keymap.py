#!/usr/bin/env python3
"""键映射覆盖率关卡（issue #52 第 13 条「按键映射不正确」的回归网）。

背景：PC 侧三份注入器各自维护一张 HID usage → 宿主键码表：
    pc/host/src/wireless/input_injector_win.cpp   usageToVk()
    pc/host/src/wireless/input_injector_lin.cpp   hidToLinuxKey()
    pc/host/src/wireless/input_injector_mac.mm    hidToMacKey()

这三张表**没有编译器管**：漏一条 case 不会报错，只会落 default（win/lin 返回 0、
mac 返回 -1）被静默丢弃。用户看到的是「按了没反应」，日志里一片干净——本轮就是
这么漏掉 PrtSc / ScrLk / Pause / 菜单 / 键盘 Power / 小键盘 = / 小键盘 1–9，
macOS 那边更是把小键盘整段错位了一格。所以这里做四件事：

  1. 覆盖率：遥控器/键盘会上报的 HID usage 全表，是否每端都有映射（或已登记豁免）；
  2. 重复 case：同一 usage 在同一张表里出现两次 → C++ 直接编译失败（本轮真踩到）；
  3. 死代码：被前面的范围分支提前拦下的 case，永远执行不到（往往还语义错）；
  4. 小键盘锚点：macOS kVK 值逐键核对，防止再次整段错位。

只做静态文本分析，不需要编译器。退出码非 0 = 有违规。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WIN = ROOT / "pc/host/src/wireless/input_injector_win.cpp"
LIN = ROOT / "pc/host/src/wireless/input_injector_lin.cpp"
MAC = ROOT / "pc/host/src/wireless/input_injector_mac.mm"

# 遥控器 / 键盘侧会上报的 HID usage 全集（键盘页里有实际按键的那些）。
# 分成「必须映射」与「已登记豁免」两档：豁免必须写明理由，不许默默放过。
REQUIRED = {
    0x04: "A", 0x05: "B", 0x06: "C", 0x07: "D", 0x08: "E", 0x09: "F", 0x0A: "G",
    0x0B: "H", 0x0C: "I", 0x0D: "J", 0x0E: "K", 0x0F: "L", 0x10: "M", 0x11: "N",
    0x12: "O", 0x13: "P", 0x14: "Q", 0x15: "R", 0x16: "S", 0x17: "T", 0x18: "U",
    0x19: "V", 0x1A: "W", 0x1B: "X", 0x1C: "Y", 0x1D: "Z",
    0x1E: "1", 0x1F: "2", 0x20: "3", 0x21: "4", 0x22: "5", 0x23: "6", 0x24: "7",
    0x25: "8", 0x26: "9", 0x27: "0", 0x28: "Enter", 0x29: "Esc", 0x2A: "Backspace",
    0x2B: "Tab", 0x2C: "Space", 0x2D: "-", 0x2E: "=", 0x2F: "[", 0x30: "]", 0x31: "\\",
    0x33: ";", 0x34: "'", 0x35: "`", 0x36: ",", 0x37: ".", 0x38: "/", 0x39: "CapsLock",
    0x3A: "F1", 0x3B: "F2", 0x3C: "F3", 0x3D: "F4", 0x3E: "F5", 0x3F: "F6",
    0x40: "F7", 0x41: "F8", 0x42: "F9", 0x43: "F10", 0x44: "F11", 0x45: "F12",
    0x49: "Insert", 0x4A: "Home", 0x4B: "PageUp", 0x4C: "Delete", 0x4D: "End",
    0x4E: "PageDown", 0x4F: "Right", 0x50: "Left", 0x51: "Down", 0x52: "Up",
    0x53: "NumLock", 0x54: "KP /", 0x55: "KP *", 0x56: "KP -", 0x57: "KP +",
    0x58: "KP Enter", 0x59: "KP 1", 0x5A: "KP 2", 0x5B: "KP 3", 0x5C: "KP 4",
    0x5D: "KP 5", 0x5E: "KP 6", 0x5F: "KP 7", 0x60: "KP 8", 0x61: "KP 9",
    0x62: "KP 0", 0x63: "KP .", 0x67: "KP =",
    # —— issue #52 第 13 条这批（本轮补齐）——
    0x46: "PrtSc", 0x47: "ScrLk", 0x48: "Pause", 0x65: "菜单/Application",
    0x66: "键盘 Power",
    # —— 次要但同样不该静默丢（0x32 = 非美 #~，0x64 = 非美 \|）——
    0x32: "非美 #~", 0x64: "非美 \\|",
}

# 已登记豁免：端 → usage → 理由。
EXEMPT = {
    "win": {
        0x32: "Windows 无独立 #~ 键，表里显式写 return 0（有意不映射，非漏项）",
    },
    "lin": {
        0x32: "Linux 无独立 #~ 键，表里显式写 return 0（有意不映射，非漏项）",
    },
    "mac": {
        0x65: "「菜单」不走键码表：postKey 里特判 → 发右键事件（刻意的，不在 hidToMacKey 出现）",
        0x66: "macOS 无键盘 Power 的公开 CGKeyCode，不做猜测（保持 default，日志已升 WARN）",
    },
}

# 各端「范围分支」预先吃掉的 usage 区间：这些 case 够不着，写了就是死代码。
RANGE_BRANCHES = {
    "win": [(0x04, 0x1D, "'A' + offset"), (0x1E, 0x26, "'1' + offset"),
            (0x3A, 0x45, "VK_F1 + offset"), (0x59, 0x61, "VK_NUMPAD1 + offset")],
    "lin": [(0x04, 0x1D, "KEY_A + offset"), (0x1E, 0x27, "KEY_1 + offset"),
            (0x3A, 0x45, "KEY_F1 + offset")],
    "mac": [],
}

# 表里写着、但值表示「不映射」的假映射（win/lin 的 0 与 mac 的 -1 都会被静默丢弃）。
DEAD_VALUES = {"0", "-1"}

# macOS 小键盘锚点：HID usage → kVK，对着 Apple HIToolbox/Events.h 核对。
# 就是本轮被「整段错位一格」坑掉的地方，钉死它。
MAC_KP_ANCHORS = {
    0x53: 0x4A,  # KeypadClear
    0x54: 0x4B,  # KeypadDivide
    0x55: 0x43,  # KeypadMultiply
    0x56: 0x4E,  # KeypadMinus
    0x57: 0x45,  # KeypadPlus
    0x58: 0x4C,  # KeypadEnter
    0x59: 0x53,  # Keypad1
    0x5A: 0x54,  # Keypad2
    0x5B: 0x55,  # Keypad3
    0x5C: 0x56,  # Keypad4
    0x5D: 0x57,  # Keypad5
    0x5E: 0x58,  # Keypad6
    0x5F: 0x59,  # Keypad7
    0x60: 0x5B,  # Keypad8
    0x61: 0x5C,  # Keypad9
    0x62: 0x52,  # Keypad0
    0x63: 0x41,  # KeypadDecimal
    0x67: 0x51,  # KeypadEquals
}

problems = []


def strip_comments(text):
    """去掉 // 行注释，避免注释里举的例子被当成真映射。"""
    out = []
    for line in text.splitlines():
        i = line.find("//")
        out.append(line[:i] if i >= 0 else line)
    return "\n".join(out)


def extract_fn(text, start_marker):
    """截取一个函数体（按大括号配对），只喂给正则用。"""
    i = text.find(start_marker)
    if i < 0:
        return None
    j = text.find("{", i)
    if j < 0:
        return None
    depth, k = 0, j
    while k < len(text):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return text[j:k + 1]
        k += 1
    return None


def parse_cases(body):
    """case 0xNN: return <expr>; → {usage: [expr,...]}（保留重复以便报错）"""
    hits = {}
    for m in re.finditer(r"case\s+0x([0-9A-Fa-f]{2})\s*:\s*return\s+([^;]+);", body):
        usage = int(m.group(1), 16)
        hits.setdefault(usage, []).append(m.group(2).strip())
    return hits


def in_range(usage, ranges):
    return any(lo <= usage <= hi for lo, hi, _ in ranges)


def check_end(name, path, fn_marker, exempt):
    if not path.exists():
        problems.append(f"[{name}] 源文件不存在：{path.relative_to(ROOT)}")
        return None
    raw = path.read_text(encoding="utf-8")
    body = extract_fn(strip_comments(raw), fn_marker)
    if body is None:
        problems.append(f"[{name}] 找不到函数 {fn_marker}")
        return None
    cases = parse_cases(body)

    # 1) 重复 case 标签（C++ 编译错误）
    for usage, exprs in sorted(cases.items()):
        if len(exprs) > 1:
            problems.append(
                f"[{name}] usage 0x{usage:02X} 有 {len(exprs)} 条 case（重复标签 → 编译失败）："
                + " / ".join(exprs))

    # 2) 被范围分支吃掉、够不着的 case（死代码，且往往语义错）
    for usage in sorted(cases):
        if in_range(usage, RANGE_BRANCHES[name]):
            problems.append(
                f"[{name}] usage 0x{usage:02X} 的 case 在范围分支之后，永远执行不到（死代码）："
                + " / ".join(cases[usage]))

    # 3) 覆盖率。注意「表里有这条 case、但值是不映射」不算覆盖（win/lin 的 return 0）。
    missing = []
    for usage, label in sorted(REQUIRED.items()):
        live = [e for e in cases.get(usage, []) if e not in DEAD_VALUES]
        if live or in_range(usage, RANGE_BRANCHES[name]):
            continue
        if usage in exempt:
            continue
        missing.append(f"0x{usage:02X}({label})")
    if missing:
        problems.append(f"[{name}] 缺映射（会落 default 静默丢弃）：" + "、".join(missing))

    # 4) 豁免表里的 usage 不该同时又真映射了（豁免已过时 → 清理）
    for usage in sorted(exempt):
        live = [e for e in cases.get(usage, []) if e not in DEAD_VALUES]
        if live or in_range(usage, RANGE_BRANCHES[name]):
            problems.append(
                f"[{name}] usage 0x{usage:02X} 已登记豁免但表里实际有有效映射 —— 豁免过时，请清理")
    return cases


win_cases = check_end("win", WIN, "int usageToVk(", EXEMPT["win"])
lin_cases = check_end("lin", LIN, "int hidToLinuxKey(", EXEMPT["lin"])
mac_cases = check_end("mac", MAC, "int hidToMacKey(", EXEMPT["mac"])

# 5) macOS 小键盘锚点逐键核对
if mac_cases is not None:
    for usage, kvk in sorted(MAC_KP_ANCHORS.items()):
        got = mac_cases.get(usage)
        if not got:
            problems.append(f"[mac] 小键盘 0x{usage:02X} 无映射（预期 kVK 0x{kvk:02X}）")
            continue
        try:
            val = int(got[0], 16) if got[0].lower().startswith("0x") else int(got[0], 10)
        except ValueError:
            continue  # 走枚举常量名就不追问数值
        if val != kvk:
            problems.append(
                f"[mac] 小键盘 0x{usage:02X} 期望 kVK 0x{kvk:02X}，实际 0x{val:02X}"
                f"（整段错位的典型症状）")

if problems:
    print("键映射关卡 FAIL：")
    for p in problems:
        print("  - " + p)
    sys.exit(1)

print(f"键映射关卡 OK：win {len(win_cases)} / lin {len(lin_cases)} / mac {len(mac_cases)} 条映射，"
      f"必需 usage {len(REQUIRED)} 项全覆盖（豁免 {sum(len(v) for v in EXEMPT.values())} 项已登记理由），"
      f"mac 小键盘 {len(MAC_KP_ANCHORS)} 键锚点一致。")