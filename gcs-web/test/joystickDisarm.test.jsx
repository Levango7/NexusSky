import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import Joystick from '../src/components/Joystick.jsx'

vi.mock('../src/api.js', () => ({
  api: {
    sendJoystick: vi.fn().mockResolvedValue({ status: 'ok' }),
    sendCommand: vi.fn().mockResolvedValue({ status: 'ok' }),
  },
}))

import { api } from '../src/api.js'

const AIRBORNE = { sysid: 7, mode: 'MISSION', armed: true }
const ON_GROUND = { sysid: 7, mode: 'STANDBY', armed: true }

let confirmSpy
beforeEach(() => {
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
  api.sendJoystick.mockClear()
  api.sendCommand.mockClear()
})
afterEach(() => {
  confirmSpy.mockRestore()
  vi.useRealTimers()
})

function clickDisarm() {
  fireEvent.click(screen.getByRole('button', { name: '上锁' }))
}

describe('Joystick — 上锁需要二次确认', () => {
  it('取消时不下发 disarm', () => {
    confirmSpy.mockReturnValue(false)
    render(<Joystick drone={AIRBORNE} />)

    clickDisarm()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(api.sendCommand).not.toHaveBeenCalled()
  })

  it('确认时下发 disarm', async () => {
    render(<Joystick drone={AIRBORNE} />)

    clickDisarm()

    await vi.waitFor(() => expect(api.sendCommand).toHaveBeenCalledWith(7, 'disarm'))
  })

  it('空中上锁的警告文案点名当前 mode', () => {
    render(<Joystick drone={AIRBORNE} />)
    clickDisarm()
    const prompt = confirmSpy.mock.calls[0][0]
    expect(prompt).toContain('Drone-7')
    expect(prompt).toContain('MISSION')
    expect(prompt).toContain('空中')
  })

  it('地面上锁的文案不含空中警告', () => {
    render(<Joystick drone={ON_GROUND} />)
    clickDisarm()
    expect(confirmSpy.mock.calls[0][0]).not.toContain('空中')
  })

  it('未解锁时上锁按钮禁用，点不到', () => {
    render(<Joystick drone={{ sysid: 7, mode: 'STANDBY', armed: false }} />)
    expect(screen.getByRole('button', { name: '上锁' })).toBeDisabled()
  })

  /**
   * 顺序陷阱回归（2026-10-01 加确认时最容易踩的坑）：
   * disarm 的旧实现是「先停发送循环 → 再下发上锁」。若把 confirm 放在停发之后，
   * 用户点「取消」就会留下 sendingRef=false 且没有下发上锁——摇杆变死区而飞机仍在飞。
   * 这条用例证明确认发生在停发之前：取消后发送循环仍在跑。
   */
  it('取消上锁不会掐断正在进行的摇杆发送循环', async () => {
    vi.useFakeTimers()
    confirmSpy.mockReturnValue(false)
    render(<Joystick drone={AIRBORNE} />)

    const pad = document.querySelector('.joypad')
    // 按住摇杆 → engage → 立即发第一帧，随后每 100ms 一帧
    // pointerDown 而非 mouseDown：组件 2026-10-02 起按 Pointer Events 统一
    // 鼠标/触摸/笔输入（onPointerDown + setPointerCapture），jsdom 里
    // fireEvent.mouseDown 不会触发 onPointerDown handler。
    fireEvent.pointerDown(pad, { clientX: 0, clientY: 0, pointerId: 1, isPrimary: true })
    expect(api.sendJoystick).toHaveBeenCalledTimes(1)

    // 按住不放的过程中点了「上锁」又取消
    clickDisarm()
    expect(api.sendCommand).not.toHaveBeenCalled()

    // 关键断言：循环没被停掉——再推进一个周期仍有新帧
    await vi.advanceTimersByTimeAsync(150)
    expect(api.sendJoystick.mock.calls.length).toBeGreaterThan(1)
  })
})
