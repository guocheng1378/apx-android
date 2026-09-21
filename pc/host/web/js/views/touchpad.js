/**
 * 触控板视图：模式、手势、调参、实测。
 *
 * 关键区分：**触控板 ≠ 副屏触控**。
 * 副屏触控是「手机显示 PC 画面、点哪映射到哪」（绝对坐标）；
 * 触控板是「手机不显示画面、拖动即移动光标」（相对位移）。两者互斥。
 *
 * 路径说明：
 *   - 无线模式 → 蓝牙 HID（PC 零驱动，最省事）
 *   - 有线模式 → 复合设备里的 Mouse TLC（同样免驱，1ms 中断，最跟手）
 *   - 兜底     → bulk 上行 + PC 端注入（可做灵敏度曲线等精细调参）
 * 免驱路径无法做「指针加速度曲线」这类调参（那些由系统鼠标栈负责），
 * 因此对应控件需要置灰并说明原因，而不是假装可调。
 */

import { h, card, row, tag, switchEl, segmented, metric, fmt } from '../ui.js';
import { act, withFeedback } from '../api.js';

const PATHS = [
  { id: 'bt',   label: '蓝牙 HID', title: 'PC 零驱动；无线模式首选' },
  { id: 'hid',  label: '复合设备', title: 'USB 内 Mouse TLC，1ms 中断，最跟手' },
  { id: 'bulk', label: '注入',     title: '走 bulk 通道由 PC 端注入，可精细调参' },
];

function modeCard(s) {
  const tp = s?.touchpad ?? {};
  const link = s?.link ?? {};
  const conflicts = s?.display?.enabled;

  const sw = switchEl(!!tp.enabled, {
    onChange: () => act('touchpad.toggle'),
  });

  return card('触控板模式', {
    sub: '手机不显示画面，触摸即鼠标',
    actions: [tag(tp.enabled ? '已开启' : '已关闭', tp.enabled ? 'ok' : '')],
    children: [
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px' } }, [
        sw,
        h('span', { text: tp.enabled ? '运行中' : '已停止' }),
      ]),

      h('div', { style: { marginBottom: '14px' } }, [
        h('div.metric-label', { text: '上行路径' }),
        h('div', { style: { marginTop: '6px', display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' } }, [
          segmented(PATHS, tp.path, (id) => act('touchpad.setPath', { path: id })),
          tag(tp.pathLabel || '--', 'info'),
        ]),
      ]),

      conflicts
        ? h('div.banner', {}, [
            h('div.banner-icon', { html: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M12 3.5 22 20.5H2z"/><path d="M12 9.5v5M12 17.5h.01"/></svg>' }),
            h('div.banner-body', {}, [
              h('div.banner-title', { text: '副屏正在运行' }),
              h('div.banner-text', {
                text: '副屏触控与触控板互斥：前者用绝对坐标定位到虚拟屏，后者是相对位移驱动光标。'
                    + '同时启用会让光标在两种语义间跳变，建议先关闭副屏。',
              }),
              h('div.banner-actions', {}, [
                (() => {
                  const b = h('button.btn.sm', { type: 'button', text: '关闭副屏并启用触控板' });
                  b.addEventListener('click', () => withFeedback(b, () => act('scene.touchpad')));
                  return b;
                })(),
              ]),
            ]),
          ])
        : null,

      h('div.card-note', {
        text: link.mode === 'wireless'
          ? '无线模式下推荐蓝牙 HID：PC 端零驱动，且可与 USB 副屏同时使用。'
          : '有线模式下推荐复合设备的 Mouse TLC：1ms 中断轮询，比蓝牙（约 7.5–11ms）更跟手。',
      }),
    ],
  });
}

function gestureCard(s) {
  const g = s?.touchpad?.gestures ?? {};
  const defs = [
    ['singleMove', '单指移动', '拖动光标'],
    ['singleTap',  '单指点按', '轻点即左键'],
    ['twoFingerScroll', '双指滚动', '上下 / 左右滚动'],
    ['twoFingerTap',    '双指轻点', '右键'],
    ['threeFingerTap',  '三指轻点', '中键'],
    ['tapDrag',    '轻点拖动', '点两下后拖动'],
  ];
  return card('手势', {
    sub: '识别在手机端完成，只上报增量（保跟手）',
    children: [
      h('div.rows', {}, defs.map(([id, name, desc]) =>
        row(name, desc, [
          switchEl(g[id] !== false, { onChange: () => act('touchpad.setGesture', { id }) }),
        ]))),
    ],
  });
}

function tuningCard(s) {
  const tp = s?.touchpad ?? {};
  const injectable = tp.path === 'bulk';   // 只有注入路径能改这些

  const sens = h('input', {
    type: 'range', min: '0.2', max: '3', step: '0.1',
    value: String(tp.sensitivity ?? 1),
    disabled: !injectable,
    style: { width: '140px' },
    on: { change: (e) => act('touchpad.setSensitivity', { value: Number(e.target.value) }) },
  });

  return card('调参', {
    sub: injectable ? '当前路径支持精细调参' : '免驱路径下由系统鼠标栈接管',
    children: [
      h('div.rows', {}, [
        row('指针灵敏度', injectable ? '拖动手势的位移倍率' : '免驱路径不可调：增量由系统鼠标加速处理', [sens]),
        row('滚动步长', injectable ? '每次滚动的行数' : '免驱路径不可调', [
          h('select.select', {
            disabled: !injectable,
            on: { change: (e) => act('touchpad.setScrollStep', { step: Number(e.target.value) }) },
          }, [1, 2, 3, 6].map((n) =>
            h('option', { value: String(n), selected: (tp.scrollStep ?? 3) === n, text: `${n} 行` }))),
        ]),
        row('指针加速', '带加速度曲线更省力，但需要更高的手眼适应成本', [
          switchEl(!!tp.acceleration, {
            disabled: !injectable,
            onChange: () => act('touchpad.toggleAcceleration'),
          }),
        ]),
        row('自然滚动', '双指上滑时内容上移（触屏习惯）', [
          switchEl(tp.naturalScroll !== false, {
            onChange: () => act('touchpad.toggleNaturalScroll'),
          }),
        ]),
      ]),

      !injectable
        ? h('div.card-note', {
            text: '免驱路径（蓝牙 / 复合设备）下，指针位移由 PC 的操作系统鼠标栈统一处理，'
                + '面板无法介入。需要上面这些调参时，请把上行路径切到「注入」。',
          })
        : null,
    ],
  });
}

function metricsCard(s) {
  const tp = s?.touchpad ?? {};
  return card('实测', {
    children: [
      h('div.metrics', {}, [
        metric('端到端延迟', fmt.ms(tp.latencyMs), {
          unit: 'ms', hero: true,
          cls: tp.latencyMs == null ? '' : (tp.latencyMs < 10 ? 'ok' : tp.latencyMs < 20 ? 'warn' : 'err'),
        }),
        metric('丢点', fmt.int(tp.drops), { cls: tp.drops > 0 ? 'warn' : '' }),
        metric('当前路径', tp.pathLabel || '--'),
      ]),
      h('div.card-note', {
        text: '参考：复合设备路径（USB 1ms 中断）通常 <5ms；'
            + '蓝牙受连接间隔限制（BLE 约 7.5ms、经典蓝牙约 11ms），跟手程度有差距。',
      }),
    ],
  });
}

export function render(s) {
  return h('div.view', {}, [
    h('div.view-head', {}, [
      h('div', {}, [
        h('h1.view-title', { text: '触控板' }),
        h('div.view-desc', { text: '把手机当作无线触摸板使用' }),
      ]),
    ]),
    h('div.grid', {}, [
      h('div.col-6', {}, [modeCard(s)]),
      h('div.col-6', {}, [gestureCard(s)]),
      h('div.col-6', {}, [tuningCard(s)]),
      h('div.col-6', {}, [metricsCard(s)]),
    ]),
  ]);
}
