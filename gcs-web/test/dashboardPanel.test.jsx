import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import DashboardPanel from '../src/components/DashboardPanel.jsx'

/**
 * DashboardPanel 组件级测试（2026-10-24 补口）。
 *
 * 这个面板是"操作员第一眼看到的东西"，所以钉的全是**汇总口径**：
 * ① 平均电量/最高高度只在非 null 的样本上算（把 null 当 0 会凭空拉低机队均值）；
 * ② 告警数的三条来源（离线 / 电量严重档 / GPS 降级）逐条单独生效，且复用 utils/battery
 *    的判级（历史上这里曾与颜色口径分叉）；
 * ③ `gpsHealthy` **缺字段 ≠ 降级**（只有显式 false 才算），少字段的遥测帧不该被报成故障。
 */

const D = (over = {}) => ({
  sysid: 7,
  callsign: 'Alpha',
  online: true,
  battery: 80,
  mode: 'MISSION',
  armed: true,
  relativeAlt: 30,
  groundspeed: 8,
  heading: 90,
  gpsHealthy: true,
  satellites: 12,
  ...over,
})

function renderPanel(drones, onSelect = vi.fn()) {
  const { container } = render(<DashboardPanel drones={drones} onSelect={onSelect} />)
  const cells = [...container.querySelectorAll('.db-sum-cell')].map((c) => c.textContent)
  const findCell = (label) => cells.find((c) => c.startsWith(label))
  return { container, cells, findCell, cards: container.querySelectorAll('.db-card'), onSelect }
}

describe('DashboardPanel 汇总口径', () => {
  it('在线 / 总数：分子按 online，分母按全部', () => {
    const { findCell, container } = renderPanel([D(), D({ sysid: 8, online: false })])
    expect(container.textContent).toContain('1/2 在线')
    expect(findCell('在线 / 总数')).toContain('1')
    expect(findCell('在线 / 总数')).toContain('2')
  })

  it('平均电量只在有电量的样本上算（null 不参与、不当 0）', () => {
    // 80 与 40 的均值 60；若把 null 当 0，三台会算成 40
    const { findCell } = renderPanel([D({ battery: 80 }), D({ sysid: 8, battery: 40 }), D({ sysid: 9, battery: null })])
    expect(findCell('平均电量')).toContain('60%')
  })

  it('电量全缺时平均电量显示 --，而不是 0%', () => {
    const { findCell } = renderPanel([D({ battery: null }), D({ sysid: 8, battery: undefined })])
    expect(findCell('平均电量')).toContain('--')
  })

  it('后端的 -1「未知」哨兵不参与平均（既不当 0，也不当 -1%）', () => {
    const { findCell } = renderPanel([D({ battery: 80 }), D({ sysid: 8, battery: -1 })])
    expect(findCell('平均电量')).toContain('80%')
  })

  it('最高高度取非 null 样本的最大值，四舍五入到整数米', () => {
    const { findCell } = renderPanel([D({ relativeAlt: 12.4 }), D({ sysid: 8, relativeAlt: 57.6 }), D({ sysid: 9, relativeAlt: null })])
    expect(findCell('最高高度')).toContain('58 m')
  })

  it('高度全缺时显示 --', () => {
    const { findCell } = renderPanel([D({ relativeAlt: null })])
    expect(findCell('最高高度')).toContain('--')
  })
})

describe('DashboardPanel 告警计数：三条来源各自生效', () => {
  it('一台离线 → 告警数 +1', () => {
    const { findCell } = renderPanel([D({ online: false })])
    expect(findCell('告警数')).toMatch(/1\s*$/)
  })

  it('电量处于严重档（≤20）→ +1，且判级跟着 utils/battery 走', () => {
    const { findCell } = renderPanel([D({ battery: 20 })])
    expect(findCell('告警数')).toMatch(/1\s*$/)
    // 30 是 warn 不是 crit ⇒ 不计
    expect(renderPanel([D({ battery: 30 })]).findCell('告警数')).toMatch(/0\s*$/)
  })

  it('GPS 显式 false → +1；字段缺失（undefined/true）都不算', () => {
    expect(renderPanel([D({ gpsHealthy: false })]).findCell('告警数')).toMatch(/1\s*$/)
    expect(renderPanel([D({ gpsHealthy: undefined })]).findCell('告警数')).toMatch(/0\s*$/)
    expect(renderPanel([D({ gpsHealthy: true })]).findCell('告警数')).toMatch(/0\s*$/)
  })

  it('三个条件同时命中同一台 ⇒ 计 3（本面板刻意按"事件数"而非"台数"计，钉住这个口径）', () => {
    const { findCell } = renderPanel([D({ online: false, battery: 5, gpsHealthy: false })])
    expect(findCell('告警数')).toMatch(/3\s*$/)
  })

  it('健康机队 → 告警数为 0，且不是空白', () => {
    const { findCell } = renderPanel([D()])
    expect(findCell('告警数')).toMatch(/0\s*$/)
  })

  it('从未上报过电量（-1 哨兵）的设备不得白占一条告警', () => {
    const { findCell } = renderPanel([D({ battery: -1 })])
    expect(findCell('告警数')).toMatch(/0\s*$/)
    // 卡片上也必须是 --，而不是 "-1%"
    expect(renderPanel([D({ battery: -1 })]).container.textContent).not.toContain('-1%')
  })
})

describe('DashboardPanel 卡片', () => {
  it('空列表：显示"暂无设备"引导，不渲染卡片', () => {
    const { cards, container } = renderPanel([])
    // 文案被 <br/> 断成两段，所以按容器整体文本判，不用 getByText 精确匹配
    expect(container.textContent).toContain('暂无设备')
    expect(cards).toHaveLength(0)
  })

  it('drones 传成非数组也不崩，按空处理', () => {
    const { cards } = renderPanel(undefined)
    expect(cards).toHaveLength(0)
  })

  it('每台一张卡，标题是 callsign 或 Drone-<sysid>', () => {
    const { cards } = renderPanel([D(), D({ sysid: 8, callsign: undefined })])
    expect(cards).toHaveLength(2)
    expect(cards[0].textContent).toContain('Alpha')
    expect(cards[1].textContent).toContain('Drone-8')
    expect(cards[1].textContent).toContain('#8')
  })

  it('电量缺失：卡片显示 -- 且 fill 宽度归零（不画成满电）', () => {
    const { container } = renderPanel([D({ battery: null })])
    const fill = container.querySelector('.db-batt-fill')
    expect(fill.style.width).toBe('0%')
    expect(container.querySelector('.db-card-body').textContent).toContain('--')
  })

  it('高度/地速为 0 时显示 0.0 而不是被当成缺失（0 是有效读数）', () => {
    const { container } = renderPanel([D({ relativeAlt: 0, groundspeed: 0 })])
    const body = container.querySelector('.db-card-body').textContent
    expect(body).toContain('0.0 m')
    expect(body).toContain('0.0 m/s')
  })

  it('航向缺失 → --；有值时四舍五入成整数度', () => {
    expect(renderPanel([D({ heading: null })]).container.textContent).toContain('--')
    const withHdg = renderPanel([D({ heading: 274.6 })]).container.textContent
    expect(withHdg).toContain('275°')
  })

  it('GPS 降级在卡片上明写"降级"，正常写"正常"', () => {
    expect(renderPanel([D({ gpsHealthy: false })]).container.textContent).toContain('降级')
    expect(renderPanel([D({ gpsHealthy: true })]).container.textContent).toContain('正常')
  })

  it('卫星数缺失时不显示"· undefined星"', () => {
    const t = renderPanel([D({ satellites: null })]).container.textContent
    expect(t).not.toContain('undefined')
  })

  it('点击卡片回调 sysid；Enter 同样回调', () => {
    const { cards, onSelect } = renderPanel([D({ sysid: 42 })])
    fireEvent.click(cards[0])
    expect(onSelect).toHaveBeenCalledWith(42)
    fireEvent.keyDown(cards[0], { key: 'Enter' })
    expect(onSelect).toHaveBeenLastCalledWith(42)
  })

  it('onSelect 缺失（只读渲染）时点击不崩', () => {
    const { container } = render(<DashboardPanel drones={[D()]} />)
    expect(() => fireEvent.click(container.querySelector('.db-card'))).not.toThrow()
  })
})

describe('DashboardPanel 模式文案（三份 MODE_LABEL 归一后的共用口径）', () => {
  it.each([['STANDBY', '待命'], ['MANUAL', '待命'], ['ARMED', '已解锁'], ['MISSION', '任务中'], ['RTL', '返航']])(
    '%s → %s',
    (mode, label) => {
      expect(renderPanel([D({ mode })]).container.textContent).toContain(label)
    }
  )

  // 2026-10-24 这条当时钉的是"和 DroneList 分叉"（那里显示"待命"、这里显示"未知"、
  // TelemetryPanel 显示空白）。2026-10-25 用户拍板缺省一律「未知」，三份归一进
  // utils/statusMeta —— 分叉消失，但断言本身**留着**：它现在钉的是归一后的共同口径，
  // 谁把缺省改回冒充真实状态，这里就红。
  it('mode 缺失 → 未知', () => {
    const { cards } = renderPanel([D({ mode: undefined })])
    expect(cards[0].querySelector('.db-mode').textContent).toBe('未知')
  })

  it('未识别模式原样透传，class 也跟着落到具体模式名', () => {
    const { container } = renderPanel([D({ mode: 'GUIDED' })])
    expect(container.textContent).toContain('GUIDED')
    expect(container.querySelector('.db-mode').className).toContain('mode-GUIDED')
  })
})
