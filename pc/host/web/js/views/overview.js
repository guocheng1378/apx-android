/**
 * 总览视图：链路状态、副屏、场景预设、外设可用性矩阵。
 *
 * 设计原则：**这一屏要能回答「现在是什么状态、有没有东西坏了」**。
 * 因此降级项（传感器 Code 10、副屏后端 C）在这里直接暴露，不做美化。
 */

import { h, card, metric, matrix, sparkline, fmt, speedLabel, tag } from '../ui.js';
import { act, withFeedback } from '../api.js';

function linkCard(s) {
  const link = s?.link ?? {};
  const wireless = link.mode === 'wireless';

  const primary = wireless
    ? metric('往返延迟', fmt.ms(link.rttMs), { unit: 'ms', hero: true,
        cls: link.rttMs < 15 ? 'ok' : 'warn' })
    : metric('链路速度', link.speedLabel || speedLabel(link.speed), { hero: true,
        cls: (link.speed === 'super' || link.speed === 'super_plus') ? 'ok' : 'warn' });

  return card('链路状态', {
    sub: wireless ? '无线模式 · 无需 root' : '有线模式 · USB 复合设备',
    actions: [tag(link.connected ? '已连接' : '未连接', link.connected ? 'ok' : 'err')],
    children: [
      h('div.metrics', {}, [
        primary,
        metric('协议版本', link.protocolVersion || '--'),
        metric(wireless ? '传输' : '当前模式', wireless ? (link.transport || 'tcp') : 'USB'),
        metric('面板端口', link.port ?? '--'),
      ]),
      (link.rttHistory || s?.rttHistory)
        ? h('div', { style: { marginTop: '16px' } }, [
            h('div.metric-label', { text: '延迟走势（近 60 秒）' }),
            sparkline(s?.rttHistory || link.rttHistory),
          ])
        : null,
    ],
  });
}

function displayCard(s) {
  const d = s?.display ?? {};
  const btn = h('button.btn.primary', { type: 'button' }, [
    h('span.btn-icon', { html: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M12 3.5v17M6.5 9 12 3.5 17.5 9"/><path d="M5 20.5h14"/></svg>' }),
    h('span', { text: d.enabled ? '关闭副屏' : '开启副屏' }),
  ]);
  btn.addEventListener('click', () => withFeedback(btn, () => act('screen.toggle')));

  return card('副屏', {
    sub: d.enabled ? `${d.width}×${d.height} @ ${d.refreshHz}Hz` : '未开启',
    actions: [tag(d.enabled ? '运行中' : '已停止', d.enabled ? 'ok' : '')],
    children: [
      h('div.metrics', {}, [
        metric('码率', fmt.num(d.bitrateMbps), { unit: 'Mbps' }),
        metric('帧率', fmt.num(d.fps), { unit: 'fps' }),
        metric('端到端', fmt.ms(d.e2eMs), { unit: 'ms' }),
        metric('丢帧', fmt.int(d.droppedFrames), { cls: d.droppedFrames > 0 ? 'warn' : '' }),
      ]),
      h('div', { style: { marginTop: '16px', display: 'flex', alignItems: 'center', gap: '10px' } }, [
        btn,
        tag(`${d.codec || '--'} · ${d.encoder || '--'}`, 'info'),
      ]),
      // 后端 C 是「降级」路径，必须让用户知道关不掉显示器本身
      d.backend === 'C'
        ? h('div.card-note', {
            text: '当前驱动后端不支持运行时插拔：关闭副屏只会停止推流，显示器本身仍会留在系统中。',
          })
        : null,
    ],
  });
}

function scenesCard(s) {
  const list = s?.scenes ?? [];
  const btns = list.map((sc) => {
    const b = h('button.btn', { type: 'button' }, [
      h('span', { text: sc.name }),
    ]);
    b.title = sc.desc;
    b.addEventListener('click', () => withFeedback(b, () => act(`scene.${sc.id}`)));
    return b;
  });
  return card('快捷场景', {
    sub: '一组动作的原子化编排',
    children: [
      h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: '8px' } }, btns),
      h('div.card-note', {
        text: list.map((x) => `${x.name}：${x.desc}`).join('　·　'),
      }),
    ],
  });
}

export function render(s) {
  const cells = [];
  for (const sen of (s?.sensors ?? [])) {
    cells.push({ name: sen.name, ok: sen.ok, note: sen.note });
  }
  for (const [, v] of Object.entries(s?.peripherals ?? {})) {
    cells.push({ name: v.label, ok: v.ok, note: v.note });
  }
  cells.push({
    name: '触控板',
    ok: !!s?.touchpad?.available,
    note: s?.touchpad?.pathLabel || '',
  });
  cells.push({
    name: '摄像头',
    ok: !!s?.camera?.available,
    note: s?.camera?.note || '',
  });

  return h('div.view', {}, [
    h('div.view-head', {}, [
      h('div', {}, [
        h('h1.view-title', { text: '总览' }),
        h('div.view-desc', { text: '链路、副屏与外设的实时状态' }),
      ]),
    ]),

    h('div.grid', {}, [
      h('div.col-6', {}, [linkCard(s)]),
      h('div.col-6', {}, [displayCard(s)]),
      h('div.col-12', {}, [scenesCard(s)]),
      h('div.col-12', {}, [
        card('外设可用性', {
          sub: '如实反映每一项的真实状态，不可用的会直接标出',
          children: [matrix(cells)],
        }),
      ]),
    ]),
  ]);
}
