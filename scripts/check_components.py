#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""组件声明关卡（CI 用，非 0 即失败）。

## 为什么需要它
`check_manifest_classes.py` 管的是**反方向**：清单里写了 `android:name=".ui.X"`、
源码里有没有这个类。而更隐蔽的是**这条路**：源码里实现了一个系统组件（Activity /
Service / InputMethodService / Receiver / Provider / TileService / AccessibilityService…），
却**没有任何清单声明**。编译器对此完全沉默：

  · 类写好了，编译通过、出包正常；
  · 系统只会启动**清单里声明过**的组件，于是这个类永远不会被实例化；
  · 连日志都没有 —— 表现为「这个功能没有入口 / 开了没用」。

本仓真踩过，而且是最近才被本关卡发现的一例：
`com.allperiph.ui.ApxImeService`（批 S 的「物理键盘转发用输入法」）从引入起就
**从未**出现在任何 `AndroidManifest.xml` 里。清单里注册的是**同名不同包**的
`com.allperiph.shared.ime.ApxImeService`（被控端文本注入通道，完全是另一个功能）。
两个同名类的存在让这个悬空更难被发现 —— 搜 `ApxImeService` 总能搜到东西，
只有按**完整包名**逐个比对清单才会暴露。

## 判据
`android/{app,tv,shared}/src/main/**` 下每个 `class/object`，若其直接基类是
[BASES] 里的组件基类（去掉包名前缀后按简单名比较，覆盖
`android.app.Service` / `android.app.Activity` / `androidx.activity.ComponentActivity` 等写法），
则它的**全限定名必须出现在某个模块的清单里**（相对名以该模块 `namespace` 补全）。

白名单只放两类，且必须写理由：
  · 组件基类本身（`abstract class XxxBase : Service()` —— 子类才会被声明）；
  · 由框架按约定实例化的辅助类（如 `ContentProvider` 的子类若仅作库内部使用）。

只做静态文本分析，不需要编译器。退出码非 0 = 有悬空组件。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = os.path.join(ROOT, "android")
MODULES = ("app", "tv", "shared")

SRC = {m: os.path.join(ANDROID, m, "src", "main") for m in MODULES}
MANIFEST = {m: os.path.join(SRC[m], "AndroidManifest.xml") for m in MODULES}

# 需要清单声明才会被系统启动的组件基类（按简单名比对）
BASES = (
    "Activity", "ComponentActivity", "AppCompatActivity", "FragmentActivity",
    "Service", "IntentService", "InputMethodService", "AccessibilityService",
    "TileService", "BroadcastReceiver", "ContentProvider", "JobService",
    "WallpaperService", "VpnService", "MediaBrowserService", "RemoteViewsService",
)

# 组件标签：只在这些标签上认 android:name（<action>/<meta-data> 不是类名）
TAGS = ("application", "activity", "activity-alias", "service", "receiver", "provider")

# 允许「实现但不声明」的类：全限定名 → 理由
ALLOWED_UNDECLARED = {
    # 例：("com.allperiph.tv.ui.TvOverlay", "抽象基类，只有子类被声明"),
}

DECL = re.compile(
    r"\b(?:class|object)\s+(?P<name>\w+)\s*(?:\([^)]*\))?\s*:\s*(?P<bases>[A-Za-z0-9_.]+)",
    re.M,
)


def module_namespace(mod):
    gradle = os.path.join(ANDROID, mod, "build.gradle.kts")
    if os.path.exists(gradle):
        m = re.search(r'namespace\s*=\s*"([^"]+)"',
                      open(gradle, encoding="utf-8").read())
        if m:
            return m.group(1)
    return None


def declared_names():
    """所有模块清单里声明的类全限定名。"""
    out = set()
    for mod in MODULES:
        p = MANIFEST[mod]
        if not os.path.exists(p):
            continue
        ns = module_namespace(mod)
        body = re.sub(r"<!--.*?-->", "", open(p, encoding="utf-8").read(), flags=re.S)
        for tag in TAGS:
            for m in re.finditer(r"<" + tag + r"\b[^>]*android:name=\"([^\"]+)\"", body):
                raw = m.group(1)
                if raw.startswith("."):
                    out.add(ns + raw)
                elif "." not in raw:
                    out.add(ns + "." + raw)
                else:
                    out.add(raw)
    return out


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("//"))


def main():
    declared = declared_names()
    problems = []
    checked = 0
    missing_allowed = []

    for mod in MODULES:
        root = SRC[mod]
        if not os.path.isdir(root):
            continue
        for dirpath, _dirs, files in os.walk(root):
            for f in sorted(files):
                if not f.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, f)
                rel = os.path.relpath(path, ROOT)
                code = strip_comments(open(path, encoding="utf-8").read())
                pkg = re.search(r"^package\s+([\w.]+)", code, re.M)
                if not pkg:
                    continue
                pkg = pkg.group(1)
                for m in DECL.finditer(code):
                    base = m.group("bases").split(".")[-1]
                    if base not in BASES:
                        continue
                    checked += 1
                    fqn = pkg + "." + m.group("name")
                    if fqn in declared:
                        continue
                    if fqn in ALLOWED_UNDECLARED:
                        missing_allowed.append(fqn)
                        continue
                    line = code[: m.start()].count("\n") + 1
                    problems.append(
                        "%s:%d  %s 继承 %s 但没有任何清单声明 —— 系统永远不会启动它"
                        % (rel, line, fqn, base))

    # 白名单条目若已不再悬空，提醒清掉（避免白名单腐烂）
    for fqn in ALLOWED_UNDECLARED:
        if fqn not in missing_allowed:
            problems.append("白名单里的 `%s` 已不再悬空，请从 ALLOWED_UNDECLARED 删除" % fqn)

    if problems:
        print("组件声明关卡 FAIL：")
        for p in problems:
            print("  - " + p)
        print()
        print("两类修法：① 在清单里声明（相对名以模块 namespace 补全）；"
              "② 确认是废弃实现就删掉类，别留悬空。")
        sys.exit(1)

    print("组件声明关卡 OK：%d 个组件实现（Activity / Service / IME / 无障碍 / "
          "Receiver / Provider…）都能在清单里找到对应声明。" % checked)


if __name__ == "__main__":
    main()