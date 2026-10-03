import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import TelemetryPanel from '../src/components/TelemetryPanel.jsx'
// 文案实现只有一处：src/utils/flightSafety.js。组件路径的再导出另有一条断言钉住。
import { confirmText } from '../src/utils/flightSafety.js'
import { confirmText as confirmTextViaComponent } from '../src/components/TelemetryPanel.jsx'

/**
 * 回归测试（2026-10-01 新增的飞行安全护栏）：
 * 修复前 TelemetryPanel 的 6 个飞行命令全部单击直达后端
 * （arm / disarm / takeoff / start_mission / rtl / kill），
 * 误触或选错目标机的后果不可逆。
 */

const ARMED_AIRBORNE = { sysid: 7, mode: 'MISSION', armed: true }
const ARMED_STANDBY = { sysid: 7, mode: 'STANDBY', armed: true }
const STANDBY_LOCKED = { sysid: 7, mode: 'STANDBY', armed: false }

let confirmSpy
beforeEach(() => {
  // 默认「确认」；各用例按需覆盖
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
  confirmSpy.mockRestore()
})

function clickButton(label) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(label) }))
}

describe('confirmText — 需要二次确认的命令集', () => {
  it('五个不可逆命令都要确认', () => {
    for (const type of ['arm', 'disarm', 'takeoff', 'start_mission', 'kill']) {
      expect(confirmText(type, ARMED_AIRBORNE, 30), type).toBeTruthy()
    }
  })

  it('rtl 刻意不确认——应急回收动作不该被模态框挡住', () => {
    // 见 TelemetryPanel 顶部注释：QGC / Mission Planner 同样不对 RTL 二次确认。
    // 这条断言把「刻意不确认」固化成契约，若将来有人误加，会立刻红。
    expect(confirmText('rtl', ARMED_AIRBORNE, 30)).toBeNull()
  })

  it('非飞行命令（未知 type）不弹确认', () => {
    expect(confirmText('some_future_cmd', ARMED_AIRBORNE, 30)).toBeNull()
  })

  it('确认文案指名道姓地带上机号，避免机队中误伤', () => {
    expect(confirmText('kill', { sysid: 42 }, 30)).toContain('Drone-42')
  })

  it('callsign 优先于 Drone-N（与 DroneList 命名一致）', () => {
    expect(confirmText('kill', { sysid: 7, callsign: '鹰眼-01' }, 30)).toContain('鹰眼-01')
  })

  it('起飞确认里带上实际爬升高度', () => {
    expect(confirmText('takeoff', STANDBY_LOCKED, 45)).toContain('45')
  })

  it('空中上锁的警告与地面不同', () => {
    const air = confirmText('disarm', ARMED_AIRBORNE, 30)
    const ground = confirmText('disarm', ARMED_LOCKED_SAFE(), 30)
    expect(air).toContain('空中')
    expect(air).toContain('返航')
    expect(ground).not.toContain('空中')
  })

  it('未选择设备时文案明确说明，不静默通过', () => {
    expect(confirmText('kill', null, 30)).toContain('未选择设备')
  })

  it('组件路径的再导出与实现是同一个函数（防止两份实现漂移）', () => {
    expect(confirmTextViaComponent).toBe(confirmText)
  })
})

function ARMED_LOCKED_SAFE() {
  return STANDBY_LOCKED
}

describe('TelemetryPanel — 点「取消」时命令不得下发', () => {
  const cases = [
    ['解锁', 'arm'],
    ['上锁', 'disarm'],
    ['起飞', 'takeoff'],
    ['开始任务', 'start_mission'],
    ['急停', 'kill'],
  ]

  for (const [label, type] of cases) {
    it(`${label}（${type}）被取消时不调用 onCommand`, () => {
      confirmSpy.mockReturnValue(false)
      const onCommand = vi.fn().mockResolvedValue({ status: 'ok', result: 'sent' })
      render(<TelemetryPanel drone={ARMED_AIRBORNE} telemetry={{}} onCommand={onCommand} />)

      clickButton(label)

      expect(confirmSpy).toHaveBeenCalledTimes(1)
      expect(onCommand).not.toHaveBeenCalled()
    })
  }

  it('rtl 单击直达，不弹确认（应急路径保持一键）', async () => {
    const onCommand = vi.fn().mockResolvedValue({ status: 'ok', result: 'sent' })
    render(<TelemetryPanel drone={ARMED_AIRBORNE} telemetry={{}} onCommand={onCommand} />)

    fireEvent.click(screen.getByRole('button', { name: /返航/ }))

    await waitFor(() => expect(onCommand).toHaveBeenCalledWith('rtl', undefined))
    expect(confirmSpy).not.toHaveBeenCalled()
  })
})

describe('TelemetryPanel — 点「确认」时命令照常下发', () => {
  it('急停：确认后调用 onCommand("kill")', async () => {
    const onCommand = vi.fn().mockResolvedValue({ status: 'ok', result: 'ack' })
    render(<TelemetryPanel drone={ARMED_AIRBORNE} telemetry={{}} onCommand={onCommand} />)

    clickButton('急停')

    await waitFor(() => expect(onCommand).toHaveBeenCalledWith('kill', undefined))
  })

  it('起飞：确认后带上高度参数', async () => {
    const onCommand = vi.fn().mockResolvedValue({ status: 'ok', result: 'ack' })
    render(<TelemetryPanel drone={ARMED_LOCKED_SAFE_OBJ} telemetry={{}} onCommand={onCommand} />)

    clickButton('起飞')

    await waitFor(() => expect(onCommand).toHaveBeenCalledWith('takeoff', 30))
  })

  it('上锁：确认后调用 onCommand("disarm")', async () => {
    const onCommand = vi.fn().mockResolvedValue({ status: 'ok', result: 'ack' })
    render(<TelemetryPanel drone={ARMED_AIRBORNE} telemetry={{}} onCommand={onCommand} />)

    clickButton('上锁')

    await waitFor(() => expect(onCommand).toHaveBeenCalledWith('disarm', undefined))
  })
})

// 上锁按钮与摇杆按钮同名，测试里用更精确的对象
const ARMED_LOCKED_SAFE_OBJ = { sysid: 7, mode: 'STANDBY', armed: true }
