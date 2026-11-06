import React from 'react';
import { HostState, bridge } from '../api/bridge';
import { Badge, Metrics, Notice, Row, Section, Segmented, Switch } from '../ui/primitives';
import { useToggle } from '../ui/hooks';
import { IconAlert, IconCamera, IconDisplay, IconInfo } from '../ui/icons';

interface Props {
  state: HostState;
}

/**
 * 画面页 —— 副屏（本机桌面 → 手机）+ 摄像头（手机 → 本机预览 / 虚拟摄像头）。
 *
 * 对照组件的取舍：旧 `ScreenCard` 里有「镜像/扩展」「分辨率」两排 chip，但后端
 * `screen.toggle` 走的是固定参数（web_panel.cpp: opt.mirrorMode=false, bitrateKbps=8000），
 * 点了不生效 —— 假控件比没有控件更伤。这里只保留真能控的开关与真实遥测；
 * 码率/自适应这类**确实会写配置**的项收进设置页。
 */
export const Display: React.FC<Props> = ({ state }) => {
  const scr = state.media.screen;
  const running = !!scr?.running;
  const cfg = state.config as Record<string, any>;

  /*
   * 投屏模式：扩展屏要 IddCx 虚拟显示器，桌面镜像直接抓主屏。
   * 以前内核把 mirrorMode 写死 false、面板也没有切换入口 —— 「复制屏幕」等于不存在。
   * 现在开流时把选择带过去（act 参数），模式本身由宿主做会话内记忆，页面重开读回。
   */
  const [mode, setMode] = React.useState<'extend' | 'mirror'>(() =>
    cfg?.screenMirror ? 'mirror' : 'extend',
  );
  const screen = useToggle(running, () =>
    bridge.act('screen.toggle', { mirror: mode === 'mirror' }),
  );

  const bitrateText = `${cfg?.bitrateMbps ?? 8} Mbps${cfg?.adaptiveBitrate ? ' · 自适应' : ''}`;

  const cam = state.camera;
  const preview = !!cam?.previewOpen;
  const native = cam?.native ?? true;
  const camera = useToggle(preview, () => bridge.act('camera.toggle'));
  const vcam = useToggle(!!cam?.virtualCam, () => bridge.act('camera.vcam'));

  const screenSub = running
    ? `正在投屏 · ${scr?.deviceName || (mode === 'mirror' ? '桌面镜像' : '扩展屏')}`
    : scr?.error
      ? '投屏失败，原因见下'
      : !state.media.connected
        ? '等待媒体连接（需与手机同一 Wi‑Fi）'
        : '未开始 —— 打开右侧开关';

  const cameraSub = cam?.error
    ? '虚拟摄像头不可用'
    : cam?.running
      ? cam?.frames
        ? '正在采集手机画面'
        : '等待手机画面'
      : !state.media.connected
        ? '等待媒体连接'
        : '未开始 —— 打开右侧开关';

  return (
    <>
      <Section index={0} title="副屏" note={`把本机桌面投到手机上 · ${bitrateText}`}>
        <div className="card">
          <Row
            lead={<IconDisplay size={19} />}
            leadTone="accent"
            title="桌面投屏"
            sub={screenSub}
            trail={
              <>
                {running && <Badge tone="ok" live>运行中</Badge>}
                <Switch on={screen.on} busy={screen.busy} onChange={screen.toggle} label="副屏投屏" />
              </>
            }
          />

          {/* 模式要在开流时定：运行中禁止切换（点了只会误导），停止后即可改 */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '0 16px 12px',
              flexWrap: 'wrap',
            }}
          >
            <span className="faint" style={{ fontSize: 12, width: 52, flex: 'none' }}>
              模式
            </span>
            <Segmented
              value={mode}
              disabled={running}
              onChange={(v) => setMode(v as 'extend' | 'mirror')}
              options={[
                { value: 'extend', label: '扩展屏' },
                { value: 'mirror', label: '桌面镜像' },
              ]}
            />
            <span className="faint" style={{ fontSize: 11.5, lineHeight: 1.5 }}>
              {running
                ? '停止后可切换'
                : mode === 'mirror'
                  ? '整块桌面复制过去，不需要虚拟显示器'
                  : '需要 IddCx 虚拟显示器，没有会如实报错'}
            </span>
          </div>

          <div style={{ padding: '0 16px 14px' }}>
            <Metrics
              items={[
                {
                  k: '分辨率',
                  v: running && scr?.width ? `${scr.width}×${scr.height}` : '—',
                  dim: !running,
                },
                { k: '帧率', v: running ? `${(scr?.fps ?? 0).toFixed(0)} fps` : '—', dim: !running },
                {
                  k: '编码耗时',
                  v: running ? `${(scr?.encodeMs ?? 0).toFixed(1)} ms` : '—',
                  dim: !running,
                },
                {
                  k: '丢帧',
                  v: running ? (scr?.dropped ?? 0) : '—',
                  dim: !running || (scr?.dropped ?? 0) === 0,
                },
              ]}
            />
          </div>

          {screen.err && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="err" icon={<IconAlert size={17} />} title="开关失败">
                {screen.err}
              </Notice>
            </div>
          )}

          {scr?.error && !running && (
            <div style={{ padding: '0 16px 14px', display: 'flex', flexDirection: 'column', gap: 8 }}>
              <Notice tone="err" icon={<IconAlert size={17} />} title="投屏失败">
                {scr.error}
              </Notice>

              {/* mac/Linux：本机不挂虚拟显示器，副屏的出路 = 自装 Sunshine + Moonlight。
                  只做检测 + 指引（不内嵌 Moonlight：GPLv3 与 MIT 冲突，ROADMAP §2.5）。 */}
              {typeof scr.sunshineInstalled === 'boolean' && (
                <Notice tone="info" icon={<IconInfo size={17} />}>
                  {scr.sunshineInstalled ? (
                    scr.sunshineRunning ? (
                      <>
                        检测到 Sunshine 已在跑（端口 {scr.sunshineHttpsPort}）—— 手机端装 Moonlight 即可连。
                        {scr.sunshineUrl && (
                          <a
                            href={scr.sunshineUrl}
                            target="_blank"
                            rel="noreferrer"
                            style={{ color: 'var(--accent)', marginLeft: 6 }}
                          >
                            打开 Sunshine 设置页
                          </a>
                        )}
                      </>
                    ) : (
                      <>检测到已安装 Sunshine，但 {scr.sunshineHttpsPort} 端口没有服务在监听 —— 打开 Sunshine 应用后刷新本页。</>
                    )
                  ) : (
                    <>
                      mac 上的副屏出路：安装 Sunshine（本机）+ Moonlight（手机）；本面板只做检测与指引，
                      不内置客户端（Moonlight 是 GPLv3，与本项目 MIT 冲突）。安装后若没被检测到，
                      说明它不在 ~/.config/sunshine/，可在 Sunshine 里确认端口后手动连。
                    </>
                  )}
                </Notice>
              )}
            </div>
          )}

          {!running && !scr?.error && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="info" icon={<IconInfo size={17} />}>
                {state.media.connected
                  ? '打开开关后，本机桌面开始编码推流，并会唤起手机端的副屏页。'
                  : '副屏走媒体通道（9502）：先让手机与本机连上同一 Wi‑Fi，或用 USB / 蓝牙通道。'}
              </Notice>
            </div>
          )}
        </div>
      </Section>

      <Section index={1} title="摄像头" note="手机画面回传本机；可选注册为虚拟摄像头">
        <div className="card">
          <Row
            lead={<IconCamera size={19} />}
            leadTone="accent"
            title="预览手机画面"
            sub={cameraSub}
            trail={
              <>
                {cam?.running && <Badge tone="ok" live>采集中</Badge>}
                <Switch on={camera.on} busy={camera.busy} onChange={camera.toggle} label="预览手机画面" />
              </>
            }
          />
          <Row
            title="虚拟摄像头"
            sub={
              native
                ? '注册给微信 / 会议软件选择（会话级，Win11）'
                : 'macOS 不支持虚拟摄像头出口（仅 Windows 可用）'
            }
            trail={
              native ? (
                <Switch on={vcam.on} busy={vcam.busy} onChange={vcam.toggle} label="虚拟摄像头" />
              ) : (
                <span className="faint" style={{ fontSize: 12.5 }}>
                  不可用
                </span>
              )
            }
          />

          <div style={{ padding: '0 16px 14px' }}>
            <Metrics
              items={[
                {
                  k: '画面',
                  v: cam?.width ? `${cam.width}×${cam.height}` : '—',
                  dim: !cam?.width,
                },
                { k: '已收帧', v: cam?.frames ?? 0, dim: !cam?.frames },
                {
                  k: '接收量',
                  v: cam?.bytes ? `${(cam.bytes / 1024 / 1024).toFixed(1)} MB` : '—',
                  dim: !cam?.bytes,
                },
                {
                  k: '画面状态',
                  v: cam?.stale ? '停滞' : cam?.frames ? '正常' : '—',
                  dim: !cam?.frames,
                },
              ]}
            />
          </div>

          {cam?.error && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="err" icon={<IconAlert size={17} />} title="虚拟摄像头不可用">
                {cam.error}
              </Notice>
            </div>
          )}

          {cam?.virtualCam && !cam?.error && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="warn" icon={<IconInfo size={17} />} title="这条链路还没在真机上跑过">
                虚拟摄像头已注册（Win11，会话级），但微信 / 会议端能否拉流尚未实测。如遇对方拉不到画面，
                关掉开关改用预览窗，并在反馈里说明。
              </Notice>
            </div>
          )}

          {!cam?.running && !cam?.error && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="info" icon={<IconInfo size={17} />}>
                {state.media.connected
                  ? '打开预览开关后，手机端会拉起摄像头采集页，画面经媒体通道回传本机。'
                  : '摄像头走媒体通道（9502）：先连上手机再开预览。'}
              </Notice>
            </div>
          )}
        </div>
      </Section>
    </>
  );
};
