import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import ThermalOverlayPanel from '../src/components/ThermalOverlayPanel.jsx'

/**
 * 逐点热源明细的显示契约（P2 #19 条件③，2026-10-26）。
 *
 * 背景：之前面板只有"热点数"一个数字——P2 #19 恢复条件③要求给出每个热源的地面
 * 经纬度。链路（drone-sim 30064 → cloud GeolocationSolver → /thermal/status 的
 * hotspots 段）接通后，面板必须如实呈现三件事：
 *
 * ① 解算出来的坐标要显示（5 位小数，够 1m 级读辨）；
 * ② **lat/lon 为 null 时必须显示"无法解算"**——缺 GPS/高度/姿态时云端解不出来，
 *    此时填 0 或 NaN 就是把"不知道"伪装成"在赤道几内亚湾"，P1 #12 刚清过
 *    这类伪造数据，这里不许再犯；
 * ③ truncated=true 要显式告知"热点超容量已截断"，否则操作员会以为只剩这些热源。
 *
 * mock global.fetch（rocPanel 先例）：面板经 api.js 的 jsonFetch 请求
 * /api/v1/thermal/status，这里给出确定性的机队快照。
 */

function installThermalStatus(items) {
  global.fetch = vi.fn((url) => {
    const u = String(url)
    if (u.includes('/api/v1/thermal/status')) {
      return Promise.resolve({ ok: true, json: async () => ({ items, total: items.length }) })
    }
    return Promise.resolve({ ok: true, json: async () => ({}) })
  })
}

beforeEach(() => {
  installThermalStatus([])
})

afterEach(() => {
  vi.restoreAllMocks()
  delete global.fetch
})

describe('ThermalOverlayPanel 逐点热源明细', () => {
  it('解算成功：逐点显示像素坐标 + 峰值温度 + lat/lon（5 位小数）', async () => {
    installThermalStatus([
      {
        sysid: 7, tempMean: 27.3, tempMin: 22.0, tempMax: 61.2, tempStdDev: 8.1,
        hotspotCount: 2, updatedAtMs: Date.now(), hotspotTruncated: false,
        hotspots: [
          { u: 16, v: 12, tempC: 61.2, lat: 32.00012345, lon: 118.00023456 },
          { u: 26, v: 19, tempC: 45.5, lat: 31.99987654, lon: 118.00099876 },
        ],
      },
    ])

    render(<ThermalOverlayPanel />)

    // 逐点 chip 以像素坐标做 title（同一面板里其它位置也有 61.2°C，按 title 定点取）
    await waitFor(() => expect(screen.getByTitle('像素 (16, 12)')).toBeTruthy())
    const first = screen.getByTitle('像素 (16, 12)')
    expect(first.textContent).toContain('(16,12)')
    expect(first.textContent).toContain('61.2')
    // lat/lon 5 位小数（32.00012345 → 32.00012，够 1m 级读辨）
    expect(first.textContent).toContain('32.00012, 118.00023')
    // 31.99987654 → toFixed(5) = 31.99988（第 6 位 6 进位）
    const second = screen.getByTitle('像素 (26, 19)')
    expect(second.textContent).toContain('45.5')
    expect(second.textContent).toContain('31.99988, 118.00100')
  })

  it('无法解算（lat/lon=null）：明示"无法解算"，不得显示 0 坐标', async () => {
    installThermalStatus([
      {
        sysid: 8, tempMean: 27.3, tempMin: 22.0, tempMax: 55.0, tempStdDev: 6.0,
        hotspotCount: 1, updatedAtMs: Date.now(), hotspotTruncated: false,
        hotspots: [
          { u: 10, v: 8, tempC: 55.0, lat: null, lon: null },
        ],
      },
    ])

    render(<ThermalOverlayPanel />)

    await waitFor(() => expect(screen.getByText(/无法解算（缺位姿\/高度）/)).toBeTruthy())
    // 空白/0 坐标都不允许出现在这一台上
    expect(screen.queryByText(/0\.00000, 0\.00000/)).toBeNull()
  })

  it('truncated=true：显式告知热点超容量已截断', async () => {
    installThermalStatus([
      {
        sysid: 9, tempMean: 28.0, tempMin: 22.0, tempMax: 70.0, tempStdDev: 9.0,
        hotspotCount: 5, updatedAtMs: Date.now(), hotspotTruncated: true,
        hotspots: [
          { u: 3, v: 4, tempC: 70.0, lat: 32.0, lon: 118.0 },
          { u: 20, v: 15, tempC: 56.0, lat: 32.000001, lon: 118.000001 },
          { u: 25, v: 20, tempC: 48.0, lat: 32.000002, lon: 118.000002 },
        ],
      },
    ])

    render(<ThermalOverlayPanel />)

    await waitFor(() => expect(screen.getByText(/热点超容量已截断（保留最热的）/)).toBeTruthy())
  })

  it('未上报逐点（hotspots 为空数组）：明说本机未上报 30064', async () => {
    installThermalStatus([
      {
        sysid: 10, tempMean: 27.3, tempMin: 22.0, tempMax: 50.0, tempStdDev: 7.0,
        hotspotCount: 0, updatedAtMs: Date.now(), hotspotTruncated: false,
        hotspots: [],
      },
    ])

    render(<ThermalOverlayPanel />)

    await waitFor(() => expect(screen.getByText(/本机未上报逐点热源/)).toBeTruthy())
  })
})
