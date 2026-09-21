/**
 * 通用 UI 组件。
 *
 * 全部用 DOM API 构造而非 innerHTML 拼接：状态里含设备名、错误信息等外部字符串，
 * 拼接 HTML 会有注入风险，且难以安全地挂事件。`h()` 让组合式写法依然简洁。
 */

/* ------------------------------------------------------------ DOM 构造 --- */

/**
 * 创建元素。
 * @param {string} tag  形如 'div.card.span-6' 或 'button.btn.primary'
 * @param {object} [attrs] 属性；`text` 设文本、`html` 设富文本、`on` 挂事件、其余作 attribute
 * @param {Array} [children]
 */
export function h(tag, attrs = {}, children = []) {
  const [name, ...classes] = String(tag).split('.');
  const el = document.createElement(name || 'div');
  if (classes.length) el.className = classes.join(' ');

  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'text') { el.textContent = String(v); continue; }
    if (k === 'html') { el.innerHTML = v; continue; }          // 仅用于内部可信的图标字符串
    if (k === 'class') { el.className = [el.className, v].filter(Boolean).join(' '); continue; }
    if (k === 'style' && typeof v === 'object') { Object.assign(el.style, v); continue; }
    if (k === 'on' && typeof v === 'object') {
      for (const [evt, fn] of Object.entries(v)) el.addEventListener(evt, fn);
      continue;
    }
    if (k === 'dataset' && typeof v === 'object') { Object.assign(el.dataset, v); continue; }
    el.setAttribute(k, v === true ? '' : String(v));
  }

  for (const c of [].concat(children)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

export const clear = (el) => { while (el.firstChild) el.removeChild(el.firstChild); return el; };

/** 内联 SVG 图标（全部本地路径，不依赖任何图标库/CDN） */
export const icon = (name, cls = '') => {
  const paths = {
    gauge:   '<path d="M12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"/><path d="M13.4 10.6 19 5"/><path d="M3.3 18a9 9 0 1 1 17.4 0"/>',
    screen:  '<rect x="2.5" y="3.5" width="19" height="13" rx="2"/><path d="M8 20.5h8M12 16.5v4"/>',
    chip:    '<rect x="7" y="7" width="10" height="10" rx="2"/><path d="M10 2.5v3M14 2.5v3M10 18.5v3M14 18.5v3M2.5 10h3M2.5 14h3M18.5 10h3M18.5 14h3"/>',
    pointer: '<path d="M5.5 3.5 19 10.8l-5.6 1.8-1.8 5.6z"/>',
    plug:    '<path d="M9 2.5v6M15 2.5v6"/><path d="M6 8.5h12v3a6 6 0 0 1-6 6 6 6 0 0 1-6-6z"/><path d="M12 17.5v4"/>',
    keyboard:'<rect x="2.5" y="6.5" width="19" height="11" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M6 13.5h12"/>',
    cog:     '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2v.3a2 2 0 1 1-4 0v-.2A1.7 1.7 0 0 0 7 19.4a1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0-1.2-2.9H.8a2 2 0 1 1 0-4h.2A1.7 1.7 0 0 0 2.6 7a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.7 1.7 0 0 0 7 2.6h.1A1.7 1.7 0 0 0 8.3.8V.6a2 2 0 1 1 4 0v.2a1.7 1.7 0 0 0 2.9 1.2"/>',
    warn:    '<path d="M12 3.5 22 20.5H2z"/><path d="M12 9.5v5M12 17.5h.01"/>',
    info:    '<circle cx="12" cy="12" r="9.5"/><path d="M12 11v5.5M12 7.5h.01"/>',
    check:   '<path d="m4.5 12.5 5 5 10-11"/>',
    x:       '<path d="M6 6l12 12M18 6L6 18"/>',
    refresh: '<path d="M20.5 12a8.5 8.5 0 1 1-2.6-6.1"/><path d="M20.5 3.5V10h-6.5"/>',
    qr:      '<rect x="3.5" y="3.5" width="6.5" height="6.5" rx="1"/><rect x="14" y="3.5" width="6.5" height="6.5" rx="1"/><rect x="3.5" y="14" width="6.5" height="6.5" rx="1"/><path d="M14 14h3v3h-3zM20.5 14v3M14 20.5h3M20.5 20.5h.01"/>',
    bluetooth:'<path d="m7 7 10 10-5 4V3l5 4L7 17"/>',
    wifi:    '<path d="M2.5 8.5a15 15 0 0 1 19 0M5.5 12a10.5 10.5 0 0 1 13 0M8.5 15.5a6 6 0 0 1 7 0"/><path d="M12 19h.01"/>',
    layers:  '<path d="m12 2.5 9.5 5-9.5 5-9.5-5z"/><path d="m2.5 12.5 9.5 5 9.5-5"/><path d="m2.5 17 9.5 5 9.5-5"/>',
    activity:'<path d="M2.5 12h4l3-8 5 16 3-8h4"/>',
    eye:     '<path d="M1.5 12S5 5.5 12 5.5 22.5 12 22.5 12 19 18.5 12 18.5 1.5 12 1.5 12Z"/><circle cx="12" cy="12" r="3"/>',
  };
  return `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${paths[name] || ''}</svg>`;
};

/* ---------------------------------------------------------------- 格式化 --- */

export const fmt = {
  num: (v, d = 1) => (v === null || v === undefined || Number.isNaN(v)) ? '--' : Number(v).toFixed(d),
  int: (v) => (v === null || v === undefined) ? '--' : String(Math.round(v)),
  mbps: (v) => `${fmt.num(v, 1)}`,
  ms: (v) => (v === null || v === undefined) ? '--' : (v < 10 ? fmt.num(v, 2) : fmt.num(v, 1)),
};

/** 'super_plus' → 'SuperSpeed+ 10Gbps'，未知值返回原样，绝不谎报 */
export function speedLabel(speed) {
  switch (speed) {
    case 'super_plus': return 'SuperSpeed+ 10Gbps';
    case 'super':      return 'SuperSpeed 5Gbps';
    case 'high':       return 'High-Speed 480Mbps';
    case 'full':       return 'Full-Speed 12Mbps';
    default:           return '未知';
  }
}

/* ---------------------------------------------------------------- 组件 --- */

export function card(title, { sub = '', actions = [], children = [], cls = '' } = {}) {
  const head = h('div.card-head', {}, [
    h('div', {}, [
      h('div.card-title', { text: title }),
      sub ? h('div.card-sub', { text: sub }) : null,
    ]),
    actions.length ? h('div.row-ctrl', {}, actions) : null,
  ]);
  return h(`div.card${cls ? '.' + cls : ''}`, {}, [head, ...[].concat(children)]);
}

export function metric(label, value, { unit = '', cls = '', hero = false, title = '' } = {}) {
  return h(`div.metric${hero ? '.hero' : ''}`, { title }, [
    h('div.metric-label', { text: label }),
    h('div.metric-value' + (cls ? '.' + cls : ''), {}, [
      String(value),
      unit ? h('span.metric-unit', { text: unit }) : null,
    ]),
  ]);
}

export const tag = (text, kind = '') => h(`span.tag${kind ? '.' + kind : ''}`, { text });

export function row(name, desc, ctrl = []) {
  return h('div.row', {}, [
    h('div.row-main', {}, [
      h('div.row-name', { text: name }),
      desc ? h('div.row-desc', { text: desc }) : null,
    ]),
    h('div.row-ctrl', {}, [].concat(ctrl)),
  ]);
}

export function switchEl(on, { disabled = false, onChange, title = '' } = {}) {
  const el = h(`button.switch${on ? '.on' : ''}`, {
    type: 'button',
    role: 'switch',
    'aria-checked': String(!!on),
    disabled: disabled || false,
    title,
    on: { click: () => onChange && onChange(!on) },
  });
  return el;
}

export function segmented(items, activeId, onPick) {
  return h('div.segmented', {}, items.map((it) =>
    h(`button${it.id === activeId ? '.active' : ''}`, {
      type: 'button',
      text: it.label,
      title: it.title || '',
      disabled: it.disabled || false,
      on: { click: () => !it.disabled && onPick && onPick(it.id) },
    })));
}

export function banner({ level = 'warn', title, text, action, onAction }) {
  const cls = level === 'info' ? '.info' : level === 'err' ? '.err' : '';
  return h(`div.banner${cls}`, {}, [
    h('div.banner-icon', { html: icon(level === 'info' ? 'info' : 'warn') }),
    h('div.banner-body', {}, [
      h('div.banner-title', { text: title }),
      text ? h('div.banner-text', { text }) : null,
      action ? h('div.banner-actions', {}, [
        h('button.btn.sm', { type: 'button', text: action, on: { click: () => onAction && onAction() } }),
      ]) : null,
    ]),
  ]);
}

export function matrix(cells) {
  return h('div.matrix', {}, cells.map((c) =>
    h('div.matrix-cell', { title: c.title || c.note || '' }, [
      h('div.mc-name', { text: c.name }),
      h('div.mc-state', {}, [
        h(`span.dot.${c.ok ? 'on' : 'warn'}`),
        h('span', { text: c.ok ? '可用' : (c.note || '不可用') }),
      ]),
    ])));
}

export function empty(title, text, iconName = 'info') {
  return h('div.empty', {}, [
    h('div.empty-icon', { html: icon(iconName) }),
    h('div.empty-title', { text: title }),
    text ? h('div.empty-text', { text }) : null,
  ]);
}

export function steps(items) {
  return h('div.steps', {}, items.map((it) =>
    h('div.step', {}, [
      h('div.step-num'),
      h('div.step-body', { html: it }),
    ])));
}

/**
 * 迷你折线图（纯 SVG，无依赖）。
 * 用于 RTT 等时序指标 —— 数值跳动时只看瞬时值看不出趋势。
 */
let sparkSeq = 0;

export function sparkline(values, { width = 260, height = 46, minGap = 0 } = {}) {
  const vals = (values || []).filter((v) => typeof v === 'number' && !Number.isNaN(v));
  if (vals.length < 2) {
    return h('svg.spark', { viewBox: `0 0 ${width} ${height}` });
  }
  const lo = Math.min(...vals);
  const hi = Math.max(...vals);
  const span = Math.max(hi - lo, minGap || 1e-6);
  const stepX = width / (vals.length - 1);
  const pts = vals.map((v, i) => [i * stepX, height - ((v - lo) / span) * (height - 6) - 3]);

  const line = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `${line} L${width},${height} L0,${height} Z`;

  // 每次实例使用唯一渐变 id：同一视图可挂多个 sparkline，避免 id 冲突导致渐变串色
  const uid = `spark${++sparkSeq}`;

  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'spark');
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('preserveAspectRatio', 'none');
  svg.innerHTML = `
    <defs>
      <linearGradient id="${uid}g" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#22D3EE"/><stop offset="100%" stop-color="#8B5CF6"/>
      </linearGradient>
      <linearGradient id="${uid}f" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="rgba(34,211,238,.22)"/>
        <stop offset="100%" stop-color="rgba(34,211,238,0)"/>
      </linearGradient>
    </defs>
    <path class="area" style="fill:url(#${uid}f)" d="${area}"/>
    <path class="line" style="stroke:url(#${uid}g)" d="${line}"/>`;
  return svg;
}

/** 带宽占用条的状态分级（用颜色表达压力，而不是等满了才报警） */
export function bwLevel(ratio) {
  if (ratio >= 0.9) return 'max';
  if (ratio >= 0.7) return 'hi';
  return '';
}
