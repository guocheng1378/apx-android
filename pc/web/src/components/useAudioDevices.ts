import React from 'react';
import { bridge, AudioDevice } from '../api/bridge';
import type { HostPlatform } from '../api/bridge';

/**
 * 设备下拉为空时给用户的一句人话 —— 按平台分叉。
 *
 * 同一个空列表在三个平台上是三种完全不同的原因，用一句「本机不支持枚举播放设备」
 * 糊住它们，用户只会以为功能坏了（issue #52 第三条反馈）：
 *   · Windows：枚举失败 = 本机确实没给到端点，保持原文案；
 *   · macOS：Process Tap 采的是全局进程组混音，链路里没有任何一端注册成 CoreAudio 设备，
 *     控制中心/声音偏好里也就永远看不到这台手机 —— 这是设计边界，不是缺陷；
 *   · Linux：音频端点枚举尚未实现。
 * 三种情况下手机那一侧都照常出声/收音，因为无线音频本来就是应用内 PCM 直连。
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
 * 播放设备列表（音箱 / 麦克风设备下拉的数据源）。
 *
 * 与后端 query `audioDevices`、Win32 原生面板 refreshSpeakerCombo 同源：
 *   · Windows：真实播放端点；
 *   · macOS：Process Tap 采全局混音、不挂具体端点；Linux：未实现 —— 两者都是空表。
 * 空表不编造条目，只把原因讲清楚（见上面 [audioDeviceHint]）。
 */
export function useAudioDevices() {
  const [devices, setDevices] = React.useState<AudioDevice[]>([]);
  const [defaultName, setDefaultName] = React.useState('');

  React.useEffect(() => {
    let alive = true;
    bridge.audioDevices()
      .then((r) => {
        if (!alive) return;
        setDevices(r.devices);
        setDefaultName(r.defaultName);
      })
      .catch(() => {
        // 查询失败（宿主没起 / 协议不认识）——按「枚举不到」处理，不假装有列表
        if (!alive) return;
        setDevices([]);
        setDefaultName('');
      });
    return () => { alive = false; };
  }, []);

  return { devices, defaultName };
}
