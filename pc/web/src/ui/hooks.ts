import React from 'react';
import { AudioDevice, HostPlatform, bridge } from '../api/bridge';

/**
 * 交互 hook 集合。
 *
 * 面板的所有动作都是「发一条 act，等宿主 400ms 推流回来」—— 如果不在本地先乐观翻转，
 * 用户点开关会有一拍延迟，这是旧面板「点了像没反应」的主要原因之一。
 * 这里统一成：**先翻转 → 发请求 → 失败回滚并留一句人话**。
 */

/** 开关：乐观翻转 + 失败回滚 + 与服务端状态自动对齐 */
export function useToggle(serverOn: boolean, run: (next: boolean) => Promise<unknown>) {
  const [on, setOn] = React.useState(serverOn);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');

  React.useEffect(() => {
    setOn(serverOn);
  }, [serverOn]);

  const toggle = React.useCallback(
    async (next: boolean) => {
      setOn(next);
      setBusy(true);
      setErr('');
      try {
        await run(next);
      } catch (e: any) {
        setOn(!next);
        setErr(e?.message || '操作失败');
      } finally {
        setBusy(false);
      }
    },
    [run],
  );

  return { on, busy, err, toggle, setErr };
}

/** 一次性动作（按钮）：busy + error，不外抛 */
export function useAction() {
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');

  const run = React.useCallback(async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setErr('');
    try {
      await fn();
      return true;
    } catch (e: any) {
      setErr(e?.message || '操作失败');
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  return { busy, err, run, setErr };
}

/* ———————————————————————————— 主题 ———————————————————————————— */

export type ThemePref = 'auto' | 'light' | 'dark';
const THEME_KEY = 'apx.panel.theme';

/**
 * 主题偏好：auto（跟随系统）/ light / dark。
 *
 * 首帧由 index.html 里的内联脚本先行落定（避免闪白），这里只负责切换与持久化。
 * 与手机端一致：明暗两套是**同一套令牌的两份取值**，不做「深色只换背景」的半成品。
 */
export function useTheme() {
  const [pref, setPref] = React.useState<ThemePref>(() => {
    try {
      const v = localStorage.getItem(THEME_KEY);
      return v === 'light' || v === 'dark' ? v : 'auto';
    } catch {
      return 'auto';
    }
  });

  React.useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = () => {
      const dark = pref === 'dark' || (pref === 'auto' && mq.matches);
      document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    };
    apply();
    mq.addEventListener('change', apply);
    try {
      localStorage.setItem(THEME_KEY, pref);
    } catch {
      /* 隐私模式写不进去：本次会话照常生效 */
    }
    return () => mq.removeEventListener('change', apply);
  }, [pref]);

  return { pref, setPref };
}

/* ———————————————————————————— 音频端点 ———————————————————————————— */

/**
 * 设备下拉为空时给用户的一句人话 —— 按平台分叉。
 *
 * 同一个空列表在三个平台上是三种完全不同的原因，用一句「本机不支持枚举播放设备」
 * 糊住它们，用户只会以为功能坏了（issue #52 第三条反馈）：
 *   · Windows：枚举失败 = 本机确实没给到端点，保持原文案；
 *   · macOS：Process Tap 采的是全局进程组混音，不挂具体端点 —— 设计边界，不是缺陷；
 *   · Linux：音频端点枚举尚未实现。
 * 三种情况下手机那一侧都照常出声/收音（无线音频是应用内 PCM 直连）。
 */
export function audioDeviceHint(platform: HostPlatform, kind: 'play' | 'capture'): string {
  if (platform === 'macos') {
    return kind === 'play'
      ? '无线音频由本应用直接播放（局域网 PCM），不注册成系统音频设备；手机那边的扬声器照常出声。'
      : '手机麦克风由本应用直接采集，不注册成系统音频设备；这里只决定声音渲染到 Mac 的哪个端点。';
  }
  if (platform === 'linux') {
    return '本平台尚未实现音频端点枚举；声音经本应用直连传输，不经过系统音频设备。';
  }
  return '（本机不支持枚举播放设备）';
}

/**
 * 播放设备列表（音箱 / 麦克风下拉的数据源）。
 * 与后端 query `audioDevices`、Win32 原生面板 refreshSpeakerCombo 同源；
 * 空表不编造条目，只把原因讲清楚（见 [audioDeviceHint]）。
 */
export function useAudioDevices() {
  const [devices, setDevices] = React.useState<AudioDevice[]>([]);
  const [defaultName, setDefaultName] = React.useState('');
  const [loaded, setLoaded] = React.useState(false);

  const refresh = React.useCallback(() => {
    let alive = true;
    bridge
      .audioDevices()
      .then((r) => {
        if (!alive) return;
        setDevices(r.devices);
        setDefaultName(r.defaultName);
      })
      .catch(() => {
        if (!alive) return;
        setDevices([]);
        setDefaultName('');
      })
      .finally(() => {
        if (alive) setLoaded(true);
      });
    return () => {
      alive = false;
    };
  }, []);

  React.useEffect(() => refresh(), [refresh]);

  return { devices, defaultName, loaded, refresh };
}

/* ———————————————————————————— 格式化 ———————————————————————————— */

export function fmtSize(n: number): string {
  if (!n || n < 0) return '0 B';
  if (n >= 1024 * 1024 * 1024) return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${n} B`;
}

export function fileNameOf(p: string): string {
  return p.split(/[\\/]/).pop() || p;
}
