#!/usr/bin/env python3
"""repo-edit：大文件锚点替换编辑器。

批 M4-b（mac 球四件套）：ball_win32.hpp / CMake / desktop_main 共 4 处。
根因：球 v2 全在 #if _WIN32 内，mac 空 TU——球在 mac 从不出现（M4-b）。
修法：① hpp #else 补 createBall/destroyBall 最小声明；② CMake if(APHERE APPLE)
块照 tray_mac.mm 加 ball_mac.mm（ARC/AppKit 继承）；③ desktop_main include 区
mac 专属引入 registry/ball 头；④ runWebPanelHttp 调用前装配（四钩子同源托盘）
+ 退出处 destroyBall（与 Win 批 B 同生命周期语义）。新文件 ball_mac.mm 随本批同 commit。
两阶段：①按期望次数全量校验；②全过才写盘。
"""
import sys


def T(*lines):
    return "\n".join(lines) + "\n"


EDITS = [
    ("pc/host/src/ball/ball_win32.hpp", [
        (T(
            "} // namespace ball",
            "} // namespace apxpc",
            "",
            "#endif // _WIN32",
        ), T(
            "} // namespace ball",
            "} // namespace apxpc",
            "",
            "#else // !_WIN32 —— mac 球（ball_mac.mm）只需生命周期 API；渲染全在实现内",
            "",
            "namespace apxpc {",
            "namespace ball {",
            "",
            "bool createBall();",
            "void destroyBall();",
            "",
            "} // namespace ball",
            "} // namespace apxpc",
            "",
            "#endif // _WIN32",
        ), 1),
    ]),
    ("pc/host/CMakeLists.txt", [
        (T(
            "  list(APPEND APXPC_SERVICE_SRC \"${APXPC_ROOT}/src/tray/tray_mac.mm\")",
        ), T(
            "  list(APPEND APXPC_SERVICE_SRC \"${APXPC_ROOT}/src/tray/tray_mac.mm\")",
            "  # mac 球 v2（M4-b）：AppKit 同窗渲染（球+扇形），registry/fx 复用共享层。",
            "  # ball_win32.cpp 在 mac 为整文件 #if _WIN32 空 TU，无符号冲突；ARC/AppKit 选项继承本块。",
            "  list(APPEND APXPC_SERVICE_SRC \"${APXPC_ROOT}/src/ball/ball_mac.mm\")",
        ), 1),
    ]),
    ("pc/host/src/desktop_main.cpp", [
        (T(
            "#include \"apxpc/tray/tray_win32.hpp\"",
        ), T(
            "#include \"apxpc/tray/tray_win32.hpp\"",
            "#if defined(__APPLE__)",
            "#include \"ball/registry.hpp\"",
            "#include \"ball/ball_win32.hpp\"",
            "#endif",
        ), 1),
        (T(
            "        panelRc.store(apxpc::ui::runWebPanelHttp(bundle, {}));",
        ), T(
            "#if defined(__APPLE__)",
            "        {",
            "            // M4-b mac 球装配（web_panel.cpp 批 B 的 mac 同款）：钩子与托盘同源，",
            "            // 空钩子/回 false = registry 规则灰显（send_clipboard mac 二期先灰；speaker 待媒体栈）。",
            "            apxpc::ball::HostActions ha;",
            "            ha.open_panel = [] { openPanelInBrowser(apxpc::ui::panelUrl()); };",
            "            ha.exit_app = [] { apxpc::ui::requestPanelQuit(); };",
            "            ha.send_clipboard = [] { return false; };",
            "            ha.connected = [&bundle] { return bundle.session && bundle.session->connected(); };",
            "            ha.clipboard_pending = [] { return false; };",
            "            apxpc::ball::installDefaultActions(ha);",
            "            if (!apxpc::ball::createBall()) APX_LOGI(\"mac ball 创建失败（面板/托盘不受影响）\");",
            "        }",
            "#endif",
            "        panelRc.store(apxpc::ui::runWebPanelHttp(bundle, {}));",
            "        apxpc::ball::destroyBall();  // M4-b：面板收尾后收球（与 Win 批 B 同生命周期语义）",
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
