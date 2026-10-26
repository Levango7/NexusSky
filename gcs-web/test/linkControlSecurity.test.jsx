import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import LinkQualityPanel from '../src/components/LinkQualityPanel.jsx'

/**
 * 链路控制面的安全画像分支（P2 #20，2026-10-26）。
 *
 * 背景：link-sim 起在安全画像（SIGNING_TAMPER/SIGNING_UNSIGNED）时，控制面
 * GET /profile 返回 mode="security"、只有一个可调参数 tamperRate。此前面板
 * 只会照十元组渲染——六个 0 值滑块摆在一台从不存在这些参数的链路上，
 * 拖了发 PATCH 只会拿到 400，正是 P1 #11 清掉的"假滑块"换了层皮。
 *
 * 本测试钉住分支行为：
 * ① 安全模式下十元组滑块**一个都不渲染**（找不到"基准延迟"等标签）；
 * ② 只有 tamperRate 一个滑块，值来自控制面当前值（50），拖动后应用发
 *    PATCH {tamperRate: 0.75}（不是 75——服务端口径是 0~1）；
 * ③ 应用成功后以服务器响应回填（100 覆盖本地 75）；
 * ④ 统计行如实显示篡改/剥离计数，并明说 dropped 恒 0 的原因。
 */

function installControl(view, patchResponse) {
  global.fetch = vi.fn((url, opts) => {
    const u = String(url)
    if (u.includes('/api/v1/link-sim/control')) {
      if (opts && opts.method === 'PATCH') {
        return Promise.resolve({ ok: true, json: async () => (patchResponse || view) })
      }
      return Promise.resolve({ ok: true, json: async () => view })
    }
    return Promise.resolve({ ok: true, json: async () => ({ items: [] }) })
  })
}

const SECURITY_VIEW = {
  profile: 'tamper',
  mode: 'security',
  envCoupled: false,
  security: {
    kind: 'SIGNING_TAMPER',
    tamperRate: 0.5,
    tunable: 'tamperRate',
    limits: { tamperRateMin: 0, tamperRateMax: 1 },
  },
  uplink: {
    profile: 'tamper', mode: 'security', tamperRate: 0.5,
    stats: { forwarded: 120, dropped: 0, dropPct: 0, avgDelayMs: 0, tampered: 80, stripped: 0, tamperRatio: 0.4 },
  },
  downlink: {
    profile: 'tamper', mode: 'security', tamperRate: 0.5,
    stats: { forwarded: 300, dropped: 0, dropPct: 0, avgDelayMs: 0, tampered: 12, stripped: 0, tamperRatio: 0.04 },
  },
}

const TEN_TUPLE_VIEW = {
  profile: 'lan', mode: 'profile', envCoupled: false,
  profiles: ['lan', 'lte', 'lte-edge', 'tamper', 'unsigned'],
  uplink: { profile: 'lan', mode: 'profile', delayMs: 0, dropModel: 'ge',
    stats: { forwarded: 3, dropped: 0, dropPct: 0, avgDelayMs: 0 } },
  downlink: { profile: 'lan', mode: 'profile', delayMs: 0, dropModel: 'ge',
    stats: { forwarded: 3, dropped: 0, dropPct: 0, avgDelayMs: 0 } },
  limits: {},
}

beforeEach(() => {
  installControl(TEN_TUPLE_VIEW)
})

afterEach(() => {
  vi.restoreAllMocks()
  delete global.fetch
})

describe('LinkQualityPanel 安全画像分支', () => {
  it('安全画像：十元组滑块一个都不渲染，只有 tamperRate', async () => {
    installControl(SECURITY_VIEW)
    render(<LinkQualityPanel />)

    await waitFor(() => expect(screen.getAllByText(/篡改概率 tamperRate/).length).toBeGreaterThan(0))
    // 滑块当前值读自控制面（0.5 → 50%），不是写死的默认值
    expect(screen.getByText('50 %')).toBeTruthy()
    // 结构性断言：整页只应剩下 1 个滑块（tamperRate），十元组整套不渲染
    expect(document.querySelectorAll('input[type="range"]').length).toBe(1)
    for (const label of ['基准延迟', '抖动', '丢包（独立随机）', '分区黑洞窗口', '切换画像']) {
      expect(screen.queryByText(label)).toBeNull()
    }
    expect(screen.getByText(/在线 · security · 安全画像 SIGNING_TAMPER/)).toBeTruthy()
    expect(screen.getAllByText(/安全画像没有延迟/).length).toBe(1)
    // stats 行：上/下行的篡改计数如实出现
    expect(screen.getByText(/上行 fwd 120 \/ 篡改 80 \/ 剥离 0/)).toBeTruthy()
    expect(screen.getByText(/下行 fwd 300 \/ 篡改 12 \/ 剥离 0/)).toBeTruthy()
    expect(screen.getByText(/dropped 恒为 0/)).toBeTruthy()
  })

  it('应用 tamperRate：发 PATCH {tamperRate: 0.75}（75% → 0~1 口径），响应回填', async () => {
    installControl(SECURITY_VIEW, { ...SECURITY_VIEW, security: { ...SECURITY_VIEW.security, tamperRate: 1.0 }, uplink: { ...SECURITY_VIEW.uplink, tamperRate: 1.0 } })
    let patched
    global.fetch = vi.fn((url, opts) => {
      const u = String(url)
      if (u.includes('/api/v1/link-sim/control')) {
        if (opts && opts.method === 'PATCH') {
          patched = JSON.parse(String(opts.body))
          return Promise.resolve({
            ok: true,
            json: async () => ({ ...SECURITY_VIEW, security: { ...SECURITY_VIEW.security, tamperRate: 1.0 }, uplink: { ...SECURITY_VIEW.uplink, tamperRate: 1.0 } }),
          })
        }
        return Promise.resolve({ ok: true, json: async () => SECURITY_VIEW })
      }
      return Promise.resolve({ ok: true, json: async () => ({ items: [] }) })
    })

    render(<LinkQualityPanel />)

    await waitFor(() => expect(screen.getAllByText(/应用篡改概率/).length).toBe(1))
    // 把滑块从 50 拖到 75：SliderRow 是 range input，用 change 事件
    const slider = document.querySelector('input[type="range"]')
    expect(slider).toBeTruthy()
    fireEvent.change(slider, { target: { value: '75' } })

    fireEvent.click(screen.getByText(/应用篡改概率/))

    await waitFor(() => expect(patched).toBeTruthy())
    expect(patched).toEqual({ tamperRate: 0.75 })
    // 服务器是权威：响应回填 100%，而不是留在本地 75
    await waitFor(() => expect(screen.getByText('100 %')).toBeTruthy())
  })

  it('非安全画像：十元组滑块照常渲染（分支没有把正常链路也藏了）', async () => {
    render(<LinkQualityPanel />)
    await waitFor(() => expect(screen.getAllByText(/基准延迟/).length).toBe(1))
    expect(screen.getByText(/切换画像/)).toBeTruthy()
    expect(screen.queryByText(/应用篡改概率/)).toBeNull()
    // 对照组：六个滑块（含分区两个）
    expect(document.querySelectorAll('input[type="range"]').length).toBe(6)
  })
})
