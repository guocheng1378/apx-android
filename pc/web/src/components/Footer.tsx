import React, { useState, useEffect } from 'react';
import { bridge, HostState } from '../api/bridge';
import { Switch } from './Switch';

interface Props { state: HostState }

/**
 * 底部控件：开机自启 + 设置 + 隐藏 + 退出。
 * 两个按钮左对齐，退出靠右——操作风险递减（设置 → 隐藏 → 退出）。
 */
export const Footer: React.FC<Props> = ({ state }) => {
  const [autostart, setAutostart] = useState(false);
  React.useEffect(() => { setAutostart(!!state.config.autostart); }, [state.config.autostart]);

  const toggleAutostart = async () => {
    const next = !autostart;
    setAutostart(next);
    await bridge.act('tray.setAutostart', { enable: next });
  };

  return (
    <div className="flex items-center justify-between gap-3 pt-2">
      <div className="flex items-center gap-3">
        <Switch on={autostart} onChange={toggleAutostart} />
        <span className="body-text text-on-surface">开机自启</span>
      </div>

      <div className="flex items-center gap-2">
        <button className="btn-ghost h-[38px]" onClick={() => bridge.act('window.hide')}>
          隐藏
        </button>
        <button className="btn-ghost h-[38px]" onClick={() => { /* 设置面板待定 */ }}>
          设置
        </button>
        <button className="btn-primary h-[38px]" onClick={() => bridge.act('window.quit')}>
          退出
        </button>
      </div>
    </div>
  );
};
