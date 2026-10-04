import { defineConfig, devices } from '@playwright/test'

// Playwright E2E（CI7 后半边：vitest 管组件逻辑，这个管「真浏览器 + 真后端」的集成面）。
//
// 为什么不用 mock 拦截：GCS 的价值在「WebSocket 遥测流 → 面板渲染」这条链，
// route 拦截掉的正是要测的东西。这个配置打真 cloud-backend（dev profile）。
//
// webServer 只起 vite（预览产物）；后端由 scripts/e2e-playwright.mjs 拉起，
// 因为它要和 Playwright 的 webServer 生命周期解耦——后端是 Java 进程，
// 起停日志与端口占用判定都要单独看，交给一个 Node 编排脚本更清楚。
// 端口：默认 4273。4173/5173 一类在部分 Windows 上落在 Hyper-V/WSL 保留段，
// listen 会直接 EACCES（无进程占用，只是端口被系统保留）——踩过就换，别去查进程。
const PREVIEW_PORT = Number(process.env.AF_GCS_PORT || 4273)
const BASE_URL = process.env.AF_GCS_BASEURL || `http://127.0.0.1:${PREVIEW_PORT}`

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  // 串行：E2E 打的是同一个真后端实例，并行会互相抢设备状态（起飞/降落会互相看见）
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['list']] : [['list']],
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    // --host 127.0.0.1 必须与上面的 BASE_URL 同为 IPv4：不指定时 vite preview 绑的是
    // localhost，而 GitHub runner 上 localhost 先解析到 ::1（Vite 只监听 IPv6），
    // Playwright 轮询 127.0.0.1 永远连不上，最终报
    // "Timed out waiting 180000ms from config.webServer"。
    command: `npm run build && npm run preview -- --host 127.0.0.1 --port ${PREVIEW_PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
})