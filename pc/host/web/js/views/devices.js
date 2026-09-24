/**
 * 外设视图：传感器、音频、设备。
 *
 * 传感器部分刻意保留**「不可用」的行**而不是隐藏掉：
 * 当前机型 IMU 三个 TLC 在 Windows 侧是 Code 10，把它藏起来会让人以为功能正常。
 * 面板的既定原则是「如实呈现降级，不伪装成功」。
 */

import { h, card, row, tag, switchEl, segmented, matrix, steps, fmt, empty } from '../ui.js';
import { act, withFeedback } from '../api.js';

const RATE_PRESETS = [
  { id: '50',  label: '50Hz' },
  { id: '100', label: '100Hz' },
  { id: '200', label: '200Hz', title: 'Android 12+ 免权上限' },
];

function sensorsCard(s) {
  const list = s?.sensors ?? [];
  const rows = list.map((sen) => {
    const sw = switchEl(!!sen.on, {
      disabled: !sen.ok,
      title: sen.ok ? '启用/停用该传感器上报' : (sen.note || '不可用'),
      onChange: () => act('sensor.toggle', { id: sen.id }),
    });
    return row(
      sen.name,
      sen.ok ? (sen.reading || '') : (sen.note || '不可用'),
      [
        !sen.ok ? tag('不可用', 'warn') : null,
        h('span.mono', { text: sen.rateHz ? `${sen.rateHz}Hz` : '' }),
        sw,
      ].filter(Boolean),
    );
  });

  return card('传感器', {
    sub: `${list.filter((x) => x.ok).length} / ${list.length} 可用`,
    actions: [
      h('button.btn.sm', {
        type: 'button', text: '全开',
        on: { click: (e) => withFeedback(e.target, () => act('sensor.allOn')) },
      }),
      h('button.btn.sm', {
        type: 'button', text: '全关',
        on: { click: (e) => withFeedback(e.target, () => act('sensor.allOff')) },
      }),
    ],
    children: [
      h('div', { style: { marginBottom: '12px' } }, [
        h('div.metric-label', { text: '采样率档位（全部传感器）' }),
        h('div', { style: { marginTop: '6px' } }, [
          segmented(RATE_PRESETS, String(s?.sensorRateHz ?? 100),
            (id) => act('sensor.setRate', { rateHz: Number(id) })),
        ]),
      ]),
      h('div.rows', {}, rows),
      list.some((x) => !x.ok)
        ? h('div.card-note', {
            text: '不可用的项并非被面板禁用，而是该 TLC 在系统侧驱动未启动'
                + '（Windows 设备状态显示为「无法启动」）。描述符已按官方要求补齐所需的属性报告，'
                + '但问题仍未解决 —— 详见「设置与诊断」。',
          })
        : null,
    ],
  });
}



function audioCard(s) {
  const a = s?.audio ?? {};
  return card('音频', {
    sub: 'UAC2 声卡（PC 侧表现为扬声器 + 麦克风）',
    actions: [tag(a.enabled ? '已启用' : '已停用', a.enabled ? 'ok' : '')],
    children: [
      h('div', { style: { marginBottom: '14px' } }, [
        h('div.metric-label', { text: '路由' }),
        h('div', { style: { marginTop: '6px' } }, [
          segmented([
            { id: 'speaker', label: '扬声器' },
            { id: 'mic',     label: '麦克风' },
            { id: 'both',    label: '双向' },
          ], a.route, (id) => act('audio.setRoute', { route: id })),
        ]),
      ]),
      h('div.rows', {}, [
        row('采样率', '与手机端 AudioRecord / AudioTrack 保持一致，否则「认到声卡但没声音」',
          [h('span.mono', { text: `${a.sampleRate || '--'} Hz` })]),
        row('声道', '', [h('span.mono', { text: String(a.channels ?? '--') })]),
      ]),
    ],
  });
}

function deviceCard(s) {
  const link = s?.link ?? {};
  const devs = s?.devices ?? [];
  return card('设备', {
    sub: `${devs.length || (link.connected ? 1 : 0)} 个已发现`,
    children: [
      devs.length
        ? h('div.rows', {}, devs.map((d) => row(
            d.name || d.serial || '未命名设备',
            `${d.path || ''} ${d.speed ? '· ' + d.speed : ''}`.trim(),
            [tag(d.connected ? '已连接' : '可用', d.connected ? 'ok' : 'info')],
          )))
        : h('div.rows', {}, [
            row(link.deviceName || '未发现设备',
                link.serial ? `序列号 ${link.serial}` : '请确认手机已挂载或已进入无线模式',
                [tag(link.connected ? '已连接' : '未连接', link.connected ? 'ok' : 'err')]),
          ]),
      h('div', { style: { marginTop: '14px', display: 'flex', gap: '8px', flexWrap: 'wrap' } }, [
        (() => {
          const b = h('button.btn.primary', { type: 'button', text: link.connected ? '断开' : '连接' });
          b.addEventListener('click', () => withFeedback(b, () =>
            act(link.connected ? 'device.disconnect' : 'device.connect')));
          return b;
        })(),
        (() => {
          const b = h('button.btn', { type: 'button', text: '重新枚举' });
          b.addEventListener('click', () => withFeedback(b, () => act('device.rescan')));
          return b;
        })(),
      ]),
    ],
  });
}

export function render(s) {
  const periph = Object.entries(s?.peripherals ?? {}).map(([k, v]) => ({
    name: v.label, ok: v.ok, note: v.note,
  }));

  return h('div.view', {}, [
    h('div.view-head', {}, [
      h('div', {}, [
        h('h1.view-title', { text: '外设' }),
        h('div.view-desc', { text: '传感器、音频与设备连接' }),
      ]),
    ]),
    h('div.grid', {}, [
      h('div.col-8', {}, [sensorsCard(s)]),
      h('div.col-4', {}, [
        card('其它外设', { children: [matrix(periph.length ? periph : [{ name: '暂无', ok: false, note: '' }])] }),
      ]),

      h('div.col-6', {}, [audioCard(s)]),
      h('div.col-12', {}, [deviceCard(s)]),
    ]),
  ]);
}
