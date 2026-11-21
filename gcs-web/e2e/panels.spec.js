import { test, expect } from '@playwright/test'

// Panel render E2E: real browser + real cloud-backend + real drone-sim.
// gcs.spec.js only smoke-tests "no blank screen". This one asserts data actually
// renders. No route/mock: a field-name mismatch would silently render "--"/empty.

const API = '/api/v1'

async function gotoView(page, groupLabel, itemLabel) {
  await page.goto('/')
  await page.getByRole('button', { name: groupLabel }).click()
  await page.getByRole('menuitem', { name: itemLabel }).click()
}

test.beforeEach(async ({ page }) => {
  // dev profile: backend permitAll, but frontend `authed` only checks
  // sessionStorage for a 3-segment token. Seed it before app scripts run.
  await page.addInitScript(() => {
    window.sessionStorage.setItem('nexus_auth_token', 'e2e.a.b')
    window.sessionStorage.setItem('nexus_current_user', JSON.stringify(
      { username: 'e2e', role: 'ADMIN', tenantId: 1 }))
  })
})

test.describe('panel render: real data must actually show', () => {
  test('fleet list: drone-sim online device appears', async ({ page, request }) => {
    // 数据一致性：UI 显示的呼号必须与 GET /api/v1/drones 经后端序列化的真实值一致；
    // 后端 callsign 缺失/改名会立刻变红（以前 DroneList 有回退，破坏 callsign 不可见）。
    const drones = await request.get(`${API}/drones`).then((r) => r.json())
    const callsign = drones.find((d) => d.sysid === 9)?.callsign
    expect(callsign, 'GET /api/v1/drones 应返回 sysid=9 的 callsign').toBe('Drone-9')

    await page.goto('/')
    // backend summary callsign is "Drone-<sysid>"; start-backend.mjs uses 9
    await expect(page.getByText('Drone-9', { exact: true })).toBeVisible({ timeout: 20000 })
    await expect(page.getByText('1/1 在线')).toBeVisible()
  })

  test('dock panel: REST-registered SN appears in list', async ({ page, request }) => {
    const sn = `E2E-DOCK-${Date.now()}`
    const res = await request.post(`${API}/docks`, {
      data: { name: 'E2E-Dock', model: 'E2E-M', sn, lat: 22.5916, lon: 113.9345, droneSysid: 7 },
    })
    expect(res.status(), 'dock register should be 201').toBe(201)

    await gotoView(page, '运营', '机巢')
    await expect(page.getByText(new RegExp('SN ' + sn))).toBeVisible({ timeout: 20000 })
  })
})
