/** @type {import('tailwindcss').Config} */
//
// —— 反 slop 设计令牌（impeccable + taste-skill）——
//
// 色彩原则（impeccable anti-pattern）：
//   ✗ 不要纯黑/纯灰 —— 全部带 tint（蓝/紫/红向的轻微偏移）
//   ✗ 不要 gray text on colored backgrounds —— primary button 文字必须纯白
//   ✗ 不要 Tailwind *-500 直接用 —— 全部自定义色值
// 字体原则（taste-skill）：
//   ✗ 不要 Arial/Inter/system default —— 用 Instrument Sans（现代 sans）+ Geist Mono（数据）
// 间距原则：
//   8pt base（Tailwind 默认 4px 粒度 × 2 = 8pt）—— 所有间距都是 8 的倍数
// 圆角原则：
//   统一 16px（card）/ 12px（badge）/ 8px（chip）—— 没有 6px/10px/14px
// 阴影原则：
//   ✗ 不要泛阴影（impeccable anti-pattern：generic drop shadows）—— 只有 card 和 primary 有 shadow-sm
// 动画原则：
//   ✗ 不要 bounce/elastic easing（impeccable：feels dated）—— 统一 ease-out-expo
//
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Instrument Sans"', '"Inter Tight"', 'system-ui', 'sans-serif'],
        mono: ['"Geist Mono"', '"JetBrains Mono"', 'monospace'],
      },
      colors: {
        bg: '#F5F6F8',              // 页面底（比 Android #F2F3F5 略冷）
        surface: '#FFFFFF',         // 卡片底
        stroke: '#E5E7EB',          // 卡片描边（比 Android #EDEDED 深一点）
        primary: {
          DEFAULT: '#2B6CFF',       // 主色（Android #3482FF 降饱和 + 升明度，现代感）
          hover: '#4B86FF',
          pressed: '#1F54D8',
          soft: '#E8F0FF',
        },
        on: {
          surface: '#1A1D21',       // 正文（带蓝 tint，不是纯黑）
          variant: '#5B6068',       // 次要字（带蓝 tint）
          tertiary: '#949AA4',      // 辅助字
        },
        state: {
          ok: '#17A66C',            // 绿（Android #12B76A 降饱和）
          warn: '#D98C00',          // 黄（Android #F79009 降饱和）
          error: '#E5484D',         // 红（Android #F04438 降饱和）
          idle: '#A1A8B3',          // 灰（带蓝 tint）
        },
      },
      borderRadius: {
        'card': '16px',
        'badge': '12px',
        'chip': '8px',
        'btn': '10px',
      },
      boxShadow: {
        'card': '0 1px 2px rgba(26,29,33,0.04), 0 1px 3px rgba(26,29,33,0.06)',
        'btn-primary': '0 2px 4px rgba(43,108,255,0.24)',
      },
      animation: {
        'fade-in': 'fadeIn 200ms ease-out-expo',
        'slide-up': 'slideUp 240ms ease-out-expo',
      },
      keyframes: {
        fadeIn: { '0%': { opacity: '0' }, '100%': { opacity: '1' } },
        slideUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
    },
  },
  plugins: [],
};
