import React from 'react';

/**
 * 图标集：全部手写 SVG（24 网格、1.6 描边、圆头圆角）。
 *
 * 为什么不引图标库：面板是本地页面，多一个依赖就多一份体积与锁文件风险；
 * 而且 HyperOS 那套观感靠的是「细描边 + 圆头 + 统一网格」，自己画反而更一致。
 */
type IconProps = React.SVGProps<SVGSVGElement> & { size?: number };

const Svg: React.FC<IconProps & { children: React.ReactNode }> = ({ size = 20, children, ...rest }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={1.6}
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
    {...rest}
  >
    {children}
  </svg>
);

/** 应用标记：一张卡片 + 一条信号弧（「外设都挂在一台机上」） */
export const IconLogo: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <rect x="3.2" y="6.4" width="11.2" height="11.2" rx="3.4" />
    <circle cx="8.8" cy="12" r="1.7" fill="currentColor" stroke="none" />
    <path d="M17.4 8.6a5 5 0 0 1 0 6.8" />
    <path d="M20.1 5.9a8.8 8.8 0 0 1 0 12.2" />
  </Svg>
);

export const IconOverview: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <rect x="3" y="3.6" width="7.6" height="7.6" rx="2.4" />
    <rect x="13.4" y="3.6" width="7.6" height="7.6" rx="2.4" />
    <rect x="3" y="13.4" width="7.6" height="7.6" rx="2.4" />
    <path d="M17.2 13.6v7.2M13.6 17.2h7.2" />
  </Svg>
);

export const IconDisplay: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <rect x="2.6" y="4.4" width="18.8" height="12.4" rx="2.8" />
    <path d="M8.6 20.4h6.8M12 16.8v3.6" />
    <path d="M6.8 11.2l2.4-2.6 2.2 2.2 2-2.2 3.8 4" />
  </Svg>
);

export const IconSound: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M4 9.4h3.1L12 5.6v12.8L7.1 14.6H4z" />
    <path d="M15.6 9.2a3.8 3.8 0 0 1 0 5.6" />
    <path d="M18.2 6.8a7.4 7.4 0 0 1 0 10.4" />
  </Svg>
);

export const IconMic: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <rect x="9.2" y="3.2" width="5.6" height="10" rx="2.8" />
    <path d="M5.8 11.4a6.2 6.2 0 0 0 12.4 0" />
    <path d="M12 17.6v3.2M9.2 20.8h5.6" />
  </Svg>
);

export const IconTransfer: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M4.6 8.4h13.2l-3-3.2" />
    <path d="M19.4 15.6H6.2l3 3.2" />
  </Svg>
);

export const IconSettings: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M4 7.4h5.2M13.4 7.4H20" />
    <path d="M4 16.6h3.6M11.8 16.6H20" />
    <circle cx="11.2" cy="7.4" r="2.2" />
    <circle cx="9.4" cy="16.6" r="2.2" />
  </Svg>
);

export const IconWifi: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M3.4 9.2a12.6 12.6 0 0 1 17.2 0" />
    <path d="M6.6 12.6a8 8 0 0 1 10.8 0" />
    <path d="M9.8 16a3.4 3.4 0 0 1 4.4 0" />
    <circle cx="12" cy="19.2" r="1" fill="currentColor" stroke="none" />
  </Svg>
);

export const IconBluetooth: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M8 7.6l8 8.8-4 3.6V4l4 3.6-8 8.8" />
  </Svg>
);

export const IconUsb: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M12 21V6.4" />
    <path d="M12 6.4l-2.6 3M12 6.4l2.6 3" />
    <circle cx="12" cy="3.6" r="1.4" />
    <path d="M12 13.6l-3.4-3.2V8.2" />
    <rect x="6.4" y="5.4" width="4.4" height="2.8" rx="1" />
    <path d="M12 17.2l3.4-3v-2.4" />
    <circle cx="15.4" cy="10" r="1.8" />
  </Svg>
);

export const IconCamera: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M3 8.4a2.4 2.4 0 0 1 2.4-2.4h1.3l1.2-1.8h6.2l1.2 1.8h1.3A2.4 2.4 0 0 1 21 8.4v8.2a2.4 2.4 0 0 1-2.4 2.4H5.4A2.4 2.4 0 0 1 3 16.6z" />
    <circle cx="12" cy="12.4" r="3.4" />
  </Svg>
);

export const IconLink: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M10.2 13.8a3.8 3.8 0 0 0 5.4 0l2.2-2.2a3.8 3.8 0 0 0-5.4-5.4l-1 1" />
    <path d="M13.8 10.2a3.8 3.8 0 0 0-5.4 0l-2.2 2.2a3.8 3.8 0 0 0 5.4 5.4l1-1" />
  </Svg>
);

export const IconFolder: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M3.4 7.6a2.4 2.4 0 0 1 2.4-2.4h3.1l2 2.4h7.3a2.4 2.4 0 0 1 2.4 2.4v7.6a2.4 2.4 0 0 1-2.4 2.4H5.8a2.4 2.4 0 0 1-2.4-2.4z" />
  </Svg>
);

export const IconFileUp: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M13.4 3.4H7.4A2.4 2.4 0 0 0 5 5.8v12.4a2.4 2.4 0 0 0 2.4 2.4h9.2a2.4 2.4 0 0 0 2.4-2.4V9z" />
    <path d="M13.2 3.4V9h5.8" />
    <path d="M12 17.4v-5.2M9.8 14.2L12 12l2.2 2.2" />
  </Svg>
);

export const IconFileDown: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M13.4 3.4H7.4A2.4 2.4 0 0 0 5 5.8v12.4a2.4 2.4 0 0 0 2.4 2.4h9.2a2.4 2.4 0 0 0 2.4-2.4V9z" />
    <path d="M13.2 3.4V9h5.8" />
    <path d="M12 12v5.4M9.8 15.2L12 17.4l2.2-2.2" />
  </Svg>
);

export const IconChevronRight: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M9.4 5.6l6.4 6.4-6.4 6.4" />
  </Svg>
);

export const IconChevronDown: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M5.6 9.4l6.4 6.4 6.4-6.4" />
  </Svg>
);

export const IconRefresh: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M20 12a8 8 0 1 1-2.6-5.9" />
    <path d="M20.2 4.4v4.2h-4.2" />
  </Svg>
);

export const IconX: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M6.4 6.4l11.2 11.2M17.6 6.4L6.4 17.6" />
  </Svg>
);

export const IconSend: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M20.6 3.8L3.9 10.4l6.1 2.1 2.1 6.1z" />
    <path d="M20.6 3.8l-10.6 8.7" />
  </Svg>
);

export const IconAlert: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M12 4.2l8.4 15H3.6z" />
    <path d="M12 9.6v4.2" />
    <circle cx="12" cy="16.8" r="0.9" fill="currentColor" stroke="none" />
  </Svg>
);

export const IconInfo: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="8.6" />
    <path d="M12 11v5.4" />
    <circle cx="12" cy="8" r="0.9" fill="currentColor" stroke="none" />
  </Svg>
);

export const IconSun: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="4.2" />
    <path d="M12 2.8v2.2M12 19v2.2M2.8 12h2.2M19 12h2.2M5.6 5.6l1.6 1.6M16.8 16.8l1.6 1.6M18.4 5.6l-1.6 1.6M7.2 16.8l-1.6 1.6" />
  </Svg>
);

export const IconMoon: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M20 14.4A8.4 8.4 0 0 1 9.6 4a8.6 8.6 0 1 0 10.4 10.4z" />
  </Svg>
);

export const IconAuto: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <rect x="2.8" y="4.4" width="18.4" height="12.6" rx="2.6" />
    <path d="M8.4 20.6h7.2" />
    <path d="M12 4.4v12.6" strokeDasharray="2.4 2.4" />
  </Svg>
);

export const IconMouse: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <rect x="6.6" y="2.8" width="10.8" height="18.4" rx="5.4" />
    <path d="M12 7.4v3.2" />
  </Svg>
);

export const IconKeyboard: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <rect x="2.6" y="6" width="18.8" height="12" rx="2.8" />
    <path d="M6.4 10h.01M9.8 10h.01M13.2 10h.01M16.6 10h.01M8 14h8" />
  </Svg>
);

export const IconWave: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M3 12h2.4l1.8-5.6 2.4 11.2 2.2-8 2 8 1.8-5.6H21" />
  </Svg>
);

export const IconPower: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M12 3.4v8" />
    <path d="M6.6 6.6a8 8 0 1 0 10.8 0" />
  </Svg>
);

export const IconPlay: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M7.4 4.8l11 7.2-11 7.2z" />
  </Svg>
);

export const IconStop: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <rect x="5.6" y="5.6" width="12.8" height="12.8" rx="3" />
  </Svg>
);

export const IconCheck: React.FC<IconProps> = (p) => (
  <Svg {...p}>
    <path d="M5 12.6l4.4 4.4L19 7.4" />
  </Svg>
);
