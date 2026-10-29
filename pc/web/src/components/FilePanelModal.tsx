import React, { useCallback, useEffect, useState } from 'react';
import { bridge } from '../api/bridge';

/**
 * 文件面板 Modal —— 收到的文件列表 + 发送文件入口（替代旧版 file_panel_win32.cpp）。
 *
 * 链路（见 web_panel.cpp handleAct 的 file.* 一族）：
 *   发：open-file 选文件 → act file.send {path} → C++ FileSender 后台发送（TCP 9512）
 *   收：C++ FileReceiver 收满 → postEvent on-file-received → 本组件刷新列表
 *   删：act file.delete {path} —— C++ 侧白名单限制在「下载\AllPeriph」落盘目录内，
 *       前端做两段式确认（先点「删除」再点「确认删除？」）。
 */
interface RecvFile { name: string; path: string; size: number; }

const fmtSize = (n: number) => {
  if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
  if (n >= 1024) return (n / 1024).toFixed(1) + ' KB';
  return n + ' B';
};

const fileNameOf = (p: string) => p.split(/[\\/]/).pop() ?? p;

export const FilePanelModal: React.FC<{ open: boolean; onClose: () => void }> = ({ open, onClose }) => {
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const [files, setFiles] = useState<RecvFile[]>([]);
  const [confirmDel, setConfirmDel] = useState<string | null>(null);

  // 浏览器面板（bridge.mode === 'http'）：页面拿不到系统文件对话框（openFile 会被桥层拒绝，
  // 以前点「发送文件到手机」只会看到「不支持」），改用页面自带的 <input type=file> ——
  // 与互传卡同款 label[for] 方式（label 是浏览器自身的默认行为，不经过脚本，
  // 没有手势时序问题），字节走 /api/upload?target=file，宿主落盘后入队 9512 发送。
  const browserMode = bridge.mode === 'http';
  const fileRef = React.useRef<HTMLInputElement | null>(null);
  const fileInputId = 'apx-filepanel-file';

  const refresh = useCallback(async () => {
    try {
      const r: any = await bridge.act('file.list');
      if (r?.ok && Array.isArray(r.data)) setFiles(r.data);
    } catch { /* 宿主未就绪时静默，下个事件再刷 */ }
  }, []);

  // 打开即拉一次列表；收到新文件（C++ 推事件）时自动刷新
  useEffect(() => { if (open) { refresh(); setStatus(''); setConfirmDel(null); } }, [open, refresh]);

  // C++ 推来的两类事件：收到新文件 / 托盘入口发完文件
  useEffect(() => {
    const offs = [
      bridge.onEvent('on-file-received', (evt: any) => {
        refresh();
        setStatus(`已收到：${fileNameOf(evt.data?.path || '')}`);
      }),
      bridge.onEvent('on-file-send-done', (evt: any) => {
        setSending(null);
        setStatus(evt.data?.ok ? '已发送到对端' : `发送失败：${evt.data?.error || '未知原因'}`);
        refresh();
      }),
    ];
    return () => { offs.forEach(f => f()); };
  }, [refresh]);

  if (!open) return null;

  // 两条通道的响应语义不同：
  //   webview：act-response 在后台线程发完后回 —— await 到了就是真的发完了。
  //   http（浏览器面板）：act-response 先回「已入队」，结果走 on-file-send-done 事件
  //   （HTTP 连接不能挂着等大文件传完）—— 这里只改状态，最终状态等事件。
  const httpMode = bridge.mode === 'http';
  const doSend = async (path: string) => {
    setSending(path);
    setStatus(`正在发送：${fileNameOf(path)}…`);
    try {
      await bridge.act('file.send', { path });
      if (!httpMode) setStatus(`已发送：${fileNameOf(path)}`);
    } catch (e: any) {
      setStatus(`发送失败：${e?.message || e}`);
      setSending(null);
      return;
    }
    if (!httpMode) setSending(null);   // http：等 on-file-send-done 再清
  };

  const sendFile = async () => {
    setBusy(true);
    setStatus('');
    try {
      const r: any = await bridge.openFile('全部文件|*.*');
      if (r.ok && r.path) await doSend(r.path);
    } catch (e: any) {
      setStatus(`发送失败：${e?.message || e}`);
    } finally {
      setBusy(false);
    }
  };

  // 浏览器模式选完文件：逐个上传（宿主落盘后入队 9512 发送队列），全部传完算一步；
  // 发送结果由 on-file-send-done 事件回填（本组件已在听）。
  const onFilesPicked = async (ev: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(ev.target.files ?? []);
    ev.target.value = '';        // 清空：同一个文件连选两次也要能触发 change
    if (picked.length === 0 || busy || sending) return;
    setBusy(true);
    let done = 0;
    let warned = '';
    for (const f of picked) {
      try {
        const r: any = await bridge.uploadFilePhone(f);
        done++;
        setStatus(`已上传 ${done}/${picked.length}，正在发送到手机…`);
        if (r?.warn) warned = r.warn;
      } catch (e: any) {
        setStatus(`发送失败：${e?.message || `「${f.name}」上传失败`}`);
        setBusy(false);
        return;
      }
    }
    setBusy(false);
    setStatus(warned || `已上传 ${picked.length} 个文件，正在发送到手机…`);
  };

  const removeFile = async (f: RecvFile) => {
    // 两段式确认，防误删
    if (confirmDel !== f.path) { setConfirmDel(f.path); return; }
    try {
      await bridge.act('file.delete', { path: f.path });
      setStatus(`已删除：${f.name}`);
      refresh();
    } catch (e: any) {
      setStatus(`删除失败：${e?.message || e}`);
    }
    setConfirmDel(null);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 animate-fade-in" onClick={onClose}>
      <div className="card card-inner w-[480px] max-h-[72vh] flex flex-col animate-slide-up" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <span className="section-title">文件传输</span>
          <button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose}>✕</button>
        </div>

        {browserMode ? (
          /* label[for]：浏览器自身的默认行为打开选择器（不经过脚本、没有手势时序问题） */
          <label
            htmlFor={fileInputId}
            className="btn-primary w-full h-[44px] flex items-center justify-center cursor-pointer select-none"
          >
            {sending ? '发送中…' : busy ? '请稍候…' : '发送文件到手机'}
          </label>
        ) : (
          <button className="btn-primary w-full h-[44px]" onClick={sendFile} disabled={busy || !!sending}>
            {sending ? '发送中…' : busy ? '请稍候…' : '发送文件到手机'}
          </button>
        )}
        <input ref={fileRef} id={fileInputId} type="file" multiple className="sr-only" onChange={onFilesPicked} />

        {status && <div className="mt-3 data-text text-on-surface break-all">{status}</div>}

        <div className="mt-4 mb-1 caption-text text-on-tertiary">收到的文件（下载\AllPeriph）</div>
        <div className="flex-1 overflow-auto">
          {files.length === 0 && (
            <div className="caption-text text-on-tertiary py-2">还没有收到过文件</div>
          )}
          {files.map(f => (
            <div key={f.path} className="flex items-center gap-2 py-2 border-b border-tint last:border-b-0">
              <div className="flex-1 min-w-0">
                <div className="data-text text-on-surface truncate" title={f.path}>{f.name}</div>
                <div className="caption-text text-on-tertiary">{fmtSize(f.size)}</div>
              </div>
              <button
                className="btn-ghost h-[30px]"
                disabled={!!sending}
                onClick={() => doSend(f.path)}
              >发回手机</button>
              <button
                className="btn-ghost h-[30px]"
                disabled={!!sending}
                onClick={() => removeFile(f)}
              >{confirmDel === f.path ? '确认删除？' : '删除'}</button>
            </div>
          ))}
        </div>

        <div className="mt-3 caption-text text-on-tertiary">
          发送走 TCP 9512 直连（需与手机同一 Wi‑Fi）；删除仅限本目录内的文件。
        </div>
      </div>
    </div>
  );
};