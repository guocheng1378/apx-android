import React from 'react';
import { HostState, bridge } from '../api/bridge';
import { Badge, Empty, Meter, Metrics, Notice, Row, Section, Switch } from '../ui/primitives';
import { fileNameOf, fmtSize, useToggle } from '../ui/hooks';
import {
  IconAlert,
  IconCamera,
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

/** 手机文件条目（on-phone-files 事件载荷，0x45 应答）：isDir=true 时 size 无意义 */
interface PhoneEntry {
  name: string;
  isDir: boolean;
  size: number;
}

/** 相册条目（on-album-page 事件载荷，0x49 应答）：added 为秒，id 是 MediaStore 图片 _id */
interface AlbumEntryT {
  id: number;
  added: number;
  size: number;
  path: string;
  name: string;
}

/** 相册一页条数（与 Android ApxFrame.ALBUM_PAGE_SIZE / web_panel.cpp 口径一致） */
const ALBUM_PAGE_SIZE = 60;

/** 0x45/0x47/0x49 的 err 取值 → 中文文案（与 Android ApxFrame.PHONEFILE_ERR_* 对拍） */
const phoneFileErrText = (err: number, path: string) => {
  const name = path ? `「${fileNameOf(path)}」` : '';
  switch (err) {
    case 1: return '手机还没授权：请在手机端允许电脑访问';
    case 2: return `${name}不存在（可能已被删）`;
    case 3: return `${name}读取失败`;
    case 4: return `${name}发送失败（连接中断？）`;
    default: return `操作失败（错误码 ${err}）`;
  }
};

/** 公共外部存储根（Android 惯例路径；子目录路径由对端拼出来再请求） */
const STORAGE_ROOT = '/storage/emulated/0';

/** 手机路径拼接：根入口条目（「内部存储」）= 存储根本身；其余按目录层级往下拼 */
const childPathOf = (cur: string, name: string) =>
  cur === '' ? (name === '内部存储' ? STORAGE_ROOT : STORAGE_ROOT + '/' + name)
             : cur + '/' + name;

/** 相册拍摄时间（秒）→ 分组日期标签：今天 / 昨天 / 完整日期 */
const albumDateLabel = (sec: number) => {
  const d = new Date(sec * 1000);
  const day = new Date(d);
  day.setHours(0, 0, 0, 0);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diff = Math.round((today.getTime() - day.getTime()) / 86400000);
  if (diff === 0) return '今天';
  if (diff === 1) return '昨天';
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
};

/** 缩略图加载重试上限：手机回图需要时间，先轮询几轮，超限按「取回失败」处理 */
const THUMB_MAX_TRIES = 15;

/**
 * 相册单格：滚进视口才发缩略图请求（album.thumb，宿主去重守卫）；
 * 缩略图文件由 9512 异步推回，img 首次会 404 —— 失败后隔 1.5s 换时间戳重试，
 * 重试超限或收到失败回执（album:thumb: 前缀）就标灰占位、点按可重试。
 * 点格子 = 取回原图（phonefile.pull，与浏览文件同一通道）；已标灰的格子点按 = 重试缩略图。
 */
const AlbumTile: React.FC<{
  entry: AlbumEntryT;
  failed: boolean;
  onVisible: (id: number) => void;
  onRetry: (id: number) => void;
  onOpen: (e: AlbumEntryT) => void;
  pulling: boolean;
}> = ({ entry, failed, onVisible, onRetry, onOpen, pulling }) => {
  const boxRef = React.useRef<HTMLButtonElement | null>(null);
  const [tries, setTries] = React.useState(0); // 已重试次数（img onError 驱动）
  const [loaded, setLoaded] = React.useState(false);

  React.useEffect(() => {
    const el = boxRef.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (ins) => ins.forEach((i) => i.isIntersecting && onVisible(entry.id)),
      { rootMargin: '300px' },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [entry.id, onVisible]);

  const dead = failed || tries >= THUMB_MAX_TRIES;
  return (
    <button
      ref={boxRef}
      className="album-tile"
      style={{
        position: 'relative',
        aspectRatio: '1 / 1',
        borderRadius: 8,
        overflow: 'hidden',
        border: '1px solid var(--line-soft)',
        padding: 0,
        cursor: 'pointer',
        background: 'var(--bg-soft, rgba(127,127,127,.12))',
      }}
      title={`${entry.name} · 点击取回原图`}
      onClick={() => {
        if (dead) {
          setTries(0);          // 重试：本地计数清零 + 宿主侧守卫由 onRetry 清
          onRetry(entry.id);
        } else {
          onOpen(entry);
        }
      }}
    >
      {!dead && (
        <img
          src={`https://media.allperiph/albumthumb_${entry.id}.jpg` + (tries > 0 ? `?t=${tries}` : '')}
          alt={entry.name}
          loading="lazy"
          onLoad={() => setLoaded(true)}
          onError={() => {
            // 缩略图还没推回来（img 会先 404）：隔 1.5s 换时间戳重试，
            // 超过上限当「取回失败」标灰，避免无限刷新
            window.setTimeout(() => setTries((t) => t + 1), 1500);
          }}
          style={{
            width: '100%',
            height: '100%',
            objectFit: 'cover',
            display: loaded ? 'block' : 'none',
          }}
        />
      )}
      {(!loaded || dead) && (
        <span
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 4,
            fontSize: 10.5,
            color: 'var(--on-tertiary, #888)',
            background: dead ? 'rgba(127,127,127,.25)' : 'transparent',
            pointerEvents: 'none',
          }}
        >
          {pulling ? (
            <>取回中…</>
          ) : dead ? (
            <>无法显示<br />点按重试</>
          ) : (
            <>加载中…</>
          )}
        </span>
      )}
    </button>
  );
};

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

  /* —— 浏览手机文件（0x44–0x47）：面包屑 + 条目列表 —— */
  // act 只表示「请求已发出」，真正的列表走 on-phone-files 事件，
  // 当前目录与事件里的 path 回显要对着匹配（过期应答直接丢）。
  const [phonePath, setPhonePath] = React.useState('');            // '' = 根入口
  const [phoneEntries, setPhoneEntries] = React.useState<PhoneEntry[]>([]);
  const [phoneBusy, setPhoneBusy] = React.useState(false);
  const [phonePull, setPhonePull] = React.useState<string | null>(null); // 正在取回的文件名
  const [phoneErr, setPhoneErr] = React.useState('');
  const phonePathRef = React.useRef('');

  /** 进入/刷新指定目录：应答是事件，这里只发请求 */
  const browsePhone = React.useCallback(async (dir: string) => {
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

  /* —— 相册投影（0x48–0x4B）：日期分组网格 + 滚动加载 + 新照片提醒 —— */
  const [albumEntries, setAlbumEntries] = React.useState<AlbumEntryT[]>([]);
  const [albumBusy, setAlbumBusy] = React.useState(false);          // 等一页应答中
  const [albumDone, setAlbumDone] = React.useState(false);          // 已到相册最老一页
  const [albumWatchOn, setAlbumWatchOn] = React.useState(false);    // 新照片提醒开关
  const [albumErr, setAlbumErr] = React.useState('');
  const [albumNote, setAlbumNote] = React.useState('');
  const albumLoadedPages = React.useRef(new Set<number>());   // 已请求过的页号
  const albumBusyRef = React.useRef(false);                   // 同一时间只等一页
  const albumRequestedThumbs = React.useRef(new Set<number>()); // 已请求过缩略图的 id
  const albumFailedThumbs = React.useRef(new Set<number>());    // 缩略图取回失败的 id
  const [, setThumbTick] = React.useState(0);                 // 失败集合变化触发重渲染

  /** 拉一页相册（page 从 0 起）：去重防抖，真正的条目走 on-album-page 事件 */
  const loadAlbumPage = React.useCallback(async (page: number) => {
    if (albumBusyRef.current || albumLoadedPages.current.has(page)) return;
    albumBusyRef.current = true;
    albumLoadedPages.current.add(page);
    setAlbumBusy(true);
    try {
      await bridge.act('album.page', { page });
    } catch (e: any) {
      albumLoadedPages.current.delete(page);
      albumBusyRef.current = false;
      setAlbumBusy(false);
      setAlbumErr(e?.message || String(e));
    }
  }, []);

  /** 按条目 id 请求缩略图（同 id 只发一次；重试由 onThumbRetry 清守卫后再发） */
  const requestThumb = React.useCallback((id: number) => {
    if (albumRequestedThumbs.current.has(id)) return;
    albumRequestedThumbs.current.add(id);
    bridge.act('album.thumb', { id }).catch(() => {
      albumFailedThumbs.current.add(id);
      setThumbTick((t) => t + 1);
    });
  }, []);

  /** 缩略图占位点击重试：清掉两条守卫后重新请求，img 的 src 加时间戳绕缓存 */
  const retryThumb = React.useCallback((id: number) => {
    albumRequestedThumbs.current.delete(id);
    albumFailedThumbs.current.delete(id);
    setThumbTick((t) => t + 1);
    requestThumb(id);
  }, [requestThumb]);

  /** 相册刷新：清空已加载页与守卫，从第 0 页重新开始（第一次进入也走这里） */
  const resetAlbum = React.useCallback(() => {
    albumLoadedPages.current = new Set();
    albumBusyRef.current = false;
    albumRequestedThumbs.current = new Set();
    albumFailedThumbs.current = new Set();
    setAlbumEntries([]);
    setAlbumDone(false);
    setAlbumErr('');
    setAlbumNote('');
    loadAlbumPage(0);
  }, [loadAlbumPage]);

  const toggleWatch = React.useCallback(async (on: boolean) => {
    setAlbumWatchOn(on);
    try {
      await bridge.act('album.watch', { on });
      setAlbumNote(on ? '已开启：手机拍到新照片会发系统通知提醒' : '已关闭新照片提醒');
    } catch (e: any) {
      setAlbumWatchOn(!on);
      setAlbumErr(e?.message || String(e));
    }
  }, []);

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
      // 宿主每 10% 推一拍（web_panel.cpp localsend.send 分支节流）：大文件互传
      // 从「正在发送…」干等变成带百分比 —— 也是区分「在传」与「卡死」的唯一信号
      bridge.onEvent('on-localsend-progress', (evt: any) => {
        const d = evt?.data || {};
        if (typeof d.percent !== 'number') return;
        const nm = d.fileName ? fileNameOf(String(d.fileName)) : '';
        setLsStatus(
          `正在发送${nm ? ` ${nm}` : ''} · ${d.percent}%` +
            (d.filesTotal > 1 ? ` · ${d.filesDone + 1}/${d.filesTotal}` : ''),
        );
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
      // —— 浏览手机文件：目录应答（0x45），path 与请求对上才算这轮结果 ——
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
      // 取文件失败回执（0x47，非相册前缀）：按钮还原并提示原因
      bridge.onEvent('on-phone-files-fail', (evt: any) => {
        const d = evt.data ?? {};
        setPhonePull(null);
        setPhoneErr(phoneFileErrText(d.err ?? -1, d.path || ''));
      }),
      // —— 相册投影：分页应答（0x49）—— 按页拼接、少于整页即到相册最老处
      bridge.onEvent('on-album-page', (evt: any) => {
        const d = evt.data ?? {};
        setAlbumBusy(false);
        albumBusyRef.current = false;
        if (d.err) {
          setAlbumErr(phoneFileErrText(d.err, ''));
          return;
        }
        const entries: AlbumEntryT[] = Array.isArray(d.entries) ? d.entries : [];
        setAlbumEntries((prev) => {
          const seen = new Set(prev.map((e) => e.id));
          return prev.concat(entries.filter((e) => !seen.has(e.id)));
        });
        if (entries.length < ALBUM_PAGE_SIZE) setAlbumDone(true);
      }),
      // 缩略图失败回执（0x47 的 album:thumb: 前缀）：对应格子标灰占位
      bridge.onEvent('on-album-page-fail', (evt: any) => {
        const d = evt.data ?? {};
        const m = /^album:thumb:(\d+)/.exec(d.path || '');
        if (m) {
          albumFailedThumbs.current.add(Number(m[1]));
          setThumbTick((t) => t + 1);
        }
        setAlbumErr(phoneFileErrText(d.err ?? -1, d.path || ''));
      }),
    ];
    return () => offs.forEach((f) => f());
  }, [refreshFiles]);

  /* —— 浏览手机文件 / 相册：只在桌面壳（webview）里能用 —— 浏览器面板的宿主
     （panel_http）没有 phonefile/album 动作，缩略图虚拟主机也只挂在 WebView2 上，
     这里如实降级，不装作可用。首次进入桌面壳时各拉一次根入口 / 第 0 页。 */
  React.useEffect(() => {
    if (bridge.mode !== 'webview') return;
    if (phonePathRef.current === '' && phoneEntries.length === 0 && !phoneBusy) browsePhone('');
    resetAlbum();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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

  /* —— 相册：滚动到底自动加载下一页（IntersectionObserver 哨兵） —— */
  const albumSentinelRef = React.useRef<HTMLDivElement | null>(null);
  React.useEffect(() => {
    const el = albumSentinelRef.current;
    if (!el || bridge.mode !== 'webview') return;
    const io = new IntersectionObserver(
      (ins) => {
        ins.forEach((i) => {
          if (i.isIntersecting && !albumDone && !albumBusy && albumEntries.length > 0)
            loadAlbumPage(albumLoadedPages.current.size);
        });
      },
      { rootMargin: '300px' },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [albumDone, albumBusy, albumEntries.length, loadAlbumPage]);

  /* —— 相册：按拍摄日期分组（条目天然按时间倒序，顺序扫一遍成组） —— */
  const albumGroups = React.useMemo(() => {
    const groups: { label: string; items: AlbumEntryT[] }[] = [];
    for (const e of albumEntries) {
      const label = albumDateLabel(e.added);
      const last = groups[groups.length - 1];
      if (last && last.label === label) last.items.push(e);
      else groups.push({ label, items: [e] });
    }
    return groups;
  }, [albumEntries]);

  /* —— 相册：点格子取原图（走手机文件通道，落进「手机直传」的收到列表） —— */
  const [albumPull, setAlbumPull] = React.useState<string | null>(null);
  const pullOriginal = async (e: AlbumEntryT) => {
    setAlbumPull(e.name);
    setAlbumErr('');
    try {
      await bridge.act('phonefile.pull', { path: e.path });
      setAlbumNote(`正在取回原图：${e.name}（完成后出现在「手机直传」列表）`);
    } catch (err: any) {
      setAlbumPull(null);
      setAlbumErr(err?.message || String(err));
    }
  };

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

      {/* ————— 相册投影（0x48–0x4B）————— */}
      <Section index={2} title="相册投影" note="手机相册在电脑上翻 · 点格子取原图">
        <div className="card">
          {browserMode ? (
            <div style={{ padding: 16 }}>
              <Notice tone="info" icon={<IconInfo size={16} />}>
                相册投影需要桌面客户端（缩略图通道只挂在 Windows 桌面壳上），浏览器面板暂不支持。
              </Notice>
            </div>
          ) : (
            <>
              <div style={{ display: 'flex', gap: 10, padding: 16, alignItems: 'center' }}>
                <div style={{ flex: 1 }}>
                  <Row
                    lead={<IconCamera size={19} />}
                    leadTone="accent"
                    title="新照片提醒"
                    sub="手机拍到新照片时发一条系统通知（转发到电脑）"
                    trail={
                      <Switch on={albumWatchOn} onChange={toggleWatch} label="新照片提醒" />
                    }
                  />
                </div>
                <button className="btn btn-ghost btn-sm" onClick={resetAlbum} title="从头重新加载相册">
                  刷新
                </button>
              </div>

              {(albumErr || albumNote) && (
                <div style={{ padding: '0 16px 12px' }}>
                  <Notice
                    tone={albumErr ? 'err' : 'info'}
                    icon={albumErr ? <IconAlert size={16} /> : <IconInfo size={16} />}
                  >
                    {albumErr || albumNote}
                  </Notice>
                </div>
              )}

              {albumGroups.length === 0 && !albumBusy && !albumErr && (
                <Empty
                  icon={<IconCamera size={19} />}
                  title="还没有加载到照片"
                  hint="确认手机已连上、且在手机端允许电脑访问相册"
                />
              )}

              {albumGroups.map((g) => (
                <div key={g.label} style={{ padding: '4px 16px 12px' }}>
                  <div
                    className="faint"
                    style={{ fontSize: 11.5, margin: '4px 0 8px' }}
                  >
                    {g.label}
                  </div>
                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))',
                      gap: 6,
                    }}
                  >
                    {g.items.map((e) => (
                      <AlbumTile
                        key={e.id}
                        entry={e}
                        failed={albumFailedThumbs.current.has(e.id)}
                        onVisible={requestThumb}
                        onRetry={retryThumb}
                        onOpen={pullOriginal}
                        pulling={albumPull === e.name}
                      />
                    ))}
                  </div>
                </div>
              ))}

              {/* 哨兵：滚进视口且还有下一页时自动加载 */}
              <div ref={albumSentinelRef} style={{ height: 1 }} />
              <div style={{ padding: '8px 16px 14px' }} className="faint">
                <span style={{ fontSize: 11.5 }}>
                  {albumBusy
                    ? '正在加载…'
                    : albumDone
                      ? `已到相册最老处 · 共 ${albumEntries.length} 张`
                      : '继续滚动加载更早的照片'}
                </span>
              </div>
            </>
          )}
        </div>
      </Section>

      {/* ————— 浏览手机文件（0x44–0x47）————— */}
      <Section index={3} title="浏览手机文件" note="电脑直接翻手机存储 · 取走文件">
        <div className="card">
          {browserMode ? (
            <div style={{ padding: 16 }}>
              <Notice tone="info" icon={<IconInfo size={16} />}>
                浏览手机文件需要桌面客户端，浏览器面板暂不支持。
              </Notice>
            </div>
          ) : (
            <>
              {/* 面包屑：手机路径映射成「内部存储 / Download / …」，tap 任意一段回跳 */}
              <div
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  alignItems: 'center',
                  gap: 6,
                  padding: '14px 16px 8px',
                  fontSize: 12.5,
                  color: 'var(--on-tertiary, #888)',
                }}
              >
                <button
                  className="btn btn-quiet btn-sm"
                  onClick={() => browsePhone('')}
                  disabled={phoneBusy}
                >
                  <IconFolder size={14} />
                  手机存储
                </button>
                {(() => {
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
                          className="btn btn-quiet btn-sm"
                          onClick={() => browsePhone(target)}
                          disabled={i === segs.length - 1 || phoneBusy}
                        >
                          {seg}
                        </button>
                      </React.Fragment>
                    );
                  });
                })()}
              </div>

              {phoneErr && (
                <div style={{ padding: '0 16px 12px' }}>
                  <Notice tone="err" icon={<IconAlert size={16} />}>
                    {phoneErr}
                  </Notice>
                </div>
              )}

              <div className="rows" style={{ borderTop: '1px solid var(--line-soft)' }}>
                {phoneBusy && (
                  <Empty icon={<IconFolder size={19} />} title="正在读取手机目录…" />
                )}
                {!phoneBusy && !phoneErr && phoneEntries.length === 0 && (
                  <Empty icon={<IconFolder size={19} />} title="这个目录是空的" />
                )}
                {phoneEntries.map((e) => {
                  const full = childPathOf(phonePath, e.name);
                  return e.isDir ? (
                    <Row
                      key={'d:' + e.name}
                      lead={<IconFolder size={17} />}
                      title={e.name}
                      sub="文件夹"
                      onClick={() => browsePhone(full)}
                    />
                  ) : (
                    <Row
                      key={'f:' + e.name}
                      lead={<IconFileDown size={17} />}
                      title={e.name}
                      sub={fmtSize(e.size)}
                      trail={
                        <button
                          className="btn btn-ghost btn-sm"
                          disabled={!!phonePull}
                          onClick={() => {
                            setPhonePull(e.name);
                            setPhoneErr('');
                            bridge.act('phonefile.pull', { path: full }).catch((err: any) => {
                              setPhonePull(null);
                              setPhoneErr(err?.message || String(err));
                            });
                          }}
                          title="取回这个文件（落到「手机直传」的收到列表）"
                        >
                          {phonePull === e.name ? '取回中…' : '取到电脑'}
                        </button>
                      }
                    />
                  );
                })}
              </div>

              <div style={{ padding: '10px 16px 14px' }} className="faint">
                <span style={{ fontSize: 11.5 }}>
                  取回的文件与手机发来的文件落在同一目录（下载\AllPeriph）；只读，不会改动手机里的文件。
                </span>
              </div>
            </>
          )}
        </div>
      </Section>
    </>
  );
};
