/** @type {import('tailwindcss').Config} */
//
// 只保留「布局类」工具（flex / grid / gap / min-w-0 …）；
// 所有视觉（颜色、圆角、阴影、动效）都写在 src/index.css 的设计令牌里。
// 这样做的理由：面板要跟随明暗主题切换，颜色必须是 CSS 变量而不是编译期常量，
// 而 Tailwind 的 `bg-xxx/10` 透明度语法对 var() 不成立 —— 混用迟早出现「这块不换主题」。
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['var(--font-sans)'],
        display: ['var(--font-display)'],
        mono: ['var(--font-mono)'],
      },
      colors: {
        surface: 'var(--surface)',
        ink: 'var(--ink)',
        line: 'var(--line)',
        accent: 'var(--accent)',
      },
    },
  },
  plugins: [],
};
