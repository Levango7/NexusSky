import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import AutoDispatchPanel from '../src/components/AutoDispatchPanel.jsx'
import CommAdaptPanel from '../src/components/CommAdaptPanel.jsx'
import {
  getAutoDispatchConfig,
  updateAutoDispatchConfig,
  getCommConfig,
  updateCommConfig,
} from '../src/api.js'

/**
 * 「服务端当前生效配置」的渲染测试（2026-10-17）。
 *
 * 两处接线缺口：`AutoDispatchPanel.config` 与 `CommAdaptPanel.config`
 * 都由后端回填（L120/L140 与 L131/L224），但此前**从头到尾没人读**。
 *
 * 两处的缺失程度**不一样**，台账上一视同仁地记成"没有回显"是不准的：
 *   * AutoDispatchPanel：失败路径有 `setError`，**成功路径零反馈** ——
 *     用户点完"保存配置"看不到任何东西，无法判断是否生效，容易重复点击。
 *   * CommAdaptPanel：`configSuccess`（"配置保存成功"）本来就在渲染，
 *     所以它**不缺**"成没成功"的反馈；缺的是"服务端认了什么"——
 *     表单显示的是编辑缓冲 `configForm`，后端是否采纳、采纳成什么只能靠猜。
 *
 * 修复方式统一为：显示后端回填的真值。这是**持久回显**而不是一次性 toast，
 * 因此判据不只是"点完保存有提示"，而是"生效值必须跟着后端返回值变"。
 */

vi.mock('../src/api.js', () => ({
  // AutoDispatchPanel
  triggerAutoDispatch: vi.fn(),
  getAutoDispatchHistory: vi.fn().mockResolvedValue([]),
  getAutoDispatchActive: vi.fn().mockResolvedValue([]),
  abortAutoDispatch: vi.fn().mockResolvedValue({}),
  getAutoDispatchConfig: vi.fn(),
  updateAutoDispatchConfig: vi.fn(),
  getVideoStreamUrl: vi.fn().mockResolvedValue(null),
  startVideoStream: vi.fn().mockResolvedValue({}),
  stopVideoStream: vi.fn().mockResolvedValue({}),
  getActiveVideoStreams: vi.fn().mockResolvedValue([]),
  startVoiceIntercom: vi.fn().mockResolvedValue({}),
  stopVoiceIntercom: vi.fn().mockResolvedValue({}),
  broadcastVoice: vi.fn().mockResolvedValue({}),
  // CommAdaptPanel
  getCommLinks: vi.fn().mockResolvedValue([]),
  getCommScore: vi.fn().mockResolvedValue(null),
  getCommDecision: vi.fn().mockResolvedValue([]),
  executeCommFailover: vi.fn().mockResolvedValue({}),
  getCommFailoverHistory: vi.fn().mockResolvedValue([]),
  getCommConfig: vi.fn(),
  updateCommConfig: vi.fn(),
}))

// 键名与取值全部照抄后端 configToMap 的权威清单：
//   AutoDispatchController.configToMap → enabled / minBatteryPct / maxDispatchDistanceM
//     / defaultDroneCount / hoverAltitudeM / hoverDurationSec
//   CommSituationController.configToMap → switchThreshold / failoverThreshold
//     / detectionIntervalMs / autoSwitchEnabled / minStableTimeMs
const dispatchConfig = (over = {}) => ({
  enabled: true,
  minBatteryPct: 30,
  maxDispatchDistanceM: 5000,
  defaultDroneCount: 2,
  hoverAltitudeM: 50,
  hoverDurationSec: 300,
  ...over,
})

const commConfig = (over = {}) => ({
  switchThreshold: 0.65,
  failoverThreshold: 0.8,
  detectionIntervalMs: 5000,
  autoSwitchEnabled: true,
  minStableTimeMs: 15000,
  ...over,
})

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
})
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('AutoDispatchPanel：保存后能看到服务端认了什么', () => {
  it('加载完成后显示服务端当前生效的出警配置（原先 config 没人读）', async () => {
    getAutoDispatchConfig.mockResolvedValue(dispatchConfig())
    render(<AutoDispatchPanel />)

    await waitFor(() => expect(screen.getByText(/当前生效/)).toBeTruthy())
    expect(screen.getByText(/当前生效.*已启用/)).toBeTruthy()
    expect(screen.getByText(/最低电量 30%/)).toBeTruthy()
    expect(screen.getByText(/最大距离 5000m/)).toBeTruthy()
    expect(screen.getByText(/默认机数 2/)).toBeTruthy()
    expect(screen.getByText(/悬停高度 50m/)).toBeTruthy()
    expect(screen.getByText(/悬停时长 300s/)).toBeTruthy()
  })

  it('保存成功后生效值跟着后端返回值变（决定性判据）', async () => {
    getAutoDispatchConfig.mockResolvedValue(dispatchConfig())
    // 后端保存后的真值与表单里不一样：这里只认后端返回的
    updateAutoDispatchConfig.mockResolvedValue(
      dispatchConfig({ enabled: false, minBatteryPct: 45 })
    )
    render(<AutoDispatchPanel />)
    await waitFor(() => expect(screen.getByText(/当前生效/)).toBeTruthy())

    fireEvent.click(screen.getByText('保存配置'))
    await waitFor(() => expect(updateAutoDispatchConfig).toHaveBeenCalled())

    await waitFor(() => expect(screen.getByText(/当前生效.*未启用/)).toBeTruthy())
    expect(screen.getByText(/最低电量 45%/)).toBeTruthy()
    // 旧值不该还在 —— 否则说明生效行根本没跟着后端走
    expect(screen.queryByText(/最低电量 30%/)).toBeNull()
  })

  it('配置未加载出来时不显示生效行（不拿假数据糊界面）', async () => {
    getAutoDispatchConfig.mockResolvedValue(null)
    render(<AutoDispatchPanel />)

    await waitFor(() => expect(screen.getByText('加载配置中…')).toBeTruthy())
    expect(screen.queryByText(/当前生效/)).toBeNull()
  })
})

describe('CommAdaptPanel：能看到服务端当前生效的自适应配置', () => {
  it('加载完成后显示服务端当前生效的自适应配置', async () => {
    getCommConfig.mockResolvedValue(commConfig())
    render(<CommAdaptPanel />)

    await waitFor(() => expect(screen.getByText(/当前生效/)).toBeTruthy())
    expect(screen.getByText(/切换阈值 0.65/)).toBeTruthy()
    expect(screen.getByText(/故障切换阈值 0.8/)).toBeTruthy()
    expect(screen.getByText(/检测间隔 5000ms/)).toBeTruthy()
    expect(screen.getByText(/最小稳定时间 15000ms/)).toBeTruthy()
    expect(screen.getByText(/自动切换 开/)).toBeTruthy()
  })

  it('保存成功后生效值跟着后端返回值变（决定性判据）', async () => {
    getCommConfig.mockResolvedValue(commConfig())
    updateCommConfig.mockResolvedValue(
      commConfig({ switchThreshold: 0.42, autoSwitchEnabled: false })
    )
    render(<CommAdaptPanel />)
    await waitFor(() => expect(screen.getByText(/当前生效/)).toBeTruthy())

    fireEvent.click(screen.getByText('保存配置'))
    await waitFor(() => expect(updateCommConfig).toHaveBeenCalled())

    await waitFor(() => expect(screen.getByText(/切换阈值 0.42/)).toBeTruthy())
    expect(screen.getByText(/自动切换 关/)).toBeTruthy()
    expect(screen.queryByText(/切换阈值 0.65/)).toBeNull()
  })

  it('原有的 configSuccess 成功提示仍在（本次没有拿掉任何已有反馈）', async () => {
    getCommConfig.mockResolvedValue(commConfig())
    updateCommConfig.mockResolvedValue(commConfig())
    render(<CommAdaptPanel />)
    // 刻意**不**等「当前生效」那一行：这条用例守的是"没拿掉已有反馈"，
    // 若拿新渲染块当等待前提，它就成了新渲染块的附庸，守不到该守的东西。
    await waitFor(() => expect(screen.getByLabelText('切换阈值')).toBeTruthy())

    fireEvent.click(screen.getByText('保存配置'))
    await waitFor(() => expect(screen.getByText('✓ 配置保存成功')).toBeTruthy())
  })
})