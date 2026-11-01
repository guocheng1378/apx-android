#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""形状工厂关卡（CI 用，非 0 即失败）。

## 为什么需要它
「卡片圆角是 18 还是 12」「描边 1px 还是 2px」这类问题**编译器永远不报**，只在真机上体现为
「同一个界面里两种圆角」。本仓的应对是 `com.allperiph.shared.ui.ApKit`——画形状的唯一实现处。
但规矩只写在注释与 README 里，历史上反复被绕开：

  · 手机端 7 个文件曾各自手写 `GradientDrawable()` 共 36 处；
  · 后来 Kit 建好了，`TouchpadActivity` / `HotkeyBoard` / 两个 `TvOverlay` / `RemoteActivity`
    仍在自己 `new`（Kit 的注释写着「唯一实现处」，而事实是 5 处绕过）；
  · `:app` 与 `:tv` 各存一份同构实现，改了一边忘另一边。

所以这里把「只有一处」变成本关卡能机械核对的判据。

## 判据
1. `android/{app,tv,shared}/src/**/*.kt` 里**禁止** `GradientDrawable()` 构造，白名单只有
   `shared/ui/ApKit.kt` 本身（它就是那个实现）。
2. Kit 的三个出口必须齐全：`shape()` / `rect()` / `pill()` / `oval()`；少一个说明有人删了
   出口却留着调用，编译期才会炸。
3. Kit 必须位于 `:shared`。`:app` / `:tv` 下出现同名 `object ApKit` → 失败（分叉的开始）。

只做静态文本分析，不需要编译器。退出码非 0 = 有违规。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_ROOTS = [
    ROOT / "android/app/src",
    ROOT / "android/tv/src",
    ROOT / "android/shared/src",
]

# 唯一允许 new GradientDrawable 的文件（相对路径）
ALLOWED_DRAWABLE = "android/shared/src/main/java/com/allperiph/shared/ui/ApKit.kt"

KIT_PATH = ROOT / "android/shared/src/main/java/com/allperiph/shared/ui/ApKit.kt"

REQUIRED_KIT_API = ("fun shape(", "fun rect(", "fun pill(", "fun oval(")

NEW_DRAWABLE = re.compile(r"GradientDrawable\s*\(\s*\)")
DUP_KIT = re.compile(r"\bobject\s+ApKit\b")


def main():
    problems = []
    scanned = 0

    for root in SRC_ROOTS:
        for f in sorted(root.rglob("*.kt")):
            rel = f.relative_to(ROOT).as_posix()
            text = f.read_text(encoding="utf-8")
            scanned += 1
            # 去掉注释行再查，免得文档里举例被误判
            code = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("//"))

            if rel != ALLOWED_DRAWABLE:
                for m in NEW_DRAWABLE.finditer(code):
                    line = code[:m.start()].count("\n") + 1
                    problems.append(
                        f"{rel}:{line}: 直接 new GradientDrawable（形状请走 shared.ui.ApKit）")

            if rel != ALLOWED_DRAWABLE and DUP_KIT.search(code):
                problems.append(f"{rel}: 又出现了 `object ApKit`（Kit 只许在 :shared 有一份）")

    if not KIT_PATH.exists():
        problems.append(f"找不到 Kit：{KIT_PATH.relative_to(ROOT)}")
    else:
        kit = KIT_PATH.read_text(encoding="utf-8")
        for api in REQUIRED_KIT_API:
            if api not in kit:
                problems.append(f"ApKit 缺少出口 `{api}`（调用方依赖它，不许删）")

    if problems:
        print("形状工厂关卡 FAIL：")
        for p in problems:
            print("  - " + p)
        print()
        print(f"白名单（唯一允许直接构造形状的文件）：{ALLOWED_DRAWABLE}")
        sys.exit(1)

    print(f"形状工厂关卡 OK：扫描 {scanned} 个 Kotlin 文件，"
          f"只有 {ALLOWED_DRAWABLE} 直接构造 GradientDrawable，"
          f"ApKit 的 {len(REQUIRED_KIT_API)} 个出口齐全。")


if __name__ == "__main__":
    main()