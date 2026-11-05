#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 M6-a（Sunshine 推屏 spike 第一阶段，2026-11-05）：
  · pc/host/CMakeLists.txt —— APXPC_COMMON_SRC 挂 sunshine_launch.cpp（core）。
  · pc/host/src/host_main.cpp —— sunshine-spike 子命令（start/stop/status）+ 用法行 + include。
脚手架本体三个文件是**新建**（无锚点），已随批次直接 push：
  pc/host/include/apxpc/screen/sunshine_launch.hpp
  pc/host/src/screen/sunshine_launch.cpp
  docs/spikes/m6-sunshine-spike.md
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    # ---- CMake：core 源列表（detect 行 + 收尾 ) 一起锚定，全文唯一）----
    ("pc/host/CMakeLists.txt", [
        (T(
            '  "${APXPC_ROOT}/src/screen/sunshine_detect.cpp"',
            ")",
        ), T(
            '  "${APXPC_ROOT}/src/screen/sunshine_detect.cpp"',
            "  # Sunshine 子进程托管（M6-a spike 脚手架）：起/停/查，日志与 pid 落临时目录。",
            "  # 与 detect 同进 core（apxhost 的 sunshine-spike 子命令用）；不进面板 TU。",
            '  "${APXPC_ROOT}/src/screen/sunshine_launch.cpp"',
            ")",
        ), 1),
    ]),
    # ---- host_main.cpp ----
    ("pc/host/src/host_main.cpp", [
        (T(
            "#include <apxpc/ui/panel_http.hpp>",
        ), T(
            "#include <apxpc/ui/panel_http.hpp>",
            "#include <apxpc/screen/sunshine_detect.hpp>    // spike status：只读检测（mac/Linux）",
            "#include <apxpc/screen/sunshine_launch.hpp>    // spike 脚手架：Sunshine 子进程托管（M6-a）",
        ), 1),
        (T(r'        "  apxhost --help\n"'), T(
            r'        "  apxhost sunshine-spike start [exe路径] [秒数]\n"',
            r'        "                     M6 spike：把 Sunshine 当子进程拉起（日志/pid 落临时目录），\n"',
            r'        "                     到时停止并收尸；stop 停记录在案的子进程，status 只读检测\n"',
            r'        "  apxhost --help\n"',
        ), 1),
        (T(
            "int interactive() {",
        ), T(
            "// --------------------------------------- Sunshine 推屏 spike（M6-a 子进程脚手架）----",
            "// 把 Sunshine 当子进程拉起 / 停掉 / 查状态：M6 第一阶段只验证「托管这条路跑得通」，",
            "// 不碰任何 Moonlight 协议（GPLv3 不内嵌，ROADMAP §2.5 与 docs/INTEGRATIONS.md §1）。",
            "// 真机实测步骤与选型评估见 docs/spikes/m6-sunshine-spike.md。",
            "static int runSunshineSpikeStart(const std::string& exeArg, int seconds) {",
            "    const std::string exe = apxpc::screen::findSunshineExecutable(exeArg);",
            "    if (exe.empty()) {",
            "        std::fprintf(stderr,",
            "                     \"未找到 Sunshine 可执行文件%s。先安装，或显式给路径：\"",
            r'                     "apxhost sunshine-spike start <exe路径>（指引见 docs/spikes/m6-sunshine-spike.md）\n",',
            "                     exeArg.empty() ? \"\" : (\"：\" + exeArg).c_str());",
            "        return 1;",
            "    }",
            "    const std::string pidFile = apxpc::screen::defaultSpikePidPath();",
            "    const std::string logFile = apxpc::screen::defaultSpikeLogPath();",
            "    if (apxpc::screen::spikeChildAlive(pidFile)) {",
            r'        std::fputs("已有 spike 子进程在跑（先 apxhost sunshine-spike stop）。\n", stderr);',
            "        return 2;",
            "    }",
            r'    std::printf("启动 Sunshine 子进程：\n  exe   %s\n  日志  %s\n", exe.c_str(), logFile.c_str());',
            "    const auto r = apxpc::screen::startSunshineProcess(exe, {}, logFile, pidFile);",
            "    if (!r.ok) {",
            r'        std::printf("启动失败：%s\n", r.error.c_str());',
            "        return 1;",
            "    }",
            r'    std::printf("已拉起（pid %ld）。\n", r.pid);',
            "",
            "    bool sawDead = false;",
            "    if (seconds > 0) {",
            r'        std::printf("轮询 %d 秒……\n", seconds);',
            "        const auto t0 = std::chrono::steady_clock::now();",
            "        while (true) {",
            "            std::this_thread::sleep_for(std::chrono::milliseconds(500));",
            "            const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(",
            "                                std::chrono::steady_clock::now() - t0).count();",
            "            if (!apxpc::screen::spikeChildAlive(pidFile)) { sawDead = true; break; }",
            r'            std::printf("\r[%4llds] 子进程存活   ", static_cast<long long>(ms / 1000));',
            "            std::fflush(stdout);",
            "            if (ms >= static_cast<long long>(seconds) * 1000) break;",
            "        }",
            "        std::puts(\"\");",
            "    } else {",
            "        std::puts(\"按 Enter 停止……\");",
            "        std::cin.get();",
            "    }",
            "    if (sawDead) {",
            "        std::puts(\"子进程已自行退出（原因见日志）。\");",
            "        apxpc::screen::forgetSpikeChild(pidFile);   // 只清记录，不拿可能已复用的 pid 去杀",
            "        return 0;",
            "    }",
            "    const bool stopped = apxpc::screen::stopSunshineProcess(pidFile);",
            "    std::puts(stopped ? \"已停止 spike 子进程。\" : \"没有需要停止的子进程（可能刚退出）。\");",
            "    return 0;",
            "}",
            "",
            "static int runSunshineSpikeStop() {",
            "    const bool ok = apxpc::screen::stopSunshineProcess(apxpc::screen::defaultSpikePidPath());",
            "    std::puts(ok ? \"已停止 spike 子进程。\" : \"没有可停止的子进程（无记录或已退出）。\");",
            "    return ok ? 0 : 1;",
            "}",
            "",
            "static int runSunshineSpikeStatus() {",
            "    const std::string pidFile = apxpc::screen::defaultSpikePidPath();",
            r'    std::printf("spike 子进程：%s（记录 %s）\n",',
            "                apxpc::screen::spikeChildAlive(pidFile) ? \"在跑\" : \"无\", pidFile.c_str());",
            "    const std::string exe = apxpc::screen::findSunshineExecutable(\"\");",
            r'    std::printf("Sunshine 可执行文件：%s\n", exe.empty() ? "未找到" : exe.c_str());',
            "#if !defined(_WIN32)",
            "    const auto s = apxpc::screen::sunshineStatus();",
            r'    std::printf("只读检测：installed=%s running=%s https端口=%u\n",',
            "                s.installed ? \"yes\" : \"no\", s.running ? \"yes\" : \"no\",",
            "                static_cast<unsigned>(s.httpsPort));",
            "#else",
            "    std::puts(\"只读检测仅 mac/Linux（Windows 自研副屏已交付，ROADMAP §2.5）。\");",
            "#endif",
            "    return 0;",
            "}",
            "int interactive() {",
        ), 1),
        (T(
            "            return runLocalSendSend(argv[2], files);",
            "        }",
        ), T(
            "            return runLocalSendSend(argv[2], files);",
            "        }",
            "",
            "        // ---- Sunshine 推屏 spike（M6-a）：子进程托管脚手架（实测见 docs/spikes/m6-sunshine-spike.md）----",
            "        if (cmd == \"sunshine-spike\") {",
            "            const std::string sub = argc > 2 ? argv[2] : \"status\";",
            "            std::string exeArg = argc > 3 ? argv[3] : \"\";",
            "            int secs = 0;",
            "            // 「start 60」（只给秒数）与「start <exe路径> 60」两种写法都吃",
            "            if (!exeArg.empty() &&",
            "                exeArg.find_first_not_of(\"0123456789\") == std::string::npos) {",
            "                secs = std::atoi(exeArg.c_str());",
            "                exeArg.clear();",
            "            } else if (argc > 4) {",
            "                secs = std::atoi(argv[4]);",
            "            }",
            "            if (sub == \"start\") return runSunshineSpikeStart(exeArg, secs);",
            "            if (sub == \"stop\") return runSunshineSpikeStop();",
            "            if (sub == \"status\") return runSunshineSpikeStatus();",
            r'            std::fputs("用法：apxhost sunshine-spike start [exe路径] [秒数] | stop | status\n", stderr);',
            "            return 1;",
            "        }",
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
                errors.append(f"{path} edit#{i}: count={n} expect={expected}\\n---anchor---\\n{old[:300]}")
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
