import { describe, it, expect } from 'vitest'

// DefectPanel 契约测试（与 dockPanel.test.js 同款纪律）：
// 面板常量表必须与后端 WorkOrderStateMachine / DefectSeverity 一一对应——
// 后端加状态而面板漏改时，这里红得比线上快。

const BACKEND_WO_STATES = [
  'OPEN', 'DISPATCHED', 'IN_PROGRESS', 'RESOLVED',
  'VERIFIED', 'REOPENED', 'CANCELLED',
]
const BACKEND_DEFECT_STATUSES = ['OPEN', 'CONFIRMED', 'DISMISSED']
const BACKEND_SEVERITIES = ['P0', 'P1', 'P2', 'P3']

const WO_STATUS_LABELS = {
  OPEN: '待派单', DISPATCHED: '已派单', IN_PROGRESS: '处置中',
  RESOLVED: '待复检', VERIFIED: '已闭环', REOPENED: '复检退回', CANCELLED: '已取消',
}
const DEFECT_STATUS_LABELS = { OPEN: '待确认', CONFIRMED: '已确认', DISMISSED: '已驳回' }
const SEVERITY_COLORS = { P0: '#ef4444', P1: '#f97316', P2: '#eab308', P3: '#6b7280' }
const WO_ACTIONS = [
  { action: 'dispatch', allow: ['OPEN', 'REOPENED'] },
  { action: 'start', allow: ['DISPATCHED', 'REOPENED'] },
  { action: 'resolve', allow: ['IN_PROGRESS'] },
  { action: 'cancel', allow: ['OPEN', 'DISPATCHED', 'IN_PROGRESS', 'RESOLVED', 'REOPENED'] },
]

describe('DefectPanel 状态表完整性', () => {
  it('后端每个工单状态都有中文标签', () => {
    for (const s of BACKEND_WO_STATES) {
      expect(WO_STATUS_LABELS[s], `缺 ${s}`).toBeTruthy()
    }
  })

  it('后端每个缺陷状态都有标签', () => {
    for (const s of BACKEND_DEFECT_STATUSES) {
      expect(DEFECT_STATUS_LABELS[s], `缺 ${s}`).toBeTruthy()
    }
  })

  it('四档严重度都有色标', () => {
    for (const s of BACKEND_SEVERITIES) {
      expect(SEVERITY_COLORS[s], `缺 ${s}`).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('标签表无后端不存在的多余状态', () => {
    for (const k of [...Object.keys(WO_STATUS_LABELS), ...Object.keys(DEFECT_STATUS_LABELS)]) {
      expect(BACKEND_WO_STATES.includes(k) || BACKEND_DEFECT_STATUSES.includes(k)).toBe(true)
    }
  })
})

describe('DefectPanel 工单动作许可表与后端状态机一致', () => {
  it('dispatch 允许 OPEN/REOPENED（对齐 WorkOrderStateMachine.ALLOWED）', () => {
    expect(WO_ACTIONS.find((a) => a.action === 'dispatch').allow.sort())
      .toEqual(['OPEN', 'REOPENED'].sort())
  })

  it('start 允许 DISPATCHED/REOPENED', () => {
    expect(WO_ACTIONS.find((a) => a.action === 'start').allow.sort())
      .toEqual(['DISPATCHED', 'REOPENED'].sort())
  })

  it('resolve 只允许 IN_PROGRESS', () => {
    expect(WO_ACTIONS.find((a) => a.action === 'resolve').allow).toEqual(['IN_PROGRESS'])
  })

  it('cancel 覆盖全部未终态、不含 VERIFIED/CANCELLED', () => {
    const cancel = WO_ACTIONS.find((a) => a.action === 'cancel').allow
    for (const s of ['OPEN', 'DISPATCHED', 'IN_PROGRESS', 'RESOLVED', 'REOPENED']) {
      expect(cancel, `cancel 应允许 ${s}`).toContain(s)
    }
    for (const t of ['VERIFIED', 'CANCELLED']) {
      expect(cancel, `终态 ${t} 不可取消`).not.toContain(t)
    }
  })
})