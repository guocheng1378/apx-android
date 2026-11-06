import React from 'react';
import { IconKeyboard, IconSend, IconX } from '../ui/icons';

interface Props {
  hint: string;
  onClose: () => void;
  onSend: (text: string) => void;
}

/**
 * 远程输入 —— 手机/TV 侧有输入框获焦，请求本机代输（搜索框、地址栏这种）。
 *
 * 改成右下角浮层（.dock）而不是整屏遮罩弹窗：
 *   ① 代输时用户往往还要对照本机别的内容，遮罩把这个可能抹掉了；
 *   ② 面板里的其它开关不该因为一个待输入请求全部变灰；
 *   ③ 手机端同类请求是「抽屉式挂起」，两端行为一致才好理解。
 *
 * 键盘：Enter 发送 / Shift+Enter 换行 / Esc 关闭。
 */
export const RemoteInputDock: React.FC<Props> = ({ hint, onClose, onSend }) => {
  const [text, setText] = React.useState('');
  const areaRef = React.useRef<HTMLTextAreaElement | null>(null);

  React.useEffect(() => {
    const t = window.setTimeout(() => areaRef.current?.focus(), 120);
    return () => window.clearTimeout(t);
  }, []);

  const submit = () => {
    if (!text.trim()) {
      onClose();
      return;
    }
    onSend(text);
  };

  return (
    <div className="dock" role="dialog" aria-label="远程输入请求">
      <div className="dock-head">
        <span className="row-lead accent" style={{ width: 30, height: 30, borderRadius: 10 }}>
          <IconKeyboard size={16} />
        </span>
        <span className="row-main">
          <span className="row-title" style={{ fontSize: 13.5 }}>
            手机请求代输
          </span>
          <span className="row-sub dim truncate-1">{hint || '对端输入框已获焦，输入后回车发送'}</span>
        </span>
        <button className="icon-btn" onClick={onClose} aria-label="关闭">
          <IconX size={16} />
        </button>
      </div>

      <textarea
        ref={areaRef}
        className="field"
        rows={3}
        placeholder="在这里打字，回车发送（Shift+回车换行）"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
          if (e.key === 'Escape') onClose();
        }}
      />

      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
        <button className="btn btn-quiet btn-sm" onClick={onClose}>
          忽略
        </button>
        <button className="btn btn-primary btn-sm" onClick={submit} disabled={!text.trim()}>
          <IconSend size={14} />
          发送
        </button>
      </div>
    </div>
  );
};
