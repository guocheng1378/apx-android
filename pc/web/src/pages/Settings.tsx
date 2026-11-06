import React from 'react';
import { HostState, bridge, hostPlatform } from '../api/bridge';
import { Badge, Chip, Fold, Notice, Row, Section, Segmented, Switch } from '../ui/primitives';
import { useAction, useToggle, type ThemePref } from '../ui/hooks';
import { IconAlert, IconFileDown, IconInfo } from '../ui/icons';

interface Props {
  state: HostState;
  theme: ThemePref;
  onTheme: (t: ThemePref) => void;
  version?: string;
}

const PLATFORM_LABEL: Record<string, string> = {
  windows: 'Windows',
  macos: 'macOS',
  linux: 'Linux',
};

/**
 * 设置页 —— 原来的「设置弹窗」整块搬到这里，顺带把散落的项收敛：
 *   · 外观（主题）—— PC 端新增，与手机端「明暗两套令牌」同一套语义；
 *   · 启动与窗口 —— 开机自启 / 隐藏 / 退出；
 *   · 副屏 —— 自适应码率 / 默认码率（这两项真的会写配置并持久化）；
 *   · 悬浮球 —— 显隐 + 球上显示哪些功能（写入后立刻重装注册表）；
 *   · 日志 —— 级别与导出（导出原来是独立弹窗，现在就地展开）。
 *
 * 传输状态（USB / 蓝牙）不在这里重复：概览页「接入方式」已经是唯一真源。
 */
export const Settings: React.FC<Props> = ({ state, theme, onTheme, version }) => {
  const platform = hostPlatform(state);
  const cfg = state.config as Record<string, any>;

  const autostart = useToggle(!!cfg.autostart, (enable) =>
    bridge.act('tray.setAutostart', { enable }),
  );
  const adaptive = useToggle(!!cfg.adaptiveBitrate, (on) => bridge.act('config.setAdaptive', { on }));
  const [bitrate, setBitrate] = React.useState(String(cfg.bitrateMbps ?? 8));
  const [logLevel, setLogLevel] = React.useState(String(cfg.logLevel ?? 'info'));
  const [savedHint, setSavedHint] = React.useState('');
  const acts = useAction();

  React.useEffect(() => {
    if (cfg.bitrateMbps != null) setBitrate(String(cfg.bitrateMbps));
    if (cfg.logLevel) setLogLevel(String(cfg.logLevel));
  }, [cfg.bitrateMbps, cfg.logLevel]);

  const flash = (text: string) => {
    setSavedHint(text);
    window.setTimeout(() => setSavedHint(''), 2200);
  };

  /* —— 悬浮球 —— */
  const [ball, setBall] = React.useState<{
    created?: boolean;
    visible?: boolean;
    actions: { id: string; icon: string; label: string }[];
    selected: string[];
  } | null>(null);

  React.useEffect(() => {
    let alive = true;
    bridge
      .act('ball.config')
      .then((r: any) => {
        if (!alive) return;
        const d = r?.data ?? {};
        const list: { id: string; icon: string; label: string }[] = Array.isArray(d.actions)
          ? d.actions
          : [];
        const sel: string[] = Array.isArray(d.selected) ? d.selected : [];
        setBall({
          created: d.created !== false,
          visible: !!d.visible,
          actions: list,
          // selected 为空 = 用户没自定义过（后端按默认全装）
          selected: sel.length ? sel : list.map((a) => a.id),
        });
      })
      .catch(() => alive && setBall(null));
    return () => {
      alive = false;
    };
  }, []);

  const setBallVisible = async (visible: boolean) => {
    setBall((b) => (b ? { ...b, visible } : b));
    await acts.run(() => bridge.act('ball.setVisible', { visible }));
    flash('已保存');
  };

  const toggleBallAction = async (id: string) => {
    if (!ball) return;
    const next = ball.selected.includes(id)
      ? ball.selected.filter((x) => x !== id)
      : [...ball.selected, id];
    setBall({ ...ball, selected: next });
    await acts.run(() => bridge.act('ball.setActions', { actions: next }));
    flash('球上的功能已更新');
  };

  return (
    <>
      {/* ————— 外观 ————— */}
      <Section index={0} title="外观">
        <div className="card rows">
          <Row
            title="主题"
            sub="跟随系统时随系统日夜自动切换；两套都是完整配色，不是简单反色"
            trail={
              <Segmented
                value={theme}
                onChange={(v) => onTheme(v as ThemePref)}
                options={[
                  { value: 'auto', label: '跟随系统' },
                  { value: 'light', label: '浅色' },
                  { value: 'dark', label: '深色' },
                ]}
              />
            }
          />
        </div>
      </Section>

      {/* ————— 启动与窗口 ————— */}
      <Section index={1} title="启动与窗口">
        <div className="card rows">
          <Row
            title="开机自启"
            sub="写入 HKCU\…\Run，登录后静默启动并缩到托盘"
            trail={
              <Switch
                on={autostart.on}
                busy={autostart.busy}
                onChange={autostart.toggle}
                label="开机自启"
              />
            }
          />
          <Row
            title="隐藏面板"
            sub="面板收到托盘；点托盘图标可再唤回"
            trail={
              bridge.mode === 'http' ? (
                <span className="faint" style={{ fontSize: 12.5 }}>
                  浏览器面板不支持
                </span>
              ) : (
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => bridge.act('window.hide').catch(() => {})}
                >
                  隐藏
                </button>
              )
            }
          />
          <Row
            title="退出全能外设"
            sub="会同时停止副屏、音频、互传等后台服务"
            trail={
              <button
                className="btn btn-danger btn-sm"
                onClick={() => bridge.act('window.quit').catch(() => {})}
              >
                退出
              </button>
            }
          />
          {autostart.err && (
            <div style={{ padding: '0 16px 14px' }}>
              <Notice tone="err" icon={<IconAlert size={16} />}>
                {autostart.err}
              </Notice>
            </div>
          )}
        </div>
      </Section>

      {/* ————— 副屏参数 ————— */}
      <Section index={2} title="副屏" note="这两项会写进配置文件并持久化">
        <div className="card rows">
          <Row
            title="自适应码率"
            sub="按链路质量自动升降码率（关掉则固定用下面的默认码率）"
            trail={
              <Switch
                on={adaptive.on}
                busy={adaptive.busy}
                onChange={adaptive.toggle}
                label="副屏自适应码率"
              />
            }
          />
          <Row
            title="默认码率"
            sub="开流时使用的目标码率"
            trail={
              <Segmented
                value={bitrate}
                onChange={(v) => {
                  setBitrate(v);
                  acts
                    .run(() => bridge.act('config.setBitrate', { mbps: parseInt(v) || 8 }))
                    .then((ok) => ok && flash(`默认码率已设为 ${v} Mbps`));
                }}
                options={[
                  { value: '8', label: '8 M' },
                  { value: '12', label: '12 M' },
                  { value: '16', label: '16 M' },
                ]}
              />
            }
          />
        </div>
      </Section>

      {/* ————— 悬浮球 ————— */}
      <Section index={3} title="悬浮球" note="桌面常驻快捷入口（Windows）">
        <div className="card rows">
          <Row
            title="显示悬浮球"
            sub={
              ball?.created === false
                ? '当前平台 / 会话没有创建悬浮球'
                : '球上可放常用功能，点开后扇形展开'
            }
            trail={
              <Switch
                on={!!ball?.visible}
                disabled={!ball || ball.created === false}
                busy={acts.busy}
                onChange={setBallVisible}
                label="显示悬浮球"
              />
            }
          />
          {ball && ball.actions.length > 0 && (
            <div style={{ padding: '12px 16px 14px' }}>
              <div className="row-title" style={{ fontSize: 13.5 }}>
                球上显示的功能
              </div>
              <div className="faint" style={{ fontSize: 11.5, marginTop: 2, marginBottom: 10 }}>
                点一下即保存，立刻生效（后端重装注册表）
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {ball.actions.map((a) => (
                  <Chip
                    key={a.id}
                    active={ball.selected.includes(a.id)}
                    onClick={() => toggleBallAction(a.id)}
                  >
                    <span style={{ fontSize: 14 }}>{a.icon}</span>
                    {a.label}
                  </Chip>
                ))}
              </div>
            </div>
          )}
        </div>
      </Section>

      {/* ————— 日志 ————— */}
      <Section index={4} title="日志">
        <div className="card rows">
          <Row
            title="日志级别"
            sub="debug 会记录逐帧/逐包细节，平时用 info 就够"
            trail={
              <Segmented
                value={logLevel}
                onChange={(v) => {
                  setLogLevel(v);
                  acts
                    .run(() => bridge.act('config.setLogLevel', { level: v }))
                    .then((ok) => ok && flash(`日志级别已设为 ${v}`));
                }}
                options={[
                  { value: 'debug', label: 'debug' },
                  { value: 'info', label: 'info' },
                  { value: 'warn', label: 'warn' },
                  { value: 'error', label: 'error' },
                ]}
              />
            }
          />
          <LogExport />
        </div>
      </Section>

      {/* ————— 关于 ————— */}
      <Section index={5} title="关于">
        <div className="card rows">
          <Row
            title="全能外设"
            sub="把手机变成电脑的触控板、键盘、副屏、音箱、麦克风与摄像头"
            trail={<Badge tone="accent">{version || '本地版'}</Badge>}
          />
          <Row title="宿主平台" trail={<span className="mono faint">{PLATFORM_LABEL[platform] ?? platform}</span>} />
          <Row
            title="传输通道"
            sub="无线 9511 / 9512 · 蓝牙 HID · USB（HID + UAC2 + NCM）"
            trail={<span className="faint mono">MIT</span>}
          />
        </div>
      </Section>

      {savedHint && (
        <div className="toast" style={{ position: 'fixed', right: 24, bottom: 22, zIndex: 90 }}>
          <span className="dot" style={{ color: 'var(--ok)' }} />
          {savedHint}
        </div>
      )}
    </>
  );
};

/* ══════════════════════════ 日志导出（原独立弹窗） ══════════════════════════ */

interface LogFile {
  name: string;
  kind: string;
  size: number;
  mtime: number;
}

function toEpoch(local: string): number {
  if (!local) return 0;
  const t = Date.parse(local);
  return Number.isNaN(t) ? 0 : Math.floor(t / 1000);
}

function toLocalInput(sec: number): string {
  if (!sec) return '';
  const d = new Date(sec * 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(
    d.getMinutes(),
  )}:${p(d.getSeconds())}`;
}

function humanSize(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

/**
 * 日志导出：列目录 → 选类型 / 时间窗 / 标识 → 导出。
 *
 * 后端 `log.list` / `log.export` / `log.pickDir`；mac/Linux 弹不了系统目录框时
 * 后端如实回 canPickDir=false，这里退回「常用目录」下拉 —— 不假装能弹框。
 */
const LogExport: React.FC = () => {
  const [files, setFiles] = React.useState<LogFile[]>([]);
  const [logDir, setLogDir] = React.useState('');
  const [exportDir, setExportDir] = React.useState('');
  const [kind, setKind] = React.useState('');
  const [since, setSince] = React.useState('');
  const [until, setUntil] = React.useState('');
  const [note, setNote] = React.useState('');
  const [canPickDir, setCanPickDir] = React.useState(false);
  const [dirPresets, setDirPresets] = React.useState<{ name: string; path: string }[]>([]);
  const [busy, setBusy] = React.useState(false);
  const [msg, setMsg] = React.useState('');
  const [err, setErr] = React.useState('');

  const load = React.useCallback(() => {
    bridge
      .act('log.list')
      .then((r: any) => {
        if (r?.ok === false) {
          setErr(r.message || '读取日志目录失败');
          return;
        }
        const d = r?.data ?? {};
        setFiles(d.files ?? []);
        setLogDir(d.dir ?? '');
        setExportDir(d.exportDir ?? '');
        setCanPickDir(d.canPickDir === true);
        setDirPresets(Array.isArray(d.dirPresets) ? d.dirPresets : []);
      })
      .catch((e: any) => setErr(String(e?.message ?? e)));
  }, []);

  /* 目录扫描要遍历日志目录，别在设置页一挂载就做：展开时才第一次拉，之后走「重新扫描」 */
  const scannedRef = React.useRef(false);
  const openOnce = () => {
    if (scannedRef.current) return;
    scannedRef.current = true;
    load();
  };

  const kinds = React.useMemo(() => {
    const m = new Map<string, { count: number; bytes: number }>();
    for (const f of files) {
      const cur = m.get(f.kind) ?? { count: 0, bytes: 0 };
      cur.count++;
      cur.bytes += f.size;
      m.set(f.kind, cur);
    }
    return Array.from(m.entries()).map(([k, v]) => ({ kind: k, ...v }));
  }, [files]);

  const picked = React.useMemo(
    () => files.filter((f) => !kind || f.kind === kind),
    [files, kind],
  );
  const span = React.useMemo(() => {
    if (!picked.length) return '';
    const times = picked.map((f) => f.mtime).filter(Boolean).sort((a, b) => a - b);
    if (!times.length) return '';
    return `${toLocalInput(times[0]).replace('T', ' ')} ~ ${toLocalInput(
      times[times.length - 1],
    ).replace('T', ' ')}`;
  }, [picked]);

  const quick = (hours: number) => {
    const now = Math.floor(Date.now() / 1000);
    setSince(toLocalInput(now - hours * 3600));
    setUntil(toLocalInput(now));
  };

  const pickDir = async () => {
    setErr('');
    setMsg('');
    try {
      const r: any = await bridge.act('log.pickDir');
      if (r?.ok === false) {
        setErr(r.message || '选择目录失败');
        return;
      }
      const d = r?.data?.dir;
      if (d) {
        setExportDir(d);
        setMsg('导出目录已更新');
      } else setMsg('已取消（未改变目录）');
    } catch (e: any) {
      setErr(String(e?.message ?? e));
    }
  };

  const doExport = async () => {
    setBusy(true);
    setMsg('');
    setErr('');
    try {
      const r: any = await bridge.act('log.export', {
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

  return (
    <Fold
      title="导出日志"
      sub="按类型与时间窗合并成一个文件，文件名自动带设备标识与时间戳"
      lead={<IconFileDown size={17} />}
      onOpen={openOnce}
    >
      <div style={{ padding: '4px 16px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="faint" style={{ fontSize: 11.5 }}>
          源目录：<span className="mono">{logDir || '—'}</span>
          <button className="btn btn-quiet btn-sm" style={{ marginLeft: 8 }} onClick={load}>
            重新扫描
          </button>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span className="faint" style={{ fontSize: 12, width: 52, flex: 'none' }}>
            类型
          </span>
          <span className="select-wrap" style={{ flex: 1 }}>
            <select className="field" value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="">全部（{files.length} 个文件）</option>
              {kinds.map((k) => (
                <option key={k.kind} value={k.kind}>
                  {k.kind}（{k.count} 个 · {humanSize(k.bytes)}）
                </option>
              ))}
            </select>
            <span className="chev">▾</span>
          </span>
        </div>

        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
            <span className="faint" style={{ fontSize: 12, width: 52, flex: 'none' }}>
              时间窗
            </span>
            <button className="btn btn-quiet btn-sm" onClick={() => quick(1)}>
              近 1 小时
            </button>
            <button className="btn btn-quiet btn-sm" onClick={() => quick(24)}>
              近 24 小时
            </button>
            <button
              className="btn btn-quiet btn-sm"
              onClick={() => {
                setSince('');
                setUntil('');
              }}
            >
              全部
            </button>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingLeft: 52 }}>
            <input
              type="datetime-local"
              step={1}
              className="field"
              style={{ flex: 1 }}
              value={since}
              onChange={(e) => setSince(e.target.value)}
            />
            <span className="faint">~</span>
            <input
              type="datetime-local"
              step={1}
              className="field"
              style={{ flex: 1 }}
              value={until}
              onChange={(e) => setUntil(e.target.value)}
            />
          </div>
          <div className="faint" style={{ fontSize: 11.5, marginTop: 6, paddingLeft: 52 }}>
            留空 = 该端不限{span ? ` · 选中文件覆盖 ${span}` : ''}
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span className="faint" style={{ fontSize: 12, width: 52, flex: 'none' }}>
            标识
          </span>
          <input
            className="field"
            style={{ flex: 1 }}
            value={note}
            placeholder="留空 = 用本机主机名"
            onChange={(e) => setNote(e.target.value)}
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span className="faint" style={{ fontSize: 12, width: 52, flex: 'none' }}>
            导出到
          </span>
          <input
            className="field"
            style={{ flex: 1 }}
            value={exportDir}
            onChange={(e) => setExportDir(e.target.value)}
          />
          {canPickDir ? (
            <button className="btn btn-ghost btn-sm" onClick={pickDir}>
              选择目录…
            </button>
          ) : (
            <span className="select-wrap" style={{ width: 130, flex: 'none' }}>
              <select
                className="field"
                value=""
                onChange={(e) => {
                  if (e.target.value) setExportDir(e.target.value);
                }}
              >
                <option value="">常用目录…</option>
                {dirPresets.map((p) => (
                  <option key={p.path} value={p.path}>
                    {p.name}
                  </option>
                ))}
              </select>
              <span className="chev">▾</span>
            </span>
          )}
        </div>

        {!canPickDir && (
          <div className="faint" style={{ fontSize: 11.5, paddingLeft: 62 }}>
            当前平台不支持弹出系统目录框：用「常用目录」或直接填路径。
          </div>
        )}

        {(msg || err) && (
          <Notice
            tone={err ? 'err' : 'info'}
            icon={err ? <IconAlert size={16} /> : <IconInfo size={16} />}
          >
            <span style={{ wordBreak: 'break-all' }}>{err || msg}</span>
          </Notice>
        )}

        <div>
          <button className="btn btn-primary" onClick={doExport} disabled={busy}>
            <IconFileDown size={16} />
            {busy ? '导出中…' : '导出日志'}
          </button>
        </div>
      </div>
    </Fold>
  );
};
