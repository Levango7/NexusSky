import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import HealthPanel from '../src/components/HealthPanel.jsx'
import { getDroneHealth, getFleetHealth, getDroneHealthHistory } from '../src/api.js'

/**
 * HealthPanel 字段名接线测试（2026-10-17）。
 *
 * 缺陷：面板读 `score` / `components`，而后端 HealthScore 的字段是
 *       `overallScore` / `componentScores`（普通 getter、无 @JsonProperty，
 *       所以 JSON 键就是字段名）。三个候选名一个都对不上。
 *       同仓库的 CommAdaptPanel 读的就是 overallScore —— 正确名字本来是已知的。
 * 后果：机队健康总览、单机总评分、7 部件评分三处全部静默显示 '--'，
 *       面板的核心功能是空白的，而且**不报任何错**。
 *
 * 本文件的夹具照抄后端 DTO 的逐字段形状（ComponentType 七个取值、
 * ComponentScore.Status 三个取值都是照抄枚举），不是随手编的字段名——
 * 「多跳中继」那 11 个假绿测试栽的就是这个跟头：合成字节数组让断言
 * 在错误的字段上也能绿。
 */

vi.mock('../src/api.js', () => ({
  getDroneHealth: vi.fn(),
  getFleetHealth: vi.fn(),
  getDroneHealthHistory: vi.fn(),
  getHealthWarnings: vi.fn().mockResolvedValue([]),
  listMaintenanceRecords: vi.fn().mockResolvedValue([]),
  createMaintenanceRecord: vi.fn().mockResolvedValue({}),
  getMaintenancePredictions: vi.fn().mockResolvedValue([]),
  getMaintenanceSchedule: vi.fn().mockResolvedValue([]),
}))

const TS = 1760000000000

const COMPONENT_SCORES = {
  BATTERY: 88,
  MOTOR: 77,
  VIBRATION: 91,
  TEMPERATURE: 84,
  COMMUNICATION: 72,
  IMU: 95,
  GPS: 69,
}

const componentScore = (type, score) => ({
  componentType: type,
  score,
  status: score >= 80 ? 'HEALTHY' : score >= 60 ? 'WARNING' : 'CRITICAL',
  metrics: {},
  recommendation: '',
})

// 与后端 HealthScore 逐字段对齐：sysid / timestamp / overallScore / grade / componentScores
const healthScore = (sysid, overallScore, grade) => ({
  sysid,
  timestamp: TS,
  overallScore,
  grade,
  componentScores: Object.fromEntries(
    Object.entries(COMPONENT_SCORES).map(([type, s]) => [type, componentScore(type, s)])
  ),
})

// 机队：#7 = 88、#8 = 72 ⇒ 平均 80。三个数互不相同，避免断言互相串味。
const FLEET = [healthScore(7, 88, 'A'), healthScore(8, 72, 'C')]

/** 机队行：#<sysid> 的 span → 兄弟 flex div → 行容器 */
const fleetRow = (sysid) => screen.getByText(`#${sysid}`).parentElement.parentElement

/**
 * 机队行里的分数那一格（不含 /100、不含状态行）。
 * 必须精确取到这一格：状态行在缺 status 字段时本来就渲染 '--'，
 * 拿整行文本判「不该出现 --」会误伤。
 */
const fleetScore = (sysid) => {
  const head = fleetRow(sysid).children[0] // 「#sysid」与分数并排的 flex 行
  return head.children[1].children[0].textContent // 分数 span（下一个兄弟是 /100）
}

/** 部件行：标签 span → 行容器（行文本形如「电池88」） */
const compRowText = (label) => screen.getByText(label).parentElement.textContent

/** 单机详情的「总评分」块 */
const detailTotal = () => screen.getByText('总评分').parentElement

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  getFleetHealth.mockResolvedValue(FLEET)
  getDroneHealth.mockResolvedValue(FLEET[0])
  getDroneHealthHistory.mockResolvedValue([])
})
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('HealthPanel 读的是后端 DTO 的真实字段名', () => {
  it('机队总览按 overallScore 显示分数（旧代码读 score → 显示 --）', async () => {
    render(<HealthPanel />)
    await waitFor(() => expect(screen.getByText('#7')).toBeTruthy())

    // 决定性判据：字段名读不到时这一格就是 fmtScore(undefined) === '--'
    expect(fleetScore(7), '#7 应显示 overallScore=88').toBe('88')
    expect(fleetScore(8), '#8 应显示 overallScore=72').toBe('72')
  })

  it('机队平均分按 overallScore 求平均（旧代码恒为 0）', async () => {
    render(<HealthPanel />)
    await waitFor(() => expect(screen.getByText('#7')).toBeTruthy())

    // (88 + 72) / 2 = 80。字段名读不到时这里会是 Number(undefined)||0 ⇒ 0。
    const avg = screen.getByText('80', { selector: 'b' })
    expect(avg.textContent, '平均分应为 80').toBe('80')
  })

  it('单机总评分读 overallScore（旧代码读 score → 显示 --/100）', async () => {
    render(<HealthPanel />)
    await waitFor(() => expect(screen.getByText('#7')).toBeTruthy())
    fireEvent.click(screen.getByText('#7'))

    await waitFor(() => expect(screen.getByText('总评分')).toBeTruthy())
    expect(detailTotal().textContent, '总评分应为 88/100').toContain('88/100')
  })

  it('7 部件评分读 componentScores（旧代码找 components → 整块空白）', async () => {
    render(<HealthPanel />)
    await waitFor(() => expect(screen.getByText('#7')).toBeTruthy())
    fireEvent.click(screen.getByText('#7'))

    await waitFor(() => expect(screen.getByText('总评分')).toBeTruthy())
    // 七个部件行：label 与分数相邻 ⇒ 行文本形如「电池88」
    expect(compRowText('电池'), '电池应显示 88').toContain('88')
    expect(compRowText('电机'), '电机应显示 77').toContain('77')
    expect(compRowText('GPS'), 'GPS 应显示 69').toContain('69')
  })

  it('历史评分有渲染点：droneHistory 取回了真实数据并显示（旧代码存了没人读）', async () => {
    getDroneHealthHistory.mockResolvedValue([
      healthScore(7, 72, 'C'),
      healthScore(7, 88, 'A'),
    ])
    render(<HealthPanel />)
    await waitFor(() => expect(screen.getByText('#7')).toBeTruthy())
    fireEvent.click(screen.getByText('#7'))

    await waitFor(() => expect(screen.getByText('历史评分（2）')).toBeTruthy())
    // slice(-10).reverse() ⇒ 新的在前
    expect(screen.getByText('88 (A)'), '最新一条应是 88 (A)').toBeTruthy()
    expect(screen.getByText('72 (C)'), '次新一条应是 72 (C)').toBeTruthy()
  })

  it('历史为空时给明确提示，而不是空白块', async () => {
    getDroneHealthHistory.mockResolvedValue([])
    render(<HealthPanel />)
    await waitFor(() => expect(screen.getByText('#7')).toBeTruthy())
    fireEvent.click(screen.getByText('#7'))

    await waitFor(() => expect(screen.getByText('历史评分（0）')).toBeTruthy())
    expect(screen.getByText('暂无历史评分')).toBeTruthy()
  })

  it('机队无数据时给明确提示（对照组：面板本身的空态没坏）', async () => {
    getFleetHealth.mockResolvedValue([])
    render(<HealthPanel />)
    await waitFor(() => expect(screen.getByText('暂无机队健康数据')).toBeTruthy())
  })
})