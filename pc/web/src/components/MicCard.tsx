import React, { useState } from 'react';
import { bridge, HostState, hostPlatform } from '../api/bridge';
import { Switch } from './Switch';
import { useAudioDevices, audioDeviceHint } from './useAudioDevices';

interface Props { state: HostState }

/** 麦克风卡 —— 双栏左下。动作：转发开关 + 渲染设备下拉。 */
export const MicCard: React.FC<Props> = ({ state }) => {
  const [on, setOn] = useState(false);
  const [devId, setDevId] = useState('');
  const { devices, defaultName } = useAudioDevices();
  // 同 SpeakerCard：端点枚举只有 Windows 有，其它平台给说明态（issue #52 第三条）
  const platform = hostPlatform(state);
  const mic = state.media.mic;
  const running = mic?.running ?? false;

  React.useEffect(() => { setOn(running); }, [running]);

  const toggle = async () => {
    // 选了具体设备就把 deviceId 带上；留空 = 跟随系统默认（后端两种都支持）
    await bridge.act('mic.toggle', devId ? { deviceId: devId } : {});
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

      {platform === 'windows' ? (
        <select className="input-field h-[36px] text-[13px]"
                value={devId}
                onChange={(e) => setDevId(e.target.value)}>
          <option value="">跟随系统默认{defaultName ? `（${defaultName}）` : ''}</option>
          {devices.map((d) => (
            <option key={d.id} value={d.id}>{d.name}{d.isDefault ? '（默认）' : ''}</option>
          ))}
          {devices.length === 0 && <option disabled>（本机不支持枚举播放设备）</option>}
        </select>
      ) : (
        <div className="caption-text text-on-variant leading-snug">{audioDeviceHint(platform, 'capture')}</div>
      )}

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
