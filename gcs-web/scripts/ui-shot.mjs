// ui-shot.mjs — 无头 Chrome 截图 GCS 各视图（UI 改动的视觉复核工具）
// 用法：cd gcs-web && UI_BASE=http://127.0.0.1:5300 node scripts/ui-shot.mjs [view1,view2,...]
// 与 scripts/ui-audit.mjs 成对：审计脚本给数值判据，这个留图供人工复核。
// 依赖：gcs-web/node_modules 的 playwright + 系统 Chrome（channel: 'chrome'）
// 顶栏导航改成分组后：先点所属域按钮，再点下拉里的视图项。
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { VIEW_GROUPS, VIEW_LABELS } from '../src/nav/viewGroups.js'

const here = dirname(fileURLToPath(import.meta.url))
const BASE = process.env.UI_BASE || 'http://127.0.0.1:5300'
const OUT = process.env.UI_OUT || join(here, '..', 'shots')
const WIDTH = Number(process.env.UI_W || 1920)
const HEIGHT = Number(process.env.UI_H || 1080)

const groupOf = (view) => VIEW_GROUPS.find((g) => g.views.includes(view))

const views = (process.argv[2] || 'control').split(',').map((s) => s.trim()).filter(Boolean)
mkdirSync(OUT, { recursive: true })

// 假 JWT（三段结构，api.js 的格式校验只查形状）：绕过登录门看内页
const FAKE_TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiIsInJvbGUiOiJBRE1JTiJ9.uishot'
const FAKE_USER = { username: 'admin', role: 'ADMIN' }

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await browser.newContext({
  viewport: { width: WIDTH, height: HEIGHT },
  deviceScaleFactor: 1,
  colorScheme: 'dark',
  locale: 'zh-CN',
})
await ctx.addInitScript(
  ([t, u]) => {
    try {
      sessionStorage.setItem('nexus_auth_token', t)
      sessionStorage.setItem('nexus_current_user', JSON.stringify(u))
    } catch { /* 忽略 */ }
  },
  [FAKE_TOKEN, FAKE_USER],
)

const page = await ctx.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))

for (const view of views) {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
  // 等首轮设备轮询落地（轮询 2s 起步 + vite 首次编译延迟）：等到"云端 已连接"
  await page
    .waitForFunction(() => {
      const chips = [...document.querySelectorAll('.chip')]
      return chips.some((c) => c.textContent.includes('已连接'))
    }, { timeout: 20000 })
    .catch(() => console.warn('[shot] 等设备上线超时（继续截图）'))
  await page.waitForTimeout(1000) // 等遥测/图表渲染稳定

  if (view !== 'control') {
    const g = groupOf(view)
    if (!g) {
      console.error(`[shot] 视图 ${view} 不在任何导航分组中`)
    } else if (g.views.length > 1) {
      try {
        await page.getByRole('button', { name: new RegExp(`^${g.label}`) }).first().click({ timeout: 5000 })
        await page.waitForTimeout(250)
        await page.getByRole('menuitem', { name: VIEW_LABELS[view], exact: true }).click({ timeout: 5000 })
      } catch (e) {
        console.error(`[shot] 导航到「${VIEW_LABELS[view]}」失败：${e.message}`)
      }
    } else {
      await page.getByRole('button', { name: new RegExp(`^${g.label}`) }).first().click({ timeout: 5000 }).catch(() => {})
    }
    await page.waitForTimeout(1400)
  }
  const file = join(OUT, `${view}.png`)
  await page.screenshot({ path: file })
  console.log(`[shot] ${view} -> ${file}`)
}

// 顶栏测量：高度 + 组按钮数 + 下拉是否溢出视口
const metrics = await page.evaluate(() => {
  const topbar = document.querySelector('.topbar')
  const groups = document.querySelector('.nav-groups')
  return {
    topbarH: topbar ? Math.round(topbar.getBoundingClientRect().height) : null,
    groupButtons: groups ? groups.querySelectorAll('.nav-group-btn').length : 0,
  }
})
console.log('[metrics]', JSON.stringify(metrics))
if (errors.length) console.log('[pageerrors]', errors.join(' | '))

await browser.close()
