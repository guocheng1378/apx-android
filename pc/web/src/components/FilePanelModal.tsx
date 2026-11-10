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

/** 手机文件条目（on-phone-files 事件载荷，0x45 应答）：isDir=true 时 size 无意义 */
interface PhoneFileEntry { name: string; isDir: boolean; size: number; }

/** 0x45/0x47 的 err 取值 → 中文文案（与 Android ApxFrame.PHONEFILE_ERR_* 对拍） */
const phoneFileErrText = (err: number, path: string) => {
  const name = path ? `「${fileNameOf(path)}」` : '';
  switch (err) {
    case 1: return '手机还没授权：请在手机文件面板里点「允许电脑浏览手机文件」';
    case 2: return `${name}不存在（可能已被删）`;
    case 3: return `${name}读取失败`;
    case 4: return `${name}发送失败（连接中断？）`;
    default: return `操作失败（错误码 ${err}）`;
  }
};

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

  // —— 浏览手机文件（0x44–0x47，2026-11-11，能力位 bit3）——
  // 面包屑 + 条目列表：tap 文件夹 → phonefile.list 进子目录；tap 文件 → phonefile.pull
  // 取回（文件本体经 9512 推回，走「收到的文件」列表）。目录应答/失败回执都是**事件**
  // （act 只回「已发出」），所以当前目录与请求路径要对着事件里的 path 回显匹配。
  const [phonePath, setPhonePath] = useState('');       // 当前浏览的目录；'' = 根入口
  const [phoneEntries, setPhoneEntries] = useState<PhoneFileEntry[]>([]);
  const [phoneBusy, setPhoneBusy] = useState(false);    // 等目录应答中
  const [phonePull, setPhonePull] = useState<string | null>(null); // 正在取回的文件名
  const [phoneErr, setPhoneErr] = useState('');         // 最近一次失败（0x45 err!=0 或 0x47）

  /** 公共外部存储根（Android 惯例路径；子目录路径由对端拼出来再请求） */
  const STORAGE_ROOT = '/storage/emulated/0';
  /** 手机路径拼接：根入口条目（「内部存储」）= 存储根本身；其余按目录层级往下拼 */
  const childPathOf = (cur: string, name: string) =>
    cur === '' ? (name === '内部存储' ? STORAGE_ROOT : STORAGE_ROOT + '/' + name)
               : cur + '/' + name;

  const phonePathRef = React.useRef('');

  /** 进入/刷新指定目录：act 只表示「请求已发出」，真正的列表走 on-phone-files 事件 */
  const browsePhone = useCallback(async (dir: string) => {
    phonePathRef.current = dir;
    setPhonePath(dir);
    setPhoneBusy(true);
    setPhoneErr('');
    try {
      await bridge.act('phonefile.list', { path: dir });
    } catch (e: any) {
      setPhoneBusy(false);
      setPhoneErr(e?.message || String(e));
    }
  }, []);

  // 打开面板时若从没逛过手机目录，自动浏览一次根入口（失败也只提示，不循环重试）
  useEffect(() => {
    if (open && phonePathRef.current === '' && phoneEntries.length === 0 && !phoneBusy) browsePhone('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, browsePhone]);

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
        setPhonePull(null);   // 从手机取回的文件也落在这 —— 到达即结束「取回中」
        setStatus(`已收到：${fileNameOf(evt.data?.path || '')}`);
      }),
      bridge.onEvent('on-file-send-done', (evt: any) => {
        setSending(null);
        setStatus(evt.data?.ok ? '已发送到对端' : `发送失败：${evt.data?.error || '未知原因'}`);
        refresh();
      }),
      // 目录应答（0x45）：path 与当前请求对上才算这一轮的结果，过期应答直接丢
      bridge.onEvent('on-phone-files', (evt: any) => {
        const d = evt.data ?? {};
        if (d.path !== phonePathRef.current) return;
        setPhoneBusy(false);
        const err = d.err ?? 0;
        if (err !== 0) {
          setPhoneEntries([]);
          setPhoneErr(phoneFileErrText(err, d.path || ''));
          return;
        }
        setPhoneEntries(Array.isArray(d.entries) ? d.entries : []);
        setPhoneErr('');
      }),
      // 取文件失败回执（0x47）：文件本体没推回来时才有 —— 按钮还原并提示原因
      bridge.onEvent('on-phone-files-fail', (evt: any) => {
        const d = evt.data ?? {};
        setPhonePull(null);
        setPhoneErr(phoneFileErrText(d.err ?? -1, d.path || ''));
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
          <button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose} title="关闭" aria-label="关闭">✕</button>
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

        {/* —— 浏览手机文件（0x44–0x47，能力位 bit3）：面包屑 + 条目列表 —— */}
        <div className="mt-4 mb-1 caption-text text-on-tertiary">浏览手机文件</div>
        {/* 面包屑：手机路径映射成「内部存储 / Download / …」，tap 任意一段回跳 */}
        <div className="flex flex-wrap items-center gap-1 caption-text text-on-tertiary">
          <button className="hover:text-on-surface" onClick={() => browsePhone('')}>手机存储</button>
          {(() => {
            // 展示段：根 = [内部存储]；子目录 = 内部存储 + 根之后的各段
            const segs = phonePath === STORAGE_ROOT
              ? ['内部存储']
              : phonePath.startsWith(STORAGE_ROOT + '/')
                ? ['内部存储', ...phonePath.slice(STORAGE_ROOT.length + 1).split('/').filter(Boolean)]
                : [];
            return segs.map((seg, i) => {
              const target = i === 0 ? STORAGE_ROOT : STORAGE_ROOT + '/' + segs.slice(1, i + 1).join('/');
              return (
                <React.Fragment key={i}>
                  <span>›</span>
                  <button
                    className={i === segs.length - 1 ? 'text-on-surface' : 'hover:text-on-surface'}
                    onClick={() => browsePhone(target)}
                    disabled={i === segs.length - 1}
                  >{seg}</button>
                </React.Fragment>
              );
            });
          })()}
        </div>
        {phoneErr && <div className="mt-2 data-text break-all" style={{ color: 'var(--err)' }}>{phoneErr}</div>}
        <div className="mt-1 flex-1 min-h-[80px] overflow-auto">
          {phoneBusy && <div className="caption-text text-on-tertiary py-2">正在读取手机目录…</div>}
          {!phoneBusy && !phoneErr && phoneEntries.length === 0 && (
            <div className="caption-text text-on-tertiary py-2">这个目录是空的</div>
          )}
          {phoneEntries.map(e => {
            const full = childPathOf(phonePath, e.name);
            return e.isDir ? (
              <button
                key={'d:' + e.name}
                className="w-full flex items-center gap-2 py-2 border-b border-tint last:border-b-0 hover:opacity-80 text-left"
                disabled={phoneBusy}
                onClick={() => browsePhone(full)}
              >
                <span className="data-text text-on-surface">📁 {e.name}</span>
              </button>
            ) : (
              <div key={'f:' + e.name} className="flex items-center gap-2 py-2 border-b border-tint last:border-b-0">
                <div className="flex-1 min-w-0">
                  <div className="data-text text-on-surface truncate" title={e.name}>{e.name}</div>
                  <div className="caption-text text-on-tertiary">{fmtSize(e.size)}</div>
                </div>
                <button
                  className="btn-ghost h-[30px]"
                  disabled={!!phonePull}
                  onClick={() => { setPhonePull(e.name); setPhoneErr('');
                    bridge.act('phonefile.pull', { path: full })
                      .catch((err: any) => { setPhonePull(null); setPhoneErr(err?.message || String(err)); });
                  }}
                >{phonePull === e.name ? '取回中…' : '取到电脑'}</button>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};