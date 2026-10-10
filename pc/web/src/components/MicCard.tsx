import React, { useState } from 'react';
import { bridge, HostState } from '../api/bridge';
import { Switch } from './Switch';

interface Props { state: HostState }

/** 麦克风卡 —— 双栏左下。动作：转发开关 + 渲染设备下拉。 */
export const MicCard: React.FC<Props> = ({ state }) => {
  const [on, setOn] = useState(false);
  const mic = state.media.mic;
  const running = mic?.running ?? false;

  React.useEffect(() => { setOn(running); }, [running]);

  const toggle = async () => {
    await bridge.act('mic.toggle');
    setTimeout(() => setOn(!on), 400);
  };

  return (
    <div className="card card-inner animate-slide-up flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="section-title">麦克风</span>
          {running && <span className="text-[11px] text-state-ok font-mono tracking-[0.04em]">RUNNING</span>}
        </div>
        <Switch on={on} onChange={toggle} />
      </div>

      <select className="input-field h-[36px] text-[13px]">
        <option>跟随系统默认</option>
        <option disabled>（暂未枚举播放设备）</option>
      </select>

      <div className="mt-1 flex flex-col gap-0.5">
        {running ? (
          <>
            <div className="body-text text-on-surface">转发中</div>
            <div className="data-text">渲染到 {mic?.device || '跟随系统默认'}</div>
          </>
        ) : !state.media.connected ? (
          <>
            <div className="body-text text-on-variant">等待媒体连接</div>
            <div className="caption-text">需与手机同一 Wi‑Fi</div>
          </>
        ) : (
          <>
            <div className="body-text text-on-surface">未开启</div>
            <div className="caption-text">打开右侧开关：手机麦克风上行渲染到本机播放设备</div>
          </>
        )}
      </div>
    </div>
  );
};
