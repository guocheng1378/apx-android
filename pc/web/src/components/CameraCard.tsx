import React from 'react';
import { bridge, HostState } from '../api/bridge';
import { Switch } from './Switch';

interface Props { state: HostState }

/**
 * 摄像头卡（M7-c）—— 双栏第五格。
 * 动作：预览窗开关（camera.toggle）、虚拟摄像头开关（camera.vcam）。
 * 虚拟摄像头链路未真机实测，面板上如实标注，不假装可用。
 */
export const CameraCard: React.FC<Props> = ({ state }) => {
  const [on, setOn] = React.useState(false);
  const [vcam, setVcam] = React.useState(false);

  const cam = state.camera;
  const preview = cam?.previewOpen ?? false;
  React.useEffect(() => { setOn(preview); }, [preview]);
  React.useEffect(() => { setVcam(cam?.virtualCam ?? false); }, [cam?.virtualCam]);

  const togglePreview = async () => {
    await bridge.act('camera.toggle');
    // 等状态推流回新（对齐 ScreenCard 的做法）
    setTimeout(() => setOn(!on), 400);
  };
  const toggleVcam = async () => {
    await bridge.act('camera.vcam');
    setTimeout(() => setVcam(!vcam), 400);
  };

  return (
    <div className="card card-inner animate-slide-up flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="section-title">摄像头</span>
          {cam?.running && (
            <span className="text-[11px] text-state-ok font-mono tracking-[0.04em]">RUNNING</span>
          )}
        </div>
        <Switch on={on} onChange={togglePreview} />
      </div>

      <div className="flex items-center justify-between">
        <span className="caption-text">虚拟摄像头（供微信/会议端选）</span>
        <Switch on={vcam} onChange={toggleVcam} />
      </div>

      <div className="mt-1 flex flex-col gap-0.5">
        {cam?.error ? (
          <>
            <div className="body-text text-state-error">虚拟摄像头不可用</div>
            <div className="caption-text text-state-error">{cam.error}</div>
          </>
        ) : cam?.running ? (
          <>
            <div className="body-text text-on-surface">
              {cam?.frames ? '正在采集' : '等待手机画面'}
            </div>
            <div className="data-text">
              {cam?.frames
                ? `${cam.width}×${cam.height} · 已收 ${cam.frames} 帧${
                    cam.stale ? ' · 画面停滞' : ''
                  }${cam.virtualCam ? ' · 虚拟摄像头已注册' : ''}`
                : '打开手机端「摄像头采集」页开始'}
            </div>
          </>
        ) : !state.media.connected ? (
          <>
            <div className="body-text text-on-variant">等待媒体连接</div>
            <div className="caption-text">需与手机同一 Wi‑Fi，打开「Wi‑Fi 控制」</div>
          </>
        ) : (
          <>
            <div className="body-text text-on-surface">未开始</div>
            <div className="caption-text">打开右上开关：手机画面开预览窗到本机</div>
          </>
        )}

        {/* 诚实标注：虚拟摄像头链路（注册/微信拉流）未真机实测 */}
        {vcam && !cam?.error && (
          <div className="mt-1 pt-2 border-t border-stroke flex flex-col gap-1">
            <div className="caption-text text-on-tertiary">
              虚拟摄像头已注册（Win11，会话级）—— 但这条链路还没在真机微信/会议端跑过，
              如遇对方拉不到画面，关掉开关用预览窗，并到反馈页报问题。
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
