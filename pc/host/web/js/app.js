/**
 * 面板主入口：视图路由 + 全局状态分发 + 顶栏渲染。
 *
 * 数据流是单向的：SSE 推来一份聚合状态 → app.js 分发到「顶栏」与「当前视图」。
 * 视图只需实现 `render(state)` 并返回 DOM，不需要自己订阅或清理，切换视图时天然重建。
 */

import { onState, onHealth, getHealth, connect } from './sse.js';
import { h, clear, icon, fmt, speedLabel, bwLevel, banner } from './ui.js';

import * as overview   from './views/overview.js';
import * as screen     from './views/screen.js';
import * as devices    from './views/devices.js';
import * as touchpad   from './views/touchpad.js';
import * as connection from './views/connection.js';
import * as hotkeys    from './views/hotkeys.js';
import * as settings   from './views/settings.js';

/* -------------------------------------------------------------- 视图注册 --- */
const VIEWS = [
  { id: 'overview',   label: '总览',        icon: 'gauge',    mod: overview },
  { id: 'screen',     label: '副屏',        icon: 'screen',   mod: screen },
  { id: 'devices',    label: '外设',        icon: 'chip',     mod: devices },
  { id: 'touchpad',   label: '触控板',      icon: 'pointer',  mod: touchpad },
  { id: 'connection', label: '连接与配对',  icon: 'plug',     mod: connection },
  { id: 'hotkeys',    label: '快捷键',      icon: 'keyboard', mod: hotkeys },
  { id: 'settings',   label: '设置与诊断',  icon: 'cog',      mod: settings },
];

const navEl     = document.getElementById('nav');
const viewHost  = document.getElementById('viewHost');
const bannersEl = document.getElementById('banners');

let currentId = VIEWS[0].id;
let lastState = null;

/* ---------------------------------------------------------------- 导航 --- */
function buildNav() {
  clear(navEl);
  for (const v of VIEWS) {
    const badge = h('span.nav-badge', { hidden: true });
    const el = h('button.nav-item', {
      type: 'button',
      dataset: { view: v.id },
      on: { click: () => go(v.id) },
    }, [
      h('span.nav-icon', { html: icon(v.icon) }),
      h('span', { text: v.label }),
      badge,
    ]);
    navEl.append(el);
  }
}

function markActive() {
  for (const el of navEl.querySelectorAll('.nav-item')) {
    el.classList.toggle('active', el.dataset.view === currentId);
  }
}

export function go(id) {
  const v = VIEWS.find((x) => x.id === id);
  if (!v) return;
  currentId = id;
  markActive();
  renderView();
}

/* ------------------------------------------------------------ 视图渲染 --- */
function renderView() {
  const v = VIEWS.find((x) => x.id === currentId);
  if (!v) return;
  const node = v.mod.render(lastState);
  // 必须**一次性原子替换**，不能先 clear() 再 append()：
  // 状态是 1Hz 推送的，会周期性触发重绘，中间那段"空窗期"在浏览器里就是可见闪烁
  // （截图也可能正好落在窗口里拍到空白页）。
  if (node) viewHost.replaceChildren(node);
  else clear(viewHost);
  // 视图切换后回到顶部，避免沿用上一个视图的滚动位置
  viewHost.scrollTop = 0;
}

/** 仅当状态确实变了才重绘当前视图，避免 1Hz 推送把正在操作的控件重置掉 */
function renderViewIfIdle() {
  const active = document.activeElement;
  if (active && viewHost.contains(active)) {
    const tag = active.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
    if (active.classList?.contains('recording')) return;   // 改键录制中不能重绘
  }
  renderView();
}

/* -------------------------------------------------------------- 顶栏 --- */
function renderTopbar(s) {
  const link = s?.link ?? {};

  document.getElementById('deviceName').textContent   = link.deviceName || '未发现设备';
  document.getElementById('deviceSerial').textContent = link.serial || '--';
  document.getElementById('chipPort').textContent     = link.port ?? '--';

  // 链路速度（有线）或往返延迟（无线）
  const chipSpeed = document.getElementById('chipSpeed');
  const speedVal  = document.getElementById('chipSpeedValue');
  chipSpeed.classList.remove('ok', 'warn', 'err');
  if (link.mode === 'wireless') {
    speedVal.textContent = link.rttMs != null ? `${fmt.ms(link.rttMs)}ms` : '无线';
    chipSpeed.classList.add(link.rttMs != null && link.rttMs < 15 ? 'ok' : 'warn');
  } else if (link.connected) {
    speedVal.textContent = link.speedLabel || speedLabel(link.speed);
    // 非 SuperSpeed 属于既定降级项，明确标黄而不是装作正常
    chipSpeed.classList.add(link.speed === 'super' || link.speed === 'super_plus' ? 'ok' : 'warn');
  } else {
    speedVal.textContent = '未连接';
  }

  // 带宽占用
  const bw = s?.bandwidth ?? {};
  const ratio = bw.totalMbps ? Math.min(1, (bw.usedMbps || 0) / bw.totalMbps) : 0;
  const fill  = document.getElementById('bwFill');
  document.getElementById('bwValue').textContent =
    bw.totalMbps ? `${fmt.int(bw.usedMbps)}/${fmt.int(bw.totalMbps)} Mbps` : '--';
  fill.style.width = `${(ratio * 100).toFixed(1)}%`;
  fill.className = 'bandwidth-fill' + (bwLevel(ratio) ? ' ' + bwLevel(ratio) : '');

  // 外设状态点：一眼看出哪些没起来
  const dots = document.getElementById('statusDots');
  clear(dots);
  const items = [
    ['副屏',   s?.display?.enabled ? 'on' : ''],
    ['触控板', s?.touchpad?.enabled ? 'on' : ''],
    ['摄像头', s?.camera?.enabled ? 'on' : (s?.camera?.available ? '' : 'warn')],
    ['音频',   s?.audio?.enabled ? 'on' : ''],
  ];
  for (const [name, cls] of items) {
    dots.append(h(`span.status-dot${cls ? '.' + cls : ''}`, { title: name }));
  }

  // 侧栏底部连接徽标
  const dot = document.getElementById('linkDot');
  dot.className = 'dot ' + (link.connected ? (link.mode === 'wireless' ? 'warn' : 'on') : 'idle');
  document.getElementById('linkMode').textContent =
    link.connected ? (link.mode === 'wireless' ? '无线模式' : '有线模式') : '未连接';
  document.getElementById('linkDetail').textContent =
    link.connected
      ? (link.mode === 'wireless' ? `${fmt.ms(link.rttMs)}ms · ${link.transport || 'tcp'}` : (link.speedLabel || speedLabel(link.speed)))
      : '等待设备';

  // 演示模式提示由 renderBanners 统一处理（放在这里会与它互相清空，形成闪烁）
}

/* -------------------------------------------------------- 告警横幅 --- */
function renderBanners(s) {
  const nodes = [];

  // 演示模式：必须显式告知，否则会让人以为面板接的是真设备
  if (s?.demo) {
    const b = banner({
      level: 'info',
      title: '演示模式',
      text: '未连接后端，当前展示的是内置样例数据（含已知的降级项），仅用于界面评审。',
    });
    b.dataset.demo = '1';
    nodes.push(b);
  }

  for (const a of (s?.alerts || [])) {
    nodes.push(banner({
      level: a.level || 'warn',
      title: a.title,
      text: a.text,
      action: a.action,
      onAction: () => go('settings'),
    }));
  }

  // 同样是原子替换，避免 1Hz 重绘造成横幅区域闪烁
  if (nodes.length) bannersEl.replaceChildren(...nodes);
  else clear(bannersEl);
}

/* ---------------------------------------------------------- 导航角标 --- */
function renderNavBadges(s) {
  const n = (s?.alerts || []).length;
  for (const el of navEl.querySelectorAll('.nav-item')) {
    const badge = el.querySelector('.nav-badge');
    if (!badge) continue;
    const show = el.dataset.view === 'settings' && n > 0;
    badge.hidden = !show;
    if (show) badge.textContent = String(n);
  }
}

/* -------------------------------------------------------- 连接健康 --- */
// 连接在、但超过阈值没有新帧 → 视为「链路静默」，用状态色提示而非弹窗打断操作
const STALE_MS = 4000;

function renderHealth(h) {
  const stale = h.live && h.lastUpdateAt > 0 && (Date.now() - h.lastUpdateAt > STALE_MS);
  document.body.classList.toggle('is-stale', !!stale);
  if (stale) {
    const detail = document.getElementById('linkDetail');
    if (detail) detail.textContent = '信号丢失 · 重连中…';
  }
}

/* -------------------------------------------------------------- 启动 --- */
function applyState(s) {
  lastState = s;
  renderTopbar(s);
  renderBanners(s);
  renderNavBadges(s);
  renderViewIfIdle();
}

function boot() {
  buildNav();
  markActive();
  renderView();

  onState(applyState);
  onHealth(renderHealth);
  setInterval(() => renderHealth(getHealth()), 1000);   // 静默检测（无新帧时也需刷新提示）
  connect();

  // 键盘快捷键：Ctrl/⌘ + 1..7 切换视图（面板自身的本地导航，与"全局热键"无关）
  window.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey) {
      const n = Number(e.key);
      if (n >= 1 && n <= VIEWS.length) { e.preventDefault(); go(VIEWS[n - 1].id); }
    }
  });
}

boot();
