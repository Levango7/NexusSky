import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import TelemetryCharts from '../src/components/TelemetryCharts.jsx'

/**
 * 回归测试（2026-10-01 修复的 bug）：
 * 修复前 `series()` 遍历整个扁平 history 且**从不过滤 sysid**，
 * 于是电量/高度/速度曲线把机队里所有飞机的数据交错画在一起。
 *
 * 断言方式：CurveCard 右上角显示的是序列**最后一个点**的值。
 * 构造「别的机排在最后」的历史，若不过滤，卡片就会显示别机的值——
 * 这条断言在修复前必然失败。
 */
function cardByTitle(title) {
  // 每张卡片结构：.tchart-card > .tchart-head > (.tchart-title, .tchart-val)
  const el = document.evaluate(
    `//div[contains(@class,'tchart-card')][.//span[contains(@class,'tchart-title') and text()='${title}']]`,
    document,
    null,
    XPathResult.FIRST_ORDERED_NODE_TYPE,
    null
  ).singleNodeValue
  if (!el) throw new Error(`card "${title}" not rendered`)
  return el
}

function cardValue(title) {
  const el = cardByTitle(title)
  const val = el.querySelector('.tchart-val')
  return val.textContent.replace(/[^\d.-]/g, '')
}

const now = Date.now()

describe('TelemetryCharts — 按 sysid 过滤曲线数据', () => {
  it('选中机的曲线不会被其它机的数据覆盖（核心回归）', () => {
    // 故意把 drone 2 放在数组**最后**：不过滤时末点就是 drone 2
    const history = [
      { ts: now - 2000, sysid: 1, battery: 77, voltage: 15200, relativeAlt: 12, groundspeed: 5 },
      { ts: now - 1000, sysid: 2, battery: 12, voltage: 14000, relativeAlt: 90, groundspeed: 20 },
    ]
    render(<TelemetryCharts telemetry={{}} history={history} sysid={1} />)

    expect(cardValue('电量')).toBe('77')
    expect(cardValue('高度')).toBe('12.0')
  })

  it('切到另一架机时，曲线随之切到那架机的数据', () => {
    const history = [
      { ts: now - 2000, sysid: 1, battery: 77, voltage: 15200, relativeAlt: 12, groundspeed: 5 },
      { ts: now - 1000, sysid: 2, battery: 12, voltage: 14000, relativeAlt: 90, groundspeed: 20 },
    ]
    render(<TelemetryCharts telemetry={{}} history={history} sysid={2} />)

    expect(cardValue('电量')).toBe('12')
    expect(cardValue('高度')).toBe('90.0')
  })

  it('该机无数据时不借用别机的点（显示 --）', () => {
    const history = [{ ts: now - 1000, sysid: 2, battery: 12, voltage: 14000, relativeAlt: 90 }]
    render(<TelemetryCharts telemetry={{}} history={history} sysid={1} />)

    // 该机没有任何点 → 卡片显示 '--'，而不是借用 drone 2 的 12
    expect(cardValue('电量')).toBe('--')
  })

  it('实时 telemetry 追加的末点带上 sysid，不会被过滤丢掉', () => {
    // telemetry 里的值与 history 末点不同：若追加点缺 sysid 被过滤掉，
    // 卡片会退回显示 history 的值（这里是 77）；带上则显示实时的 55。
    const history = [{ ts: now - 5000, sysid: 1, battery: 77 }]
    render(
      <TelemetryCharts
        telemetry={{ sysid: 1, battery: 55, voltage: 15000, relativeAlt: 30, groundspeed: 4, heading: 90 }}
        history={history}
        sysid={1}
      />
    )
    expect(cardValue('电量')).toBe('55')
  })

  it('未选机（sysid 为 null）时不按机过滤，单机场景行为不变', () => {
    const history = [{ ts: now - 1000, sysid: 1, battery: 66 }]
    render(<TelemetryCharts telemetry={{}} history={history} sysid={null} />)
    expect(cardValue('电量')).toBe('66')
  })

  it('空 history / 非数组输入不崩', () => {
    expect(() => render(<TelemetryCharts telemetry={{}} history={[]} sysid={1} />)).not.toThrow()
    expect(() => render(<TelemetryCharts telemetry={{}} history={undefined} sysid={1} />)).not.toThrow()
    expect(() => render(<TelemetryCharts telemetry={{}} history={null} sysid={1} />)).not.toThrow()
  })

  it('窗口外（超过 60s）的点被丢弃', () => {
    const history = [
      { ts: now - 120000, sysid: 1, battery: 99 }, // 窗口外
      { ts: now - 1000, sysid: 1, battery: 44 },
    ]
    render(<TelemetryCharts telemetry={{}} history={history} sysid={1} />)
    expect(cardValue('电量')).toBe('44')
  })
})
