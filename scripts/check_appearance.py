#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""外观门面关卡（CI 用，非 0 即失败）。

## 为什么需要它
外观这套东西（皮肤 / 取色来源 / 背景 / 动效 / 震动音效）历史上全塞在一个 380 行的
`ThemeSkin` 对象里，18 个文件共 122 处引用。改一处配色要在 18 个文件里找，而且**编译器
对「走门面」与「绕过门面」等权通过**：绕过的那处不会报错、不会崩，只在真机上表现为
「换了皮肤这块没变」「设置里关了照样震」——本仓库在 issue#52 / #57 已经踩过两次同类。

2026-11-02 把它拆成五个门面（各自只干一件事，共用 `ApAppearancePrefs` 落盘）：

  · `ApSkin`      皮肤本身（8 套预设 + 自定义 + 导入导出）
  · `ApPalette`   强调色 / 取色来源（莫奈）/ 对话框主题
  · `ApBackdrop`  背景（预设底色 / 自定义图）
  · `ApTiming`    动效档位
  · `ApFeel`      震动 / 音效档位

拆完最容易发生的退化是三件，本关卡把它们变成机械可核对的判据。

## 判据
1. **旧名不得复活**：代码里出现 `ThemeSkin` → 失败（拆分说明写在注释里不算，先剥注释）。
2. **外观偏好只有一个落盘处**：字符串 `apx_theme` 只许出现在 `ui/ApAppearancePrefs.kt`。
   别处自己 `getSharedPreferences("apx_theme", ...)` 会与门面各读各的缓存，写偏了没人报错。
3. **强调色不得直接取常量**：Kotlin 里出现 `R.color.md_primary` / `md_primary_pressed` /
   `md_primary_container` / `md_on_primary` → 失败（白名单只有 `ApPalette.kt`：它的兜底色）。
   这四个是编译期常量，看不见「跟随系统取色」的叠加层与模块皮肤 —— 取它等于皮肤白选。
4. **每个门面读写成对**：只有读没有写（或反之）说明门面被削掉了一半，设置页改了存不下来，
   而编译期一声不响。
5. **引用可解析**：`ui` 包外引用这五个门面时，必须有 `import` 或写成全限定名；
   漏 import 是会编译失败的，但那份报错在 Android 构建里往往被别的错误抢先，定位费时间。

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

# 门面 → (所在文件, 必须有的读出口, 必须有的写出口)
FACADES = {
    "ApSkin": ("android/app/src/main/java/com/allperiph/ui/ApSkin.kt",
               ("fun current(", "fun picked("),
               ("fun set(", "fun saveCustoms(")),
    "ApPalette": ("android/app/src/main/java/com/allperiph/ui/ApPalette.kt",
                  ("fun primary(", "fun colorSource(", "fun dialogStyle("),
                  ("fun setColorSource(",)),
    "ApBackdrop": ("android/app/src/main/java/com/allperiph/ui/ApBackdrop.kt",
                   ("fun bgId(", "fun bgImage("),
                   ("fun setBg(", "fun setBgImage(")),
    "ApTiming": ("android/app/src/main/java/com/allperiph/ui/ApTiming.kt",
                 ("fun motionIndex(", "fun motionMs("),
                 ("fun setMotion(",)),
    "ApFeel": ("android/app/src/main/java/com/allperiph/ui/ApFeel.kt",
               ("fun hapticIndex(", "fun soundIndex("),
               ("fun setHaptic(", "fun setSound(")),
}

APPEARANCE_PREFS = "android/app/src/main/java/com/allperiph/ui/ApAppearancePrefs.kt"
PALETTE = "android/app/src/main/java/com/allperiph/ui/ApPalette.kt"
APPEARANCE_PKG = "com.allperiph.ui"

OLD_NAME = re.compile(r"\bThemeSkin\b")
PREF_FILE_NAME = re.compile(r"apx_theme")
ACCENT_CONST = re.compile(
    r"\bR\.color\.(md_primary|md_primary_pressed|md_primary_container|md_on_primary)\b")


def strip_comments(text):
    """剥掉 // 行注释与 /* */ 块注释（KDoc 也算），避免文档里举例被误判。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("//"))


def main():
    problems = []
    scanned = 0

    for root in SRC_ROOTS:
        for f in sorted(root.rglob("*.kt")):
            rel = f.relative_to(ROOT).as_posix()
            code = strip_comments(f.read_text(encoding="utf-8"))
            scanned += 1

            for m in OLD_NAME.finditer(code):
                line = code[: m.start()].count("\n") + 1
                problems.append(
                    f"{rel}:{line}: 引用了已删除的 `ThemeSkin`（皮肤→ApSkin / 取色→ApPalette / "
                    f"背景→ApBackdrop / 动效→ApTiming / 手感→ApFeel）")

            if rel != APPEARANCE_PREFS:
                for m in PREF_FILE_NAME.finditer(code):
                    line = code[: m.start()].count("\n") + 1
                    problems.append(
                        f"{rel}:{line}: 直接摸外观偏好文件 `apx_theme`（请走 ApAppearancePrefs）")

            if rel != PALETTE:
                for m in ACCENT_CONST.finditer(code):
                    line = code[: m.start()].count("\n") + 1
                    problems.append(
                        f"{rel}:{line}: 直接取强调色常量 `{m.group(0)}` —— 皮肤与莫奈会静默失效，"
                        f"请走 ApPalette.primary / onPrimary / primaryContainer")

            # —— 引用可解析（只查 ui 包外的文件）——
            pkg = re.search(r"^package\s+([\w.]+)", code, re.M)
            pkg = pkg.group(1) if pkg else ""
            if pkg != APPEARANCE_PKG:
                imported = set(re.findall(r"^import\s+com\.allperiph\.ui\.(\w+)\s*$", code, re.M))
                for name in FACADES:
                    bare = re.search(r"(?<![\w.])" + name + r"\.", code)
                    fqn = "com.allperiph.ui." + name
                    if bare and name not in imported and fqn not in code:
                        line = code[: bare.start()].count("\n") + 1
                        problems.append(
                            f"{rel}:{line}: 用了 `{name}` 却既没 import 也没写全限定名")

    # —— 门面文件自身的读写成对 ——
    for name, (rel, reads, writes) in FACADES.items():
        p = ROOT / rel
        if not p.exists():
            problems.append(f"找不到门面文件：{rel}")
            continue
        body = strip_comments(p.read_text(encoding="utf-8"))
        for api in reads + writes:
            if api not in body:
                problems.append(f"{rel}: 缺少出口 `{api}`（门面被削掉一半，设置页会改不动/读不到）")

    if problems:
        print("外观门面关卡 FAIL：")
        for p in problems:
            print("  - " + p)
        print()
        print("分工：皮肤 ApSkin ／ 取色 ApPalette ／ 背景 ApBackdrop ／ 动效 ApTiming ／ 手感 ApFeel")
        sys.exit(1)

    print(f"外观门面关卡 OK：扫描 {scanned} 个 Kotlin 文件，"
          f"{len(FACADES)} 个门面读写成对，外观偏好只有一个落盘处，"
          f"无 ThemeSkin 残留、无直接取强调色常量。")


if __name__ == "__main__":
    main()
