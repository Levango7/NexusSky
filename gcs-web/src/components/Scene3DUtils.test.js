/**
 * Scene3DUtils 纯函数单测（vitest）。
 *
 * geoTo3D 的坐标契约（Scene3D/Trajectory3D 共用，改错会让 3D 场景镜像翻转）：
 *  - 以 REF（无人机 home）为原点
 *  - 东为 +X，上为 +Y，南为 +Z（即北为 -Z）
 *  - 1 米 = SCALE(0.05) 个 Three.js 单位
 *
 * 距离换算独立于实现公式验证：0.001° 纬度 ≈ 111.32 m（M_PER_DEG_LAT 常数），
 * 而不是把实现公式原样抄一遍（那会是同义反复）。
 */
import { describe, it, expect } from 'vitest'
import { REF, SCALE, M_PER_DEG_LAT, M_PER_DEG_LON, geoTo3D } from './Scene3DUtils'

describe('M_PER_DEG_LON 常数', () => {
  it('等于 111320 × cos(REF 纬度)', () => {
    expect(M_PER_DEG_LON).toBeCloseTo(111320 * Math.cos((REF.lat * Math.PI) / 180), 6)
  })
})

describe('geoTo3D', () => {
  it('REF 点映射为原点 (0,0,0)', () => {
    const p = geoTo3D(REF.lat, REF.lon, 0)
    // toBeCloseTo 而非 toBe：-(lat-REF.lat) 会产生 -0，Object.is(-0,0) 为 false
    expect(p.x).toBeCloseTo(0, 9)
    expect(p.y).toBeCloseTo(0, 9)
    expect(p.z).toBeCloseTo(0, 9)
  })

  it('alt 缺省 / null 归 0，不产生 NaN', () => {
    expect(geoTo3D(REF.lat, REF.lon).y).toBe(0)
    expect(geoTo3D(REF.lat, REF.lon, null).y).toBe(0)
    expect(geoTo3D(REF.lat, REF.lon, 0).y).toBe(0)
  })

  it('东为 +X：经度偏东 0.001° ≈ (0.001×M_PER_DEG_LON) 米 × SCALE', () => {
    const p = geoTo3D(REF.lat, REF.lon + 0.001, 0)
    expect(p.x).toBeGreaterThan(0)
    expect(p.x).toBeCloseTo(0.001 * M_PER_DEG_LON * SCALE, 9)
    expect(p.z).toBeCloseTo(0, 9)
  })

  it('北为 -Z：纬度偏北 0.001° ≈ 111.32 m → z ≈ -5.566', () => {
    const p = geoTo3D(REF.lat + 0.001, REF.lon, 0)
    // 111.32 m（每 0.001° 纬度）× 0.05 units/m
    expect(p.z).toBeCloseTo(-(0.001 * M_PER_DEG_LAT * SCALE), 9)
    expect(p.z).toBeCloseTo(-5.566, 3)
    expect(p.x).toBeCloseTo(0, 9)
  })

  it('上为 +Y：alt 100 m → y = 100 × SCALE = 5', () => {
    expect(geoTo3D(REF.lat, REF.lon, 100).y).toBeCloseTo(100 * SCALE, 9)
    expect(geoTo3D(REF.lat, REF.lon, 100).y).toBeCloseTo(5, 9)
  })

  it('象限一致性：东北方向 → x>0 且 z<0', () => {
    const p = geoTo3D(REF.lat + 0.0005, REF.lon + 0.0005, 10)
    expect(p.x).toBeGreaterThan(0)
    expect(p.z).toBeLessThan(0)
    expect(p.y).toBeGreaterThan(0)
  })
})
