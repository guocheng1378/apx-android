import React from 'react';
import { IconDisplay, IconOverview, IconSettings, IconSound, IconTransfer } from './ui/icons';

/** 页面标识（左侧导航轨 / 快捷键 1..5 / 顶栏标题共用一份） */
export type PageId = 'overview' | 'display' | 'sound' | 'transfer' | 'settings';

export interface PageMeta {
  id: PageId;
  /** 导航轨文字（两字，保证 88px 轨道不折行） */
  label: string;
  /** 顶栏大标题 */
  title: string;
  /** 顶栏副标题：一句话说明这页能干什么 */
  sub: string;
  icon: React.FC<{ size?: number }>;
}

export const PAGES: PageMeta[] = [
  {
    id: 'overview',
    label: '概览',
    title: '概览',
    sub: '手机与本机的连接、外设状态与用量',
    icon: IconOverview,
  },
  {
    id: 'display',
    label: '画面',
    title: '画面',
    sub: '把本机桌面投到手机；手机摄像头回传到本机',
    icon: IconDisplay,
  },
  {
    id: 'sound',
    label: '声音',
    title: '声音',
    sub: '本机系统声音送手机；手机麦克风回本机',
    icon: IconSound,
  },
  {
    id: 'transfer',
    label: '传输',
    title: '传输',
    sub: '互传文件（LocalSend）与手机直传',
    icon: IconTransfer,
  },
  {
    id: 'settings',
    label: '设置',
    title: '设置',
    sub: '外观、启动、悬浮球与日志',
    icon: IconSettings,
  },
];

export function pageMeta(id: PageId): PageMeta {
  return PAGES.find((p) => p.id === id) ?? PAGES[0];
}
