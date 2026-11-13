import React from 'react';
import { HostState, bridge } from './api/bridge';
import { PAGES, pageMeta, type PageId } from './nav';
import { Overview } from './pages/Overview';
import { Display } from './pages/Display';
import { Sound } from './pages/Sound';
import { Transfer } from './pages/Transfer';
import { Settings } from './pages/Settings';
import { RemoteInputDock } from './components/RemoteInputDock';
import { Badge } from './ui/primitives';
import { useTheme, type ThemePref } from './ui/hooks';
import { phaseLabel, phaseTone } from './utils/status';
import { IconAuto, IconLogo, IconMoon, IconSun, IconX } from './ui/icons';

interface Toast {
  id: number;
  text: string;
  tone: 'ok' | 'err' | 'warn' | 'info';
}

/**
 * 主壳 —— 左侧导航轨 + 分区页面。
 *
 * 为什么不是「一张长页面 + 一堆弹窗」：面板窗口只有 960×808，功能却有六块；
 * 旧版把每块塞成一张小卡、点设置/文件/日志再各弹一个 Modal，结果是
 * 「看的信息挤、干的事都躲在窗后面」。现在按手机端的分页思路切开：
 *   一页只干一件事，功能之间用导航切换 —— 不再有多层窗口。
 */
export default function App() {
  const [state, setState] = React.useState<HostState | null>(null);
  const [page, setPage] = React.useState<PageId>('overview');
  const [toasts, setToasts] = React.useState<Toast[]>([]);
  const [remote, setRemote] = React.useState<{ hint: string } | null>(null);
  const [update, setUpdate] = React.useState<{
    latest: string;
    current: string;
    url: string;
    canInstall?: boolean; // 宿主支持一键装（Win 且 Release 有 Setup 资产）；mac 恒 false
    download?: { percent: number } | null; // 下载中（percent -1 = 总量未知）
    donePath?: string; // 下载完成后的安装包路径：横幅切「立即安装」
  } | null>(null);
  const { pref, setPref } = useTheme();
  const pageRef = React.useRef<PageId>('overview');
  pageRef.current = page;
  const toastSeq = React.useRef(0);

  const pushToast = React.useCallback((text: string, tone: Toast['tone'] = 'info') => {
    const id = ++toastSeq.current;
    setToasts((list) => [...list.slice(-2), { id, text, tone }]);
    window.setTimeout(() => setToasts((list) => list.filter((t) => t.id !== id)), 3600);
  }, []);

  /* —— 400ms 状态推流 —— */
  React.useEffect(() => bridge.subscribeState((s) => setState(s)), []);

  /* —— 事件（C++ postEvent）—— */
  React.useEffect(
    () =>
      bridge.onEvent('on-remote-input-request', (evt: any) => {
        setRemote({ hint: evt?.data?.hint || '' });
      }),
    [],
  );

  React.useEffect(
    () =>
      bridge.onEvent('on-update-available', (evt: any) => {
        const d = evt?.data || {};
        if (d.latest && d.url)
          setUpdate({
            latest: d.latest,
            current: d.current || '',
            url: d.url,
            canInstall: !!d.canInstall,
            download: null,
          });
      }),
    [],
  );

  // 一键升级下载进度/结果：横幅按钮按状态切换（下载中禁用 → 就绪后「立即安装」）
  React.useEffect(
    () =>
      bridge.onEvent('on-update-download-progress', (evt: any) => {
        const pct = evt?.data?.percent;
        setUpdate((u) =>
          u ? { ...u, download: { percent: typeof pct === 'number' ? Math.round(pct) : -1 } } : u,
        );
      }),
    [],
  );
  React.useEffect(
    () =>
      bridge.onEvent('on-update-download-done', (evt: any) => {
        const d = evt?.data || {};
        if (d.ok && d.path) {
          setUpdate((u) => (u ? { ...u, download: null, donePath: d.path } : u));
        } else {
          pushToast(`升级包下载失败：${d.error || '未知原因'}`, 'err');
          setUpdate((u) => (u ? { ...u, download: null } : u)); // 横幅回退为「查看」
        }
      }),
    [pushToast],
  );

  // 托盘「文件传输…」：以前是弹文件窗，现在直接切到传输页（事件与页面两处同源）
  React.useEffect(
    () =>
      bridge.onEvent('open-file-panel', () => {
        setPage('transfer');
        pushToast('已切到传输页', 'info');
      }),
    [pushToast],
  );

  // 收文件：不管在哪一页都要让人知道（这就是「全局提示」存在的意义）
  React.useEffect(
    () =>
      bridge.onEvent('on-localsend-received', (evt: any) => {
        const from = evt?.data?.from;
        pushToast(from ? `${from} 发来文件` : '收到文件（互传）', 'ok');
      }),
    [pushToast],
  );

  React.useEffect(
    () =>
      bridge.onEvent('on-file-received', (evt: any) => {
        const name = (evt?.data?.path || '').split(/[\\/]/).pop();
        pushToast(name ? `收到手机的 ${name}` : '收到手机文件', 'ok');
      }),
    [pushToast],
  );

  React.useEffect(
    () =>
      bridge.onEvent('on-file-send-done', (evt: any) => {
        if (pageRef.current === 'transfer') return;
        pushToast(evt?.data?.ok ? '文件已发送到手机' : '文件发送失败', evt?.data?.ok ? 'ok' : 'err');
      }),
    [pushToast],
  );

  /* —— 键盘：1..5 切页，Esc 收起代输浮层 —— */
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      const typing =
        el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT');
      if (e.key === 'Escape' && remote) {
        setRemote(null);
        return;
      }
      if (typing || e.metaKey || e.ctrlKey || e.altKey) return;
      const idx = parseInt(e.key, 10);
      if (idx >= 1 && idx <= PAGES.length) setPage(PAGES[idx - 1].id);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [remote]);

  /* —— 导航滑动指示块：量出当前项的位置，交给 CSS 做回弹 —— */
  const navWrap = React.useRef<HTMLDivElement | null>(null);
  const navItems = React.useRef<(HTMLButtonElement | null)[]>([]);
  const [pillY, setPillY] = React.useState(0);
  React.useLayoutEffect(() => {
    const i = PAGES.findIndex((p) => p.id === page);
    const el = navItems.current[i];
    if (el && navWrap.current) setPillY(el.offsetTop);
  }, [page]);

  if (!state) {
    return (
      <div className="boot">
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
          <div className="boot-mark">
            <IconLogo size={26} />
          </div>
          <div className="boot-text">正在唤醒面板</div>
        </div>
      </div>
    );
  }

  const s = state.link;
  const meta = pageMeta(page);
  const nextPref: ThemePref = pref === 'auto' ? 'light' : pref === 'light' ? 'dark' : 'auto';
  const ThemeIcon = pref === 'auto' ? IconAuto : pref === 'light' ? IconSun : IconMoon;

  const body =
    page === 'overview' ? (
      <Overview state={state} onNavigate={setPage} />
    ) : page === 'display' ? (
      <Display state={state} />
    ) : page === 'sound' ? (
      <Sound state={state} />
    ) : page === 'transfer' ? (
      <Transfer state={state} />
    ) : (
      <Settings
        state={state}
        theme={pref}
        onTheme={setPref}
        version={update?.current || undefined}
      />
    );

  return (
    <div className="app">
      {/* ————— 导航轨 ————— */}
      <aside className="rail">
        <div className="rail-mark" title="全能外设">
          <IconLogo size={20} />
        </div>
        <div className="rail-nav" ref={navWrap}>
          <div className="rail-pill" style={{ ['--y' as any]: `${pillY}px` }} />
          {PAGES.map((p, i) => {
            const Icon = p.icon;
            return (
              <button
                key={p.id}
                ref={(el) => {
                  navItems.current[i] = el;
                }}
                className={`nav-item ${p.id === page ? 'is-active' : ''}`}
                onClick={() => setPage(p.id)}
                title={`${p.title}（${i + 1}）`}
                aria-current={p.id === page}
              >
                <Icon size={21} />
                <span className="nav-label">{p.label}</span>
              </button>
            );
          })}
        </div>
        <div className="rail-foot">
          <span
            className={`dot ${s.phase === 3 ? 'live' : ''}`}
            style={{
              color:
                s.phase === 3
                  ? 'var(--ok)'
                  : s.phase === 4
                    ? 'var(--err)'
                    : s.phase === 0
                      ? 'var(--ink-3)'
                      : 'var(--warn)',
            }}
          />
          <span>{s.phase === 3 ? '已连接' : '待连接'}</span>
        </div>
      </aside>

      {/* ————— 主区 ————— */}
      <main className="main">
        <header className="topbar">
          <div style={{ minWidth: 0 }}>
            <div className="topbar-eyebrow">ALLPERIPH</div>
            <h1 className="topbar-title">{meta.title}</h1>
            <div className="faint" style={{ fontSize: 12.5, marginTop: 2 }}>
              {meta.sub}
            </div>
          </div>

          <div className="topbar-right">
            {update && (
              <div className="update-pill">
                {update.donePath ? '安装包已就绪' : `新版 ${update.latest}`}
                {update.canInstall ? (
                  /* 一键升级（Win）：下载中禁用并显示百分比；就绪后切「立即安装」 */
                  update.donePath ? (
                    <button
                      className="btn btn-primary btn-sm"
                      style={{ height: 26, borderRadius: 99 }}
                      onClick={() =>
                        bridge
                          .act('update.installRun', { path: update.donePath })
                          .catch(() => {})
                      }
                    >
                      立即安装
                    </button>
                  ) : (
                    <button
                      className="btn btn-primary btn-sm"
                      style={{ height: 26, borderRadius: 99 }}
                      disabled={!!update.download}
                      onClick={() => bridge.act('update.install').catch(() => {})}
                    >
                      {update.download
                        ? update.download.percent >= 0
                          ? `下载中 ${update.download.percent}%`
                          : '下载中…'
                        : '一键升级'}
                    </button>
                  )
                ) : (
                  /* 非一键（mac）：保持「查看」跳下载页（后端 app.openUrl 白名单代开） */
                  <button
                    className="btn btn-primary btn-sm"
                    style={{ height: 26, borderRadius: 99 }}
                    onClick={() =>
                      bridge.act('app.openUrl', { url: update.url }).catch(() => {})
                    }
                  >
                    查看
                  </button>
                )}
                <button
                  className="icon-btn"
                  style={{ width: 24, height: 24 }}
                  onClick={() => setUpdate(null)}
                  aria-label="忽略更新" title="忽略更新"
                >
                  <IconX size={13} />
                </button>
              </div>
            )}

            <button
              style={{ all: 'unset', cursor: 'pointer' }}
              onClick={() => setPage('overview')}
              title="回到概览"
            >
              <Badge tone={phaseTone(s.phase as 0)} live={s.phase === 1 || s.phase === 2}>
                {phaseLabel[s.phase as 0]}
              </Badge>
            </button>

            <button
              className="icon-btn"
              style={{ border: '1px solid var(--line)', background: 'var(--surface)' }}
              onClick={() => {
                setPref(nextPref);
                pushToast(
                  `主题：${nextPref === 'auto' ? '跟随系统' : nextPref === 'light' ? '浅色' : '深色'}`,
                  'info',
                );
              }}
              title="切换主题"
              aria-label="切换主题"
            >
              <ThemeIcon size={17} />
            </button>
          </div>
        </header>

        <div className="scroller">
          <div className="page" key={page}>
            {body}
          </div>
        </div>
      </main>

      {/* ————— 全局提示 ————— */}
      <div className="toasts">
        {toasts.map((t) => (
          <div className="toast" key={t.id} data-tone={t.tone}>
            <span className="dot" />
            {t.text}
          </div>
        ))}
      </div>

      {/* ————— 远程代输（非阻塞浮层）————— */}
      {remote && (
        <RemoteInputDock
          hint={remote.hint}
          onClose={() => setRemote(null)}
          onSend={async (text) => {
            setRemote(null);
            try {
              // 0x04 = INPUT_FLAG_COMMIT（整段提交）—— 与手机端 RemoteInputActivity 同口径
              await bridge.act('session.sendInputText', { text, flags: 0x04 });
              await bridge.act('session.sendInputDone');
              pushToast('已发送到手机', 'ok');
            } catch (e: any) {
              pushToast(e?.message || '发送失败', 'err');
            }
          }}
        />
      )}
    </div>
  );
}
