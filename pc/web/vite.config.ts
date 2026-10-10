import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

// WebView2 加载方式：Navigate 到 dist/index.html（file:// URL）。
// base 必须是相对路径 "./"，否则所有资源会变成绝对路径 "/" 下找不到。
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
  },
  resolve: {
    alias: { '@': resolve(__dirname, 'src') },
  },
});
