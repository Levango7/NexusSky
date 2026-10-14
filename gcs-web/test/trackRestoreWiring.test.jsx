/**
 * C4 前端接线的**验收**：走真实装载路径（useDrones.loadTelemetry），
 * 而不是只测工具函数存在。
 *
 * 为什么单独一个文件：trackRestore.test.js 测的是 mergeTrack 的纯逻辑；
 * 但本仓已被"共享件写了却没人调用"烧过（见 test/singleSourceOfTruth.test.js 的动机）。
 * 这里断言的是**症状**：后端重启后内存只剩一个点，前端仍应画出当天完整折线。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

// useDrones 用的是命名导出 `api` 这个对象（api.js:138 `export const api = {...}`），
// 所以 mock 工厂必须返回 { api: {...} }；把方法平铺在 namespace 上会让 hook 拿到 undefined。
vi.mock('../src/api.js', () => ({
  api: {
    listDrones: vi.fn(),
    getTelemetry: vi.fn(),
    getTrack: vi.fn(),
    getDayTrack: vi.fn(),
  },
  getWsUrl: () => 'ws://localhost:8080/ws/telemetry',
}))

const { api } = await import('../src/api.js')
const useDrones = (await import('../src/hooks/useDrones.js')).default

/**
 * 后端 GET /api/v1/drones/{sysid}/track 的真实形状（DroneViews.java:114-122）：
 * 只出 {lat, lon, alt, ts} 四个键——**没有** heading/batteryPct/timestampMs。
 * 所以区分"保留的是内存点还是库内点"只能用 alt（内存 100 / 库内 50）。
 */
const live = (ts, extra = {}) => ({
  lat: 30 + ts / 1e6, lon: 120 + ts / 1e6, alt: 100, ts, ...extra,
})

/** 后端 /flightlog/track 的点：控制器固定返回 {lat, lon, alt, ts}。 */
const stored = (ts) => ({ lat: 30 + ts / 1e6, lon: 120 + ts / 1e6, alt: 50, ts })

beforeEach(() => {
  api.listDrones.mockResolvedValue([{ sysid: 7, online: true }])
  api.getTelemetry.mockResolvedValue({ sysid: 7 })
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('loadTelemetry：内存轨迹 + 当日库内历史', () => {
  it('重启场景（内存 1 点 / 库内 5 点）⇒ 前端拿到 5 点，症状消失', async () => {
    // 后端重启后 loadLastKnownPositions 只会塞一个点
    api.getTrack.mockResolvedValue([live(5000)])
    api.getDayTrack.mockResolvedValue([stored(1000), stored(2000), stored(3000), stored(4000), stored(5000)])

    const { result } = renderHook(() => useDrones())
    await act(async () => { await result.current.loadTelemetry(7) })

    await waitFor(() => expect(result.current.track).toHaveLength(5))
    expect(result.current.track.map((x) => x.ts)).toEqual([1000, 2000, 3000, 4000, 5000])
  })

  it('同一 ts 保留内存点，不被库内点覆盖（用 alt 分辨：内存 100 / 库内 50）', async () => {
    api.getTrack.mockResolvedValue([live(4000)])
    api.getDayTrack.mockResolvedValue([stored(1000), stored(4000)])

    const { result } = renderHook(() => useDrones())
    await act(async () => { await result.current.loadTelemetry(7) })

    expect(result.current.track).toHaveLength(2)
    const at4000 = result.current.track.find((x) => x.ts === 4000)
    expect(at4000.alt).toBe(100)          // 来自内存；若被库内点顶掉就是 50
  })

  it('历史通路单独失败时，实时轨迹不能被抹成空（现在整段 catch 会把 track 清空）', async () => {
    api.getTrack.mockResolvedValue([live(1000), live(2000)])
    api.getDayTrack.mockRejectedValue(new Error('flight log 不可用'))

    const { result } = renderHook(() => useDrones())
    await act(async () => { await result.current.loadTelemetry(7) })

    expect(result.current.track.map((x) => x.ts)).toEqual([1000, 2000])
  })

  it('两条通路都真的被调用（防"mergeTrack 实现了但没人接"）', async () => {
    api.getTrack.mockResolvedValue([])
    api.getDayTrack.mockResolvedValue([])

    const { result } = renderHook(() => useDrones())
    await act(async () => { await result.current.loadTelemetry(7) })

    expect(api.getTrack).toHaveBeenCalledWith(7)
    expect(api.getDayTrack).toHaveBeenCalled()
  })

  it('合并后再来实时点：追加到尾部，且不越过窗口上限', async () => {
    api.getTrack.mockResolvedValue([live(2000)])
    api.getDayTrack.mockResolvedValue([stored(1000)])

    const { result } = renderHook(() => useDrones())
    await act(async () => { await result.current.loadTelemetry(7) })

    act(() => { result.current.appendTrackPoint({ lat: 31, lon: 121, alt: 120, ts: 3000 }) })

    expect(result.current.track.map((x) => x.ts)).toEqual([1000, 2000, 3000])
  })
})
