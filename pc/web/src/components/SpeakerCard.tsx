import React, { useState } from 'react';
import { bridge, HostState, hostPlatform } from '../api/bridge';
import { Switch } from './Switch';
import { useAudioDevices, audioDeviceHint } from './useAudioDevices';

interface Props { state: HostState }

/**
 * 音箱卡 —— 双栏右上。
 * 动作：开关音箱、播放设备下拉、试听。
 */
export const SpeakerCard: React.FC<Props> = ({ state }) => {
  const [on, setOn] = useState(false);
  const [devId, setDevId] = useState('');
  const { devices, defaultName } = useAudioDevices();
  // 只有 Windows 能枚举真实播放端点；macOS/Linux 是空表而且永远不会变，
  // 与其给一个永远空着的下拉，不如直接说明「音频走本应用内直连」（issue #52 第三条）。
  const platform = hostPlatform(state);
  const audio = state.media.audio;
  const running = audio?.running ?? false;

  React.useEffect(() => { setOn(running); }, [running]);

  const toggle = async () => {
    // 选了具体设备就把 deviceId 带上；留空 = 跟随系统默认（后端两种都支持）
    await bridge.act('speaker.toggle', devId ? { deviceId: devId } : {});
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

      {/* 设备下拉 —— 跟随系统默认 / 实体播放设备（列表来自宿主枚举，枚举不到就不编条目）。
          macOS/Linux 没有端点可枚举：换成说明态，别让空下拉被读成「功能坏了」。 */}
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
        <div className="caption-text text-on-variant leading-snug">{audioDeviceHint(platform, 'play')}</div>
      )}

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
