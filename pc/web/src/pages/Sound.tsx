import React from 'react';
import { HostState, bridge, hostPlatform } from '../api/bridge';
import { Badge, Meter, Notice, Row, Section, Switch } from '../ui/primitives';
import { audioDeviceHint, useAudioDevices, useToggle } from '../ui/hooks';
import { IconAlert, IconInfo, IconMic, IconSound } from '../ui/icons';

interface Props {
  state: HostState;
}

/** 设备下拉：原生 select 换成统一描边样式（箭头由 .select-wrap 提供） */
const DeviceSelect: React.FC<{
  value: string;
  onChange: (v: string) => void;
  devices: { id: string; name: string; isDefault: boolean }[];
  defaultName: string;
  capture?: boolean;
}> = ({ value, onChange, devices, defaultName, capture }) => (
  <span className="select-wrap" style={{ flex: 1, minWidth: 0 }}>
    <select className="field" value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">跟随系统默认{defaultName ? `（${defaultName}）` : ''}</option>
      {devices.map((d) => (
        <option key={d.id} value={d.id}>
          {d.name}
          {d.isDefault ? '（默认）' : ''}
        </option>
      ))}
      {devices.length === 0 && (
        <option disabled>（本机不支持枚举{capture ? '录音' : '播放'}设备）</option>
      )}
    </select>
    <span className="chev">▾</span>
  </span>
);

/**
 * 声音页 —— 两条方向相反的链路放在一起看：
 *   音箱：本机系统声音 → 手机扬声器（AudioCapture）
 *   麦克风：手机麦克风 → 本机播放设备（MicBridge，渲染成本机的一个端点）
 *
 * 旧面板的「试听」按钮没有任何后端动作（点了永远没反应），已删 —— 假入口比没有更糟。
 */
export const Sound: React.FC<Props> = ({ state }) => {
  const platform = hostPlatform(state);
  const { devices, defaultName } = useAudioDevices();
  const [spkDev, setSpkDev] = React.useState('');
  const [micDev, setMicDev] = React.useState('');

  const audio = state.media.audio;
  const mic = state.media.mic;
  const playHint = audioDeviceHint(platform, 'play');
  const capHint = audioDeviceHint(platform, 'capture');

  const speaker = useToggle(!!audio?.running, () =>
    bridge.act('speaker.toggle', spkDev ? { deviceId: spkDev } : {}),
  );
  const microphone = useToggle(!!mic?.running, () =>
    bridge.act('mic.toggle', micDev ? { deviceId: micDev } : {}),
  );

  const peak = audio?.peak ?? 0;
  const peakPct = Math.min(1, peak * 1.6); // 峰值通常很小，放大一点让电平条看得见

  return (
    <>
      <Section index={0} title="音箱" note="本机系统声音送到手机">
        <div className="card">
          <Row
            lead={<IconSound size={19} />}
            leadTone="accent"
            title="系统声音转发"
            sub={
              audio?.running
                ? `正在播放${peak > 0.003 ? ` · 电平 ${(peak * 100).toFixed(0)}%` : ' · 系统当前没有声音'}`
                : !state.media.connected
                  ? '等待媒体连接（需与手机同一 Wi‑Fi）'
                  : '未开启 —— 打开右侧开关'
            }
            trail={
              <>
                {audio?.running && <Badge tone="ok" live>运行中</Badge>}
                <Switch
                  on={speaker.on}
                  busy={speaker.busy}
                  onChange={speaker.toggle}
                  label="系统声音转发到手机"
                />
              </>
            }
          />

          <div style={{ padding: '0 16px 12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span className="faint" style={{ fontSize: 12, width: 52, flex: 'none' }}>
                播放设备
              </span>
              {platform === 'windows' ? (
                <DeviceSelect
                  value={spkDev}
                  onChange={setSpkDev}
                  devices={devices}
                  defaultName={defaultName}
                />
              ) : (
                <span className="faint" style={{ fontSize: 12, lineHeight: 1.5 }}>
                  {playHint}
                </span>
              )}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 10 }}>
              <span className="faint" style={{ fontSize: 12, width: 52, flex: 'none' }}>
                电平
              </span>
              <div style={{ flex: 1 }}>
                <Meter value={peakPct} live={!!audio?.running && peak > 0.003} />
              </div>
              <span className="num faint" style={{ fontSize: 11.5, width: 44, textAlign: 'right' }}>
                {audio?.running ? `${(peak * 100).toFixed(0)}%` : '—'}
              </span>
            </div>
          </div>

          {(speaker.err || audio?.error) && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="err" icon={<IconAlert size={17} />} title="开启失败">
                {speaker.err || audio?.error}
              </Notice>
            </div>
          )}

          {!audio?.running && !audio?.error && !speaker.err && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="info" icon={<IconInfo size={17} />}>
                {state.media.connected
                  ? `采样 ${audio?.sampleRate ?? 48000} Hz · ${audio?.channels ?? 2} 声道；打开开关后手机扬声器开始出声。`
                  : '声音走媒体通道（9502）：先连上手机再打开。'}
              </Notice>
            </div>
          )}
        </div>
      </Section>

      <Section index={1} title="麦克风" note="手机麦克风回传到本机播放">
        <div className="card">
          <Row
            lead={<IconMic size={19} />}
            leadTone="accent"
            title="手机麦克风"
            sub={
              mic?.running
                ? `转发中 · 渲染到 ${mic.device || '跟随系统默认'}`
                : !state.media.connected
                  ? '等待媒体连接（需与手机同一 Wi‑Fi）'
                  : '未开启 —— 打开右侧开关'
            }
            trail={
              <>
                {mic?.running && <Badge tone="ok" live>运行中</Badge>}
                <Switch
                  on={microphone.on}
                  busy={microphone.busy}
                  onChange={microphone.toggle}
                  label="手机麦克风回传"
                />
              </>
            }
          />

          <div style={{ padding: '0 16px 14px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span className="faint" style={{ fontSize: 12, width: 52, flex: 'none' }}>
                渲染到
              </span>
              {platform === 'windows' ? (
                <DeviceSelect
                  value={micDev}
                  onChange={setMicDev}
                  devices={devices}
                  defaultName={defaultName}
                  capture
                />
              ) : (
                <span className="faint" style={{ fontSize: 12, lineHeight: 1.5 }}>
                  {capHint}
                </span>
              )}
            </div>
          </div>

          {microphone.err && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="err" icon={<IconAlert size={17} />} title="开启失败">
                {microphone.err}
              </Notice>
            </div>
          )}

          {!mic?.running && !microphone.err && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="info" icon={<IconInfo size={17} />}>
                {platform === 'windows'
                  ? '手机麦克风会渲染到上面选的播放设备；选「跟随系统默认」时由系统决定。'
                  : '这条链路把手机麦克风渲染到本机播放端点；本平台不注册系统音频设备（见上方说明）。'}
              </Notice>
            </div>
          )}
        </div>
      </Section>
    </>
  );
};
