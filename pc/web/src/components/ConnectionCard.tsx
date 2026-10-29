import React, { useState } from 'react';
import { bridge, HostState } from '../api/bridge';
import { StatusBadge } from './StatusBadge';
import { Switch } from './Switch';
import { phaseSentence, upTimeText } from '../utils/status';

interface Props {
  state: HostState;
  onManualConnect: (host: string, port: number) => void;
}

/** transports 里的 USB 三态诊断（C++ refreshUsbStatus 探测，~10s 刷一次） */
interface UsbTransports {
  usbDevice?: boolean;
  usbNcm?: boolean;
  usbHid?: boolean;
  usbName?: string;
  usbSpeed?: string;
  btSupported?: boolean;
  /** macOS「辅助功能」授权：false = 手机发来的输入会被系统静默丢弃 */
  inputTrusted?: boolean;
  /** 实际注入进程的 exe 路径（TCC 按进程/签名归属授权，用户要对上「该勾哪一个」） */
  inputProc?: string;
  /** 手机上报的三种接入模式状态（9511 帧 0x31） */
  phone?: PhoneMode;
  /** 9511 受控端的归属：listening=本面板起的；shared=端口被本机另一个实例占着（它提供） */
  ctrlServer?: CtrlServerOwner;
}

/**
 * 9511 受控端由谁提供。
 *
 * 为什么值得单独显示：面板与 `apxhost serve` / `apxdesktop` 都会起 9511，而端口独占 ——
 * 第二个起来的实例 bind 必然失败。以前这种情况只在日志里留一句告警，用户看面板只会觉得
 * 「手机发现不了本机」，而真实原因是**另一个实例已经提供了受控端**（功能其实是好的）。
 * 见 ROADMAP §2.4（v223.7 已交付：apxpc::net::tcp_probe）。
 */
interface CtrlServerOwner {
  listening?: boolean;
  shared?: boolean;
  connected?: boolean;
  error?: string;
}

/**
 * 手机上报的「无线 / 蓝牙 / USB」三模式状态。
 * 位布局的唯一真源是 pc/host/include/apxpc/wireless/phone_mode.hpp（用例钉住，与 Android 侧对拍）。
 * reported=false 表示手机还没连进本机 9511 —— 这时一律显示「未上报」，不猜。
 */
interface PhoneMode {
  reported?: boolean;
  connected?: boolean;
  frames?: number;
  /** 9511 由本机另一个实例提供：本面板收不到手机上报（modeStatus=0），不是故障 */
  shared?: boolean;
  wirelessAvailable?: boolean;
  bluetoothAvailable?: boolean;
  usbAvailable?: boolean;
  /** "wireless" | "bluetooth" | "usb" | "none" */
  active?: string;
}

/**
 * 一行接入方式状态：圆点 + 名称 + 状态词 + 说明。
 * 灰=不可用/未上报、绿=可用但没在用、蓝=当前在用 —— 与状态徽章同一套色，不再有「拨了没反应」的开关。
 */
const ModeRow: React.FC<{
  name: string;
  reported: boolean;
  available?: boolean;
  active?: boolean;
  /** 受控端由另一实例提供：本面板读不到手机上报，如实说，别误读成「未上报/坏了」 */
  shared?: boolean;
  note: string;
  sub?: string;
  extra?: React.ReactNode;
}> = ({ name, reported, available, active, shared, note, sub, extra }) => {
  const usable = reported && available;
  const dot = !usable ? 'bg-state-idle' : active ? 'bg-primary' : 'bg-state-ok';
  const tag = shared ? '经另一实例' : !reported ? '未上报' : !available ? '不可用' : active ? '当前在用' : '可用';
  const tagColor = !usable ? 'text-on-tertiary' : active ? 'text-primary' : 'text-state-ok';
  return (
    <div className="pt-3 border-t border-stroke flex items-start gap-3">
      <span className={`w-[8px] h-[8px] rounded-full mt-[6px] shrink-0 ${dot}`} />
      <div className="flex flex-col gap-[2px] min-w-0">
        <div className="flex items-center gap-2">
          <span className="body-text text-on-surface">{name}</span>
          <span className={`data-text ${tagColor}`}>{tag}</span>
        </div>
        <div className="data-text text-on-tertiary">{note}</div>
        {sub && <div className="data-text text-on-tertiary">{sub}</div>}
      </div>
      {extra && <div className="ml-auto shrink-0 flex flex-col items-end gap-1">{extra}</div>}
    </div>
  );
};

/**
 * 连接卡 —— 整宽卡，第一屏最重要的信息。
 * impeccable anti-pattern：
 *   ✗ 不要卡套卡（本卡是顶层卡，内部直接布局，不嵌 card）
 *   ✗ 不要装饰性 sparkline（旧面板上的 RTT 历史图在 Web 版删掉 —— convey nothing meaningful）
 *   ✗ 不要玻璃拟态（backdrop-blur）
 *
 * 接入方式（issue#47：三个开关改成真实状态）：
 * 老面板连接卡有「无线 / 蓝牙 / USB」三个开关，但**只有无线是真开关**（启停 9511 会话），
 * 蓝牙与 USB 的真开关在手机端（配对 / root 配 gadget），PC 只是被动 host —— 摆两个拨了
 * 没反应的开关，用户只能认定「坏了」。现在改成：手机把三条出口的**可用性 + 当前在用哪条**
 * 用 0x31 帧报上来（见 utils 上方 PhoneMode 注释），这里照实显示；手机没上报就说没上报。
 *
 * 无线那一行右侧仍保留「本机发现」开关（真开关）：开 = session.startAuto（自动发现手机），
 * 关 = session.disconnect。状态由 400ms tick 的 link.phase 驱动：未启用(0)/手动失败(4) 视为关。
 */
export const ConnectionCard: React.FC<Props> = ({ state, onManualConnect }) => {
  const [host, setHost] = useState('');
  const [port, setPort] = useState('9511');
  // 辅助功能「重新检测」：以前用户在系统设置改完只能干等 400ms 轮询 + 重启面板，
  // 没有任何主动反馈入口。act 会再弹一次系统引导（仍未授权时），并回实时 preflight。
  const [rechecking, setRechecking] = useState(false);
  const [recheckHint, setRecheckHint] = useState('');
  const recheck = async () => {
    setRechecking(true);
    try {
      const r: any = await bridge.act('input.recheck');
      if (r?.ok && r?.data?.trusted === false)
        setRecheckHint('仍未授权：勾选的条目要和上面显示的进程对上（.app 双击启动 = AllPeriph；终端启动 = 终端 App）');
      else setRecheckHint('');
    } catch { /* 宿主未就绪：横幅会随 400ms 轮询自己刷新 */ }
    setRechecking(false);
  };
  const s = state.link;
  const tp = (state.transports ?? {}) as UsbTransports;
  const phone = tp.phone ?? {};
  const ctrl = tp.ctrlServer;
  const usbSpeedText = tp.usbSpeed && tp.usbSpeed !== 'unknown' ? ` · ${tp.usbSpeed}` : '';
  const usbLine = !tp.usbDevice
    ? '未检测到手机（检查 USB 线 / 手机端 USB 开关）'
    : tp.usbNcm
      ? `NCM 已挂载${usbSpeedText}`
      : '设备在线，NCM 未挂载（手机端重开「有线（USB）」开关）';
  // 无线开关状态：phase 0=未启用 4=手动连接失败 视为关；发现/连接/已连接 视为开
  const wifiOn = s.phase !== 0 && s.phase !== 4;

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

      {/* macOS 注入权限：没授权时手机发来的键鼠会被系统静默丢弃 ——
          用户看到的现象就是「连上了、按什么都没反应」，所以这里必须说出来。 */}
      {tp.inputTrusted === false && (
        <div className="mx-5 mb-3 px-3 py-2 rounded-chip bg-state-error/10">
          <div className="body-text text-state-error">
            本机未授予「辅助功能」权限：手机发来的键鼠会被系统丢弃
          </div>
          <div className="data-text text-on-tertiary mt-1">
            系统设置 → 隐私与安全性 → 辅助功能 → 勾选与上面进程对应的条目
            {tp.inputProc ? `（本面板：${tp.inputProc.split(/[\\/]/).pop()}）` : ''}
            ，勾选后 1 秒内横幅会自动消失（检测是实时的，不用重启面板）。
          </div>
          <div className="data-text text-on-tertiary mt-1">
            包里有三个各自签名的副本（AllPeriph.app / apxdesktop / apxhost），勾错对象权限不生效；
            从终端启动时，权限要授给终端 App 本身。「录屏」「麦克风」是另一道权限，与此不通。
          </div>
          <div className="flex items-center gap-2 mt-2">
            <button className="btn-ghost h-[30px]" disabled={rechecking} onClick={recheck}>
              {rechecking ? '检测中…' : '重新检测 / 弹出授权引导'}
            </button>
            {recheckHint && <span className="data-text text-state-error">{recheckHint}</span>}
          </div>
        </div>
      )}

      {/* 三种接入方式（真实状态，不是开关）：数据来自手机上报的 0x31 帧 */}
      <div className="flex flex-col px-5 pb-4 -mt-1">
        <ModeRow
          name="无线（9511 控制面）"
          reported={!!phone.reported}
          available={phone.wirelessAvailable}
          active={phone.active === 'wireless'}
          note={
            phone.shared
              ? '受控端由另一实例提供：本面板读不到手机上报（不是故障）'
              : phone.reported
              ? phone.wirelessAvailable
                ? '手机已选定受控目标：输入经 9511 上行，本机就地注入'
                : '手机未启用无线出口（未选受控目标，或不在同一局域网）'
              : '手机未上报（尚未连入本机 9511 控制面）'
          }
          extra={
            <>
              <span className="data-text text-on-tertiary">本机发现</span>
              <Switch
                on={wifiOn}
                onChange={on => bridge.act(on ? 'session.startAuto' : 'session.disconnect')}
              />
            </>
          }
        />
        <ModeRow
          name="蓝牙 HID"
          reported={!!phone.reported}
          available={phone.bluetoothAvailable}
          active={phone.active === 'bluetooth'}
          note={
            phone.shared
              ? '受控端由另一实例提供：本面板读不到手机上报（不是故障）'
              : phone.reported
              ? phone.bluetoothAvailable
                ? '手机蓝牙已开启：配对后本机免驱识别为鼠标 / 键盘 / 多媒体键'
                : '手机蓝牙出口不可用（未开启，或未配对）'
              : '手机未上报（未连入本机 9511；蓝牙链路不经过本机面板）'
          }
          sub={
            tp.btSupported
              ? '本机支持蓝牙 · 真开关在手机端，面板只读'
              : '真开关在手机端（蓝牙配对），面板只读'
          }
        />
        <ModeRow
          name="USB 外设"
          reported={!!phone.reported}
          available={phone.usbAvailable ?? tp.usbDevice}
          active={phone.active === 'usb'}
          note={
            phone.shared
              ? '受控端由另一实例提供：本面板读不到手机上报（不是故障）'
              : phone.reported
              ? phone.usbAvailable
                ? '手机 USB gadget 已就绪：插线即用（HID 键鼠 + UAC2 声卡 + NCM）'
                : '手机 USB 出口不可用（未插线，或手机端「有线（USB）」开关未开）'
              : usbLine
          }
          sub={tp.usbDevice && tp.usbName ? `${tp.usbName}${usbSpeedText}` : undefined}
        />
        {/* 受控端归属：三种事实说三种话，不把「别人在提供服务」说成故障 */}
        {ctrl && (ctrl.listening || ctrl.shared || ctrl.error) && (
          <div className="pt-3 border-t border-stroke">
            <div className="data-text text-on-tertiary">
              {ctrl.listening
                ? '受控端：本面板提供（9511）—— 手机「TV 控制」里能看到本机'
                : ctrl.shared
                  ? '受控端：由本机另一个 AllPeriph 实例提供（9511 已被占用，本面板不重复起）'
                  : `受控端未启动：${ctrl.error || '未知原因'} —— 手机将无法发现本机`}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
