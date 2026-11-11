#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UI 一致性关卡（CI 用，非 0 即失败）。

## 为什么需要它
UI 上的这些问题**编译器都不报**，只在真机上以「深色看不清」「换肤没跟着变」「排版乱」暴露：

- **字面色值**：布局里写 `#DD000000`，浅色看着正常，values-night 一覆盖底色就成黑字压深底
  （issue#44 的悬浮球面板就是这么来的）。
- **硬编码文案**：`android:text="…"` 写死在布局里，绕开 strings.xml，改文案要动布局、没法多语言。
- **dp 字面量**：padding/margin 此前有 14 种取值（2/3/4/6/8/10/12/14/16/18/20/24/90/96/104），
  同类场景一会儿 10 一会儿 12 —— issue#42「排版比较混乱」的观感来源。
- **漏 import R**：用了 `R.string.*` 却没 `import <namespace>.R`，本地没 SDK 时只有 CI 编译
  才发现（run#594 三个文件中招，整轮构建白跑）。

## 规则
1. `res/layout*/**/*.xml`、`res/drawable/*.xml` 里 `#RGB`/`#ARGB` 字面色值 → 失败。
   白名单：`<vector>` 图标内的 `fillColor`/`strokeColor` —— 单色图标是**遮罩**，
   颜色由使用处 `android:tint` / `setColorFilter` 决定（本仓 `ic_apx_*` 即如此）。
2. 布局里 `android:text|hint|contentDescription` 含中文 → 失败（应移入 strings.xml）。
3. 布局里 padding / layout_margin 写 dp 字面量 → 失败（0dp 与 `?attr` 放行）。
   梯度在 `values/dimens.xml`。
4. 用了 `R.*` 却没 `import <模块 namespace>.R` → 失败（`android.R` 不算）。
5. 代码里 `R.id.xxx` 必须在布局里有对应 `@+id/xxx` → 失败（`android.R.id.*` 跳过）。
6. `xxx.visibility` / `xxx.text` 这类用法，其字段必须在同一文件里有声明 → 失败。
7. Kotlin/Java 里的中文文案存量 → **棘轮**：只统计 + 超基线告警，不让 CI 变红。
   这 500+ 处分散在 UI 与各模块（模块状态串、LocalSend 结果串所在类没有 Context，
   要资源化得先注入 App 级 Context），属独立重构，不适合顺手塞进某一批。

注释行（`<!-- -->` 与 `//`）不参与判定 —— 文档里举例提到色值是正常的。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

CJK = re.compile(r"[\u4e00-\u9fff]")
LITERAL_COLOR = re.compile(r'"#[0-9A-Fa-f]{3,8}"')
TEXT_ATTR = re.compile(r'android:(text|hint|contentDescription)="([^"]*)"')
SPACING_ATTR = re.compile(r'android:(padding|layout_margin)[A-Za-z]*="([^"]*)"')
R_USE = re.compile(
    r"(?<![\w.])R\.(string|color|layout|id|drawable|style|array|dimen|raw|xml|anim|menu"
    r"|plurals|mipmap|font|integer|bool)\b")
# 中文文案棘轮基线：只许降不许升。降下来之后把这里调成新值即可锁住战果。
# 530 → 578（miuix-ui）：Miuix 0.9.4 迁移新增 MiuixShowcase 组件总览演示页（纯 demo，
# 不碰业务状态），演示文案暂留 Kotlin；后续资源化时把这里降回并锁住。
# 629 → 634（v223.18）：新增 hid/HidMouse.kt —— 鼠标三条出口的错误原因文案与
# HidKeys/HotkeyController 同构（那些也是 Kotlin 字符串，不是 UI 文案，进 strings.xml 没意义：
# 它们是 Uplink.reason 的诊断数据，会被通知栏/诊断行拼进句子）。
# 690 → 689（2026-11-01 面板重排）→ 687（补入口这一版）：设置页新增的
# 「键盘外观 / 音频方向 / 输入与外设」三段 17 条文案全部进 strings.xml（不写死在 Kotlin），
# 合并 master 后实测 687（面板重排把几处 Kotlin 文案换成了资源引用），按实测值收紧锁住战果。
# 687 → 686：insets 修复那一版实测 686。
# 686 → 680（v223.24 主屏键盘页对齐操控面）：键盘页整块重写时把「快捷键 / 新增 / 上下移 /
# 改键 / 删除 / 遥控键面 / 候选键名」全部搬进 strings.xml（KeyboardPage.kt 的中文字面量
# 20 → 0，本文件是唯一还在乎这件事的地方），实测 680，收紧锁住。
# 676 → 667（v223.27）：键盘页去掉快捷面板与编辑弹窗后，Kotlin 里的中文文案净减 9 处
# （KeyboardPage.kt 的相关字面量在 v223.24 已搬进 strings.xml，本轮删的是引用它的代码），
# 实测 667，按实测值收紧锁住战果。
# 656 → 657（2026-11-02 审查条 1.8 的修复）：状态页在 runtime 未建时给模块行兜底一句
# 「未启动（点上方开始）」。这是**状态串**而不是可资源化文案（它和 detailOf() 的返回值拼在
# 同一行，跟 HidKeys/HidMouse 那批 Uplink.reason 同性质），按实测值收紧，不手填。
# 657 → 663（同批）：**这条归因是错的**，6 处不是注释而是用户可见文案 —— 脚本的
# count_chinese_literals 跳过 `// * /*` 开头的注释行，注释压根不计；真实 +6 来自
# TvOverlayPermission 的权限引导、TvFilePrompt「已接收文件：」、TvNotificationPrompt
# 「通知浮窗未显示（系统拒绝悬浮窗）：」、TvServerService 的场景名各一条。棘轮只能往下，
# 那 6 处应搬进 tv 模块的 strings.xml（2026-11-02 审查 v5 §9.2-D）。
# 663 → 659：主壳底栏四个导航 label 进 strings.xml。
# 659 → 652：TV 侧 7 处用户可见文案搬进 android/tv/src/main/res/values/strings.xml
#   （权限引导 1、场景名 2、已接收文件 1、浮窗失败 1，另两处同句重复），
#   即上面那 6 处（实际 7 处）的真正归属。
# 652 → 646（v223.29.4）：键帽材质三档 / 按键特效四档的**选项文案**（7 处）进
#   strings.xml，KeyPref 只按档位取 id（keycapStyleLabels / effectLabels）；
#   同时设置页背景段顺手资源化，净减 6 处，按实测值收紧锁住。
# 652 -> 647（v223.29.4）：键帽材质三档 / 按键特效四档的**选项文案**进
#   strings.xml，KeyPref 只按档位取 id；同时设置页背景段顺手资源化。
#   口径：本值量的是**真远端 base**（origin/master 9a2c84b3，自身实测 653、
#   基线 652，也带着 +1 告警）：base 653 -> 本支 647 = 净减 6。此前按旧本地线
#   （落后真远端 23 个提交）记的 646 是**错的口径**，不是这一支的真实值。
# 647 -> 643（v223.29.9）：状态页顶部「运行状态」整块移除（连 overallLabel /
#   overallDetail 两条 Kotlin 字面量一起删），新增的设备形态 / 悬浮面板高度文案
#   全部走 strings.xml（DeviceFormPref 只按档位取 getString），净减 4，按实测收紧。
# 643 -> 657（v223.29.49，issue#168 追补批）：设置页新增「球面图标」标题行与 BallIcon
#   的未知图标异常文案等 Kotlin 字面量，本批按实测上调；后续新文案仍应走 strings.xml。
# 657 -> 664（issue#168 第 5/11/12 条批次，未发版）：悬浮窗权限引导行、手柄自定义卡片
#   与日志文案等新增 Kotlin 字面量，本批按实测上调；后续新文案仍应走 strings.xml。
# 664 -> 665（第 9 条相册皮肤，未发版）：设置页「相册」入口字样一处。
# 665 -> 668（通知同步 0x40/0x42，未发版）：设置页「键盘与无障碍」组新增开关标题/摘要、
#   通知使用权入口、跳系统设置失败提示共 4 条 Kotlin 字面量（沿用本文件既有混用风格），
#   本批按实测上调；后续新文案仍应优先走 strings.xml。
# 668 -> 671（查找手机 0x38，未发版）：FindPhoneRinger 的日志 TAG 与两条 stopInternal
#   原因标记（"30s 到时"/"对端请求"）——诊断数据而非用户可见文案，进 strings.xml 没意义
#   （与 HidMouse 错误文案同口径），本批按实测上调。
# 671 -> 674（文件管理三方向，未发版）：文件面板 v2 一处状态行内嵌提示（FilePanelActivity
#   "沿用传统回调（本机无 AndroidX）"，代码行尾注释被行内引号误计 1 处）+ PhoneFiles 根入口
#   数据名（"内部存储"，经 0x45 协议返回的对端展示名，PC 端文案已走 web 文案层）。
#   后续新文案仍应优先走 strings.xml。
# 658 -> 660（issue#168 二轮，未发版）：设置页「相册按钮」开关行内嵌标题与摘要 2 处
#   （SettingsPage 悬浮球分组，与同组「球面图标」「相册」等既有行内文案同口径）。
# 660 -> 582（v223.29.73 UI 审查批次 2）：SettingsPage.kt 的 73 处中文字面量全部迁进
#   strings.xml（ui_set_* 74 条 + 日志筛选两个档位数组）—— 设置页是最后一块大存量，
#   迁完即锁住战果。日志导出文件头（appendLine 的 # 时间：等）是落盘文件内容而非 UI 文案，
#   按 v223.18 口径留在 Kotlin，不追求清零。
TEXT_BASELINE = 582


def strip_comments(text):
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)

# 文案属性的整篇匹配版：`android:text="A\nB"` 在 XML 里就是**真换行**，逐行匹配会漏
# （本仓真有三处长文这么写：藏在分组卡片里、既不进 strings.xml 也不被本关卡拦住）。
TEXT_ATTR_ML = re.compile(r'android:(text|hint|contentDescription)="([^"]*)"', re.S)


def xml_files():
    out = []
    for sub in ("layout", "layout-land", "drawable"):
        d = os.path.join(RES, sub)
        if os.path.isdir(d):
            out += [os.path.join(d, f) for f in sorted(os.listdir(d)) if f.endswith(".xml")]
    return out


def is_vector_masking(path):
    """单色 vector 图标：颜色写死是遮罩惯例，交给使用处 tint。"""
    return "<vector" in open(path, encoding="utf-8").read(400)


def check_literals_and_text(problems):
    checked = 0
    for p in xml_files():
        rel = os.path.relpath(p, ROOT)
        body = strip_comments(open(p, encoding="utf-8").read())
        is_layout = "/layout" in p and "drawable" not in p
        checked += 1

        if not is_vector_masking(p):
            for i, line in enumerate(body.splitlines(), 1):
                if LITERAL_COLOR.search(line):
                    problems.append("%s:%d 字面色值（应改用 @color/ 令牌）：%s"
                                    % (rel, i, line.strip()[:90]))

        if is_layout:
            for m in TEXT_ATTR_ML.finditer(body):
                if CJK.search(m.group(2)):
                    i = body.count("\n", 0, m.start()) + 1
                    problems.append('%s:%d 硬编码文案（应移入 strings.xml）：%s="%s…"'
                                    % (rel, i, m.group(1), m.group(2)[:24]))
    return checked


def check_spacing(problems):
    """布局里的 padding / margin 必须走 @dimen 令牌（0dp 与 ?attr 放行）。"""
    for sub in ("layout", "layout-land"):
        d = os.path.join(RES, sub)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".xml"):
                continue
            path = os.path.join(d, f)
            rel = os.path.relpath(path, ROOT)
            for i, line in enumerate(open(path, encoding="utf-8"), 1):
                body = re.sub(r"<!--.*?-->", "", line)
                for m in SPACING_ATTR.finditer(body):
                    val = m.group(2)
                    if val == "0dp" or val.startswith(("@dimen/", "?")):
                        continue
                    problems.append('%s:%d %s="%s" 是 dp 字面量，应改用 @dimen/space_*'
                                    % (rel, i, m.group(1), val))


def check_r_import(problems):
    """用了 R.* 却没 `import <namespace>.R` —— 本地无 SDK 时这类错误只有 CI 编译暴露。

    判据是**调用点**而不是「文件在哪个包」：AGP 按模块 namespace 生成 R
    （app=com.allperiph、tv=com.allperiph.tv），同包文件不用 import，其余都要。
    """
    for mod, ns in (("app", "com.allperiph"), ("tv", "com.allperiph.tv")):
        base = os.path.join(ROOT, "android", mod, "src", "main", "java")
        if not os.path.isdir(base):
            continue
        for dirpath, _d, files in os.walk(base):
            for f in files:
                if not f.endswith((".kt", ".java")):
                    continue
                p = os.path.join(dirpath, f)
                txt = open(p, encoding="utf-8", errors="replace").read()
                if not R_USE.search(txt):
                    continue
                pkg = re.search(r"^\s*package\s+([\w.]+)", txt, re.M)
                if pkg and pkg.group(1) == ns:
                    continue
                if re.search(r"^import\s+" + re.escape(ns) + r"\.(R|\*)\s*$", txt, re.M):
                    continue
                problems.append("%s 用了 R.* 却没有 `import %s.R`（编译必炸）"
                                % (os.path.relpath(p, ROOT), ns))


def check_view_refs(problems):
    """代码里 `R.id.xxx` 引用的 id 必须真的在布局里定义过。

    为什么需要：删视图时很容易只删布局、或改错 id —— 这类问题**编译器不报**
    （`R.id.xxx` 只要有定义就合法），运行时 `findViewById` 返回 null，接着就是 NPE
    或「这块 UI 永远不刷新」。本仓刚发生过一次：精简状态块时删掉了 rowsLink/tvLinkRtt
    的字段与绑定，但 buildLinkRows() 还在用 rowsLink。

    只查本包自己的 `R.id.*`；`android.R.id.content` 这类系统 id 天然不在布局里，跳过。
    """
    res = os.path.join(ROOT, "android", "app", "src", "main", "res")
    defined = set()
    for sub in ("layout", "layout-land"):
        d = os.path.join(res, sub)
        if not os.path.isdir(d):
            continue
        for f in os.listdir(d):
            if f.endswith(".xml"):
                txt = open(os.path.join(d, f), encoding="utf-8").read()
                defined |= set(re.findall(r'android:id="@[+]id/(\w+)"', txt))

    java_root = os.path.join(ROOT, "android", "app", "src", "main", "java")
    for dirpath, _d, files in os.walk(java_root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(dirpath, f)
            for i, line in enumerate(open(path, encoding="utf-8"), 1):
                safe = line.replace("android.R.id.", "ANDROID_R_ID.")
                for m in re.finditer(r'(?<![\w.])R\.id\.(\w+)', safe):
                    vid = m.group(1)
                    if vid not in defined:
                        problems.append(
                            "%s:%d R.id.%s 在布局里没有定义（findViewById 会返回 null）"
                            % (os.path.relpath(path, ROOT), i, vid))


def check_view_fields(problems):
    """删掉视图字段后，同一文件里遗留的 `字段.xxx` 用法必须能被发现。

    本轮踩过：精简状态块时删了 `boxRunning` / `tvRunningSummary` 的 lateinit 声明与
    findViewById 绑定，但 `boxRunning.visibility = ...` 还在 —— 编译器会报
    Unresolved reference，可**要等一整轮 CI 构建**才知道；`R.id` 关卡也查不到
    （布局里 id 还在）。

    判据刻意收得很窄，避免误报：
      · 只认「小写字母开头、长度 ≥ 3」的接收者；
      · 只认视图属性/方法（visibility / text / isChecked / setOnClickListener …）；
      · 排除 `android.`、`java.`、`kotlin.` 等包名前缀与常见局部名；
      · 该名字必须在本文件的 `lateinit var` 声明里出现过**或**出现在
        `字段 = ...findViewById` 绑定里，否则不判 —— 也就是本规则只用来抓
        「本文件曾经声明过这类字段」的残留，宁可漏报不误报。
    """
    PKG = ("android", "java", "kotlin", "kotlinx", "androidx", "com", "org", "io")
    VIEW_PROP = re.compile(
        r'(?<![\w.])([a-z]\w{2,})\.(?:visibility|isChecked|isEnabled|alpha|'
        r'setText|setTextColor|setTextSize|setBackgroundColor|setBackgroundResource|'
        r'setOnClickListener|setPadding|setValue|isSelected|setVisibility|setImageResource)\b')
    FIELD_DECL = re.compile(r'\blateinit\s+var\s+(\w+)\s*:')
    for dirpath, _d, files in os.walk(os.path.join(ROOT, "android", "app", "src", "main", "java")):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(dirpath, f)
            txt = open(path, encoding="utf-8").read()
            declared = set(FIELD_DECL.findall(txt))
            if not declared:
                continue          # 本文件没有 lateinit 视图字段，不适用本规则
            local = set(re.findall(r'\b(?:val|var)\s+(\w+)\s*[:=]', txt))
            for i, line in enumerate(txt.splitlines(), 1):
                if line.strip().startswith(("//", "*", "/*")):
                    continue
                for m in VIEW_PROP.finditer(line):
                    name = m.group(1)
                    if name in declared or name in local or name in PKG:
                        continue
                    # 循环变量 / 参数里常见的短名一律放过（宁可漏报）
                    if name in ("btn", "row", "tv", "box", "col", "cell", "node"):
                        continue
                    problems.append(
                        "%s:%d 用到 `%s.…`，但本文件没有它的 lateinit var 声明"
                        "（删字段时容易漏改调用点）" % (os.path.relpath(path, ROOT), i, name))


def count_chinese_literals():
    """main 源码里的中文字面量（排除注释行与日志行）。`src/test` 的测试数据不算。"""
    hits, per_file = 0, {}
    q = re.compile(r'"([^"]*)"')
    for java in [os.path.join(ROOT, "android", m, "src", "main", "java")
                 for m in ("app", "tv", "shared")]:
        if not os.path.isdir(java):
            continue
        for dirpath, _d, files in os.walk(java):
            for f in files:
                if not f.endswith((".kt", ".java")):
                    continue
                src = os.path.join(dirpath, f)
                n = 0
                for line in open(src, encoding="utf-8", errors="replace"):
                    if line.strip().startswith(("//", "*", "/*")):
                        continue
                    if "Log." in line or "println" in line:
                        continue
                    for m in q.finditer(line):
                        if CJK.search(m.group(1)):
                            n += 1
                if n:
                    per_file[os.path.relpath(src, ROOT)] = n
                    hits += n
    return hits, per_file


def main():
    problems = []
    checked = check_literals_and_text(problems)
    check_spacing(problems)
    check_r_import(problems)
    check_view_refs(problems)
    check_view_fields(problems)
    hits, per_file = count_chinese_literals()

    print("检查 %d 个布局/资源文件" % checked)
    print("Kotlin/Java 中文文案存量：%d 处（棘轮基线 %d）" % (hits, TEXT_BASELINE))
    for p, n in sorted(per_file.items(), key=lambda kv: -kv[1])[:5]:
        print("   %4d  %s" % (n, p))
    if hits > TEXT_BASELINE:
        print("::warning title=中文文案存量上升::当前 %d 处，基线 %d 处（+%d）。"
              "新增 UI 文案请走 strings.xml；若确为新增中文数据，请在同一提交里调大脚本的 TEXT_BASELINE。"
              % (hits, TEXT_BASELINE, hits - TEXT_BASELINE))
    elif hits < TEXT_BASELINE:
        print("::notice title=中文文案存量下降::当前 %d 处，基线 %d 处（-%d）。"
              "把脚本里的 TEXT_BASELINE 调到当前值即可锁住战果。"
              % (hits, TEXT_BASELINE, TEXT_BASELINE - hits))

    if problems:
        print("\n========== UI 一致性违规 ==========")
        for x in problems:
            print("FAIL " + x)
        print("\n修法：色值进 values/colors.xml（要随主题变就在 values-night 里成对覆盖）；"
              "文案进 values/strings.xml 并改引 @string/；间距引用 @dimen/space_*。")
        return 1

    print(f"通过：布局无字面色值、无硬编码文案、无 dp 字面量间距；R 引用均有 import 且 id 都在布局里。"
          f"（注意口径：上面的『无硬编码文案』只覆盖 res/layout*；界面层 Kotlin 的中文存量走"
          f"棘轮 {hits}/{TEXT_BASELINE}，只告警不判红——2026-11-02 审查条 2.4 指的正是这个"
          f"不对称，别把本行当成『Kotlin 里没有中文』。）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
