import React, { useState } from 'react';
import { bridge, HostState } from '../api/bridge';
import { Switch } from './Switch';

interface Props { state: HostState }

/**
 * 音箱卡 —— 双栏右上。
 * 动作：开关音箱、播放设备下拉、试听。
 */
export const SpeakerCard: React.FC<Props> = ({ state }) => {
  const [on, setOn] = useState(false);
  const audio = state.media.audio;
  const running = audio?.running ?? false;

  React.useEffect(() => { setOn(running); }, [running]);

  const toggle = async () => {
    await bridge.act('speaker.toggle');
    setTimeout(() => setOn(!on), 400);
  };

  const peakLabel = (audio?.peak ?? 0) > 0.003
    ? `电平 ${((audio?.peak ?? 0) * 100).toFixed(0)}%`
    : audio?.running
      ? '系统当前没有声音'
      : '';

  return (
    <div className="card card-inner animate-slide-up flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="section-title">音箱</span>
          {running && <span className="text-[11px] text-state-ok font-mono tracking-[0.04em]">RUNNING</span>}
        </div>
        <Switch on={on} onChange={toggle} />
      </div>

      {/* 设备下拉 —— 跟随系统默认 / 实体播放设备 */}
      <select className="input-field h-[36px] text-[13px]">
        <option>跟随系统默认</option>
        <option disabled>（暂未枚举播放设备）</option>
      </select>

      {/* 试听按钮 */}
      <button className="btn-ghost h-[36px] text-[13px] w-fit"
              disabled={!state.media.connected}>
        试听
      </button>

      <div className="mt-1 flex flex-col gap-0.5">
        {running ? (
          <>
            <div className="body-text text-on-surface">
              正在播放{peakLabel ? ` · ${peakLabel}` : ''}
            </div>
            <div className="data-text">
              {audio?.device || '跟随系统默认'} · 已送 {audio?.framesSent ?? 0} 片
            </div>
          </>
        ) : audio?.error ? (
          <>
            <div className="body-text text-state-error">开启失败</div>
            <div className="caption-text text-state-error">{audio.error}</div>
          </>
        ) : !state.media.connected ? (
          <>
            <div className="body-text text-on-variant">等待媒体连接</div>
            <div className="caption-text">需与手机同一 Wi‑Fi</div>
          </>
        ) : (
          <>
            <div className="body-text text-on-surface">未开启</div>
            <div className="caption-text">打开右侧开关：把本机系统声音送到手机扬声器</div>
          </>
        )}
      </div>
    </div>
  );
};
