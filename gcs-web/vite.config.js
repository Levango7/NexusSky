import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 后端地址可被环境变量覆盖（默认 8080）：
//  - E2E（Playwright）用 AF_BACKEND_ORIGIN，因为本机 8080 常被常驻容器占用；
//  - preview 也挂同一份 proxy——E2E 跑的是真实构建产物，需要能打真后端，
//    否则请求会落到 preview 自己的静态目录上变成 404（假绿的最短路径）。
const backend = process.env.AF_BACKEND_ORIGIN || 'http://localhost:8080'
const wsTarget = backend.replace(/^http/, 'ws')

const proxy = {
  '/api': { target: backend, changeOrigin: true },
  '/ws': { target: wsTarget, ws: true },
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy,
  },
  preview: {
    port: 4173,
    proxy,
  },
})