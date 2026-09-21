/**
 * 状态订阅：单条 SSE 长连接，1 Hz 推送聚合状态。
 *
 * 为什么是 SSE 而不是 WebSocket：面板本质是「服务端单向推送状态 + 客户端发指令」，
 * SSE 无需握手与帧协议，后端实现也只需一个 `text/event-stream` 长连接。
 * 视图切换**不重建连接**（所有数据由这一条流分发）。
 *
 * 演示模式：后端未就绪时用内置样例数据驱动界面，便于纯前端阶段开发与评审。
 * 样例会刻意包含「降级项」（传感器驱动异常、FunctionFS 不可用），
 * 以便校验面板**如实呈现异常**这一硬性要求 —— 面板不得把不可用伪装成可用。
 */

import { isDemoMode } from './api.js';

const listeners = new Set();

/** 订阅状态更新；返回取消订阅函数 */
export function onState(fn) {
  listeners.add(fn);
  if (lastState) fn(lastState);
  return () => listeners.delete(fn);
}

let lastState = null;
export const getState = () => lastState;

/**
 * 连接健康：供顶栏/侧栏区分「实时数据 / 演示数据 / 链路静默重连中」。
 * - connected：SSE 是否处于已连接（收到过 open/state）
 * - live：是否拿到过**真实**后端数据（区别于演示数据）
 * - lastUpdateAt：最近一次收到 state 的时间戳，用于检测「静默」（连接在但长时间无帧）
 */
let health = { connected: false, live: false, lastUpdateAt: 0 };
export const getHealth = () => health;

const healthListeners = new Set();
export function onHealth(fn) {
  healthListeners.add(fn);
  fn(health);
  return () => healthListeners.delete(fn);
}

function emitHealth(patch) {
  health = { ...health, ...patch };
  for (const fn of healthListeners) {
    try { fn(health); } catch (e) { console.error('[sse] health listener error', e); }
  }
}

function emit(state) {
  lastState = state;
  for (const fn of listeners) {
    try { fn(state); } catch (e) { console.error('[sse] listener error', e); }
  }
}

// ---------------------------------------------------------------- 演示数据 --
// 刻意贴近真机现状（含已知缺陷），避免演示数据比真实情况"更漂亮"而误导评审。
function demoState(tick) {
  const t = tick / 10;
  return {
    demo: true,
    link: {
      mode: 'wired',              // wired | wireless
      connected: true,
      transport: 'usb',
      speed: 'high',              // full | high | super | super_plus
      speedLabel: '480Mbps',
      rttMs: 1.2 + Math.sin(t) * 0.3,
      protocolVersion: '1.3',
      deviceName: '2509FPN0BC',
      serial: 'APX00000001',
      port: 47990,
    },
    display: {
      enabled: tick % 40 > 8,      // 周期性开关，便于观察状态切换
      backend: 'C',                // A 第三方驱动 | B 自研 | C 降级(仅停推流)
      backendLabel: 'C · 无插拔能力',
      width: 1080, height: 2400, refreshHz: 60, orientation: 'portrait',
      codec: 'HEVC', encoder: 'NVENC',
      bitrateMbps: 11.4 + Math.sin(t * .7) * 1.8,
      fps: 59.2 + Math.sin(t * 1.3) * 0.6,
      droppedFrames: Math.max(0, Math.round(Math.sin(t / 3) * 4)),
      e2eMs: 24 + Math.sin(t * .9) * 4,
    },
    touchpad: { enabled: false, path: 'bt', pathLabel: '蓝牙 HID（免驱）', latencyMs: 8.6, drops: 0 },
    camera:    { enabled: false, route: 'uvc', routeLabel: 'f_uvc 复合设备', available: true, note: '' },
    audio:     { enabled: true, route: 'speaker', sampleRate: 48000, channels: 2 },
    sensors: [
      { id: 'accel',   name: '加速度计',   ok: false, note: 'Windows 侧 Code 10' },
      { id: 'gyro',    name: '陀螺仪',     ok: false, note: '随 IMU TLC 一并异常' },
      { id: 'mag',     name: '磁力计',     ok: false, note: '随 IMU TLC 一并异常' },
      { id: 'light',   name: '环境光',     ok: true,  note: '' },
      { id: 'prox',    name: '接近',       ok: true,  note: '' },
      { id: 'press',   name: '气压',       ok: true,  note: '' },
      { id: 'orient',  name: '设备方向',   ok: true,  note: '' },
      { id: 'incl',    name: '倾角计',     ok: true,  note: '' },
      { id: 'temp',    name: '环境温度',   ok: true,  note: '' },
      { id: 'humid',   name: '湿度',       ok: true,  note: '' },
      { id: 'steps',   name: '计步器',     ok: true,  note: '' },
      { id: 'hr',      name: '心率',       ok: true,  note: '' },
    ],
    peripherals: {
      gps:      { ok: true,  label: 'GPS · COM3' },
      vibrate:  { ok: true,  label: '振动 / 手电 / 红外' },
      battery:  { ok: true,  label: '电池 · 78%' },
      consumer: { ok: true,  label: '多媒体键' },
    },
    bandwidth: {
      usedMbps: 96,
      totalMbps: 300,
      items: [
        { name: '副屏视频', mbps: 82, prio: 3 },
        { name: '触控上行', mbps: 2,  prio: 0 },
        { name: '音频',     mbps: 12, prio: 4 },
        { name: 'HID 传感器', mbps: 1, prio: 1 },
      ],
    },
    rttHistory: Array.from({ length: 60 }, (_, i) =>
      1.2 + Math.sin((tick - 59 + i) / 10) * 0.35 + (Math.random() - .5) * .15),
    wireless: {
      connected: false,
      token: 'APX-DEMO-TOKEN',
      qrPayload: 'apx://192.168.1.10:9500?t=APX-DEMO-TOKEN',
      host: '192.168.1.10',
      port: 9500,
      discovered: [
        { name: 'Pixel 演示机', host: '192.168.1.10', port: 9500, token: 'APX-DEMO-TOKEN' },
      ],
    },
    alerts: [
      {
        level: 'warn',
        title: 'HID 传感器驱动未就绪（Code 10）',
        text: 'IMU 相关的三个 TLC 在 Windows 侧显示为「设备无法启动」。'
            + '描述符已按 SensorsHIDClassDriver 要求补齐 Feature Report，但仍未起来，待继续排查。',
        action: '查看解决方案',
      },
      {
        level: 'info',
        title: '副屏走降级路径（后端 C）',
        text: '当前未检测到支持运行时插拔的虚拟显示器驱动，关闭副屏时只会停止推流，'
            + '显示器本身仍会留在系统中。',
        action: '了解详情',
      },
    ],
    hotkeys: [
      { id: 'screen.toggle',   label: '副屏开关 / 全屏', mods: ['ctrl', 'alt'], key: 'F1', enabled: true },
      { id: 'screen.cycle',    label: '分辨率与方向切换', mods: ['ctrl', 'alt'], key: 'F2', enabled: true },
      { id: 'touchpad.toggle', label: '触控板开关',       mods: ['ctrl', 'alt'], key: 'F3', enabled: true },
      { id: 'sensor.toggle',   label: '传感器启停',       mods: ['ctrl', 'alt'], key: 'F4', enabled: true },
      { id: 'camera.toggle',   label: '摄像头开关',       mods: ['ctrl', 'alt'], key: 'F5', enabled: false },
      { id: 'audio.route',     label: '音频路由',         mods: ['ctrl', 'alt'], key: 'F6', enabled: true },
      { id: 'device.toggle',   label: '设备连接 / 断开',  mods: ['ctrl', 'alt'], key: 'F7', enabled: true },
    ],
    scenes: [
      { id: 'present', name: '演示模式', desc: '开副屏 + 通知静音 + 音频静音' },
      { id: 'create',  name: '创作模式', desc: '关触控板 + 开数位板 + 高采样率' },
      { id: 'touchpad', name: '触控板模式', desc: '关副屏 + 开触控板 + 关摄像头' },
    ],
  };
}

// -------------------------------------------------------------------- 连接 --
let es = null;
let demoTimer = null;
let tick = 0;

export function connect() {
  if (es) return;

  try {
    es = new EventSource('/api/events');
  } catch {
    enterDemo();
    return;
  }

  es.addEventListener('state', (ev) => {
    // 收到真实状态即退出演示。**刻意不关闭 EventSource**：
    // 若演示期间后端才上线，这条连接会自动接管，无需刷新页面。
    leaveDemo();
    emitHealth({ connected: true, live: true, lastUpdateAt: Date.now() });
    try { emit(JSON.parse(ev.data)); } catch (e) { console.error('[sse] bad state', e); }
  });

  es.onerror = () => {
    // EventSource 自身会按浏览器策略自动重连；这里只更新健康状态，
    // 并在从未拿到真实数据时补上演示数据，避免界面空白。
    emitHealth({ connected: false });
    if (!lastState || lastState.demo) enterDemo();
  };
}

/** 进入演示模式（幂等）：仅启动样例数据驱动，**不触碰 EventSource** */
function enterDemo() {
  if (demoTimer) return;
  console.info('[sse] 后端未连接，进入演示模式（样例数据）');
  const push = () => emit(demoState(++tick));
  push();
  demoTimer = setInterval(push, 1000);
}

function leaveDemo() {
  if (demoTimer) { clearInterval(demoTimer); demoTimer = null; }
}
