import React, { useEffect, useState } from 'react';
import { bridge, HostState } from './api/bridge';
import { ConnectionCard } from './components/ConnectionCard';
import { ScreenCard } from './components/ScreenCard';
import { SpeakerCard } from './components/SpeakerCard';
import { MicCard } from './components/MicCard';
import { Footer } from './components/Footer';

/**
 * 主面板 —— 双栏 2×2 布局（连接卡整宽 + 三功能卡双栏 + 底部控件）。
 * 初始 960×808 窗口（与旧 Win32 面板同尺寸），由 WebView2 窗口创建时决定。
 *
 * impeccable / taste-skill 设计原则直接体现在：
 *   ✗ 无 glassmorphism（backdrop-blur）—— 所有卡实色白底
 *   ✗ 无装饰性 sparkline —— 不画 RTT 历史折线
 *   ✗ 无卡套卡 —— 卡内直接布局
 *   ✗ 无 bounce/elastic —— 统一 ease-out-expo
 *   ✗ 无纯黑/纯灰 —— 全部带 tint
 *   ✗ 无灰色 on 彩色底 —— primary button 文字纯白
 *   ✓ Instrument Sans 字体（不系统默认）
 *   ✓ 8pt 间距
 *   ✓ 16px 统一圆角
 *   ✓ 轻描边 + 轻阴影（只有 shadow-card / shadow-btn-primary）
 */
export default function App() {
  const [state, setState] = useState<HostState | null>(null);

  useEffect(() => bridge.subscribeState(s => setState(s)), []);

  if (!state) {
    return (
      <div className="w-screen h-screen flex items-center justify-center bg-bg">
        <div className="data-text text-on-tertiary">等待宿主状态…</div>
      </div>
    );
  }

  const handleManualConnect = async (host: string, port: number) => {
    if (!host) return;
    await bridge.act('session.connectManual', { host, port });
  };

  return (
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
        <Footer state={state} />
      </div>

      {/* 埋点（impeccable anti-pattern: 不要装饰性 sparkline —— 旧面板 repaintKey 指纹不再画成折线图） */}
    </div>
  );
}
