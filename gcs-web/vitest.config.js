import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// 独立的 vitest 配置，不污染 vite.config.js（构建用的那份保持原样）。
//
// 为什么不复用 vite.config.js：那会把 test 字段带进 `vite build`，
// 两者的运行目标不同（jsdom vs 浏览器），混在一起容易在将来某次构建里
// 被无意影响。
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./test/setup.js'],
    // 测试放顶层 test/ 而不是 src/，保持 src 只有产品代码
    // （src/**/*.test.* 会被 check-frontend.cjs 和 eslint 一起扫到）
    include: ['test/**/*.test.{js,jsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{js,jsx}'],
      // 覆盖率阈值暂不设：本仓前端覆盖率基线为零（2026-10-01 前无任何测试），
      // 一上来就卡阈值只会让 CI 立刻变红。先把测试建起来，基线由 CI 实测产出，
      // 之后再按模块逐步抬高。见 README「测试规模」一节。
    },
  },
})
