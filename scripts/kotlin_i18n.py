#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Kotlin 里的**用户可见中文文案**抽到 strings.xml，并把调用点改成 getString(...)。

背景：手机端 Kotlin 里散着上千处硬编码中文字面量，改一处措辞要在上百个文件里翻，
术语也就跟着各写各的（"被控 / 受控 / 对端"三套说法就是这么来的）。
本脚本只做**机械搬运**：措辞与命名规则由人决定（见 res/values/strings.xml 头部的词汇表）。

识别范围（宁少勿错，只认"确定是 UI 文案"的调用形态）：
    Toast.makeText(ctx, "...") / settingRow("标题", "按钮") / listOf(...) / xxx.add("...")
    .setTitle / .setMessage / .setPositiveButton / .setNegativeButton / .setNeutralButton
    text = "" / setText("") / hint = "" / contentDescription = ""
    本项目的辅助函数 toast("") / status("") / mkText("")

自动搬运的条件（不满足就打印出来交人工）：
  · 不在 Log.* 里，不在注释里（含 /* */ 块注释）
  · 字面量里没有反斜杠转义与 `%`（转义规则不同）
  · 字面量后面不接 `+`（字符串拼接）
  · 插值只支持 `$x` / `${x}`（x 是简单标识符）：按首次出现顺序转 %1$s / %2$s
  · 转换后不得残留 `$`（说明还有复杂模板）

去重规则：
  · 同一模块内**相同文案复用同一个资源名**（"取消"只会有一条 ui_common_cancel）
  · 优先复用 strings.xml 里**已有且文案相同**的资源（如「重新检测」→ action_refresh）

用法：
    python scripts/kotlin_i18n.py <文件...>            # 预览（不落盘）
    python scripts/kotlin_i18n.py <文件...> --apply    # 落盘，并写入对应模块的 strings.xml
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CJK = re.compile(r'[\u4e00-\u9fff]')
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
INTERP_SIMPLE = re.compile(r'(?<!\\)\$([A-Za-z_][A-Za-z0-9_]*)')
# ${表达式}：允许属性访问 / 函数调用 / 逗号等，但**不含引号与嵌套花括号**（那些留给人工，
# 例如 ${if (running) "运行中" else "未运行"} —— 里面还有字面量，机械搬运会出错）
INTERP_BRACE = re.compile(r'(?<!\\)\$\{([^"${}]+)\}')
# 相邻字面量拼接：`"a" + "b"`（可跨行、可带缩进）。不合并的话它们永远进不了识别范围
# （前一行的字面量后面跟着 `+`，会被当成"字符串拼接"跳过）
ADJACENT = re.compile(r'"((?:[^"\\]|\\.)*)"[ \t]*\+[ \t]*(?:\r?\n[ \t]*)?"((?:[^"\\]|\\.)*)"')

# 「行内前缀（到字面量左引号为止）的尾部 → 该字面量在 UI 里扮演的角色」
TAIL_PATTERNS = [
    (r'Toast\.makeText\([^,]*,\s*$', 'toast'),
    (r'settingRow\([^,]*,\s*$', 'btn'),
    (r'settingRow\(\s*$', 'label'),
    (r'\.add\(\s*$', 'item'),
    (r'\blistOf\(\s*$', 'item'),
    (r'\barrayOf\(\s*$', 'item'),
    (r'setTitle\(\s*$', 'dialog'),
    (r'setMessage\(\s*$', 'dialog'),
    (r'setPositiveButton\(\s*$', 'btn'),
    (r'setNegativeButton\(\s*$', 'btn'),
    (r'setNeutralButton\(\s*$', 'btn'),
    (r'\bsetText\(\s*$', 'text'),
    (r'\bhint\s*=\s*$', 'hint'),
    (r'\bcontentDescription\s*=\s*$', 'cd'),
    (r'\btext\s*=\s*$', 'text'),
    (r'\btoast\(\s*$', 'toast'),
    (r'\bstatus\(\s*$', 'status'),
    (r'\bmkText\(\s*$', 'text'),
    # 条件表达式里的文案：if (...) "A" else "B" / ?: "C"
    (r'\bif\s*\([^()]*(?:\([^()]*\)[^()]*)*\)\s*$', 'text'),
    (r'\belse\s*$', 'text'),
    (r'\?:\s*$', 'text'),
    (r'\bifEmpty\s*\{\s*$', 'text'),
    # 本项目其余 UI 辅助函数
    (r'\bpickChoice\(\s*$', 'label'),
    (r'\bfieldLabel\(\s*$', 'label'),
    (r'\bsubRow\(\s*$', 'label'),
    (r'\bPage\([^,]*,\s*$', 'label'),
    (r'\bPage\(\s*$', 'label'),
    (r'\bTriple\(\s*$', 'label'),
    (r'\bmediaTile\(\s*$', 'label'),
    (r'\bactionTile\(\s*$', 'label'),
    (r'\bconfirmPower\([^,]*,\s*$', 'label'),
    (r'\bputExtra\([^,]*,\s*$', 'text'),
    (r'\bcreateChooser\([^,]*,\s*$', 'dialog'),
]

# 高频短词：跨文件共用一条资源，避免"取消 / 取消_2 / 取消_3"
COMMON = {
    '取消': 'ui_common_cancel', '关闭': 'ui_common_close', '保存': 'ui_common_save',
    '删除': 'ui_common_delete', '确定': 'ui_common_confirm', '知道了': 'ui_common_got_it',
    '打开': 'ui_common_open', '连接': 'ui_common_connect', '退出': 'ui_common_exit',
    '新建': 'ui_common_new', '编辑': 'ui_common_edit', '刷新': 'ui_common_refresh',
    '返回': 'ui_common_back', '名称': 'ui_common_name', '大小': 'ui_common_size',
    '时间': 'ui_common_time', '重试': 'ui_common_retry', '完成': 'ui_common_done',
}

# 「中文关键词 → 英文 token」：只影响资源名可读性，不动文案本身
KEYWORDS = [
    ('剪贴板', 'clipboard'), ('副屏', 'screen'), ('遥控', 'remote'), ('快捷键', 'hotkey'),
    ('模板', 'template'), ('主题', 'theme'), ('配色', 'color'), ('背景', 'background'),
    ('无障碍', 'a11y'), ('权限', 'perm'), ('授权', 'perm'), ('日志', 'log'),
    ('设备', 'device'), ('输入', 'input'), ('文件', 'file'), ('文件夹', 'folder'),
    ('麦克风', 'mic'), ('音箱', 'speaker'), ('触控板', 'touchpad'), ('键盘', 'keyboard'),
    ('手柄', 'gamepad'), ('连接', 'connect'), ('断开', 'disconnect'), ('发送', 'send'),
    ('接收', 'recv'), ('导出', 'export'), ('导入', 'import'), ('分享', 'share'),
    ('删除', 'delete'), ('保存', 'save'), ('取消', 'cancel'), ('退出', 'exit'),
    ('关闭', 'close'), ('打开', 'open'), ('添加', 'add'), ('新建', 'new'),
    ('编辑', 'edit'), ('刷新', 'refresh'), ('检测', 'check'), ('重启', 'reboot'),
    ('关机', 'poweroff'), ('电源', 'power'), ('全屏', 'fullscreen'), ('手动', 'manual'),
    ('失败', 'failed'), ('成功', 'ok'), ('完成', 'done'), ('已', 'done'),
    ('未', 'not'), ('没有', 'no'), ('还没', 'no'), ('空', 'empty'),
    ('只', 'only'), ('先', 'first'), ('请', 'please'), ('为什么', 'why'),
    ('需要', 'need'), ('确认', 'confirm'), ('速度', 'speed'), ('延迟', 'latency'),
    ('动效', 'motion'), ('动画', 'motion'), ('震动', 'haptic'), ('振动', 'haptic'),
    ('音效', 'sound'), ('按键音', 'sound'), ('键位', 'keyrow'), ('排列', 'arrange'),
    ('键帽', 'keycap'), ('密度', 'density'), ('自检', 'selftest'), ('选择', 'pick'),
    ('回本机', 'to_local'), ('本机', 'local'), ('转发', 'forward'), ('查看', 'view'),
    ('相册', 'gallery'), ('图库', 'gallery'), ('图片', 'image'), ('手电', 'torch'),
    ('红外', 'ir'), ('传感器', 'sensor'), ('定位', 'gps'), ('面板', 'panel'),
    ('悬浮', 'overlay'), ('光标', 'cursor'), ('滑动', 'swipe'), ('轻点', 'tap'),
    ('长按', 'longpress'), ('拖拽', 'drag'), ('方向键', 'dpad'), ('音量', 'volume'),
    ('播放', 'play'), ('暂停', 'pause'), ('主页', 'home'), ('菜单', 'menu'),
    ('遥控器', 'remote'), ('操控面', 'console'), ('状态', 'state'), ('通道', 'channel'),
    ('注入', 'inject'), ('网关', 'gateway'), ('系统', 'system'), ('设置', 'settings'),
]


def tokens_of(text: str, limit: int = 4) -> list:
    out = []
    for zh, en in KEYWORDS:
        if zh in text and en not in out:
            out.append(en)
        if len(out) >= limit:
            break
    return out


def sanitize(name: str) -> str:
    name = re.sub(r'[^a-z0-9_]+', '_', name.lower())
    name = re.sub(r'_+', '_', name).strip('_')
    if not name or not name[0].isalpha():
        name = 's_' + name
    return name[:55].rstrip('_')


def escape_xml(s: str) -> str:
    # 注意：不做 % → %%（含 % 的字面量已在入参阶段被拒），否则会把我自己插入的 %1$s 也转义掉
    return (s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
             .replace("'", "\\'").replace('"', '\\"'))


def unescape_xml(s: str) -> str:
    return (s.replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')
             .replace("\\'", "'").replace('\\"', '"'))


def module_of(path: str) -> str:
    p = path.replace('\\', '/')
    if '/android/tv/' in p:
        return 'tv'
    if '/android/app/' in p:
        return 'app'
    return ''


def strings_xml_of(module: str) -> str:
    return os.path.join(ROOT, 'android', module, 'src', 'main', 'res', 'values', 'strings.xml')


def load_existing(path: str):
    """返回 (已有的 名称集合, 已有文案→名称 映射)。带格式参数的条目不复用。"""
    if not os.path.exists(path):
        return set(), {}
    with open(path, encoding='utf-8') as f:
        text = f.read()
    names, by_value = set(), {}
    for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', text, re.S):
        name, attrs, value = m.group(1), m.group(2), m.group(3)
        names.add(name)
        if '%' in value or '\\' in value or 'translatable="false"' in attrs:
            continue
        by_value.setdefault(unescape_xml(value), name)
    return names, by_value


class Migrator:
    def __init__(self, module: str):
        self.module = module
        self.names, self.by_value = load_existing(strings_xml_of(module))
        self.existing = set(self.names)   # 文件里本来就有的名字：**绝不能再写一遍**（aapt 会因重名直接失败）
        self.resources = {}     # name -> value（本次真正要新增的）
        self.reused = []        # 复用了已有资源的 (文案, 资源名)

    def name_for(self, file_prefix: str, kind: str, raw: str) -> str:
        # ① 文案完全相同 → 复用（同一句话在三个页面只有一条资源）
        if raw in self.by_value:
            name = self.by_value[raw]
            self.names.add(name)
            self.reused.append((raw, name))
            return name
        # ② 高频短词用固定名（该名字已被别的文案占用时让位，走普通命名）
        if raw in COMMON and COMMON[raw] not in self.names:
            name = COMMON[raw]
        else:
            toks = tokens_of(raw)
            name = sanitize('ui_%s_%s_%s' % (file_prefix, kind, '_'.join(toks) if toks else 'x'))
        base, n = name, 2
        while name in self.names:      # ③ 保证不与已有资源重名（重名会让 aapt 直接失败）
            name, n = '%s_%d' % (base, n), n + 1
        self.names.add(name)
        self.by_value[raw] = name
        return name


def bad_escape(s: str) -> bool:
    """除换行转义（反斜杠 n）之外的反斜杠转义在 XML 资源里行为不同，都留给人工。"""
    return bool(re.search(r'\\(?!n)', s))


def join_literals(text: str):
    """把相邻字面量拼接合成一个（`"a" + "b"`，可跨行）。

    纯字面量拼接在语义上就是一条文案；不合并的话它们永远进不了识别范围
    （前一行的字面量后面跟着 `+`，会被当成"字符串拼接"跳过）。
    只处理：至少一处含中文、且各部分不含 `$` / `%` / 除换行以外的转义。
    返回 (新文本, 合并次数)。
    """
    count = 0
    while True:
        def _sub(m):
            nonlocal count
            a, b = m.group(1), m.group(2)
            if not CJK.search(a + b) or any(c in (a + b) for c in ('$', '%')):
                return m.group(0)
            if bad_escape(a) or bad_escape(b):
                return m.group(0)
            count += 1
            return '"%s%s"' % (a, b)
        new = ADJACENT.sub(_sub, text)
        if new == text:
            return text, count
        text = new


def match_kind(s: str):
    """按"尾部形态"判断这段前缀把字面量放在了 UI 的哪个位置。"""
    for pat, kind in TAIL_PATTERNS:
        if re.search(pat, s):
            return kind
    return None


def kind_of(lines, i: int, start_col: int):
    """本行前缀匹配不到时，往上找"参数列表开括号"那一行（多行调用写法）。

    例：Toast.makeText( / this, / "连不上…" —— 字面量独自一行，本行没有上下文，
    只能往上认领；往上只认"以 ( 结尾"的那一行，中途的 `,` 行跳过。
    """
    k = match_kind(lines[i][:start_col])
    if k:
        return k
    j, hops = i, 0
    while j > 0 and hops < 4:
        j -= 1
        s = lines[j]
        if not s.strip():
            continue
        hops += 1
        t = s.rstrip()
        if t.endswith(','):
            continue            # 还在参数列表中间，继续往上找
        if t.endswith('('):
            return match_kind(s)
        return None             # 其它情况不猜，交人工
    return None


def migrate_file(path: str, mg: Migrator, source_text: str = None):
    text = source_text if source_text is not None else open(path, encoding='utf-8').read()
    lines = text.split('\n')

    # getString(...) 不带接收者只能在 Activity / Fragment 里用；object / 辅助类里要显式传
    # Context —— 那是人工改动（要改函数签名），不机械搬，整文件跳过。
    if not re.search(r':\s*(?:\w+\.)*\w*(?:Activity|Fragment)\s*[({]', text):
        return lines, [], [(0, '整文件非 Activity/Fragment（getString 需显式 Context，交人工）',
                            os.path.basename(path))]

    prefix = os.path.basename(path).replace('.kt', '').replace('Activity', '').lower() or 'ui'
    skipped, migrated = [], []
    in_block = False

    for i, line in enumerate(lines):
        stripped = line.strip()
        if in_block:
            if '*/' in stripped:
                in_block = False
            continue
        if stripped.startswith('//') or stripped.startswith('*'):
            continue
        if stripped.startswith('/*') or stripped.startswith('/**'):
            in_block = '*/' not in stripped
            continue

        lits = [m for m in LITERAL.finditer(line) if CJK.search(m.group(1))]
        if not lits:
            continue

        edits, ok_all = [], True
        for m in lits:
            raw, start, end = m.group(1), m.start(), m.end()
            if 'Log.' in line[:start] and not line[:start].rstrip().endswith(','):
                skipped.append((i + 1, 'Log 内容', stripped))
                ok_all = False
                break
            if 'Log.' in line and 'Toast' not in line and 'setTitle' not in line and 'setMessage' not in line:
                skipped.append((i + 1, 'Log 内容', stripped))
                ok_all = False
                break
            # 属性初始化（val/var，含 companion object 里的）拿不到 Context：
            # 换成 getString 会直接编译不过 —— PAGE_TITLES / PAGE_SUBS 就这么翻过一次车。
            # 局部的 val 也一并放过（保守），交人工。
            if re.match(r'\s*(?:(?:private|internal|public|protected|override|lateinit|@\w+)\s+)*'
                        r'(?:const\s+)?(?:val|var)\s+\w+', line):
                skipped.append((i + 1, '属性初始化（可能没有 Context）', stripped))
                ok_all = False
                break
            if bad_escape(raw):
                skipped.append((i + 1, '含反斜杠转义', stripped))
                ok_all = False
                break
            if '%' in raw:
                skipped.append((i + 1, '含 %（格式串）', stripped))
                ok_all = False
                break
            if line[end:].lstrip().startswith('+') or line[:start].rstrip().endswith('+'):
                skipped.append((i + 1, '字符串拼接（含变量）', stripped))
                ok_all = False
                break
            kind = kind_of(lines, i, start)
            if kind is None:
                skipped.append((i + 1, '非 UI 调用形态', stripped))
                ok_all = False
                break

            # 插值 → 位置参数
            args, seen = [], {}

            def _sub(mm, seen=seen, args=args):
                ident = mm.group(1)
                if ident not in seen:
                    seen[ident] = len(seen) + 1
                    args.append(ident)
                return '%' + str(seen[ident]) + '$s'

            # 顺序要紧：先 $x、再 ${表达式}。反过来的话，${} 生成出来的 %1$s 里那个 "$s"
            # 会被 SIMPLE 规则再吃一遍，变成 %1%2$s —— 格式串直接坏掉（预览时抓到过一次）。
            value = INTERP_SIMPLE.sub(_sub, raw)
            value = INTERP_BRACE.sub(_sub, value)
            # 检查是否还有没被处理掉的模板：先把合法的 %N$s 摘掉再看 $，否则会误判自己插入的占位符
            leftover = re.sub(r'%\d+\$s', '', value)
            if '${' in value or re.search(r'(?<!\\)\$', leftover):
                skipped.append((i + 1, '复杂模板（残留 $）', stripped))
                ok_all = False
                break

            name = mg.name_for(prefix, kind, raw)
            # 复用了文件里已有的资源 → 只改调用点，**不能**再把这条写进新增列表
            # （否则同一 name 出现两次，aapt 报 "Found item String/xxx more than one time"）
            if name not in mg.existing:
                mg.resources.setdefault(name, escape_xml(value))
            edits.append((start, end, 'getString(R.string.%s%s)' % (name, ''.join(', ' + a for a in args))))
            migrated.append((i + 1, name, value))

        if not ok_all or not edits:
            continue
        for start, end, repl in reversed(edits):
            line = line[:start] + repl + line[end:]
        lines[i] = line

    return lines, migrated, skipped


def write_resources(module: str, resources: dict):
    path = strings_xml_of(module)
    with open(path, encoding='utf-8') as f:
        text = f.read()
    eol = '\r\n' if '\r\n' in text else '\n'
    block = ['', '    <!-- ↓↓↓ 从 Kotlin 迁入（scripts/kotlin_i18n.py）↓↓↓ -->']
    for name, value in resources.items():
        block.append('    <string name="%s">%s</string>' % (name, value))
    block.append('    <!-- ↑↑↑ 迁入结束 ↑↑↑ -->')
    text = text.replace('</resources>', eol.join(block) + eol + '</resources>')
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text)
    return path


def ensure_r_import(path: str, module: str) -> bool:
    with open(path, encoding='utf-8') as f:
        text = f.read()
    if 'R.string.' not in text or re.search(r'^import [\w.]*\.R$', text, re.M):
        return False
    rpkg = 'com.allperiph.tv.R' if module == 'tv' else 'com.allperiph.R'
    lines = text.split('\n')
    for i, line in enumerate(lines):
        if line.startswith('package '):
            lines.insert(i + 1, '\nimport %s' % rpkg)
            break
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write('\n'.join(lines))
    return True


def main():
    apply = '--apply' in sys.argv
    do_join = '--no-join' not in sys.argv
    files = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not files:
        print(__doc__)
        return 1

    total_m, total_s = 0, 0
    migrators = {}
    for rel in files:
        path = rel if os.path.isabs(rel) else os.path.join(ROOT, rel)
        module = module_of(path)
        if not module:
            print('!! 无法判断模块（路径需含 android/app 或 android/tv）: %s' % rel)
            continue
        mg = migrators.setdefault(module, Migrator(module))
        text = open(path, encoding='utf-8').read()
        joined = 0
        if do_join:
            text, joined = join_literals(text)
        lines, migrated, skipped = migrate_file(path, mg, text)
        print('=== %s ===' % rel)
        print('  合并相邻拼接 %d 处；可迁移 %d 条，跳过 %d 行' % (joined, len(migrated), len(skipped)))
        for ln, name, value in migrated:
            print('    %5d  %-44s %s' % (ln, name, value))
        if skipped:
            print('  —— 跳过（人工处理）——')
            seen = set()
            for ln, why, txt in skipped:
                key = (why, txt[:60])
                if key in seen:
                    continue
                seen.add(key)
                print('    %5d  [%s]  %s' % (ln, why, txt[:92]))
        total_m += len(migrated)
        total_s += len(skipped)
        if apply:
            with open(path, 'w', encoding='utf-8', newline='') as f:
                f.write('\n'.join(lines))
            added = ensure_r_import(path, module)
            print('  → 已改 %s%s' % (rel, '（补 import R）' if added else ''))
        print()

    for module, mg in migrators.items():
        if apply and mg.resources:
            p = write_resources(module, mg.resources)
            print('模块 %s：新增/复用资源 %d 条 → %s' % (module, len(mg.resources), os.path.relpath(p, ROOT)))
        elif mg.resources:
            print('模块 %s：将新增资源 %d 条' % (module, len(mg.resources)))
        if mg.reused:
            print('   其中复用已有资源 %d 处：%s' % (len(mg.reused), ', '.join(sorted({n for _, n in mg.reused}))))

    print('合计：可迁移 %d 条，跳过 %d 行%s' % (total_m, total_s, '（已落盘）' if apply else '（预览，未落盘）'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
