#! /usr/bin/env python3
# -*- coding: utf-8 -*-
"""按钮文字居中的一致性关卡（CI 用，非 0 即失败）。

## 为什么需要它
issue#52 第 10 条「部分安卓端 app 内按钮内文字没有居中对齐」，根因是 framework `Button`
的默认 `gravity = left|center_vertical` —— 文字短时看不出来，宽按钮（`match_parent` /
`weight=1`）上就明显贴左。v223.16 只修了用 `@style/APButtonGhost` 的那些；
**代码里 new 出来的按钮**和「带背景 + padding 的行内胶囊入口」是漏网的那批。
编译器不报、真机才看得出，所以用这个脚本钉住。

## 判定
只扫自绘 UI（`ui/`、`miuxtv`、`touchpad/`、`screen/`、`controlled/` 下的 .kt），
看「可点击（有 setOnClickListener / setOnTouchListener）」，且**像按钮**的节点：
  - 是 `android.widget.Button` / `AppCompatButton`，或
  - 同时设了 `background` 与 `padding`（说明它是个有边界的控件，不是行内纯文字）。
再要求它的 gravity 含水平居中（`CENTER` / `CENTER_HORIZONTAL`）。

## 例外（ALLOW）
调色板圆点、列表整行、纯标题文字这类**故意靠左**的节点写进 ALLOW 并注明理由。
新增例外必须在同一提交里补理由 —— 否则下次没人知道它为什么被放行。
"""
import os
import re
import sys

ROOTS = [
    "android/app/src/main/java/com/allperiph",
    "android/tv/src/main/java/com/allperiph",
]

NEW_OBJ = re.compile(r"\b(TextView|Button|AppCompatButton)\s*\(\s*(this|act|ctx|context|this@\w+)\s*\)")

# 故意靠左、不是按钮的节点：<相对路径>:<NEW_OBJ 所在行号> → 理由
ALLOW = {
    # 被控端「调色板圆点」：28dp 的方块，里面没有文字，gravity 无意义
    "android/app/src/main/java/com/allperiph/ui/MainActivity.kt:1411": "调色板圆点，无文字",
    "android/app/src/main/java/com/allperiph/ui/SettingsFragment.kt:469": "调色板圆点，无文字",
    # 文件页整行 / 目标行：行内是「文件名 + 右侧删除」的自定义排版，必须靠左
    "android/app/src/main/java/com/allperiph/ui/FilePanelActivity.kt:95": "文件页目标行，左侧对齐是设计",
    "android/app/src/main/java/com/allperiph/ui/FilePanelActivity.kt:190": "文件页行内删除入口，随行尾排版",
    # TV 文件页：标题与列表整行同样是「图标 + 文件名 + 大小」的左对齐排版
    "android/tv/src/main/java/com/allperiph/tv/ui/TvFileActivity.kt:61": "TV 文件页标题，左对齐是设计",
    "android/tv/src/main/java/com/allperiph/tv/ui/TvFileActivity.kt:64": "TV 文件页目标行，左对齐是设计",
    "android/tv/src/main/java/com/allperiph/tv/ui/TvFileActivity.kt:87": "TV 文件列表行，左对齐是设计",
    # TV 副屏返回按钮：← 开头、贴着屏角，居中会把箭头挪到中间
    "android/tv/src/main/java/com/allperiph/tv/ui/TvScreenActivity.kt:102": "「← 返回」贴角按钮，左对齐是设计",
}

# 注释行/行尾注释在取 gravity 前先剥掉：否则注释里提到 gravity 会被当成取值
LINE_COMMENT = re.compile(r"//.*$")


def strip_comments(body: str) -> str:
    return "\n".join(LINE_COMMENT.sub("", ln) for ln in body.split("\n"))


def blocks(src: str):
    out = []
    lines = src.split("\n")
    for i, ln in enumerate(lines):
        if not NEW_OBJ.search(ln) or ln.strip().startswith("//"):
            continue
        indent = len(ln) - len(ln.lstrip())
        body = [ln]
        j = i + 1
        while j < len(lines):
            nxt = lines[j]
            if nxt.strip() == "":
                body.append(nxt)
                j += 1
                continue
            ind = len(nxt) - len(nxt.lstrip())
            if ind <= indent and nxt.strip().startswith(("}", ")", "),", "});")):
                break
            if ind < indent:
                break
            body.append(nxt)
            j += 1
        out.append((i + 1, "\n".join(body)))
    return out


def looks_like_button(code: str, is_button_class: bool) -> bool:
    if is_button_class:
        return True
    # 有背景 + 有 padding 的自绘「胶囊入口」按按钮算；纯文字标签不算
    return ("background" in code or "setBackground" in code) and (
        "setPadding" in code or "padding" in code
    )


def centered(code: str) -> bool:
    m = re.search(r"gravity\s*=\s*([^\n]+)", code)
    if not m:
        return False
    val = m.group(1)
    if "CENTER_HORIZONTAL" in val:
        return True
    if "CENTER_VERTICAL" in val:
        # Gravity.CENTER_VERTICAL 的 horizontal 位是 0（= left），不算居中
        return False
    return "CENTER" in val


def main():
    offenders = []
    total = 0
    for root in ROOTS:
        for dirpath, _d, files in os.walk(root):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                p = os.path.join(dirpath, f)
                src = open(p, encoding="utf-8").read()
                for line_no, body in blocks(src):
                    if "setOnClickListener" not in body and "setOnTouchListener" not in body:
                        continue
                    code = strip_comments(body)
                    is_button_class = bool(re.search(r"\b(Button|AppCompatButton)\s*\(", body))
                    if not looks_like_button(code, is_button_class):
                        continue
                    total += 1
                    if centered(code):
                        continue
                    key = "%s:%d" % (p, line_no)
                    if key in ALLOW:
                        continue
                    g = re.search(r"gravity\s*=\s*([^\n]+)", code)
                    offenders.append(
                        "%s  gravity=%s   %s"
                        % (key, (g.group(1).strip() if g else "(未设置)"),
                           body.split("\n")[0].strip()[:70])
                    )
    print("自绘按钮类节点 %d 个（已排除 %d 条登记的例外）" % (total, len(ALLOW)))
    if offenders:
        print("\n========== 疑似按钮文字未居中 ==========")
        for o in offenders:
            print("FAIL " + o)
        print("\n修法：给该控件加 `gravity = Gravity.CENTER`（Gravity.CENTER_VERTICAL 只居纵轴、"
              "横轴仍是 left）。若确实是故意靠左的非按钮节点，在脚本 ALLOW 里登记并写明理由。")
        return 1
    print("通过：所有按钮类节点的文字都水平居中。")
    return 0


if __name__ == "__main__":
    sys.exit(main())