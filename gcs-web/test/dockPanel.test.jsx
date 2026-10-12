import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import DockPanel, { STATE_LABELS, STATE_COLORS, ACTIONS } from '../src/components/DockPanel.jsx'
import { docks as docksApi } from '../src/api.js'

/**
 * DockPanel 测试（2026-10-12 重写）。
 *
 * ## 为什么重写
 *
 * 旧版本是**镜像常量自证**：测试文件里自带一份 STATE_LABELS/STATE_COLORS/ACTIONS
 * 副本，断言的是"这份副本自身完整"。它从不 import 组件——组件把表改错、甚至把整个
 * 面板删掉，测试照样全绿。这正是外部评估报告点名的"虚假安全感"。
 *
 * 现在：表从组件本体 import（组件是唯一真相源），另外补**真实渲染冒烟**
 * （空态 / 错误态 / 有数据），以及命令许可与后端状态机一致性的核对。
 */

const BACKEND_STATES = [
  'OFFLINE', 'IDLE', 'OPENING', 'OPEN', 'CLOSING',
  'CHARGING', 'EXCHANGING', 'FAULT', 'MAINTENANCE',
]

vi.mock('../src/api.js', () => ({
  docks: {
    list: vi.fn().mockResolvedValue([]),
    detail: vi.fn().mockResolvedValue({ id: 1, name: 'dock-1', state: 'IDLE', sn: 'SN-1' }),
    schedules: vi.fn().mockResolvedValue([]),
    metrics: vi.fn().mockResolvedValue({}),
    register: vi.fn().mockResolvedValue({ id: 2 }),
    command: vi.fn().mockResolvedValue({ success: true }),
    createSchedule: vi.fn().mockResolvedValue({ id: 3 }),
    setScheduleEnabled: vi.fn().mockResolvedValue({ success: true }),
  },
}))

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
})
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('DockPanel 状态表与后端枚举一一对应（表来自组件本体）', () => {
  it('每个后端状态都有中文标签与合法 hex 色标', () => {
    for (const s of BACKEND_STATES) {
      expect(STATE_LABELS[s], `组件缺 ${s} 标签`).toBeTruthy()
      expect(STATE_COLORS[s], `组件缺 ${s} 色标`).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('表里没有后端不存在的多余状态（防拼写错误状态名）', () => {
    for (const k of Object.keys(STATE_LABELS)) {
      expect(BACKEND_STATES).toContain(k)
    }
  })
})

describe('DockPanel 动作许可表与后端状态机一致（表来自组件本体）', () => {
  it('door_open 只在 IDLE/CHARGING 可用', () => {
    expect(ACTIONS.find((a) => a.method === 'door_open').allow.sort()).toEqual(['CHARGING', 'IDLE'])
  })

  it('door_close 只在 OPEN 可用', () => {
    expect(ACTIONS.find((a) => a.method === 'door_close').allow).toEqual(['OPEN'])
  })

  it('battery_swap 只在 IDLE/CHARGING 可用', () => {
    expect(ACTIONS.find((a) => a.method === 'battery_swap').allow.sort()).toEqual(['CHARGING', 'IDLE'])
  })

  it('reboot 覆盖 FAULT/MAINTENANCE 与稳态，不含过渡态与离线', () => {
    const allow = ACTIONS.find((a) => a.method === 'reboot').allow
    expect(allow).toContain('FAULT')
    expect(allow).toContain('MAINTENANCE')
    for (const t of ['OFFLINE', 'OPENING', 'CLOSING', 'EXCHANGING']) {
      expect(allow, `reboot 不应允许 ${t}`).not.toContain(t)
    }
  })

  it('动作 method 与后端 DockCommand.method() 字符串一致', () => {
    expect(ACTIONS.map((a) => a.method).sort())
      .toEqual(['battery_swap', 'door_close', 'door_open', 'reboot'])
  })
})

describe('DockPanel 渲染冒烟（真组件、走统一 API 层）', () => {
  it('空列表：渲染机巢区块，不白屏', async () => {
    docksApi.list.mockResolvedValueOnce([])
    render(<DockPanel />)

    await waitFor(() => {
      expect(screen.getByText(/机巢（0）/)).toBeTruthy()
    })
  })

  it('API 抛错：显示错误提示而不是静默空白', async () => {
    docksApi.list.mockRejectedValue(new Error('network down'))
    render(<DockPanel />)

    await waitFor(() => {
      expect(screen.getByText(/network down/)).toBeTruthy()
    })
  })

  it('有数据：渲染每个机巢条目', async () => {
    docksApi.list.mockResolvedValueOnce([
      { id: 1, name: 'dock-A', state: 'IDLE', sn: 'SN-A' },
      { id: 2, name: 'dock-B', state: 'CHARGING', sn: 'SN-B' },
    ])
    render(<DockPanel />)

    await waitFor(() => {
      expect(screen.getByText('dock-A')).toBeTruthy()
    })
    expect(screen.getByText('dock-B')).toBeTruthy()
  })
})
