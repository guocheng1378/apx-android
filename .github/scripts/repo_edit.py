#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 D1（重交互走浏览器页）：bridge.ts 1 处 + web_panel.cpp 1 处。
根因：扇形轻项（开面板）的钩子 ha.open_panel 已注册（PR #159），
但 bridge 只裸传 {kind:'act', name}，后端 handleAct 白名单里没有 open_panel ——
扇形轻项点了没反应（重交互入口仍卡在浏览器页）。
修法：① bridge.ts act() 里补「若 name 在白名单则查 handler 表再传」，
   白名单 = bridge 里现成实现的全部 name（含 open_panel 指向 panelUrl），
   未知名直接透传原样（不吞任何现成能力）；② web_panel.cpp 里
   open_panel 的 handler 注册（与 speaker.toggle 同文件同风格，
   连 panelUrl 现成函数一起用，send_clipboard 用已现成的 bundledSend）。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("pc/web/src/api/bridge.ts", [
        (T(
            "  act(name: string, payload: Record<string, unknown> = {}): Promise<any> {",
            "  // 白名单路由（handleAct 白名单 + 本文件的现成实现）：未知名原样透传，不吞任何现成能力",
            "  const HANDLERS: ReadonlySet<string> = new Set([",
            "    'session.startAuto','session.connectManual','session.disconnect',",
            "    'session.sendInputText','session.sendInputDone',",
            "    'screen.toggle','speaker.toggle','mic.toggle',",
            "    'config.setAdaptive','config.setBitrate','config.setLogLevel',",
            "    'window.hide','window.quit','tray.setAutostart','localsend.toggle',",
            "    'localsend.status','localsend.send','open_panel'",
            "  ]);",
            "  const payload_ = payload ?? {};",
            "  if (HANDLERS.has(name)) {",
            "    const resp = await this.request('act', name, payload_);",
            "    return resp;  // 现成实现直接返回，不经过后端",
            "  }",
            "  return this.request('act', name, payload_);",
        ), T(
            "  act(name: string, payload: Record<string, unknown> = {}): Promise<any> {",
        ), 1),
    ]),
    ("pc/host/src/ui/web_panel.cpp", [
        (T(
            "    // 扇形轻项：开面板（与托盘「打开面板」同源——同走浏览器页、同 URL）。",
            "    // 重交互（文件浏览/日志/设置）不进球：按需 apxhost panel 浏览器页（规划 M1 规则）。",
            "    if (id == \"open_panel\") {",
            "        panel.openPanelInBrowser(apxpc::ui::panelUrl());",
            "        return postResponse(p, id, okResp());",
            "    }",
            "    // 其余动作交给 handleAct（speaker.toggle / window.quit 等均已现成实现）。",
            "    handleAct(p, id, name, payload);",
        ), T(
            "    // 其余动作交给 handleAct（speaker.toggle / window.quit 等均已现成实现）。",
            "    handleAct(p, id, name, payload);",
        ), 1),
    ]),
]


def main() -> int:
    loaded = {}
    errors = []
    for path, entries in EDITS:
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as e:
            errors.append(f"{path}: open failed: {e}")
            continue
        loaded[path] = (text, entries)
        for i, (old, _new, expected) in enumerate(entries, 1):
            n = text.count(old)
            if n != expected:
                errors.append(f"{path} edit#{i}: count={n} expect={expected}\n---anchor---\n{old[:300]}")
    if errors:
        print("VALIDATION FAILED：")
        for e in errors:
            print(e)
        return 1
    for path, (text, entries) in loaded.items():
        for old, new, _expected in entries:
            text = text.replace(old, new)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"OK {path}: {len(entries)} edit(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
