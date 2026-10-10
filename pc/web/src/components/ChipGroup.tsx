import React from 'react';

interface Props {
  options: { label: string; value: string }[];
  value: string;
  onChange: (v: string) => void;
  className?: string;
}

/**
 * 分段选择器（Chip Group）—— 统一视觉：平背景 + 3px padding chip 悬浮即激活。
 * impeccable anti-pattern：不要装饰性 sparkline / 不要泛阴影。
 */
export const ChipGroup: React.FC<Props> = ({ options, value, onChange, className }) => {
  return (
    <div className={`chip-group ${className ?? ''}`}>
      {options.map(opt => (
        <button
          key={opt.value}
          className="chip"
          data-active={opt.value === value}
          onClick={() => onChange(opt.value)}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
};
