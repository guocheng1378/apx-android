import React from 'react';
import { HostState, bridge, hostPlatform } from '../api/bridge';
import { transportsOf } from '../api/hostTypes';
import { phaseLabel, phaseSentence, phaseTone, upTimeText } from '../utils/status';
import { Badge, Metrics, Notice, Row, Section, Switch } from '../ui/primitives';
import { useAction, useToggle } from '../ui/hooks';
import {
  IconAlert,
  IconBluetooth,
  IconCamera,
  IconChevronRight,
  IconDisplay,
  IconFileUp,
  IconMic,
  IconPower,
  IconSound,
  IconTransfer,
  IconUsb,
  IconWifi,
} from '../ui/icons';
import type { PageId } from '../nav';

interface Props {
  state: HostState;
  onNavigate: (page: PageId) => void;
}

const PLATFORM_LABEL: Record<string, string> = {
  windows: 'Windows',
  macos: 'macOS',
  linux: 'Linux',
};

/**
 * 概览页 —— 第一屏只回答三个问题：
 *   ① 现在连上没有？② 用什么方式连的？③ 想开的那个功能在哪？
 *
 * 与手机端「状态页」同构：一张主卡讲链路，下面按分组列通道与模块；
 * 这里把「模块」换成入口磁贴 —— 桌面端每个功能都有自己的页，点了是**切换页面**，
 * 不再弹窗（旧面板每个功能一个 Modal，是这次点名要去掉的东西）。
 */
export const Overview: React.FC<Props> = ({ state, onNavigate }) => {
  const s = state.link;
  const tp = transportsOf(state);
  const phone = tp.phone ?? {};
  const ctrl = tp.ctrlServer;
  const platform = hostPlatform(state);

  // 无线：真开关（启停 9511 自动发现）。phase 0=未启用 / 4=手动连接失败 视为关。
  const wifiOn = s.phase !== 0 && s.phase !== 4;
  const wifi = useToggle(wifiOn, (next) =>
    bridge.act(next ? 'session.startAuto' : 'session.disconnect'),
  );

  // 手动指定 IP：自动发现失败时的兜底（就地展开，不弹窗）
  const [manualOpen, setManualOpen] = React.useState(false);
  const [host, setHost] = React.useState('');
  const [port, setPort] = React.useState('9511');
  const manual = useAction();
  const busyLink = s.phase === 2 || s.phase === 3;

  const submitManual = async () => {
    if (!host.trim()) return;
    const ok = await manual.run(() =>
      bridge.act('session.connectManual', { host: host.trim(), port: parseInt(port) || 9511 }),
    );
    if (ok) setManualOpen(false);
  };

  // macOS 注入权限：没授权时手机发来的键鼠会被系统静默丢弃 —— 必须说出来
  const trustLabel = tp.inputTrustLabel || '辅助功能';
  const procName = tp.inputProc ? tp.inputProc.split(/[\\/]/).pop() || tp.inputProc : '';
  const procKind = tp.inputProcKind || '';
  const [rechecking, setRechecking] = React.useState(false);
  const [recheckHint, setRecheckHint] = React.useState('');
  const recheck = async () => {
    setRechecking(true);
    try {
      const r: any = await bridge.act('input.recheck');
      if (r?.ok && r?.data?.trusted === false)
        setRecheckHint('仍未授权：请用「打开系统设置」勾选上面那一项，回来点「重启应用」');
      else setRecheckHint('');
    } catch {
      /* 宿主未就绪：横幅会随 400ms 轮询自己刷新 */
    }
    setRechecking(false);
  };

  const primary =
    s.phase === 3 || s.phase === 1 || s.phase === 2
      ? {
          label: s.phase === 3 ? '断开连接' : '取消发现',
          icon: <IconPower size={16} />,
          next: false,
        }
      : {
          label: s.phase === 4 ? '重新发现' : '开始发现手机',
          icon: <IconChevronRight size={16} />,
          next: true,
        };

  const screenRunning = !!state.media.screen?.running;
  const audioRunning = !!state.media.audio?.running;
  const micRunning = !!state.media.mic?.running;
  const lsRunning = !!state.localsend?.running;
  const camRunning = !!state.camera?.running;

  const tiles: { page: PageId; icon: React.ReactNode; title: string; sub: string; on: boolean }[] = [
    {
      page: 'display',
      icon: <IconDisplay size={21} />,
      title: '副屏',
      sub: screenRunning
        ? `正在投屏 · ${state.media.screen?.fps.toFixed(0)} fps`
        : state.media.connected
          ? '未开始投屏'
          : '等待媒体连接',
      on: screenRunning,
    },
    {
      page: 'display',
      icon: <IconCamera size={21} />,
      title: '摄像头',
      sub: camRunning ? '正在预览手机画面' : '未开启',
      on: camRunning,
    },
    {
      page: 'sound',
      icon: <IconSound size={21} />,
      title: '音箱',
      sub: audioRunning ? '本机声音正在送手机' : '未开启',
      on: audioRunning,
    },
    {
      page: 'sound',
      icon: <IconMic size={21} />,
      title: '麦克风',
      sub: micRunning ? '手机麦克风正在回传' : '未开启',
      on: micRunning,
    },
    {
      page: 'transfer',
      icon: <IconTransfer size={21} />,
      title: '互传',
      sub: lsRunning
        ? `端口 ${state.localsend?.port || '—'} · 附近 ${state.localsend?.devices?.length ?? 0} 台`
        : '未开启',
      on: lsRunning,
    },
    {
      page: 'transfer',
      icon: <IconFileUp size={21} />,
      title: '文件',
      sub: '在传输页收发文件',
      on: false,
    },
  ];

  return (
    <>
      {/* ————— 连接主卡 ————— */}
      <Section index={0}>
        <div className="hero" data-live={s.phase === 3}>
          <div className="hero-inner">
            <div className="hero-top">
              <div style={{ minWidth: 0 }}>
                <div className="hero-status">
                  <Badge tone={phaseTone(s.phase as 0)} live={s.phase === 1 || s.phase === 2}>
                    {phaseLabel[s.phase as 0]}
                  </Badge>
                  {s.autoMode && <span className="badge">自动发现</span>}
                  {s.rttMs > 0 && (
                    <span className="badge num" title="往返时延">
                      RTT {s.rttMs.toFixed(0)} ms
                    </span>
                  )}
                </div>
                <h2 className="hero-sentence">{phaseSentence(state)}</h2>
                <div className="hero-meta">
                  {s.peer ? (
                    <>
                      <span className="mono">{s.peer}</span>
                      <span>已连 {upTimeText(s.upMs)}</span>
                      <span>媒体 {state.media.connected ? '已就绪' : '未连接'}</span>
                    </>
                  ) : (
                    <span>手机与本机需在同一 Wi‑Fi；也可插 USB 线走有线通道</span>
                  )}
                </div>
              </div>

              <div className="hero-actions">
                <button
                  className={`btn ${s.phase === 3 ? 'btn-ghost' : 'btn-primary'}`}
                  onClick={() => wifi.toggle(primary.next)}
                  disabled={wifi.busy}
                >
                  {primary.icon}
                  {wifi.busy ? '处理中…' : primary.label}
                </button>
                <button
                  className="btn btn-quiet btn-sm"
                  onClick={() => setManualOpen((v) => !v)}
                  aria-expanded={manualOpen}
                >
                  手动指定 IP
                </button>
              </div>
            </div>

            {manualOpen && (
              <div className="rise" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <input
                  className="field"
                  style={{ flex: 1, minWidth: 0 }}
                  placeholder="手机 IP，例如 192.168.2.69"
                  value={host}
                  autoFocus
                  onChange={(e) => setHost(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') submitManual();
                  }}
                  disabled={busyLink}
                />
                <input
                  className="field num"
                  style={{ width: 78 }}
                  placeholder="端口"
                  value={port}
                  onChange={(e) => setPort(e.target.value.replace(/\D/g, ''))}
                  disabled={busyLink}
                />
                <button
                  className="btn btn-primary"
                  onClick={submitManual}
                  disabled={!host.trim() || busyLink || manual.busy}
                >
                  {manual.busy ? '连接中…' : '连接'}
                </button>
              </div>
            )}
            {(manual.err || wifi.err) && (
              <div style={{ color: 'var(--err)', fontSize: 12.5 }}>{manual.err || wifi.err}</div>
            )}

            <Metrics
              items={[
                { k: '鼠标事件', v: state.counts.mouse },
                { k: '键盘事件', v: state.counts.keyboard },
                { k: '多媒体键', v: state.counts.consumer },
                { k: '被丢弃', v: state.counts.dropped, dim: state.counts.dropped === 0 },
              ]}
            />
          </div>
        </div>
      </Section>

      {/* ————— 权限横幅（macOS）————— */}
      {tp.inputTrusted === false && (
        <Section index={1}>
          <Notice
            tone="err"
            icon={<IconAlert size={18} />}
            title={`本机未授予「${trustLabel}」权限：手机发来的键鼠会被系统丢弃`}
            actions={
              <>
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => manual.run(() => bridge.act('input.openSettings'))}
                >
                  打开系统设置
                </button>
                <button className="btn btn-ghost btn-sm" onClick={recheck} disabled={rechecking}>
                  {rechecking ? '检测中…' : '重新检测'}
                </button>
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => manual.run(() => bridge.act('app.relaunch'))}
                >
                  重启应用
                </button>
              </>
            }
          >
            <div>
              系统设置 → 隐私与安全性 → {trustLabel} → 勾选：
              {procKind === 'app-bundle'
                ? `全能外设（AllPeriph.app，进程 ${procName}）`
                : `${procName}（终端启动的裸可执行文件 —— 授给「终端」App 不算，要勾这个文件名）`}
            </div>
            <div style={{ marginTop: 4 }}>
              授权后必须重启本应用才生效（系统只在进程启动时读一次授权状态）。「重新检测」不会重复弹窗
              —— 系统对已经拒绝过的请求不再询问。
            </div>
            {recheckHint && <div style={{ marginTop: 4 }}>{recheckHint}</div>}
          </Notice>
        </Section>
      )}

      {/* ————— 接入方式 ————— */}
      <Section index={2} title="接入方式" note="手机上报的实时状态；真开关在手机端，这里只读">
        <div className="card rows">
          <Row
            lead={<IconWifi size={18} />}
            leadTone="accent"
            title="无线 · 9511 控制面"
            sub={
              phone.shared
                ? '受控端由另一实例提供：本面板读不到手机上报（不是故障）'
                : phone.reported
                  ? phone.wirelessAvailable
                    ? '手机已选定受控目标：输入经 9511 上行，本机就地注入'
                    : '手机未启用无线出口（未选受控目标，或不在同一局域网）'
                  : '手机未上报（尚未连入本机 9511 控制面）'
            }
            trail={
              <>
                <Badge
                  tone={!phone.reported ? 'idle' : phone.active === 'wireless' ? 'accent' : 'ok'}
                >
                  {phone.shared
                    ? '经另一实例'
                    : !phone.reported
                      ? '未上报'
                      : !phone.wirelessAvailable
                        ? '不可用'
                        : phone.active === 'wireless'
                          ? '当前在用'
                          : '可用'}
                </Badge>
                <span className="faint" style={{ fontSize: 12 }}>
                  本机发现
                </span>
                <Switch
                  on={wifi.on}
                  busy={wifi.busy}
                  onChange={wifi.toggle}
                  label="本机自动发现手机"
                />
              </>
            }
          />

          <Row
            lead={<IconBluetooth size={18} />}
            title="蓝牙 HID"
            sub={
              phone.shared
                ? '受控端由另一实例提供：本面板读不到手机上报（不是故障）'
                : phone.reported
                  ? phone.bluetoothAvailable
                    ? '手机蓝牙已开启：配对后本机免驱识别为鼠标 / 键盘 / 多媒体键'
                    : '手机蓝牙出口不可用（未开启，或未配对）'
                  : '手机未上报（未连入本机 9511；蓝牙链路不经过本机面板）'
            }
            trail={
              <Badge
                tone={!phone.reported ? 'idle' : phone.active === 'bluetooth' ? 'accent' : 'ok'}
              >
                {phone.shared
                  ? '经另一实例'
                  : !phone.reported
                    ? '未上报'
                    : !phone.bluetoothAvailable
                      ? '不可用'
                      : phone.active === 'bluetooth'
                        ? '当前在用'
                        : '可用'}
              </Badge>
            }
          />

          <Row
            lead={<IconUsb size={18} />}
            title="USB 外设"
            sub={
              phone.shared
                ? '受控端由另一实例提供：本面板读不到手机上报（不是故障）'
                : phone.reported
                  ? phone.usbAvailable
                    ? `手机 USB gadget 已就绪：插线即用（HID 键鼠 + UAC2 声卡 + NCM）${
                        tp.usbName ? ` · ${tp.usbName}` : ''
                      }`
                    : '手机 USB 出口不可用（未插线，或手机端「有线（USB）」开关未开）'
                  : !tp.usbDevice
                    ? '未检测到手机（检查 USB 线 / 手机端 USB 开关）'
                    : tp.usbNcm
                      ? `NCM 已挂载${
                          tp.usbSpeed && tp.usbSpeed !== 'unknown' ? ` · ${tp.usbSpeed}` : ''
                        }`
                      : '设备在线，NCM 未挂载（手机端重开「有线（USB）」开关）'
            }
            trail={
              <Badge
                tone={
                  !(phone.reported && (phone.usbAvailable ?? tp.usbDevice))
                    ? 'idle'
                    : phone.active === 'usb'
                      ? 'accent'
                      : 'ok'
                }
              >
                {!(phone.reported && (phone.usbAvailable ?? tp.usbDevice))
                  ? '未就绪'
                  : phone.active === 'usb'
                    ? '当前在用'
                    : '可用'}
              </Badge>
            }
          />
        </div>
      </Section>

      {/* ————— 功能入口 ————— */}
      <Section index={3} title="功能" note="点开是切换页面，不弹窗">
        <div className="tiles">
          {tiles.map((t) => (
            <button key={t.title} className="tile" data-on={t.on} onClick={() => onNavigate(t.page)}>
              <span className="tile-icon">{t.icon}</span>
              <span className="row-main">
                <span className="row-title">{t.title}</span>
                <span className="row-sub dim truncate-1">{t.sub}</span>
              </span>
              <IconChevronRight size={16} className="tile-arrow" />
            </button>
          ))}
        </div>
      </Section>

      {/* ————— 本机 ————— */}
      <Section index={4} title="本机">
        <div className="card rows">
          <Row
            title="宿主平台"
            trail={<span className="mono faint">{PLATFORM_LABEL[platform] ?? platform}</span>}
          />
          <Row
            title="9511 受控端"
            sub={
              ctrl?.listening
                ? '本面板提供 —— 手机「TV 控制」里能看到本机'
                : ctrl?.shared
                  ? '由本机另一个 AllPeriph 实例提供（9511 已被占用，本面板不重复起）'
                  : ctrl?.error
                    ? `未启动：${ctrl.error} —— 手机将无法发现本机`
                    : '状态未知'
            }
            trail={
              <Badge
                tone={ctrl?.listening || ctrl?.shared ? 'ok' : ctrl?.error ? 'err' : 'idle'}
              >
                {ctrl?.listening
                  ? '本机提供'
                  : ctrl?.shared
                    ? '另一实例'
                    : ctrl?.error
                      ? '未启动'
                      : '未知'}
              </Badge>
            }
          />
          {state.tray && (
            <Row
              title="托盘图标"
              sub={state.tray.reason || '关闭面板后仍在托盘运行，点图标唤回'}
              trail={
                <Badge tone={state.tray.active ? 'ok' : state.tray.created ? 'warn' : 'err'}>
                  {state.tray.active ? '正常' : state.tray.created ? '未激活' : '创建失败'}
                </Badge>
              }
            />
          )}
          {platform !== 'windows' && (
            <Row
              title="副屏说明"
              sub="mac / Linux 上本机不挂虚拟显示器：副屏的出路是自装 Sunshine + Moonlight（本面板只做检测与指引）。"
            />
          )}
        </div>
      </Section>
    </>
  );
};
