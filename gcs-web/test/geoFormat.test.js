import { describe, it, expect } from 'vitest'
import { haversine, circularLayout, EARTH_RADIUS_M } from '../src/utils/geo'
import { fmtDur, fmtPct, fmtMs, gradeColor, GRADE_OK, GRADE_WARN, GRADE_BAD, GRADE_UNKNOWN } from '../src/utils/format'

// 广州市中心（23.1291, 113.2644）
const GZ = { lat: 23.1291, lon: 113.2644 }
// 深圳市中心（22.5431, 114.0579）
const SZ = { lat: 22.5431, lon: 114.0579 }

describe('geo: haversine', () => {
  it('同一点距离为 0', () => {
    expect(haversine(GZ, GZ)).toBe(0)
  })

  it('对称：a→b 与 b→a 同值', () => {
    const b = { lat: 22.6, lon: 113.95 }
    expect(haversine(GZ, b)).toBeCloseTo(haversine(b, GZ), 6)
  })

  it('1 度纬度 ≈ 111.19 km', () => {
    const d = haversine({ lat: 0, lon: 0 }, { lat: 1, lon: 0 })
    expect(d / 1000).toBeCloseTo(111.19, 1)
  })

  it('1 度经度在赤道上也 ≈ 111.19 km（经度随纬度收缩）', () => {
    const d = haversine({ lat: 0, lon: 0 }, { lat: 0, lon: 1 })
    expect(d / 1000).toBeCloseTo(111.19, 1)
    // 到 60 度经度应显著变短（约一半）
    const d60 = haversine({ lat: 60, lon: 0 }, { lat: 60, lon: 1 })
    expect(d60).toBeLessThan(d * 0.6)
  })

  it('广州→深圳约 100 km 量级', () => {
    const km = haversine(GZ, SZ) / 1000
    expect(km).toBeGreaterThan(90)
    expect(km).toBeLessThan(120)
  })

  it('反极点距离约等于半周长', () => {
    const d = haversine({ lat: 0, lon: 0 }, { lat: 180, lon: 0 })
    expect(d).toBeCloseTo(Math.PI * EARTH_RADIUS_M, 0)
  })
})

describe('geo: circularLayout', () => {
  it('空列表返回空对象', () => {
    expect(circularLayout([], 100, 100, 50)).toEqual({})
  })

  it('单节点落在圆心', () => {
    const l = circularLayout([{ sysid: 7 }], 100, 200, 50)
    expect(l[7]).toEqual({ x: 100, y: 200 })
  })

  it('4 节点构成正方形，第一个在正上方', () => {
    const nodes = [{ sysid: 1 }, { sysid: 2 }, { sysid: 3 }, { sysid: 4 }]
    const l = circularLayout(nodes, 0, 0, 10)
    // 起始角 -π/2 → (cos, sin) = (0, -1) → 位于正上方（SVG y 轴向下）
    expect(l[1].x).toBeCloseTo(0, 6)
    expect(l[1].y).toBeCloseTo(-10, 6)
  })

  it('所有节点都落在圆周上（到圆心距离 = radius）', () => {
    const nodes = Array.from({ length: 7 }, (_, i) => ({ sysid: i + 1 }))
    const l = circularLayout(nodes, 30, 40, 25)
    for (let i = 1; i <= 7; i++) {
      const d = Math.hypot(l[i].x - 30, l[i].y - 40)
      expect(d).toBeCloseTo(25, 6)
    }
  })

  it('节点间角距均匀', () => {
    const nodes = Array.from({ length: 6 }, (_, i) => ({ sysid: i + 1 }))
    const l = circularLayout(nodes, 0, 0, 10)
    const angles = Object.values(l).map((p) => Math.atan2(p.y, p.x))
    const sorted = angles.slice().sort((a, b) => a - b)
    for (let i = 1; i < sorted.length; i++) {
      const gap = sorted[i] - sorted[i - 1]
      expect(gap).toBeCloseTo((2 * Math.PI) / 6, 6)
    }
  })

  it('以 sysid 为键，键互不覆盖', () => {
    const nodes = [{ sysid: 1 }, { sysid: 1 }] // 重复 sysid
    const l = circularLayout(nodes, 0, 0, 10)
    expect(Object.keys(l)).toHaveLength(1)
  })
})

describe('format: fmtDur', () => {
  it('正常换算', () => {
    expect(fmtDur(0.4)).toBe('00:00')
    expect(fmtDur(1)).toBe('00:01')
    expect(fmtDur(59)).toBe('00:59')
    expect(fmtDur(60)).toBe('01:00')
    expect(fmtDur(61)).toBe('01:01')
    expect(fmtDur(600)).toBe('10:00')
    expect(fmtDur(3661)).toBe('61:01') // 分钟不封顶
  })

  it('秒数向下取整（不四舍五入）', () => {
    expect(fmtDur(59.9)).toBe('00:59')
  })

  it('0 与负数与空值都显示为 --:--，不显示成已飞 0 秒', () => {
    expect(fmtDur(0)).toBe('--:--')
    expect(fmtDur(-1)).toBe('--:--')
    expect(fmtDur(null)).toBe('--:--')
    expect(fmtDur(undefined)).toBe('--:--')
  })
})

describe('format: fmtPct', () => {
  it('比例值转百分比，保留一位小数', () => {
    expect(fmtPct(0)).toBe('0.0%')
    expect(fmtPct(1)).toBe('100.0%')
    expect(fmtPct(0.856)).toBe('85.6%')
  })

  it('空值显示 --', () => {
    expect(fmtPct(null)).toBe('--')
    expect(fmtPct(undefined)).toBe('--')
  })
})

describe('format: fmtMs', () => {
  it('毫秒保留一位小数', () => {
    expect(fmtMs(0)).toBe('0.0 ms')
    expect(fmtMs(12.34)).toBe('12.3 ms')
  })

  it('空值显示 --', () => {
    expect(fmtMs(null)).toBe('--')
    expect(fmtMs(undefined)).toBe('--')
  })
})

describe('format: gradeColor', () => {
  it('越大越好：达到 good 为绿', () => {
    expect(gradeColor(0.9, 0.85, 0.7, false)).toBe(GRADE_OK)
    expect(gradeColor(0.85, 0.85, 0.7, false)).toBe(GRADE_OK) // 边界算达标
  })

  it('越大越好：介于 good 与 warn 为黄', () => {
    expect(gradeColor(0.8, 0.85, 0.7, false)).toBe(GRADE_WARN)
    expect(gradeColor(0.7, 0.85, 0.7, false)).toBe(GRADE_WARN) // 边界算临界
  })

  it('越大越好：低于 warn 为红', () => {
    expect(gradeColor(0.5, 0.85, 0.7, false)).toBe(GRADE_BAD)
  })

  it('越小越好：达到 good 为绿', () => {
    expect(gradeColor(0.1, 0.15, 0.3, true)).toBe(GRADE_OK)
    expect(gradeColor(0.15, 0.15, 0.3, true)).toBe(GRADE_OK)
  })

  it('越小越好：介于 good 与 warn 为黄，超过 warn 为红', () => {
    expect(gradeColor(0.2, 0.15, 0.3, true)).toBe(GRADE_WARN)
    expect(gradeColor(0.3, 0.15, 0.3, true)).toBe(GRADE_WARN)
    expect(gradeColor(0.5, 0.15, 0.3, true)).toBe(GRADE_BAD)
  })

  it('无数据显示暗色，不是绿也不是红', () => {
    expect(gradeColor(null, 0.85, 0.7, false)).toBe(GRADE_UNKNOWN)
    expect(gradeColor(undefined, 0.85, 0.7, true)).toBe(GRADE_UNKNOWN)
  })

  it('0 对"越小越好"是达标，对"越大越好"是不达标——方向确实生效', () => {
    expect(gradeColor(0, 0.15, 0.3, true)).toBe(GRADE_OK)
    expect(gradeColor(0, 0.85, 0.7, false)).toBe(GRADE_BAD)
  })
})
