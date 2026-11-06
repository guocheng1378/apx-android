import React from 'react';
import { IconChevronRight } from './icons';

/**
 * 界面基元 —— 与手机端 `ApComponents` 一一对应：
 *   Section ↔ 小标题 + 卡片   Row ↔ ItemRow / SwitchRow   Fold ↔ FoldGroup
 *   Segmented ↔ TabChip 组    Badge/Pill ↔ Pill
 *
 * 规则：页面里不直接拼卡片与行，一律经这里 —— 否则「改一次圆角/间距要翻七八个文件」
 * 的老问题会立刻回来（手机端就是这么收敛的，见 ApComponents 的 KDoc）。
 */

type CSSVars = React.CSSProperties & Record<string, string | number>;

/** 分组：小标题 + 白卡。index 决定入场节拍（逐组上浮）。 */
export const Section: React.FC<{
  title?: string;
  note?: string;
  index?: number;
  children: React.ReactNode;
}> = ({ title, note, index = 0, children }) => (
  <section className="section rise" style={{ '--i': index } as CSSVars}>
    {(title || note) && (
      <div className="section-head">
        {title && <span className="section-title">{title}</span>}
        {note && <span className="section-note">{note}</span>}
      </div>
    )}
    {children}
  </section>
);

export const Card: React.FC<{ children: React.ReactNode; className?: string }> = ({
  children,
  className,
}) => <div className={`card ${className ?? ''}`}>{children}</div>;

/** 一行：标题 + 说明 + 尾部控件。给了 onClick 就整行可点（键盘可达）。 */
export const Row: React.FC<{
  title?: React.ReactNode;
  sub?: React.ReactNode;
  lead?: React.ReactNode;
  leadTone?: 'accent' | 'plain';
  trail?: React.ReactNode;
  onClick?: () => void;
  ariaLabel?: string;
}> = ({ title, sub, lead, leadTone = 'plain', trail, onClick, ariaLabel }) => {
  const inner = (
    <>
      {lead && <span className={`row-lead ${leadTone === 'accent' ? 'accent' : ''}`}>{lead}</span>}
      <span className="row-main">
        {title && <span className="row-title">{title}</span>}
        {sub && <span className="row-sub">{sub}</span>}
      </span>
      {trail && <span className="row-trail">{trail}</span>}
    </>
  );
  if (onClick) {
    return (
      <button className="row is-tappable" onClick={onClick} aria-label={ariaLabel}>
        {inner}
      </button>
    );
  }
  return <div className="row">{inner}</div>;
};

/** 折叠分组：默认收起，组头回显计数（对齐手机端「不打开不出现」）。 */
export const Fold: React.FC<{
  title: string;
  sub?: string;
  lead?: React.ReactNode;
  defaultOpen?: boolean;
  /** 首次展开时回调：内容要扫盘/发请求的（如日志目录）用它在展开时才加载 */
  onOpen?: () => void;
  children: React.ReactNode;
}> = ({ title, sub, lead, defaultOpen = false, onOpen, children }) => {
  const [open, setOpen] = React.useState(defaultOpen);
  return (
    <div>
      <button
        className="fold-head"
        data-open={open}
        onClick={() => {
          const next = !open;
          setOpen(next);
          if (next) onOpen?.();
        }}
        aria-expanded={open}
      >
        {lead && <span className="row-lead">{lead}</span>}
        <span className="row-main">
          <span className="row-title">{title}</span>
          {sub && <span className="row-sub">{sub}</span>}
        </span>
        <IconChevronRight size={16} className="fold-chevron" />
      </button>
      {open && <div className="fold-body">{children}</div>}
    </div>
  );
};

export const Switch: React.FC<{
  on: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  busy?: boolean;
  label?: string;
}> = ({ on, onChange, disabled, busy, label }) => (
  <span
    className="switch"
    role="switch"
    aria-checked={on}
    aria-label={label}
    aria-busy={busy}
    data-on={on}
    data-busy={!!busy}
    data-disabled={!!disabled}
    tabIndex={disabled ? -1 : 0}
    onClick={() => {
      if (disabled || busy) return;
      onChange(!on);
    }}
    onKeyDown={(e) => {
      if (disabled || busy) return;
      if (e.key === ' ' || e.key === 'Enter') {
        e.preventDefault();
        onChange(!on);
      }
    }}
  >
    <span className="thumb" />
  </span>
);

export const Segmented: React.FC<{
  options: { value: string; label: string }[];
  value: string;
  onChange: (v: string) => void;
  disabled?: boolean;
}> = ({ options, value, onChange, disabled }) => (
  <div className="seg" role="group">
    {options.map((o) => (
      <button
        key={o.value}
        data-active={o.value === value}
        disabled={disabled}
        onClick={() => onChange(o.value)}
        aria-pressed={o.value === value}
      >
        {o.label}
      </button>
    ))}
  </div>
);

export const Chip: React.FC<{
  active?: boolean;
  onClick?: () => void;
  children: React.ReactNode;
  title?: string;
}> = ({ active, onClick, children, title }) => (
  <button className="chip" data-active={!!active} onClick={onClick} title={title}>
    {children}
  </button>
);

export const Badge: React.FC<{
  tone?: 'ok' | 'warn' | 'err' | 'accent' | 'idle';
  live?: boolean;
  children: React.ReactNode;
}> = ({ tone = 'idle', live, children }) => (
  <span className="badge" data-tone={tone}>
    <span className={`dot ${live ? 'live' : ''}`} />
    {children}
  </span>
);

export const Metrics: React.FC<{ items: { k: string; v: React.ReactNode; dim?: boolean }[] }> = ({
  items,
}) => (
  <div className="metrics">
    {items.map((it) => (
      <div className="metric" key={it.k}>
        <div className="k">{it.k}</div>
        <div className={`v ${it.dim ? 'dim' : ''}`}>{it.v}</div>
      </div>
    ))}
  </div>
);

export const Meter: React.FC<{ value: number; live?: boolean }> = ({ value, live }) => (
  <div className="meter" role="meter" aria-valuenow={Math.round(value * 100)}>
    <div
      className="meter-fill"
      data-live={!!live}
      style={{ width: `${Math.max(0, Math.min(1, value)) * 100}%` }}
    />
  </div>
);

export const Notice: React.FC<{
  tone?: 'info' | 'warn' | 'err';
  title?: string;
  icon?: React.ReactNode;
  children?: React.ReactNode;
  actions?: React.ReactNode;
}> = ({ tone = 'info', title, icon, children, actions }) => (
  <div className="notice" data-tone={tone}>
    {icon && <span style={{ flex: 'none', marginTop: 1 }}>{icon}</span>}
    <div className="notice-body">
      {title && <div className="notice-title">{title}</div>}
      {children && <div className="notice-line">{children}</div>}
      {actions && <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>{actions}</div>}
    </div>
  </div>
);

export const Spinner: React.FC<{ size?: number }> = ({ size = 14 }) => (
  <svg
    className="spin"
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2.4}
    strokeLinecap="round"
    aria-hidden="true"
  >
    <path d="M12 3.4a8.6 8.6 0 1 1-6.1 2.5" opacity="0.9" />
  </svg>
);

export const Empty: React.FC<{ icon?: React.ReactNode; title: string; hint?: string }> = ({
  icon,
  title,
  hint,
}) => (
  <div className="empty">
    {icon && <div className="empty-icon">{icon}</div>}
    <div>{title}</div>
    {hint && <div style={{ fontSize: 11.5 }}>{hint}</div>}
  </div>
);
