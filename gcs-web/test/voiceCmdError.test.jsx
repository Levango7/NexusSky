import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import VoiceCmdPanel from '../src/components/VoiceCmdPanel.jsx'
import {
  parseVoiceCommand,
  executeVoiceCommand,
  confirmVoiceCommand,
  broadcastVoiceMessage,
  getVoiceStatus,
  broadcastVoiceAlert,
  getVoiceHistory,
  getVoicePending,
} from '../src/api.js'

/**
 * VoiceCmdPanel 错误通路测试。
 *
 * ## 为什么需要它
 *
 * 此前这个面板的"错误通路"在**三个层面同时是死的**，任何单看渲染或单看状态都发现不了：
 *
 * 1. **写入被吞**：轮询里给每个请求挂了 `.catch(() => [])`，异常在到达
 *    `Promise.all` 的外层 catch 之前就没了，于是那个 catch 是**进不去的死代码**，
 *    `setPendingError(e.message)` 从未执行过。
 * 2. **唯一的真实消息被覆盖**：`pendingError` 拿到真实内容只有 `handleConfirm`
 *    失败时那一处，而**每轮轮询（POLL_MS=5000）无条件 `setPendingError(null)`**，
 *    5 秒内就被抹掉。
 * 3. **没有渲染点**：即使状态有值，界面也不显示。
 *
 * 三层叠加的结果：语音指令确认失败时，操作员拿不到任何提示 —— 而
 * `setPendingError('确认失败：' + ...)` 的存在让代码看起来"已经处理了"。
 *
 * 本测试逐层钉住：写入要能到达状态、状态要能显示、且**确认失败不能被轮询覆盖**
 * （最后这条是区分"真修好"与"只是加了个渲染"的关键）。
 */

vi.mock('../src/api.js', () => ({
  parseVoiceCommand: vi.fn().mockResolvedValue({}),
  executeVoiceCommand: vi.fn().mockResolvedValue({}),
  confirmVoiceCommand: vi.fn().mockResolvedValue({ result: 'OK' }),
  broadcastVoiceMessage: vi.fn().mockResolvedValue({}),
  getVoiceStatus: vi.fn().mockResolvedValue({}),
  broadcastVoiceAlert: vi.fn().mockResolvedValue({}),
  getVoiceHistory: vi.fn().mockResolvedValue([]),
  getVoicePending: vi.fn().mockResolvedValue([]),
}))

const PENDING_ITEM = {
  pendingId: 'p1',
  command: { action: 'takeoff', targetName: 'A1', priority: 'HIGH', rawText: '起飞' },
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  getVoicePending.mockResolvedValue([])
  getVoiceHistory.mockResolvedValue([])
  confirmVoiceCommand.mockResolvedValue({ result: 'OK' })
})

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('VoiceCmdPanel 轮询失败：原因要能显示出来', () => {
  it('取待确认列表失败：显示原因，而不是静默变成空列表', async () => {
    getVoicePending.mockRejectedValue(new Error('pending boom'))
    const { container } = render(<VoiceCmdPanel />)
    await waitFor(() => {
      expect(container.textContent).toContain('待确认列表刷新失败：pending boom')
    })
  })

  it('取指令历史失败：显示原因', async () => {
    getVoiceHistory.mockRejectedValue(new Error('history boom'))
    const { container } = render(<VoiceCmdPanel />)
    await waitFor(() => {
      expect(container.textContent).toContain('history boom')
    })
  })

  it('下一次轮询成功：错误提示随之消失（错误不是粘住的）', async () => {
    getVoicePending.mockRejectedValueOnce(new Error('pending boom'))
    const { container } = render(<VoiceCmdPanel />)
    await waitFor(() => {
      expect(container.textContent).toContain('待确认列表刷新失败：pending boom')
    })
    // 让下一轮轮询成功（mock 的默认实现就是 resolvedValue([])）
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000)
    })
    await waitFor(() => {
      expect(container.textContent).not.toContain('待确认列表刷新失败')
    })
  })
})

describe('VoiceCmdPanel 确认执行失败：显示且不被轮询抹掉', () => {
  it('点「确认执行」失败：显示「确认失败：原因」', async () => {
    getVoicePending.mockResolvedValue([PENDING_ITEM])
    confirmVoiceCommand.mockRejectedValue(new Error('boom'))
    const { container } = render(<VoiceCmdPanel />)
    const btn = await screen.findByText('确认执行')
    await act(async () => {
      fireEvent.click(btn)
    })
    await waitFor(() => {
      expect(container.textContent).toContain('确认失败：boom')
    })
  })

  it('关键：跨过一次轮询周期（POLL_MS=5000）后仍然在', async () => {
    // 这条是本文件的核心判据。原实现把「确认失败：…」写进 pendingError，
    // 而每轮轮询无条件 setPendingError(null) —— 5 秒内就被抹掉，
    // 所以"加个渲染点"根本救不回来，必须让确认失败用独立的状态槽。
    getVoicePending.mockResolvedValue([PENDING_ITEM])
    confirmVoiceCommand.mockRejectedValue(new Error('boom'))
    const { container } = render(<VoiceCmdPanel />)
    const btn = await screen.findByText('确认执行')
    await act(async () => {
      fireEvent.click(btn)
    })
    await waitFor(() => {
      expect(container.textContent).toContain('确认失败：boom')
    })

    // 跨过一个完整轮询周期，并让轮询本身成功
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000)
    })
    expect(
      container.textContent,
      '确认失败信息不应被下一轮轮询抹掉（否则操作员 5 秒后就看不到刚才的失败了）'
    ).toContain('确认失败：boom')
  })

  it('确认成功：待确认列表里那一项被移除，且不显示错误', async () => {
    getVoicePending.mockResolvedValue([PENDING_ITEM])
    confirmVoiceCommand.mockResolvedValue({ result: 'OK' })
    const { container } = render(<VoiceCmdPanel />)
    const btn = await screen.findByText('确认执行')
    await act(async () => {
      fireEvent.click(btn)
    })
    await waitFor(() => {
      expect(container.textContent).not.toContain('确认失败')
    })
    expect(confirmVoiceCommand).toHaveBeenCalledWith('p1')
  })
})

describe('VoiceCmdPanel 反向对照：成功时不得显示错误', () => {
  it('两个请求都成功：面板出空态，且没有任何错误提示', async () => {
    const { container } = render(<VoiceCmdPanel />)
    await waitFor(() => {
      expect(container.textContent).toContain('暂无待确认指令')
    })
    expect(container.textContent).toContain('暂无指令历史')
    expect(container.textContent).not.toContain('刷新失败')
    expect(container.textContent).not.toContain('确认失败')
    expect(container.textContent).not.toContain('boom')
  })
})
