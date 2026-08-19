import react from '@vitejs/plugin-react';
import path from 'node:path';
import { defineConfig } from 'vite';

// GagneFlow 前端开发配置
// 开发期: 5173 直连, /api 代理到后端 9900 (免 CORS)
// 生产期: npm run build -> dist/ 拷回 src/main/resources/static/ 同源部署
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:9900',
        changeOrigin: true,
      },
      '/milvus': {
        target: 'http://localhost:9900',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1024,
    rollupOptions: {
      output: {
        // 稳定 chunk 名, 避免拷回 static/ 后文件名漂移
        entryFileNames: 'assets/[name].[hash].js',
        chunkFileNames: 'assets/[name].[hash].js',
        assetFileNames: 'assets/[name].[hash][extname]',
      },
    },
  },
});
