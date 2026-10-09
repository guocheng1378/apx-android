import React from 'react';

interface Props {
  on: boolean;
  onChange: (v: boolean) => void;
  className?: string;
}

/** MIUI 风格开关 —— 无动画 bounce/elastic，统一 ease-out-expo（impeccable anti-pattern）。 */
export const Switch: React.FC<Props> = ({ on, onChange, className }) => {
  return (
    <div
      className={`switch ${className ?? ''}`}
      data-on={on}
      onClick={() => onChange(!on)}
      role="switch"
      aria-checked={on}
    >
      <span className="switch-thumb" />
    </div>
  );
};
