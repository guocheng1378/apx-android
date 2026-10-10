import React, { useEffect, useState } from 'react';
import { bridge, HostState } from './api/bridge';
import { ConnectionCard } from './components/ConnectionCard';
import { ScreenCard } from './components/ScreenCard';
import { SpeakerCard } from './components/SpeakerCard';
import { MicCard } from './components/MicCard';
import { RemoteInputModal } from './components/RemoteInputModal';
import { SettingsModal } from './components/SettingsModal';
import { FilePanelModal } from './components/FilePanelModal';

/**
 * 主面板 —— 双栏 2×2 布局（连接卡整宽 + 功能卡双栏 + 底部控件）。
 *
 * impeccable / taste-skill 设计原则直接体现在：
 *   ✗ 无 glassmorphism（backdrop-blur）—— 所有卡实色白底
 *   ✗ 无装饰性 sparkline —— 不画 RTT 历史折线
 *   ✗ 无卡套卡 —— 卡内直接布局
 *   ✗ 无 bounce/elastic —— 统一 ease-out-expo
 *   ✗ 无纯黑/纯灰 —— 全部带 tint
 *   ✗ 无灰色 on 彩色底 —— primary button 文字纯白
 *   ✗ 无动态 Tailwind class —— 所有 class 静态（见 bridge.ts mock 模式）
 *   ✓ Instrument Sans 字体（不系统默认）
 *   ✓ 8pt 间距
 *   ✓ 16px 统一圆角
 *   ✓ 轻描边 + 轻阴影（只有 shadow-card / shadow-btn-primary）
 */
export default function App() {
  const [state, setState] = useState<HostState | null>(null);

  // 三个 Modal 开关
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [fileOpen, setFileOpen] = useState(false);
  const [remoteInputOpen, setRemoteInputOpen] = useState(false);
  const [remoteInputHint, setRemoteInputHint] = useState('');

  // —— 400ms 状态推流（C++ tickState）——
  useEffect(() => bridge.subscribeState(s => setState(s)), []);

  // —— 事件监听（C++ postEvent）——
  // 对端（手机/TV）请求本机代输 → 弹 RemoteInputModal
  useEffect(() => bridge.onEvent('on-remote-input-request', (evt: any) => {
    setRemoteInputHint(evt.data?.hint || '');
    setRemoteInputOpen(true);
  }), []);

  const handleManualConnect = async (host: string, port: number) => {
    if (!host) return;
    await bridge.act('session.connectManual', { host, port });
  };

  const handleRemoteSend = async (text: string) => {
    if (!text) { setRemoteInputOpen(false); return; }
    // 0x04 = INPUT_FLAG_COMMIT（整段提交）—— 与手机端 RemoteInputActivity 同口径
    await bridge.act('session.sendInputText', { text, flags: 0x04 });
    await bridge.act('session.sendInputDone');
    setRemoteInputOpen(false);
  };

  if (!state) {
    return (
      <div className="w-screen h-screen flex items-center justify-center bg-bg">
        <div className="data-text text-on-tertiary">等待宿主状态…</div>
      </div>
    );
  }

  return (
    <>
      <div className="w-screen h-screen bg-bg p-4 overflow-auto">
        {/* 顶部 App 徽章（右上角） */}
        <div className="flex justify-end mb-2">
          <div className="badge" data-state="ok">
            <span className="w-[6px] h-[6px] rounded-full bg-state-ok" />
            AllPeriph
          </div>
        </div>

        {/* 连接卡 —— 整宽 */}
        <ConnectionCard state={state} onManualConnect={handleManualConnect} />

        {/* 双栏功能区 —— 与旧面板 kColW / kCol2X 同逻辑 */}
        <div className="mt-[14px] grid grid-cols-2 gap-[14px]">
          <ScreenCard   state={state} />
          <SpeakerCard  state={state} />
          <MicCard      state={state} />
          {/* 第四格（旧面板右下是 USB 卡 —— WebView2 版暂时省掉，BT/USB 状态走设置页） */}
        </div>

        {/* 底部 */}
        <div className="mt-[14px]">
          <FooterWithActions
            state={state}
            onSettings={() => setSettingsOpen(true)}
            onFilePanel={() => setFileOpen(true)}
          />
        </div>
      </div>

      {/* Modal 层 */}
      <SettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} state={state} />
      <FilePanelModal open={fileOpen} onClose={() => setFileOpen(false)} />
      <RemoteInputModal
        open={remoteInputOpen}
        hint={remoteInputHint}
        onClose={() => setRemoteInputOpen(false)}
        onSend={handleRemoteSend}
      />
    </>
  );
}

/** Footer 的设置按钮现在会打开 Modal —— 旧 Footer 组件无此 prop，这里用 inline 包装。 */
function FooterWithActions({ state, onSettings, onFilePanel }: {
  state: HostState;
  onSettings: () => void;
  onFilePanel: () => void;
}) {
  // 直接内联 Footer 结构，加上设置/文件按钮的正确接线
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
        <label className="switch" data-on={autostart} onClick={toggleAutostart}>
          <span className="switch-thumb" />
        </label>
        <span className="body-text text-on-surface">开机自启</span>
      </div>

      <div className="flex items-center gap-2">
        <button className="btn-ghost h-[38px]" onClick={onFilePanel}>文件传输</button>
        <button className="btn-ghost h-[38px]" onClick={() => bridge.act('window.hide')}>隐藏</button>
        <button className="btn-ghost h-[38px]" onClick={onSettings}>设置</button>
        <button className="btn-primary h-[38px]" onClick={() => bridge.act('window.quit')}>退出</button>
      </div>
    </div>
  );
}
