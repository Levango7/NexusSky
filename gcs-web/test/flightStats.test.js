import { describe, it, expect } from 'vitest'
import { flightStatsOf, fmtMeters } from '../src/utils/flightStats.js'
import { EARTH_RADIUS_M } from '../src/utils/geo.js'

// 这批断言钉的是 GAPS §八-2 那类错误的**方向**：
// "算不出来"与"结果真的是 0"必须是两个不同的值，否则界面上永远读不出区别。

const p = (lat, lon, alt, ts) => ({ lat, lon, alt, ts })

describe('flightStatsOf：0 与"没有数据"是两件事', () => {
  it('轨迹为空数组 ⇒ maxAlt 是 null，不是 0', () => {
    const s = flightStatsOf([])
    expect(s.maxAlt).toBeNull()
    // 反向钉住回归方向：将来谁图省事把 null 改回 0，这里必须红
    expect(s.maxAlt).not.toBe(0)
    expect(s.dist).toBe(0)
    expect(s.dur).toBe(0)
  })

  it('轨迹只有一个点 ⇒ 同样无法计算 maxAlt（一个点没有"最高"可言）', () => {
    expect(flightStatsOf([p(30, 120, 55, 1000)]).maxAlt).toBeNull()
  })

  it('track 是 null / undefined ⇒ 返回可判空的三元组而不是抛', () => {
    for (const bad of [null, undefined]) {
      const s = flightStatsOf(bad)
      expect(s.maxAlt).toBeNull()
      expect(s.dist).toBe(0)
      expect(s.dur).toBe(0)
    }
  })

  it('非数组（字符串/对象）也不抛 —— 上游 WS 帧形状不可信', () => {
    for (const junk of ['30,120', { lat: 30 }, 42]) {
      expect(flightStatsOf(junk).maxAlt).toBeNull()
    }
  })

  it('贴地飞行（全程 alt=0）⇒ maxAlt 真的是 0，且展示成 `0 m` 而不是 `--`', () => {
    const s = flightStatsOf([p(30, 120, 0, 0), p(30, 120.001, 0, 1000)])
    expect(s.maxAlt).toBe(0)
    expect(fmtMeters(s.maxAlt)).toBe('0 m')
  })

  it('maxAlt 取的是全程最大而不是最后一点', () => {
    const s = flightStatsOf([
      p(30, 120, 10, 0),
      p(30, 120.001, 87.5, 1000),
      p(30, 120.002, 40, 2000),
    ])
    expect(s.maxAlt).toBe(87.5)
  })

  it('alt 缺失的点按 0 参与取最大 —— 刻意保持旧实现语义，不在本批改字段口径', () => {
    const s = flightStatsOf([p(30, 120, undefined, 0), p(30, 120.001, 25, 1000)])
    expect(s.maxAlt).toBe(25)
    const allMissing = flightStatsOf([p(30, 120, undefined, 0), p(30, 120.001, null, 1000)])
    expect(allMissing.maxAlt).toBe(0)
  })

  it('时长 = 末点 ts 减首点 ts，单位秒（不是毫秒）', () => {
    const s = flightStatsOf([p(30, 120, 5, 1_700_000_000_000), p(30, 120.001, 6, 1_700_000_042_000)])
    expect(s.dur).toBe(42)
  })

  it('航程 = 逐段 haversine 累加后换算成公里（期望值现算，不写死数字）', () => {
    const s = flightStatsOf([p(0, 0, 1, 0), p(0, 1, 1, 1000)])
    expect(s.dist).toBeCloseTo((EARTH_RADIUS_M * Math.PI) / 180 / 1000, 6)
  })
})

describe('fmtMeters：只有真缺失才降级成 --', () => {
  it('null / undefined / NaN ⇒ `--`', () => {
    for (const v of [null, undefined, NaN]) expect(fmtMeters(v)).toBe('--')
  })

  it('0 ⇒ `0 m`（这就是原来那个 bug 的正例）', () => {
    expect(fmtMeters(0)).toBe('0 m')
    expect(fmtMeters(0, 2)).toBe('0.00 m')
  })

  it('取整与小数位都按 digits 走', () => {
    expect(fmtMeters(12.4)).toBe('12 m')
    expect(fmtMeters(12.4, 1)).toBe('12.4 m')
    expect(fmtMeters(-3.456, 2)).toBe('-3.46 m')
  })

  it('字符串数字也归一成数值显示（上游偶尔回字符串，不因此显示成 --）', () => {
    expect(fmtMeters('17')).toBe('17 m')
  })
})
