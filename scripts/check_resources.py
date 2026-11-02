#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""资源使用关卡：静态资源不许「定义了却没人用」。

为什么需要它
------------
这一关不是风格洁癖，是防一类只增不减的浪费：功能页一页页迁 Compose 之后，
绑在旧 View 上的 **strings / colors / dimens / styles / drawable / layout**
不会自己消失。编译器和 lint 都不会报「未使用资源」，它们只会在每一次
APK 构建里被 AAPT2 编进 resources.arsc 与 res/ 表里，于是仓库和包体只涨不落
（用户的原话：「仓库为什么变大、功能没多」）。2026-11-02 实测：一次清理就删掉
**276 处**（691 → 415），全是历代重构攒下的。

本关做什么
----------
1. 收集全仓 `res/values*` 里定义的 string / color / dimen / style / array 等，
   以及 `res/<type>/` 下的文件型资源（drawable / layout / mipmap / font…）。
2. 扫 `.kt` / `.java` / `.xml`，收集四类引用：
   - `R.string.foo`（含 `R.style.Theme_X` 下划线写法 → 还原成 `Theme.X`）
   - `@string/foo`、`?attr/foo` 等资源引用
   - `getIdentifier("foo", ...)` 反射查找
   - `<style parent="Foo">` 这种**裸 parent**（无 `@style/` 前缀，容易被漏掉）
3. 每一条「定义了但三类引用都没提到」记为死资源。

为什么不直接按「diff 里是否删了别名」保守跳过
--------------------------------------------
`miuix_bg` → `@color/md_background` 这类别名**会**被算成引用，所以别名本身
不会被误删；反过来，如果大家都改去引用 `md_*` 而没人再提 `miuix_*`，
那 `miuix_*` 就该删 —— 这正是 v1.33 那次「一律改用 md_*」的收尾。

白名单
------
`ALLOWED_UNUSED` 登记「确实没人引用但要留着」的资源，必须写理由。
新增资源请顺手在代码或布局里用上；确实暂不接入的，登记到这里。

自测
----
反例：在 values/strings.xml 里加 `<string name="zz_probe">x</string>` 后运行，
本关应 FAIL 并点名 `zz_probe`；删除即 OK。
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {"build", ".git", ".gradle", ".idea", "node_modules", ".kotlin"}

# 定义了但**确实**没有引用方、需要保留的资源：(type, name) -> 理由
ALLOWED_UNUSED = {
    # 下面几条是「给布局/工具链预留的占位」或者被 AAPT2 按约定识别的东西
    # （当前为空：2026-11-02 清理后一件都没有，保持空着就是保持零欠债）
}

VALUE_TAGS = {
    "string": "string", "color": "color", "dimen": "dimen", "style": "style",
    "bool": "bool", "integer": "integer", "fraction": "fraction",
    "string-array": "array", "integer-array": "array", "array": "array",
    "plurals": "plurals", "attr": "attr",
}
FILE_TYPES = {"drawable", "layout", "mipmap", "font", "anim", "animator",
              "xml", "menu", "raw", "navigation", "color"}

NAME_ATTR = re.compile(r'\bname\s*=\s*"([^"]+)"')


def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        yield dirpath, filenames


# ------------------------------------------------------------------ 定义
defined = {}          # (type, name) -> 定义处相对路径


def collect_definitions():
    for dirpath, filenames in walk(ROOT):
        base = os.path.basename(dirpath)
        parent = os.path.basename(os.path.dirname(dirpath))

        # 文件型资源：res/<type>/xxx.xml
        if parent == "res" and base.split("-")[0] in FILE_TYPES and base != "values":
            rtype = base.split("-")[0]
            for f in filenames:
                name = f.split(".")[0]
                if f.endswith(".9.png"):
                    name = f[:-6]
                defined[(rtype, name)] = os.path.relpath(
                    os.path.join(dirpath, f), ROOT)
            continue

        # 值型资源：res/values*/xxx.xml
        if base != "values" and not base.startswith("values-"):
            continue
        for f in filenames:
            if not f.endswith(".xml"):
                continue
            path = os.path.join(dirpath, f)
            text = open(path, encoding="utf-8", errors="replace").read()
            text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
            for m in re.finditer(r"<([\w-]+)\b([^>]*?)/?>", text):
                tag = m.group(1)
                if tag not in VALUE_TAGS:
                    continue
                nm = NAME_ATTR.search(m.group(2))
                if not nm:
                    continue
                # <item name="..."> 定义的是父 style 的字段，不算独立资源
                if tag == "string" and not m.group(0).endswith("</string>"):
                    pass
                defined.setdefault((VALUE_TAGS[tag], nm.group(1)),
                                   os.path.relpath(path, ROOT))


# ------------------------------------------------------------------ 引用
RE_R_TYPE = re.compile(r"\bR\s*\.\s*(\w+)\s*\.\s*(\w+)")
RE_AT = re.compile(r"[@?]\+?(?:[\w.]+:)?([a-z][\w]*)/([\w.]+)")
RE_GETID = re.compile(r'getIdentifier\(\s*"([^"]+)"')
RE_PARENT = re.compile(r'<style\b[^>]*\bparent="([^"]+)"')

refs = defaultdict(set)


def collect_references():
    for dirpath, filenames in walk(ROOT):
        for f in filenames:
            if not f.endswith((".kt", ".java", ".xml")):
                continue
            path = os.path.join(dirpath, f)
            text = open(path, encoding="utf-8", errors="replace").read()

            for m in RE_R_TYPE.finditer(text):
                rtype, name = m.group(1), m.group(2)
                refs[rtype].add(name)
                if "_" in name:
                    # R.style.Theme_AllPeriph_Miuix_Dialog → Theme.AllPeriph.Miuix.Dialog
                    refs[rtype].add(name.replace("_", "."))

            for m in RE_AT.finditer(text):
                rtype, name = m.group(1), m.group(2)
                if rtype in FILE_TYPES or rtype in VALUE_TAGS:
                    refs[rtype].add(name)

            for m in RE_GETID.finditer(text):
                for rtype in ("string", "drawable", "layout", "color", "mipmap"):
                    refs[rtype].add(m.group(1))

            for m in RE_PARENT.finditer(text):
                refs["style"].add(m.group(1).split("/")[-1])


# ------------------------------------------------------------------ 判定
def main():
    collect_definitions()
    collect_references()

    unused = []
    for (rtype, name), where in sorted(defined.items()):
        if (rtype, name) in ALLOWED_UNUSED:
            continue
        if rtype == "attr":
            # ?attr/foo 走 RE_AT；自定义 attr 也会在代码里以 R.attr.foo 出现
            if name in refs["attr"]:
                continue
            unused.append((rtype, name, where))
            continue
        if name in refs.get(rtype, ()) or name in refs.get("attr", ()):
            continue
        unused.append((rtype, name, where))

    total = len(defined)
    by_type = defaultdict(int)
    for rtype, _, _ in unused:
        by_type[rtype] += 1

    print("静态资源：定义 %d 处，扫描到引用 %d 处"
          % (total, sum(len(v) for v in refs.values())))

    if not unused:
        print("资源使用关卡 OK：没有「定义了没人用」的资源（白名单 %d 条）。"
              % len(ALLOWED_UNUSED))
        return 0

    print()
    print("以下资源**定义了但全仓没有任何引用**，它们只会让仓库和包体白涨：")
    for rtype, name, where in unused:
        print("  %-9s %-42s %s" % (rtype, name, where))
    print()
    print("处置：① 代码/布局里用起来；② 从资源文件里删掉；"
          "③ 确实要留（例如插件按名反射取）就登记进 check_resources.py 的 ALLOWED_UNUSED 并写理由。")
    print("FAIL：死资源 %d 处，按类型 %s" % (len(unused), dict(by_type)))
    return 1


if __name__ == "__main__":
    sys.exit(main())