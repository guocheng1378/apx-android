/**
 * 副屏视图：显示器插拔、编码参数、通道与实时指标。
 *
 * 这一屏的核心是**「副屏开关」到底做了什么**。
 * 若驱动后端支持运行时插拔（A/B），开关会真的让显示器出现/消失；
 * 若走降级后端（C），开关只停推流，显示器仍在 —— 界面必须把这点讲清楚，
 * 否则用户会以为「关了但屏幕还亮着」是 bug。
 */

import { h, card, metric, row, tag, segmented, switchEl, fmt } from '../ui.js';
import { act, withFeedback } from '../api.js';

const RES_PRESETS = [
  { id: '1080x2400@60', label: '1080×2400@60', title: '手机竖屏（默认）' },
  { id: '1200x1920@60', label: '1200×1920@60', title: '竖屏高清' },
  { id: '1920x1080@60', label: '1920×1080@60', title: '横屏' },
  { id: '1440x2560@60', label: '1440×2560@60', title: '竖屏 2K' },
];

const ORIENT = [
  { id: 'portrait',  label: '竖屏' },
  { id: 'landscape', label: '横屏' },
];

const BACKENDS = [
  { id: 'A', label: 'A', title: '第三方已签名驱动（支持运行时插拔，推荐）' },
  { id: 'B', label: 'B', title: '自研 IddCx 驱动（需签名）' },
  { id: 'C', label: 'C', title: '降级：仅停推流，显示器保留' },
];

function displayControlCard(s) {
  const d = s?.display ?? {};

  const plugBtn = h('button.btn.primary', { type: 'button' },
    [h('span', { text: d.enabled ? '拔除显示器' : '插入显示器' })]);
  plugBtn.addEventListener('click', () => withFeedback(plugBtn, () => act('display.plug')));

  const unres = d.backend === 'C';

  return card('显示器', {
    sub: '虚拟显示器的插入与拔除',
    actions: [tag(d.enabled ? '已插入' : '未插入', d.enabled ? 'ok' : '')],
    children: [
      h('div', { style: { marginBottom: '14px' } }, [
        h('div.metric-label', { text: '驱动后端' }),
        h('div', { style: { marginTop: '6px', display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' } }, [
          segmented(BACKENDS, d.backend, (id) => act('display.setBackend', { backend: id })),
          tag(d.backendLabel || '--', unres ? 'warn' : 'ok'),
        ]),
      ]),

      h('div', { style: { marginBottom: '14px' } }, [
        h('div.metric-label', { text: '分辨率预设' }),
        h('div', { style: { marginTop: '6px' } }, [
          segmented(RES_PRESETS, d.resPreset || `${d.width}x${d.height}@${d.refreshHz}`,
            (id) => act('display.setResolution', { preset: id })),
        ]),
      ]),

      h('div', { style: { marginBottom: '16px' } }, [
        h('div.metric-label', { text: '方向' }),
        h('div', { style: { marginTop: '6px' } }, [
          segmented(ORIENT, d.orientation, (id) => act('display.setOrientation', { orientation: id })),
        ]),
      ]),

      h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } }, [
        plugBtn,
        tag(d.enabled ? `${d.width}×${d.height} @ ${d.refreshHz}Hz` : '--', 'info'),
      ]),

      unres
        ? h('div.card-note', {
            text: '⚠ 降级后端：插入/拔除不会真正增删显示器，只切换推流；'
                + '显示器会一直留在系统里。要获得真正的插拔能力，需后端 A 或 B。',
          })
        : null,
    ],
  });
}

function encodeCard(s) {
  const d = s?.display ?? {};
  const auto = h('button', { type: 'button' });
  const autoSw = switchEl(!!d.adaptiveBitrate, {
    title: '依据丢帧 / 重同步 / RTT 动态调整码率与帧率',
    onChange: () => act('display.toggleAdaptive'),
  });

  return card('编码参数', {
    sub: `${d.codec || '--'} · ${d.encoder || '--'}`,
    children: [
      h('div.rows', {}, [
        row('编码器', '硬编优先（NVENC / QSV / AMF / MF），不可用时回退 raw_lz4', [
          h('select.select', {
            on: { change: (e) => act('display.setEncoder', { encoder: e.target.value }) },
          }, ['auto', 'nvenc', 'qsv', 'amf', 'mf', 'raw_lz4'].map((x) =>
            h('option', { value: x, selected: (d.encoderBackend || 'auto') === x, text: x }))),
        ]),
        row('目标码率', '副屏场景关 B 帧、GOP=1，以延迟优先', [
          h('span.mono', { text: `${fmt.int(d.bitrateKbps || 12000)} kbps` }),
        ]),
        row('最大帧率', '与手机端解码能力匹配', [
          h('span.mono', { text: `${fmt.int(d.maxFps || 60)} fps` }),
        ]),
        row('自适应码率', '链路抖动时自动降码率，避免排队延迟累积', [
          autoSw,
        ]),
      ]),
    ],
  });
}

function channelCard(s) {
  const link = s?.link ?? {};
  const wireless = link.mode === 'wireless';
  return card('通道', {
    sub: wireless ? '无线模式' : '有线模式',
    children: [
      h('div.rows', {}, [
        row('传输方式', wireless ? 'WiFi 局域网 TCP' : 'USB bulk 端点',
          [tag(wireless ? (link.transport || 'tcp') : 'usb', 'info')]),
        row('端点 / 地址', wireless
          ? `${link.host || '--'}:${link.port ?? '--'}`
          : 'FFS 不可用，副屏走 TCP（见诊断）',
          [h('span.mono', { text: wireless ? `${link.host || '--'}` : 'tcp://' })]),
        row('链路健康度', '近 60 秒延迟走势',
          [h('span.mono', { text: `${fmt.ms(link.rttMs)} ms` })]),
      ]),
      h('div.card-note', {
        text: 'FunctionFS 路线在本机不可用（内核 FFS 上下文被系统实例占满），'
            + '副屏的下行通道走 TCP；详见「设置与诊断」。',
      }),
    ],
  });
}

function metricsCard(s) {
  const d = s?.display ?? {};
  return card('实时指标', {
    children: [
      h('div.metrics', {}, [
        metric('端到端延迟', fmt.ms(d.e2eMs), { unit: 'ms' }),
        metric('解码帧率', fmt.num(d.decodeFps ?? d.fps), { unit: 'fps' }),
        metric('丢帧', fmt.int(d.droppedFrames), { cls: d.droppedFrames > 0 ? 'warn' : 'ok' }),
        metric('重同步', fmt.int(d.resyncCount ?? 0)),
      ]),
    ],
  });
}

export function render(s) {
  return h('div.view', {}, [
    h('div.view-head', {}, [
      h('div', {}, [
        h('h1.view-title', { text: '副屏' }),
        h('div.view-desc', { text: '虚拟显示器、编码参数与推流通道' }),
      ]),
    ]),
    h('div.grid', {}, [
      h('div.col-6', {}, [displayControlCard(s)]),
      h('div.col-6', {}, [encodeCard(s)]),
      h('div.col-6', {}, [channelCard(s)]),
      h('div.col-6', {}, [metricsCard(s)]),
    ]),
  ]);
}
