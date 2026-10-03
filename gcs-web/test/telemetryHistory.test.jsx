import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import useWebSocket from '../src/hooks/useWebSocket.js'

// useWebSocket 只用到 api.js 的 getWsUrl，直接把整个模块替换掉，
// 避免测试去碰 sessionStorage / fetch。
vi.mock('../src/api.js', () => ({
  getWsUrl: () => 'ws://localhost:8080/ws/telemetry',
}))

/** 可控的假 WebSocket：记录实例，测试用 emit() 往 onmessage 灌数据 */
class FakeWebSocket {
  constructor(url) {
    this.url = url
    this.onopen = null
    this.onclose = null
    this.onmessage = null
    this.closed = false
    FakeWebSocket.instances.push(this)
  }
  close() {
    this.closed = true
  }
  emit(payload) {
    if (this.onmessage) this.onmessage({ data: JSON.stringify(payload) })
  }
}
FakeWebSocket.instances = []

function mountHook(selectedSysid = 1) {
  const ref = { current: selectedSysid }
  const setTelemetry = vi.fn()
  return renderHook(() => useWebSocket(ref, setTelemetry))
}

function latestSocket() {
  const s = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
  if (!s) throw new Error('WebSocket was never constructed')
  return s
}

function telemetryMsg(sysid, data) {
  return { type: 'telemetry', sysid, data: { lat: 22.59, lon: 113.93, ...data } }
}

describe('useWebSocket — telemetryHistory 按 sysid 分桶', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /**
   * 回归测试（2026-10-01 修复的 bug）：
   * 修复前 telemetryHistory 是一个**扁平数组**，所有机型的点混在一起；
   * 消费端从不过滤 sysid → 2 架机以上时遥测曲线把不同飞机的数据交错画在一起。
   */
  it('两架机的遥测点分属不同桶，互不污染', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      ws.emit(telemetryMsg(1, { battery: 90, relativeAlt: 10 }))
      ws.emit(telemetryMsg(2, { battery: 30, relativeAlt: 50 }))
      ws.emit(telemetryMsg(1, { battery: 88, relativeAlt: 11 }))
    })

    const h = result.current.telemetryHistory
    expect(h).not.toBeInstanceOf(Array) // 形状已从数组变为按机分桶的对象
    expect(Object.keys(h).sort()).toEqual(['1', '2'])
    expect(h[1]).toHaveLength(2)
    expect(h[2]).toHaveLength(1)
    expect(h[1].map((p) => p.battery)).toEqual([90, 88])
    expect(h[2].map((p) => p.battery)).toEqual([30])
    expect(h[1].every((p) => p.sysid === 1)).toBe(true)
    expect(h[2].every((p) => p.sysid === 2)).toBe(true)
  })

  it('每桶独立计数：一架机狂刷不会挤掉另一架的历史', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      for (let i = 0; i < 5; i++) ws.emit(telemetryMsg(1, { battery: 90 }))
      ws.emit(telemetryMsg(2, { battery: 30 }))
    })

    expect(result.current.telemetryHistory[1]).toHaveLength(5)
    expect(result.current.telemetryHistory[2]).toHaveLength(1)
  })

  it('单桶上限 300：超出后丢弃最旧的点', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      for (let i = 0; i < 320; i++) ws.emit(telemetryMsg(1, { battery: i }))
    })

    const bucket = result.current.telemetryHistory[1]
    expect(bucket).toHaveLength(300)
    // 保留的是**最新**的 300 个：首点应是第 20 个（i=20），末点应是 i=319
    expect(bucket[0].battery).toBe(20)
    expect(bucket[299].battery).toBe(319)
  })

  it('多机同时超限时各自独立截断，互不影响', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      for (let i = 0; i < 310; i++) ws.emit(telemetryMsg(1, { battery: i }))
      for (let i = 0; i < 305; i++) ws.emit(telemetryMsg(2, { battery: i }))
    })

    const h = result.current.telemetryHistory
    expect(h[1]).toHaveLength(300)
    expect(h[2]).toHaveLength(300)
    expect(h[1][299].battery).toBe(309)
    expect(h[2][299].battery).toBe(304)
  })

  it('setTelemetry 只在消息属于当前选中机时调用（原有行为未回退）', () => {
    const ref = { current: 1 }
    const setTelemetry = vi.fn()
    renderHook(() => useWebSocket(ref, setTelemetry))
    const ws = latestSocket()

    act(() => {
      ws.emit(telemetryMsg(2, { battery: 30 }))
      ws.emit(telemetryMsg(1, { battery: 90 }))
    })

    // 只有 drone 1 的那条触发了 setTelemetry
    expect(setTelemetry).toHaveBeenCalledTimes(1)
    // setTelemetry 收到的是 React updater 函数，应用它才能看到合并结果
    const merged = setTelemetry.mock.calls[0][0](null)
    expect(merged.sysid).toBe(1)
    expect(merged.battery).toBe(90)
  })

  it('malformed JSON 不炸、不写历史', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      ws.onmessage({ data: 'not json at all' })
    })

    expect(Object.keys(result.current.telemetryHistory)).toHaveLength(0)
  })

  it('卸载时关闭 socket 且不再重连', () => {
    const { unmount } = mountHook(1)
    const ws = latestSocket()
    expect(ws.closed).toBe(false)
    unmount()
    expect(ws.closed).toBe(true)
  })
})
