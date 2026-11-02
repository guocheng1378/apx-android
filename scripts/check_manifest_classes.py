#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Manifest 声明 vs 源文件一致性检查（CI 关卡）。

## 为什么需要它
Android 构建**不会**校验 `android:name=".ui.XxxService"` 指向的类是否存在：
清单合并只认字符串，类写错了照样编译通过、照样出包，症状是"静默失效"——
注册悬空的组件永远不会被启动，运行期连异常都没有。

本仓库已经因此踩过 4 次（都记在 app/AndroidManifest.xml 注释里）：
  - `.shared.im.AppxImeService`   —— 包名少个 e、类名多一个 p，输入法根本没被注册
  - `.shared.accessibility.AppxAccessibilityService` —— 同上多一个 p，无障碍服务不加载
  - `.ui.KeepAliveReceiver`       —— 真类在 shared.inject 包，保活闭环完全失效
  - `.ui.ExternalSendActivity`    —— 该类从未实现（现已被注释停用）
这类问题不会让 CI 变红，只会让功能悄悄不工作。

## 顺带把关：清单必须能通过 XML 解析（2026-11-02 新增）
上面所有判据都走正则扫文本，**结构非法它看不出来**。v223.29.1 的修复版就栽在这里：
一段 `<!-- ... -->` 注释被写进了 `<activity>` 开始标签的**属性之间**（XML 不允许），
本地 11 个关卡全绿、正则照样能扫到 android:name，CI 却整轮死在
`:app:processReleaseMainManifest` → `Error parsing AndroidManifest.xml`。
所以这里先用 xml.dom.minidom 做一次良构校验，把这类「本地绿、红在 CI」的坑提前到本地。

## 判据（按完整包名，不按简单类名）
相对名以该模块 `namespace`（app=com.allperiph / tv=com.allperiph.tv / shared=com.allperiph.shared）
为前缀，拼成全限定名后**必须在某个模块的源码里真的声明了这个类**。
只按简单类名匹配会给出假的 OK —— 仓库里就有两个同名 ApxImeService
（com.allperiph.ui 与 com.allperiph.shared.ime），谁被注册必须看包名。
全限定名（插件库等）不检查——那不是本仓库能决定的。

退出码非 0 即失败，并打印每一处悬空声明。
"""
import os
import re
import sys
import xml.dom.minidom

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = os.path.join(ROOT, "android")

MODULES = ("app", "tv", "shared")
MANIFESTS = {m: os.path.join(ANDROID, m, "src", "main", "AndroidManifest.xml") for m in MODULES}

# 组件标签：只查这些标签上的 android:name（<action>/<meta-data> 等不是类名）
TAGS = ("application", "activity", "activity-alias", "service", "receiver", "provider")

DECL = re.compile(
    r"^(?P<indent>[ \t]*)"
    r"(?:@\w+(?:\([^)]*\))?[ \t\r\n]*)*"
    r"(?:open |abstract |sealed |data |value |internal |private |public |final |enum |annotation )*"
    r"(?:class|object|interface)[ \t]+(?P<name>\w+)",
    re.M,
)


def module_namespace(mod):
    gradle = os.path.join(ANDROID, mod, "build.gradle.kts")
    if os.path.exists(gradle):
        m = re.search(r'namespace\s*=\s*"([^"]+)"', open(gradle, encoding="utf-8").read())
        if m:
            return m.group(1)
    return None


def source_index():
    """返回 {全限定类名: 源文件相对路径}，覆盖所有模块的 main 源码。"""
    idx = {}
    for mod in MODULES:
        base = os.path.join(ANDROID, mod, "src", "main", "java")
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, files in os.walk(base):
            for f in files:
                if not f.endswith((".kt", ".java")):
                    continue
                p = os.path.join(dirpath, f)
                txt = open(p, encoding="utf-8", errors="replace").read()
                pkg = re.search(r"^\s*package\s+([\w.]+)", txt, re.M)
                if not pkg:
                    continue
                pkg = pkg.group(1)
                top = None
                for m in DECL.finditer(txt):
                    name = m.group("name")
                    if m.group("indent") == "":
                        top = name
                        idx[pkg + "." + name] = os.path.relpath(p, ROOT)
                    elif top:
                        idx[pkg + "." + top + "." + name] = os.path.relpath(p, ROOT)
    return idx


def declared_classes(text):
    """返回 [(标签, 类名)]，只看组件标签上的 android:name。"""
    out = []
    for m in re.finditer(r"<\s*([A-Za-z-]+)\b([^>]*?)/?>", text, re.S):
        tag, attrs = m.group(1), m.group(2)
        if tag not in TAGS:
            continue
        n = re.search(r'android:name\s*=\s*"([^"]+)"', attrs)
        if n:
            out.append((tag, n.group(1).strip()))
    return out


def main():
    idx = source_index()
    print("源码索引：%d 个全限定类名" % len(idx))
    problems = []
    checked = 0
    for mod, path in MANIFESTS.items():
        if not os.path.exists(path):
            continue
        ns = module_namespace(mod)
        if not ns:
            problems.append("%s: 读不到 namespace，无法解析相对类名" % mod)
            continue
        text = open(path, encoding="utf-8").read()
        # 良构校验：正则扫描看不出「注释写在开始标签中间」这类结构错误
        rel = os.path.relpath(path, ROOT)
        try:
            xml.dom.minidom.parseString(text)
            print("  OK   xml-wellformed %s" % rel)
        except Exception as exc:
            problems.append("%s: XML 结构非法（%s）→ Gradle 的 "
                            "processReleaseMainManifest 会直接判死整轮构建"
                            % (rel, exc))
        for tag, name in declared_classes(text):
            if not name.startswith("."):
                continue  # 全限定名 / 插件类，不归本检查管
            fqn = ns + name
            checked += 1
            where = idx.get(fqn)
            if where:
                print("  OK   %-9s %-46s -> %s" % (tag, fqn, where))
            else:
                problems.append('%s: <%s android:name="%s"> → %s 没有对应的类声明'
                                % (os.path.relpath(path, ROOT), tag, name, fqn))
    print("检查 %d 处相对类名声明" % checked)
    if problems:
        print("\n========== manifest 悬空声明 ==========")
        for p in problems:
            print("FAIL " + p)
        print("\n修法：")
        print("  · 悬空声明 → 把 android:name 改成真正存在的类（或删掉该组件声明）；")
        print("  · XML 结构非法 → 检查注释是不是写进了标签属性之间（XML 注释只能整段放在")
        print("    元素外面），或属性值里有没有漏引号、漏闭合。")
        return 1
    print("全部命中源文件里的类声明。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
