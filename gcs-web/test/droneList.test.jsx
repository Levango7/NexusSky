import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import DroneList from '../src/components/DroneList.jsx'

/**
 * DroneList 组件级测试（2026-10-24 补口）。
 *
 * 重点是三条**契约**而不是长相：
 * ① "在线 x/y" 的分母是全部设备数（曾经有面板把分母写成在线数）；
 * ② 电量未知时**不套任何分级样式**（utils/battery 的硬契约：把没数据画成绿色是误导）；
 * ③ 键盘可以选中（role=button + tabIndex，触屏之外还得能走键盘）。
 */

const D = (over = {}) => ({
  sysid: 7,
  callsign: 'Alpha',
  online: true,
  battery: 80,
  mode: 'MISSION',
  armed: true,
  ...over,
})

function renderList(drones, onSelect = vi.fn()) {
  const { container } = render(<DroneList drones={drones} selectedSysid={null} onSelect={onSelect} />)
  return { container, items: container.querySelectorAll('li.drone-item'), onSelect }
}

describe('DroneList 汇总与空态', () => {
  it('"在线 x/y"：分子是在线的台数，分母是全部台数', () => {
    const { container } = renderList([D(), D({ sysid: 8, online: false }), D({ sysid: 9 })])
    expect(container.textContent).toContain('2/3 在线')
  })

  it('空列表：给出"暂无设备上线"的引导文案，而不是空白面板', () => {
    const { container } = renderList([])
    expect(container.textContent).toContain('暂无设备上线')
    expect(container.querySelectorAll('li.drone-item')).toHaveLength(0)
  })
})

describe('DroneList 名称与电量', () => {
  it('缺 callsign 时回落成 Drone-<sysid>（不是 undefined）', () => {
    const { items } = renderList([D({ callsign: undefined })])
    expect(items[0].querySelector('.name').textContent).toBe('Drone-7')
    expect(items[0].textContent).not.toContain('undefined')
  })

  it('battery=null 时不套任何分级类（未知 ≠ 电量充足）', () => {
    const { items } = renderList([D({ battery: null })])
    const batt = items[0].querySelector('.batt')
    expect(batt.className.trim()).toBe('batt')
    expect(batt.textContent).toContain('--')
  })

  it('后端的 -1「未知」哨兵显示成 --，且不染红（曾是 "-1%" + 红色）', () => {
    const { items } = renderList([D({ battery: -1 })])
    const batt = items[0].querySelector('.batt')
    expect(batt.textContent.trim()).toBe('--')
    expect(batt.className.trim()).toBe('batt')
    expect(items[0].textContent).not.toContain('-1%')
  })

  it('1% 就显示 1%（本地副本的 `n <= 1 ? n * 100 : n` 曾把它说成 100%）', () => {
    const { items } = renderList([D({ battery: 1 })])
    expect(items[0].querySelector('.batt').textContent).toContain('1%')
    expect(items[0].querySelector('.batt').className).toContain('batt-crit')
  })

  it.each([
    [10, 'batt-crit'],
    [30, 'batt-warn'],
    [80, 'batt-ok'],
  ])('battery=%i 走 utils/battery 的 20/40 口径 → %s', (value, cls) => {
    const { items } = renderList([D({ battery: value })])
    expect(items[0].querySelector('.batt').className).toContain(cls)
  })

  it('battery 是字符串数字时也照样分级（后端偶尔给字符串）', () => {
    const { items } = renderList([D({ battery: '30' })])
    expect(items[0].querySelector('.batt').className).toContain('batt-warn')
  })
})

describe('DroneList 在线/选中样式', () => {
  it('在线与离线各带自己的类，供 CSS 区分', () => {
    const { items } = renderList([D({ online: true }), D({ sysid: 8, online: false })])
    expect(items[0].className).toContain('online')
    expect(items[1].className).toContain('offline')
    expect(items[1].className).not.toContain('online')
  })

  it('只有 selectedSysid 命中的那一项带 selected', () => {
    const { items } = renderList([D(), D({ sysid: 8 })], vi.fn())
    const { items: withSel } = renderListWithSelected([D(), D({ sysid: 8 })], 8)
    expect(items[0].className).not.toContain('selected')
    expect(withSel[0].className).not.toContain('selected')
    expect(withSel[1].className).toContain('selected')
  })

  it('RTK 徽章只在 FIXED / FLOAT 出现，其余状态静默（不画一个空的 "RTK·"）', () => {
    for (const [status, expectBadge] of [['RTK_FIXED', true], ['RTK_FLOAT', true], ['NONE', false], [undefined, false]]) {
      const { items } = renderList([D({ rtkStatus: status })])
      const text = items[0].querySelector('.sub').textContent
      if (expectBadge) {
        expect(text).toContain(status === 'RTK_FIXED' ? 'RTK·F' : 'RTK·f')
      } else {
        expect(text).not.toContain('RTK')
      }
    }
  })
})

describe('DroneList 选中交互', () => {
  it('点击一项 → onSelect 收到该项 sysid', () => {
    const { items, onSelect } = renderList([D(), D({ sysid: 8 })])
    fireEvent.click(items[1])
    expect(onSelect).toHaveBeenCalledWith(8)
  })

  it('键盘 Enter 与空格同样能选中（role=button 不是装饰）', () => {
    const { items, onSelect } = renderList([D({ sysid: 12 })])
    expect(items[0].getAttribute('role')).toBe('button')
    expect(items[0].getAttribute('tabindex')).toBe('0')
    fireEvent.keyDown(items[0], { key: 'Enter' })
    fireEvent.keyDown(items[0], { key: ' ' })
    expect(onSelect).toHaveBeenCalledTimes(2)
    expect(onSelect).toHaveBeenLastCalledWith(12)
  })

  it('其它按键不触发选中（Esc / Tab 不算）', () => {
    const { items, onSelect } = renderList([D()])
    fireEvent.keyDown(items[0], { key: 'Escape' })
    expect(onSelect).not.toHaveBeenCalled()
  })
})

describe('DroneList 模式文案', () => {
  it('已知模式翻成中文', () => {
    const cases = [['STANDBY', '待命'], ['ARMED', '已解锁'], ['MISSION', '任务中'], ['RTL', '返航']]
    for (const [mode, label] of cases) {
      const { items } = renderList([D({ mode })])
      expect(items[0].querySelector('.sub').textContent).toContain(label)
    }
  })

  it('未知模式原样透传（不伪装成任何一个已知状态）', () => {
    const { items } = renderList([D({ mode: 'GUIDED' })])
    expect(items[0].querySelector('.sub').textContent).toContain('GUIDED')
  })

  // 这条原来钉的是"三份 MODE_LABEL 各自为政"的分叉：mode 缺失时这里显示"待命"，
  // DashboardPanel 显示"未知"，TelemetryPanel 显示空白（GAPS §八-1）。
  // 2026-10-25 用户拍板缺省一律「未知」并归一进 utils/statusMeta ⇒ 断言跟着翻，
  // 这正是当初留这条注释的用意：**它该在这里变红，而不是变成阻力**。
  it('mode 缺失 → 「未知」（不再冒充"待命"，与 Dashboard/Telemetry 同一口径）', () => {
    const { items } = renderList([D({ mode: undefined })])
    expect(items[0].querySelector('.sub').textContent).toContain('未知')
  })

  it('后端显式发的 UNKNOWN 也归一成「未知」（旧表里那个小写 unknown 键永远不会命中）', () => {
    const { items } = renderList([D({ mode: 'UNKNOWN' })])
    expect(items[0].querySelector('.sub').textContent).toContain('未知')
  })
})

// helper：带 selectedSysid 的渲染（上面 renderList 固定传 null）
function renderListWithSelected(drones, selectedSysid) {
  const onSelect = vi.fn()
  const { container } = render(
    <DroneList drones={drones} selectedSysid={selectedSysid} onSelect={onSelect} />
  )
  return { items: container.querySelectorAll('li.drone-item'), onSelect }
}
