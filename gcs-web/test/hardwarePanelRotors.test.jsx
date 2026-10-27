import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import HardwarePanel from '../src/components/HardwarePanel.jsx'
import { api } from '../src/api.js'

/**
 * HardwarePanel 旋翼遥测行的契约测试（GAPS §八.6 收口，2026-10-26）。
 *
 * 裁决依据（读的是后端代码不是猜的）：`/rotor/telemetry/{sysid}` 恒返回单台机的
 * **扁平**旋翼视图——`HardwareDataController.rotorTelemetryView` 字段齐全，
 * `RotorController.getTelemetry` 是 computeIfAbsent 永不为 null——
 * 既没有 `{rotors:[...]}` 形状，也不可能返回 `{}`。
 *
 * 旧写法 `rotor?.rotors || (rotor ? [rotor] : [])` 的前者是对不存在契约的死防御，
 * 后者会把任何 truthy 异形载荷当一台旋翼渲染成整行 `--`。修复后：只有带契约键
 * `rotorIndex` 的载荷才算一台旋翼（0 是合法值，用 != null 判）。
 *
 * api 全部 vi.mock：面板挂载即 1Hz 轮询，不 mock 会在测试里打真请求。
 */

vi.mock('../src/api.js', () => ({
  // HardwarePanel 走的是命名导出的 `api` 对象（import { api } from '../api.js'）
  api: {
    getRadarStatus: vi.fn().mockResolvedValue(null),
    getRadarTargets: vi.fn().mockResolvedValue(null),
    getRotorTelemetry: vi.fn().mockResolvedValue(null),
    getLidarData: vi.fn().mockResolvedValue(null),
    getImuData: vi.fn().mockResolvedValue(null),
    configureRadar: vi.fn().mockResolvedValue(null),
  },
}))

const DRONES = [{ sysid: 1, online: true }]

function renderPanel() {
  return render(<HardwarePanel drones={DRONES} />)
}

beforeEach(() => {
  vi.clearAllMocks()
  api.getRadarStatus.mockResolvedValue(null)
  api.getRadarTargets.mockResolvedValue(null)
  api.getRotorTelemetry.mockResolvedValue(null)
  api.getLidarData.mockResolvedValue(null)
  api.getImuData.mockResolvedValue(null)
})

describe('HardwarePanel 旋翼遥测（GAPS §八.6 契约对齐）', () => {
  it('契约形状（带 rotorIndex）⇒ 恰一行旋翼数据，rpm 真值上屏', async () => {
    api.getRotorTelemetry.mockResolvedValue({
      sysid: 1, rotorIndex: 0, rpm: 5200, thrust: 1.2,
      power: 88, totalThrust: 4.8, totalPower: 352, lastUpdateTime: 1700000000000,
    })
    renderPanel()
    await waitFor(() => expect(screen.getByText(/5200/)).toBeTruthy())
    // 契约视图是一台机一份：不因异形分支产生第二行
    expect(screen.getAllByText(/5200/)).toHaveLength(1)
    expect(screen.queryByText('暂无旋翼数据')).toBeNull()
  })

  it('无数据（404 → null）⇒ 空态文案，零行渲染', async () => {
    renderPanel()
    await waitFor(() => expect(screen.getByText('暂无旋翼数据')).toBeTruthy())
  })

  it('异形载荷 {}（GAPS 点名的形态）⇒ 不当旋翼，空态', async () => {
    api.getRotorTelemetry.mockResolvedValue({})
    renderPanel()
    await waitFor(() => expect(screen.getByText('暂无旋翼数据')).toBeTruthy())
  })

  it('异形载荷 {rotors: []}（旧死分支防御的形状）⇒ 同样不当旋翼', async () => {
    api.getRotorTelemetry.mockResolvedValue({ rotors: [] })
    renderPanel()
    await waitFor(() => expect(screen.getByText('暂无旋翼数据')).toBeTruthy())
  })

  it('rotorIndex=0 是合法契约值（!= null 判，不误杀 0 号旋翼）', async () => {
    api.getRotorTelemetry.mockResolvedValue({ sysid: 1, rotorIndex: 0, rpm: 4800, thrust: 0.9, power: 70 })
    renderPanel()
    await waitFor(() => expect(screen.getByText('4800')).toBeTruthy())
    expect(screen.queryByText('暂无旋翼数据')).toBeNull()
  })
})
