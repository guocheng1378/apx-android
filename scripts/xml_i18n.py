#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把布局 XML 里的**用户可见文案**抽到 strings.xml，调用点改成 @string/...。

只认 android: 命名空间的 text / hint / contentDescription 三个属性（tools: 不动），
且值满足：含中文、不以 @ 或 ? 开头（那已经是资源引用）、不含 % 与反斜杠转义。
同一模块内相同文案复用同一条资源，并优先复用 strings.xml 里已有的同名文案。

用法：
    python scripts/xml_i18n.py <布局.xml ...>            # 预览
    python scripts/xml_i18n.py <布局.xml ...> --apply    # 落盘
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CJK = re.compile(r'[\u4e00-\u9fff]')
ATTR = re.compile(r'android:(text|hint|contentDescription)="([^"]*)"')

KEYWORDS = [
    ('剪贴板', 'clipboard'), ('副屏', 'screen'), ('遥控', 'remote'), ('快捷键', 'hotkey'),
    ('模板', 'template'), ('主题', 'theme'), ('配色', 'color'), ('背景', 'background'),
    ('无障碍', 'a11y'), ('日志', 'log'), ('设备', 'device'), ('输入', 'input'),
    ('文件', 'file'), ('麦克风', 'mic'), ('音箱', 'speaker'), ('触控板', 'touchpad'),
    ('键盘', 'keyboard'), ('手柄', 'gamepad'), ('连接', 'connect'), ('断开', 'disconnect'),
    ('发送', 'send'), ('导出', 'export'), ('导入', 'import'), ('分享', 'share'),
    ('删除', 'delete'), ('保存', 'save'), ('取消', 'cancel'), ('退出', 'exit'),
    ('关闭', 'close'), ('打开', 'open'), ('新建', 'new'), ('编辑', 'edit'),
    ('刷新', 'refresh'), ('检测', 'check'), ('重启', 'reboot'), ('关机', 'poweroff'),
    ('电源', 'power'), ('全屏', 'fullscreen'), ('手动', 'manual'), ('失败', 'failed'),
    ('状态', 'state'), ('设置', 'settings'), ('速度', 'speed'), ('延迟', 'latency'),
    ('光标', 'cursor'), ('滑动', 'swipe'), ('音量', 'volume'), ('播放', 'play'),
    ('主页', 'home'), ('菜单', 'menu'), ('传感器', 'sensor'), ('定位', 'gps'),
    ('电池', 'battery'), ('通知', 'notify'), ('授权', 'perm'), ('权限', 'perm'),
]


def tokens_of(text, limit=4):
    out = []
    for zh, en in KEYWORDS:
        if zh in text and en not in out:
            out.append(en)
        if len(out) >= limit:
            break
    return out


def sanitize(name):
    name = re.sub(r'[^a-z0-9_]+', '_', name.lower())
    name = re.sub(r'_+', '_', name).strip('_')
    if not name or not name[0].isalpha():
        name = 's_' + name
    return name[:55].rstrip('_')


def escape_xml(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", "\\'")


def module_of(path):
    p = path.replace('\\', '/')
    if '/android/tv/' in p:
        return 'tv'
    if '/android/app/' in p:
        return 'app'
    return ''


def strings_xml_of(module):
    return os.path.join(ROOT, 'android', module, 'src', 'main', 'res', 'values', 'strings.xml')


def load_existing(path):
    if not os.path.exists(path):
        return set(), {}
    with open(path, encoding='utf-8') as f:
        text = f.read()
    names, by_value = set(), {}
    for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', text, re.S):
        name, value = m.group(1), m.group(3)
        names.add(name)
        if '%' in value or '\\' in value or 'translatable="false"' in m.group(2):
            continue
        plain = (value.replace('&lt;', '<').replace('&gt;', '>')
                 .replace('&amp;', '&').replace("\\'", "'"))
        by_value.setdefault(plain, name)
    return names, by_value


def migrate(path, taken, by_value, resources, reused):
    with open(path, encoding='utf-8') as f:
        text = f.read()
    stem = re.sub(r'^(activity|page|item|header|nav)_', '',
                  os.path.basename(path).replace('.xml', '')) or 'lay'
    skipped, migrated = [], []

    def _sub(m):
        attr, value = m.group(1), m.group(2)
        if not CJK.search(value):
            return m.group(0)
        if value.startswith('@') or value.startswith('?'):
            skipped.append((attr, value, '已是资源引用'))
            return m.group(0)
        if '%' in value or '\\' in value:
            skipped.append((attr, value, '含 % 或转义'))
            return m.group(0)
        if value in by_value:
            name = by_value[value]
            taken.add(name)
            reused.append(name)
            migrated.append((attr, name, value))
            return 'android:%s="@string/%s"' % (attr, name)
        toks = tokens_of(value)
        key = attr.replace('contentDescription', 'cd')
        base = sanitize('lay_%s_%s_%s' % (stem, key, '_'.join(toks) if toks else 'x'))
        name, n = base, 2
        while name in taken:
            name, n = '%s_%d' % (base, n), n + 1
        taken.add(name)
        by_value[value] = name
        resources[name] = escape_xml(value)
        migrated.append((attr, name, value))
        return 'android:%s="@string/%s"' % (attr, name)

    out = ATTR.sub(_sub, text)
    return out, migrated, skipped


def write_resources(module, resources):
    path = strings_xml_of(module)
    with open(path, encoding='utf-8') as f:
        text = f.read()
    eol = '\r\n' if '\r\n' in text else '\n'
    block = ['    <!-- ↓↓↓ 从布局 XML 迁入（scripts/xml_i18n.py）↓↓↓ -->']
    for name, value in resources.items():
        block.append('    <string name="%s">%s</string>' % (name, value))
    block.append('    <!-- ↑↑↑ 迁入结束 ↑↑↑ -->')
    text = text.replace('</resources>', eol.join(block) + eol + '</resources>')
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text)


def main():
    apply = '--apply' in sys.argv
    files = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not files:
        print(__doc__)
        return 1
    modules = {}
    total = 0
    for rel in files:
        path = rel if os.path.isabs(rel) else os.path.join(ROOT, rel)
        module = module_of(path)
        if not module:
            print('!! 无法判断模块: %s' % rel)
            continue
        if module not in modules:
            names, by_value = load_existing(strings_xml_of(module))
            modules[module] = [names, by_value, {}, []]
        names, by_value, resources, reused = modules[module]
        out, migrated, skipped = migrate(path, names, by_value, resources, reused)
        print('=== %s ===' % rel)
        print('  可迁移 %d 条，跳过 %d 处' % (len(migrated), len(skipped)))
        for attr, name, value in migrated:
            print('    %-6s %-44s %s' % (attr, name, value))
        for attr, value, why in skipped:
            print('    [跳过:%s] %s  %s' % (why, attr, value[:70]))
        total += len(migrated)
        if apply:
            with open(path, 'w', encoding='utf-8', newline='') as f:
                f.write(out)
    for module, (_, _, resources, reused) in modules.items():
        if apply and resources:
            write_resources(module, resources)
            print('模块 %s：新增资源 %d 条（另复用已有 %d 处）'
                  % (module, len(resources), len(reused)))
        elif resources:
            print('模块 %s：将新增资源 %d 条' % (module, len(resources)))
    print('合计：可迁移 %d 条%s' % (total, '（已落盘）' if apply else '（预览，未落盘）'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
