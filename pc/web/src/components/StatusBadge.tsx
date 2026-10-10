import React from 'react';
import { phaseLabel, phaseState, type Phase } from '../utils/status';

interface Props {
  phase: Phase;
  size?: 'sm' | 'md';
}

/**
 * 状态徽章 —— 极简：圆点 + 文字。
 * impeccable anti-pattern：不要泛阴影、不要装饰性背景、不要渐变。
 * 只用纯色底 + 色点，语义一目了然。
 */
export const StatusBadge: React.FC<Props> = ({ phase, size = 'md' }) => {
  const st = phaseState[phase];
  const dotColor = {
    ok: 'bg-state-ok',
    warn: 'bg-state-warn',
    error: 'bg-state-error',
    idle: 'bg-state-idle',
  }[st];

  const dotSize = size === 'sm' ? 'w-[6px] h-[6px]' : 'w-[8px] h-[8px]';
  const textSize = size === 'sm' ? 'text-[11px]' : 'text-[12px]';

  return (
    <span className={`badge ${textSize}`} data-state={st}>
      <span className={`${dotSize} rounded-full ${dotColor}`} />
      {phaseLabel[phase]}
    </span>
  );
};
