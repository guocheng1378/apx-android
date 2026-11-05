#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 M6-c（sunshine_launch 接线：面板/球 + 文档状态，2026-11-05，rev2 修文档锚点）：
  · pc/host/src/ui/panel_http.cpp —— include；screen.sunshine 补 childAlive/exe；
    新增 screen.sunshineStart / screen.sunshineStop 动作。
  · pc/host/src/ui/web_panel.cpp —— include；装配点填 sunshine_running / toggle_display。
  · docs/spikes/m6-sunshine-spike.md —— 两处 M6-c 状态改「已完成」。
rev2：§3 M6-c 行无 .cpp；§5 行在文件尾无换行（锚点不带尾 \n）。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    # ---- 面板：include ----
    ("pc/host/src/ui/panel_http.cpp", [
        (T('#include "apxpc/screen/sunshine_detect.hpp"'),
         T('#include "apxpc/screen/sunshine_detect.hpp"',
           '#include "apxpc/screen/sunshine_launch.hpp"   // M6-c：面板起/停 Sunshine 子进程（可复用模块）'), 1),
        # ---- 面板：status 扩字段 + 两个起/停动作 ----
        (T('        v["running"]   = d.running;',
           '        v["httpsPort"] = static_cast<double>(d.httpsPort);',
           '        v["url"] = apxpc::screen::sunshineUrl(d);',
           '        return postResponse(p, id, okResp(v));',
           '    }'),
         T('        v["running"]   = d.running;',
           '        v["childAlive"] = apxpc::screen::spikeChildAlive(apxpc::screen::defaultSpikePidPath());',
           '        v["exe"] = apxpc::screen::findSunshineExecutable("");',
           '        v["httpsPort"] = static_cast<double>(d.httpsPort);',
           '        v["url"] = apxpc::screen::sunshineUrl(d);',
           '        return postResponse(p, id, okResp(v));',
           '    }',
           '    // —— M6-c：Sunshine 子进程起/停（复用 sunshine_launch；日志/pid 落临时目录）——',
           '    if (name == "screen.sunshineStart") {',
           '        const std::string pidFile = apxpc::screen::defaultSpikePidPath();',
           '        if (apxpc::screen::spikeChildAlive(pidFile))',
           '            return postResponse(p, id, failResp("已有 spike 子进程在跑"));',
           '        const std::string exe = apxpc::screen::findSunshineExecutable("");',
           '        if (exe.empty())',
           '            return postResponse(p, id, failResp("未找到 Sunshine 可执行文件（见 docs/spikes/m6-sunshine-spike.md）"));',
           '        const auto r = apxpc::screen::startSunshineProcess(',
           '            exe, {}, apxpc::screen::defaultSpikeLogPath(), pidFile);',
           '        if (!r.ok) return postResponse(p, id, failResp(r.error));',
           '        Json vp = Json::makeObject();',
           '        vp["pid"] = static_cast<double>(r.pid);',
           '        vp["log"] = apxpc::screen::defaultSpikeLogPath();',
           '        return postResponse(p, id, okResp(vp));',
           '    }',
           '    if (name == "screen.sunshineStop") {',
           '        const bool ok = apxpc::screen::stopSunshineProcess(apxpc::screen::defaultSpikePidPath());',
           '        return postResponse(p, id, ok ? okResp() : failResp("没有可停止的子进程"));',
           '    }'), 1),
    ]),
    # ---- 球：include + 装配点两钩子 ----
    ("pc/host/src/ui/web_panel.cpp", [
        (T('#include "../ball/registry.hpp"'),
         T('#include "../ball/registry.hpp"',
           '#include "apxpc/screen/sunshine_launch.hpp"   // M6-c：球侧 Sunshine 子进程钩子'), 1),
        (T('        ha.connected = [&bundle] { return bundle.session && bundle.session->connected(); };',
           '        ha.clipboard_pending = [] { return false; };',
           '        apxpc::ball::installDefaultActions(ha);'),
         T('        ha.connected = [&bundle] { return bundle.session && bundle.session->connected(); };',
           '        ha.clipboard_pending = [] { return false; };',
           '        // M6-c：副屏项装配——enabled=在跑才可点（registry 使能条件不动），run=起/停子进程',
           '        ha.sunshine_running = [] { return apxpc::screen::spikeChildAlive(apxpc::screen::defaultSpikePidPath()); };',
           '        ha.toggle_display = []() -> bool {',
           '            const std::string pidFile = apxpc::screen::defaultSpikePidPath();',
           '            if (apxpc::screen::spikeChildAlive(pidFile)) {',
           '                (void)apxpc::screen::stopSunshineProcess(pidFile);',
           '                return false;',
           '            }',
           '            const std::string exe = apxpc::screen::findSunshineExecutable("");',
           '            if (exe.empty()) return false;',
           '            const auto r = apxpc::screen::startSunshineProcess(',
           '                exe, {}, apxpc::screen::defaultSpikeLogPath(), pidFile);',
           '            return r.ok;',
           '        };',
           '        apxpc::ball::installDefaultActions(ha);'), 1),
    ]),
    # ---- 文档：两处 M6-c 状态（§3 行无 .cpp；§5 行 EOF 无尾换行，锚点不带尾 \n）----
    ("docs/spikes/m6-sunshine-spike.md", [
        (T('  `docs/spikes/m6-sunshine-spike.md` §4 实测步骤照旧；**真机实测未执行**。',
           '- **M6-c（进行中）**：`sunshine_launch` 扩成可复用模块（hpp 声明 + cpp 实现双文件，`start/stop/status` 全模块化，供后续面板/球接线复用）。'),
         T('  `docs/spikes/m6-sunshine-spike.md` §4 实测步骤照旧；**真机实测未执行**。',
           '- **M6-c（已完成，2026-11-05）**：面板/球接线已入库 —— `panel_http.cpp` 新增 `screen.sunshineStart` /',
           '  `screen.sunshineStop` 动作并给 `screen.sunshine` 回包补 `childAlive`/`exe`；`web_panel.cpp` 装配点填',
           '  `sunshine_running`（spikeChildAlive）与 `toggle_display`（start/stop）两钩子（registry 使能条件不动）。',
           '  `sunshine_launch` 为面板/球/apxhost 三处复用的可复用模块。**真机实测仍未执行**。'), 1),
        ('  sunshine_detect 的 running 证据为准（端口有人听 = 在跑）。\n'
         '- **M6-c（进行中）**：`sunshine_launch.cpp` 扩成可复用模块（hpp 声明 + cpp 实现双文件，`start/stop/status` 全模块化，供后续面板/球接线复用）。',
         '  sunshine_detect 的 running 证据为准（端口有人听 = 在跑）。\n'
         '- **M6-c（已完成，2026-11-05）**：接线入库；§4 真机实测清单仍待用户在 PC + 手机执行回传。',
         1),
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
