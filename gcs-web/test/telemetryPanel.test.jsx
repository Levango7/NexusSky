import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import TelemetryPanel from '../src/components/TelemetryPanel.jsx'

/**
 * 「阶段 / 固件」两格的展示口径（2026-10-29 补口）。
 *
 * 这两格的数据来自 EXTENDED_SYS_STATE / AUTOPILOT_VERSION，与 mode 不同：
 * mode 是固件相关的导航状态，换一家固件同一个数字含义就变了；所以缺省必须显示
 * 「未知」，绝不能拿 mode 或别的字段顶上冒充真实阶段。
 */
const DRONE = { sysid: 1, mode: 'MISSION', armed: true }

describe('TelemetryPanel 飞行阶段 / 固件版本', () => {
  it('有真值时直接显示协议层给的阶段名与固件版本', () => {
    const t = { landedState: 3, landedStateName: '起飞中', firmwareVersion: '1.14.7', relativeAlt: 12.3 }
    const { container } = render(
      <TelemetryPanel drone={DRONE} telemetry={t} onCommand={async () => ({ status: 'ok' })} />
    )
    expect(container.textContent).toContain('起飞中')
    expect(container.textContent).toContain('1.14.7')
  })

  it('未上报（landedState=-1 / 无版本）显示「未知」与 --，不冒充地面或版本号', () => {
    const t = { landedState: -1, landedStateName: null, firmwareVersion: null }
    const { container } = render(<TelemetryPanel drone={DRONE} telemetry={t} />)
    expect(container.textContent).toContain('未知')
    expect(container.textContent).toContain('--')
    expect(container.textContent).not.toContain('地面')
  })

  it('telemetry 整体缺失也不崩，同样落到占位符', () => {
    const { container } = render(<TelemetryPanel drone={DRONE} />)
    expect(container.textContent).toContain('未知')
    expect(container.textContent).toContain('--')
  })
})
