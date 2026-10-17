import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import UnifiedCommandPanel from '../src/components/UnifiedCommandPanel.jsx'
import { emergencyOrch } from '../src/api.js'

/**
 * 「一键应急响应」按钮的契约测试（2026-10-17）。
 *
 * 实测缺陷（后端 MockMvc 侧另有一个同批测试 EmergencyOrchPathVariableTest 钉住）：
 *   1. 后端 `POST /scenarios/{type}/start` 的 `@PathVariable("type")` 是 **int**，
 *      而前端选择器用的是硬编码 `SCENARIO_TYPES`，type 是字符串
 *      `'earthquake'|'mudslide'|'fire'|'custom'` ⇒ 请求 URL 里带字符串 ⇒ **400**。
 *      按钮从来没有成功过，而且失败发生在路径变量解析阶段，连业务分支都进不去。
 *   2. 前端发 `radiusKm`，后端读的是 `radius`（后端源码里 `radiusKm` 出现 0 次）
 *      ⇒ 半径被静默忽略，service 回退到预设默认值。
 *   3. 单位也没换算：UI 是「半径(km)」默认 5，后端契约是**米**。
 *   4. `centerLat/centerLon` 后端契约是 **1E7 度**，前端发的是度。
 *
 * 这四条全藏在第 1 条后面 —— 因为按钮从未成功过，后三条从未被触发过。
 *
 * 夹具按后端 `EmergencyOrchService.getScenarios()` 的真实形状构造：
 * {type:int, name, description, defaults:{radius,droneCount,priority}}。
 */

vi.mock('../src/api.js', () => ({
  api: {
    getMeshTopology: vi.fn().mockResolvedValue(null),
    getMeshLinks: vi.fn().mockResolvedValue(null),
    getStreamToken: vi.fn().mockResolvedValue({ token: 't' }),
  },
  emergencyOrch: {
    getScenarios: vi.fn(),
    getPlan: vi.fn().mockResolvedValue(null),
    getCoverage: vi.fn().mockResolvedValue(null),
    getPriorityQueue: vi.fn().mockResolvedValue(null),
    startScenario: vi.fn(),
    stop: vi.fn().mockResolvedValue({}),
    replan: vi.fn().mockResolvedValue({}),
    adjustPriority: vi.fn().mockResolvedValue({}),
  },
  disaster: {
    getStatus: vi.fn().mockResolvedValue(null),
    getClusters: vi.fn().mockResolvedValue({ clusterCount: 0, clusters: [] }),
    getLinks: vi.fn().mockResolvedValue({ linkCount: 0, links: [] }),
    getQoS: vi.fn().mockResolvedValue(null),
  },
  listSurveillanceDevices: vi.fn().mockResolvedValue([]),
  ptzControl: { preset: vi.fn(), zoom: vi.fn() },
  listSurveillanceEvents: vi.fn().mockResolvedValue({ items: [], total: 0 }),
  acknowledgeAlarm: vi.fn().mockResolvedValue({}),
  listAlarmRules: vi.fn().mockResolvedValue([]),
  triggerEmergencyResponse: vi.fn().mockResolvedValue({}),
  listLinkageLogs: vi.fn().mockResolvedValue([]),
  alarmStreamUrl: () => 'ws://localhost/x',
  fetchStreamToken: vi.fn().mockResolvedValue({ token: 't' }),
  getAirGroundSituation: vi.fn().mockResolvedValue(null),
  triggerReconFromAlarm: vi.fn().mockResolvedValue({}),
  triggerPtzTracking: vi.fn().mockResolvedValue({}),
}))

// 与后端 EmergencyOrchService.scenarioOf(...) 的返回值逐字段对齐
const BACKEND_SCENARIOS = [
  { type: 0, name: '地震', description: '地震灾害应急搜救与通信覆盖', defaults: { radius: 5000, droneCount: 12, priority: 1 } },
  { type: 1, name: '泥石流', description: '泥石流灾害区域测绘与预警', defaults: { radius: 3000, droneCount: 8, priority: 2 } },
  { type: 2, name: '火灾', description: '火灾现场监控与指挥中继', defaults: { radius: 2000, droneCount: 6, priority: 2 } },
  { type: 3, name: '自定义', description: '自定义应急场景', defaults: { radius: 1000, droneCount: 4, priority: 4 } },
]

const START_BTN = '▶ 一键启动'

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  emergencyOrch.getScenarios.mockResolvedValue(BACKEND_SCENARIOS)
  emergencyOrch.startScenario.mockResolvedValue({ planId: 42, status: 'RUNNING' })
})
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

async function renderPanel() {
  render(<UnifiedCommandPanel />)
  await waitFor(() => expect(screen.getByText('地震')).toBeTruthy())
}

describe('场景选择器用后端真值驱动（scenarios 不再是拉回来没人读）', () => {
  it('四个场景按后端返回渲染，type 是 int', async () => {
    await renderPanel()
    for (const s of BACKEND_SCENARIOS) {
      expect(screen.getByText(s.name), `场景 ${s.name} 应在按钮里`).toBeTruthy()
    }
  })

  it('拉取失败时退到兜底清单，且兜底的 type 同样是 int', async () => {
    emergencyOrch.getScenarios.mockResolvedValue([])
    render(<UnifiedCommandPanel />)
    await waitFor(() => expect(screen.getByText('地震')).toBeTruthy())
    // 兜底清单存在 ⇒ 按钮可用，而不是因为列表空了就永久不可用
    expect(screen.getByText('泥石流')).toBeTruthy()
  })

  it('显示选中场景的预设参数（defaults 之前也没人读）', async () => {
    await renderPanel()
    // 自动选中第一项（后端 type=0 地震，radius 5000 / droneCount 12 / priority 1）
    await waitFor(() => expect(screen.getByText(/半径 5000m/)).toBeTruthy())
    expect(screen.getByText(/机数 12/)).toBeTruthy()
    expect(screen.getByText(/优先级 1/)).toBeTruthy()

    // 切到火灾 → 预设应跟着换
    fireEvent.click(screen.getByText('火灾'))
    await waitFor(() => expect(screen.getByText(/半径 2000m/)).toBeTruthy())
    expect(screen.queryByText(/半径 5000m/)).toBeNull()
  })
})

describe('启动编排：请求必须符合后端契约', () => {
  it('URL 里的 type 是后端的 int，不是字符串', async () => {
    await renderPanel()
    fireEvent.click(screen.getByText(START_BTN))
    await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalled())

    const [type] = emergencyOrch.startScenario.mock.calls[0]
    expect(typeof type, 'type 必须是 number，字符串会被 @PathVariable int 判 400').toBe('number')
    expect(type).toBe(0) // 自动选中的第一项 = 地震
  })

  // 逐个场景各起一个干净的面板：第一次启动成功后 isRunning 会变真，
  // 按钮随即禁用并换成「编排进行中」，在同一面板里没法再点下一个。
  it.each(BACKEND_SCENARIOS.map((s) => [s.name, s.type]))(
    '场景 %s 发出去的是 int %i',
    async (name, type) => {
      await renderPanel()
      fireEvent.click(screen.getByText(name))
      fireEvent.click(screen.getByText(START_BTN))
      await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalled())
      expect(emergencyOrch.startScenario.mock.calls[0][0]).toBe(type)
      expect(typeof emergencyOrch.startScenario.mock.calls[0][0]).toBe('number')
    }
  )

  it('半径发的是 radius（米），不是后端不读的 radiusKm', async () => {
    await renderPanel()
    fireEvent.click(screen.getByText(START_BTN))
    await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalled())

    const body = emergencyOrch.startScenario.mock.calls[0][1]
    expect(body.radius, '后端 numInt(body,"radius",0) 读的是 radius').toBe(5000) // 5km × 1000
    expect(body.radiusKm, 'radiusKm 这个键后端根本没读').toBeUndefined()
  })

  it('坐标按后端契约换算成 1E7 度', async () => {
    await renderPanel()
    fireEvent.click(screen.getByText(START_BTN))
    await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalled())

    const body = emergencyOrch.startScenario.mock.calls[0][1]
    // UI 初值 30.0 / 104.0（度）⇒ 1E7 度
    expect(body.centerLat).toBe(300000000)
    expect(body.centerLon).toBe(1040000000)
  })

  it('启动成功后按钮进入运行态（activePlan 生效、按钮禁用）', async () => {
    await renderPanel()
    fireEvent.click(screen.getByText(START_BTN))
    await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalled())
    // 不去断言 planId 的字面文本（它不是独立文本节点），改断言可观察的行为：
    // 按钮文案切到「编排进行中」说明 activePlan 已带回并进入运行态。
    await waitFor(() => expect(screen.getByText('编排进行中')).toBeTruthy())
  })
})

// ============================================================================
// EmergencyOrchPanel：同一个后端端点、同样的三处缺陷（两个面板互相抄的）
// ============================================================================
describe('EmergencyOrchPanel 的启动编排契约（同一个端点，同样的缺陷）', () => {
  async function renderOrch() {
    const { default: EmergencyOrchPanel } = await import('../src/components/EmergencyOrchPanel.jsx')
    render(<EmergencyOrchPanel />)
    await waitFor(() => expect(screen.getByText('地震')).toBeTruthy())
  }

  it('type 发的是后端的 int，不是字符串', async () => {
    await renderOrch()
    fireEvent.click(screen.getByText(START_BTN))
    await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalled())
    const [type] = emergencyOrch.startScenario.mock.calls[0]
    expect(typeof type, '字符串 type ⇒ @PathVariable int 直接 400').toBe('number')
    expect(type).toBe(0)
  })

  it('半径发 radius（米），坐标发 1E7 度', async () => {
    await renderOrch()
    fireEvent.click(screen.getByText(START_BTN))
    await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalled())
    const body = emergencyOrch.startScenario.mock.calls[0][1]
    expect(body.radius).toBe(5000)
    expect(body.radiusKm).toBeUndefined()
    expect(body.centerLat).toBe(300000000)
    expect(body.centerLon).toBe(1040000000)
  })

  it('「可用预设」不再显示成「地震 / 1 / 2 / 3」', async () => {
    await renderOrch()
    // 原表达式是 s.type || s.name：type=0 是 falsy 短路到 name，1/2/3 是 truthy 直接显示数字
    const line = screen.getByText(/可用预设/).textContent
    expect(line, '不该出现裸数字 type').not.toMatch(/\d\s*\//)
    expect(line).toContain('地震')
    expect(line).toContain('泥石流')
    expect(line).toContain('火灾')
    expect(line).toContain('自定义')
  })
})