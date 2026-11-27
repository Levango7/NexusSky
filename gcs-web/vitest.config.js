import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'

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
    // 测试主战场放顶层 test/（保持 src 只有产品代码），但 src/ 下历史上
    // 已有两处测试（api.test.js / components/Scene3DUtils.test.js，2026-10-02
    // 诚实化轮建立）——include 漏掉 src/ 会让它们静默不跑，门禁变假绿。
    // eslint-disable-next-line no-useless-escape
    include: ['test/**/*.test.{js,jsx}', 'src/**/*.test.{js,jsx}'],
    coverage: {
      provider: 'v8',
      // json-summary 供 scripts/check-frontend-coverage-threshold.py
      // 做「声明阈值 == 实测地板」一致性核对（与 Java 侧
      // ci-coverage-threshold.sh 同源职责）。
      reporter: ['text', 'lcov', 'json-summary'],
      include: ['src/**/*.{js,jsx}'],
      // 阈值从 coverage-threshold.json 读，与 scripts/check-frontend-coverage-threshold.py
      // 共用同一份声明（单真值源）。策略口径 = 实测向下取整到 5%：
      // 覆盖率上涨时阈值同步抬高，防止「实测涨、门槛不动」让门禁慢慢失效。
      // 2026-11-27 建立（#144）：此前阈值刻意为空（基线为零，先建测试再卡门槛）。
      thresholds: {
        ...JSON.parse(readFileSync('./coverage-threshold.json', 'utf-8')),
      },
    },
  },
})
