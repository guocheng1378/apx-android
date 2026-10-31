import React, { useEffect, useMemo, useState } from 'react';
import { bridge } from '../api/bridge';

/**
 * 日志导出（issue #52 建议 5）。
 *
 * 报障者原话：mac 端日志「只能导出到指定文件夹、文件名要带日志类型和时间戳、
 * 支持按日期时间范围（精确到秒）导出」。
 *
 * 后端：`log.list` 列目录、`log.export` 按窗口过滤合并写出（apxpc::logexport）。
 * 时间选择用 `<input type="datetime-local" step="1">` —— 精确到秒，浏览器原生
 * 时间选择器，不自己写日历。
 */
interface LogFile { name: string; kind: string; size: number; mtime: number }

interface Props { open: boolean; onClose: () => void }

/** "2026-10-31T00:59:12" → epoch 秒（本地时区）；空串 → 0（不限） */
function toEpoch(local: string): number {
  if (!local) return 0;
  const t = Date.parse(local);
  return Number.isNaN(t) ? 0 : Math.floor(t / 1000);
}

/** epoch 秒 → "2026-10-31T00:59:12"（本地时区）；0 → 空串 */
function toLocalInput(sec: number): string {
  if (!sec) return '';
  const d = new Date(sec * 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function humanSize(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

export const LogExportModal: React.FC<Props> = ({ open, onClose }) => {
  const [files, setFiles] = useState<LogFile[]>([]);
  const [logDir, setLogDir] = useState('');
  const [exportDir, setExportDir] = useState('');
  const [kind, setKind] = useState('');
  const [since, setSince] = useState('');
  const [until, setUntil] = useState('');
  /** 导出文件名里的设备标识（issue #52 第 3 条）：留空 = 本机主机名 */
  const [note, setNote] = useState('');
  /** 后端能不能弹原生「选择文件夹」对话框（Windows 能，macOS/Linux 不能） */
  const [canPickDir, setCanPickDir] = useState(false);
  /** 常用目录候选（不能被弹窗时的一键降级路径） */
  const [dirPresets, setDirPresets] = useState<{ name: string; path: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!open) return;
    setMsg(''); setErr('');
    bridge.act('log.list').then((r: any) => {
      if (r?.ok === false) { setErr(r.message || '读取日志目录失败'); return; }
      const d = r?.data ?? {};
      setFiles(d.files ?? []);
      setLogDir(d.dir ?? '');
      setExportDir(d.exportDir ?? '');
      /** 后端如实上报能力：canPickDir=true 才显示「选择目录…」按钮，
       *  否则退回「常用目录」候选 —— 不假装能弹框。 */
      setCanPickDir(d.canPickDir === true);
      setDirPresets(Array.isArray(d.dirPresets) ? d.dirPresets : []);
    }).catch((e: any) => setErr(String(e?.message ?? e)));
  }, [open]);

  /** 类型下拉：按 kind 归并，带文件数与总大小 */
  const kinds = useMemo(() => {
    const m = new Map<string, { count: number; bytes: number }>();
    for (const f of files) {
      const cur = m.get(f.kind) ?? { count: 0, bytes: 0 };
      cur.count++;
      cur.bytes += f.size;
      m.set(f.kind, cur);
    }
    return Array.from(m.entries()).map(([k, v]) => ({ kind: k, ...v }));
  }, [files]);

  /** 选中的类型包含哪些文件 + 覆盖的时间跨度（给用户确认范围） */
  const picked = useMemo(
    () => files.filter(f => !kind || f.kind === kind),
    [files, kind],
  );
  const span = useMemo(() => {
    if (!picked.length) return '';
    const times = picked.map(f => f.mtime).filter(Boolean).sort((a, b) => a - b);
    if (!times.length) return '';
    return `${toLocalInput(times[0]).replace('T', ' ')} ~ ${toLocalInput(times[times.length - 1]).replace('T', ' ')}`;
  }, [picked]);

  if (!open) return null;

  const doExport = async () => {
    setBusy(true); setMsg(''); setErr('');
    try {
      const r = await bridge.act('log.export', {
        dest: exportDir,
        kind,
        note,
        since: toEpoch(since),
        until: toEpoch(until),
      });
      if (r?.ok === false) setErr(r.message || '导出失败');
      else setMsg(`已导出 ${r?.data?.lines ?? 0} 行 → ${r?.data?.path ?? ''}`);
    } catch (e: any) {
      setErr(String(e?.message ?? e));
    } finally {
      setBusy(false);
    }
  };

  /** 拉起「选择目录」对话框（issue #52 第 3 条）。
   *
   * 原来只有一条「手动填目录」，用户得自己敲路径。现在统一问后端 `log.pickDir`：
   *  · Windows 桌面壳（WebView2）→ 后端在 UI 线程弹 IFileOpenDialog，真能选；
   *  · macOS / Linux 浏览器面板 → 后端如实回 ok=false（面板主线程被 accept 循环
   *    占着，弹不了模态框），前端就把按钮换成「常用目录」下拉，一键换目录。
   *  取消选择时后端回 ok=true + dir 为空，这里显示「已取消」而不是红字报错。
   */
  const pickDir = async () => {
    setErr(''); setMsg('');
    try {
      const r = await bridge.act('log.pickDir');
      if (r?.ok === false) { setErr(r.message || '选择目录失败'); return; }
      const d = r?.data?.dir;
      if (d) { setExportDir(d); setMsg('导出目录已更新'); }
      else setMsg('已取消（未改变目录）');
    } catch (e: any) { setErr(String(e?.message ?? e)); }
  };

  const quick = (hours: number) => {
    const now = Math.floor(Date.now() / 1000);
    setSince(toLocalInput(now - hours * 3600));
    setUntil(toLocalInput(now));
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 animate-fade-in" onClick={onClose}>
      <div className="card card-inner w-[520px] animate-slide-up" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <span className="section-title">日志导出</span>
          <button className="text-on-tertiary hover:text-on-surface text-[18px] leading-none" onClick={onClose}>✕</button>
        </div>

        <div className="data-text text-on-tertiary mb-3 break-all">源目录：{logDir || '—'}</div>

        {/* 类型 */}
        <div className="flex items-center justify-between py-3 border-b border-stroke gap-3">
          <div className="body-text shrink-0">日志类型</div>
          <select className="input-field h-[36px] text-[13px] w-[260px]" value={kind}
                  onChange={e => setKind(e.target.value)}>
            <option value="">全部（{files.length} 个文件）</option>
            {kinds.map(k => (
              <option key={k.kind} value={k.kind}>
                {k.kind}（{k.count} 个 · {humanSize(k.bytes)}）
              </option>
            ))}
          </select>
        </div>

        {/* 时间范围 */}
        <div className="py-3 border-b border-stroke">
          <div className="flex items-center justify-between gap-3">
            <div className="body-text shrink-0">时间范围（精确到秒）</div>
            <div className="flex items-center gap-2">
              <button className="btn-ghost h-[30px] text-[12px]" onClick={() => quick(1)}>近 1 小时</button>
              <button className="btn-ghost h-[30px] text-[12px]" onClick={() => quick(24)}>近 24 小时</button>
              <button className="btn-ghost h-[30px] text-[12px]" onClick={() => { setSince(''); setUntil(''); }}>全部</button>
            </div>
          </div>
          <div className="flex items-center gap-2 mt-2">
            <input type="datetime-local" step="1" className="input-field h-[36px] text-[13px] flex-1"
                   value={since} onChange={e => setSince(e.target.value)} />
            <span className="data-text text-on-tertiary">~</span>
            <input type="datetime-local" step="1" className="input-field h-[36px] text-[13px] flex-1"
                   value={until} onChange={e => setUntil(e.target.value)} />
          </div>
          <div className="data-text text-on-tertiary mt-2">
            留空 = 该端不限{span ? ` · 选中文件覆盖 ${span}` : ''}
          </div>
        </div>

        {/* 文件名标识（device 型号 / 自定义） */}
        <div className="py-3 border-b border-stroke">
          <div className="body-text mb-2">文件名标识（设备型号 / 备注）</div>
          <input className="input-field h-[36px] text-[13px] w-full" value={note}
                 placeholder="留空 = 用本机主机名"
                 onChange={e => setNote(e.target.value)} />
          <div className="data-text text-on-tertiary mt-2">
            拼进文件名，例如 apx-logs-apxdesktop@TurnsMacBookAir-20261031-005912.log
          </div>
        </div>

        {/* 导出目录 */}
        <div className="py-3 gap-3">
          <div className="body-text mb-2">导出到</div>
          <div className="flex items-center gap-2">
            <input className="input-field h-[36px] text-[13px] flex-1" value={exportDir}
                   onChange={e => setExportDir(e.target.value)} />
            {canPickDir ? (
              <button className="btn-ghost h-[36px] text-[13px] shrink-0" onClick={pickDir}>选择目录…</button>
            ) : (
              <select className="input-field h-[36px] text-[13px] shrink-0 max-w-[140px]"
                      value="" onChange={e => { if (e.target.value) setExportDir(e.target.value); }}>
                <option value="">常用目录…</option>
                {dirPresets.map(p => <option key={p.path} value={p.path}>{p.name}</option>)}
              </select>
            )}
          </div>
          <div className="data-text text-on-tertiary mt-2">
            文件名自动带类型、设备标识与时间戳（保留原始文件，不覆盖）
            {!canPickDir && ' · 当前平台不支持弹出系统目录框，请用「常用目录」或手动填写路径'}
          </div>
        </div>

        {(msg || err) && (
          <div className={`data-text mt-2 break-all ${err ? 'text-state-err' : 'text-on-tertiary'}`}>
            {err || msg}
          </div>
        )}

        <div className="mt-4 flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>关闭</button>
          <button className="btn-primary" onClick={doExport} disabled={busy}>
            {busy ? '导出中…' : '导出'}
          </button>
        </div>
      </div>
    </div>
  );
};