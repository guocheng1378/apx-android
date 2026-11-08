#!/usr/bin/env python3
"""repo-edit：v223.29.30 发版一次性编辑 —— 在 docs/CHANGELOG.md 顶部插入本版明细段。

发版门禁（docs/RELEASE.md 第 2 步）：Release 正文顶部的「改了什么」由 build.yml 的
Extract changelog 步骤从 CHANGELOG 对应版本段提取 —— 没写那段，CI 打 error 并降级
为占位正文。CHANGELOG ~89KB 不适合整文件回传，故走本锚点编辑（唯一命中才写盘）。

锚点：当前顶部第一个版本段标题 `## v223.29.29（2026-11-08）`（全文唯一），
新段插到它前面。幂等：new 已在文中则自动 skip。
（本文件历史上承载批 N/U-7 系列锚点，均已落地；git log 可查。）
"""
import os
import sys

CHANGELOG = "docs/CHANGELOG.md"
ERRLOG = "docs/edit_error.log"

ANCHOR = "## v223.29.29（2026-11-08）"

SECTION = (
    "## v223.29.30（2026-11-08）\n"
    "\n"
    "键盘灯效「皮肤审查」五条收口，Build #1431 全绿（android + pc + 11 项协议关卡）。\n"
    "\n"
    "### 灯效结构（审查 #1 / #5）\n"
    "\n"
    "- 玻璃键帽「透明底隐形键」根治：灯效整条移出 tint 通道，GlassKeycap 拆出 faceMod（灯面在绘制期落到玻璃高光之下）与 labelMod（字色在绘制期染色）两个参数，透明 tint 这类坑从结构上封死。\n"
    "- 遥控页图块接入灯效：呼吸 / 波浪 / 按下扩散三档与键帽同款亮灯；灯效公式抽成键帽与图块共用的单一真源（rgbFaceAt / touchLightAt），遥控页不再是灯效死角。\n"
    "\n"
    "### 手感（审查 #2）\n"
    "\n"
    "- 键盘页页签补回按下震动与音效（Feedback.tap）：此前只有选中态、按下去毫无反馈，是全页手感最弱的可点面。\n"
    "\n"
    "### 性能架构（审查 #3，本轮主力）\n"
    "\n"
    "- 字色移出组合期：Text 只画静态基色，灯色由 graphicsLayer 的 colorFilter 在绘制阶段按字形 alpha 染色 —— 开灯时 84 颗键只重绘、不再逐帧重组，兑现 RgbSpec 一直承诺的「只重绘不重组」。\n"
    "- 机械侧壁涟漪不再借重组带动（drawBehind 自己订阅相位 tick），遥控图块同口径（9 个图块开灯也不重组）；顺带修掉侧壁扩散档空闲画透明的同族 bug。\n"
    "\n"
    "### 文档更正（审查 #4）\n"
    "\n"
    "- KeyPref 六档 / 四档、KeyFx 关闭档无水波兜底等过期注释与实现对齐。\n"
    "\n"
    "### 验证状态\n"
    "\n"
    "- 仅 CI 编译 + 关卡验证，**未装机回验**：灯效观感与开灯帧率改善待真机确认。\n"
    "\n"
    "---\n"
    "\n"
)

EDITS = [
    {
        "id": "REL-v223.29.30 CHANGELOG 顶插",
        "file": CHANGELOG,
        "kind": "replace",
        "anchor": ANCHOR,
        "new": SECTION + ANCHOR,
    },
]


def main() -> int:
    errors = []
    pending = {}
    for e in EDITS:
        path = e["file"]
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except OSError as ex:
            errors.append("open failed " + path + ": " + str(ex))
            continue
        if e["new"] in text:
            print("already applied, skip: " + e["id"])
            continue
        n = text.count(e["anchor"])
        if n != 1:
            errors.append(
                e["id"] + ": anchor count=" + str(n) + " expect=1 file=" + path
                + "\n---anchor---\n" + e["anchor"][:200]
            )
            continue
        pending[path] = text.replace(e["anchor"], e["new"], 1)

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
