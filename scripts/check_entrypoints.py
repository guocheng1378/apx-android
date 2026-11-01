#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「能力入口」关卡（CI 用，非 0 即失败）。

## 为什么需要它
2026-11-01 的界面层大迁移（`c6734c8` 界面层迁 Compose + Miuix，PR #73）之后，真机反馈
「有些功能只有提示不执行，有些有功能没入口了」。逐个核对代码后发现，两类问题都有机械特征，
而现有 7 个关卡与编译器**都报不出来**：

- **有实现、无入口**：`GamepadActivity` 整页（含虚拟手柄两个出口）只有 Manifest 声明，
  全仓零 `startActivity`；`RootPointerBridge.setEnabled`（root 直读外接触控板）、
  `AudioModule.applyMic` / `applySpeaker`（麦克风 / 扬声器方向开关）、
  `KeyPref.setDensity` / `setKeycapStyle` / `setLayout`（键帽密度 / 材质 / 键位排列）零调用 ——
  功能完整，用户永远碰不到。
- **提示与实际不符**：设置页把「物理键盘全局转发」的开关写进了 `apx` 偏好，而
  `GlobalKeyForwarder` 只从 `apx_ime_bridge` 恢复 —— 本会话有效、重启后静默回退，
  用户看到的就是「开了没用」。

这两类的共同点是**编译器等权通过**：少一次 `startActivity`、键名拼错、偏好文件选错，
都不影响编译，只在真机上表现为「没反应 / 找不到」。

## 判据
1. **Manifest 里的 Activity 必须可达**：`exported="false"`、无 `intent-filter`、且全仓没有
   任何一处在同一文件外引用该功能名的 Activity → 失败。`exported=true` 或有 intent-filter 的
   （Launcher / 分享接收 / 快捷方式）不查。豁免需写明理由。
2. **能力 API 必须有人调**：`CAPABILITY_APIS` 用 `(所属类型, 方法名)` 描述「控制某项用户可见
   能力」的入口函数。判定口径：**定义该类型的文件之外**，存在 `方法名(` 的调用，且该调用所在
   文件提到了所属类型名（单例的 `Type.method(...)` 与实例的 `obj.method(...)` 都算）。
   全仓零调用即失败 —— 但**公开认可的可选**项要显式登记在 `ACCEPTED_NO_UI` 里（并写清理由）。
3. **偏好键名不许手写**：禁止页面代码直接 `getSharedPreferences("apx_ime_bridge")` /
   `getBoolean("kb_forward_enabled")` 一类字面量，必须经所属组件自己的读值出口
   （如 `GlobalKeyForwarder.isEnabled`）—— 键名写在两处必然有一天对不上。
   覆盖 `kb_forward_enabled` / `apx_key_overrides` / `apx_root_pointer` / `apx_usb_audio` /
   `apx_wifi_audio` / `apx_kb` 这几组已踩过坑或即将踩坑的键。

只做静态文本分析，不需要编译器。退出码非 0 = 有违规。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = [ROOT / "android/app/src", ROOT / "android/tv/src", ROOT / "android/shared/src"]
MANIFESTS = [
    ROOT / "android/app/src/main/AndroidManifest.xml",
    ROOT / "android/tv/src/main/AndroidManifest.xml",
]

# ① Activity 可达性豁免：类名 -> 理由
ACTIVITY_EXEMPT = {
    "ControlShortcutActivity": "有 intent-filter（快捷方式 / 磁贴），系统可直接拉起",
}

# ② 必须有人调用的能力 API：说明 -> 限定调用名（`类型.方法`）
#    只认限定名：同名方法在别的类上很常见（applyMic 在 USB / Wi‑Fi 两个模块里都有，
#    setEnabled 在 GestureShield / BallConfig / RootPointerBridge 里都有），
#    按裸方法名核对会互相顶替，把「某一路没入口」漏掉。
CAPABILITY_APIS = {
    "外接触控板 root 直读开关": "RootPointerBridge.setEnabled",
    "USB 麦克风方向开关": "AudioDirections.setUsbMic",
    "USB 扬声器方向开关": "AudioDirections.setUsbSpeaker",
    "Wi‑Fi 音频麦克风方向开关": "AudioDirections.setWifiMic",
    "Wi‑Fi 音频扬声器方向开关": "AudioDirections.setWifiSpeaker",
    "键帽密度": "KeyPref.setDensity",
    "键帽材质": "KeyPref.setKeycapStyle",
    "键位排列": "KeyPref.setLayout",
}

# 公开认可「暂时没有界面入口」的能力 API：说明 -> 理由
ACCEPTED_NO_UI = {
    "AccessibilityAutoEnable.tryEnableViaRoot":
        "手机端不自动改 secure 设置（改系统无障碍属于高影响动作）；"
        "设置页给的是「无障碍设置」按钮，由用户本人去系统页勾选",
    "KeyPref.setCustomRows":
        "自定义键盘布局的保存出口，界面入口在触控板的键盘面板（CustomKeyboardDialog）",
}

# ③ 必须经组件读值出口、不许页面手写的偏好键
FORBIDDEN_LITERAL_KEYS = (
    ("apx_ime_bridge", "GlobalKeyForwarder.prefs / isEnabled"),
    ("kb_forward_enabled", "GlobalKeyForwarder.prefs / isEnabled"),
    ("apx_key_overrides", "HidKeyOverrides.prefs / load / set / clear"),
    ("apx_root_pointer", "RootPointerBridge.prefs / isEnabled / setEnabled"),
    ("apx_usb_audio", "AudioModule.isMicOn / setMicPref"),
    ("apx_wifi_audio", "WirelessAudioModule.isMicOn / setMicPref"),
    ("apx_kb", "KeyPref.keycapStyleIndex / densityIndex / layoutIndex"),
)
# 允许出现字面量的文件（组件自己的实现处）
LITERAL_ALLOWED = {
    "android/app/src/main/java/com/allperiph/ui/GlobalKeyForwarder.kt",
    "android/app/src/main/java/com/allperiph/ui/HidKeyOverrides.kt",
    "android/app/src/main/java/com/allperiph/touchpad/RootPointerBridge.kt",
    "android/app/src/main/java/com/allperiph/audio/AudioModule.kt",
    "android/app/src/main/java/com/allperiph/audio/WirelessAudioModule.kt",
    "android/app/src/main/java/com/allperiph/ui/KeyPref.kt",
}


def kotlin_files():
    for root in SRC:
        for f in sorted(root.rglob("*.kt")):
            if "/build/" in f.as_posix():
                continue
            yield f


def activities():
    out = []
    for man in MANIFESTS:
        if not man.exists():
            continue
        text = man.read_text(encoding="utf-8")
        for m in re.finditer(r"<activity\b[^>]*>", text, re.S):
            blk = m.group(0)
            name = re.search(r'android:name="([^"]+)"', blk)
            if not name:
                continue
            out.append((man, blk, name.group(1)))
    return out


def strip_comments(text):
    """去掉整行注释（`//`、`*`、`/*`、`*/` 开头）—— 注释里提一句功能名不算入口。"""
    out = []
    for line in text.splitlines():
        t = line.lstrip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        out.append(line)
    return "\n".join(out)


def defining_files(type_name, texts):
    """声明 `object/class <Type>` 的文件 —— 那些文件里的 `method(` 是定义，不是调用。"""
    pat = re.compile(r"\b(?:object|class|interface)\s+" + re.escape(type_name) + r"\b")
    return {f for f, t in texts.items() if pat.search(t)}


def main():
    problems = []
    files = list(kotlin_files())
    texts = {f: f.read_text(encoding="utf-8") for f in files}

    # —— ① Activity 可达性 ——
    for man, blk, name in activities():
        simple = name.split(".")[-1]
        if simple in ACTIVITY_EXEMPT:
            continue
        if 'exported="true"' in blk:
            continue
        # 同一 <activity> 里带 intent-filter，或紧随其后的注释块里声明了 filter
        man_text = texts.get(man) or man.read_text(encoding="utf-8")
        idx = man_text.find(blk)
        tail = man_text[idx:idx + len(blk) + 400]
        if "<intent-filter" in tail.split("</activity>")[0]:
            continue
        # 只认**代码里的类引用**，不认注释里提一句「整页手柄」——
        # 这是本轮反例测出来的：把 startActivity 那行删掉、注释留着，按子串计数仍然算
        # "有引用"，关卡就白放了。认可两种写法：
        #   · `X::class`（Intent(ctx, X::class.java)）；
        #   · `X.start(...)`（本仓库常见的伴生启动器，如 RemoteInputActivity.start）。
        ref_pat = re.compile(
            r"(?<![A-Za-z0-9_])" + re.escape(simple) + r"\s*(?:::\s*class\b|\.\s*start\s*\()")
        refs = 0
        for f, t in texts.items():
            if f.name.startswith(simple + "."):
                continue
            if ref_pat.search(strip_comments(t)):
                refs += 1
        if refs == 0:
            problems.append(
                f"{man.relative_to(ROOT)}: Activity `{simple}` 全仓无任何启动调用，"
                f"且 exported=false / 无 intent-filter —— 用户无法到达（补入口，或登记豁免理由）")

    # —— ② 能力 API 有调用 ——
    for label, qualified in CAPABILITY_APIS.items():
        if qualified in ACCEPTED_NO_UI:
            continue
        type_name = qualified.split(".")[0]
        defs = defining_files(type_name, texts)
        # 允许限定名跨行（Kotlin 里 `Foo\n    .bar(` 是常见的自动换行结果）
        body = re.escape(qualified).replace(r"\.", r"\s*\.\s*")
        call_pat = re.compile(r"(?<![A-Za-z0-9_])" + body + r"\s*\(")
        hit = [f for f, t in texts.items() if f not in defs and call_pat.search(t)]
        if not hit:
            problems.append(
                f"能力 `{label}`（{qualified}）全仓零调用 —— 实现完整但界面无入口"
                f"（补入口，或加进 ACCEPTED_NO_UI 并写明理由）")

    # —— ③ 偏好键不许手写 ——
    for f, text in texts.items():
        rel = f.relative_to(ROOT).as_posix()
        if rel in LITERAL_ALLOWED:
            continue
        code = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("//"))
        for key, api in FORBIDDEN_LITERAL_KEYS:
            if f'"{key}"' in code:
                problems.append(
                    f"{rel}: 直接手写偏好键 `{key}` —— 键名写错不会编译报错、只会静默失效；"
                    f"请改走 {api}")

    if problems:
        print("能力入口关卡 FAIL：")
        for p in problems:
            print("  - " + p)
        print()
        print("已登记「公开认可暂无入口」：")
        for k, v in ACCEPTED_NO_UI.items():
            print(f"  - {k}：{v}")
        print()
        print("已登记 Activity 豁免：")
        for k, v in ACTIVITY_EXEMPT.items():
            print(f"  - {k}：{v}")
        sys.exit(1)

    acts = [a for a in activities()]
    print(f"能力入口关卡 OK：{len(acts)} 个 Activity 声明均可达或已登记豁免，"
          f"{len(CAPABILITY_APIS)} 个能力 API 均有调用方"
          f"（其中 {len(ACCEPTED_NO_UI)} 个已登记为公开认可暂无入口），"
          f"偏好键无手写字面量。")


if __name__ == "__main__":
    main()
