import React from 'react';
import { phaseLabel, phaseTone, type Phase } from '../utils/status';
import { Badge } from '../ui/primitives';

interface Props {
  phase: Phase;
  size?: 'sm' | 'md';
}

/**
 * 状态徽章 —— 纯色底 + 色点，语义一目了然。
 *
 * 这里不再手写 Tailwind 色类（`bg-state-ok` 一类的令牌随设计系统迁到 index.css 后已不存在，
 * 旧实现会渲染出一个没有颜色的空点）；直接复用面板统一的 `<Badge>`，与概览页同一个圆点。
 */
export const StatusBadge: React.FC<Props> = ({ phase }) => (
  <Badge tone={phaseTone(phase)} live={phase === 1 || phase === 2}>
    {phaseLabel[phase]}
  </Badge>
);
