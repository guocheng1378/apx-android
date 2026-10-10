import React, { useEffect, useState } from 'react';

interface Props { open: boolean; hint: string; onClose: () => void; onSend: (text: string) => void; }

/**
 * 远程输入浮层 —— 对端（手机/TV）请求本机输入文本时弹出。
 * 语义：对端 UI 有输入框获焦，想让 PC 用户代输（如搜索框）。
 * 替代旧版 RemoteInputOverlay 原生窗口。
 */
export const RemoteInputModal: React.FC<Props> = ({ open, hint, onClose, onSend }) => {
  const [text, setText] = useState('');
  useEffect(() => { if (open) setText(''); }, [open]);
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 animate-fade-in"
         onClick={onClose}>
      <div className="card card-inner w-[420px] animate-slide-up" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-3">
          <span className="section-title">远程输入</span>
          <button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose}>✕</button>
        </div>
        {hint && <div className="caption-text text-on-variant mb-2">{hint}</div>}
        <textarea
          className="input-field w-full h-[120px] resize-none"
          placeholder="在此输入，回车发送"
          value={text}
          onChange={e => setText(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onSend(text); } }}
          autoFocus
        />
        <div className="mt-3 flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>取消</button>
          <button className="btn-primary" onClick={() => onSend(text)}>发送</button>
        </div>
      </div>
    </div>
  );
};
