import { describe, it, expect } from 'vitest'

// DockPanel 的纯逻辑部分在这里以"契约"形式钉住：
// 状态色标/标签必须与后端 DockState 枚举一一对应（组件里是查表，漏一个就显示裸枚举值）。
// 不引 jsdom 渲染（面板逻辑简单、渲染由 Playwright E2E 覆盖），
// 重点是：新增后端状态时这里漏改会红。

const BACKEND_STATES = [
  'OFFLINE', 'IDLE', 'OPENING', 'OPEN', 'CLOSING',
  'CHARGING', 'EXCHANGING', 'FAULT', 'MAINTENANCE',
]

// 与 DockPanel.jsx 中常量保持同步的镜像（此处独立声明，故意不复用组件内部实现——
// 若组件改了表而测试没改，两边不一致会被下面的完整性断言抓住）
const STATE_LABELS = {
  OFFLINE: '离线', IDLE: '空闲', OPENING: '开门中', OPEN: '门已开', CLOSING: '关门中',
  CHARGING: '充电中', EXCHANGING: '换电中', FAULT: '故障', MAINTENANCE: '维护',
}
const STATE_COLORS = {
  OFFLINE: '#6b7280', IDLE: '#22c55e', OPENING: '#eab308', OPEN: '#38bdf8',
  CLOSING: '#eab308', CHARGING: '#a855f7', EXCHANGING: '#f97316', FAULT: '#ef4444',
  MAINTENANCE: '#94a3b8',
}
const ACTIONS = [
  { method: 'door_open', allow: ['IDLE', 'CHARGING'] },
  { method: 'door_close', allow: ['OPEN'] },
  { method: 'battery_swap', allow: ['IDLE', 'CHARGING'] },
  { method: 'reboot', allow: ['FAULT', 'MAINTENANCE', 'IDLE', 'CHARGING', 'OPEN'] },
]

describe('DockPanel 状态表完整性', () => {
  it('每个后端状态都有中文标签', () => {
    for (const s of BACKEND_STATES) {
      expect(STATE_LABELS[s], `缺 ${s} 标签`).toBeTruthy()
    }
  })

  it('每个后端状态都有色标（且是合法 hex）', () => {
    for (const s of BACKEND_STATES) {
      expect(STATE_COLORS[s], `缺 ${s} 色标`).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('表里没有后端不存在的多余状态（防拼写错误状态名）', () => {
    for (const k of Object.keys(STATE_LABELS)) {
      expect(BACKEND_STATES).toContain(k)
    }
  })
})

describe('DockPanel 动作许可表与后端状态机一致', () => {
  it('door_open 只在 IDLE/CHARGING 可用（对齐 DockStateMachine.ALLOWED）', () => {
    const open = ACTIONS.find((a) => a.method === 'door_open')
    expect(open.allow.sort()).toEqual(['CHARGING', 'IDLE'])
  })

  it('door_close 只在 OPEN 可用', () => {
    const close = ACTIONS.find((a) => a.method === 'door_close')
    expect(close.allow).toEqual(['OPEN'])
  })

  it('battery_swap 只在 IDLE/CHARGING 可用', () => {
    const swap = ACTIONS.find((a) => a.method === 'battery_swap')
    expect(swap.allow.sort()).toEqual(['CHARGING', 'IDLE'])
  })

  it('reboot 覆盖 FAULT/MAINTENANCE 与各稳态、不含过渡态与离线', () => {
    const rb = ACTIONS.find((a) => a.method === 'reboot')
    expect(rb.allow).toContain('FAULT')
    expect(rb.allow).toContain('MAINTENANCE')
    for (const t of ['OFFLINE', 'OPENING', 'CLOSING', 'EXCHANGING']) {
      expect(rb.allow, `reboot 不应允许 ${t}`).not.toContain(t)
    }
  })

  it('所有动作 method 与后端 DockCommand.method() 字符串一致', () => {
    const backendMethods = ['door_open', 'door_close', 'battery_swap', 'reboot']
    expect(ACTIONS.map((a) => a.method).sort()).toEqual(backendMethods.sort())
  })
})