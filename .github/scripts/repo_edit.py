#!/usr/bin/env python3
"""repo-edit：CHANGELOG 顶插 v223.29.31 发版段（锚点 v223.29.30 段题，唯一命中）。"""
import os
import sys

ERRLOG = "docs/edit_error.log"
CHG = "docs/CHANGELOG.md"

ANCHOR = "## v223.29.30（2026-11-08）"

SECTION = (
    "## v223.29.31（2026-11-08）\n"
    "\n"
    "七条功能收口，各批 repo-edit run 全绿：#7 文字照不齐、#1 灯效调色、#6 长按连发、"
    "#4 语音输入、#5 按下即震、#3 触控板接通用逻辑；叠加本日更早落盘的发送超时三修"
    "（register/prepare 30s + upload 响应宽限重读 + 进度事件）与 open-file 10 分钟超时修复。"
    "仅 CI 编译验证，未装机回验。\n"
    "\n"
    "### 文字对账（#7）\n"
    "\n"
    "- 手机端 sendInputText 改用真实返回值（此前丢弃、恒报成功）；发送失败帧入队，通道恢复自动按序重推 —— 不再要用户「恢复后重新打」。\n"
    "- 发送按钮先发 0x04 全量对账，对账失败不清空输入框（字不再凭空消失）。\n"
    "- PC 端实现 0x04 = 替换语义（按已注入计数退格清场再重打）；DONE / CANCEL / 新会话三处清零 —— 单向增量丢一帧的漂移在这一拍归零。\n"
    "\n"
    "### 灯效调色（#1）\n"
    "\n"
    "- 灯效芯片旁新增「调色」入口（灯效开着才显示）：色相 8 选 + 亮度三档，点击即生效并持久化；遥控图块同步。\n"
    "- 呼吸暗谷 0.30 → 0.45（「灯效太黑」根因）；调色只在绘制阶段读取 —— 拖动只触发键帽重绘，不破「84 键只重绘不重组」。\n"
    "\n"
    "### 长按连发（#6）\n"
    "\n"
    "- 键盘页全键 + 编辑键排：按住 400ms 起每 55ms 一拍重发，松手即停、抬手吞尾；连发只送键不出声，修饰键不连发。\n"
    "\n"
    "### 语音输入（#4）\n"
    "\n"
    "- 「让手机帮我输入」底部新增「语音」：系统语音识别 → 文字自动进输入框 → 走增量上屏 + 对账链。\n"
    "\n"
    "### 动效与震动（#5）\n"
    "\n"
    "- 编辑键排、取消/发送按钮按下即震（Feedback.tap）；触控板轻点补震感。\n"
    "\n"
    "### 触控板通用逻辑（#3）\n"
    "\n"
    "- 双指点按 = 右键单击；双指长按 550ms = 右键拖拽（与 touchpad.kt rightDragTask 同口径）；长按左拖 400ms 对齐 550ms。\n"
    "\n"
    "### 发送超时与进度（本日更早）\n"
    "\n"
    "- register/prepare 超时 10s → 30s；upload 响应首超 30s 宽限重读（手机已收到、PC 报超时的根治）。\n"
    "- 进度事件全链路：发送中每 10% 推一拍，传输页显示百分比，区分「在传」与「卡死」。\n"
    "- open-file（选文件对话框）前端超时 10s → 10 分钟（「open-file dummy 超时」根治），超时文案改人话。\n"
    "\n"
    "---\n"
    "\n"
)

EDITS = [
    {
        "id": "CHANGELOG v223.29.31 顶插",
        "file": CHG,
        "anchor": ANCHOR,
        "new": SECTION + ANCHOR,
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
