import { HostState } from '../api/bridge';

export type Phase = 0 | 1 | 2 | 3 | 4;

export const phaseLabel: Record<Phase, string> = {
  0: '未启用',
  1: '正在发现',
  2: '正在连接',
  3: '已连接',
  4: '连接失败',
};

/** 徽章语义色：与前端的 --ok / --warn / --err / 中性一一对应 */
export type Tone = 'ok' | 'warn' | 'err' | 'idle' | 'accent';

export function phaseTone(phase: Phase): Tone {
  switch (phase) {
    case 3:
      return 'ok';
    case 1:
    case 2:
      return 'warn';
    case 4:
      return 'err';
    default:
      return 'idle';
  }
}

/** 一句话说清「现在是什么状态、下一步能干什么」 */
export function phaseSentence(s: HostState): string {
  switch (s.link.phase) {
    case 3:
      return '手机已连上，可直接用';
    case 2:
      return '正在接入手机…';
    case 1:
      return '正在找同一 Wi‑Fi 下的手机';
    case 4:
      return s.link.error || '没连上手机';
    default:
      return '尚未连接';
  }
}

/** 已连时长 → H:MM:SS */
export function upTimeText(upMs: number): string {
  const sec = Math.floor((upMs || 0) / 1000);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
