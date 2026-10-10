import { HostState } from '../api/bridge';

export type Phase = 0 | 1 | 2 | 3 | 4;

export const phaseLabel: Record<Phase, string> = {
  0: '未启用',
  1: '正在发现',
  2: '正在连接',
  3: '已连接',
  4: '连接失败',
};

export const phaseState: Record<Phase, 'ok' | 'warn' | 'error' | 'idle'> = {
  0: 'idle', 1: 'warn', 2: 'warn', 3: 'ok', 4: 'error',
};

export function phaseSentence(s: HostState): string {
  switch (s.link.phase) {
    case 3: return '手机已连上，可直接用';
    case 2: return '正在接入手机…';
    case 1: return '正在找同一 Wi‑Fi 下的手机';
    case 4: return s.link.error || '没连上手机';
    default: return '尚未连接';
  }
}

export function upTimeText(upMs: number): string {
  const sec = Math.floor(upMs / 1000);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
