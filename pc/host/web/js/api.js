/**
 * 统一动作调用层。
 *
 * 所有与后端的交互都经此处，目的是保证三件事在**一处**得到保证，而不是每个视图各写一遍：
 *   1. 统一的响应体解析（后端约定 `{ok, code, message, data}`）
 *   2. 可读的错误信息（尤其 gadget 失败时后端会附带 dmesg 线索）
 *   3. 按钮的 pending / success / error 三态反馈
 *
 * 后端未就绪时（fetch 失败）会抛出 DemoModeError，由调用方决定降级呈现 ——
 * 面板在纯前端开发阶段也能完整点通，不会因为没后端就白屏。
 */

export class ApiError extends Error {
  constructor(message, { code = -1, status = 0, data = null } = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.data = data;
  }
}

export class DemoModeError extends Error {
  constructor(message = '后端未连接（演示模式）') {
    super(message);
    this.name = 'DemoModeError';
    this.demo = true;
  }
}

/** 演示模式标记：首次请求失败后置位，避免反复重试拖慢交互 */
let demoMode = false;
export const isDemoMode = () => demoMode;

async function request(method, path, body, { timeoutMs = 8000 } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);

  let res;
  try {
    res = await fetch(path, {
      method,
      signal: ctl.signal,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    // 网络层失败 = 后端没起来（不是业务错误）
    demoMode = true;
    throw new DemoModeError();
  } finally {
    clearTimeout(timer);
  }

  if (!res.ok) {
    throw new ApiError(`HTTP ${res.status}`, { status: res.status });
  }

  let payload;
  try {
    payload = await res.json();
  } catch {
    throw new ApiError('响应不是合法 JSON', { status: res.status });
  }

  demoMode = false;
  if (payload && payload.ok === false) {
    throw new ApiError(payload.message || '操作失败', {
      code: payload.code ?? -1,
      data: payload.data ?? null,
      status: res.status,
    });
  }
  return payload?.data ?? payload;
}

/** 发起一个动作（POST /api/act/<name>） */
export function act(name, payload = undefined, opts) {
  return request('POST', `/api/act/${name}`, payload ?? {}, opts);
}

/** 读取一个查询（GET /api/q/<name>） */
export function query(name, opts) {
  return request('GET', `/api/q/${name}`, undefined, opts);
}

/**
 * 把按钮包上三态反馈。
 *
 * 用法：`onClickWithFeedback(btn, () => act('screen.toggle'))`
 * 成功/失败用边框颜色 + 短暂闪烁表达，**失败信息由调用方就地展示**（不弹窗打断操作，
 * 这是本项目的既定交互原则）。
 */
export async function withFeedback(btn, fn, { onError } = {}) {
  if (!btn || btn.classList.contains('pending')) return;
  btn.classList.add('pending');
  try {
    const r = await fn();
    btn.classList.add('flash-ok');
    setTimeout(() => btn.classList.remove('flash-ok'), 700);
    return r;
  } catch (e) {
    btn.classList.add('flash-err');
    setTimeout(() => btn.classList.remove('flash-err'), 900);
    if (onError) onError(e);
    else console.warn('[act]', e?.message ?? e);
    return undefined;
  } finally {
    btn.classList.remove('pending');
  }
}
