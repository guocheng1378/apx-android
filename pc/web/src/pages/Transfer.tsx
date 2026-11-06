import React from 'react';
import { HostState, bridge } from '../api/bridge';
import { Badge, Empty, Meter, Metrics, Notice, Row, Section, Switch } from '../ui/primitives';
import { fileNameOf, fmtSize, useToggle } from '../ui/hooks';
import {
  IconAlert,
  IconFileDown,
  IconFileUp,
  IconFolder,
  IconInfo,
  IconSend,
  IconTransfer,
} from '../ui/icons';

interface Props {
  state: HostState;
}

interface RecvFile {
  name: string;
  path: string;
  size: number;
}

interface LsDevice {
  alias: string;
  deviceType: string;
  ip: string;
  fingerprint: string;
  port: number;
  lastSeenMs: number;
}

/**
 * 传输页 —— 原来分散在「互传卡」与「文件传输弹窗」里，现在同页两条链路：
 *
 *   互传（LocalSend 协议）：手机 / 官方 LocalSend 直发到本机；也能把本机文件发过去。
 *     · 开关 → localsend.toggle；发送 → localsend.send；打开目录 → localsend.openDir
 *     · 浏览器面板拿不到真实路径，改走 /api/upload 把字节传上去（宿主落盘后入队）
 *
 *   文件（9512 私有通道）：只能用给当前连着的手机收发。
 *     · 列表 file.list；发 file.send；删 file.delete（白名单限制在落盘目录内）
 *
 * 结果都靠事件回填（on-localsend-sent / on-file-send-done），页面只在打开时拉一次列表，
 * 收到新文件的事件会自己刷新 —— 与旧弹窗同一套契约，只是不再需要一个窗口才能看。
 */
export const Transfer: React.FC<Props> = ({ state }) => {
  const ls = state.localsend;
  const running = !!ls?.running;
  const devices: LsDevice[] = ls?.devices ?? [];
  const browserMode = bridge.mode === 'http';

  const localSend = useToggle(running, (enable) => bridge.act('localsend.toggle', { enable }));

  /* —— 互传：发送状态 —— */
  const [lsStatus, setLsStatus] = React.useState('');
  const [lsErr, setLsErr] = React.useState('');
  const [lsNote, setLsNote] = React.useState('');
  const [pending, setPending] = React.useState<LsDevice | null>(null);
  const pendingRef = React.useRef<LsDevice | null>(null);
  const lsInputId = 'apx-localsend-file';
  const lsInputRef = React.useRef<HTMLInputElement | null>(null);

  /* —— 文件：列表与状态 —— */
  const [files, setFiles] = React.useState<RecvFile[]>([]);
  const [fileStatus, setFileStatus] = React.useState('');
  const [sendingPath, setSendingPath] = React.useState<string | null>(null);
  const [sendPct, setSendPct] = React.useState<number | null>(null);
  const [confirmDel, setConfirmDel] = React.useState<string | null>(null);
  const fileInputId = 'apx-filepanel-file';

  const refreshFiles = React.useCallback(async () => {
    try {
      const r: any = await bridge.act('file.list');
      if (r?.ok && Array.isArray(r.data)) setFiles(r.data);
    } catch {
      /* 宿主未就绪：下个事件再刷 */
    }
  }, []);

  React.useEffect(() => {
    refreshFiles();
  }, [refreshFiles]);

  /* —— 事件：两个链路的结果都在这里收 —— */
  React.useEffect(() => {
    const offs = [
      bridge.onEvent('on-localsend-received', (evt: any) => {
        const from = evt?.data?.from || '';
        setLsErr('');
        setLsNote(from ? `${from} 发来文件` : '收到文件');
      }),
      bridge.onEvent('on-localsend-sent', (evt: any) => {
        const d = evt?.data || {};
        setLsStatus('');
        setLsErr(d.ok ? '' : d.error || '发送失败');
        if (d.ok) setLsNote(`已发送 ${d.count} 个文件到 ${d.peerAlias || d.ip}`);
      }),
      bridge.onEvent('on-file-received', (evt: any) => {
        refreshFiles();
        setFileStatus(`已收到：${fileNameOf(evt.data?.path || '')}`);
      }),
      // 宿主每 10% 推一拍（web_panel.cpp startFileSend 里节流）：大文件没这条就只剩「正在发送」四个字
      bridge.onEvent('on-file-send-progress', (evt: any) => {
        const d = evt?.data || {};
        if (typeof d.percent === 'number') setSendPct(d.percent);
        if (d.path) setFileStatus(`正在发送：${fileNameOf(d.path)} · ${d.percent ?? 0}%`);
      }),
      bridge.onEvent('on-file-send-done', (evt: any) => {
        setSendingPath(null);
        setSendPct(null);
        setFileStatus(evt.data?.ok ? '已发送到手机' : `发送失败：${evt.data?.error || '未知原因'}`);
        refreshFiles();
      }),
    ];
    return () => offs.forEach((f) => f());
  }, [refreshFiles]);

  /* —— 互传：选文件后发送 —— */
  const armPicker = (dev: LsDevice) => {
    setLsErr('');
    pendingRef.current = dev;
    setPending(dev);
    setLsStatus(`选择要发送到 ${dev.alias || dev.ip} 的文件…`);
  };

  const sendTo = async (dev: LsDevice) => {
    setLsErr('');
    if (browserMode) {
      armPicker(dev);
      lsInputRef.current?.click();
      return;
    }
    if (bridge.mode !== 'webview') {
      setLsErr('当前页面没有连上宿主（地址缺少 ?host=1）：请从 AllPeriph 面板入口打开。');
      return;
    }
    setLsStatus(`正在发送到 ${dev.alias || dev.ip}…`);
    try {
      const pick: any = await bridge.openFile('全部文件|*.*');
      if (!pick?.ok || !pick?.path) {
        setLsStatus('');
        return;
      }
      await bridge.act('localsend.send', { ip: dev.ip, port: dev.port, paths: [pick.path] });
    } catch (e: any) {
      setLsStatus('');
      setLsErr(e?.message || '发送失败');
    }
  };

  const onLsFilesPicked = async (ev: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(ev.target.files ?? []);
    ev.target.value = '';
    const dev = pendingRef.current;
    pendingRef.current = null;
    setPending(null);
    if (!dev || picked.length === 0) {
      setLsStatus('');
      return;
    }
    let done = 0;
    for (const f of picked) {
      try {
        const r: any = await bridge.uploadFile(dev.ip, dev.port, f);
        done++;
        setLsStatus(`正在上传 ${done}/${picked.length} …`);
        if (r?.warn) setLsErr(r.warn);
      } catch (e: any) {
        setLsErr(e?.message || `「${f.name}」上传失败`);
        setLsStatus('');
        return;
      }
    }
    setLsStatus('');
    setLsNote(`已发送 ${picked.length} 个文件到 ${dev.alias || dev.ip}`);
  };

  /* —— 文件：发到手机 —— */
  const sendPath = async (path: string) => {
    setSendingPath(path);
    setSendPct(null);
    setFileStatus(`正在发送：${fileNameOf(path)}…`);
    try {
      await bridge.act('file.send', { path });
      if (!browserMode) setFileStatus(`已发送：${fileNameOf(path)}`);
    } catch (e: any) {
      setFileStatus(`发送失败：${e?.message || e}`);
      setSendingPath(null);
      setSendPct(null);
      return;
    }
    setSendPct(null);
    if (!browserMode) setSendingPath(null);
  };

  const sendByPicker = async () => {
    try {
      const r: any = await bridge.openFile('全部文件|*.*');
      if (r.ok && r.path) await sendPath(r.path);
    } catch (e: any) {
      setFileStatus(`发送失败：${e?.message || e}`);
    }
  };

  const onPhoneFilesPicked = async (ev: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(ev.target.files ?? []);
    ev.target.value = '';
    if (picked.length === 0) return;
    let done = 0;
    let warned = '';
    for (const f of picked) {
      try {
        const r: any = await bridge.uploadFilePhone(f);
        done++;
        setFileStatus(`已上传 ${done}/${picked.length}，正在发送到手机…`);
        if (r?.warn) warned = r.warn;
      } catch (e: any) {
        setFileStatus(`发送失败：${e?.message || `「${f.name}」上传失败`}`);
        return;
      }
    }
    setFileStatus(warned || `已上传 ${picked.length} 个文件，正在发送到手机…`);
  };

  const removeFile = async (f: RecvFile) => {
    if (confirmDel !== f.path) {
      setConfirmDel(f.path);
      window.setTimeout(() => setConfirmDel((cur) => (cur === f.path ? null : cur)), 4000);
      return;
    }
    try {
      await bridge.act('file.delete', { path: f.path });
      setFileStatus(`已删除：${f.name}`);
      refreshFiles();
    } catch (e: any) {
      setFileStatus(`删除失败：${e?.message || e}`);
    }
    setConfirmDel(null);
  };

  const lsLine = lsErr
    ? { tone: 'err' as const, text: lsErr }
    : lsStatus
      ? { tone: 'info' as const, text: lsStatus }
      : localSend.err
        ? { tone: 'err' as const, text: localSend.err }
        : ls?.warn
          ? { tone: 'warn' as const, text: ls.warn }
          : lsNote
            ? { tone: 'ok' as const, text: lsNote }
            : null;

  return (
    <>
      {/* ————— 互传 ————— */}
      <Section index={0} title="互传" note="LocalSend 协议 · 手机或官方客户端直发本机">
        <input
          ref={lsInputRef}
          id={lsInputId}
          type="file"
          multiple
          tabIndex={-1}
          style={{ display: 'none' }}
          onChange={onLsFilesPicked}
        />
        <div className="card">
          <Row
            lead={<IconTransfer size={19} />}
            leadTone="accent"
            title="接收端"
            sub={
              running
                ? `已开启 · 端口 ${ls?.port || '—'}`
                : ls?.error && ls.error !== '未启动'
                  ? ls.error
                  : '未开启 —— 打开开关后手机 / 官方 LocalSend 可直发本机'
            }
            trail={
              <>
                {running && <Badge tone="ok" live>运行中</Badge>}
                <Switch
                  on={localSend.on}
                  busy={localSend.busy}
                  onChange={localSend.toggle}
                  label="互传接收端"
                />
              </>
            }
          />

          <div style={{ padding: '0 16px 12px' }}>
            <Metrics
              items={[
                { k: '端口', v: running ? ls?.port || '—' : '—', dim: !running },
                { k: '附近设备', v: running ? devices.length : '—', dim: !running },
                { k: '本次已收', v: running ? (ls?.received ?? 0) : '—', dim: !running },
                {
                  k: '落盘目录',
                  v: (
                    <span
                      className="mono truncate-1"
                      style={{ fontSize: 12, display: 'block', maxWidth: 190 }}
                      title={ls?.dir || ''}
                    >
                      {ls?.dir || '—'}
                    </span>
                  ),
                  dim: !ls?.dir,
                },
              ]}
            />
          </div>

          {running && devices.length > 0 && (
            <div className="rows" style={{ borderTop: '1px solid var(--line-soft)' }}>
              {devices.slice(0, 5).map((d) => (
                <Row
                  key={d.ip + d.port}
                  title={d.alias || d.ip}
                  sub={
                    <span className="mono">
                      {d.ip}:{d.port}
                      {d.deviceType ? ` · ${d.deviceType}` : ''}
                    </span>
                  }
                  trail={
                    browserMode ? (
                      <label
                        htmlFor={lsInputId}
                        className="btn btn-ghost btn-sm"
                        style={{ cursor: 'pointer' }}
                        onClick={() => armPicker(d)}
                        title={`发送文件到 ${d.alias || d.ip}`}
                      >
                        <IconSend size={14} />
                        发送
                      </label>
                    ) : (
                      <button
                        className="btn btn-ghost btn-sm"
                        onClick={() => sendTo(d)}
                        title={`发送文件到 ${d.alias || d.ip}`}
                      >
                        <IconSend size={14} />
                        发送
                      </button>
                    )
                  }
                />
              ))}
            </div>
          )}

          {running && devices.length === 0 && (
            <Empty
              icon={<IconTransfer size={19} />}
              title="还没有发现附近设备"
              hint="让手机打开互传 / 官方 LocalSend，与本机连同一 Wi‑Fi 即可自动出现"
            />
          )}

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '10px 16px 14px',
              borderTop: '1px solid var(--line-soft)',
            }}
          >
            <button
              className="btn btn-ghost btn-sm"
              onClick={() => bridge.act('localsend.openDir').catch(() => {})}
              disabled={!ls?.dir}
            >
              <IconFolder size={15} />
              打开目录
            </button>
            <span className="faint truncate-1" style={{ fontSize: 11.5 }}>
              {pending ? `等待选择文件发送到 ${pending.alias || pending.ip}` : '收到的文件落在上面的目录里'}
            </span>
          </div>

          {lsLine && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice
                tone={lsLine.tone === 'ok' ? 'info' : lsLine.tone}
                icon={
                  lsLine.tone === 'err' || lsLine.tone === 'warn' ? (
                    <IconAlert size={16} />
                  ) : (
                    <IconInfo size={16} />
                  )
                }
              >
                {lsLine.text}
              </Notice>
            </div>
          )}
        </div>
      </Section>

      {/* ————— 文件（9512 直连手机）————— */}
      <Section index={1} title="手机直传" note="TCP 9512 · 只能发给当前连接的手机">
        <input
          id={fileInputId}
          type="file"
          multiple
          style={{ display: 'none' }}
          onChange={onPhoneFilesPicked}
        />
        <div className="card">
          <div style={{ display: 'flex', gap: 10, padding: 16, alignItems: 'center' }}>
            {browserMode ? (
              <label
                htmlFor={fileInputId}
                className="btn btn-primary"
                style={{ cursor: 'pointer', flex: 1 }}
              >
                <IconFileUp size={16} />
                发送文件到手机
              </label>
            ) : (
              <button className="btn btn-primary" onClick={sendByPicker} style={{ flex: 1 }}>
                <IconFileUp size={16} />
                发送文件到手机
              </button>
            )}
            <button className="btn btn-ghost" onClick={refreshFiles} title="刷新列表">
              刷新
            </button>
          </div>

          {fileStatus && (
            <div style={{ padding: '0 16px 12px' }}>
              <Notice
                tone={fileStatus.includes('失败') ? 'err' : 'info'}
                icon={
                  fileStatus.includes('失败') ? <IconAlert size={16} /> : <IconInfo size={16} />
                }
              >
                {fileStatus}
              </Notice>
            </div>
          )}

          {sendPct != null && (
            <div style={{ padding: '0 16px 12px' }}>
              <Meter value={sendPct / 100} live />
            </div>
          )}

          <div className="rows" style={{ borderTop: '1px solid var(--line-soft)' }}>
            {files.length === 0 ? (
              <Empty
                icon={<IconFileDown size={19} />}
                title="还没有收到过文件"
                hint="手机发来的文件会出现在这里，可原样发回或删除"
              />
            ) : (
              files.map((f) => (
                <div className="row" key={f.path}>
                  <span className="row-lead">
                    <IconFileDown size={17} />
                  </span>
                  <span className="row-main">
                    <span className="row-title truncate-1" title={f.path}>
                      {f.name}
                    </span>
                    <span className="row-sub dim num">{fmtSize(f.size)}</span>
                  </span>
                  <span className="row-trail">
                    <button
                      className="btn btn-ghost btn-sm"
                      disabled={!!sendingPath}
                      onClick={() => sendPath(f.path)}
                      title="把这个文件发回手机"
                    >
                      发回手机
                    </button>
                    <button
                      className={`btn btn-sm ${confirmDel === f.path ? 'btn-danger' : 'btn-quiet'}`}
                      disabled={!!sendingPath}
                      onClick={() => removeFile(f)}
                    >
                      {confirmDel === f.path ? '确认删除？' : '删除'}
                    </button>
                  </span>
                </div>
              ))
            )}
          </div>

          <div style={{ padding: '10px 16px 14px' }} className="faint">
            <span style={{ fontSize: 11.5 }}>
              发送走与手机的控制连接（需已连上）；删除仅限本面板落盘目录内的文件。
            </span>
          </div>
        </div>
      </Section>
    </>
  );
};
