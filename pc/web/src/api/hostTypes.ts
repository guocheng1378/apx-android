import { HostState } from './bridge';

/**
 * `state.transports` 里的非标量字段（C++ buildState 逐字段下发）。
 *
 * 放在独立文件里而不是各组件内联：概览页与设置页都要读 USB / 权限 / 9511 归属，
 * 各写一份 interface 迟早漂移 —— 字段名是与后端的契约，只允许有一个真源。
 */
export interface UsbTransports {
  usbDevice?: boolean;
  usbNcm?: boolean;
  usbHid?: boolean;
  usbName?: string;
  usbSpeed?: string;
  btSupported?: boolean;
  /** macOS 输入注入授权：false = 手机发来的输入会被系统静默丢弃 */
  inputTrusted?: boolean;
  /** 实际注入进程的 exe 路径（TCC 按进程归属授权） */
  inputProc?: string;
  /** 权限面板在系统设置里的显示名（macOS 26+ = 设备控制与数据访问） */
  inputTrustLabel?: string;
  /** 注入进程身份：app-bundle / bare-binary */
  inputProcKind?: string;
  phone?: PhoneMode;
  ctrlServer?: CtrlServerOwner;
}

/** 手机上报的三模式状态（9511 帧 0x31） */
export interface PhoneMode {
  reported?: boolean;
  connected?: boolean;
  frames?: number;
  shared?: boolean;
  wirelessAvailable?: boolean;
  bluetoothAvailable?: boolean;
  usbAvailable?: boolean;
  /** "wireless" | "bluetooth" | "usb" | "none" */
  active?: string;
}

/** 9511 受控端归属：listening=本面板提供；shared=被本机另一实例占着 */
export interface CtrlServerOwner {
  listening?: boolean;
  shared?: boolean;
  connected?: boolean;
  error?: string;
}

export function transportsOf(state: HostState | null): UsbTransports {
  return ((state?.transports ?? {}) as UsbTransports) ?? {};
}
