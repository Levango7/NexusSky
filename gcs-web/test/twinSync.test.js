import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import useWebSocket from '../src/hooks/useWebSocket.js'
import { normalizeTwinState, twinSyncFreshness, TWIN_BATTERY_UNKNOWN } from '../src/utils/twinSync.js'

// ---- 纯函数：normalizeTwinState / twinSyncFreshness ----

describe('normalizeTwinState — MAVLink 原始单位 → 展示单位', () => {
  it('全字段换算：1E7 度 / mm→m / cdeg→deg / battery 原值', () => {
    const raw = {
      twinLat: 225900000,        // 22.59°
      twinLon: 1139300000,       // 113.93°
      twinAlt: 120000,           // 120 m
      syncTimestamp: 1760000000000,
      twinVelocity: 5.5,
      driftMeters: 5.25,
      twinHeading: 9000,         // 90°
      sysId: 7,
      twinBattery: 88,
    }
    const t = normalizeTwinState(raw, 123)
    expect(t.lat).toBeCloseTo(22.59, 6)
    expect(t.lon).toBeCloseTo(113.93, 6)
    expect(t.altM).toBeCloseTo(120, 6)
    expect(t.headingDeg).toBeCloseTo(90, 6)
    expect(t.velocity).toBe(5.5)
    expect(t.driftM).toBe(5.25)
    expect(t.battery).toBe(88)
    expect(t.sysid).toBe(7)
    expect(t.syncTimestamp).toBe(1760000000000)
    expect(t.receivedAt).toBe(123)
  })

  it('航向 40000cdeg（400°）归一到 40°', () => {
    const t = normalizeTwinState({ twinLat: 0, twinLon: 0, twinAlt: 0, twinHeading: 40000 }, 0)
    expect(t.headingDeg).toBeCloseTo(40, 6)
  })

  it(`battery=${TWIN_BATTERY_UNKNOWN}（未知哨兵）→ null`, () => {
    const t = normalizeTwinState({ twinBattery: TWIN_BATTERY_UNKNOWN }, 0)
    expect(t.battery).toBeNull()
  })

  it('字段缺失 → null 而非 NaN/异常', () => {
    const t = normalizeTwinState({}, 0)
    expect(t).not.toBeNull()
    expect(t.lat).toBeNull()
    expect(t.lon).toBeNull()
    expect(t.altM).toBeNull()
    expect(t.headingDeg).toBeNull()
    expect(t.velocity).toBeNull()
    expect(t.driftM).toBeNull()
    expect(t.battery).toBeNull()
    expect(t.sysid).toBeNull()
  })

  it('非对象输入（null/数字/字符串）→ null，不抛异常', () => {
    expect(normalizeTwinState(null, 0)).toBeNull()
    expect(normalizeTwinState(undefined, 0)).toBeNull()
    expect(normalizeTwinState(42, 0)).toBeNull()
    expect(normalizeTwinState('x', 0)).toBeNull()
  })
})

describe('twinSyncFreshness — 5s 新鲜度分档', () => {
  it('5s 内 fresh，超时 stale，无 receivedAt 视为 stale', () => {
    const t = { receivedAt: 1000 }
    expect(twinSyncFreshness(t, 1000 + 4999)).toBe('fresh')
    expect(twinSyncFreshness(t, 1000 + 5001)).toBe('stale')
    expect(twinSyncFreshness(null, 0)).toBe('stale')
    expect(twinSyncFreshness({}, 0)).toBe('stale')
  })
})

// ---- hook 集成：useWebSocket 的 twin-state-sync 分桶 ----

vi.mock('../src/api.js', () => ({
  getWsUrl: () => 'ws://localhost:8080/ws/telemetry',
}))

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

describe('useWebSocket — twin-state-sync 按机分桶', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('两架机各留最新一帧，互不覆盖', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      ws.emit({ type: 'twin-state-sync', sysid: 1, data: { sysId: 1, twinLat: 225900000, twinBattery: 90 } })
      ws.emit({ type: 'twin-state-sync', sysid: 2, data: { sysId: 2, twinLat: 300000000, twinBattery: 40 } })
      ws.emit({ type: 'twin-state-sync', sysid: 1, data: { sysId: 1, twinLat: 225900100, twinBattery: 89 } })
    })

    const ts = result.current.twinStates
    expect(Object.keys(ts).sort()).toEqual(['1', '2'])
    // sysid 1 保留的是最后一帧
    expect(ts[1].twinLat).toBe(225900100)
    expect(ts[1].twinBattery).toBe(89)
    expect(ts[2].twinBattery).toBe(40)
  })

  it('帧带 receivedAt 本地时间戳，且不干扰其他类型状态', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      ws.emit({ type: 'twin-state-sync', sysid: 3, data: { sysId: 3 } })
    })

    expect(result.current.twinStates[3].receivedAt).toBeGreaterThan(0)
    // 其他状态未被误写
    expect(result.current.cellTowerData).toBeNull()
    expect(Object.keys(result.current.telemetryHistory)).toHaveLength(0)
  })

  it('data 缺失（异常帧）不炸：存空对象壳，面板侧归一化为全 null 行', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      ws.emit({ type: 'twin-state-sync', sysid: 4 })
    })

    // hook 层不丢帧（保留 receivedAt），归一化由消费侧兜底
    expect(result.current.twinStates[4].receivedAt).toBeGreaterThan(0)
    expect(normalizeTwinState(result.current.twinStates[4], result.current.twinStates[4].receivedAt)).not.toBeNull()
  })
})
