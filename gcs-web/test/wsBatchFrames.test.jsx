import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import useWebSocket from '../src/hooks/useWebSocket.js'

/**
 * P0 钉测：周期遥测主链的「批帧（batch）拆包」。
 *
 * 后端 `TelemetryPusher.pushOnce()`（1Hz，按租户分组）把每个 online drone 的
 * telemetry/status 帧打包成 `{ type:'batch', items:[ {type,sysid,data}, ... ] }`
 * 经 `broadcastToTenant` 下发；而 `useWebSocket.onmessage` 此前只有
 * `telemetry/status/alert/formation/...` 单帧分支，**没有 `batch` 分支**——
 * batch 整帧在 onmessage 末尾被静默丢弃，里面的遥测帧从未进入缓冲。
 *
 * 后果（断链且无任何信号变红）：WS 显示 open、告警/自定义事件能到，
 * 但**周期遥测快照、历史曲线、多机轨迹永不更新**。
 * `wsFrameConsumption.test.js` 的守卫覆盖不到它：batch 不是 WS_TYPE_MAP 的
 * MAVLink 事件总线帧，而是 TelemetryPusher 直接 broadcast 的专用 pusher 帧。
 *
 * 本钉测是**行为级**的：用真实 batch 形状驱动 hook，断言 items 被逐条消费。
 * 先红（修复前 history 为空）后绿（修复后 items 进 history），证明判别有判别力。
 */

const FLUSH_MS = 100

vi.mock('../src/api.js', () => ({
  getWsUrl: () => 'ws://localhost:8080/ws/telemetry',
}))

/** 可控的假 WebSocket：与 telemetryHistory.test.jsx 同款机制。 */
class FakeWebSocket {
  constructor(url) {
    this.url = url
    this.onopen = null
    this.onclose = null
    this.onmessage = null
    this.closed = false
    FakeWebSocket.instances.push(this)
  }
  close() { this.closed = true }
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
function emitAndFlush(ws, payloads) {
  act(() => { for (const p of payloads) ws.emit(p) })
  act(() => { vi.advanceTimersByTime(FLUSH_MS) })
}

// —— 与后端 TelemetryPusher.frameMap 一致的帧形状 {type,sysid,data} ——
function telemetryMsg(sysid, data) {
  return { type: 'telemetry', sysid, data: { lat: 22.59, lon: 113.93, ...data } }
}
function statusMsg(sysid, data) {
  return { type: 'status', sysid, data: { ...data } }
}
/** 真实 batch 帧：{ type:'batch', items:[帧,...] } */
function batchMsg(items) {
  return { type: 'batch', items }
}

describe('useWebSocket — batch 帧拆包（P0 周期遥测主链）', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('batch 帧里的遥测被逐条拆包消费（修复前整帧被丢弃、history 为空）', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    emitAndFlush(ws, [
      batchMsg([telemetryMsg(1, { battery: 90 })]),
      batchMsg([telemetryMsg(1, { battery: 88 })]),
    ])

    const h = result.current.telemetryHistory
    expect(h[1]).toHaveLength(2)
    expect(h[1].map((p) => p.battery)).toEqual([90, 88])
  })

  it('一个 batch 里同时含 telemetry + status：两类都被拆包进缓冲', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    // 后端 pushOnce 对每机同时塞 telemetry 与 status 两帧进同一 batch
    emitAndFlush(ws, [
      batchMsg([telemetryMsg(1, { battery: 90 }), statusMsg(1, { mode: 'AUTO', battery: 91 })]),
    ])

    const h = result.current.telemetryHistory
    expect(h[1]).toHaveLength(2)
    const batteries = h[1].map((p) => p.battery)
    expect(batteries).toContain(90) // telemetry 帧
    expect(batteries).toContain(91) // status 帧
  })

  it('一个 batch 里多机的帧分别进各自桶，互不污染', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    emitAndFlush(ws, [
      batchMsg([telemetryMsg(1, { battery: 90 }), telemetryMsg(2, { battery: 30 })]),
    ])

    const h = result.current.telemetryHistory
    expect(Object.keys(h).sort()).toEqual(['1', '2'])
    expect(h[1]).toHaveLength(1)
    expect(h[2]).toHaveLength(1)
    expect(h[1][0].battery).toBe(90)
    expect(h[2][0].battery).toBe(30)
  })

  it('选中机快照：batch 里的遥测同样驱动 setTelemetry（合批后一次）', () => {
    const ref = { current: 1 }
    const setTelemetry = vi.fn()
    renderHook(() => useWebSocket(ref, setTelemetry))
    const ws = latestSocket()

    emitAndFlush(ws, [
      batchMsg([telemetryMsg(1, { battery: 90 }), telemetryMsg(1, { battery: 87 })]),
    ])

    expect(setTelemetry).toHaveBeenCalledTimes(1)
    const merged = setTelemetry.mock.calls[0][0](null)
    expect(merged.sysid).toBe(1)
    expect(merged.battery).toBe(87) // 最后一帧
  })

  it('单帧（非 batch）行为不变：抽函数重构不回退既有单帧处理', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    emitAndFlush(ws, [telemetryMsg(1, { battery: 90 }), telemetryMsg(1, { battery: 88 })])

    expect(result.current.telemetryHistory[1].map((p) => p.battery)).toEqual([90, 88])
  })

  it('畸形 batch：items 非数组 / 缺 items，不炸、不写历史', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      ws.emit({ type: 'batch', items: 'not-an-array' })
      ws.emit({ type: 'batch' }) // 无 items
      ws.emit({ type: 'batch', items: null })
    })
    act(() => { vi.advanceTimersByTime(FLUSH_MS) })

    expect(Object.keys(result.current.telemetryHistory)).toHaveLength(0)
  })

  it('空 batch（items=[]）：不炸、不写历史', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    emitAndFlush(ws, [batchMsg([])])

    expect(Object.keys(result.current.telemetryHistory)).toHaveLength(0)
  })
})
