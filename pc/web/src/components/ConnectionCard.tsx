import React, { useState } from 'react';
import { bridge, HostState } from '../api/bridge';
import { StatusBadge } from './StatusBadge';
import { phaseSentence, upTimeText } from '../utils/status';

interface Props {
  state: HostState;
  onManualConnect: (host: string, port: number) => void;
}

/**
 * 连接卡 —— 整宽卡，第一屏最重要的信息。
 * impeccable anti-pattern：
 *   ✗ 不要卡套卡（本卡是顶层卡，内部直接布局，不嵌 card）
 *   ✗ 不要装饰性 sparkline（旧面板上的 RTT 历史图在 Web 版删掉 —— convey nothing meaningful）
 *   ✗ 不要玻璃拟态（backdrop-blur）
 */
export const ConnectionCard: React.FC<Props> = ({ state, onManualConnect }) => {
  const [host, setHost] = useState('');
  const [port, setPort] = useState('9511');
  const s = state.link;

  return (
    <div className="card animate-fade-in">
      <div className="card-inner flex items-center justify-between gap-4">
        <div className="flex flex-col gap-1">
          <StatusBadge phase={s.phase as 0} />
          <div className="body-text text-on-surface">{phaseSentence(state)}</div>
          {s.peer && (
            <div className="data-text">
              {s.peer} · RTT {s.rttMs.toFixed(0)} ms · 已连 {upTimeText(s.upMs)}
            </div>
          )}
        </div>

        {/* 手动 IP —— 自动发现失败时的兜底入口 */}
        <div className="flex items-center gap-2 shrink-0">
          <input
            className="input-field w-[200px] h-[38px] text-[13px]"
            placeholder="手机 IP"
            value={host}
            onChange={e => setHost(e.target.value)}
            disabled={s.phase === 3 || s.phase === 2}
          />
          <input
            className="input-field w-[72px] h-[38px] text-[13px]"
            placeholder="端口"
            value={port}
            onChange={e => setPort(e.target.value.replace(/\D/g, ''))}
            disabled={s.phase === 3 || s.phase === 2}
          />
          <button
            className="btn-ghost h-[38px]"
            onClick={() => onManualConnect(host, parseInt(port) || 9511)}
            disabled={!host || s.phase === 3 || s.phase === 2}
          >
            手动连接
          </button>
        </div>
      </div>
    </div>
  );
};
