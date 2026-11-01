#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UI Kit 关卡（CI 用，非 0 即失败）。

## 为什么需要它
界面层从 Views 迁 Compose 是**分屏**做的：先迁的屏（`KeyboardPage`、`HelpSheet`）各自裸写
`Card(` / `TextButton(`，后迁的屏走 `ui/miuix/ApComponents.kt`。结果同一套界面里两种写法并存，
"改一次卡片圆角 / 按钮内边距要翻好几个文件"的老问题换个形式回来了 —— 与 issue#42
「排版比较混乱」同一根因。编译器对这两种写法**等权通过**，只会静默长歪。

## 判据
在 `android/app/src/main/java/com/allperiph/ui/compose/` 下的页面里，禁止直接使用 Miuix 的
布局级组件：
    top.yukonga.miuix.kmp.basic 下的 Card / SmallTitle / TextButton
（`Text` / `Icon` / `Button` 这类**原子**组件不拦：它们不携带本项目的排版约定，
 用哪个包里的都只是文字与图标本体。）

页面只能经由 `ApComponents` 取：
    ApComponents.Card        卡片 / 弹窗主体 / 键帽
    ApComponents.Section     带动小标题的分组
    ApComponents.DialogCard  Dialog + 限高卡片 + 内边距（弹窗统一外壳）
    ApComponents.ActionButton 页面级动作按钮（整行宽）
    ApComponents.NarrowButton 随文字宽度的按钮（弹窗取消 / 扇出项 / chip）

## 豁免（必须写明理由，不许默默放过）
每条例外用 `文件名 -> 理由` 登记，新增豁免必须同时给出"为什么不走 Kit"。

只做静态文本分析，不需要编译器。退出码非 0 = 有违规。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCOPE = ROOT / "android/app/src/main/java/com/allperiph/ui/compose"

# 被接管的 Miuix 组件（布局级、携带本仓排版约定）
BANNED = ("Card", "SmallTitle", "TextButton")

# 豁免：文件名 -> 理由
EXEMPT = {
    # 组件画廊：演示的对象**就是**原生 Miuix 组件，包一层就展示不出真实观感了
    "MiuixShowcaseActivity.kt": "组件总览演示页，展示对象是原生 Miuix 组件",
}

# Kit 里必须存在的出口（防止"只删页面用法、不改 Kit"的半截改动）
REQUIRED_KIT_API = (
    "fun Card(",
    "fun Section(",
    "fun DialogCard(",
    "fun ActionButton(",
    "fun NarrowButton(",
)

KIT = ROOT / "android/app/src/main/java/com/allperiph/ui/miuix/ApComponents.kt"

IMPORT_BANNED = re.compile(
    r"^import\s+top\.yukonga\.miuix\.kmp\.basic\.(" + "|".join(BANNED) + r")\s*$", re.M)
CALL_BANNED = re.compile(
    r"(?<![.\w])(" + "|".join(BANNED) + r")\(")


def main():
    problems = []

    for f in sorted(SCOPE.rglob("*.kt")):
        rel = f.name
        if rel in EXEMPT:
            continue
        text = f.read_text(encoding="utf-8")
        for m in IMPORT_BANNED.finditer(text):
            problems.append(f"{rel}: import 了被接管的 Miuix 组件 {m.group(1)}"
                            f"（请改走 ApComponents）")
        # 去掉注释行再查调用，免得文档里举例被误判
        code = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("//"))
        for m in CALL_BANNED.finditer(code):
            name = m.group(1)
            line = code[:m.start()].count("\n") + 1
            # 已经是 ApComponents.X( 的形式由 (?<![.\w]) 排除；这里再排除本文件自身定义的
            problems.append(f"{rel}:{line}: 裸用 {name}(（请改走 ApComponents）")

    kit = KIT.read_text(encoding="utf-8")
    for api in REQUIRED_KIT_API:
        if api not in kit:
            problems.append(f"ApComponents.kt 缺少出口 `{api}`（页面依赖它，不许删）")

    if problems:
        print("UI Kit 关卡 FAIL：")
        for p in problems:
            print("  - " + p)
        print()
        print("豁免清单（新增豁免必须写理由）：")
        for k, v in EXEMPT.items():
            print(f"  - {k}：{v}")
        sys.exit(1)

    print(f"UI Kit 关卡 OK：ui/compose 下 {len(list(SCOPE.rglob('*.kt')))} 个文件只经 ApComponents "
          f"取卡片/按钮（豁免 {len(EXEMPT)} 项已登记理由），Kit 的 "
          f"{len(REQUIRED_KIT_API)} 个出口齐全。")


if __name__ == "__main__":
    main()