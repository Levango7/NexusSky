import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import UnifiedCommandPanel from '../src/components/UnifiedCommandPanel.jsx'
import { emergencyOrch, disaster, listSurveillanceEvents } from '../src/api.js'

/**
 * 接线缺口 #104 的三处（2026-10-17）。三处性质**不同**，台账上一视同仁记成
 * 「写了但没人读」是不准的：
 *
 * 1. `disasterClusters` —— 后端 `getClusterTopology()` 返回的真数据
 *    {clusterCount, clusters:[{clusterId,clusterHead,members,memberCount,clusterRadius,timestamp}]}
 *    确实被写进了 state，但全组件无人读 ⇒ 纯渲染缺口。
 *
 * 2. `orchEvents` —— 数据是真数据，但**去重逻辑把事件全并成一条**：
 *    后端 `EmergencyOrchService` 的 events 是 `List<String>`
 *    （L572 直接 `((List<String>) plan.get("events")).add(event)`），
 *    而前端去重键是 `(e.ts||e.timestamp)+'|'+(e.message||e.msg)`，
 *    对字符串 e 而言两个字段全是 undefined ⇒ **键恒为 `'|'`**
 *    ⇒ seen 集合最多一个元素 ⇒ 除第一条外全部被判重复。
 *
 * 3. `survEvents` —— **两个独立原因叠加**：
 *    (a) 前端读 `data.events`，而后端 `SurveillanceController` 的 /events 返回的是
 *        `{ items, ... }` ⇒ 键名不符；
 *    (b) 后端本身就是**永久空桩**（注释自述「当前没有全局 SurveillanceEventStore」），
 *        固定返回 `{ items: [] }`。
 *    所以只修 (a) 列表仍然是空的 —— 空列表不能读作「没有发生过事」，
 *    因此空态必须显式说明后端未实现（这才是本 PR 对 (b) 的处理方式）。
 *
 * 判据不能只看「有没有渲染出东西」：硬编码一个假数据同样能骗过断言。
 * 每条都钉在「发回来的数据原样出现在界面上，且不重复也不丢失」。
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

const SCENARIOS = [
  { type: 0, name: '地震', description: '地震灾害应急搜救与通信覆盖', defaults: { radius: 5000, droneCount: 12, priority: 1 } },
]

/** 后端 plan 的当前内容（只追加）。测试里靠改这几个变量模拟新事件 / 新计划。 */
let planEvents
let planStatusNow
let nextPlanId
const PLAN_ID = 42

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  planEvents = ['plan created: scenario=地震']
  planStatusNow = 'RUNNING'
  nextPlanId = PLAN_ID
  emergencyOrch.getScenarios.mockResolvedValue(SCENARIOS)
  emergencyOrch.startScenario.mockImplementation(() =>
    Promise.resolve({ planId: nextPlanId, status: 'RUNNING' })
  )
  emergencyOrch.getPlan.mockImplementation(() =>
    Promise.resolve({ planId: nextPlanId, status: planStatusNow, events: [...planEvents] })
  )
  disaster.getClusters.mockResolvedValue({ clusterCount: 0, clusters: [] })
  listSurveillanceEvents.mockResolvedValue({ items: [], total: 0 })
})

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

async function renderPanel() {
  const r = render(<UnifiedCommandPanel />)
  await waitFor(() => expect(screen.getByText('地震')).toBeTruthy())
  return r
}

/** 启动一个计划并等第一次轮询落地。只等标签出现，具体条数由各用例自己断言。 */
async function startPlan() {
  const r = await renderPanel()
  fireEvent.click(screen.getByText('▶ 一键启动'))
  await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalled())
  await waitFor(() => expect(screen.getByText(/编排事件（/)).toBeTruthy())
  return r
}

/** 触发一次轮询（POLL_MS = 2000）。 */
async function nextPoll() {
  await act(async () => { await vi.advanceTimersByTimeAsync(2100) })
}

describe('#104 orchEvents：后端是 List<String>，去重必须按位置而不是内容', () => {
  it('多条不同事件全部显示，不被并成一条', async () => {
    await startPlan()
    planEvents = [
      'plan created: scenario=地震',
      'phase 0->1 status=RUNNING',
      'phase 1->2 status=RUNNING',
    ]
    await nextPoll()
    await waitFor(() => expect(screen.getByText(/编排事件（3）/)).toBeTruthy())
    expect(screen.getByText('plan created: scenario=地震')).toBeTruthy()
    expect(screen.getByText('phase 0->1 status=RUNNING')).toBeTruthy()
    expect(screen.getByText('phase 1->2 status=RUNNING')).toBeTruthy()
  })

  it('内容完全相同的两条事件不能被当成重复吞掉一条', async () => {
    // 这是"用字符串内容做去重键"的直接后果：后端确实可以追加两条一模一样的串
    // （例如 priority adjust: task=5 EMERGENCY->COMMAND 可以发生多次）
    planEvents = ['dup-event', 'dup-event']
    await startPlan()
    await nextPoll()
    await waitFor(() => expect(screen.getByText(/编排事件（2）/)).toBeTruthy())
    expect(screen.getAllByText('dup-event')).toHaveLength(2)
  })

  it('只追加：多次轮询不重不漏（总数 == 后端长度）', async () => {
    await startPlan()
    for (const e of ['e1', 'e2', 'e3', 'e4']) {
      planEvents = [...planEvents, e]
      await nextPoll()
    }
    await waitFor(() => expect(screen.getByText(/编排事件（5）/)).toBeTruthy())
    for (const e of ['e1', 'e2', 'e3', 'e4']) {
      expect(screen.getByText(e), `${e} 应各出现一次`).toBeTruthy()
    }
  })

  it('同一面板内换 planId 时整体替换，不拿旧计划的长度去切新计划', async () => {
    // 必须在**同一个面板**里换计划才算覆盖到 planId 锚点：
    // 之前这版测试先 unmount 再渲染，新面板的 orchEventsRef 是全新的
    // {planId:null,count:0}，于是根本走不到锚点分支 —— 去掉锚点它照样绿。
    await startPlan()
    planEvents = [...planEvents, 'old-e1', 'old-e2']
    await nextPoll()
    await waitFor(() => expect(screen.getByText(/编排事件（3）/)).toBeTruthy())

    // 让第一个计划走到终态，按钮才重新可点（isRunning = !!planId && !TERMINAL_STATUS）
    planStatusNow = 'COMPLETED'
    await nextPoll()
    await waitFor(() => expect(screen.getByText('▶ 一键启动')).toBeTruthy())

    // 同一面板里启动第二个计划，它的事件更少（1 条 vs 旧计划 3 条）
    nextPlanId = 99
    planEvents = ['new-ev']
    planStatusNow = 'RUNNING'
    fireEvent.click(screen.getByText('▶ 一键启动'))
    await waitFor(() => expect(emergencyOrch.startScenario).toHaveBeenCalledTimes(2))

    // 新计划的事件必须出现，且条数按新计划算（不是被旧 count 切掉）
    await waitFor(() => expect(screen.getByText('new-ev')).toBeTruthy())
    await waitFor(() => expect(screen.getByText(/编排事件（1）/)).toBeTruthy())
    // 旧计划的事件不残留
    expect(screen.queryByText('old-e1')).toBeNull()
    expect(screen.queryByText('old-e2')).toBeNull()
  })
})

describe('#104 disasterClusters：真数据必须渲染出来', () => {
  it('按后端返回渲染每个簇（id / 簇头 / 节点数 / 半径）', async () => {
    disaster.getClusters.mockResolvedValue({
      clusterCount: 2,
      clusters: [
        { clusterId: 7, clusterHead: 'UAV-11', members: [11, 12, 13], memberCount: 3, clusterRadius: 850, timestamp: 1 },
        { clusterId: 8, clusterHead: 'UAV-21', members: [21, 22], memberCount: 2, clusterRadius: 400, timestamp: 2 },
      ],
    })
    await renderPanel()
    await waitFor(() => expect(screen.getByText(/灾害聚簇（2）/)).toBeTruthy())
    expect(screen.getByText(/簇7 · 头 UAV-11/)).toBeTruthy()
    expect(screen.getByText(/簇8 · 头 UAV-21/)).toBeTruthy()
    expect(screen.getByText('3 节点')).toBeTruthy()
    expect(screen.getByText('2 节点')).toBeTruthy()
    expect(screen.getByText('R=850m')).toBeTruthy()
  })

  it('空拓扑时如实显示 0，不是空白', async () => {
    disaster.getClusters.mockResolvedValue({ clusterCount: 0, clusters: [] })
    await renderPanel()
    await waitFor(() => expect(screen.getByText(/灾害聚簇（0）/)).toBeTruthy())
    expect(screen.getByText(/暂无分簇/)).toBeTruthy()
  })
})

describe('#104 survEvents：键名是 items，且空态必须诚实', () => {
  it('读 items 键并渲染事件（此前读 data.events ⇒ 恒空）', async () => {
    listSurveillanceEvents.mockResolvedValue({
      total: 1,
      items: [{ id: 'evt-1', message: '门禁异常闯入', timestamp: Date.now() }],
    })
    await renderPanel()
    await waitFor(() => expect(screen.getByText(/安防事件（1）/)).toBeTruthy())
    expect(screen.getByText('门禁异常闯入')).toBeTruthy()
  })

  it('空态必须说明后端未实现，不能让空列表被读成「没有发生过事」', async () => {
    await renderPanel()
    await waitFor(() => expect(screen.getByText(/安防事件（0）/)).toBeTruthy())
    const hint = screen.getByText(/尚未实现全局安防事件存储/)
    expect(hint).toBeTruthy()
    // 并且明说这不是"无事件"
    expect(hint.textContent).toMatch(/不代表/)
  })

  it('真拿到事件时不再显示"后端未实现"的提示', async () => {
    listSurveillanceEvents.mockResolvedValue({
      total: 1,
      items: [{ id: 'evt-2', message: '周界入侵', timestamp: Date.now() }],
    })
    await renderPanel()
    await waitFor(() => expect(screen.getByText('周界入侵')).toBeTruthy())
    expect(screen.queryByText(/尚未实现全局安防事件存储/)).toBeNull()
  })
})