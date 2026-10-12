import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import DefectPanel, {
  WO_STATUS_LABELS,
  DEFECT_STATUS_LABELS,
  SEVERITY_COLORS,
  WO_ACTIONS,
} from '../src/components/DefectPanel.jsx'
import { defects as defectsApi } from '../src/api.js'

/**
 * DefectPanel 测试（2026-10-12 重写）。
 *
 * 与 dockPanel 同因重写：旧版本在测试文件里自带一份常量镜像、从不 import 组件，
 * 组件改错或面板被删测试都照绿。现在表从组件本体 import，并补真实渲染冒烟。
 */

const BACKEND_WO_STATES = [
  'OPEN', 'DISPATCHED', 'IN_PROGRESS', 'RESOLVED',
  'VERIFIED', 'REOPENED', 'CANCELLED',
]
const BACKEND_DEFECT_STATUSES = ['OPEN', 'CONFIRMED', 'DISMISSED']
const BACKEND_SEVERITIES = ['P0', 'P1', 'P2', 'P3']

vi.mock('../src/api.js', () => ({
  defects: {
    list: vi.fn().mockResolvedValue([]),
    workOrders: vi.fn().mockResolvedValue([]),
    createWorkOrder: vi.fn().mockResolvedValue({ id: 1 }),
    transitionWorkOrder: vi.fn().mockResolvedValue({ result: 'OK' }),
    verifyWorkOrder: vi.fn().mockResolvedValue({ result: 'VERIFIED' }),
    setStatus: vi.fn().mockResolvedValue({ result: 'OK' }),
  },
}))

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
})
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('DefectPanel 状态表与后端枚举一一对应（表来自组件本体）', () => {
  it('后端每个工单状态都有中文标签', () => {
    for (const s of BACKEND_WO_STATES) {
      expect(WO_STATUS_LABELS[s], `组件缺 ${s} 标签`).toBeTruthy()
    }
  })

  it('后端每个缺陷状态都有标签', () => {
    for (const s of BACKEND_DEFECT_STATUSES) {
      expect(DEFECT_STATUS_LABELS[s], `组件缺 ${s} 标签`).toBeTruthy()
    }
  })

  it('四档严重度都有合法 hex 色标', () => {
    for (const s of BACKEND_SEVERITIES) {
      expect(SEVERITY_COLORS[s], `组件缺 ${s} 色标`).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('标签表无后端不存在的多余状态', () => {
    for (const k of [...Object.keys(WO_STATUS_LABELS), ...Object.keys(DEFECT_STATUS_LABELS)]) {
      expect(BACKEND_WO_STATES.includes(k) || BACKEND_DEFECT_STATUSES.includes(k)).toBe(true)
    }
  })
})

describe('DefectPanel 工单动作许可表与后端状态机一致（表来自组件本体）', () => {
  it('dispatch 允许 OPEN/REOPENED', () => {
    expect(WO_ACTIONS.find((a) => a.action === 'dispatch').allow.sort())
      .toEqual(['OPEN', 'REOPENED'])
  })

  it('start 允许 DISPATCHED/REOPENED', () => {
    expect(WO_ACTIONS.find((a) => a.action === 'start').allow.sort())
      .toEqual(['DISPATCHED', 'REOPENED'])
  })

  it('resolve 只允许 IN_PROGRESS', () => {
    expect(WO_ACTIONS.find((a) => a.action === 'resolve').allow).toEqual(['IN_PROGRESS'])
  })

  it('cancel 覆盖未闭环各态但不含 VERIFIED（已闭环不可取消）', () => {
    const allow = WO_ACTIONS.find((a) => a.action === 'cancel').allow
    expect(allow).toContain('OPEN')
    expect(allow).toContain('IN_PROGRESS')
    expect(allow).not.toContain('VERIFIED')
    expect(allow).not.toContain('CANCELLED')
  })
})

describe('DefectPanel 渲染冒烟', () => {
  it('空列表：渲染面板主体，不白屏', async () => {
    defectsApi.list.mockResolvedValueOnce([])
    render(<DefectPanel />)
    await waitFor(() => {
      expect(screen.getAllByText(/缺陷|工单/).length).toBeGreaterThan(0)
    })
  })

  it('API 抛错：显示错误提示而不是静默空白', async () => {
    defectsApi.list.mockRejectedValue(new Error('api down'))
    const { container } = render(<DefectPanel />)
    // 错误是绝对定位的 toast（不是表单内联），所以按容器文本查
    await waitFor(() => {
      expect(container.textContent).toContain('api down')
    })
  })
})
