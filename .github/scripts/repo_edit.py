#!/usr/bin/env python3
"""repo-edit：批 U-1R（PC 更新通道修复重跑，2026-11-06）。

上一轮（U-1）把 edit1 的新函数插到了 notifyFileReceived **函数体中间**
（replace(anchor, insert+anchor) 方向写反：插在锚点前，而锚点含 postEvent 行与收尾
大括号，于是函数定义被夹在 d["path"] 与 postEvent 之间 → 函数套函数，pc job 编译失败）。

本轮：
  ① 若检测到 insert 紧贴 anchor 之前（错位形态）→ 先剥离还原；
  ② 按 placement=after 重插到 anchor 之后（notifyFileReceived 收尾之后的顶层位置）；
  ③ edit2（app.openUrl act，插在 session.connectManual 前）上轮位置正确，幂等跳过；
  ④ 全部在内存完成校验/修复，最后一次写盘（两阶段精神不变）。
"""
import sys

PATH = "pc/host/src/ui/web_panel.cpp"

EDITS = [
    {
        "id": "notifyUpdateAvailable",
        "placement": "after",   # 新函数必须在 notifyFileReceived 收尾之后（顶层）
        "anchor": '    postEvent(p, std::string("on-file-received"), d);\n}\n',
        "insert": (
            '\n// ———————— 更新通道（v223.29.16，desktop_main 更新检查线程调用） ————————\n'
            '// 查到 GitHub releases 有新版 → 推 on-update-available 事件，前端顶部横幅展示；\n'
            '// 点「查看」回 app.openUrl act（白名单放行本仓库 release 页）。\n'
            '// PostWebMessageAsJson 线程安全（同 notifyFileReceived 的约定），检查线程可直接调。\n'
            'void notifyUpdateAvailable(const std::string& latestTag, const std::string& currentTag,\n'
            '                           const std::string& url) {\n'
            '    Panel* p = g_panel;\n'
            '    if (!p) {\n'
            '        APX_LOGW("发现新版本 {} 但面板未就绪（当前 {}）", latestTag.c_str(), currentTag.c_str());\n'
            '        return;\n'
            '    }\n'
            '    Json d = Json::makeObject();\n'
            '    d["latest"] = latestTag;\n'
            '    d["current"] = currentTag;\n'
            '    d["url"] = url;\n'
            '    postEvent(p, std::string("on-update-available"), d);\n'
            '}\n'
        ),
    },
    {
        "id": "app.openUrl act",
        "placement": "before",  # act 分发链上，插在 connectManual 之前
        "anchor": '    if (name == "session.connectManual" && b.session) {',
        "insert": (
            '    // ———————— 更新通道（v223.29.16）：前端横幅点「查看」→ 打开 release 页 ————————\n'
            '    // 白名单只放本仓库 release 相关域名：act 通道不能变成任意 URL 启动器。\n'
            '    // URL 恒为 ASCII（github.com / gh-proxy.com），narrow 版 ShellExecute 足够。\n'
            '    if (name == "app.openUrl") {\n'
            '        const std::string url = payload.find("url") ? payload.find("url")->asString() : "";\n'
            '        const bool allowed =\n'
            '            url.rfind("https://github.com/guocheng1378/apx-android/", 0) == 0 ||\n'
            '            url.rfind("https://gh-proxy.com/", 0) == 0;\n'
            '        if (allowed) {\n'
            '            ::ShellExecuteA(nullptr, "open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);\n'
            '        } else {\n'
            '            APX_LOGW("app.openUrl 拒绝非白名单 URL：{}", url.c_str());\n'
            '        }\n'
            '        // 防御分支（本 UI 不会触发）：回 ok 避免前端挂 10s 超时，拒绝已落日志。\n'
            '        return postResponse(p, id, okResp());\n'
            '    }\n'
        ),
    },
]


def main() -> int:
    try:
        with open(PATH, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print(f"open failed: {e}")
        return 1

    # —— 阶段一（内存）：错位修复 + 位置校验 ——
    todo = []
    for e in EDITS:
        ins, anc, place = e["insert"], e["anchor"], e["placement"]
        correct = (anc + ins) if place == "after" else (ins + anc)
        wrong = (ins + anc) if place == "after" else (anc + ins)
        if correct in text:
            print(f"already correct, skip: {e['id']}")
            continue
        if wrong in text:
            # 上轮错位形态：先剥离，再走正常插入
            text = text.replace(wrong, anc, 1)
            print(f"repaired misplaced block: {e['id']}")
        # 此时目标形态不存在 → 校验锚点唯一
        n = text.count(anc)
        if n != 1:
            print(f"VALIDATION FAILED：{e['id']} anchor count={n} expect=1\n---anchor---\n{anc}")
            return 1
        # 预演插入，确认结果只出现一次且锚点仍在
        candidate = text.replace(anc, correct, 1)
        if candidate.count(ins) != 1:
            print(f"VALIDATION FAILED：{e['id']} 插入后 insert 出现 {candidate.count(ins)} 次")
            return 1
        text = candidate
        todo.append(e["id"])

    # —— 阶段二：全部通过才写盘 ——
    if not todo:
        print("no changes")
        return 0
    try:
        with open(PATH, "w", encoding="utf-8") as f:
            f.write(text)
    except OSError as e:
        print(f"write failed: {e}")
        return 1
    print(f"OK {PATH}: fixed/inserted " + ", ".join(todo))
    return 0


if __name__ == "__main__":
    sys.exit(main())
