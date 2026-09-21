/**
 * 快捷键视图：热键列表 + 「按下即录制」改键 + 冲突检测 + 配置导入导出。
 *
 * 录制交互的几个必要细节（缺一个都会让改键很难用）：
 *   - 录制中**必须阻止事件冒泡**，否则 Ctrl+1..7 会被面板自身的视图切换快捷键吃掉
 *   - 只按修饰键不能算一次有效录入（否则会录出「Ctrl+」这种半截组合）
 *   - Esc 取消、Backspace 清除
 *   - **冲突必须即时提示**：与已绑定的其它动作撞车时立刻标红并说明撞了谁
 *   - 录制期间禁止视图重绘（app.js 里已按 .recording 类判断）
 */

import { h, card, row, tag, switchEl, fmt } from '../ui.js';
import { act, withFeedback } from '../api.js';

/* 修饰键与主键的显示名 */
const MOD_LABEL = { ctrl: 'Ctrl', alt: 'Alt', shift: 'Shift', meta: 'Win' };

/** 把 KeyboardEvent 归一化成 {mods, key}；返回 null 表示这次按键不构成有效组合 */
function readCombo(e) {
  const mods = [];
  if (e.ctrlKey)  mods.push('ctrl');
  if (e.altKey)   mods.push('alt');
  if (e.shiftKey) mods.push('shift');
  if (e.metaKey)  mods.push('meta');

  const k = e.key;
  const isModifierOnly = ['Control', 'Alt', 'Shift', 'Meta', 'OS'].includes(k);
  if (isModifierOnly) return { mods, key: null };

  let key = k;
  if (k === ' ') key = 'Space';
  else if (k === 'Escape') key = 'Esc';
  else if (k.length === 1) key = k.toUpperCase();
  // F1..F24、ArrowUp 等保持原样

  return { mods, key };
}

export const comboText = (b) =>
  [...(b?.mods || []).map((m) => MOD_LABEL[m] || m), b?.key].filter(Boolean).join(' + ');

/** 组合键归一化后的比较键，用于冲突检测 */
const comboKeyOf = (b) =>
  [...(b?.mods || [])].sort().join('+') + '|' + (b?.key || '').toUpperCase();

function hotkeyRow(item, all, onRebind) {
  const conflicts = all.filter((x) => x.id !== item.id && comboKeyOf(x) === comboKeyOf(item));
  const conflict = conflicts.length > 0;

  const keyEl = h('div.hotkey-key' + (conflict ? '.conflict' : ''), {
    text: comboText(item) || '未设置',
    title: conflict
      ? `与「${conflicts.map((c) => c.label).join('、')}」冲突`
      : '点击后按下新组合键',
    tabindex: '0',
  });

  // ---- 录制 ----
  const startRecording = () => {
    if (keyEl.classList.contains('recording')) return;
    const prev = keyEl.textContent;
    keyEl.classList.add('recording');
    keyEl.textContent = '请按下组合键…';

    const finish = (combo, ok) => {
      window.removeEventListener('keydown', onKey, true);
      keyEl.classList.remove('recording');
      if (ok && combo) {
        onRebind(item, combo);
        keyEl.textContent = comboText(combo);
      } else {
        keyEl.textContent = prev;
      }
    };

    const onKey = (e) => {
      // 阻止冒泡：否则 Ctrl+1..7 会触发面板自身的视图切换
      e.preventDefault();
      e.stopPropagation();

      if (e.key === 'Escape') { finish(null, false); return; }
      if (e.key === 'Backspace' || e.key === 'Delete') { finish({ mods: [], key: '' }, true); return; }

      const combo = readCombo(e);
      if (!combo.key) return;          // 只按了修饰键，继续等
      finish(combo, true);
    };

    window.addEventListener('keydown', onKey, true);
  };

  keyEl.addEventListener('click', startRecording);
  keyEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); startRecording(); }
  });

  return row(item.label, conflict
    ? `⚠ 与「${conflicts.map((c) => c.label).join('、')}」冲突`
    : (item.desc || ''),
    [
      conflict ? tag('冲突', 'err') : null,
      keyEl,
      switchEl(item.enabled !== false, {
        onChange: () => act('hotkey.toggle', { id: item.id }),
      }),
    ].filter(Boolean));
}

export function render(s) {
  const list = s?.hotkeys ?? [];

  // 改键：立即反馈到本地副本，随后落盘；冲突由重新渲染体现
  const rebind = (item, combo) => {
    item.mods = combo.mods;
    item.key = combo.key;
    act('hotkey.rebind', { id: item.id, mods: combo.mods, key: combo.key })
      .catch((e) => console.warn('[hotkey] 保存失败', e?.message));
    // 重新渲染本视图以刷新冲突标记
    const host = document.getElementById('viewHost');
    if (host) { host.firstChild?.replaceWith(render(s)); }
  };

  const conflicts = list.filter((a) =>
    list.some((b) => b.id !== a.id && comboKeyOf(a) === comboKeyOf(b)));

  return h('div.view', {}, [
    h('div.view-head', {}, [
      h('div', {}, [
        h('h1.view-title', { text: '快捷键' }),
        h('div.view-desc', { text: '系统级全局热键，无需管理员权限' }),
      ]),
    ]),

    h('div.grid', {}, [
      h('div.col-8', {}, [
        card('热键列表', {
          sub: '点击键位即可录制新组合键',
          actions: conflicts.length ? [tag(`${conflicts.length} 项冲突`, 'err')] : [tag('无冲突', 'ok')],
          children: [h('div.rows', {}, list.map((it) => hotkeyRow(it, list, rebind)))],
        }),
      ]),

      h('div.col-4', {}, [
        card('操作', {
          children: [
            h('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px' } }, [
              (() => {
                const b = h('button.btn', { type: 'button', text: '恢复默认' });
                b.addEventListener('click', () => withFeedback(b, () => act('hotkey.resetDefaults')));
                return b;
              })(),
              (() => {
                const b = h('button.btn', { type: 'button', text: '导出配置' });
                b.addEventListener('click', () => { window.location.href = '/api/q/hotkeys.export'; });
                return b;
              })(),
              (() => {
                const input = h('input', { type: 'file', accept: '.json', style: { display: 'none' } });
                input.addEventListener('change', () => {
                  const f = input.files?.[0];
                  if (!f) return;
                  f.text().then((text) => act('hotkey.import', { json: text }))
                    .catch((e) => console.warn(e));
                });
                const b = h('button.btn', { type: 'button', text: '导入配置' });
                b.addEventListener('click', () => input.click());
                return h('div', {}, [b, input]);
              })(),
            ]),
          ],
        }),

        card('场景绑定', {
          cls: '',
          sub: '',
          children: [
            h('div.rows', {}, (s?.scenes ?? []).map((sc) => row(
              sc.name, sc.desc,
              [
                h('div.hotkey-key', { text: comboText(sc) || '未绑定', title: '点击后按下新组合键' }),
              ],
            ))),
            h('div.card-note', { text: '场景可以把一组动作绑到一个键上，例如一键进入「演示模式」。' }),
          ],
        }),

        card('说明', {
          children: [
            h('div.card-note', {
              text: '热键由系统级注册（RegisterHotKey），在任意程序前台时都生效，且无需管理员权限。'
                  + '若某个组合无效，通常是已被其它软件占用（常见于输入法、截图工具、显卡驱动面板），'
                  + '换一个组合即可。',
            }),
          ],
        }),
      ]),
    ]),
  ]);
}
