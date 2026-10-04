import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import useWebSocket from '../src/hooks/useWebSocket.js'
import {
  normalizeDecisionEvent,
  normalizeAdaptivePath,
  normalizeEdgeTaskStatus,
  DECISION_TYPE_META,
  DECISION_REASON_META,
  ADJUST_REASON_META,
  EDGE_TASK_TYPE_META,
  EDGE_TASK_STATUS_META,
} from '../src/utils/aiDecision.js'

// ---- 纯函数：三帧归一化 ----

describe('normalizeDecisionEvent — DECISION_EVENT(30051) 归一化', () => {
  it('全字段换算：已知码 → 标签/颜色，confidence 0.0-1.0 → 百分比', () => {
    const raw = {
      triggerValue: 10.5,
      confidence: 0.92,
      timestamp: 1760000000000,
      sysId: 7,
      decisionType: 2, // ADAPT_PATH
      reason: 3,       // strong wind
    }
    const e = normalizeDecisionEvent(raw, 123)
    expect(e.sysid).toBe(7)
    expect(e.decisionType).toBe(2)
    expect(e.decisionTypeLabel).toBe(DECISION_TYPE_META[2].label)
    expect(e.decisionTypeColor).toBe(DECISION_TYPE_META[2].color)
    expect(e.reasonLabel).toBe(DECISION_REASON_META[3])
    expect(e.triggerValue).toBe(10.5)
    expect(e.confidence).toBe(0.92)
    expect(e.confidencePct).toBe(92)
    expect(e.sourceTimestamp).toBe(1760000000000)
    expect(e.receivedAt).toBe(123)
  })

  it('未知码回退：decisionType/reason 99 → "码 99"，颜色回退 --dim', () => {
    const e = normalizeDecisionEvent({ decisionType: 99, reason: 99, sysId: 1 }, 0)
    expect(e.decisionTypeLabel).toBe('码 99')
    expect(e.decisionTypeColor).toBe('var(--dim)')
    expect(e.reasonLabel).toBe('码 99')
  })

  it('字段缺失 → null 而非 NaN/异常', () => {
    const e = normalizeDecisionEvent({}, 0)
    expect(e).not.toBeNull()
    expect(e.sysid).toBeNull()
    expect(e.decisionType).toBeNull()
    expect(e.decisionTypeLabel).toBeNull()
    expect(e.reasonLabel).toBeNull()
    expect(e.triggerValue).toBeNull()
    expect(e.confidencePct).toBeNull()
    expect(e.sourceTimestamp).toBeNull()
  })

  it('非对象输入（null/数字/字符串）→ null，不抛异常', () => {
    expect(normalizeDecisionEvent(null, 0)).toBeNull()
    expect(normalizeDecisionEvent(undefined, 0)).toBeNull()
    expect(normalizeDecisionEvent(42, 0)).toBeNull()
    expect(normalizeDecisionEvent('x', 0)).toBeNull()
  })
})

describe('normalizeAdaptivePath — ADAPTIVE_PATH(30052) 归一化', () => {
  it('全字段换算：1E7 度 / cdeg→deg / 原因码标签', () => {
    const raw = {
      newLat: 225900000,        // 22.59°
      newLon: 1139300000,       // 113.93°
      windSpeed: 5.25,
      originalWaypointSeq: 3,
      newAlt: 120,
      windDirection: 9000,      // 90°
      sysId: 7,
      adjustmentReason: 0,      // 风场
    }
    const p = normalizeAdaptivePath(raw, 321)
    expect(p.lat).toBeCloseTo(22.59, 6)
    expect(p.lon).toBeCloseTo(113.93, 6)
    expect(p.altM).toBe(120)
    expect(p.windSpeed).toBe(5.25)
    expect(p.windDirDeg).toBeCloseTo(90, 6)
    expect(p.originalWaypointSeq).toBe(3)
    expect(p.reasonLabel).toBe(ADJUST_REASON_META[0])
    expect(p.sysid).toBe(7)
    expect(p.receivedAt).toBe(321)
  })

  it('风向 40000cdeg（400°）归一到 40°；未知原因码回退 "码 N"', () => {
    const p = normalizeAdaptivePath({ newLat: 0, newLon: 0, windDirection: 40000, adjustmentReason: 99 }, 0)
    expect(p.windDirDeg).toBeCloseTo(40, 6)
    expect(p.reasonLabel).toBe('码 99')
  })

  it('字段缺失 → null；非对象输入 → null', () => {
    const p = normalizeAdaptivePath({}, 0)
    expect(p).not.toBeNull()
    expect(p.lat).toBeNull()
    expect(p.lon).toBeNull()
    expect(p.altM).toBeNull()
    expect(p.windDirDeg).toBeNull()
    expect(p.originalWaypointSeq).toBeNull()
    expect(p.reasonLabel).toBeNull()
    expect(normalizeAdaptivePath(null, 0)).toBeNull()
    expect(normalizeAdaptivePath(42, 0)).toBeNull()
  })
})

describe('normalizeEdgeTaskStatus — EDGE_TASK_STATUS(30053) 归一化', () => {
  it('全字段换算：任务类型/状态标签 + 状态颜色', () => {
    const raw = {
      edgeTaskId: 45,
      processingTimeMs: 32.5,
      resultSize: 128,
      sysId: 7,
      taskType: 0,   // 视频分析
      status: 2,     // 完成
    }
    const t = normalizeEdgeTaskStatus(raw, 99)
    expect(t.taskId).toBe(45)
    expect(t.processingMs).toBe(32.5)
    expect(t.resultSize).toBe(128)
    expect(t.taskTypeLabel).toBe(EDGE_TASK_TYPE_META[0])
    expect(t.statusLabel).toBe(EDGE_TASK_STATUS_META[2].label)
    expect(t.statusColor).toBe(EDGE_TASK_STATUS_META[2].color)
    expect(t.sysid).toBe(7)
    expect(t.receivedAt).toBe(99)
  })

  it('未知状态码 99 → "码 99" + 颜色回退 --dim；字段缺失 → null', () => {
    const t = normalizeEdgeTaskStatus({ status: 99, taskType: 99 }, 0)
    expect(t.statusLabel).toBe('码 99')
    expect(t.statusColor).toBe('var(--dim)')
    expect(t.taskTypeLabel).toBe('码 99')
    const empty = normalizeEdgeTaskStatus({}, 0)
    expect(empty.taskId).toBeNull()
    expect(empty.statusLabel).toBeNull()
    expect(empty.processingMs).toBeNull()
  })

  it('非对象输入 → null，不抛异常', () => {
    expect(normalizeEdgeTaskStatus(null, 0)).toBeNull()
    expect(normalizeEdgeTaskStatus('x', 0)).toBeNull()
  })
})

// ---- hook 集成：useWebSocket 三帧分发 / 头插 / 50 条上限 ----

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

describe('useWebSocket — M11 三帧事件流', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('三帧各入各的数组，头插（最新在前），带 sysid + receivedAt', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      ws.emit({ type: 'decision-event', sysid: 1, data: { sysId: 1, decisionType: 0, reason: 0, confidence: 0.8 } })
      ws.emit({ type: 'adaptive-path', sysid: 1, data: { sysId: 1, newLat: 225900000, adjustmentReason: 0 } })
      ws.emit({ type: 'edge-task-status', sysid: 2, data: { sysId: 2, taskType: 1, status: 2 } })
      ws.emit({ type: 'decision-event', sysid: 2, data: { sysId: 2, decisionType: 3, reason: 1, confidence: 0.9 } })
    })

    expect(result.current.decisionEvents).toHaveLength(2)
    // 最新帧在前
    expect(result.current.decisionEvents[0].decisionType).toBe(3)
    expect(result.current.decisionEvents[0].sysid).toBe(2)
    expect(result.current.decisionEvents[0].receivedAt).toBeGreaterThan(0)
    expect(result.current.decisionEvents[1].decisionType).toBe(0)

    expect(result.current.adaptivePaths).toHaveLength(1)
    expect(result.current.adaptivePaths[0].newLat).toBe(225900000)

    expect(result.current.edgeTasks).toHaveLength(1)
    expect(result.current.edgeTasks[0].taskType).toBe(1)
  })

  it('50 条上限：连发 52 帧只留最近 50 条（最老 2 条丢弃）', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      for (let i = 0; i < 52; i++) {
        ws.emit({ type: 'decision-event', sysid: 1, data: { sysId: 1, decisionType: 0, triggerValue: i } })
      }
    })

    const events = result.current.decisionEvents
    expect(events).toHaveLength(50)
    // 头插：最新（i=51）在前，最老保留的是 i=2
    expect(events[0].triggerValue).toBe(51)
    expect(events[49].triggerValue).toBe(2)
  })

  it('data 缺失（异常帧）不炸：存壳 + receivedAt，归一化侧兜底；不干扰其他状态', () => {
    const { result } = mountHook(1)
    const ws = latestSocket()

    act(() => {
      ws.emit({ type: 'decision-event', sysid: 4 })
      ws.emit({ type: 'adaptive-path', sysid: 4 })
      ws.emit({ type: 'edge-task-status', sysid: 4 })
    })

    // hook 层不丢帧（保留 receivedAt），归一化由消费侧兜底
    expect(result.current.decisionEvents[0].receivedAt).toBeGreaterThan(0)
    expect(result.current.adaptivePaths[0].receivedAt).toBeGreaterThan(0)
    expect(result.current.edgeTasks[0].receivedAt).toBeGreaterThan(0)
    expect(normalizeDecisionEvent(result.current.decisionEvents[0], 0)).not.toBeNull()
    // 其他状态未被误写
    expect(result.current.twinStates).toEqual({})
    expect(result.current.alerts).toHaveLength(0)
    expect(result.current.cellTowerData).toBeNull()
  })
})
