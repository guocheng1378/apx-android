import React, { useState } from 'react';
import { bridge } from '../api/bridge';

/**
 * 文件面板 Modal —— 显示收到的文件列表 + 发送文件入口。
 * 替代旧版 file_panel_win32.cpp 原生窗口。
 * 当前简化：只做「发送文件」按钮（调 C++ open-file → 用户选文件 → C++ FileSender 发）。
 * 收到的文件暂时走托盘气泡（和旧版同口径）。
 */
export const FilePanelModal: React.FC<{ open: boolean; onClose: () => void }> = ({ open, onClose }) => {
  const [busy, setBusy] = useState(false);
  const [lastFile, setLastFile] = useState<string | null>(null);

  if (!open) return null;

  const sendFile = async () => {
    setBusy(true);
    try {
      const r = await bridge.openFile('全部文件|*.*');
      if (r.ok && r.path) {
        setLastFile(r.path);
        // C++ 端 FileSender 发送逻辑暂未在 web_panel 里暴露（需要 FileSender 桥接）
        // 先展示选中的文件路径，完整实现在后续 PR 里补
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 animate-fade-in" onClick={onClose}>
      <div className="card card-inner w-[440px] animate-slide-up" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <span className="section-title">文件传输</span>
          <button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose}>✕</button>
        </div>

        <button className="btn-primary w-full h-[44px]" onClick={sendFile} disabled={busy}>
          {busy ? '请稍候…' : '发送文件到手机'}
        </button>

        {lastFile && (
          <div className="mt-3 data-text truncate" title={lastFile}>选中：{lastFile}</div>
        )}

        <div className="mt-4 caption-text text-on-tertiary">
          收到的文件会自动落盘到应用私有目录（无需存储权限），托盘气泡提示。
        </div>
      </div>
    </div>
  );
};
