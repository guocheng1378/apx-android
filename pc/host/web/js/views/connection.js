import { act, withFeedback } from '../api.js';
import { card, row, steps, metric, tag, h, empty, fmt } from '../ui.js';

// 连接与配对视图
// v1：后端 wireless.connect 已是**真连接**（TCP → 令牌握手 → 保活心跳），
// 本视图同步升级：三态状态（已连接/已配对未连接/未连接）、实测 RTT 展示、
// 断开按钮、host:port 手动输入解析、蓝牙卡读后端 link.bluetooth 引导字段。
export function render(s) {
  return h('div.view', {}, [
    h('div.view-head', {}, [
      h('div', {}, [
        h('h1.view-title', { text: '连接与配对' }),
        h('div.view-desc', { text: '有线 / 无线模式切换、发现与配对' }),
      ]),
    ]),
    h('div.grid', {}, [
      h('div.col-12', {}, [pairCard(s)]),
      h('div.col-12', {}, [btCard(s)]),
    ]),
  ]);
}

// 解析 "192.168.1.42:9500" → { host, port }；无端口用默认 9500
function parseHostPort(text) {
  const t = (text || '').trim();
  if (!t) return { host: '', port: 9500 };
  const i = t.lastIndexOf(':');
  if (i > 0 && /^\d+$/.test(t.slice(i + 1))) {
    return { host: t.slice(0, i), port: Number(t.slice(i + 1)) || 9500 };
  }
  return { host: t, port: 9500 };
}

function pairCard(s) {
  const wifi = s?.wireless ?? {};
  const connected = !!wifi.connected;
  const paired = !!wifi.paired;
  const qr = wifi.qrPayload || '';

  // 三态标签：已连接（数据通道 live）> 已配对未连接 > 未连接
  const stateTag = connected
    ? tag('已连接', 'ok')
    : paired ? tag('已配对 · 未连接', 'warn') : tag('未连接', '');
  const rttText = connected
    ? (typeof wifi.rttMs === 'number' && wifi.rttMs >= 0 ? ` · RTT ${fmt.ms(wifi.rttMs)}` : ' · 测量 RTT 中…')
    : '';

  const copyBtn = h('button.btn.sm', { type: 'button', text: '复制连接信息' });
  copyBtn.addEventListener('click', () => {
    if (!qr) return;
    navigator.clipboard?.writeText(qr).then(
      () => { copyBtn.textContent = '已复制 ✓'; setTimeout(() => (copyBtn.textContent = '复制连接信息'), 1500); },
      () => {},
    );
  });

  const regenBtn = h('button.btn.sm', { type: 'button', text: '重新生成配对码' });
  regenBtn.addEventListener('click', () => withFeedback(regenBtn, () => act('wireless.regenToken')));

  const host = h('input.input', { type: 'text', placeholder: '手机地址，如 192.168.1.42:9500', value: wifi.host || '', style: { flex: '1', minWidth: '160px' } });
  const token = h('input.input', { type: 'text', placeholder: '手机配对令牌（来自手机端）', value: '', style: { flex: '1', minWidth: '160px' } });
  const connectBtn = h('button.btn.primary', { type: 'button', text: '手动连接' });
  connectBtn.addEventListener('click', () => {
    const hp = parseHostPort(host.value);
    withFeedback(connectBtn, () => act('wireless.connect', { host: hp.host, port: hp.port, token: token.value.trim() }));
  });
  const scanBtn = h('button.btn', { type: 'button', text: '刷新发现' });
  scanBtn.addEventListener('click', () => withFeedback(scanBtn, () => act('wireless.scan')));

  // 断开按钮（仅已连接时展示）
  const disconnectBtn = connected
    ? (() => {
        const b = h('button.btn.danger', { type: 'button', text: '断开' });
        b.addEventListener('click', () => withFeedback(b, () => act('wireless.disconnect')));
        return b;
      })()
    : null;

  const discovered = wifi.discovered || [];

  const errNote = wifi.error
    ? h('div.banner.warn', { style: { marginTop: '10px' }, text: `上次连接失败：${wifi.error}` })
    : null;

  return card('无线配对', {
    sub: '同局域网内手机自动出现；点「配对」即建立数据通道（TCP + 令牌鉴权）',
    actions: [stateTag],
    children: [
      // 已连接状态条：peer + 实测 RTT + 断开
      connected
        ? h('div', { style: {
            display: 'flex', gap: '12px', alignItems: 'center', flexWrap: 'wrap',
            padding: '10px 14px', marginBottom: '14px', borderRadius: '10px',
            background: 'rgba(34,211,238,.08)', border: '1px solid rgba(34,211,238,.35)' } }, [
            h('div', { style: { flex: '1', minWidth: '180px' } }, [
              h('div.metric-label', { text: '数据通道' }),
              h('div.mono', { style: { fontSize: '13px' }, text: `${wifi.peer || '—'}${rttText}` }),
            ]),
            disconnectBtn,
          ].filter(Boolean))
        : h('div', {}, []),

      h('div', { style: { display: 'flex', gap: '12px', alignItems: 'flex-start', flexWrap: 'wrap', marginBottom: '14px' } }, [
        // 本机配对码（二维码载荷）：手机扫码即拿到 PC 地址 + 令牌
        h('div', { style: {
          width: '180px', flex: 'none', borderRadius: '12px', border: '1px solid var(--glass-line)',
          background: 'rgba(255,255,255,.04)', padding: '12px', display: 'flex', flexDirection: 'column', gap: '8px' } },
        [
          h('div.metric-label', { text: '本机配对码（手机扫码）' }),
          h('div.mono', { style: { fontSize: '11px', wordBreak: 'break-all', color: 'var(--tx-2)', lineHeight: '1.5' }, text: qr || '生成中…' }),
          copyBtn,
        ]),
        h('div', { style: { flex: '1', minWidth: '220px', display: 'flex', flexDirection: 'column', gap: '8px' } }, [
          h('div.metric-label', { text: 'PC 配对令牌（供手机回带）' }),
          h('div.mono', { style: { padding: '8px 10px', borderRadius: '8px', background: 'rgba(34,211,238,.08)', border: '1px solid var(--glass-line)', fontSize: '14px', letterSpacing: '.5px' }, text: wifi.token || '—' }),
          regenBtn,
        ]),
      ]),

      h('div.metric-label', { style: { marginTop: '4px', marginBottom: '6px' }, text: '发现的设备' }),
      discovered.length
        ? h('div.rows', {}, discovered.map((d) => row(
            d.name || d.host,
            `${d.host}:${d.port}` + (d.token ? ` · 令牌 ${String(d.token).slice(0, 6)}…` : ''),
            [(() => {
              const b = h('button.btn.sm.primary', { type: 'button', text: '连接' });
              b.addEventListener('click', () => withFeedback(b, () => act('wireless.connect', { host: d.host, port: d.port, token: d.token || '' })));
              return b;
            })()],
          )))
        : h('div', { style: { marginTop: '6px' } }, [
            empty('尚未发现设备', '确认手机与本机在同一局域网，且手机端已开启无线服务（自动双向广播）；也可直接填写地址手动连接。', 'wifi'),
          ]),

      h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginTop: '14px' } }, [host, token]),
      h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginTop: '8px' } }, [connectBtn, scanBtn].filter(Boolean)),

      errNote,
      h('div.card-note', {
        text: '安全：无线通道仅用于局域网；配对采用双向令牌鉴权（PC 广播信标 + 手机反向信标，双方令牌一致才接受连接）。连接建立后每秒发送心跳帧保活并实测 RTT。',
      }),
    ],
  });
}

function btCard(s) {
  const link = s?.link ?? {};
  const bt = link.bluetooth ?? {};
  const ok = !!bt.supported;

  return card('蓝牙 HID', {
    sub: '手机作为免驱蓝牙 HID 设备（触控板 / 键盘 / 多媒体键）',
    actions: [tag(ok ? (bt.connected ? '已连接' : '就绪 · 需系统配对') : '不可用', ok ? (bt.connected ? 'ok' : 'warn') : 'warn')],
    children: [
      steps([
        ['开启手机蓝牙与外设服务', true],
        ['在 Windows 蓝牙设置中添加「AllPeriph」设备', ok],
        ['系统配对完成（PC 主机角色免驱动）', !!bt.connected],
      ]),
      metric('可达范围', ok ? (bt.rangeM ? `${bt.rangeM} m` : '约 10 m') : '—'),
      h('div.card-note', { text: bt.guide || bt.note || '蓝牙 HID 承载全部输入类外设；连接状态由 Windows 蓝牙栈管理，面板如实标注为「系统级配对」，不伪装连接态。' }),
    ],
  });
}
