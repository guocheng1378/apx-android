// IPC 桥接：前端 ↔ C++ 宿主。
// WebView2 提供 window.chrome.webview.postMessage / addEventListener 双向通信。
// 协议（与 web_panel.cpp 顶部注释一致）：
//   前端 → 后端：{kind:'act', id, name, payload} | {kind:'query', id, name} | {kind:'open-file', id, filter}
//   后端 → 前端：{kind:'act-response', id, ok, data} | {kind:'query-response', id, ok, data}
//                | {kind:'open-file-response', id, ok, path} | {kind:'state', t, data} | {kind:'event', name, hint}
//
// 所有请求用 id 做 correlation，响应 Promise 化。状态推流独立走 state 订阅。

type Kind = 'act' | 'query' | 'open-file';

export interface HostState {
  link: {
    phase: number;             // 0=idle 1=discovering 2=connecting 3=connected 4=failed
    phaseName: string;
    peer: string;
    rttMs: number;
    upMs: number;
    error: string;
    autoMode: boolean;
  };
  media: {
    connected: boolean;
    peer: string;
    error: string;
    videoFramesSent: number;
    audioFramesSent: number;
    micFrames: number;
    dropped: number;
    screen?: {
      running: boolean; fps: number; deviceName: string;
      width: number; height: number; framesSent: number;
      encodeMs: number; dropped: number; error: string;
    };
    audio?: {
      running: boolean; peak: number; device: string;
      sampleRate: number; channels: number; framesSent: number; error: string;
    };
    mic?: { running: boolean; device: string };
  };
  transports: Record<string, unknown>;
  counts: { mouse: number; keyboard: number; consumer: number; dropped: number };
  config: Record<string, unknown>;
}

interface Request {
  kind: Kind;
  id: string;
  resolve: (value: unknown) => void;
  reject: (reason: unknown) => void;
  timer: number;
}

type Listener = (state: HostState) => void;

class Bridge {
  private pending = new Map<string, Request>();
  private stateListeners = new Set<Listener>();
  private eventListeners = new Map<string, Set<(data: any) => void>>();
  private seq = 0;
  private ready = false;

  constructor() {
    if (typeof window !== 'undefined' && (window as any).chrome?.webview) {
      (window as any).chrome.webview.addEventListener('message', this.onMessage.bind(this));
      this.ready = true;
    } else {
      // 开发期 fallback：模拟宿主（无 WebView2 时给个假状态方便调 UI）
      console.warn('[bridge] WebView2 not available, running in mock mode');
      this.simulateMockState();
    }
  }

  private simulateMockState() {
    const mockInterval = setInterval(() => {
      if (this.stateListeners.size === 0) return;
      const state: HostState = {
        link: {
          phase: 3, phaseName: 'connected',
          peer: '192.168.1.5:9511', rttMs: 12, upMs: 12345000,
          error: '', autoMode: true,
        },
        media: {
          connected: true, peer: '192.168.1.5:9502', error: '',
          videoFramesSent: 1234, audioFramesSent: 567, micFrames: 0, dropped: 2,
          screen: { running: true, fps: 30, deviceName: '\\.\DISPLAY2',
                    width: 1920, height: 1080, framesSent: 1234,
                    encodeMs: 16.2, dropped: 3, error: '' },
          audio: { running: false, peak: 0, device: '', sampleRate: 48000, channels: 2, framesSent: 0, error: '' },
          mic: { running: false, device: '' },
        },
        transports: {},
        counts: { mouse: 4567, keyboard: 890, consumer: 45, dropped: 1 },
        config: { autostart: false, logLevel: 'info' },
      };
      this.stateListeners.forEach(l => l(state));
    }, 400);
    // 不保存 mockInterval —— 让页面存活期内持续推
    (window as any).__mockInterval = mockInterval;
  }

  private onMessage(event: MessageEvent) {
    const msg = event.data;
    if (!msg || typeof msg !== 'object') return;

    // 状态推流（400ms tick）
    if (msg.kind === 'state') {
      this.stateListeners.forEach(l => l(msg.data));
      return;
    }
    // 事件
    if (msg.kind === 'event') {
      const set = this.eventListeners.get(msg.name);
      set?.forEach(cb => cb(msg));
      return;
    }
    // 响应
    if (msg.id && (msg.kind === 'act-response' || msg.kind === 'query-response' || msg.kind === 'open-file-response')) {
      const req = this.pending.get(msg.id);
      if (req) {
        clearTimeout(req.timer);
        if (msg.ok !== false) req.resolve(msg);
        else req.reject(msg);
        this.pending.delete(msg.id);
      }
    }
  }

  /** 发起请求，返回 Promise */
  private request(kind: Kind, name: string, payload?: Record<string, unknown>): Promise<any> {
    const id = `req-${Date.now()}-${++this.seq}`;
    return new Promise((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${kind} ${name} 超时`));
      }, 10_000);
      this.pending.set(id, { kind, id, resolve, reject, timer: timeout });

      const msg = kind === 'open-file'
        ? { kind, id, filter: (payload as any)?.filter ?? '' }
        : { kind, id, name, payload: payload ?? {} };

      if (this.ready) {
        (window as any).chrome.webview.postMessage(msg);
      } else {
        // mock 模式：直接回一个空响应
        window.setTimeout(() => {
          this.pending.delete(id);
          resolve({ id, ok: true, data: {} });
        }, 50);
      }
    });
  }

  // —— 公共 API ——

  act(name: string, payload: Record<string, unknown> = {}): Promise<any> {
    return this.request('act', name, payload);
  }
  query(name: string): Promise<any> {
    return this.request('query', name);
  }
  openFile(filter: string): Promise<any> {
    return this.request('open-file', 'dummy', { filter });
  }

  subscribeState(listener: Listener): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }
  onEvent(name: string, cb: (data: any) => void): () => void {
    if (!this.eventListeners.has(name)) this.eventListeners.set(name, new Set());
    this.eventListeners.get(name)!.add(cb);
    return () => this.eventListeners.get(name)?.delete(cb);
  }
}

export const bridge = new Bridge();
